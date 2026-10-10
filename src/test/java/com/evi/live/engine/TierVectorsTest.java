package com.evi.live.engine;

import static com.evi.live.engine.EngineJson.arg;
import static com.evi.live.engine.EngineJson.bool;
import static com.evi.live.engine.EngineJson.check;
import static com.evi.live.engine.EngineJson.exactLong;
import static com.evi.live.engine.EngineJson.intSet;
import static com.evi.live.engine.EngineJson.isNullish;
import static com.evi.live.engine.EngineJson.longOr0;
import static com.evi.live.engine.EngineJson.num;
import static com.evi.live.engine.EngineJson.number;
import static com.evi.live.engine.EngineJson.numOrNull;
import static com.evi.live.engine.EngineJson.str;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Offer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Phase 3b engine functions against what the REAL JS answered, value for value (tools/parity-tier-vectors.mjs,
 * synthetic): sell support and its merge with the archive, the support note and the stale-support cap, the holding,
 * persisted-position, idle-stock and history tiers, the "Best of both" rule, the forecast policy, the verdict, the two
 * server rules, the PICK CHAIN driven by scripted tiers and hooks, and the sell-support JUDGEMENT cut from server.mjs.
 *
 * <p>Exact equality, as EngineVectorsTest: a number is the very double the JS computed, a string character for
 * character, absent equals null. Every expectation is a recorded JS answer; nothing is hand-computed here.
 */
public final class TierVectorsTest {
  private static final List<String> diffs = new ArrayList<>();
  private static final Map<String, Integer> perFunction = new TreeMap<>();
  private static int compared;

  public static void main(String[] args) throws Exception {
    JsonElement root = EngineJson.read("/parity/tier-vectors.json.gz");
    check(root != null, "parity/tier-vectors.json.gz is missing from the test resources");
    JsonObject v = root.getAsJsonObject();
    check("evi-tier-vectors/1".equals(v.get("format").getAsString()), "unexpected vectors format");
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
    int chains = 0, picked = 0, heldBack = 0, demotedShown = 0;
    for (JsonElement e : v.getAsJsonArray("chains")) {
      JsonObject c = e.getAsJsonObject();
      JsonElement got;
      try {
        got = chain(c.getAsJsonObject("in"));
      } catch (RuntimeException ex) {
        got = threw(ex);
      }
      same("chain#" + chains++, c.get("out"), got);
      JsonObject out = c.getAsJsonObject("out");
      if (!isNullish(out.get("pick"))) {
        picked++;
        if (bool(out.getAsJsonObject("pick").get("demoted"))) demotedShown++;
      }
      if (!isNullish(out.get("blocked"))) heldBack++;
    }
    int judged = 0, blocked = 0, warned = 0, carried = 0;
    for (JsonElement e : v.getAsJsonObject("judge").getAsJsonArray("vectors")) {
      JsonObject j = e.getAsJsonObject();
      JsonElement got;
      try {
        got = judge(j.getAsJsonObject("in"));
      } catch (RuntimeException ex) {
        got = threw(ex);
      }
      same("judge#" + judged++, j.get("out"), got);
      JsonElement out = j.get("out");
      if (!isNullish(out)) {
        if (bool(out.getAsJsonObject().get("blocked"))) blocked++;
        else if (!isNullish(out.getAsJsonObject().get("warning"))) warned++;
        else carried++;
      }
    }
    if (!diffs.isEmpty()) {
      StringBuilder sb = new StringBuilder(diffs.size() + " tier-vector divergence(s) from the JS engine:");
      for (String d : diffs.subList(0, Math.min(40, diffs.size()))) sb.append("\n  ").append(d);
      throw new AssertionError(sb.toString());
    }
    // Inert-test guards: a file silently not read, or a recorder that stopped reaching a case, would pass with nothing compared.
    check(tested >= 250 && generated >= 6000 && chains >= 800 && judged >= 800, "too little was compared (" + tested + " from the tests, " + generated
      + " generated, " + chains + " chains, " + judged + " judgements)");
    for (String fn : new String[]{"sellPriceSupport", "supportIsStale", "supportedPriceForHeadline", "mergeArchiveHours", "sellSupportNote", "computeSuggestion",
      "computeHoldingSuggestion", "hasLiveSellOffer", "holdingPreempts", "pickPersistentOpenPosition", "computeInventorySuggestion", "comparableAsHistoryPick",
      "decideForecast", "recencyWeight", "headlineProfit", "autoMinProfit", "suggestionVerdict"}) {
      check(perFunction.getOrDefault("test:" + fn, 0) >= 5, "fewer than 5 vectors from the JS tests for " + fn);
      check(perFunction.getOrDefault("gen:" + fn, 0) >= 15, "fewer than 15 generated vectors for " + fn);
    }
    check(picked >= 300 && heldBack >= 50 && demotedShown >= 30, "the chains no longer reach every outcome (" + picked + " picked, " + heldBack
      + " held back, " + demotedShown + " shown demoted)");
    check(blocked >= 100 && warned >= 100 && carried >= 50, "the judgements no longer reach every outcome (" + blocked + " held back, " + warned + " warned, "
      + carried + " carried)");
    System.out.println("PASS: tier vectors vs the JS engine (engine " + v.getAsJsonObject("engineLock").get("combined").getAsString().substring(0, 16) + "): "
      + tested + " recorded from the JS tests, " + generated + " generated, " + chains + " scripted pick chains (" + picked + " picked, " + heldBack
      + " with candidates held back, " + demotedShown + " shown demoted), " + judged + " sell-support judgements (" + blocked + " held back, " + warned
      + " warned, " + carried + " carried); " + compared + " comparisons");
  }

  private static JsonElement threw(RuntimeException ex) {
    JsonObject j = new JsonObject();
    j.addProperty("javaThrew", ex.toString());
    return j;
  }

  private static void guarded(JsonObject vec, String origin) throws IOException {
    try {
      call(vec, origin);
    } catch (RuntimeException ex) {
      compared++;
      diffs.add(origin + " " + vec.get("fn").getAsString() + vec.get("args") + ": the Java port threw " + ex);
    }
  }

  private static void same(String where, JsonElement exp, JsonElement act) {
    compared++;
    List<String> d = EngineJson.diff(exp, act);
    if (!d.isEmpty()) diffs.add(where + ": " + String.join("; ", d.subList(0, Math.min(3, d.size()))));
  }

  // ------------------------------------------------------------------------------------------- one call

  private static void call(JsonObject vec, String origin) throws IOException {
    String fn = vec.get("fn").getAsString();
    JsonArray a = vec.getAsJsonArray("args");
    perFunction.merge(origin + ":" + fn, 1, Integer::sum);
    JsonElement got;
    switch (fn) {
      case "sellPriceSupport": {
        JsonObject o = arg(a, 3).getAsJsonObject();
        int hours = isNullish(o.get("hours")) ? SellSupport.SELL_SUPPORT_HOURS : (int) exactLong(o.get("hours"));
        got = EngineJson.detail(SellSupport.sellPriceSupport(EngineJson.points(arg(a, 0)), (int) exactLong(arg(a, 1)), num(arg(a, 2)), hours, num(o.get("nowMs"))));
        break;
      }
      case "supportIsStale": {
        JsonElement r = arg(a, 1);
        SellSupport.Detail d = EngineJson.detail(arg(a, 0));
        got = new JsonPrimitive(EngineJson.isUndefined(r) ? SellSupport.supportIsStale(d) : SellSupport.supportIsStale(d, r.isJsonNull() ? 0 : num(r)));
        break;
      }
      case "supportedPriceForHeadline":
        got = number(SellSupport.supportedPriceForHeadline(EngineJson.detail(arg(a, 0))));
        break;
      case "sellSupportNote": {
        String note = SellSupport.sellSupportNote(EngineJson.detail(arg(a, 0)), num(arg(a, 1)));
        got = note == null ? JsonNull.INSTANCE : new JsonPrimitive(note);
        break;
      }
      case "mergeArchiveHours": {
        JsonArray out = new JsonArray();
        for (SellSupport.Point p : SellSupport.mergeArchiveHours(EngineJson.points(arg(a, 0)), EngineJson.buckets(arg(a, 1)), (int) exactLong(arg(a, 2))))
          out.add(EngineJson.point(p));
        got = out;
        break;
      }
      case "computeHoldingSuggestion":
        got = EngineJson.pick(HoldingTiers.computeHoldingSuggestion(EngineJson.latest(arg(a, 0)), (int) exactLong(arg(a, 1)), exactLong(arg(a, 2)),
          str(arg(a, 3)), str(arg(a, 4)), num(arg(a, 5)), EngineJson.endedUnseen(arg(a, 6))));
        break;
      case "hasLiveSellOffer": {
        List<Offer> offers = null;
        if (!isNullish(arg(a, 0))) {
          offers = new ArrayList<>();
          for (JsonElement x : arg(a, 0).getAsJsonArray()) {
            if (isNullish(x)) {
              offers.add(null);
              continue;
            }
            JsonObject o = x.getAsJsonObject();
            offers.add(new Offer(0, str(o.get("state")), null, (int) exactLong(o.get("itemId")), null, 0, 0, 0, 0, false, null, str(o.get("account")),
              null, null, null, null, null));
          }
        }
        got = new JsonPrimitive(HoldingTiers.hasLiveSellOffer(offers, (int) exactLong(arg(a, 1)), str(arg(a, 2))));
        break;
      }
      case "holdingPreempts": {
        Pick p = null;
        if (!isNullish(arg(a, 0))) {
          p = new Pick(1, null, "sell", 1, 1, 1, "holding");
          p.netIfSoldNow = numOrNull(arg(a, 0).getAsJsonObject().get("netIfSoldNow"));
        }
        got = new JsonPrimitive(HoldingTiers.holdingPreempts(p, num(arg(a, 1))));
        break;
      }
      case "pickPersistentOpenPosition": {
        JsonElement list = arg(a, 0);
        List<FifoMatcher.OpenPosition> positions = null;
        Map<FifoMatcher.OpenPosition, JsonElement> source = new IdentityHashMap<>();
        if (!isNullish(list)) {
          positions = new ArrayList<>();
          for (JsonElement x : list.getAsJsonArray()) {
            if (isNullish(x)) {
              positions.add(null);
              continue;
            }
            JsonObject o = x.getAsJsonObject();
            FifoMatcher.OpenPosition p = new FifoMatcher.OpenPosition(str(o.get("account")), (int) exactLong(o.get("itemId")), str(o.get("item")),
              str(o.get("buyId")), longOr0(o.get("remaining")), longOr0(o.get("remaining")), EngineJson.longOrNull(o.get("firstSeen")),
              num(o.get("unitCost")), false);
            positions.add(p);
            source.put(p, x);
          }
        }
        Set<Integer> listed = intSet(arg(a, 3));
        FifoMatcher.OpenPosition chosen = HoldingTiers.pickPersistentOpenPosition(positions, str(arg(a, 1)), intSet(arg(a, 2)),
          listed == null ? null : listed::contains);
        got = chosen == null ? JsonNull.INSTANCE : source.get(chosen); // the very object the JS handed back
        break;
      }
      case "computeInventorySuggestion": {
        if (isNullish(arg(a, 1))) {
          got = EngineJson.pick(HoldingTiers.computeInventorySuggestion(null, null, null, null));
          break;
        }
        TreeMap<Integer, Long> inv = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : arg(a, 1).getAsJsonObject().entrySet()) inv.put(Integer.parseInt(e.getKey()), exactLong(e.getValue()));
        Map<Integer, String> names = new HashMap<>();
        for (JsonElement m : arg(a, 2).getAsJsonArray()) names.put((int) exactLong(m.getAsJsonObject().get("id")), str(m.getAsJsonObject().get("name")));
        JsonObject o = arg(a, 3).getAsJsonObject();
        HoldingTiers.InventoryOptions opts = new HoldingTiers.InventoryOptions();
        opts.blocklist = intSet(o.get("blocklist"));
        opts.positionItemIds = intSet(o.get("positionItemIds"));
        Set<Integer> members = intSet(o.get("members"));
        opts.membersBlocked = members == null ? null : members::contains;
        Map<Integer, Long> kept = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("keptForUse").entrySet()) kept.put(Integer.parseInt(e.getKey()), exactLong(e.getValue()));
        opts.keptForUse = kept;
        got = EngineJson.pick(HoldingTiers.computeInventorySuggestion(EngineJson.latest(arg(a, 0)), inv, names, opts));
        break;
      }
      case "comparableAsHistoryPick": {
        Pick p = null;
        if (!isNullish(arg(a, 0))) p = new Pick(1, null, str(arg(a, 0).getAsJsonObject().get("action")), 1, 1, 1, null);
        got = new JsonPrimitive(HoldingTiers.comparableAsHistoryPick(p, str(arg(a, 1))));
        break;
      }
      case "decideForecast": {
        PickChain.Forecast f = null;
        if (!isNullish(arg(a, 0))) {
          JsonObject o = arg(a, 0).getAsJsonObject();
          f = new PickChain.Forecast(null, (int) exactLong(o.get("dir")), num(o.get("confidence")), null, null);
        }
        got = new JsonPrimitive(PickChain.decideForecast(f, str(arg(a, 1))));
        break;
      }
      case "forecastPolicyNote": {
        String n = PickChain.forecastPolicyNote(str(arg(a, 0)));
        got = n == null ? JsonNull.INSTANCE : new JsonPrimitive(n);
        break;
      }
      case "recencyWeight":
        got = number(HistoryTier.recencyWeight(num(arg(a, 0)), num(arg(a, 1))));
        break;
      case "headlineProfit":
        got = number(Policy.headlineProfitOf(num(arg(a, 0)), num(arg(a, 1))));
        break;
      case "autoMinProfit":
        got = number(Policy.autoMinProfit(num(arg(a, 0))));
        break;
      case "suggestionVerdict":
        got = EngineJson.verdict(Verdict.suggestionVerdict(EngineJson.pick(arg(a, 0))));
        break;
      case "computeSuggestion":
        got = EngineJson.pick(history(a));
        break;
      default:
        throw new AssertionError("no Java port for tier vector function " + fn);
    }
    same(origin + " " + fn + a, vec.get("result"), got);
  }

  /** computeSuggestion, from the projected call: flips, /latest, the clock and the options with the gates as tables. */
  static Pick history(JsonArray a) throws IOException {
    List<History.Flip> flips = null;
    if (!isNullish(arg(a, 0))) {
      flips = new ArrayList<>();
      for (JsonElement f : arg(a, 0).getAsJsonArray()) flips.add(flip(f));
    }
    JsonObject p = arg(a, 3).getAsJsonObject();
    HistoryTier.Options o = new HistoryTier.Options();
    o.minProfit = num(p.get("minProfit"));
    o.maxSpend = num(p.get("maxSpend"));
    o.targetDurationMinutes = num(p.get("targetDurationMinutes"));
    o.maxStackShare = num(p.get("maxStackShare"));
    o.maxVolumeShare = num(p.get("maxVolumeShare"));
    o.volumeWindowShare = num(p.get("volumeWindowShare"));
    o.marginTaxMultiple = isNullish(p.get("marginTaxMultiple")) ? null : Double.valueOf(num(p.get("marginTaxMultiple"))); // ?? the level's bar
    o.risk = str(p.get("risk"));
    o.blocklist = intSet(p.get("blocklist"));
    o.captureCap = bool(p.get("captureCap"));
    o.requireMarginOverTax = bool(p.get("requireMarginOverTax"));
    o.volumes = EngineJson.hour(p.get("volumes"));
    if (!isNullish(p.get("typicalVolumes"))) {
      Map<Integer, Double> typical = new HashMap<>();
      for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("typicalVolumes").entrySet()) typical.put(Integer.parseInt(e.getKey()), num(e.getValue()));
      o.typicalVolumes = typical;
    }
    JsonObject gates = p.getAsJsonObject("gates");
    o.gate = new Sizing.ItemGate() {
      private JsonObject g(int id) {
        JsonElement e = gates.get(String.valueOf(id));
        return isNullish(e) ? null : e.getAsJsonObject();
      }

      @Override public boolean membersBlocked(int itemId) {
        return g(itemId) != null && bool(g(itemId).get("members"));
      }

      @Override public boolean focusBlocked(int itemId) {
        return g(itemId) != null && bool(g(itemId).get("focus"));
      }

      @Override public Double limitRemaining(int itemId) {
        JsonObject x = g(itemId);
        if (x == null || isNullish(x.get("limit"))) return null;
        return num(x.getAsJsonObject("limit").get("remaining"));
      }
    };
    return HistoryTier.computeSuggestion(flips, EngineJson.latest(arg(a, 1)), num(arg(a, 2)), o);
  }

  static History.Flip flip(JsonElement e) {
    if (isNullish(e)) return null;
    JsonObject o = e.getAsJsonObject();
    return new History.Flip(bool(o.get("removed")), num(o.get("itemId")), num(o.get("profit")), num(o.get("quantity")), num(o.get("hold")),
      isNullish(o.get("lastSell")) ? null : num(o.get("lastSell")), isNullish(o.get("confirmedAt")) ? null : num(o.get("confirmedAt")), str(o.get("item")));
  }

  // ------------------------------------------------------------------------------------------- the pick chain

  private static JsonElement chain(JsonObject c) {
    JsonArray cands = c.getAsJsonArray("cands");
    JsonObject hooks = c.getAsJsonObject("hooks");
    Set<Integer> blocklist = new LinkedHashSet<>();
    for (JsonElement x : c.getAsJsonArray("blocklist")) blocklist.add((int) exactLong(x));
    PickChain.Options o = new PickChain.Options();
    o.blocklist = blocklist;
    o.rank = bl -> {
      for (JsonElement x : cands) if (!bl.contains((int) exactLong(x.getAsJsonObject().get("itemId")))) return EngineJson.pick(x); // a fresh object each call
      return null;
    };
    o.policy = str(c.get("policy"));
    o.horizon = str(c.get("horizon"));
    o.requireCushion = bool(c.get("requireCushion"));
    if (!isNullish(c.get("maxAttempts"))) o.maxAttempts = (int) exactLong(c.get("maxAttempts"));
    JsonObject forecasts = c.getAsJsonObject("forecastFor");
    if (bool(hooks.get("forecast"))) o.forecastFor = id -> {
      JsonElement f = forecasts.get(String.valueOf(id));
      if (isNullish(f)) return null;
      JsonObject fo = f.getAsJsonObject();
      JsonObject fc = fo.getAsJsonObject("forecast");
      return new PickChain.Forecast(fc, (int) exactLong(fc.get("dir")), num(fc.get("confidence")), str(fo.get("sentence")),
        isNullish(fo.get("outlook")) ? null : fo.get("outlook"));
    };
    if (bool(hooks.get("cushion"))) o.cushionFor = cand -> hookCheck(c.getAsJsonObject("cushion").get(String.valueOf(cand.itemId)));
    if (bool(hooks.get("correlation"))) o.correlationFor = cand -> hookCheck(c.getAsJsonObject("correlation").get(String.valueOf(cand.itemId)));
    if (bool(hooks.get("support"))) o.supportFor = cand -> {
      JsonElement s = c.getAsJsonObject("support").get(String.valueOf(cand.itemId));
      if (isNullish(s)) return null;
      JsonObject so = s.getAsJsonObject();
      return new SellSupport.Result(bool(so.get("blocked")), str(so.get("warning")), EngineJson.detail(so.get("detail")));
    };
    JsonArray[] blockedRows = {null};
    JsonArray demoted = new JsonArray();
    if (bool(c.get("onBlocked"))) o.onBlocked = rows -> {
      JsonArray out = new JsonArray();
      for (PickChain.Blocked b : rows) {
        if (b.pick != null) out.add(EngineJson.pick(b.pick));
        else {
          JsonObject r = new JsonObject();
          r.add("itemId", number(b.itemId));
          EngineJson.put(r, "reason", b.reason);
          out.add(r);
        }
      }
      blockedRows[0] = out;
    };
    if (bool(c.get("onDemoted"))) o.onDemoted = p -> demoted.add(EngineJson.pick(p)); // as it was when demoted (the JS clones it there)
    Pick pick = PickChain.pick(o);
    JsonObject out = new JsonObject();
    out.add("pick", EngineJson.pick(pick));
    JsonArray bl = new JsonArray();
    for (Integer id : blocklist) bl.add(id);
    out.add("blocklist", bl);
    out.add("blocked", blockedRows[0] == null ? JsonNull.INSTANCE : blockedRows[0]);
    out.add("demoted", bool(c.get("onDemoted")) ? demoted : JsonNull.INSTANCE);
    return out;
  }

  private static PickChain.Check hookCheck(JsonElement e) {
    if (isNullish(e)) return null;
    JsonObject o = e.getAsJsonObject();
    return new PickChain.Check(bool(o.get("blocked")), str(o.get("note")));
  }

  // ------------------------------------------------------------------------------------------- the server's judgement

  private static JsonElement judge(JsonObject c) {
    try {
      Pick cand = EngineJson.pick(c.get("candidate"));
      JsonElement s = c.get("series");
      List<SellSupport.Point> series = s.isJsonPrimitive() ? null : EngineJson.points(s); // "fail": the fetch threw
      SellSupport.Detail d = SellSupport.reading(series, EngineJson.buckets(c.get("archived")), cand.itemId, cand.buyPrice, num(c.get("now")));
      return EngineJson.result(SellSupport.judge(d, cand, num(c.get("minProfit")), bool(c.get("minProfitChosen")), bool(c.get("requireMarginOverTax")),
        LevelSettings.forRequest(null)));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ------------------------------------------------------------------------------------------- constants (layer 0)

  private static void constants(JsonObject c) {
    eq(c, "SELL_SUPPORT_HOURS", SellSupport.SELL_SUPPORT_HOURS);
    eq(c, "STALE_SUPPORT_RATIO", SellSupport.STALE_SUPPORT_RATIO);
    eq(c, "SOFT_STALE_SUPPORT_RATIO", SellSupport.SOFT_STALE_SUPPORT_RATIO);
    eq(c, "UNFAVORABLE_FORECAST_CONFIDENCE", PickChain.UNFAVORABLE_FORECAST_CONFIDENCE);
    eq(c, "AUTO_MIN_PROFIT", Policy.AUTO_MIN_PROFIT);
    eq(c, "AUTO_STACK_SHARE", Policy.AUTO_STACK_SHARE);
    eq(c, "COINS_ITEM_ID", HoldingTiers.COINS_ITEM_ID);
    eq(c, "PLATINUM_TOKEN_ITEM_ID", HoldingTiers.PLATINUM_TOKEN_ITEM_ID);
    eq(c, "MIN_INVENTORY_VALUE", HoldingTiers.MIN_INVENTORY_VALUE);
    check(c.get("FORECAST_MAY_DROP_CANDIDATES").getAsBoolean() == PickChain.FORECAST_MAY_DROP_CANDIDATES, "FORECAST_MAY_DROP_CANDIDATES differs from the JS");
    check(Verdict.CLEAR.equals(c.get("CLEAR").getAsString()) && Verdict.CAUTION.equals(c.get("CAUTION").getAsString()) && Verdict.WARN.equals(c.get("WARN").getAsString()),
      "the verdict levels differ from the JS");
  }

  private static void eq(JsonObject c, String name, double javaValue) {
    compared++;
    check(c.has(name), "the JS constants carry no " + name);
    check(num(c.get(name)) == javaValue, name + ": Java " + javaValue + ", JS " + c.get(name));
  }
}
