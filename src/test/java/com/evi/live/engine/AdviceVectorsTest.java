package com.evi.live.engine;

import static com.evi.live.engine.EngineJson.check;
import static com.evi.live.engine.EngineJson.isNullish;
import static com.evi.live.engine.EngineJson.isUndefined;
import static com.evi.live.engine.EngineJson.num;
import static com.evi.live.engine.EngineJson.number;
import static com.evi.live.engine.EngineJson.numOrNull;
import static com.evi.live.engine.EngineJson.put;

import com.evi.live.journal.Offer;
import com.evi.live.market.HourBucket;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * PHASE 4 PARITY: every advice and safety-check function the Java engine ports, against what the REAL bridge JS answered
 * (tools/parity-advice-vectors.mjs): each input the JS tests pass them, ~7,400 generated edge cases at every threshold
 * (0.005 / 0.015 / 0.05 gaps, 15 and 30 minutes, 72 hours, a 10% cadence, a 50% recurrence, below / at / above break-even,
 * absent cost basis, zero fills, rounding ties), and scripted crash-watch sequences through the state machine behind
 * {@code isCrashing}. Zero divergences, or it fails. Synthetic data only.
 *
 * <p>The comparison is EXACT ({@link EngineJson#diff}): a number must be the very double the JS computed, a string
 * character for character -- the hedged wording is the product.
 */
public final class AdviceVectorsTest {
  private static final List<String> diffs = new ArrayList<>();
  private static final Map<String, Integer> compared = new TreeMap<>();
  private static JsonArray shared;

  public static void main(String[] args) throws Exception {
    JsonObject doc = EngineJson.read("/parity/advice-vectors.json.gz").getAsJsonObject();
    check("evi-advice-vectors/1".equals(doc.get("format").getAsString()), "unknown advice vector format");
    constants(doc.getAsJsonObject("constants"));
    shared = doc.getAsJsonArray("shared");
    int fromTests = 0;
    for (JsonElement ce : doc.getAsJsonArray("calls")) {
      JsonObject c = ce.getAsJsonObject();
      String fn = c.get("fn").getAsString();
      if (c.has("src")) fromTests++;
      JsonArray a = c.getAsJsonArray("args");
      JsonElement actual;
      try {
        actual = call(fn, a);
      } catch (RuntimeException e) {
        diffs.add(fn + " " + abbreviate(a) + ": threw " + e);
        continue;
      }
      for (String d : EngineJson.diff(c.get("result"), actual)) diffs.add(fn + " " + abbreviate(a) + ": " + d);
      compared.merge(fn, 1, Integer::sum);
    }
    int sequences = 0, steps = 0;
    for (JsonElement we : doc.getAsJsonArray("watch")) {
      JsonObject w = we.getAsJsonObject();
      JsonArray expected = w.getAsJsonArray("result"), actual = watch(w.getAsJsonObject("seq"));
      for (String d : EngineJson.diff(expected, actual)) diffs.add("watch sequence " + sequences + ": " + d);
      sequences++;
      steps += expected.size();
    }
    if (!diffs.isEmpty()) {
      StringBuilder sb = new StringBuilder(diffs.size() + " advice vector divergence(s) from the JS bridge:");
      for (String d : diffs.subList(0, Math.min(40, diffs.size()))) sb.append("\n  ").append(d);
      throw new AssertionError(sb.toString());
    }
    // Inert-test guards: the file must still reach every function, from the tests and from the generator.
    String[] fns = {"buildThinMarketIndex", "windowFor", "thinMarketNote", "thinMarketContext", "buildBaselines", "detectCrashes", "crashMessage", "crashNote",
      "measuredBandFor", "relistAdvice", "sellAdvice", "buyMarginAdvice", "buyProgressAdvice", "holdingsAdvice", "bandFor", "buildFillModel",
      "fillChance", "fillChanceSentence", "bucketAt", "predictedFillMinutes"};
    for (String f : fns) check(compared.getOrDefault(f, 0) >= 10, "too few vectors for " + f + ": " + compared);
    check(compared.get("relistAdvice") >= 1000 && compared.get("sellAdvice") >= 1000 && compared.get("thinMarketNote") >= 1000,
      "the threshold grids shrank: " + compared);
    check(fromTests >= 100, "too few calls recorded from the JS tests (" + fromTests + ")");
    check(sequences >= 9 && steps >= 40, "too few crash-watch sequences (" + sequences + ", " + steps + " steps)");
    int total = 0;
    for (int n : compared.values()) total += n;
    System.out.println("PASS: advice vectors vs the JS bridge, " + total + " calls (" + fromTests + " from the JS tests) over " + compared.size()
      + " functions and " + sequences + " crash-watch sequences (" + steps + " steps); 0 divergences, engine lock "
      + doc.getAsJsonObject("engineLock").get("combined").getAsString().substring(0, 16));
  }

  private static String abbreviate(JsonArray a) {
    String s = a.toString();
    return s.length() > 300 ? s.substring(0, 300) + "..." : s;
  }

  // ------------------------------------------------------------------------------------------- constants

  private static void constants(JsonObject c) {
    JsonObject t = c.getAsJsonObject("thinMarket");
    JsonArray w = t.getAsJsonArray("WINDOWS");
    check(w.size() == ThinMarket.WINDOWS.length, "WINDOWS");
    for (int i = 0; i < w.size(); i++) check(w.get(i).getAsInt() == ThinMarket.WINDOWS[i], "WINDOWS[" + i + "]");
    same(t, "MIN_HOURS", ThinMarket.MIN_HOURS);
    same(t, "THIN_RECURRENCE", ThinMarket.THIN_RECURRENCE);
    same(t, "THIN_CADENCE", ThinMarket.THIN_CADENCE);
    JsonObject crash = c.getAsJsonObject("crashWatch").getAsJsonObject("CRASH");
    same(crash, "z", CrashWatch.Z);
    same(crash, "minDrop", CrashWatch.MIN_DROP);
    same(crash, "volRatio", CrashWatch.VOL_RATIO);
    same(crash, "windowMinutes", CrashWatch.WINDOW_MINUTES);
    JsonObject m = c.getAsJsonObject("crashWatch").getAsJsonObject("CRASH_MEASURED");
    check(CrashWatch.MEASURED_ON.equals(m.get("measuredOn").getAsString()), "measuredOn");
    same(m, "days", CrashWatch.MEASURED_DAYS);
    same(m, "stillDownAfterHour", CrashWatch.STILL_DOWN_AFTER_HOUR);
    JsonArray bands = m.getAsJsonArray("bands");
    check(bands.size() == CrashWatch.BANDS.size(), "crash bands");
    for (int i = 0; i < bands.size(); i++) {
      JsonObject b = bands.get(i).getAsJsonObject();
      CrashWatch.Band j = CrashWatch.BANDS.get(i);
      check(j.label.equals(b.get("label").getAsString()) && j.n == b.get("n").getAsInt() && j.back == b.get("back").getAsDouble()
        && j.lower == b.get("lower").getAsDouble() && (b.get("max").isJsonNull() ? Double.isInfinite(j.max) : j.max == b.get("max").getAsDouble()), "crash band " + i);
    }
    JsonObject r = c.getAsJsonObject("relist");
    same(r, "RELIST_AFTER_SHARE", Relist.RELIST_AFTER_SHARE);
    same(r, "MIN_WAIT_MINUTES", Relist.MIN_WAIT_MINUTES);
    same(r, "MIN_GAP", Relist.MIN_GAP);
    same(r, "DRIFT_SPEAKS_NOW", Relist.DRIFT_SPEAKS_NOW);
    same(r, "PLUGIN_SPEAKS_ABOVE", Relist.PLUGIN_SPEAKS_ABOVE);
    same(r, "DRIFT_MIN_WAIT_MINUTES", Relist.DRIFT_MIN_WAIT_MINUTES);
    same(c.getAsJsonObject("sellAdvice"), "MIN_LOSS_SHARE", SellAdvice.MIN_LOSS_SHARE);
    same(c.getAsJsonObject("buyAdvice"), "MIN_MARGIN_SHARE", BuyAdvice.MIN_MARGIN_SHARE);
    same(c.getAsJsonObject("holdingsAdvice"), "MAX_LINES", HoldingsAdvice.MAX_LINES);
    JsonObject f = c.getAsJsonObject("fillModel");
    same(f, "MIN_SAMPLES", FillModel.MIN_SAMPLES);
    JsonArray fb = f.getAsJsonArray("SHARE_BANDS");
    check(fb.size() == FillModel.SHARE_BANDS.size(), "fill bands");
    for (int i = 0; i < fb.size(); i++) {
      JsonObject b = fb.get(i).getAsJsonObject();
      check(FillModel.SHARE_BANDS.get(i).label.equals(b.get("label").getAsString()) && FillModel.SHARE_BANDS.get(i).max == num(b.get("max")), "fill band " + i);
    }
  }

  private static void same(JsonObject o, String k, double v) {
    check(num(o.get(k)) == v, "constant " + k + ": the JS has " + o.get(k) + ", the port " + v);
  }

  // ------------------------------------------------------------------------------------------- decoding

  private static JsonElement resolve(JsonElement e) {
    if (e != null && e.isJsonObject() && e.getAsJsonObject().has("$ref")) return shared.get(e.getAsJsonObject().get("$ref").getAsInt());
    return e;
  }

  private static JsonElement arg(JsonArray a, int i) {
    return i < a.size() ? resolve(a.get(i)) : null;
  }

  private static JsonElement field(JsonElement o, String k) {
    return isNullish(o) || !o.isJsonObject() ? null : o.getAsJsonObject().get(k);
  }

  private static String str(JsonElement e) {
    return isNullish(e) ? null : e.getAsString();
  }

  /** A JSON string as itself; anything else (absent, null, a number) as null -- JS's `typeof x === 'string'`. */
  private static String stringOrNull(JsonElement e) {
    return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
  }

  private static Integer intOrNull(JsonElement e) {
    if (isNullish(e)) return null;
    double d = num(e);
    return JsValues.isInteger(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (int) d : null;
  }

  private static int intOf(JsonElement e) {
    Integer i = intOrNull(e);
    check(i != null, "not an item id: " + e);
    return i;
  }

  private static Long longOrNull(JsonElement e) {
    return isNullish(e) ? null : JsValues.exactLong(num(e));
  }

  private static long longOr0(JsonElement e) {
    return isNullish(e) ? 0 : JsValues.exactLong(num(e));
  }

  /** A $set (or a plain list) of item ids; null for undefined. */
  private static Set<Integer> idSet(JsonElement e) {
    if (isNullish(e)) return null;
    JsonArray a = e.isJsonObject() ? e.getAsJsonObject().getAsJsonArray("$set") : e.getAsJsonArray();
    Set<Integer> s = new LinkedHashSet<>();
    for (JsonElement x : a) s.add(intOf(x));
    return s;
  }

  /** A $map of item id to number (null values kept as null). */
  private static Map<Integer, Double> idMap(JsonElement e) {
    Map<Integer, Double> m = new LinkedHashMap<>();
    if (isNullish(e)) return m;
    for (JsonElement p : e.getAsJsonObject().getAsJsonArray("$map")) {
      JsonArray kv = p.getAsJsonArray();
      m.put(intOf(kv.get(0)), numOrNull(kv.get(1)));
    }
    return m;
  }

  private static Map<Integer, AdviceNote.Price> prices(JsonElement e) {
    if (isNullish(e)) return null;
    Map<Integer, AdviceNote.Price> m = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
      JsonElement v = x.getValue();
      m.put(Integer.parseInt(x.getKey()), isNullish(v) ? null : new AdviceNote.Price(numOrNull(field(v, "buyPrice")), numOrNull(field(v, "sellPrice"))));
    }
    return m;
  }

  private static Map<Integer, Double> highs(JsonElement e) {
    if (isNullish(e)) return null;
    Map<Integer, Double> m = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
      Double h = numOrNull(field(x.getValue(), "high"));
      if (h != null) m.put(Integer.parseInt(x.getKey()), h);
    }
    return m;
  }

  /** {@code Number(x)} for an option read straight into arithmetic: undefined NaN, null 0. */
  private static double jsNumber(JsonElement e) {
    if (e != null && e.isJsonNull()) return 0;
    return num(e);
  }

  private static List<HourBucket> buckets(JsonElement e) throws IOException {
    return EngineJson.buckets(e);
  }

  private static ThinMarket.Stats stats(JsonElement e) {
    if (isNullish(e)) return null;
    Double[] rec = new Double[ThinMarket.WINDOWS.length], within = new Double[ThinMarket.WINDOWS.length];
    for (int i = 0; i < ThinMarket.WINDOWS.length; i++) {
      String k = String.valueOf(ThinMarket.WINDOWS[i]);
      rec[i] = numOrNull(field(field(e, "recurrence"), k));
      // unitsWithin is only ever ARITHMETIC (Math.round, <): JS reads a null there as 0 and a missing one as NaN. A reading
      // the index builds never has one without the other (both null when nothing was sampled), so only hand-made readings
      // can tell -- and the port must answer them as the JS does.
      JsonElement u = field(field(e, "unitsWithin"), k);
      within[i] = u != null && u.isJsonNull() ? Double.valueOf(0) : numOrNull(u);
    }
    return new ThinMarket.Stats(num(field(e, "hours")), num(field(e, "hoursTraded")), num(field(e, "cadence")), num(field(e, "unitsPerDay")), rec, within,
      num(field(e, "samples")));
  }

  private static CrashWatch.Baseline baseline(JsonElement v) {
    return new CrashWatch.Baseline(new CrashWatch.Side(num(field(field(v, "hi"), "base")), num(field(field(v, "hi"), "sd"))),
      new CrashWatch.Side(num(field(field(v, "lo"), "base")), num(field(field(v, "lo"), "sd"))), num(field(v, "hourlyUnits")));
  }

  private static CrashWatch.Crash crash(JsonElement c) {
    return new CrashWatch.Crash(intOf(field(c, "itemId")), num(field(c, "hi")), num(field(c, "lo")), num(field(c, "baseHi")), num(field(c, "baseLo")),
      num(field(c, "drop")), num(field(c, "units")), num(field(c, "normalPerHour")));
  }

  private static VolumeRow volume(JsonElement e) {
    if (isNullish(e)) return null;
    return new VolumeRow(HourBucket.NONE, longOr0(field(e, "highPriceVolume")), HourBucket.NONE, longOr0(field(e, "lowPriceVolume")));
  }

  private static FillModel.Model model(JsonElement e) {
    if (isNullish(e)) return null;
    List<FillModel.BandStats> bands = new ArrayList<>();
    for (JsonElement b : field(e, "bands").getAsJsonArray()) {
      List<FillModel.Sample> samples = new ArrayList<>();
      int filled = 0;
      for (JsonElement s : field(b, "samples").getAsJsonArray()) {
        boolean f = field(s, "filled").getAsBoolean();
        if (f) filled++;
        samples.add(new FillModel.Sample(num(field(s, "minutes")), f, bool(field(s, "gaveUp")), bool(field(s, "open"))));
      }
      bands.add(new FillModel.BandStats(field(b, "band").getAsString(), samples, samples.size(), filled, numOrNull(field(b, "median")), field(b, "enough").getAsBoolean()));
    }
    return new FillModel.Model(bands, 0);
  }

  private static boolean bool(JsonElement e) {
    return !isNullish(e) && e.getAsBoolean();
  }

  private static List<Offer> journalOffers(JsonElement e) {
    List<Offer> out = new ArrayList<>();
    int k = 0;
    for (JsonElement o : e.getAsJsonArray()) {
      Integer ticks = intOrNull(field(o, "ticksToFill"));
      out.add(new Offer(0, field(o, "state").getAsString(), "o" + (k++), intOf(field(o, "itemId")), null, longOr0(field(o, "price")),
        longOr0(field(o, "total")), longOr0(field(o, "filled")), longOr0(field(o, "spent")), true, ticks, null, null,
        longOrNull(field(o, "firstSeen")), longOrNull(field(o, "updated")), longOrNull(field(o, "completedAt")), null));
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------- calling

  private static JsonElement call(String fn, JsonArray a) throws IOException {
    switch (fn) {
      case "buildThinMarketIndex": {
        ThinMarket.Index idx = ThinMarket.build(buckets(arg(a, 0)));
        JsonObject j = new JsonObject();
        j.add("hours", number(idx.hours));
        JsonArray by = new JsonArray();
        for (Map.Entry<Integer, ThinMarket.Stats> e : idx.all().entrySet()) {
          JsonArray kv = new JsonArray();
          kv.add(number(e.getKey()));
          kv.add(statsJson(e.getValue()));
          by.add(kv);
        }
        j.add("byItem", by);
        return j;
      }
      case "windowFor":
        return number(ThinMarket.windowFor(numOrNull(arg(a, 0))));
      case "thinMarketNote": {
        JsonElement o = arg(a, 1);
        return text(ThinMarket.note(stats(arg(a, 0)), str(field(o, "name")), numOrNull(field(o, "windowHours")), numOrNull(field(o, "quantity")),
          numOrNull(field(o, "archivedHours"))));
      }
      case "thinMarketContext": {
        JsonElement o = arg(a, 1);
        return text(ThinMarket.context(stats(arg(a, 0)), numOrNull(field(o, "windowHours")), numOrNull(field(o, "quantity"))));
      }
      case "buildBaselines": {
        JsonArray out = new JsonArray();
        for (Map.Entry<Integer, CrashWatch.Baseline> e : CrashWatch.buildBaselines(buckets(arg(a, 0)), num(arg(a, 1))).entrySet()) {
          JsonArray kv = new JsonArray();
          kv.add(number(e.getKey()));
          kv.add(baselineJson(e.getValue()));
          out.add(kv);
        }
        return out;
      }
      case "detectCrashes": {
        Map<Integer, CrashWatch.Baseline> base = new LinkedHashMap<>();
        for (JsonElement p : arg(a, 0).getAsJsonObject().getAsJsonArray("$map")) base.put(intOf(p.getAsJsonArray().get(0)), baseline(p.getAsJsonArray().get(1)));
        JsonElement rules = arg(a, 2);
        List<CrashWatch.Crash> found = isUndefined(rules) ? CrashWatch.detectCrashes(base, buckets(arg(a, 1)))
          : CrashWatch.detectCrashes(base, buckets(arg(a, 1)), new CrashWatch.Rules(num(field(rules, "z")), num(field(rules, "minDrop")), num(field(rules, "volRatio")),
            num(field(rules, "windowMinutes"))));
        JsonArray out = new JsonArray();
        for (CrashWatch.Crash c : found) out.add(crashJson(c));
        return out;
      }
      case "crashMessage": {
        JsonElement o = arg(a, 1);
        return text(CrashWatch.crashMessage(crash(arg(a, 0)), str(field(o, "name")), str(field(o, "mine"))));
      }
      case "crashNote": {
        JsonElement o = arg(a, 1);
        CrashWatch.Note n = CrashWatch.crashNote(crash(arg(a, 0)), str(field(o, "name")), str(field(o, "mine")));
        JsonObject j = new JsonObject();
        j.add("message", text(n.message));
        j.add("detail", text(n.detail));
        return j;
      }
      case "measuredBandFor": {
        CrashWatch.Band b = CrashWatch.measuredBandFor(num(arg(a, 0)));
        return b == null ? JsonNull.INSTANCE : new JsonPrimitive(b.label);
      }
      case "relistAdvice": {
        JsonElement o = arg(a, 0);
        List<Relist.SellOffer> offers = null;
        JsonElement list = field(o, "offers");
        if (!isNullish(list)) {
          offers = new ArrayList<>();
          for (JsonElement x : list.getAsJsonArray())
            offers.add(isNullish(x) ? null : new Relist.SellOffer(intOf(field(x, "itemId")), str(field(x, "name")), num(field(x, "price")),
              num(field(x, "remaining")), numOrNull(field(x, "firstSeen"))));
        }
        JsonElement t = field(o, "targetDurationMinutes");
        double target = isUndefined(t) ? 1440 : jsNumber(t);
        return notes(Relist.relistAdvice(offers, prices(field(o, "prices")), idMap(field(o, "costBasis")), target, num(field(o, "now"))));
      }
      case "sellAdvice": {
        JsonElement o = arg(a, 0);
        List<SellAdvice.SellOffer> offers = null;
        JsonElement list = field(o, "offers");
        if (!isNullish(list)) {
          offers = new ArrayList<>();
          for (JsonElement x : list.getAsJsonArray())
            offers.add(isNullish(x) ? null : new SellAdvice.SellOffer(intOf(field(x, "itemId")), str(field(x, "name")), num(field(x, "price")), num(field(x, "remaining"))));
        }
        return notes(SellAdvice.sellAdvice(offers, idMap(field(o, "costBasis"))));
      }
      case "buyMarginAdvice": {
        JsonElement o = arg(a, 0);
        List<BuyAdvice.BuyOffer> offers = null;
        JsonElement list = field(o, "offers");
        if (!isNullish(list)) {
          offers = new ArrayList<>();
          for (JsonElement x : list.getAsJsonArray())
            offers.add(isNullish(x) ? null : new BuyAdvice.BuyOffer(intOf(field(x, "itemId")), str(field(x, "name")), num(field(x, "price")),
              num(field(x, "total")), num(field(x, "filled")), num(field(x, "spent"))));
        }
        return notes(BuyAdvice.buyMarginAdvice(offers, prices(field(o, "prices"))));
      }
      case "buyProgressAdvice": {
        JsonElement o = arg(a, 0);
        List<BuyProgress.BuyOffer> offers = new ArrayList<>();
        for (JsonElement x : field(o, "offers").getAsJsonArray())
          offers.add(isNullish(x) ? null : new BuyProgress.BuyOffer(intOrNull(field(x, "itemId")), str(field(x, "name")), num(field(x, "total")),
            num(field(x, "filled")), numOrNull(field(x, "firstSeen"))));
        Set<Integer> flagged = idSet(field(o, "alreadyFlagged"));
        JsonElement max = field(o, "max");
        return notes(BuyProgress.buyProgressAdvice(offers, numOrNull(field(o, "targetDurationMinutes")), flagged == null ? new HashSet<>() : flagged,
          num(field(o, "now")), isUndefined(max) ? 3 : jsNumber(max)));
      }
      case "holdingsAdvice": {
        JsonElement o = arg(a, 0);
        List<HoldingsAdvice.Position> positions = null;
        JsonElement list = field(o, "positions");
        if (!isNullish(list)) {
          positions = new ArrayList<>();
          for (JsonElement x : list.getAsJsonArray())
            positions.add(isNullish(x) ? null : new HoldingsAdvice.Position(intOrNull(field(x, "itemId")), str(field(x, "item")), num(field(x, "remaining")),
              num(field(x, "unitCost")), isNullish(field(field(x, "endedUnseen"), "price")) ? null : num(field(field(x, "endedUnseen"), "price")),
              stringOrNull(field(x, "buyId"))));
        }
        JsonElement max = field(o, "max");
        return notes(HoldingsAdvice.holdingsAdvice(positions, highs(field(o, "prices")), idSet(field(o, "listedItemIds")), intOrNull(field(o, "suggestedItemId")),
          isUndefined(max) ? HoldingsAdvice.MAX_LINES : jsNumber(max)));
      }
      case "bandFor":
        return number(FillModel.bandFor(num(arg(a, 0))));
      case "buildFillModel":
        return modelJson(FillModel.build(journalOffers(arg(a, 0)), buckets(arg(a, 1)), num(field(arg(a, 2), "now"))));
      case "fillChance": {
        FillModel.Chance c = FillModel.fillChance(model(arg(a, 0)), num(arg(a, 1)), volume(arg(a, 2)), num(arg(a, 3)));
        return chanceJson(c);
      }
      case "fillChanceSentence": {
        JsonElement c = arg(a, 0);
        FillModel.Chance chance = isNullish(c) ? null
          : new FillModel.Chance(num(field(c, "probability")), field(c, "samples").getAsInt(), numOrNull(field(c, "medianMinutes")), field(c, "band").getAsString());
        return text(FillModel.fillChanceSentence(chance, num(arg(a, 1))));
      }
      case "bucketAt": {
        HourBucket b = FillModel.bucketAt(buckets(arg(a, 0)), num(arg(a, 1)));
        if (b == null) return JsonNull.INSTANCE;
        JsonObject j = new JsonObject();
        j.add("ts", number(b.ts));
        return j;
      }
      case "predictedFillMinutes":
        return number(FillModel.predictedFillMinutes(num(arg(a, 0)), volume(arg(a, 1))));
      default:
        throw new AssertionError("no Java port wired for " + fn);
    }
  }

  // ------------------------------------------------------------------------------------------- writing answers

  private static JsonElement text(String s) {
    return s == null ? JsonNull.INSTANCE : new JsonPrimitive(s);
  }

  private static JsonElement statsJson(ThinMarket.Stats s) {
    JsonObject j = new JsonObject();
    j.add("hours", number(s.hours));
    j.add("hoursTraded", number(s.hoursTraded));
    j.add("cadence", number(s.cadence));
    j.add("unitsPerDay", number(s.unitsPerDay));
    JsonObject rec = new JsonObject(), within = new JsonObject();
    for (int w : ThinMarket.WINDOWS) {
      rec.add(String.valueOf(w), number(s.recurrence(w)));
      within.add(String.valueOf(w), number(s.unitsWithin(w)));
    }
    j.add("recurrence", rec);
    j.add("unitsWithin", within);
    j.add("samples", number(s.samples));
    return j;
  }

  private static JsonElement baselineJson(CrashWatch.Baseline b) {
    JsonObject j = new JsonObject(), hi = new JsonObject(), lo = new JsonObject();
    hi.add("base", number(b.hi.base));
    hi.add("sd", number(b.hi.sd));
    lo.add("base", number(b.lo.base));
    lo.add("sd", number(b.lo.sd));
    j.add("hi", hi);
    j.add("lo", lo);
    j.add("hourlyUnits", number(b.hourlyUnits));
    return j;
  }

  static JsonElement crashJson(CrashWatch.Crash c) {
    if (c == null) return JsonNull.INSTANCE;
    JsonObject j = new JsonObject();
    j.add("itemId", number(c.itemId));
    j.add("hi", number(c.hi));
    j.add("lo", number(c.lo));
    j.add("baseHi", number(c.baseHi));
    j.add("baseLo", number(c.baseLo));
    j.add("drop", number(c.drop));
    j.add("units", number(c.units));
    j.add("normalPerHour", number(c.normalPerHour));
    return j;
  }

  /** An advice note as the JS object would serialise: every field the module set, nothing else. */
  /** One note as the server sends it: the PRODUCTION serialiser's (ResponseJson). */
  static JsonElement note(AdviceNote n) {
    return ResponseJson.note(n);
  }

  static JsonArray notes(List<AdviceNote> list) {
    JsonArray a = new JsonArray();
    for (AdviceNote n : list) a.add(note(n));
    return a;
  }

  private static JsonElement modelJson(FillModel.Model m) {
    JsonObject j = new JsonObject();
    JsonArray bands = new JsonArray();
    for (FillModel.BandStats b : m.bands) {
      JsonObject o = new JsonObject();
      o.addProperty("band", b.band);
      JsonArray samples = new JsonArray();
      for (FillModel.Sample s : b.samples) {
        JsonObject x = new JsonObject();
        x.add("minutes", number(s.minutes));
        x.addProperty("filled", s.filled);
        if (s.gaveUp) x.addProperty("gaveUp", true);
        if (s.open) x.addProperty("open", true);
        samples.add(x);
      }
      o.add("samples", samples);
      o.add("count", number(b.count));
      o.add("filledCount", number(b.filledCount));
      o.add("median", number(b.median));
      o.addProperty("enough", b.enough);
      bands.add(o);
    }
    j.add("bands", bands);
    j.add("skipped", number(m.skipped));
    return j;
  }

  private static JsonElement chanceJson(FillModel.Chance c) {
    if (c == null) return JsonNull.INSTANCE;
    JsonObject j = new JsonObject();
    j.add("probability", number(c.probability));
    j.add("samples", number(c.samples));
    j.add("medianMinutes", number(c.medianMinutes));
    j.addProperty("band", c.band);
    return j;
  }

  // ------------------------------------------------------------------------------------------- the crash watch

  private static JsonArray watch(JsonObject seq) throws IOException {
    List<HourBucket> hourly = new ArrayList<>(buckets(seq.get("hourly")));
    // Archive the hourly reader does not see until an op with backfillLands (a backfill finishing while the bridge runs).
    List<HourBucket> backfill = seq.has("backfill") ? buckets(seq.get("backfill")) : new ArrayList<>();
    List<HourBucket> initial = seq.has("initial5m") ? buckets(seq.get("initial5m")) : new ArrayList<>();
    boolean[] throwing = {false};
    List<JsonObject> events = new ArrayList<>();
    CrashWatch.Watch w = new CrashWatch.Watch(from -> {
      if (throwing[0]) throw new IOException("scripted archive failure");
      List<HourBucket> out = new ArrayList<>();
      for (HourBucket b : hourly) if (b.ts >= from) out.add(b);
      return out;
    }, from -> {
      List<HourBucket> out = new ArrayList<>();
      for (HourBucket b : initial) if (b.ts >= from) out.add(b);
      return out;
    }, (event, at, crash, because) -> {
      JsonObject e = new JsonObject();
      e.addProperty("event", event);
      e.add("at", number(at));
      e.add("itemId", number(crash.itemId));
      if (because == null) e.add("because", JsonNull.INSTANCE);
      else e.addProperty("because", because);
      events.add(e);
    });
    JsonArray out = new JsonArray();
    for (JsonElement oe : seq.getAsJsonArray("ops")) {
      JsonObject op = oe.getAsJsonObject();
      throwing[0] = op.has("hourlyThrows") && op.get("hourlyThrows").getAsBoolean();
      if (op.has("backfillLands") && op.get("backfillLands").getAsBoolean()) {
        hourly.addAll(backfill);
        hourly.sort(java.util.Comparator.comparingLong(x -> x.ts));
        backfill = new ArrayList<>();
      }
      int before = events.size();
      JsonElement b = op.get("bucket");
      List<CrashWatch.Crash> found = w.addFiveMinute(isNullish(b) ? null : EngineJson.bucket(b), num(op.get("at")));
      JsonObject r = new JsonObject();
      JsonArray f = new JsonArray();
      for (CrashWatch.Crash c : found) f.add(crashJson(c));
      r.add("found", f);
      JsonObject crashing = new JsonObject();
      for (JsonElement id : seq.getAsJsonArray("items")) crashing.add(id.getAsString(), crashJson(w.isCrashing(id.getAsInt())));
      r.add("crashing", crashing);
      r.add("active", alerts(w.active()));
      r.add("recent", alerts(w.recent()));
      JsonObject status = new JsonObject();
      status.addProperty("watching", w.watching());
      status.add("windowBuckets", number(w.windowBuckets()));
      status.add("itemsWithBaseline", number(w.itemsWithBaseline()));
      r.add("status", status);
      JsonArray ev = new JsonArray();
      for (JsonObject e : events.subList(before, events.size())) ev.add(e);
      r.add("events", ev);
      out.add(r);
    }
    return out;
  }

  private static JsonArray alerts(List<CrashWatch.Watch.Alert> list) {
    JsonArray a = new JsonArray();
    for (CrashWatch.Watch.Alert x : list) {
      JsonObject j = new JsonObject();
      j.add("itemId", number(x.itemId));
      j.add("since", number(x.since));
      j.add("updated", number(x.updated));
      j.add("ended", x.ended == null ? JsonNull.INSTANCE : number(x.ended));
      j.add("endedBecause", x.endedBecause == null ? JsonNull.INSTANCE : new JsonPrimitive(x.endedBecause));
      j.add("crash", crashJson(x.crash));
      a.add(j);
    }
    return a;
  }
}
