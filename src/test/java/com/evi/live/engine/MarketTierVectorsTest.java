package com.evi.live.engine;

import static com.evi.live.engine.EngineJson.arg;
import static com.evi.live.engine.EngineJson.bool;
import static com.evi.live.engine.EngineJson.check;
import static com.evi.live.engine.EngineJson.intSet;
import static com.evi.live.engine.EngineJson.isNullish;
import static com.evi.live.engine.EngineJson.num;
import static com.evi.live.engine.EngineJson.str;

import com.evi.live.market.ItemCatalog;
import com.evi.live.market.MarketAggregates;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The market tier against what the REAL JS answered, value for value (tools/parity-market-tier-vectors.mjs, synthetic):
 * {@code computeMarketSuggestion} over every input the JS tests pass it (the booted-bridge tests' calls included, with the
 * server's own options and gates) and over generated edge cases -- ties in the score, huge prices, one-sided quotes, absent
 * against zero, Bigger positions, stack shares, paces, spread ranks, every sentence of the wording -- and the request's
 * RANKERS ({@link MarketTier.Ranking}) against server.mjs's own {@code rank} and {@code marketRankAt} closures, cut out of
 * GET /api/suggestion and evaluated on generated queries: the query parsed into the market options exactly as the bridge
 * parses it, then each ranker asked with its own blocklist, budget and minimum.
 *
 * <p>Exact equality, as every vectors test here: a number is the very double the JS computed, a string character for
 * character, absent equals null. Every expectation is a recorded JS answer; nothing is hand-computed here.
 */
public final class MarketTierVectorsTest {
  private static final List<String> diffs = new ArrayList<>();
  private static int compared;
  /** The clock the recorder pinned for every ranker (computeMarketSuggestion's own Date.now() default there). */
  private static double requestClock = Double.NaN;

  public static void main(String[] args) throws Exception {
    JsonElement root = EngineJson.read("/parity/market-tier-vectors.json.gz");
    check(root != null, "parity/market-tier-vectors.json.gz is missing from the test resources");
    JsonObject v = root.getAsJsonObject();
    check("evi-market-tier-vectors/1".equals(v.get("format").getAsString()), "unexpected vectors format");
    constants(v.getAsJsonObject("constants"));
    int tested = 0, testedAnswered = 0, generated = 0, generatedAnswered = 0, fromBootedBridges = 0, offTop = 0;
    for (JsonElement e : v.getAsJsonArray("tested")) {
      JsonObject vec = e.getAsJsonObject();
      if (call(vec, "test")) testedAnswered++;
      tested++;
      String src = vec.get("src").getAsString();
      if (src.contains("Wiring") || src.contains("approvedFixes") || src.contains("accountScope") || src.contains("cashAbsent")) fromBootedBridges++;
    }
    for (JsonElement e : v.getAsJsonArray("generated")) {
      JsonObject vec = e.getAsJsonObject();
      if (call(vec, "gen")) generatedAnswered++;
      JsonObject o = vec.getAsJsonArray("args").get(3).getAsJsonObject();
      if (!isNullish(vec.get("result")) && JsValues.isFinite(num(o.get("spreadRank"))) && num(o.get("spreadRank")) >= 1) offTop++;
      generated++;
    }
    int requests = 0, calls = 0, answered = 0, rankedAt = 0, spread = 0;
    requestClock = num(v.getAsJsonObject("requests").get("now"));
    check(JsValues.isFinite(requestClock), "the requests carry no clock");
    for (JsonElement e : v.getAsJsonObject("requests").getAsJsonArray("vectors")) {
      JsonObject r = e.getAsJsonObject();
      JsonObject in = r.getAsJsonObject("in");
      JsonElement got;
      try {
        got = request(in);
      } catch (RuntimeException | IOException ex) {
        JsonObject j = new JsonObject();
        j.addProperty("javaThrew", ex.toString());
        got = j;
      }
      same("request#" + requests++, r.get("out"), got);
      JsonArray results = r.getAsJsonObject("out").getAsJsonArray("results");
      JsonArray cs = in.getAsJsonArray("calls");
      for (int i = 0; i < results.size(); i++) {
        calls++;
        if (isNullish(results.get(i))) continue;
        answered++;
        if ("rankAt".equals(cs.get(i).getAsJsonObject().get("call").getAsString())) rankedAt++;
      }
      if (num(r.getAsJsonObject("out").getAsJsonObject("derived").get("spreadRank")) > 0) spread++;
    }
    if (!diffs.isEmpty()) {
      StringBuilder sb = new StringBuilder(diffs.size() + " market-tier divergence(s) from the JS engine:");
      for (String d : diffs.subList(0, Math.min(40, diffs.size()))) sb.append("\n  ").append(d);
      throw new AssertionError(sb.toString());
    }
    // Inert-test guards: a file silently not read, or a recorder that stopped reaching a case, would pass with nothing compared.
    // The floors are for the PUBLISHED subset (tools/parity-copy-public.mjs, 8 Oct 2026: the random cases and requests thinned to a
    // subset with the full set's exact coverage; 1,840 generated and 152 requests), not the master's 3,038 / 600.
    check(tested >= 150 && testedAnswered >= 100 && fromBootedBridges >= 40, "too few vectors from the JS tests (" + tested + ", " + testedAnswered
      + " answered, " + fromBootedBridges + " from booted bridges)");
    check(generated >= 1800 && generatedAnswered >= 1000 && offTop >= 150, "too few generated vectors (" + generated + ", " + generatedAnswered + " answered, "
      + offTop + " handed a lower rank)");
    check(requests >= 150 && answered >= 300 && rankedAt >= 170 && spread >= 50, "the requests no longer reach every ranker (" + requests + " requests, "
      + answered + " of " + calls + " calls answered, " + rankedAt + " by marketRankAt, " + spread + " with a spread rank)");
    System.out.println("PASS: market-tier vectors vs the JS engine (engine " + v.getAsJsonObject("engineLock").get("combined").getAsString().substring(0, 16) + "): "
      + tested + " recorded from the JS tests (" + testedAnswered + " answered, " + fromBootedBridges + " from booted bridges), " + generated + " generated ("
      + generatedAnswered + " answered, " + offTop + " handed a lower rank), " + requests + " requests through the rankers (" + answered + " of " + calls
      + " calls answered, " + rankedAt + " by marketRankAt, " + spread + " accounts with a spread rank); " + compared + " comparisons");
  }

  private static void same(String where, JsonElement exp, JsonElement act) {
    compared++;
    List<String> d = EngineJson.diff(exp, act);
    if (!d.isEmpty()) diffs.add(where + ": " + String.join("; ", d.subList(0, Math.min(3, d.size()))));
  }

  private static void constants(JsonObject c) {
    eq(c, "MIN_HOURLY_VOLUME", LevelSettings.LOW.minHourlyVolume);
    eq(c, "DEFAULT_MARKET_QUANTITY_CAP", Sizing.DEFAULT_MARKET_QUANTITY_CAP);
    eq(c, "DEFAULT_MAX_VOLUME_SHARE", Sizing.DEFAULT_MAX_VOLUME_SHARE);
    eq(c, "MAX_PRICE_AGE_MINUTES", Prices.MAX_PRICE_AGE_MINUTES);
    eq(c, "MARGIN_TAX_NO_HISTORY_MULTIPLE", LevelSettings.LOW.noHistoryMarginTaxMultiple);
    eq(c, "IMPLAUSIBLE_MARGIN_MULTIPLE", Gates.IMPLAUSIBLE_MARGIN_MULTIPLE);
    eq(c, "BUY_PRINT_MULTIPLE", Gates.BUY_PRINT_MULTIPLE);
    JsonObject bigger = c.getAsJsonObject("BIGGER_POSITIONS");
    check(num(bigger.get("minVolumeInWindow")) == Policy.BIGGER_POSITIONS_MIN_VOLUME_IN_WINDOW
      && num(bigger.get("volumeWindowShare")) == Policy.BIGGER_POSITIONS_WINDOW_SHARE && bigger.size() == 2, "BIGGER_POSITIONS differs from the JS: " + bigger);
    for (LevelSettings level : new LevelSettings[]{LevelSettings.LOW, LevelSettings.MEDIUM, LevelSettings.HIGH}) {
      check(level.marketRankBy == null && isNullish(c.get("MARKET_RANK_BY_DEFAULT")) && isNullish(c.get("MARKET_RANK_BY_BIGGER")),
        "the market ranking rule must be today's (none) at every level and under Bigger positions");
      check(level.noHistoryMarginTaxMultiple == LevelSettings.LOW.noHistoryMarginTaxMultiple && level.minHourlyVolume == LevelSettings.LOW.minHourlyVolume,
        "the market tier's bar and floor must be the same at every level today");
    }
  }

  private static void eq(JsonObject c, String name, double java) {
    check(num(c.get(name)) == java, name + ": the JS has " + c.get(name) + ", the Java " + java);
  }

  // ------------------------------------------------------------------------------------------- one tier call

  /** Replays one recorded call; true when the JS answered with a pick. */
  private static boolean call(JsonObject vec, String origin) {
    check("computeMarketSuggestion".equals(vec.get("fn").getAsString()), "unexpected function " + vec.get("fn"));
    JsonArray a = vec.getAsJsonArray("args");
    JsonElement got;
    try {
      got = EngineJson.pick(tier(a));
    } catch (RuntimeException | IOException ex) {
      JsonObject j = new JsonObject();
      j.addProperty("javaThrew", ex.toString());
      got = j;
    }
    same(origin + " " + vec.get("src") + " " + a.toString().substring(0, Math.min(400, a.toString().length())), vec.get("result"), got);
    return !isNullish(vec.get("result"));
  }

  /** The projected call: the mapping through the production parser, /latest and the hour through the production ingest. */
  static Pick tier(JsonArray a) throws IOException {
    JsonObject p = arg(a, 3).getAsJsonObject();
    MarketTier.Options o = new MarketTier.Options();
    o.minProfit = num(p.get("minProfit"));
    o.maxSpend = num(p.get("maxSpend"));
    o.targetDurationMinutes = num(p.get("targetDurationMinutes"));
    o.now = num(p.get("now"));
    o.maxPriceAgeMinutes = num(p.get("maxPriceAgeMinutes"));
    o.maxVolumeShare = num(p.get("maxVolumeShare"));
    o.volumeWindowShare = num(p.get("volumeWindowShare"));
    o.minVolumeInWindow = num(p.get("minVolumeInWindow"));
    o.minHourlyVolume = num(p.get("minHourlyVolume"));
    o.maxStackShare = num(p.get("maxStackShare"));
    o.marginTaxMultiple = isNullish(p.get("marginTaxMultiple")) ? null : Double.valueOf(num(p.get("marginTaxMultiple"))); // ?? the level's bar
    o.spreadRank = num(p.get("spreadRank"));
    o.rankBy = str(p.get("rankBy"));
    o.blocklist = intSet(p.get("blocklist"));
    o.taxFreeOnly = bool(p.get("taxFreeOnly"));
    o.captureCap = bool(p.get("captureCap"));
    o.requireMarginOverTax = bool(p.get("requireMarginOverTax"));
    o.rankPrices = rankPrices(p.get("rankPrices"));
    o.typicalVolumes = typical(p.get("typicalVolumes"));
    o.volumes = EngineJson.hour(p.get("volumes"));
    o.gate = gates(p.get("gates"));
    JsonElement mapping = arg(a, 0);
    return MarketTier.computeMarketSuggestion(isNullish(mapping) ? null : ItemCatalog.parse(mapping.toString()).items(), EngineJson.latest(arg(a, 1)),
      EngineJson.hour(arg(a, 2)), o);
  }

  static Map<Integer, MarketAggregates.RobustPrice> rankPrices(JsonElement e) {
    if (isNullish(e)) return null;
    Map<Integer, MarketAggregates.RobustPrice> m = new HashMap<>();
    for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
      if (isNullish(x.getValue()) || !x.getValue().isJsonObject()) continue; // a falsy reading is no reading
      JsonObject r = x.getValue().getAsJsonObject();
      // `r.high > 0 ? r.high : live`: a missing or non-positive side is the live price, which a 0 here is too
      double hi = num(r.get("high")), lo = num(r.get("low"));
      m.put(Integer.parseInt(x.getKey()), MarketAggregates.RobustPrice.of(hi > 0 ? (long) hi : 0, lo > 0 ? (long) lo : 0));
    }
    return m;
  }

  static Map<Integer, Double> typical(JsonElement e) {
    if (isNullish(e)) return null;
    Map<Integer, Double> m = new HashMap<>();
    for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet())
      if (!isNullish(x.getValue())) m.put(Integer.parseInt(x.getKey()), num(x.getValue()));
    return m;
  }

  /** The gates as recorded tables: {itemId: {members, focus, limit: {remaining} | null}}; none recorded is no gate at all. */
  static Sizing.ItemGate gates(JsonElement e) {
    if (isNullish(e)) return Sizing.ItemGate.NONE;
    JsonObject gates = e.getAsJsonObject();
    return new Sizing.ItemGate() {
      private JsonObject g(int id) {
        JsonElement x = gates.get(String.valueOf(id));
        return isNullish(x) ? null : x.getAsJsonObject();
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
  }

  // ------------------------------------------------------------------------------------------- one request

  /** URLSearchParams: '+' is a space, %XX decoded, and get() answers with the FIRST value of a repeated name. */
  static Map<String, String> params(String q) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String part : q.split("&")) {
      if (part.isEmpty()) continue;
      int eq = part.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
      String val = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
      out.putIfAbsent(k, val);
    }
    return out;
  }

  /** {@code Number(searchParams.get(k))}: an absent parameter is Number(null), which is 0. */
  static double number(String raw) {
    return raw == null ? 0 : JsValues.toNumber(raw);
  }

  /**
   * The query read into the market options exactly as GET /api/suggestion reads it (suggestionPolicy, maxSpendFromQuery,
   * the duration, the stack share, the account, Bigger positions, the spread rank from the preferences), the rankers built,
   * and each recorded call asked of them.
   */
  static JsonElement request(JsonObject in) throws IOException {
    Map<String, String> q = params(str(in.get("query")));
    JsonObject prefs = in.getAsJsonObject("prefs");
    double askedFor = Math.max(0, orZero(number(q.get("minProfit"))));
    boolean minProfitChosen = askedFor > 0;
    double cashParam = QueryValues.cashFromQuery(q.get("cash"));
    double minProfit = minProfitChosen ? askedFor : Policy.autoMinProfit(cashParam);
    boolean requireMarginOverTax = askedFor != 1;
    boolean taxFreeOnly = "starter".equals(q.get("profile"));
    boolean bigger = "bigger".equals(q.get("sizing"));
    Double maxSpend = QueryValues.maxSpendFromQuery(q.get("cash"), q.get("account"));
    Double target = Sizing.duration(number(q.get("duration")));
    String account = q.get("account") == null || q.get("account").isEmpty() ? null : q.get("account");
    double maxStackShare = QueryValues.maxStackShare(q.get("stackShare"));
    double spreadRank = SpreadRank.spreadRankFor(account, num(prefs.get("spread")));
    LevelSettings level = LevelSettings.forRequest(null);

    MarketTier.Options shared = new MarketTier.Options();
    shared.targetDurationMinutes = target == null ? Double.NaN : target;
    shared.now = requestClock;
    shared.maxStackShare = maxStackShare;
    shared.taxFreeOnly = taxFreeOnly;
    shared.requireMarginOverTax = requireMarginOverTax;
    shared.rankPrices = rankPrices(in.get("rankPrices"));
    shared.rankBy = level.marketRankBy;
    shared.typicalVolumes = typical(in.get("typical"));
    shared.captureCap = bool(prefs.get("captureCap"));
    if (bigger) {
      shared.volumeWindowShare = Policy.BIGGER_POSITIONS_WINDOW_SHARE;
      shared.minVolumeInWindow = Policy.BIGGER_POSITIONS_MIN_VOLUME_IN_WINDOW;
    }
    shared.gate = gates(in.get("gates"));
    shared.level = level;
    JsonElement mapping = in.get("mapping");
    MarketTier.Ranking ranking = new MarketTier.Ranking(isNullish(mapping) ? null : ItemCatalog.parse(mapping.toString()).items(),
      EngineJson.latest(in.get("latest")), EngineJson.hour(in.get("volumes")), shared, maxSpend, spreadRank);

    JsonArray results = new JsonArray();
    for (JsonElement ce : in.getAsJsonArray("calls")) {
      JsonObject c = ce.getAsJsonObject();
      Set<Integer> bl = new LinkedHashSet<>();
      for (JsonElement x : c.getAsJsonArray("bl")) bl.add((int) EngineJson.exactLong(x));
      if ("rank".equals(c.get("call").getAsString())) results.add(EngineJson.pick(ranking.rank(bl, minProfit)));
      else {
        JsonElement s = c.get("spend"), m = c.get("mp");
        Double spend = s != null && s.isJsonPrimitive() && s.getAsJsonPrimitive().isString() ? maxSpend : isNullish(s) ? null : Double.valueOf(num(s));
        double mp = m.isJsonPrimitive() && m.getAsJsonPrimitive().isString() ? minProfit : num(m);
        results.add(EngineJson.pick(ranking.rankAt(bl, spend, mp)));
      }
    }
    JsonObject derived = new JsonObject();
    if (JsValues.isFinite(maxStackShare)) derived.add("maxStackShare", EngineJson.number(maxStackShare));
    derived.add("spreadRank", EngineJson.number(spreadRank));
    if (target != null) derived.add("targetDurationMinutes", EngineJson.number(target));
    derived.add("minProfit", EngineJson.number(minProfit));
    derived.addProperty("requireMarginOverTax", requireMarginOverTax);
    derived.addProperty("taxFreeOnly", taxFreeOnly);
    if (maxSpend != null) derived.add("maxSpend", EngineJson.number(maxSpend));
    if (bigger) {
      derived.add("volumeWindowShare", EngineJson.number(Policy.BIGGER_POSITIONS_WINDOW_SHARE));
      derived.add("minVolumeInWindow", EngineJson.number(Policy.BIGGER_POSITIONS_MIN_VOLUME_IN_WINDOW));
    }
    if (!isNullish(prefs.get("captureCap"))) derived.add("captureCap", new JsonPrimitive(bool(prefs.get("captureCap"))));
    JsonObject out = new JsonObject();
    out.add("results", results);
    out.add("derived", derived);
    return out;
  }

  private static double orZero(double d) {
    return Double.isNaN(d) || d == 0 ? 0 : d; // (x || 0)
  }
}
