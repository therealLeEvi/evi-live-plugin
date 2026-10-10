package com.evi.live.engine;

import com.evi.live.journal.Offer;
import com.evi.live.journal.Tax;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "The price you are asking does not cover what you paid." -- for a SELL offer already standing (bridge/sellAdvice.mjs
 * {@code sellAdvice}). Pure.
 *
 * <p>WHY: in a week of one player's Copilot log, five of six losing flips lost to the Grand Exchange TAX rather than to the
 * market -- a Light ballista sold 0.1% above cost and still lost 100,160 gp. Deliberately narrow: {@link Relist} speaks
 * about an ask ABOVE the market; this about an ask below the seller's own break-even, which can be true of an offer filling
 * perfectly well. It is about the player's own cost, never a market view; with no cost basis it says nothing; it warns
 * and never cancels, edits or re-prices.
 */
public final class SellAdvice {
  private SellAdvice() {}

  /** MIN_LOSS_SHARE: below this, relative to what the stock cost, the "loss" is tax rounding, not a decision. */
  public static final double MIN_LOSS_SHARE = 0.001;

  /** A live SELL offer: {itemId, name, price, remaining}; NaN for what the caller did not have. */
  public static final class SellOffer {
    public final int itemId;
    public final String name;
    public final double price;
    public final double remaining;

    public SellOffer(int itemId, String name, double price, double remaining) {
      this.itemId = itemId;
      this.name = name;
      this.price = price;
      this.remaining = remaining;
    }
  }

  /**
   * sellAdvice for ONE account (server.mjs): its live sells, each against ITS OWN cost basis -- both read from the view, so
   * another account's lot can never become this account's break-even (the 7 Oct M12 widening: one account selling stock
   * EVI never saw it buy, priced against another account's lot of the same item).
   */
  public static List<AdviceNote> forAccount(AccountView view) {
    List<SellOffer> offers = new ArrayList<>();
    for (Offer o : view.liveSells) offers.add(new SellOffer(o.itemId, o.name, o.price, Math.max(0, o.total - o.filled)));
    return sellAdvice(offers, view.costBasis);
  }

  /**
   * sellAdvice: one 'Below break-even' note per offer worth mentioning, in offer order.
   * <p>Package-private on purpose: outside the engine the only way in is {@link #forAccount}, which reads ONE account's
   * records from its {@link AccountView}. The parity harness (same package) drives this record-level core directly.
   */
  static List<AdviceNote> sellAdvice(List<SellOffer> offers, Map<Integer, Double> costBasis) {
    List<AdviceNote> out = new ArrayList<>();
    if (offers == null) return out;
    for (SellOffer offer : offers) {
      if (offer == null || !(offer.price > 0)) continue;
      Double remaining = JsValues.isFinite(offer.remaining) && offer.remaining > 0 ? offer.remaining : null;
      Double paid = costBasis == null ? null : costBasis.get(offer.itemId);
      if (paid == null || !(paid > 0)) continue; // no cost basis: nothing honest to say
      Long breakEven = Tax.breakEvenSellPrice(offer.itemId, paid);
      if (breakEven == null || !(breakEven > 0) || offer.price >= breakEven) continue;
      // What the ask actually returns per unit after tax, against what the unit cost.
      double netEach = offer.price - Tax.estimateUnitTax(offer.itemId, offer.price);
      double lossEach = paid - netEach;
      if (!(lossEach > 0) || lossEach / paid < MIN_LOSS_SHARE) continue;
      double units = remaining != null ? remaining : 1;
      String name = offer.name != null && !offer.name.isEmpty() ? offer.name : "item " + offer.itemId;
      String scope = remaining == null ? "" : " on the " + JsValues.gp(remaining) + " still unsold";
      AdviceNote n = new AdviceNote(offer.itemId);
      n.level = "warn";
      n.label = "Below break-even";
      n.figures = JsValues.gp(offer.price) + " asked · break-even " + JsValues.gp(breakEven);
      n.name = name;
      n.breakEven = (double) breakEven;
      n.lossEach = JsValues.jsRound(lossEach);
      n.lossTotal = JsValues.jsRound(lossEach * units);
      n.message = name + ": your sell at " + JsValues.gp(offer.price) + " gp is BELOW your break-even of " + JsValues.gp(breakEven) + " gp. "
        + "After tax it returns " + JsValues.gp(netEach) + " gp against the " + JsValues.gp(paid) + " gp it cost, so it loses about "
        + JsValues.gp(lossEach) + " gp each" + scope + " (" + JsValues.gp(lossEach * units) + " gp in total). "
        + "Selling at a loss is sometimes right -- EVI just won't let it happen quietly.";
      out.add(n);
    }
    return out;
  }
}
