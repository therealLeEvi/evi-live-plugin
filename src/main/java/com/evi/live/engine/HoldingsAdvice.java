package com.evi.live.engine;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Tax;
import com.evi.live.market.HourBucket;
import com.evi.live.market.LatestPrices;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Here is what you are holding." -- every tracked position, always, WHATEVER THE PROFIT SETTING (bridge/holdingsAdvice.mjs
 * {@code holdingsAdvice}). Pure.
 *
 * <p>THE SEPARATION THIS CLASS IS (30 Sept 2026): "what should I do next" and "what am I holding" had shared the one
 * suggestion slot, so a holding had to beat a new trade to be mentioned at all, and {@code holdingPreempts} judges it
 * against the player's MINIMUM PROFIT -- a +254,063 gp position was invisible under a 1m minimum. Lowering that bar was
 * measured and COSTS GP (tools/holding-gate.mjs), so the bar stays and the QUESTION moves: these lines ride relistAdvice.
 * THIS METHOD CANNOT BE PASSED A MINIMUM PROFIT -- there is no parameter for one, which is the strongest guarantee the two
 * questions stay separate. A 152 gp pie is ALLOWED here: a line to scroll past, never a displaced suggestion.
 *
 * <p>Only positions with a REAL cost basis; nothing already standing in a sell slot (visible, and Relist/SellAdvice speak
 * about it); nothing about the item EVI is suggesting right now; a loss stated as plainly as a gain; and it states, never
 * advises.
 */
public final class HoldingsAdvice {
  private HoldingsAdvice() {}

  /** MAX_LINES: so thirty holdings are not a wall of text; the most valuable first. */
  public static final int MAX_LINES = 5;

  /**
   * One open position: {itemId, item, remaining, unitCost}; NaN for what the caller did not have. {@code endedUnseenPrice}:
   * the price of a sell listing of it that ENDED while EVI was not watching (the position's endedUnseen mark), null for none.
   */
  public static final class Position {
    public final Integer itemId;
    public final String item;
    public final double remaining;
    public final double unitCost;
    public final Double endedUnseenPrice;
    /** The lot's buy offer id (8 Oct 2026): carried onto its line for the sidebar's line menu; null or empty: no key. */
    public final String buyId;

    public Position(Integer itemId, String item, double remaining, double unitCost) {
      this(itemId, item, remaining, unitCost, null);
    }

    public Position(Integer itemId, String item, double remaining, double unitCost, Double endedUnseenPrice) {
      this(itemId, item, remaining, unitCost, endedUnseenPrice, null);
    }

    public Position(Integer itemId, String item, double remaining, double unitCost, Double endedUnseenPrice, String buyId) {
      this.itemId = itemId;
      this.item = item;
      this.remaining = remaining;
      this.unitCost = unitCost;
      this.endedUnseenPrice = endedUnseenPrice;
      this.buyId = buyId;
    }
  }

  /**
   * holdingsAdvice: at most {@code max} lines, most valuable first (ties keep position order). {@code highs}: item id to
   * what a seller gets now (/latest's {@code high}; absent or null: no current price -- the position is still LISTED, with
   * its cost and no worth). {@code listedItemIds}: items standing in a sell offer. {@code suggestedItemId}: this poll's
   * suggestion (null: none).
   * <p>Package-private on purpose: outside the engine the only way in is {@link #forAccount}, which reads ONE account's
   * records from its {@link AccountView}. The parity harness (same package) drives this record-level core directly.
   */
  static List<AdviceNote> holdingsAdvice(List<Position> positions, Map<Integer, Double> highs, Set<Integer> listedItemIds,
                                         Integer suggestedItemId, double max) {
    List<AdviceNote> rows = new ArrayList<>();
    List<Double> sortBy = new ArrayList<>();
    if (positions != null) for (Position p : positions) {
      if (p == null || p.itemId == null) continue;
      double qty = JsValues.isFinite(p.remaining) && p.remaining > 0 ? p.remaining : 0;
      if (qty == 0) continue;
      if (!(p.unitCost > 0)) continue;                                             // no cost basis: nothing honest to say
      if (listedItemIds != null && listedItemIds.contains(p.itemId)) continue;     // already on the market and visible
      if (p.itemId.equals(suggestedItemId)) continue;                              // already the suggestion
      String name = p.item != null && !p.item.isEmpty() ? p.item : "item " + p.itemId;
      double cost = p.unitCost * qty;
      // The lot's buyId, only when it is a non-empty string (the JS spreads {buyId} in then, nothing otherwise).
      String lot = p.buyId != null && !p.buyId.isEmpty() ? p.buyId : null;
      // A sell listing of it ENDED while EVI was not watching: it may have sold, so no worth and no net -- only the facts.
      // Still listed, never dropped: it may be in the bag.
      if (p.endedUnseenPrice != null && JsValues.isFinite(p.endedUnseenPrice) && p.endedUnseenPrice > 0) {
        AdviceNote gone = new AdviceNote(p.itemId);
        gone.buyId = lot;
        gone.name = name;
        gone.quantity = qty;
        gone.unitCost = JsValues.jsRound(p.unitCost);
        gone.cost = JsValues.jsRound(cost);
        gone.breakEven = null;
        gone.holding = true;
        sortBy.add(cost);
        gone.level = "caution";
        gone.label = "Holding, outcome unknown";
        gone.figures = JsValues.gp(qty) + " · last listed at " + JsValues.gp(p.endedUnseenPrice);
        gone.message = "Bought for " + JsValues.gp(cost) + ". EVI last saw it listed at " + JsValues.gp(p.endedUnseenPrice)
          + " gp and the listing was gone at your next login, so EVI can't tell whether it sold. No profit is claimed.";
        rows.add(gone);
        continue;
      }
      Double price = highs == null ? null : highs.get(p.itemId);
      Long breakEven = Tax.breakEvenSellPrice(p.itemId, p.unitCost);
      boolean hasBreakEven = breakEven != null && breakEven > 0;
      AdviceNote row = new AdviceNote(p.itemId);
      row.buyId = lot;
      row.name = name;
      row.quantity = qty;
      row.unitCost = JsValues.jsRound(p.unitCost);
      row.cost = JsValues.jsRound(cost);
      row.breakEven = hasBreakEven ? (double) breakEven : null;
      row.holding = true;
      if (price == null || !(price > 0)) {
        // No current price: say so rather than imply a worth; sorted by its cost, which is still real capital.
        sortBy.add(cost);
        row.level = "info";
        row.label = "Holding";
        row.figures = JsValues.gp(qty) + " · cost " + JsValues.gp(cost);
        row.message = "Cost " + JsValues.gp(cost) + (hasBreakEven ? ". Break-even " + JsValues.gp(breakEven) + " each" : "")
          + ". No current price, so EVI can't say what it's worth today.";
        rows.add(row);
        continue;
      }
      double net = (price - p.unitCost - Tax.estimateUnitTax(p.itemId, price)) * qty;
      row.worth = JsValues.jsRound(price * qty);
      row.net = JsValues.jsRound(net);
      sortBy.add(price * qty);
      row.level = net >= 0 ? "info" : "caution";
      row.label = net >= 0 ? "Holding" : "Holding, under water";
      row.figures = JsValues.gp(qty) + " · " + (net >= 0 ? "+" : "") + JsValues.gp(net) + " after tax";
      row.message = "Bought for " + JsValues.gp(cost) + (hasBreakEven ? ". Break-even " + JsValues.gp(breakEven) + " each" : "")
        + ". Listed because you own it, not as advice to sell.";
      rows.add(row);
    }
    List<Integer> idx = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) idx.add(i);
    idx.sort((a, b) -> {
      double d = sortBy.get(b) - sortBy.get(a);
      return d > 0 ? 1 : d < 0 ? -1 : 0; // NaN reads as equal, as in JS
    });
    List<AdviceNote> out = new ArrayList<>();
    double end = BuyProgress.sliceEnd(Math.max(0, max));
    for (int k = 0; k < idx.size() && k < end; k++) out.add(rows.get(idx.get(k)));
    return out;
  }

  /**
   * holdingsAdvice for ONE account (server.mjs): ITS open lots, skipping the items IT has listed, valued at /latest's buyers'
   * price (market-wide, named as such: every item's {@code high}), with the default cap. Another account's lot or listing is
   * not in the view, so it can neither add a line here nor hide one.
   */
  public static List<AdviceNote> forAccount(AccountView view, LatestPrices marketLatest, Integer suggestedItemId) {
    List<Position> held = new ArrayList<>();
    for (FifoMatcher.OpenPosition p : view.positions)
      held.add(new Position(p.itemId, p.item, p.remaining, p.unitCost, p.endedUnseen == null ? null : (double) p.endedUnseen.price, p.buyId));
    Map<Integer, Double> highs = new LinkedHashMap<>();
    if (marketLatest != null) for (Map.Entry<Integer, LatestPrices.Quote> e : marketLatest.all().entrySet())
      if (e.getValue().high != HourBucket.NONE) highs.put(e.getKey(), (double) e.getValue().high);
    return holdingsAdvice(held, highs, view.listedItemIds, suggestedItemId);
  }

  /** The default cap, {@link #MAX_LINES}. */
  static List<AdviceNote> holdingsAdvice(List<Position> positions, Map<Integer, Double> highs, Set<Integer> listedItemIds, Integer suggestedItemId) {
    return holdingsAdvice(positions, highs, listedItemIds, suggestedItemId, MAX_LINES);
  }
}
