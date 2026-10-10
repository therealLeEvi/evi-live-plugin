package com.evi.live.inprocess;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.ManualFlip;
import com.evi.live.journal.Offer;
import com.evi.live.journal.StoreState;
import com.evi.live.journal.TradeLogExport;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * bridge/suggestionOutcomes.mjs {@code joinSuggestionOutcomes}, ported: what EVI suggested (the plugin's own suggestion log,
 * {@link SuggestionRecords}) joined to what actually happened (the journal's offers and flips). NOTHING IN THE PLUGIN SHOWS IT
 * YET -- the bridge's consumer is the scanner's track-record page -- so this exists to keep "I took this one" meaning what it means
 * in the bridge: a record that can be joined to the offer that followed it.
 *
 * <p>Three attributions, never pooled (read the JS header before changing anything):
 * <ul>
 *   <li>OBSERVED: the player pressed "I took this one" for the id. The earliest unclaimed offer for the item, on the suggested
 *       side, placed within {@link #ACCEPTED_WINDOW_MS} after the suggestion was shown, is "the" offer, at WHATEVER price -- the
 *       player said they took it. With no such offer the row is still taken ({@code noOfferFound}).</li>
 *   <li>MATCHED: not pressed, but the offer carries EVI's exact fingerprint -- the suggested price TO THE GP and the suggested
 *       quantity to the unit, the quantity at least {@link #MATCH_MIN_QUANTITY}. One gp off is not a match.</li>
 *   <li>INFERRED: any other offer for the item on that side within {@link #TAKEN_WINDOW_MS}.</li>
 * </ul>
 * A margin check never counts; each offer is claimed once, oldest suggestion first. Pure; JS semantics kept (a missing number
 * fails every comparison; the sort is stable).
 */
public final class SuggestionOutcomes {
  private SuggestionOutcomes() {}

  public static final long TAKEN_WINDOW_MS = 2L * 3600 * 1000;
  public static final long ACCEPTED_WINDOW_MS = 12L * 3600 * 1000;
  public static final int MATCH_MIN_QUANTITY = 11;

  /** One joined row: the log entry, and what happened. */
  public static final class Row {
    public final JsonObject suggestion;
    public final boolean taken;
    /** "observed", "matched", "inferred", or null when not taken. */
    public final String attribution;
    /** Only on a row with no offer: true when it was observed (taken, never placed). */
    public final Boolean noOfferFound;
    public final String offerId;
    public final Long actualPrice;
    public final Long actualQuantity;
    public final Long filled;
    public final Boolean filledFully;
    public final Double minutesToComplete;
    public final Boolean stillOpen;
    public final Long profit;
    public final Double soldWithinHours;

    Row(JsonObject suggestion, boolean taken, String attribution, Boolean noOfferFound, Offer match, Long profit, Double soldWithinHours) {
      this.suggestion = suggestion;
      this.taken = taken;
      this.attribution = attribution;
      this.noOfferFound = noOfferFound;
      this.offerId = match == null ? null : match.offerId;
      this.actualPrice = match == null ? null : match.price;
      this.actualQuantity = match == null ? null : match.total;
      this.filled = match == null ? null : match.filled;
      this.filledFully = match == null ? null : match.total > 0 && match.filled >= match.total;
      boolean finished = match != null && match.completedAt != null;
      this.minutesToComplete = match == null || !finished ? null : (match.completedAt - (double) match.firstSeen) / 60000;
      this.stillOpen = match == null ? null : !finished;
      this.profit = profit;
      this.soldWithinHours = soldWithinHours;
    }
  }

  /** A flip as the join reads it: {@code [...state.flips, ...state.autoFlips]}. */
  public static final class Flip {
    final String buyId;
    final long profit;
    final Long firstBuy;
    final Long lastSell;

    public Flip(String buyId, long profit, Long firstBuy, Long lastSell) {
      this.buyId = buyId;
      this.profit = profit;
      this.firstBuy = firstBuy;
      this.lastSell = lastSell;
    }

    public static List<Flip> of(Collection<ManualFlip> flips, Collection<FifoMatcher.AutoFlip> autoFlips) {
      List<Flip> out = new ArrayList<>();
      if (flips != null) for (ManualFlip f : flips) out.add(new Flip(f.buyId, f.profit, f.firstBuy, f.lastSell));
      if (autoFlips != null) for (FifoMatcher.AutoFlip f : autoFlips) out.add(new Flip(f.buyId, f.profit, f.firstBuy, f.lastSell));
      return out;
    }
  }

  private static boolean isBuy(Offer o) {
    return "BUYING".equals(o.state) || "BOUGHT".equals(o.state) || "CANCELLED_BUY".equals(o.state);
  }

  /** A finite JSON number, else null (JS Number.isFinite on whatever the log line holds). */
  static Double finite(JsonElement e) {
    if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
    double d = e.getAsDouble();
    return Double.isFinite(d) ? d : null;
  }

  private static String string(JsonElement e) {
    return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
  }

  /** suggestionOutcomes.mjs fingerprinted: the suggested price to the gp AND the quantity to the unit, the quantity at least 11. */
  static boolean fingerprinted(JsonObject s, Offer offer) {
    Double wanted = finite("buy".equals(string(s.get("action"))) ? s.get("buyPrice") : s.get("sellPrice"));
    Double quantity = finite(s.get("quantity"));
    if (wanted == null || quantity == null) return false;
    if (quantity < MATCH_MIN_QUANTITY) return false;
    return offer.price == wanted && offer.total == quantity;
  }

  /**
   * @param suggestions the log's entries, oldest first
   * @param offers      every journal offer, in the store's order
   * @param wasAccepted the acceptance record's answer for an id (null: none)
   */
  public static List<Row> join(List<JsonObject> suggestions, Collection<Offer> offers, List<Flip> flips, long takenWindowMs,
                               Predicate<String> wasAccepted) {
    Map<String, Flip> flipByBuyId = new HashMap<>();
    if (flips != null) for (Flip f : flips) if (f != null && f.buyId != null && !f.buyId.isEmpty()) flipByBuyId.put(f.buyId, f);
    Set<String> used = new HashSet<>();
    List<Row> rows = new ArrayList<>();
    for (JsonObject s : suggestions) {
      if (s == null) continue;
      Double itemId = finite(s.get("itemId")), ts = finite(s.get("ts"));
      if (itemId == null || ts == null) continue;
      boolean wantBuy = "buy".equals(string(s.get("action")));
      String id = string(s.get("id"));
      boolean observed = wasAccepted != null && id != null && !id.isEmpty() && wasAccepted.test(id);
      long window = observed ? ACCEPTED_WINDOW_MS : takenWindowMs;
      Offer match = null;
      for (Offer o : offers) {
        if (o.itemId != itemId || isBuy(o) != wantBuy || used.contains(o.offerId) || FifoMatcher.isMarginCheck(o)) continue;
        if (o.firstSeen == null || !(o.firstSeen >= ts) || !(o.firstSeen <= ts + window)) continue;
        if (match == null || o.firstSeen < match.firstSeen) match = o; // the earliest; a tie keeps the first (a stable sort's [0])
      }
      if (match == null) {
        rows.add(new Row(s, observed, observed ? "observed" : null, observed, null, null, null));
        continue;
      }
      used.add(match.offerId);
      Flip flip = flipByBuyId.get(match.offerId);
      // (lastSell - firstBuy) / 3600000; a missing time is NaN, as an undefined one is in JS
      Double within = flip == null ? null
        : flip.lastSell == null || flip.firstBuy == null ? Double.NaN : (flip.lastSell - (double) flip.firstBuy) / 3600000;
      rows.add(new Row(s, true, observed ? "observed" : fingerprinted(s, match) ? "matched" : "inferred", null, match,
        flip == null ? null : flip.profit, within));
    }
    return rows;
  }

  /**
   * The trade-log export's links (voluntary sharing, 9 Oct 2026): every offer this join claimed, by offer id, with its attribution
   * and the suggested price for the suggestion's side -- the same price {@link #fingerprinted} compares (buyPrice for a buy,
   * sellPrice otherwise), empty when the record holds no whole number. A row with no offer links nothing. Only the price and the
   * attribution leave the record: never its id, account, quantity or anything else.
   */
  public static Map<String, TradeLogExport.Link> offerLinks(List<Row> rows) {
    Map<String, TradeLogExport.Link> out = new HashMap<>();
    for (Row r : rows) {
      if (r.offerId == null || r.attribution == null) continue;
      Double p = finite("buy".equals(string(r.suggestion.get("action"))) ? r.suggestion.get("buyPrice") : r.suggestion.get("sellPrice"));
      Long price = p == null || p != Math.floor(p) || Math.abs(p) > 9.007199254740991E15 ? null : (long) (double) p;
      out.put(r.offerId, new TradeLogExport.Link(r.attribution, price));
    }
    return out;
  }

  /**
   * {@link #offerLinks} over everything the plugin has logged: the suggestion log's current file, oldest first, joined with the
   * default windows (2 h, 12 h when observed) to every offer and flip the export is built from, as the bridge joins them. Reads
   * the plugin's suggestion files, so never on the client thread or the Swing EDT. An offer older than the log, or imported
   * from the old companion app's history (whose suggestions the plugin never logged), has no link.
   */
  public static Map<String, TradeLogExport.Link> offerLinks(SuggestionRecords records, StoreState state, Collection<Offer> offers) {
    if (records == null) return new HashMap<>();
    return offerLinks(join(records.logged(), offers, Flip.of(state.flips, state.autoFlips), TAKEN_WINDOW_MS, records::wasAccepted));
  }
}
