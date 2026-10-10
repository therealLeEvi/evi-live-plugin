package com.evi.live.engine;

import com.evi.live.journal.Profit;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@link Engine.Response} written as the JSON the bridge's GET /api/suggestion sends ({@code send(200, {...})}), field for
 * field and in the bridge's key order: the body the plugin's {@code SuggestionResponse} parses. Pure; Gson's tree classes
 * only (no Gson instance is created here -- the plugin serialises the tree with the client's injected one).
 *
 * <p>NUMBERS ARE WRITTEN AS THEIR EXACT VALUE. A whole number is written whole (the plugin parses buyPrice, sellPrice,
 * breakEvenPrice and the quantities as {@code long}, and Gson refuses {@code 295.07} for a long, failing the WHOLE
 * response); a fraction (a sell-support average) as the exact decimal of its double, which parses back to the same double.
 * A field the engine left unset is ABSENT, as JSON.stringify drops {@code undefined}; a field the bridge sends as
 * {@code null} is written as null.
 *
 * <p>The golden-transcript parity test (EngineTranscriptTest) compares exactly this output with the bridge's recorded
 * bodies, so the serialiser the plugin will read is the one the parity suite proves.
 */
public final class ResponseJson {
  private ResponseJson() {}

  /** A double: its EXACT value; a non-finite one (never in an answer) as the parity recorder's tag. */
  public static JsonElement number(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      JsonObject t = new JsonObject();
      t.addProperty("$num", Double.isNaN(d) ? "NaN" : d > 0 ? "Infinity" : "-Infinity");
      return t;
    }
    return new JsonPrimitive(new BigDecimal(d));
  }

  /** A boxed number: null is JSON null, a Double its exact value, any integral type as written. */
  public static JsonElement number(Number n) {
    if (n == null) return JsonNull.INSTANCE;
    if (n instanceof Double || n instanceof Float) return number(n.doubleValue());
    return new JsonPrimitive(BigDecimal.valueOf(n.longValue()));
  }

  /** Sets {@code k} only when {@code n} is not null (an undefined JS field). */
  public static void put(JsonObject o, String k, Number n) {
    if (n != null) o.add(k, number(n));
  }

  public static void put(JsonObject o, String k, String s) {
    if (s != null) o.addProperty(k, s);
  }

  public static void put(JsonObject o, String k, Boolean b) {
    if (b != null) o.addProperty(k, b);
  }

  /** A JSON value with every number rewritten as its double's exact value (an opaque hook object, e.g. a forecast). */
  public static JsonElement exact(JsonElement e) {
    if (e == null || e.isJsonNull()) return e;
    if (e.isJsonPrimitive()) return e.getAsJsonPrimitive().isNumber() ? number(Double.parseDouble(e.getAsString())) : e;
    if (e.isJsonArray()) {
      JsonArray a = new JsonArray();
      for (JsonElement x : e.getAsJsonArray()) a.add(exact(x));
      return a;
    }
    JsonObject o = new JsonObject();
    for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) o.add(x.getKey(), exact(x.getValue()));
    return o;
  }

  /** A sell-support reading. */
  public static JsonElement detail(SellSupport.Detail d) {
    if (d == null) return JsonNull.INSTANCE;
    JsonObject j = new JsonObject();
    put(j, "units", d.units);
    put(j, "hours", d.hours);
    put(j, "averagePaid", d.averagePaid);
    put(j, "netAtAverage", d.netAtAverage);
    j.addProperty("supported", d.supported);
    put(j, "latestPaid", d.latestPaid);
    put(j, "staleness", d.staleness);
    put(j, "crashing", d.crashing);
    put(j, "thinMarket", d.thinMarket);
    put(j, "thinnerThanTax", d.thinnerThanTax);
    put(j, "belowMinimumAtSupportedPrice", d.belowMinimumAtSupportedPrice);
    put(j, "stale", d.stale);
    return j;
  }

  public static JsonElement verdict(Verdict.Result v) {
    if (v == null) return JsonNull.INSTANCE;
    JsonObject j = new JsonObject();
    j.addProperty("level", v.level);
    j.addProperty("label", v.label);
    JsonArray checks = new JsonArray();
    for (Verdict.Line l : v.checks) {
      JsonObject c = new JsonObject();
      if (l.ok == null) c.add("ok", JsonNull.INSTANCE);
      else c.addProperty("ok", l.ok);
      c.addProperty("text", l.text);
      checks.add(c);
    }
    j.add("checks", checks);
    return j;
  }

  /**
   * A suggestion as the JS object serialises (a null field is absent). An opaque hook object (the forecast, the fill
   * outlook) is written only when it is a JSON tree; the self-contained build ships no forecast hook (plan decision 13).
   */
  public static JsonElement pick(Pick p) {
    if (p == null) return JsonNull.INSTANCE;
    JsonObject j = new JsonObject();
    j.add("itemId", number(p.itemId));
    put(j, "name", p.name);
    put(j, "action", p.action);
    j.add("quantity", number(p.quantity));
    j.add("buyPrice", number(p.buyPrice));
    j.add("sellPrice", number(p.sellPrice));
    put(j, "source", p.source);
    put(j, "trades", p.trades);
    put(j, "reasoning", p.reasoning);
    put(j, "buyId", p.buyId);
    put(j, "breakEvenPrice", p.breakEvenPrice);
    put(j, "lossIfSoldNow", p.lossIfSoldNow);
    put(j, "netIfSoldNow", p.netIfSoldNow);
    if (p.endedUnseen != null) {
      JsonObject u = new JsonObject();
      u.addProperty("sellId", p.endedUnseen.sellId);
      u.add("price", number(p.endedUnseen.price));
      u.add("at", number(p.endedUnseen.at));
      u.add("quantity", number(p.endedUnseen.quantity));
      j.add("endedUnseen", u);
    }
    put(j, "persisted", p.persisted);
    if (p.sellSupport != null) j.add("sellSupport", detail(p.sellSupport));
    put(j, "demoted", p.demoted);
    if (p.forecast instanceof JsonElement) j.add("forecast", exact((JsonElement) p.forecast));
    if (p.fillOutlook instanceof JsonElement) j.add("fillOutlook", exact((JsonElement) p.fillOutlook));
    if (p.fillHistoryHoursTraded != null) {
      JsonObject fh = new JsonObject();
      fh.add("hoursTraded", number(p.fillHistoryHoursTraded));
      fh.add("hours", number(p.fillHistoryHours));
      j.add("fillHistory", fh);
    }
    if (p.verdict != null) j.add("verdict", verdict(p.verdict));
    put(j, "quotedProfit", p.quotedProfit);
    put(j, "expectedProfit", p.expectedProfit);
    put(j, "belowUsualBar", p.belowUsualBar);
    return j;
  }

  /** lookupItemPrice's {itemId, buyPrice, sellPrice}, plus withCostBasis's fields when it added them. */
  public static JsonElement price(ItemPrice p) {
    if (p == null) return JsonNull.INSTANCE;
    JsonObject j = new JsonObject();
    j.add("itemId", number(p.itemId));
    j.add("buyPrice", number(p.buyPrice));
    j.add("sellPrice", number(p.sellPrice));
    if (p.action != null) {
      j.addProperty("action", p.action);
      j.add("breakEvenPrice", number(p.breakEvenPrice));
      j.add("lossIfSoldNow", number(p.lossIfSoldNow));
    }
    return j;
  }

  /** One {@code relistAdvice} entry, the fields the server keeps. */
  public static JsonElement note(AdviceNote n) {
    JsonObject j = new JsonObject();
    j.add("itemId", number(n.itemId));
    put(j, "name", n.name);
    put(j, "message", n.message);
    put(j, "detail", n.detail);
    put(j, "level", n.level);
    put(j, "label", n.label);
    put(j, "figures", n.figures);
    put(j, "offerPrice", n.offerPrice);
    put(j, "marketPrice", n.marketPrice);
    put(j, "breakEven", n.breakEven);
    put(j, "suggestedPrice", n.suggestedPrice);
    put(j, "openMinutes", n.openMinutes);
    put(j, "belowBreakEven", n.belowBreakEven);
    put(j, "lossEach", n.lossEach);
    put(j, "lossTotal", n.lossTotal);
    put(j, "marginPerUnit", n.marginPerUnit);
    put(j, "netAtMarket", n.netAtMarket);
    put(j, "filled", n.filled);
    put(j, "total", n.total);
    put(j, "quantity", n.quantity);
    put(j, "unitCost", n.unitCost);
    put(j, "cost", n.cost);
    put(j, "worth", n.worth);
    put(j, "net", n.net);
    put(j, "holding", n.holding);
    put(j, "buyId", n.buyId);
    return j;
  }

  public static JsonArray notes(List<AdviceNote> list) {
    JsonArray a = new JsonArray();
    for (AdviceNote n : list) a.add(note(n));
    return a;
  }

  /** The sidebar's profit line (server.mjs profitSince): {@code since} is sent as null when the player never reset it. */
  public static JsonElement profit(Profit p) {
    if (p == null) return JsonNull.INSTANCE;
    JsonObject j = new JsonObject();
    j.add("since", number(p.since));
    j.add("gp", number(p.gp));
    j.add("trades", number(p.trades));
    j.add("winners", number(p.winners));
    j.add("losers", number(p.losers));
    j.add("unmatchedSales", number(p.unmatchedSales));
    j.add("openPositions", number(p.openPositions));
    return j;
  }

  /** One {@code heldBack} row: a held-back pick is the whole candidate (as the bridge pushes it), anything else {itemId, reason}. */
  public static JsonElement heldBack(Engine.HeldBack h) {
    if (h.pick != null) return pick(h.pick);
    JsonObject r = new JsonObject();
    r.add("itemId", number(h.itemId));
    put(r, "reason", h.reason);
    return r;
  }

  /** The whole body, in the bridge's key order; a failed request is {@code {error}} (the bridge's 502 body). */
  public static JsonObject response(Engine.Response r) {
    JsonObject j = new JsonObject();
    if (r.error != null) {
      j.addProperty("error", r.error);
      return j;
    }
    j.add("suggestion", pick(r.suggestion));
    JsonArray additional = new JsonArray();
    for (Pick a : r.additional) additional.add(pick(a));
    j.add("additional", additional);
    j.add("openItemPrice", price(r.openItemPrice));
    JsonArray slotPrices = new JsonArray();
    for (ItemPrice p : r.slotPrices) slotPrices.add(price(p));
    j.add("slotPrices", slotPrices);
    JsonArray slotFill = new JsonArray();
    for (Engine.SlotFill f : r.slotFill) {
      JsonObject o = new JsonObject();
      o.add("itemId", number(f.itemId));
      o.add("estimatedFillMinutes", number(f.estimatedFillMinutes));
      o.addProperty("likelyToFillInTime", f.likelyToFillInTime);
      slotFill.add(o);
    }
    j.add("slotFill", slotFill);
    j.add("relistAdvice", notes(r.relistAdvice));
    JsonObject slots = new JsonObject();
    slots.add("free", number(r.slots.free));
    slots.add("collectable", number(r.slots.collectable));
    slots.addProperty("full", r.slots.full);
    slots.addProperty("tight", r.slots.tight);
    slots.add("sellSlotsOwed", number(r.slots.sellSlotsOwed));
    slots.addProperty("buysHeldForExits", r.slots.buysHeldForExits);
    JsonArray positionItems = new JsonArray();
    for (Integer id : r.slots.positionItems) positionItems.add(number(id));
    slots.add("positionItems", positionItems);
    j.add("slots", slots);
    j.add("profit", profit(r.profit));
    if (r.reachable == null) j.add("reachable", JsonNull.INSTANCE);
    else {
      JsonObject o = new JsonObject();
      if (r.reachable.name == null) o.add("name", JsonNull.INSTANCE);
      else o.addProperty("name", r.reachable.name);
      o.add("itemId", number(r.reachable.itemId));
      o.add("profit", number(r.reachable.profit));
      o.add("atMinimum", number(r.reachable.atMinimum));
      j.add("reachable", o);
    }
    if (r.fellThroughFromHistory == null) j.add("fellThroughFromHistory", JsonNull.INSTANCE);
    else {
      JsonObject o = new JsonObject();
      put(o, "name", r.fellThroughFromHistory.name); // {name: undefined} is {} in JSON
      j.add("fellThroughFromHistory", o);
    }
    JsonArray heldBack = new JsonArray();
    for (Engine.HeldBack h : r.heldBack) heldBack.add(heldBack(h));
    j.add("heldBack", heldBack);
    return j;
  }
}
