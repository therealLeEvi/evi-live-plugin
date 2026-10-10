package com.evi.live.engine;

import com.evi.live.journal.CostBasis;
import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Js;
import com.evi.live.journal.Offer;
import com.evi.live.journal.Tax;
import com.evi.live.market.HourBucket;
import com.evi.live.market.LatestPrices;
import com.evi.live.market.WikiJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * The Java engine functions (com.evi.live.engine) against what the REAL JS engine answered, value for value: every
 * input the existing JS tests pass these functions (recorded through the real engine), about thirteen thousand
 * generated edge cases (.5 ties, -0, the int32/uint32 edges, 2^53 and past it, NaN, infinities, absent against zero,
 * empty and one-element lists, JS-only white space), the upper median through the real robustPrices, server.mjs's
 * limitFor closure, and the three tiers' cap ORDER through the real tier functions. Synthetic data only (recorded by
 * the project's private recorder, tools/parity-engine-vectors.mjs, and copied here gzipped).
 *
 * <p>EXACT EQUALITY. A number must be the very double the JS computed (a Java long is compared with that double's
 * exact value), a string character for character, a set in its insertion order. Absent, null and undefined are equal
 * (JSON.stringify drops undefined). Every expectation is a recorded JS answer; nothing here is hand-computed.
 *
 * <p>THE ONE DELIBERATE DIFFERENCE, made visible rather than hidden: slotExposure and heldCostBasis scope themselves to
 * one account (the PORT WARNING), so they are compared with the JS answer on the lists server.mjs actually passes
 * (filtered by ownedBy). The vectors where the bare JS function would have counted another account are counted, and
 * the count must be above zero, or the vectors no longer exercise the case the scoping exists for.
 */
public final class EngineVectorsTest {
  private static final List<String> diffs = new ArrayList<>();
  private static final Map<String, Integer> perFunction = new TreeMap<>();
  private static int compared;
  private static int scopedDivergences;
  // Cash text only JS reads as blank (Unicode white space Java's String.trim keeps), and 0x/0o/0b text whose value depends
  // on the radix (two or more digits, or an octal/binary digit past 1): each needs its own vectors or a port slip hides.
  private static int jsOnlyBlankCash, multiDigitRadixCash, fractionalRemainder;
  private static final java.util.regex.Pattern RADIX_MULTI_DIGIT = java.util.regex.Pattern.compile("[+-]?0[xXoObB][0-9a-fA-F]{2,}");

  public static void main(String[] args) throws Exception {
    JsonElement root = read("/parity/engine-vectors.json.gz");
    check(root != null, "parity/engine-vectors.json.gz is missing from the test resources");
    JsonObject v = root.getAsJsonObject();
    check("evi-engine-vectors/1".equals(v.get("format").getAsString()), "unexpected vectors format");
    constants(v.getAsJsonObject("constants"));
    int tested = 0, generated = 0;
    for (JsonElement e : v.getAsJsonArray("tested")) {
      guarded(e.getAsJsonObject(), "test");
      tested++;
    }
    for (JsonElement e : v.getAsJsonArray("generated")) {
      guarded(e.getAsJsonObject(), "gen");
      generated++;
    }
    int medians = 0;
    for (JsonElement e : v.getAsJsonArray("upperMedian")) {
      JsonObject m = e.getAsJsonObject();
      JsonArray vals = m.getAsJsonArray("values");
      long[] a = new long[vals.size()];
      for (int i = 0; i < a.length; i++) a[i] = exactLong(vals.get(i));
      same("upperMedian#" + medians++, m.get("result"), number(Medians.upper(a)));
    }
    int limits = 0;
    for (JsonElement e : v.getAsJsonObject("limitFor").getAsJsonArray("vectors")) {
      JsonObject l = e.getAsJsonObject();
      JsonArray a = l.getAsJsonArray("args");
      Sizing.Limit got = Sizing.limitFor(num(arg(a, 0)), num(arg(a, 1)), num(arg(a, 2)), num(arg(a, 3)), num(arg(a, 4)));
      JsonObject j = null;
      if (got != null) {
        j = new JsonObject();
        j.add("limit", number(got.limit));
        j.add("remaining", number(got.remaining));
        j.add("acrossWindows", got.acrossWindows == null ? JsonNull.INSTANCE : number(got.acrossWindows));
      }
      same("limitFor#" + limits++ + " " + a, l.get("result"), j);
    }
    int[] sized = new int[3], dropped = new int[3], onDropLine = new int[2];
    Map<String, Integer> flags = new TreeMap<>();
    int chains = 0;
    for (JsonElement e : v.getAsJsonArray("chains")) {
      JsonObject c = e.getAsJsonObject();
      JsonObject in = c.getAsJsonObject("in");
      String tier = in.get("tier").getAsString();
      JsonElement got;
      try {
        got = chain(in);
      } catch (RuntimeException ex) {
        got = threw(ex);
      }
      same("chain#" + chains++ + " " + tier + " " + in, c.get("out"), got);
      int t = "history".equals(tier) ? 0 : "market".equals(tier) ? 1 : 2;
      // The trade-length drop line: one unit taking EXACTLY twice the pace is kept (`>`), a hair past it is not.
      if (t < 2) {
        Double pace = Sizing.duration(num(in.get("target")));
        long liq = VolumeRow.liquidity(VolumeRow.of(hour(in.getAsJsonObject("volumes")), (int) exactLong(in.get("itemId"))));
        if (pace != null && liq > 0 && Sizing.correctedFillMinutes(Sizing.estimatedFillMinutes(1, liq, Sizing.VOLUME_WINDOW_MINUTES)) == pace * Sizing.DURATION_TOLERANCE
          && !isNullish(c.get("out"))) onDropLine[t]++;
      }
      if (isNullish(c.get("out"))) dropped[t]++;
      else {
        sized[t]++;
        for (Map.Entry<String, JsonElement> f : c.getAsJsonObject("out").getAsJsonObject("flags").entrySet())
          if (f.getValue().getAsBoolean()) flags.merge(tier + "." + f.getKey(), 1, Integer::sum);
      }
    }

    if (!diffs.isEmpty()) {
      StringBuilder sb = new StringBuilder(diffs.size() + " engine-vector divergence(s) from the JS engine:");
      for (String d : diffs.subList(0, Math.min(40, diffs.size()))) sb.append("\n  ").append(d);
      throw new AssertionError(sb.toString());
    }
    // Inert-test guards: a vectors file that silently stopped being read, or a recorder that stopped reaching a case,
    // would pass with nothing compared.
    check(tested >= 500 && generated >= 12000 && medians >= 150 && limits >= 300 && chains >= 2500,
      "too little was compared (" + tested + " from the tests, " + generated + " generated, " + medians + " medians, " + limits + " limitFor, " + chains + " chains)");
    for (String fn : new String[]{"volumeShareForDuration", "limitAllowance", "sizingLiquidityFor", "orderSizeCap", "captureFillable", "correctedFillMinutes",
      "estimateOfferFill", "marginClearsTax", "implausibleSpread", "implausibleBuyPrint", "slotCapacity", "slotNote", "slotExposure", "withCostBasis",
      "heldCostBasis", "breakEvenSellPrice", "spreadRankFor", "focusAllows", "resolveFocus", "lookupItemPrice", "priceAgeMinutes", "personalHistory",
      "median", "gateCandidate", "estimatedFillMinutes", "cashFromQuery", "maxSpendFromQuery"}) {
      check(perFunction.getOrDefault("test:" + fn, 0) >= 3, "fewer than 3 vectors from the JS tests for " + fn);
      check(perFunction.getOrDefault("gen:" + fn, 0) >= 40, "fewer than 40 generated vectors for " + fn);
    }
    for (int t = 0; t < 3; t++) check(sized[t] >= 200 && dropped[t] >= 100, "a tier's chains no longer both size and drop: " + sized[t] + " sized, " + dropped[t] + " dropped");
    // The cap ORDER is only exercised when each cap is the one that binds, often, in each tier.
    for (String f : new String[]{"history.cash", "history.stack", "history.share", "history.duration", "history.limit", "market.cash", "market.stack",
      "market.share", "market.duration", "market.limit", "pushed.cash", "pushed.share", "pushed.limit"})
      check(flags.getOrDefault(f, 0) >= 15, "the chains reach '" + f + "' as the binding cap only " + flags.getOrDefault(f, 0) + " times");
    check(scopedDivergences > 0, "no vector exercises the account scoping (the bare JS predicate never counted another account)");
    check(jsOnlyBlankCash >= 8 && multiDigitRadixCash >= 8, "too few cash vectors that only JS reads as blank (" + jsOnlyBlankCash + ") or whose value depends on the radix (" + multiDigitRadixCash + ")");
    check(fractionalRemainder >= 20, "too few gate vectors with a buy-limit remainder between 0 and 1 (" + fractionalRemainder + ")");
    // A `>=` in the drop line is only visible on a chain that lands exactly ON it and is kept; the random paces never do.
    check(onDropLine[0] >= 8 && onDropLine[1] >= 8, "too few chains sized exactly on the trade-length drop line (history "
      + onDropLine[0] + ", market " + onDropLine[1] + ")");
    System.out.println("PASS: engine vectors vs the JS engine (engine " + v.getAsJsonObject("engineLock").get("combined").getAsString().substring(0, 16) + "): "
      + tested + " recorded from the JS tests, " + generated + " generated, " + medians + " upper medians, " + limits + " limitFor, " + chains
      + " cap-order chains (" + sized[0] + "/" + sized[1] + "/" + sized[2] + " sized; binding caps " + flags + "), " + compared
      + " comparisons; scoped by design where the bare JS would count another account (" + scopedDivergences + " vectors)");
  }

  // ------------------------------------------------------------------------------------------- one call

  /** A port that THROWS where the JS answered is a divergence like any other, reported with its input. */
  private static void guarded(JsonObject vec, String origin) throws IOException {
    try {
      call(vec, origin);
    } catch (RuntimeException ex) {
      compared++;
      diffs.add(origin + " " + vec.get("fn").getAsString() + vec.get("args") + ": the Java port threw " + ex);
    }
  }

  private static JsonElement threw(RuntimeException ex) {
    JsonObject j = new JsonObject();
    j.addProperty("javaThrew", ex.toString());
    return j;
  }

  private static void call(JsonObject vec, String origin) throws IOException {
    String fn = vec.get("fn").getAsString();
    JsonArray a = vec.getAsJsonArray("args");
    String where = origin + " " + fn + a;
    perFunction.merge(origin + ":" + fn, 1, Integer::sum);
    JsonElement got;
    switch (fn) {
      case "volumeShareForDuration":
        got = number(Sizing.volumeShareForDuration(num(arg(a, 0))));
        break;
      case "captureFillable":
        got = number(Sizing.captureFillable(num(arg(a, 0))));
        break;
      case "correctedFillMinutes":
        got = number(Sizing.correctedFillMinutes(isNullish(arg(a, 0)) ? null : Double.valueOf(num(arg(a, 0)))));
        break;
      case "estimatedFillMinutes":
        got = number(Sizing.estimatedFillMinutes(num(arg(a, 0)), num(arg(a, 1)), num(arg(a, 2))));
        break;
      case "limitAllowance": {
        JsonObject o = arg(a, 0).getAsJsonObject();
        double now = isNullish(o.get("now")) ? 0 : num(o.get("now")); // undefined now only where it cannot matter (the recorder drops the rest)
        got = number(Sizing.limitAllowance(num(o.get("limit")), num(o.get("used")), num(o.get("windowEndsAt")), num(o.get("targetDurationMinutes")), now));
        break;
      }
      case "orderSizeCap": {
        JsonObject o = isNullish(arg(a, 2)) ? new JsonObject() : arg(a, 2).getAsJsonObject();
        got = number(Sizing.orderSizeCap(num(arg(a, 0)), num(arg(a, 1)), new Sizing.SizingOptions(num(o.get("maxVolumeShare")), num(o.get("volumeWindowShare")), false)));
        break;
      }
      case "sizingLiquidityFor": {
        JsonObject o = arg(a, 0).getAsJsonObject();
        int id = (int) exactLong(arg(a, 1));
        VolumeRow row = isNullish(o.get("volumes")) ? null : VolumeRow.of(hour(o.get("volumes").getAsJsonObject()), id);
        JsonElement typ = isNullish(o.get("typicalVolumes")) ? null : o.getAsJsonObject("typicalVolumes").get(String.valueOf(id));
        got = number(Sizing.sizingLiquidityFor(VolumeRow.reading(row), num(typ)));
        break;
      }
      case "estimateOfferFill": {
        Sizing.OfferFill f = Sizing.estimateOfferFill(num(arg(a, 0)), row(arg(a, 1)), num(arg(a, 2)));
        JsonObject j = null;
        if (f != null) {
          j = new JsonObject();
          j.add("estimatedFillMinutes", number(f.estimatedFillMinutes));
          j.addProperty("likelyToFillInTime", f.likelyToFillInTime);
        }
        got = j;
        break;
      }
      case "marginClearsTax":
        got = new JsonPrimitive(Gates.marginClearsTax(num(arg(a, 0)), num(arg(a, 1)), multiple(arg(a, 2), Gates.MARGIN_TAX_MULTIPLE)));
        break;
      case "implausibleSpread":
        got = new JsonPrimitive(Gates.implausibleSpread(num(arg(a, 0)), num(arg(a, 1)), num(arg(a, 2)), multiple(arg(a, 3), Gates.IMPLAUSIBLE_MARGIN_MULTIPLE)));
        break;
      case "implausibleBuyPrint": {
        double mult = multiple(arg(a, 2), Gates.BUY_PRINT_MULTIPLE);
        JsonElement e = arg(a, 1);
        boolean entry = !isNullish(e) && e.isJsonObject();
        double avg = entry ? num(e.getAsJsonObject().get("avgLowPrice")) : Double.NaN, vol = entry ? num(e.getAsJsonObject().get("lowPriceVolume")) : Double.NaN;
        boolean ans = Gates.implausibleBuyPrint(num(arg(a, 0)), avg, vol, mult);
        got = new JsonPrimitive(ans);
        // The production overload, through the price layer's own row, wherever the entry is something the Wiki can send.
        if (!entry || integralFields(e.getAsJsonObject())) {
          boolean viaRow = Gates.implausibleBuyPrint(num(arg(a, 0)), entry ? row(e) : null, mult);
          compared++;
          if (viaRow != ans) diffs.add(where + ": the VolumeRow overload answered " + viaRow + " where the numbers gave " + ans);
        }
        break;
      }
      case "slotCapacity": {
        GeSlots.Capacity c = GeSlots.slotCapacity(str(arg(a, 0)), str(arg(a, 1)));
        got = capacity(c);
        break;
      }
      case "slotNote": {
        JsonElement e = arg(a, 0);
        GeSlots.Capacity c = null;
        if (!isNullish(e)) {
          JsonObject o = e.getAsJsonObject();
          c = new GeSlots.Capacity(intOrNull(o.get("free")), intOrNull(o.get("collectable")), bool(o.get("full")), bool(o.get("tight")));
        }
        String note = GeSlots.slotNote(c);
        got = note == null ? JsonNull.INSTANCE : new JsonPrimitive(note);
        break;
      }
      case "slotExposure": {
        GeSlots.Exposure x = GeSlots.slotExposure(positions(arg(a, 0)), offers(arg(a, 1)), str(arg(a, 2)));
        JsonObject j = new JsonObject();
        JsonArray set = new JsonArray();
        for (Integer id : x.exposure) set.add(id);
        JsonObject tagged = new JsonObject();
        tagged.add("$set", set);
        j.add("exposure", tagged);
        JsonArray owed = new JsonArray();
        for (Integer id : x.owedItems) owed.add(id);
        j.add("owedItems", owed);
        j.add("sellSlotsOwed", number(x.sellSlotsOwed));
        got = j;
        scoped(vec);
        break;
      }
      case "heldCostBasis": {
        CostBasis b = Prices.heldCostBasis(positions(arg(a, 0)), offers(arg(a, 1)), (int) exactLong(arg(a, 2)), str(arg(a, 3)));
        JsonObject j = null;
        if (b != null) {
          j = new JsonObject();
          j.add("unitCost", number(b.unitCost));
          j.add("quantity", number(b.quantity));
        }
        got = j;
        scoped(vec);
        break;
      }
      case "withCostBasis": {
        ItemPrice p = null;
        if (!isNullish(arg(a, 0))) {
          JsonObject o = arg(a, 0).getAsJsonObject();
          p = new ItemPrice((int) exactLong(o.get("itemId")), exactLong(o.get("buyPrice")), exactLong(o.get("sellPrice")), null, null, null);
        }
        got = price(Prices.withCostBasis(p, num(arg(a, 1)), num(arg(a, 2))));
        break;
      }
      case "breakEvenSellPrice":
        got = number(Tax.breakEvenSellPrice((int) exactLong(arg(a, 0)), num(arg(a, 1))));
        break;
      case "spreadRankFor":
        got = number(SpreadRank.spreadRankFor(str(arg(a, 0)), num(arg(a, 1))));
        break;
      case "focusAllows": {
        Double limit = isNullish(arg(a, 1)) ? null : num(arg(a, 1));
        got = new JsonPrimitive(Focus.focusAllows(str(arg(a, 0)), limit));
        break;
      }
      case "resolveFocus":
        got = new JsonPrimitive(Focus.resolveFocus(str(arg(a, 0)), str(arg(a, 1))));
        break;
      case "lookupItemPrice": {
        LatestPrices latest = null;
        if (!isNullish(arg(a, 0))) {
          JsonObject body = new JsonObject();
          body.add("data", arg(a, 0));
          latest = WikiJson.latest(body.toString());
        }
        got = price(Prices.lookupItemPrice(latest, num(arg(a, 1))));
        break;
      }
      case "priceAgeMinutes": {
        JsonElement e = arg(a, 0);
        JsonObject o = !isNullish(e) && e.isJsonObject() ? e.getAsJsonObject() : null;
        double ht = o == null ? Double.NaN : num(o.get("highTime")), lt = o == null ? Double.NaN : num(o.get("lowTime")), now = num(arg(a, 1));
        Double age = Prices.priceAgeMinutes(ht, lt, now);
        got = age == null ? JsonNull.INSTANCE : number(age);
        // The production overload, on a /latest quote, wherever the times are what the Wiki can send.
        if (o != null && integralOrNull(o.get("highTime")) && integralOrNull(o.get("lowTime")) && Js.isInteger(now) && Math.abs(now) < 9e15) {
          JsonObject body = new JsonObject(), data = new JsonObject();
          data.add("7", o);
          body.add("data", data);
          Double viaQuote = Prices.priceAgeMinutes(WikiJson.latest(body.toString()).get(7), (long) now);
          compared++;
          if (!(viaQuote == null ? age == null : age != null && (viaQuote.equals(age) || (viaQuote.isNaN() && age.isNaN()))))
            diffs.add(where + ": the Quote overload answered " + viaQuote + " where the numbers gave " + age);
        }
        break;
      }
      case "personalHistory": {
        List<History.Flip> flips = null;
        if (!isNullish(arg(a, 0))) {
          flips = new ArrayList<>();
          for (JsonElement f : arg(a, 0).getAsJsonArray()) flips.add(flip(f));
        }
        JsonArray out = new JsonArray();
        for (History.ItemHistory h : History.personalHistory(flips)) out.add(history(h));
        got = out;
        break;
      }
      case "median": {
        List<Double> list = new ArrayList<>();
        for (JsonElement x : arg(a, 0).getAsJsonArray()) list.add(num(x));
        got = number(Medians.average(list));
        break;
      }
      case "gateCandidate": {
        Sizing.ItemGate g = gate(arg(a, 2).getAsJsonObject());
        Double left = g.limitRemaining(0);
        if (left != null && left > 0 && left < 1) fractionalRemainder++; // between the "< 1" refusal and zero
        Sizing.Clip c = Sizing.gate((int) exactLong(arg(a, 0)), exactLong(arg(a, 1)), g);
        JsonObject j = null;
        if (c != null) {
          j = new JsonObject();
          j.add("quantity", number(c.quantity));
          j.addProperty("limited", c.limited);
        }
        got = j;
        break;
      }
      case "cashFromQuery": {
        String raw = str(arg(a, 0));
        if (raw != null && JsValues.trim(raw).isEmpty() && !raw.trim().isEmpty()) jsOnlyBlankCash++;
        if (raw != null && RADIX_MULTI_DIGIT.matcher(raw).matches()) multiDigitRadixCash++;
        got = number(QueryValues.cashFromQuery(raw));
        break;
      }
      case "maxSpendFromQuery":
        got = number(QueryValues.maxSpendFromQuery(str(arg(a, 0)), str(arg(a, 1))));
        break;
      default:
        throw new AssertionError("no Java port for vector function " + fn);
    }
    same(where, vec.get("result"), got);
  }

  // Where the bare JS function's own predicate counted another account, the scoped port (correctly) does not.
  private static void scoped(JsonObject vec) {
    List<String> d = new ArrayList<>();
    diff(vec.get("bare"), vec.get("result"), "$", d);
    if (!d.isEmpty()) scopedDivergences++;
  }

  // ------------------------------------------------------------------------------------------- the chains

  private static JsonElement chain(JsonObject in) throws IOException {
    String tier = in.get("tier").getAsString();
    int itemId = (int) exactLong(in.get("itemId"));
    long low = exactLong(in.get("low"));
    VolumeRow row = VolumeRow.of(hour(in.getAsJsonObject("volumes")), itemId);
    double typical = num(in.get("typical"));
    Double maxSpend = Sizing.maxSpend(num(in.get("maxSpend")));
    double maxStackShare = num(in.get("maxStackShare"));
    Double target = Sizing.duration(num(in.get("target")));
    Sizing.SizingOptions options = new Sizing.SizingOptions(num(in.get("maxVolumeShare")), num(in.get("volumeWindowShare")), bool(in.get("captureCap")));
    Sizing.ItemGate gate = gate(in.getAsJsonObject("gate"));
    Sizing.Sized s;
    switch (tier) {
      case "history": {
        List<History.Flip> flips = new ArrayList<>();
        for (JsonElement f : in.getAsJsonArray("flips")) flips.add(flip(f));
        List<History.ItemHistory> hist = History.personalHistory(flips);
        check(hist.size() == 1, "a history chain must hold one item");
        History.ItemHistory h = hist.get(0);
        if (!History.eligible(h, LevelSettings.forHistoryTier(str(in.get("risk"))), null)) return JsonNull.INSTANCE;
        s = Sizing.historyQuantity(itemId, h.medianQty, low, maxSpend, maxStackShare, row, typical, target, options, gate);
        break;
      }
      case "market":
        if (!Gates.liquidityFloorMet(VolumeRow.liquidity(row), target, num(in.get("minVolumeInWindow")), num(in.get("minHourlyVolume")), LevelSettings.forRequest(null)))
          return JsonNull.INSTANCE;
        s = Sizing.marketQuantity(itemId, num(in.get("limit")), low, maxSpend, maxStackShare, row, typical, target, options, gate);
        break;
      default:
        s = Sizing.pushedQuantity(itemId, num(in.get("limitOf")), num(in.get("scannerQty")), low, maxSpend, row, typical, target, options, gate);
    }
    if (s == null) return JsonNull.INSTANCE;
    JsonObject out = new JsonObject(), flags = new JsonObject();
    out.add("quantity", number(s.quantity));
    flags.addProperty("cash", s.cashLimited);
    flags.addProperty("stack", s.stackLimited);
    flags.addProperty("share", s.shareLimited);
    flags.addProperty("duration", s.durationLimited);
    flags.addProperty("limit", s.limitLimited);
    out.add("flags", flags);
    return out;
  }

  // ------------------------------------------------------------------------------------------- constants (layer 0)

  private static void constants(JsonObject c) {
    eq(c, "MARGIN_TAX_MULTIPLE", Gates.MARGIN_TAX_MULTIPLE);
    eq(c, "MARGIN_TAX_NO_HISTORY_MULTIPLE", Gates.MARGIN_TAX_NO_HISTORY_MULTIPLE);
    eq(c, "MARGIN_TAX_WARN_MULTIPLE", Gates.MARGIN_TAX_WARN_MULTIPLE);
    eq(c, "IMPLAUSIBLE_MARGIN_MULTIPLE", Gates.IMPLAUSIBLE_MARGIN_MULTIPLE);
    eq(c, "BUY_PRINT_MULTIPLE", Gates.BUY_PRINT_MULTIPLE);
    eq(c, "MAX_PRICE_AGE_MINUTES", Prices.MAX_PRICE_AGE_MINUTES);
    eq(c, "LIMIT_WINDOW_MS", Sizing.LIMIT_WINDOW_MS);
    eq(c, "BULK_MIN_LIMIT", Focus.BULK_MIN_LIMIT);
    eq(c, "MAX_VOLUME_SHARE", Sizing.MAX_VOLUME_SHARE);
    eq(c, "CAPTURE_CURVE_ANCHOR_UNITS", Sizing.CAPTURE_CURVE_ANCHOR_UNITS);
    eq(c, "CAPTURE_CURVE_AT_ANCHOR", Sizing.CAPTURE_CURVE_AT_ANCHOR);
    eq(c, "CAPTURE_MAX_SHARE", Sizing.CAPTURE_MAX_SHARE);
    eq(c, "FILL_FLOOR_MINUTES", Sizing.FILL_FLOOR_MINUTES);
    eq(c, "PASSIVE_FILL_FACTOR", Sizing.PASSIVE_FILL_FACTOR);
    eq(c, "DURATION_TOLERANCE", Sizing.DURATION_TOLERANCE);
    eq(c, "GE_SLOTS", GeSlots.GE_SLOTS);
    eq(c, "MIN_HOURLY_VOLUME", LevelSettings.LOW.minHourlyVolume);
    eq(c, "DEFAULT_MARKET_QUANTITY_CAP", Sizing.DEFAULT_MARKET_QUANTITY_CAP);
    eq(c, "DEFAULT_MAX_VOLUME_SHARE", Sizing.DEFAULT_MAX_VOLUME_SHARE);
    eq(c, "VOLUME_SHARE_PER_HOUR", Sizing.VOLUME_SHARE_PER_HOUR);
    eq(c, "VOLUME_WINDOW_MINUTES", Sizing.VOLUME_WINDOW_MINUTES);
    List<String> focuses = new ArrayList<>();
    for (JsonElement f : c.getAsJsonArray("FOCUSES")) focuses.add(f.getAsString());
    check(focuses.equals(Focus.FOCUSES), "FOCUSES differs from the JS: " + focuses);
    Set<Integer> unflippable = new HashSet<>();
    for (JsonElement f : c.getAsJsonArray("UNFLIPPABLE_ITEM_IDS")) unflippable.add(f.getAsInt());
    check(unflippable.equals(Gates.UNFLIPPABLE_ITEM_IDS), "UNFLIPPABLE_ITEM_IDS differs from the JS: " + unflippable);
    // The risk seam holds today's values: the history tier's criteria per level, and the same bars at every level.
    JsonObject tiers = c.getAsJsonObject("RISK_TIERS");
    check(tiers.keySet().equals(new TreeSet<>(java.util.Arrays.asList("low", "medium", "high"))), "RISK_TIERS names changed: " + tiers.keySet());
    for (String level : tiers.keySet()) {
      JsonObject t = tiers.getAsJsonObject(level);
      LevelSettings s = LevelSettings.forHistoryTier(level);
      check(s == LevelSettings.forRequest(level), "the two level lookups disagree on '" + level + "'");
      check(t.get("minTrades").getAsInt() == s.historyMinTrades && t.get("minWinRate").getAsDouble() == s.historyMinWinRate
        && t.get("winRatePower").getAsInt() == s.historyWinRatePower, "LevelSettings." + level + " differs from RISK_TIERS." + level + ": " + t);
      check(s.historyMarginTaxMultiple == c.get("MARGIN_TAX_MULTIPLE").getAsDouble() && s.noHistoryMarginTaxMultiple == c.get("MARGIN_TAX_NO_HISTORY_MULTIPLE").getAsDouble()
        && s.warnMarginTaxMultiple == c.get("MARGIN_TAX_WARN_MULTIPLE").getAsDouble() && s.minHourlyVolume == c.get("MIN_HOURLY_VOLUME").getAsDouble(),
        "LevelSettings." + level + "'s bars are not today's");
    }
    check(LevelSettings.forRequest(null) == LevelSettings.forRequest(c.get("REQUEST_RISK_DEFAULT").getAsString())
      && LevelSettings.forRequest("bogus") == LevelSettings.LOW, "a request with no risk must read as " + c.get("REQUEST_RISK_DEFAULT"));
    check(LevelSettings.forHistoryTier(null) == LevelSettings.forHistoryTier(c.get("HISTORY_RISK_DEFAULT").getAsString())
      && LevelSettings.forHistoryTier("bogus") == LevelSettings.MEDIUM, "the history tier's unknown risk must read as " + c.get("HISTORY_RISK_DEFAULT"));
  }

  private static void eq(JsonObject c, String name, double javaValue) {
    compared++;
    check(c.has(name), "the JS constants carry no " + name);
    check(num(c.get(name)) == javaValue, name + ": Java " + javaValue + ", JS " + c.get(name)); // the same double
  }

  // ------------------------------------------------------------------------------------------- decoding

  private static Sizing.ItemGate gate(JsonObject g) {
    boolean members = bool(g.get("members")), focus = bool(g.get("focus"));
    JsonElement limit = g.get("limit");
    Double remaining = isNullish(limit) ? null : num(limit.getAsJsonObject().get("remaining"));
    return new Sizing.ItemGate() {
      @Override public boolean membersBlocked(int itemId) {
        return members;
      }

      @Override public boolean focusBlocked(int itemId) {
        return focus;
      }

      @Override public Double limitRemaining(int itemId) {
        return remaining;
      }
    };
  }

  private static History.Flip flip(JsonElement e) {
    if (isNullish(e)) return null;
    JsonObject o = e.getAsJsonObject();
    return new History.Flip(bool(o.get("removed")), num(o.get("itemId")), num(o.get("profit")), num(o.get("quantity")), num(o.get("hold")),
      isNullish(o.get("lastSell")) ? null : num(o.get("lastSell")), isNullish(o.get("confirmedAt")) ? null : num(o.get("confirmedAt")), str(o.get("item")));
  }

  private static List<FifoMatcher.OpenPosition> positions(JsonElement e) {
    if (isNullish(e)) return null;
    List<FifoMatcher.OpenPosition> out = new ArrayList<>();
    for (JsonElement x : e.getAsJsonArray()) {
      if (isNullish(x)) {
        out.add(null);
        continue;
      }
      JsonObject o = x.getAsJsonObject();
      out.add(new FifoMatcher.OpenPosition(str(o.get("account")), (int) exactLong(o.get("itemId")), null, null, longOr0(o.get("remaining")),
        longOr0(o.get("remaining")), null, num(o.get("unitCost")), false));
    }
    return out;
  }

  private static List<Offer> offers(JsonElement e) {
    if (isNullish(e)) return null;
    List<Offer> out = new ArrayList<>();
    for (JsonElement x : e.getAsJsonArray()) {
      if (isNullish(x)) {
        out.add(null);
        continue;
      }
      JsonObject o = x.getAsJsonObject();
      out.add(new Offer(0, str(o.get("state")), null, (int) exactLong(o.get("itemId")), null, 0, 0, longOr0(o.get("filled")), longOr0(o.get("spent")),
        false, null, str(o.get("account")), null, null, null, null, null));
    }
    return out;
  }

  /** One Wiki /1h data object as the price layer's hour (WikiJson.hour: the production ingest). */
  private static HourBucket hour(JsonObject data) throws IOException {
    JsonObject body = new JsonObject();
    body.addProperty("timestamp", 0);
    body.add("data", data);
    return WikiJson.hour(body.toString());
  }

  /** A single /1h entry as a row (null when the entry is absent or not an object). */
  private static VolumeRow row(JsonElement entry) throws IOException {
    if (isNullish(entry) || !entry.isJsonObject()) return null;
    JsonObject data = new JsonObject();
    data.add("1", entry);
    return VolumeRow.of(hour(data), 1);
  }

  private static boolean integralFields(JsonObject o) {
    for (Map.Entry<String, JsonElement> f : o.entrySet()) if (!integralOrNull(f.getValue())) return false;
    return true;
  }

  private static boolean integralOrNull(JsonElement e) {
    if (isNullish(e)) return true;
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
    double d = Js.number(e);
    return Js.isInteger(d) && Math.abs(d) <= Js.MAX_SAFE;
  }

  /** A multiple parameter: undefined takes the function's default; null is 0 (JS reads it as 0 in arithmetic). */
  private static double multiple(JsonElement e, double dflt) {
    if (isUndefined(e)) return dflt;
    if (e.isJsonNull()) return 0;
    return num(e);
  }

  private static boolean isUndefined(JsonElement e) {
    return e == null || (e.isJsonObject() && e.getAsJsonObject().has("$u"));
  }

  private static boolean isNullish(JsonElement e) {
    return isUndefined(e) || e.isJsonNull();
  }

  /** A JS number argument: undefined and null are NaN (every port treats them as "not a finite number"), tags decoded. */
  private static double num(JsonElement e) {
    if (isNullish(e)) return Double.NaN;
    if (e.isJsonObject() && e.getAsJsonObject().has("$num")) {
      switch (e.getAsJsonObject().get("$num").getAsString()) {
        case "NaN": return Double.NaN;
        case "Infinity": return Double.POSITIVE_INFINITY;
        case "-Infinity": return Double.NEGATIVE_INFINITY;
        case "-0": return -0.0;
        default: throw new AssertionError("unknown tag " + e);
      }
    }
    Double d = Js.number(e);
    if (d == null) throw new AssertionError("not a number: " + e);
    return d;
  }

  private static long exactLong(JsonElement e) {
    return JsValues.exactLong(num(e));
  }

  private static long longOr0(JsonElement e) {
    return isNullish(e) ? 0 : exactLong(e);
  }

  private static Integer intOrNull(JsonElement e) {
    return isNullish(e) ? null : Integer.valueOf((int) exactLong(e));
  }

  private static boolean bool(JsonElement e) {
    return !isNullish(e) && e.getAsBoolean();
  }

  /** A string argument; a number becomes String(n) as JS would make it; undefined and null are null. */
  private static String str(JsonElement e) {
    if (isNullish(e)) return null;
    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() || e.isJsonObject() && e.getAsJsonObject().has("$num")) return Js.numberToString(num(e));
    throw new AssertionError("not a string: " + e);
  }

  // ------------------------------------------------------------------------------------------- Java answers as JSON

  /** A double: its EXACT value (non-finite as the recorder's tags). */
  private static JsonElement number(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      JsonObject t = new JsonObject();
      t.addProperty("$num", Double.isNaN(d) ? "NaN" : d > 0 ? "Infinity" : "-Infinity");
      return t;
    }
    return new JsonPrimitive(new BigDecimal(d));
  }

  private static JsonElement number(Number n) {
    if (n == null) return JsonNull.INSTANCE;
    if (n instanceof Double) return number(n.doubleValue());
    return new JsonPrimitive(BigDecimal.valueOf(n.longValue()));
  }

  private static JsonElement number(long n) {
    return new JsonPrimitive(BigDecimal.valueOf(n));
  }

  private static JsonElement capacity(GeSlots.Capacity c) {
    JsonObject j = new JsonObject();
    j.add("free", number(c.free));
    j.add("collectable", number(c.collectable));
    j.addProperty("full", c.full);
    j.addProperty("tight", c.tight);
    return j;
  }

  private static JsonElement price(ItemPrice p) {
    if (p == null) return JsonNull.INSTANCE;
    JsonObject j = new JsonObject();
    j.add("itemId", number(p.itemId));
    j.add("buyPrice", number(p.buyPrice));
    j.add("sellPrice", number(p.sellPrice));
    if (p.action != null) j.addProperty("action", p.action);
    if (p.action != null) j.add("breakEvenPrice", number(p.breakEvenPrice));
    if (p.action != null) j.add("lossIfSoldNow", number(p.lossIfSoldNow));
    return j;
  }

  private static JsonElement history(History.ItemHistory h) {
    JsonObject j = new JsonObject();
    j.add("itemId", number(h.itemId));
    if (h.name != null) j.addProperty("name", h.name);
    j.add("trades", number(h.trades));
    j.add("winRate", number(h.winRate));
    j.add("avgProfit", number(h.avgProfit));
    j.add("medianQty", number(h.medianQty));
    j.add("medianHoldH", number(h.medianHoldH));
    j.add("lastTradeAt", number(h.lastTradeAt));
    return j;
  }

  // ------------------------------------------------------------------------------------------- comparison

  private static void same(String where, JsonElement exp, JsonElement act) {
    compared++;
    List<String> d = new ArrayList<>();
    diff(exp, act, "$", d);
    if (!d.isEmpty()) diffs.add(where + ": " + String.join("; ", d.subList(0, Math.min(3, d.size()))));
  }

  private static boolean isNumberish(JsonElement e) {
    return e != null && (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() || e.isJsonObject() && e.getAsJsonObject().has("$num"));
  }

  private static void diff(JsonElement exp, JsonElement act, String path, List<String> out) {
    if (isNullish(exp) || isNullish(act)) {
      if (isNullish(exp) != isNullish(act)) out.add(path + ": expected " + exp + " but was " + act);
      return;
    }
    if (isNumberish(exp) || isNumberish(act)) {
      if (!isNumberish(exp) || !isNumberish(act)) {
        out.add(path + ": expected " + exp + " but was " + act);
        return;
      }
      double e = num(exp);
      boolean ok;
      if (Double.isNaN(e) || Double.isInfinite(e)) ok = act.isJsonObject() && act.equals(exp.isJsonObject() ? exp : number(e));
      else ok = act.isJsonPrimitive() && new BigDecimal(act.getAsString()).compareTo(new BigDecimal(e)) == 0; // the JS double's EXACT value
      if (!ok) out.add(path + ": expected " + exp + " but was " + act);
      return;
    }
    if (exp.isJsonObject() && exp.getAsJsonObject().has("$set")) {
      if (!act.isJsonObject() || !act.getAsJsonObject().has("$set")) out.add(path + ": expected a set " + exp + " but was " + act);
      else diff(exp.getAsJsonObject().get("$set"), act.getAsJsonObject().get("$set"), path + ".$set", out);
      return;
    }
    if (exp.isJsonArray() && act.isJsonArray()) {
      JsonArray e = exp.getAsJsonArray(), a = act.getAsJsonArray();
      if (e.size() != a.size()) {
        out.add(path + ": expected " + e.size() + " elements but was " + a.size() + " (" + exp + " vs " + act + ")");
        return;
      }
      for (int i = 0; i < e.size(); i++) diff(e.get(i), a.get(i), path + "[" + i + "]", out);
      return;
    }
    if (exp.isJsonObject() && act.isJsonObject()) {
      TreeSet<String> keys = new TreeSet<>(exp.getAsJsonObject().keySet());
      keys.addAll(act.getAsJsonObject().keySet());
      for (String k : keys) diff(exp.getAsJsonObject().get(k), act.getAsJsonObject().get(k), path + "." + k, out);
      return;
    }
    if (!exp.equals(act)) out.add(path + ": expected " + exp + " but was " + act);
  }

  // ------------------------------------------------------------------------------------------- plumbing

  /** Argument i, or null when the call passed fewer (a missing argument is undefined in JS). */
  private static JsonElement arg(JsonArray a, int i) {
    return i < a.size() ? a.get(i) : null;
  }

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  /** A classpath resource, gunzipped; null when absent. No file API (the Hub rules): the classpath only. */
  static JsonElement read(String resource) throws IOException {
    InputStream raw = EngineVectorsTest.class.getResourceAsStream(resource);
    if (raw == null) return null;
    try (InputStream in = resource.endsWith(".gz") ? new GZIPInputStream(raw) : raw; Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return new JsonParser().parse(r);
    }
  }
}
