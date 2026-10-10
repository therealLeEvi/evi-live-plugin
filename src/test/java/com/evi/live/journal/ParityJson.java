package com.evi.live.journal;

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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * Test-side helpers for the journal parity tests: loading fixtures from the CLASSPATH only (no file API), the comparator,
 * and the Java state as the JSON the bridge would have sent.
 *
 * <p>COMPARATOR RULES (the parity design's comparator section): absent and null are equal (JSON.stringify
 * drops undefined), numbers compare by value (5 and 5.0 are equal; -0 equals 0), strings compare EXACTLY,
 * arrays in order, objects over the union of their keys. Every difference is reported with its JSON path.
 */
final class ParityJson {
  private ParityJson() {}

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  /** A classpath resource, gunzipped when its name ends in .gz; null when absent. */
  static InputStream open(String resource) throws IOException {
    InputStream in = ParityJson.class.getResourceAsStream(resource);
    if (in == null) return null;
    return resource.endsWith(".gz") ? new GZIPInputStream(in) : in;
  }

  static JsonElement read(String resource) throws IOException {
    try (InputStream in = open(resource)) {
      if (in == null) return null;
      try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        return new JsonParser().parse(r);
      }
    }
  }

  /** One JSON text, parsed. (This Gson predates the static JsonParser.parseString.) */
  static JsonElement parse(String text) {
    return new JsonParser().parse(text);
  }

  static String readText(String resource) throws IOException {
    try (InputStream in = open(resource)) {
      if (in == null) return null;
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // ------------------------------------------------------------------------------------------- compare

  static boolean isNull(JsonElement e) {
    return e == null || e.isJsonNull();
  }

  static void diff(JsonElement exp, JsonElement act, String path, List<String> out) {
    if (out.size() > 200) return;
    if (isNull(exp) || isNull(act)) {
      if (isNull(exp) != isNull(act)) out.add(path + ": expected " + exp + " but was " + act);
      return;
    }
    if (exp.isJsonObject() && act.isJsonObject()) {
      TreeSet<String> keys = new TreeSet<>(exp.getAsJsonObject().keySet());
      keys.addAll(act.getAsJsonObject().keySet());
      for (String k : keys) diff(exp.getAsJsonObject().get(k), act.getAsJsonObject().get(k), path + "." + k, out);
      return;
    }
    if (exp.isJsonArray() && act.isJsonArray()) {
      JsonArray a = exp.getAsJsonArray(), b = act.getAsJsonArray();
      if (a.size() != b.size()) out.add(path + ": expected " + a.size() + " elements but was " + b.size());
      for (int i = 0; i < Math.min(a.size(), b.size()); i++) diff(a.get(i), b.get(i), path + "[" + i + "]", out);
      return;
    }
    if (exp.isJsonPrimitive() && act.isJsonPrimitive()) {
      JsonPrimitive x = exp.getAsJsonPrimitive(), y = act.getAsJsonPrimitive();
      if (x.isNumber() && y.isNumber()) {
        Number raw = y.getAsNumber();
        if (raw instanceof Long || raw instanceof Integer) {
          // A Java INTEGER against JS's double: EXACT. Comparing (double) of the long would hide a
          // difference past 2^53, where a long holds values a double cannot (the 6 Oct finding: an
          // imported total off by 2 passed this check while it converted both sides to double).
          double js = Js.number(x);
          if (!Js.isInteger(js) || new BigDecimal(js).compareTo(BigDecimal.valueOf(raw.longValue())) != 0)
            out.add(path + ": expected " + x + " but was " + y + " (exact integer comparison)");
          return;
        }
        if (Js.number(x).doubleValue() != Js.number(y).doubleValue()) out.add(path + ": expected " + x + " but was " + y);
        return;
      }
      if (x.isString() && y.isString()) {
        if (!x.getAsString().equals(y.getAsString())) out.add(path + ": expected " + x + " but was " + y);
        return;
      }
      if (x.isBoolean() && y.isBoolean()) {
        if (x.getAsBoolean() != y.getAsBoolean()) out.add(path + ": expected " + x + " but was " + y);
        return;
      }
    }
    out.add(path + ": expected " + exp + " but was " + act + " (different JSON types)");
  }

  static List<String> diff(JsonElement exp, JsonElement act) {
    List<String> out = new ArrayList<>();
    diff(exp, act, "$", out);
    return out;
  }

  // ------------------------------------------------------------------------------- tagged JSON values

  /** The recorder's tags: {"$num":"NaN"|"Infinity"|"-Infinity"|"-0"} and {"$u":1} for undefined. */
  static double tagNumber(JsonElement e) {
    if (e != null && e.isJsonObject() && e.getAsJsonObject().has("$num")) {
      String s = e.getAsJsonObject().get("$num").getAsString();
      switch (s) {
        case "NaN": return Double.NaN;
        case "Infinity": return Double.POSITIVE_INFINITY;
        case "-Infinity": return Double.NEGATIVE_INFINITY;
        case "-0": return -0.0;
        default: throw new IllegalArgumentException(s);
      }
    }
    if (e == null || e.isJsonNull() || isUndefined(e)) return Double.NaN;
    return Js.number(e);
  }

  static boolean isUndefined(JsonElement e) {
    return e == null || (e.isJsonObject() && e.getAsJsonObject().has("$u"));
  }

  /** An object with every {"$u":1} member removed (undefined is simply absent in JS). */
  static JsonObject stripUndefined(JsonObject o) {
    JsonObject out = new JsonObject();
    for (Map.Entry<String, JsonElement> e : o.entrySet()) if (!isUndefined(e.getValue())) out.add(e.getKey(), e.getValue());
    return out;
  }

  // ------------------------------------------------------------------------- Java values as bridge JSON

  static void put(JsonObject o, String k, Number v) {
    if (v == null) return;
    if (v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite())) return; // JSON.stringify: null
    o.addProperty(k, v);
  }

  static void put(JsonObject o, String k, String v) {
    if (v != null) o.addProperty(k, v);
  }

  static void put(JsonObject o, String k, Boolean v) {
    if (v != null) o.addProperty(k, v);
  }

  static JsonArray strings(List<String> list) {
    JsonArray a = new JsonArray();
    for (String s : list) {
      if (s == null) a.add(JsonNull.INSTANCE);
      else a.add(s);
    }
    return a;
  }

  static JsonArray ints(List<Integer> list) {
    JsonArray a = new JsonArray();
    for (Integer s : list) a.add(s);
    return a;
  }

  static JsonObject offer(Offer o) {
    JsonObject j = new JsonObject();
    put(j, "slot", o.slot);
    put(j, "state", o.state);
    put(j, "offerId", o.offerId);
    put(j, "itemId", o.itemId);
    put(j, "name", o.name);
    put(j, "price", o.price);
    put(j, "total", o.total);
    put(j, "filled", o.filled);
    put(j, "spent", o.spent);
    put(j, "knownStart", o.knownStart);
    put(j, "ticksToFill", o.ticksToFill);
    put(j, "account", o.account);
    put(j, "session", o.session);
    put(j, "firstSeen", o.firstSeen);
    put(j, "updated", o.updated);
    put(j, "completedAt", o.completedAt);
    put(j, "recorded", o.recorded);
    return j;
  }

  static JsonObject proceeds(Tax.Proceeds p) {
    if (p == null) return null;
    JsonObject j = new JsonObject();
    put(j, "net", p.net);
    put(j, "tax", p.tax);
    put(j, "exact", p.exact);
    return j;
  }

  static JsonObject manualFlip(ManualFlip f) {
    JsonObject j = new JsonObject();
    put(j, "id", f.id);
    put(j, "buyId", f.buyId);
    put(j, "sellId", f.sellId);
    if (f.sellIds != null) j.add("sellIds", strings(f.sellIds));
    put(j, "account", f.account);
    put(j, "itemId", f.itemId);
    put(j, "item", f.item);
    put(j, "quantity", f.quantity);
    put(j, "capital", f.capital);
    put(j, "netProceeds", f.netProceeds);
    put(j, "profit", f.profit);
    put(j, "firstBuy", f.firstBuy);
    put(j, "lastSell", f.lastSell);
    put(j, "hold", f.hold);
    put(j, "confirmedAt", f.confirmedAt);
    put(j, "source", f.source);
    put(j, "proceedsMethod", f.proceedsMethod);
    put(j, "removed", f.removed());
    put(j, "reopened", f.reopened());
    return j;
  }

  static JsonObject autoFlip(FifoMatcher.AutoFlip f) {
    JsonObject j = new JsonObject();
    put(j, "id", f.id);
    put(j, "account", f.account);
    put(j, "itemId", f.itemId);
    put(j, "item", f.item);
    put(j, "quantity", f.quantity);
    put(j, "capital", f.capital);
    put(j, "netProceeds", f.netProceeds);
    put(j, "profit", f.profit);
    put(j, "firstBuy", f.firstBuy);
    put(j, "lastSell", f.lastSell);
    put(j, "hold", f.hold);
    put(j, "buyId", f.buyId);
    j.add("sellIds", strings(f.sellIds));
    put(j, "exact", f.exact);
    put(j, "source", f.source);
    put(j, "partial", f.partial);
    put(j, "closedReason", f.closedReason);
    return j;
  }

  static JsonObject openPosition(FifoMatcher.OpenPosition p) {
    JsonObject j = new JsonObject();
    put(j, "account", p.account);
    put(j, "itemId", p.itemId);
    put(j, "item", p.item);
    put(j, "buyId", p.buyId);
    put(j, "remaining", p.remaining);
    put(j, "totalQty", p.totalQty);
    put(j, "firstSeen", p.firstSeen);
    put(j, "unitCost", p.unitCost);
    put(j, "partiallySold", p.partiallySold);
    if (p.endedUnseen != null) {
      JsonObject u = new JsonObject();
      put(u, "sellId", p.endedUnseen.sellId);
      put(u, "price", p.endedUnseen.price);
      put(u, "at", p.endedUnseen.at);
      put(u, "quantity", p.endedUnseen.quantity);
      j.add("endedUnseen", u);
    }
    return j;
  }

  static JsonObject importedFlip(ImportedFlip f) {
    JsonObject j = new JsonObject();
    put(j, "fp", f.fp);
    put(j, "source", f.source);
    put(j, "itemId", f.itemId);
    put(j, "item", f.item);
    put(j, "quantity", f.quantity);
    put(j, "capital", f.capital);
    put(j, "netProceeds", f.netProceeds);
    put(j, "profit", f.profit);
    put(j, "firstBuy", f.firstBuy);
    put(j, "lastSell", f.lastSell);
    put(j, "hold", f.hold);
    put(j, "account", f.account);
    j.addProperty("imported", true);
    return j;
  }

  static JsonObject dataHealth(FifoMatcher.DataHealth h) {
    JsonObject j = new JsonObject();
    put(j, "openPositions", h.openPositions);
    put(j, "openCost", h.openCost);
    j.add("openItemIds", ints(h.openItemIds));
    put(j, "unmatchedSales", h.unmatchedSales);
    put(j, "unmatchedGross", h.unmatchedGross);
    put(j, "personalUseSales", h.personalUseSales);
    put(j, "personalUseGross", h.personalUseGross);
    return j;
  }

  static JsonObject state(StoreState s) {
    JsonObject j = new JsonObject();
    put(j, "version", s.version);
    put(j, "serverTime", s.serverTime);
    JsonArray sessions = new JsonArray();
    for (StoreState.SessionView v : s.sessions) {
      JsonObject o = new JsonObject();
      put(o, "id", v.id);
      put(o, "account", v.account);
      put(o, "seq", v.seq);
      put(o, "lastSeen", v.lastSeen);
      put(o, "loggedIn", v.loggedIn);
      o.add("slots", strings(v.slots));
      put(o, "capturedAt", v.capturedAt);
      put(o, "live", v.live);
      sessions.add(o);
    }
    j.add("sessions", sessions);
    JsonArray active = new JsonArray(), occupied = new JsonArray(), completed = new JsonArray();
    for (Offer o : s.active) active.add(offer(o));
    for (Offer o : s.occupied) occupied.add(offer(o));
    for (StoreState.CompletedOffer c : s.completed) {
      JsonObject o = offer(c.offer);
      JsonObject p = proceeds(c.proceeds);
      if (p != null) o.add("proceeds", p);
      o.addProperty("marginCheck", c.marginCheck);
      completed.add(o);
    }
    j.add("active", active);
    j.add("occupied", occupied);
    j.add("completed", completed);
    JsonArray flips = new JsonArray(), removed = new JsonArray(), auto = new JsonArray(), open = new JsonArray(), unmatched = new JsonArray();
    for (ManualFlip f : s.flips) flips.add(manualFlip(f));
    for (ManualFlip f : s.removedFlips) removed.add(manualFlip(f));
    for (FifoMatcher.AutoFlip f : s.autoFlips) auto.add(autoFlip(f));
    for (FifoMatcher.OpenPosition p : s.autoOpenPositions) open.add(openPosition(p));
    for (FifoMatcher.UnmatchedSell u : s.autoUnmatchedSells) {
      JsonObject o = offer(u.offer);
      put(o, "unmatchedQty", u.unmatchedQty);
      put(o, "reason", u.reason);
      unmatched.add(o);
    }
    j.add("flips", flips);
    j.add("removedFlips", removed);
    j.add("autoFlips", auto);
    j.add("autoOpenPositions", open);
    j.add("autoUnmatchedSells", unmatched);
    j.add("dataHealth", dataHealth(s.dataHealth));
    j.add("personalUseBuyIds", strings(s.personalUseBuyIds));
    j.add("personalUseItemIds", ints(s.personalUseItemIds));
    j.add("personalUseItems", ints(s.personalUseItems));
    JsonObject kept = new JsonObject();
    for (Map.Entry<Integer, Long> e : s.personalUseKept.entrySet()) kept.addProperty(String.valueOf(e.getKey()), e.getValue());
    j.add("personalUseKept", kept);
    JsonArray imported = new JsonArray();
    for (ImportedFlip f : s.importedFlips) imported.add(importedFlip(f));
    j.add("importedFlips", imported);
    if (s.importedSummary != null) {
      JsonObject o = new JsonObject();
      put(o, "count", s.importedSummary.count);
      put(o, "items", s.importedSummary.items);
      put(o, "profit", s.importedSummary.profit);
      put(o, "from", s.importedSummary.from);
      put(o, "to", s.importedSummary.to);
      o.add("sources", strings(s.importedSummary.sources));
      j.add("importedSummary", o);
    }
    JsonArray closed = new JsonArray();
    for (StoreState.ClosedPosition c : s.closedPositions) {
      JsonObject o = new JsonObject();
      put(o, "buyId", c.buyId);
      put(o, "reason", c.reason);
      put(o, "at", c.at);
      put(o, "item", c.item);
      put(o, "itemId", c.itemId);
      put(o, "account", c.account);
      closed.add(o);
    }
    j.add("closedPositions", closed);
    put(j, "linkedLoginOffers", s.linkedLoginOffers);
    JsonArray unseen = new JsonArray();
    for (StoreState.EndedUnseenOffer u : s.endedUnseen) {
      JsonObject o = new JsonObject();
      put(o, "offerId", u.offerId);
      put(o, "at", u.at);
      unseen.add(o);
    }
    j.add("endedUnseen", unseen);
    put(j, "netProfit", s.netProfit);
    put(j, "tradeCount", s.tradeCount);
    return j;
  }

  /** Key ORDER of personalUseKept matters in JS output (integer keys ascend); value comparison ignores order, so check it separately. */
  static List<String> keyOrder(JsonObject o) {
    return new ArrayList<>(o.keySet());
  }

  static JsonObject profit(Profit p) {
    JsonObject j = new JsonObject();
    put(j, "since", p.since);
    put(j, "gp", p.gp);
    put(j, "trades", p.trades);
    put(j, "winners", p.winners);
    put(j, "losers", p.losers);
    put(j, "unmatchedSales", p.unmatchedSales);
    put(j, "openPositions", p.openPositions);
    return j;
  }
}
