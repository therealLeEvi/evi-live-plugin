package com.evi.live.engine;

import com.evi.live.journal.Js;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * Reading the recorders' JSON into the engine's Java types, and writing the engine's answers back as the JSON the JS
 * would have produced, for the Phase 3b parity tests (TierVectorsTest, EngineTranscriptTest). Test code only.
 *
 * <p>The decoding rules are the recorders' (tools/parity-engine-codec.mjs): {@code {"$u":1}} is undefined, {@code
 * {"$num":"NaN"|"Infinity"|"-Infinity"|"-0"}} the numbers JSON cannot carry. The comparison is EXACT: a number must be the
 * very double the JS computed, a string character for character; absent, null and undefined are equal (JSON.stringify
 * drops undefined).
 */
final class EngineJson {
  private EngineJson() {}

  // ------------------------------------------------------------------------------------------- reading values

  static boolean isUndefined(JsonElement e) {
    return e == null || (e.isJsonObject() && e.getAsJsonObject().has("$u"));
  }

  static boolean isNullish(JsonElement e) {
    return isUndefined(e) || e.isJsonNull();
  }

  /** A JS number: undefined and null are NaN, tags decoded. */
  static double num(JsonElement e) {
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

  /** A number that may be absent: null for undefined/null. */
  static Double numOrNull(JsonElement e) {
    return isNullish(e) ? null : num(e);
  }

  static long exactLong(JsonElement e) {
    return JsValues.exactLong(num(e));
  }

  static long longOr0(JsonElement e) {
    return isNullish(e) ? 0 : exactLong(e);
  }

  static Long longOrNull(JsonElement e) {
    return isNullish(e) ? null : Long.valueOf(exactLong(e));
  }

  static Integer intOrNull(JsonElement e) {
    return isNullish(e) ? null : Integer.valueOf((int) exactLong(e));
  }

  static boolean bool(JsonElement e) {
    return !isNullish(e) && e.getAsBoolean();
  }

  static Boolean boolOrNull(JsonElement e) {
    return isNullish(e) ? null : e.getAsBoolean();
  }

  static String str(JsonElement e) {
    if (isNullish(e)) return null;
    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
    throw new AssertionError("not a string: " + e);
  }

  static JsonElement arg(JsonArray a, int i) {
    return i < a.size() ? a.get(i) : null;
  }

  static Set<Integer> intSet(JsonElement e) {
    if (isNullish(e)) return null;
    Set<Integer> s = new LinkedHashSet<>();
    for (JsonElement x : e.getAsJsonArray()) s.add((int) exactLong(x));
    return s;
  }

  // ------------------------------------------------------------------------------------------- reading engine types

  /** A Wiki /latest data object, through the production ingest (WikiJson.latest). Null for undefined/null. */
  static LatestPrices latest(JsonElement data) throws IOException {
    if (isNullish(data)) return null;
    JsonObject body = new JsonObject();
    body.add("data", data);
    return WikiJson.latest(body.toString());
  }

  /** A Wiki /1h data object, through the production ingest (WikiJson.hour). Null for undefined/null. */
  static HourBucket hour(JsonElement data) throws IOException {
    if (isNullish(data)) return null;
    JsonObject body = new JsonObject();
    body.addProperty("timestamp", 0);
    body.add("data", data);
    return WikiJson.hour(body.toString());
  }

  /** An archive bucket {ts, d} through the production parser (HourBucket.parseLine). */
  static HourBucket bucket(JsonElement b) throws IOException {
    return HourBucket.parseLine(b.toString());
  }

  static List<HourBucket> buckets(JsonElement e) throws IOException {
    if (isNullish(e)) return null;
    List<HourBucket> out = new ArrayList<>();
    for (JsonElement b : e.getAsJsonArray()) out.add(bucket(b));
    return out;
  }

  /** One series point; a JSON null stays null (the Wiki body {@code {"data":[null]}} -- the JS throws on it). */
  static SellSupport.Point point(JsonElement e) {
    if (isNullish(e)) return null;
    JsonObject o = e.getAsJsonObject();
    return new SellSupport.Point(num(o.get("timestamp")), numOrNull(o.get("avgHighPrice")), numOrNull(o.get("highPriceVolume")),
      numOrNull(o.get("avgLowPrice")), numOrNull(o.get("lowPriceVolume")));
  }

  static List<SellSupport.Point> points(JsonElement e) {
    if (isNullish(e)) return null;
    List<SellSupport.Point> out = new ArrayList<>();
    for (JsonElement p : e.getAsJsonArray()) out.add(point(p));
    return out;
  }

  static SellSupport.Detail detail(JsonElement e) {
    if (isNullish(e)) return null;
    JsonObject o = e.getAsJsonObject();
    SellSupport.Detail d = new SellSupport.Detail(numOrNull(o.get("units")), intOrNull(o.get("hours")), numOrNull(o.get("averagePaid")),
      numOrNull(o.get("netAtAverage")), bool(o.get("supported")), numOrNull(o.get("latestPaid")), numOrNull(o.get("staleness")));
    d.crashing = boolOrNull(o.get("crashing"));
    d.thinMarket = boolOrNull(o.get("thinMarket"));
    d.thinnerThanTax = boolOrNull(o.get("thinnerThanTax"));
    d.belowMinimumAtSupportedPrice = boolOrNull(o.get("belowMinimumAtSupportedPrice"));
    d.stale = boolOrNull(o.get("stale"));
    return d;
  }

  /** An open position's endedUnseen mark {sellId, price, at, quantity} (null when absent). */
  static com.evi.live.journal.FifoMatcher.EndedUnseenMark endedUnseen(JsonElement e) {
    if (isNullish(e)) return null;
    JsonObject o = e.getAsJsonObject();
    return new com.evi.live.journal.FifoMatcher.EndedUnseenMark(str(o.get("sellId")), exactLong(o.get("price")), exactLong(o.get("at")),
      exactLong(o.get("quantity")));
  }

  /** A suggestion object as a Pick (the fields a tier or a check sets; the rest stays null). */
  static Pick pick(JsonElement e) {
    if (isNullish(e)) return null;
    JsonObject o = e.getAsJsonObject();
    Pick p = new Pick((int) exactLong(o.get("itemId")), str(o.get("name")), str(o.get("action")), longOr0(o.get("quantity")),
      exactLong(o.get("buyPrice")), exactLong(o.get("sellPrice")), str(o.get("source")));
    p.trades = intOrNull(o.get("trades"));
    p.reasoning = str(o.get("reasoning"));
    p.buyId = str(o.get("buyId"));
    p.breakEvenPrice = longOrNull(o.get("breakEvenPrice"));
    p.lossIfSoldNow = longOrNull(o.get("lossIfSoldNow"));
    p.netIfSoldNow = numOrNull(o.get("netIfSoldNow"));
    p.endedUnseen = endedUnseen(o.get("endedUnseen"));
    p.sellSupport = detail(o.get("sellSupport"));
    p.demoted = boolOrNull(o.get("demoted"));
    JsonElement fh = o.get("fillHistory");
    if (!isNullish(fh)) {
      p.fillHistoryHoursTraded = intOrNull(fh.getAsJsonObject().get("hoursTraded"));
      p.fillHistoryHours = intOrNull(fh.getAsJsonObject().get("hours"));
    }
    return p;
  }

  // ------------------------------------------------------------------------------------------- writing answers

  // The writers are the PRODUCTION serialiser's (ResponseJson), so every parity test that writes an answer proves the very
  // JSON the plugin will parse.

  /** A double: its EXACT value (non-finite as the recorder's tags). */
  static JsonElement number(double d) {
    return ResponseJson.number(d);
  }

  static JsonElement number(Number n) {
    return ResponseJson.number(n);
  }

  static void put(JsonObject o, String k, Number n) {
    ResponseJson.put(o, k, n);
  }

  static void put(JsonObject o, String k, String s) {
    ResponseJson.put(o, k, s);
  }

  static void put(JsonObject o, String k, Boolean b) {
    ResponseJson.put(o, k, b);
  }

  static JsonElement point(SellSupport.Point p) {
    JsonObject j = new JsonObject();
    j.add("timestamp", number(p.timestamp));
    put(j, "avgHighPrice", p.avgHighPrice);
    put(j, "highPriceVolume", p.highPriceVolume);
    put(j, "avgLowPrice", p.avgLowPrice);
    put(j, "lowPriceVolume", p.lowPriceVolume);
    return j;
  }

  static JsonElement detail(SellSupport.Detail d) {
    return ResponseJson.detail(d);
  }

  static JsonElement verdict(Verdict.Result v) {
    return ResponseJson.verdict(v);
  }

  /** A Pick as the JS object would serialise (a null field is absent). Opaque hook objects must be JsonElements. */
  static JsonElement pick(Pick p) {
    return ResponseJson.pick(p);
  }

  /** A JSON value with every number rewritten as its double's exact value (how every Java answer is written). */
  static JsonElement exact(JsonElement e) {
    return ResponseJson.exact(e);
  }

  static JsonElement result(SellSupport.Result r) {
    if (r == null) return JsonNull.INSTANCE;
    JsonObject j = new JsonObject();
    if (r.blocked) j.addProperty("blocked", true);
    put(j, "warning", r.warning);
    if (r.detail != null) j.add("detail", detail(r.detail));
    return j;
  }

  // ------------------------------------------------------------------------------------------- comparison

  static boolean isNumberish(JsonElement e) {
    return e != null && (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() || e.isJsonObject() && e.getAsJsonObject().has("$num"));
  }

  /** Every difference between the JS answer {@code exp} and the Java answer {@code act}, with its JSON path. */
  static void diff(JsonElement exp, JsonElement act, String path, List<String> out) {
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
      else ok = act.isJsonPrimitive() && new BigDecimal(act.getAsString()).compareTo(new BigDecimal(e)) == 0;
      if (!ok) out.add(path + ": expected " + exp + " but was " + act);
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

  static List<String> diff(JsonElement exp, JsonElement act) {
    List<String> d = new ArrayList<>();
    diff(exp, act, "$", d);
    return d;
  }

  // ------------------------------------------------------------------------------------------- plumbing

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  /** A classpath resource, gunzipped; null when absent (no file API: the classpath only). */
  static JsonElement read(String resource) throws IOException {
    InputStream raw = EngineJson.class.getResourceAsStream(resource);
    if (raw == null) return null;
    try (InputStream in = resource.endsWith(".gz") ? new GZIPInputStream(raw) : raw; Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return new JsonParser().parse(r);
    }
  }

  /** Map keys of a JSON object as item ids. */
  static Map<String, JsonElement> entries(JsonObject o) {
    Map<String, JsonElement> m = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> e : o.entrySet()) m.put(e.getKey(), e.getValue());
    return m;
  }
}
