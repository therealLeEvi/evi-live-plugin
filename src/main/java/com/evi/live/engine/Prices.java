package com.evi.live.engine;

import com.evi.live.journal.CostBasis;
import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Offer;
import com.evi.live.journal.Tax;
import com.evi.live.market.HourBucket;
import com.evi.live.market.LatestPrices;
import java.util.List;

/**
 * Prices for one item, and what the player paid for it (bridge/suggestions.mjs lookupItemPrice, priceAgeMinutes,
 * withCostBasis, heldCostBasis). Pure; time is passed in.
 *
 * <p>The prompt and the sidebar must never disagree about whether a sale loses GP, so {@link #withCostBasis} uses the
 * same tax ({@link Tax#estimateUnitTax}) and break-even search ({@link Tax#breakEvenSellPrice}) as everything else. A
 * warning, never a block: the price is still offered, with the break-even beside it.
 */
public final class Prices {
  private Prices() {}

  /** MAX_PRICE_AGE_MINUTES: a market-wide pick whose staler side is older than this has no trustworthy spread. */
  public static final double MAX_PRICE_AGE_MINUTES = 60;

  /**
   * lookupItemPrice: the item's live low/high, with no ranking and no filter, or null for an invalid id or an item
   * without both sides priced. Never a fabricated number.
   */
  public static ItemPrice lookupItemPrice(LatestPrices latest, double itemId) {
    if (!JsValues.isFinite(itemId) || itemId <= 0) return null;
    // latestPrices[String(itemId)]: only a whole id in range names an item; 1.5 or 1e21 find nothing.
    if (latest == null || !JsValues.isInteger(itemId) || itemId > Integer.MAX_VALUE) return null;
    LatestPrices.Quote q = latest.get((int) itemId);
    if (q == null || !(priced(q.low) > 0) || !(priced(q.high) > 0)) return null;
    return new ItemPrice((int) itemId, q.low, q.high, null, null, null);
  }

  // A /latest side as the JS sees it: NaN (not > 0) when the Wiki sent none.
  private static double priced(long v) {
    return v == HourBucket.NONE ? Double.NaN : v;
  }

  /**
   * priceAgeMinutes: the age of the STALER side in minutes, or null unless both sides carry a usable timestamp
   * (unix seconds; NaN for none). Fails open.
   */
  public static Double priceAgeMinutes(double highTime, double lowTime, double nowMs) {
    boolean h = JsValues.isFinite(highTime) && highTime > 0, l = JsValues.isFinite(lowTime) && lowTime > 0;
    if (!h || !l) return null;
    return (nowMs / 1000 - Math.min(highTime, lowTime)) / 60;
  }

  /** The same for a {@code /latest} quote (null quote: no answer). */
  public static Double priceAgeMinutes(LatestPrices.Quote q, long nowMs) {
    if (q == null) return null;
    return priceAgeMinutes(priced(q.highTime), priced(q.lowTime), nowMs);
  }

  /**
   * withCostBasis: the open-item price, made aware of what the player paid. Unchanged (the same object) when there is
   * no price or no usable cost. {@code quantity} below 1 or not finite counts as one unit.
   */
  public static ItemPrice withCostBasis(ItemPrice price, double unitCost, double quantity) {
    if (price == null || !JsValues.isFinite(unitCost) || unitCost <= 0) return price;
    Long breakEven = Tax.breakEvenSellPrice(price.itemId, unitCost);
    long tax = Tax.estimateUnitTax(price.itemId, price.sellPrice);
    double netPerUnit = price.sellPrice - unitCost - tax;
    double held = JsValues.isFinite(quantity) && quantity > 0 ? quantity : 1;
    // ROUNDED FIRST, then judged, exactly as the holding tier does it (the maintainer, 7 Oct 2026, fixed in JS first): the
    // SIGNED total is rounded half-up once and only a total still below zero is a loss. A loss under half a gp in total is
    // no loss (never "0 gp"), and an exact .5 tie rounds toward +infinity (-2.5 -> a loss of 2), as the sidebar does.
    double totalNet = JsValues.jsRound(netPerUnit * held);
    return new ItemPrice(price.itemId, price.buyPrice, price.sellPrice, "sell", breakEven,
      totalNet < 0 ? JsValues.exactLong(-totalNet) : null);
  }

  /**
   * heldCostBasis for ONE account ({@link CostBasis#held}, which scopes itself: no account, no answer -- the PORT
   * WARNING). Null when nothing held has a known price. Package-private:
   * production asks {@link AccountView#heldCostBasis}.
   */
  static CostBasis heldCostBasis(List<FifoMatcher.OpenPosition> openPositions, List<Offer> activeOffers, int itemId, String account) {
    return CostBasis.held(openPositions, activeOffers, itemId, account);
  }
}
