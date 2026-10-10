package com.evi.live.journal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The journal line format, both ways.
 *
 * <p>{@link #encodeLine} writes exactly what {@code JSON.stringify(record)} writes in store.mjs: the same
 * keys in the same order, the same string escapes, the same number text. That is what keeps an
 * events.jsonl the plugin writes readable by every private tool, and it is checked byte for byte: against
 * the JS engine's own lines in JournalVectorsTest, and against every line of the real journal in the
 * private parity test. No Gson instance is involved (Gson HTML-escapes {@code '} and {@code =} by
 * default, and prints 3.0 for 3).
 *
 * <p>{@link #decode} reads a parsed line (any parser's tree) leniently, as {@code apply} does: a record
 * is trusted because it was validated when it was written.
 */
public final class JournalCodec {
  private JournalCodec() {}

  // ---------------------------------------------------------------------------------------------- decode

  public static JournalRecord decode(JsonElement line) {
    if (line == null || !line.isJsonObject()) throw new JournalException("Journal line is not an object");
    JsonObject r = line.getAsJsonObject();
    String type = Js.string(r.get("type"));
    if (type == null) type = "";
    switch (type) {
      case "flip-reopened": case "flip-removed": case "flip-restored":
        return new JournalRecord.FlipMark(type, Js.string(r.get("id")));
      case "flip":
        return new JournalRecord.FlipRecord(flip(r.getAsJsonObject("flip")));
      case "personal-use":
        return new JournalRecord.PersonalUse(false, Js.string(r.get("buyId")));
      case "personal-use-undo":
        return new JournalRecord.PersonalUse(true, Js.string(r.get("buyId")));
      case "personal-use-item":
        return new JournalRecord.PersonalUseItem(i(r, "itemId"), Js.number(r.get("kept")));
      case "personal-use-item-undo":
        return new JournalRecord.PersonalUseItemUndo(i(r, "itemId"));
      case "flips-imported": {
        List<ImportedFlip> flips = new ArrayList<>();
        for (JsonElement e : r.getAsJsonArray("flips")) flips.add(imported(e.getAsJsonObject()));
        return new JournalRecord.FlipsImported(flips);
      }
      case "flips-import-removed":
        return new JournalRecord.FlipsImportRemoved(Js.string(r.get("source")));
      case "purchase-recorded": {
        JsonObject o = r.getAsJsonObject("purchase");
        return new JournalRecord.PurchaseRecorded(Js.string(o.get("offerId")), i(o, "itemId"), Js.string(o.get("name")),
          l(o, "quantity"), l(o, "unitPrice"), l(o, "at"), Js.string(o.get("account")));
      }
      case "purchase-record-removed":
        return new JournalRecord.PurchaseRecordRemoved(Js.string(r.get("offerId")));
      case "position-closed":
        return new JournalRecord.PositionClosed(Js.string(r.get("buyId")), Js.string(r.get("reason")), l(r, "at"));
      case "position-reopened":
        return new JournalRecord.PositionReopened(Js.string(r.get("buyId")));
      default:
        // store.mjs treats every other record as a packet record (its apply() falls through to r.packet).
        if (!r.has("packet") || !r.get("packet").isJsonObject()) throw new JournalException("Journal line has no packet: type " + type);
        return new JournalRecord.PacketRecord(l(r, "received"), packet(r.getAsJsonObject("packet")));
    }
  }

  private static Packet packet(JsonObject p) {
    List<Packet.SlotOffer> offers = new ArrayList<>();
    for (JsonElement e : p.getAsJsonArray("offers")) {
      JsonObject o = e.getAsJsonObject();
      Double ticks = Js.number(o.get("ticksToFill"));
      offers.add(new Packet.SlotOffer(i(o, "slot"), Js.string(o.get("state")), Js.string(o.get("offerId")), i(o, "itemId"),
        Js.string(o.get("name")), l(o, "price"), l(o, "total"), l(o, "filled"), l(o, "spent"),
        o.has("knownStart") && o.get("knownStart").getAsBoolean(), ticks == null ? null : (int) (double) ticks));
    }
    return new Packet(i(p, "version"), Js.string(p.get("session")), Js.string(p.get("account")), l(p, "seq"), l(p, "ts"),
      p.get("loggedIn").getAsBoolean(), offers);
  }

  static ManualFlip flip(JsonObject f) {
    List<String> sellIds = null;
    if (f.has("sellIds") && f.get("sellIds").isJsonArray()) {
      sellIds = new ArrayList<>();
      for (JsonElement e : f.getAsJsonArray("sellIds")) sellIds.add(Js.string(e));
    }
    Double hold = Js.number(f.get("hold"));
    return new ManualFlip(Js.string(f.get("id")), Js.string(f.get("buyId")), Js.string(f.get("sellId")), sellIds,
      Js.string(f.get("account")), i(f, "itemId"), Js.string(f.get("item")), l(f, "quantity"), l(f, "capital"),
      l(f, "netProceeds"), l(f, "profit"), lOrNull(f, "firstBuy"), lOrNull(f, "lastSell"), hold == null ? Double.NaN : hold,
      lOrNull(f, "confirmedAt"), Js.string(f.get("source")), Js.string(f.get("proceedsMethod")));
  }

  static ImportedFlip imported(JsonObject f) {
    Double hold = Js.number(f.get("hold"));
    return new ImportedFlip(Js.string(f.get("fp")), Js.string(f.get("source")), i(f, "itemId"), Js.string(f.get("item")),
      l(f, "quantity"), l(f, "capital"), l(f, "netProceeds"), l(f, "profit"), l(f, "firstBuy"), l(f, "lastSell"),
      hold == null ? Double.NaN : hold, Js.string(f.get("account")));
  }

  private static int i(JsonObject o, String k) {
    Double d = Js.number(o.get(k));
    return d == null ? 0 : (int) (double) d;
  }

  private static long l(JsonObject o, String k) {
    Double d = Js.number(o.get(k));
    return d == null ? 0 : (long) (double) d;
  }

  private static Long lOrNull(JsonObject o, String k) {
    Double d = Js.number(o.get(k));
    return d == null ? null : (long) (double) d;
  }

  // ---------------------------------------------------------------------------------------------- encode

  /** The line store.mjs would write for this record, without the trailing newline. */
  public static String encodeLine(JournalRecord rec) {
    Obj r = new Obj().put("type", str(rec.type()));
    if (rec instanceof JournalRecord.PacketRecord) {
      JournalRecord.PacketRecord x = (JournalRecord.PacketRecord) rec;
      Packet p = x.packet;
      List<String> offers = new ArrayList<>();
      for (Packet.SlotOffer o : p.offers) {
        Obj w = new Obj().put("slot", num(o.slot)).put("state", str(o.state)).put("offerId", str(o.offerId)).put("itemId", num(o.itemId))
          .put("name", str(o.name)).put("price", num(o.price)).put("total", num(o.total)).put("filled", num(o.filled))
          .put("spent", num(o.spent)).put("knownStart", String.valueOf(o.knownStart));
        if (o.ticksToFill != null) w.put("ticksToFill", num(o.ticksToFill));
        offers.add(w.toString());
      }
      Obj pk = new Obj().put("version", num(p.version)).put("session", str(p.session)).put("account", str(p.account))
        .put("seq", num(p.seq)).put("ts", num(p.ts)).put("loggedIn", String.valueOf(p.loggedIn)).put("offers", arr(offers));
      r.put("received", num(x.received)).put("packet", pk.toString());
    } else if (rec instanceof JournalRecord.FlipRecord) {
      r.put("flip", flipJson(((JournalRecord.FlipRecord) rec).flip));
    } else if (rec instanceof JournalRecord.FlipMark) {
      r.put("id", str(((JournalRecord.FlipMark) rec).id));
    } else if (rec instanceof JournalRecord.PersonalUse) {
      r.put("buyId", str(((JournalRecord.PersonalUse) rec).buyId));
    } else if (rec instanceof JournalRecord.PersonalUseItem) {
      JournalRecord.PersonalUseItem x = (JournalRecord.PersonalUseItem) rec;
      r.put("itemId", num(x.itemId));
      if (x.kept != null) r.put("kept", dbl(x.kept));
    } else if (rec instanceof JournalRecord.PersonalUseItemUndo) {
      r.put("itemId", num(((JournalRecord.PersonalUseItemUndo) rec).itemId));
    } else if (rec instanceof JournalRecord.FlipsImported) {
      List<String> flips = new ArrayList<>();
      for (ImportedFlip f : ((JournalRecord.FlipsImported) rec).flips) flips.add(importedJson(f));
      r.put("flips", arr(flips));
    } else if (rec instanceof JournalRecord.FlipsImportRemoved) {
      r.put("source", str(((JournalRecord.FlipsImportRemoved) rec).source));
    } else if (rec instanceof JournalRecord.PurchaseRecorded) {
      JournalRecord.PurchaseRecorded x = (JournalRecord.PurchaseRecorded) rec;
      r.put("purchase", new Obj().put("offerId", str(x.offerId)).put("itemId", num(x.itemId)).put("name", str(x.name))
        .put("quantity", num(x.quantity)).put("unitPrice", num(x.unitPrice)).put("at", num(x.at)).put("account", str(x.account)).toString());
    } else if (rec instanceof JournalRecord.PurchaseRecordRemoved) {
      r.put("offerId", str(((JournalRecord.PurchaseRecordRemoved) rec).offerId));
    } else if (rec instanceof JournalRecord.PositionClosed) {
      JournalRecord.PositionClosed x = (JournalRecord.PositionClosed) rec;
      r.put("buyId", str(x.buyId)).put("reason", str(x.reason)).put("at", num(x.at));
    } else if (rec instanceof JournalRecord.PositionReopened) {
      r.put("buyId", str(((JournalRecord.PositionReopened) rec).buyId));
    } else {
      throw new IllegalArgumentException("unknown record " + rec.getClass());
    }
    return r.toString();
  }

  // confirm()'s key order. sellId is always written (null when several sales); sellIds and the optional
  // numbers only when present, so a line journalled before a field existed re-encodes as it was.
  private static String flipJson(ManualFlip f) {
    Obj w = new Obj().put("id", str(f.id)).put("buyId", str(f.buyId)).put("sellId", str(f.sellId));
    if (f.sellIds != null) {
      List<String> ids = new ArrayList<>();
      for (String s : f.sellIds) ids.add(str(s));
      w.put("sellIds", arr(ids));
    }
    w.put("account", str(f.account)).put("itemId", num(f.itemId)).put("item", str(f.item)).put("quantity", num(f.quantity))
      .put("capital", num(f.capital)).put("netProceeds", num(f.netProceeds)).put("profit", num(f.profit));
    if (f.firstBuy != null) w.put("firstBuy", num(f.firstBuy));
    if (f.lastSell != null) w.put("lastSell", num(f.lastSell));
    if (!Double.isNaN(f.hold)) w.put("hold", dbl(f.hold));
    if (f.confirmedAt != null) w.put("confirmedAt", num(f.confirmedAt));
    if (f.source != null) w.put("source", str(f.source));
    if (f.proceedsMethod != null) w.put("proceedsMethod", str(f.proceedsMethod));
    return w.toString();
  }

  private static String importedJson(ImportedFlip f) {
    return new Obj().put("fp", str(f.fp)).put("source", str(f.source)).put("itemId", num(f.itemId)).put("item", str(f.item))
      .put("quantity", num(f.quantity)).put("capital", num(f.capital)).put("netProceeds", num(f.netProceeds)).put("profit", num(f.profit))
      .put("firstBuy", num(f.firstBuy)).put("lastSell", num(f.lastSell)).put("hold", dbl(f.hold)).put("account", str(f.account))
      .put("imported", "true").toString();
  }

  private static String str(String s) {
    return s == null ? "null" : Js.quote(s);
  }

  // String(n) for an integer. Up to 2^53 that is every digit; past it JS prints the SHORTEST digits that
  // read back to the same double (4611686014132420608 is written 4611686014132420600), and a long that
  // came out of the JS-equal arithmetic in Js is always such a double, so the conversion loses nothing.
  private static String num(long n) {
    return Js.isSafe(n) ? Long.toString(n) : Js.numberToString((double) n);
  }

  private static String dbl(double d) {
    // JSON.stringify writes NaN and the infinities as null.
    return Double.isNaN(d) || Double.isInfinite(d) ? "null" : Js.numberToString(d);
  }

  private static String arr(List<String> items) {
    return "[" + String.join(",", items) + "]";
  }

  /** Keys in insertion order, values already JSON text. */
  private static final class Obj {
    private final StringBuilder sb = new StringBuilder("{");
    private boolean first = true;

    Obj put(String k, String rawJson) {
      if (!first) sb.append(',');
      first = false;
      Js.quote(sb, k);
      sb.append(':').append(rawJson);
      return this;
    }

    @Override public String toString() {
      return sb.toString() + "}";
    }
  }
}
