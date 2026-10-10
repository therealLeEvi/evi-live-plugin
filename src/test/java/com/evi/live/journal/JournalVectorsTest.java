package com.evi.live.journal;

import static com.evi.live.journal.ParityJson.check;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The Java journal engine against what the REAL JS engine answered for generated edge cases
 * (recorded from the real JS engine by the project's own recorder; synthetic, so it
 * ships with the plugin's tests). Every expectation here is a recorded JS answer, not a hand-computed one.
 *
 * <p>Two halves. Function vectors: estimateUnitTax, saleProceeds, breakEvenSellPrice (.5 costs, the 250m
 * cap boundary, above 2^31, exempt ids, junk input), the whole exempt set, isMarginCheck and availableAt,
 * heldCostBasis, validatePacket from raw JSON text, the journal line's string and number formatting, and
 * integer GP arithmetic (+, -, x, Math.round) past 2^53 and past 2^63.
 * Scenarios: op sequences against a real Store (rounding ties, totals past 2^31, two accounts, a restart
 * mid-offer, partial fill then cancel, relist, probes, personal use, recorded purchases, manual review,
 * imports, closing positions, ingest refusals, the buy-limit window, fill ordering, the exact boundaries of
 * liveness / continuation / closing / personal-use cover, and GP totals past 2^53), each answer compared,
 * and the journal the Java store wrote compared with the JS journal LINE BY LINE, byte for byte.
 */
public final class JournalVectorsTest {
  private static final List<String> diffs = new ArrayList<>();
  private static int compared;
  private static int knownNoAccountDivergences;
  private static int knownBeyondLong;

  public static void main(String[] args) throws Exception {
    JsonElement root = ParityJson.read("/parity/journal-vectors.json.gz");
    check(root != null, "parity/journal-vectors.json.gz is missing from the test resources");
    JsonObject v = root.getAsJsonObject();
    check("evi-journal-vectors/1".equals(v.get("format").getAsString()), "unexpected vectors format");
    constants(v.getAsJsonObject("constants"));
    int functions = 0;
    for (JsonElement e : v.getAsJsonArray("functions")) {
      function(e.getAsJsonObject());
      functions++;
    }
    int ops = 0, lines = 0;
    for (JsonElement e : v.getAsJsonArray("scenarios")) {
      JsonObject s = e.getAsJsonObject();
      ops += s.getAsJsonArray("ops").size();
      lines += s.getAsJsonArray("journal").size();
      scenario(s);
    }
    if (!diffs.isEmpty()) {
      StringBuilder sb = new StringBuilder(diffs.size() + " journal-vector divergence(s) from the JS engine:");
      for (String d : diffs.subList(0, Math.min(40, diffs.size()))) sb.append("\n  ").append(d);
      throw new AssertionError(sb.toString());
    }
    // Inert-test guard: a vectors file that silently stopped being read would pass with nothing compared.
    check(functions >= 400 && ops >= 300 && lines >= 190 && compared >= 900,
      "too little was compared (" + functions + " functions, " + ops + " ops, " + lines + " lines, " + compared + " comparisons)");
    check(knownNoAccountDivergences > 0, "the no-account heldCostBasis vectors no longer exercise the JS predicate");
    check(knownBeyondLong >= 50, "too few arithmetic vectors reach past 2^63 (" + knownBeyondLong + "): the refusal is no longer exercised");
    System.out.println("PASS: journal vectors vs the JS engine (engine " + v.getAsJsonObject("engineLock").get("combined").getAsString().substring(0, 16)
      + "): " + functions + " function vectors, " + v.getAsJsonArray("scenarios").size() + " Store scenarios, " + ops + " ops, "
      + lines + " journal lines byte-identical, " + compared + " comparisons; heldCostBasis scoped by design where JS sums every account ("
      + knownNoAccountDivergences + " no-account calls)");
  }

  private static void same(String where, JsonElement exp, JsonElement act) {
    compared++;
    for (String d : ParityJson.diff(exp, act)) diffs.add(where + ": " + d);
  }

  private static void constants(JsonObject c) {
    same("constants.taxCap", c.get("taxCap"), new JsonPrimitive(Tax.CAP));
    same("constants.taxEraStart", c.get("taxEraStart"), new JsonPrimitive(Tax.TAX_ERA_START_MS));
    same("constants.marginCheckTicks", c.get("marginCheckTicks"), new JsonPrimitive(FifoMatcher.MARGIN_CHECK_TICKS));
    same("constants.gameTickMs", c.get("gameTickMs"), new JsonPrimitive(FifoMatcher.GAME_TICK_MS));
    same("constants.limitWindowMs", c.get("limitWindowMs"), new JsonPrimitive(BuyLimitUsage.WINDOW_MS));
    same("constants.packetIntMax", c.get("packetIntMax"), new JsonPrimitive((long) Js.INT32_MAX));
  }

  // An Offer from a partial JS object: absent numbers are 0 (JS would compare undefined, which every
  // recorded vector avoids for the fields it reads), absent longs that may be absent stay null.
  static Offer offer(JsonObject o) {
    o = ParityJson.stripUndefined(o);
    Double ticks = Js.number(o.get("ticksToFill"));
    return new Offer(i(o, "slot"), Js.string(o.get("state")), Js.string(o.get("offerId")), i(o, "itemId"), Js.string(o.get("name")),
      l(o, "price"), l(o, "total"), l(o, "filled"), l(o, "spent"), o.has("knownStart") && o.get("knownStart").getAsBoolean(),
      ticks != null && Js.isInteger(ticks) ? Integer.valueOf((int) (double) ticks) : null, Js.string(o.get("account")),
      Js.string(o.get("session")), ln(o, "firstSeen"), ln(o, "updated"), ln(o, "completedAt"), null);
  }

  private static int i(JsonObject o, String k) {
    Double d = Js.number(o.get(k));
    return d == null ? 0 : (int) (double) d;
  }

  private static long l(JsonObject o, String k) {
    Double d = Js.number(o.get(k));
    return d == null ? 0 : (long) (double) d;
  }

  private static Long ln(JsonObject o, String k) {
    Double d = Js.number(o.get(k));
    return d == null ? null : (long) (double) d;
  }

  private static JsonElement num(Number n) {
    if (n == null) return JsonNull.INSTANCE;
    if (n instanceof Double && (((Double) n).isNaN() || ((Double) n).isInfinite())) return JsonNull.INSTANCE;
    return new JsonPrimitive(n);
  }

  private static void function(JsonObject f) {
    String fn = f.get("fn").getAsString();
    JsonArray a = f.has("args") ? f.getAsJsonArray("args") : null;
    String where = fn + " " + (a != null ? a : f.get("text").getAsString());
    if (where.length() > 160) where = where.substring(0, 160) + "...";
    switch (fn) {
      case "estimateUnitTax":
        same(where, f.get("result"), new JsonPrimitive(Tax.estimateUnitTax(a.get(0).getAsInt(), ParityJson.tagNumber(a.get(1)))));
        break;
      case "saleProceeds":
        same(where, f.get("result"), ParityJson.proceeds(Tax.saleProceeds(offer(a.get(0).getAsJsonObject()))));
        break;
      case "breakEvenSellPrice":
        same(where, f.get("result"), num(Tax.breakEvenSellPrice(a.get(0).getAsInt(), ParityJson.tagNumber(a.get(1)))));
        break;
      case "isMarginCheck":
        same(where, f.get("result"), new JsonPrimitive(FifoMatcher.isMarginCheck(offer(a.get(0).getAsJsonObject()))));
        break;
      case "availableAt":
        same(where, f.get("result"), num(FifoMatcher.availableAt(offer(a.get(0).getAsJsonObject()))));
        break;
      case "heldCostBasis": {
        List<FifoMatcher.OpenPosition> positions = new ArrayList<>();
        for (JsonElement p : a.get(0).getAsJsonArray()) {
          JsonObject o = p.getAsJsonObject();
          positions.add(new FifoMatcher.OpenPosition(Js.string(o.get("account")), i(o, "itemId"), null, null, l(o, "remaining"),
            l(o, "remaining"), null, Js.number(o.get("unitCost")), false));
        }
        List<Offer> active = new ArrayList<>();
        for (JsonElement o : a.get(1).getAsJsonArray()) active.add(offer(o.getAsJsonObject()));
        boolean noAccount = ParityJson.isUndefined(a.get(3)) || a.get(3).isJsonNull();
        CostBasis got = CostBasis.held(positions, active, a.get(2).getAsInt(), noAccount ? null : a.get(3).getAsString());
        if (noAccount) {
          // BY DESIGN (the PORT WARNING): no account, no answer. JS sums every account here.
          compared++;
          if (got != null) diffs.add(where + ": Java must answer null without an account, got " + got.unitCost);
          if (!ParityJson.isNull(f.get("result"))) knownNoAccountDivergences++;
          break;
        }
        JsonObject j = null;
        if (got != null) {
          j = new JsonObject();
          j.addProperty("unitCost", got.unitCost);
          j.addProperty("quantity", got.quantity);
        }
        same(where, f.get("result"), j);
        break;
      }
      case "validatePacket": {
        JsonElement got;
        try {
          got = packetJson(Packet.validate(ParityJson.parse(f.get("text").getAsString())));
        } catch (JournalException e) {
          JsonObject err = new JsonObject();
          err.addProperty("error", e.getMessage());
          got = err;
        }
        JsonElement exp = f.has("error") ? errorJson(f.get("error").getAsString()) : f.get("result");
        same(where, exp, got);
        break;
      }
      case "jsonString":
        compared++;
        if (!Js.quote(a.get(0).getAsString()).equals(f.get("result").getAsString()))
          diffs.add(where + ": JSON.stringify gave " + f.get("result").getAsString() + " but Java wrote " + Js.quote(a.get(0).getAsString()));
        break;
      case "numberToString": {
        compared++;
        String got = Js.numberToString(ParityJson.tagNumber(a.get(0)));
        if (!got.equals(f.get("result").getAsString())) diffs.add(where + ": String(x) is " + f.get("result").getAsString() + " but Java wrote " + got);
        break;
      }
      case "jsAdd":
      case "jsSubtract":
      case "jsMultiply":
      case "jsRound": {
        // JS's double answer, which Java must hold EXACTLY as a long -- or, when that double is NaN or
        // at/past 2^63, refuse with an ArithmeticException (never wrap, never saturate).
        compared++;
        double js = ParityJson.tagNumber(f.get("result"));
        boolean longCanHold = !Double.isNaN(js) && js < 0x1p63 && js >= -0x1p63;
        Long got;
        try {
          long x = (long) ParityJson.tagNumber(a.get(0));
          got = "jsRound".equals(fn) ? Js.round(ParityJson.tagNumber(a.get(0)))
            : "jsAdd".equals(fn) ? Js.add(x, (long) ParityJson.tagNumber(a.get(1)))
            : "jsSubtract".equals(fn) ? Js.subtract(x, (long) ParityJson.tagNumber(a.get(1)))
            : Js.multiply(x, (long) ParityJson.tagNumber(a.get(1)));
        } catch (ArithmeticException e) {
          got = null;
        }
        if (!longCanHold && got != null) diffs.add(where + ": JS gives " + Js.numberToString(js) + ", past a long, but Java returned " + got + " instead of refusing");
        if (longCanHold && (got == null || new BigDecimal(js).compareTo(BigDecimal.valueOf(got)) != 0))
          diffs.add(where + ": JS gives " + new BigDecimal(js).toPlainString() + " but Java " + (got == null ? "threw" : "gave " + got));
        if (!longCanHold) knownBeyondLong++;
        break;
      }
      case "exemptIds": {
        JsonArray ids = new JsonArray();
        for (int id = a.get(0).getAsInt(); id < a.get(1).getAsInt(); id++) if (Tax.estimateUnitTax(id, 1000000) == 0) ids.add(id);
        same(where, f.get("result"), ids);
        break;
      }
      default:
        diffs.add("unknown vector function " + fn);
    }
  }

  private static JsonObject errorJson(String message) {
    JsonObject o = new JsonObject();
    o.addProperty("error", message);
    return o;
  }

  static JsonObject packetJson(Packet p) {
    JsonObject j = new JsonObject();
    j.addProperty("version", p.version);
    j.addProperty("session", p.session);
    j.addProperty("account", p.account);
    j.addProperty("seq", p.seq);
    j.addProperty("ts", p.ts);
    j.addProperty("loggedIn", p.loggedIn);
    JsonArray offers = new JsonArray();
    for (Packet.SlotOffer o : p.offers) {
      JsonObject x = new JsonObject();
      x.addProperty("slot", o.slot);
      x.addProperty("state", o.state);
      x.addProperty("offerId", o.offerId);
      x.addProperty("itemId", o.itemId);
      x.addProperty("name", o.name);
      x.addProperty("price", o.price);
      x.addProperty("total", o.total);
      x.addProperty("filled", o.filled);
      x.addProperty("spent", o.spent);
      x.addProperty("knownStart", o.knownStart);
      ParityJson.put(x, "ticksToFill", o.ticksToFill);
      offers.add(x);
    }
    j.add("offers", offers);
    return j;
  }

  private static JsonObject ok() {
    JsonObject o = new JsonObject();
    o.addProperty("ok", true);
    return o;
  }

  private static void scenario(JsonObject s) {
    String name = s.get("name").getAsString();
    List<String> lines = new ArrayList<>();
    Store.Journal sink = r -> lines.add(JournalCodec.encodeLine(r));
    Store store = new Store(sink);
    int n = 0;
    for (JsonElement oe : s.getAsJsonArray("ops")) {
      JsonObject op = oe.getAsJsonObject();
      String kind = op.get("op").getAsString();
      long at = op.get("at").getAsLong();
      String where = name + " op " + (n++) + " " + kind;
      if ("restart".equals(kind)) {
        List<JournalRecord> records = new ArrayList<>();
        for (String l : lines) records.add(JournalCodec.decode(ParityJson.parse(l)));
        store = Store.replay(records, sink);
        continue;
      }
      if ("appendJournal".equals(kind)) {
        // A raw line written behind the store's back (the recorder appends it to the JS file the same way):
        // nothing reads it until the next restart replays the journal, and replay never validates a line.
        lines.add(op.get("args").getAsString());
        continue;
      }
      JsonElement args = op.get("args");
      JsonElement got;
      try {
        got = run(store, kind, args, at, op);
      } catch (JournalException e) {
        got = errorJson(e.getMessage());
      }
      JsonElement exp = op.has("error") ? errorJson(op.get("error").getAsString()) : op.get("result");
      same(where, exp, got);
      if ("state".equals(kind) && exp.isJsonObject()) {
        compared++;
        if (!ParityJson.keyOrder(exp.getAsJsonObject().getAsJsonObject("personalUseKept"))
          .equals(ParityJson.keyOrder(got.getAsJsonObject().getAsJsonObject("personalUseKept"))))
          diffs.add(where + ": personalUseKept key order differs");
      }
    }
    JsonArray js = s.getAsJsonArray("journal");
    compared++;
    if (js.size() != lines.size()) diffs.add(name + ": JS journalled " + js.size() + " lines, Java " + lines.size());
    for (int k = 0; k < Math.min(js.size(), lines.size()); k++) {
      compared++;
      if (!js.get(k).getAsString().equals(lines.get(k)))
        diffs.add(name + " journal line " + k + ":\n    JS   " + js.get(k).getAsString() + "\n    Java " + lines.get(k));
    }
  }

  private static JsonElement run(Store store, String kind, JsonElement args, long at, JsonObject op) {
    switch (kind) {
      case "ingest": {
        JsonObject o = new JsonObject();
        o.addProperty("duplicate", store.ingest(args, at));
        return o;
      }
      case "state":
        return ParityJson.state(store.state(at));
      case "buyLimitUsage": {
        JsonArray a = args.getAsJsonArray();
        BuyLimitUsage u = store.buyLimitUsage(a.get(0).isJsonNull() ? null : a.get(0).getAsString(), a.get(1).getAsInt(), at);
        JsonObject o = new JsonObject();
        o.addProperty("used", u.used);
        ParityJson.put(o, "windowEndsAt", u.windowEndsAt);
        return o;
      }
      case "closePosition":
        store.closePosition(args.getAsJsonObject(), at);
        return ok();
      case "markPersonalUse":
        store.markPersonalUse(args.getAsJsonObject());
        return ok();
      case "markPersonalUseItem": {
        Store.PersonalUseItemReply r = store.markPersonalUseItem(args.getAsJsonObject());
        JsonObject o = ok();
        o.addProperty("itemId", r.itemId);
        o.addProperty("personal", r.personal);
        ParityJson.put(o, "kept", r.kept);
        return o;
      }
      case "recordPurchase": {
        Store.RecordResult r = store.recordPurchase(args.getAsJsonObject(), at);
        JsonObject o = new JsonObject();
        ParityJson.put(o, "removed", r.removed);
        ParityJson.put(o, "recorded", r.recorded);
        ParityJson.put(o, "offerId", r.offerId);
        ParityJson.put(o, "duplicate", r.duplicate);
        ParityJson.put(o, "cost", r.cost);
        return o;
      }
      case "confirm": {
        String id = op.has("result") && op.get("result").isJsonObject() ? op.getAsJsonObject("result").get("id").getAsString() : "never-used";
        return ParityJson.manualFlip(store.confirm(args.getAsJsonObject(), at, () -> id));
      }
      case "setRemoved":
        store.setRemoved(args.getAsJsonObject());
        return ok();
      case "reopen":
        store.reopen(args.getAsJsonObject());
        return ok();
      case "importFlips": {
        Store.ImportResult r = store.importFlips(args.getAsJsonObject());
        JsonObject o = new JsonObject();
        ParityJson.put(o, "removed", r.removed);
        ParityJson.put(o, "accepted", r.accepted);
        ParityJson.put(o, "duplicates", r.duplicates);
        o.addProperty("total", r.total);
        return o;
      }
      default:
        throw new IllegalArgumentException("unknown op " + kind);
    }
  }
}
