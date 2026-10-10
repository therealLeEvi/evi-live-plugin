package com.evi.live.engine;

import com.evi.live.journal.Offer;
import com.evi.live.journal.Tax;
import com.evi.live.market.LatestPrices;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "The trade you are still buying into no longer works." -- for a BUY offer still filling while the reason for it has gone
 * (bridge/buyAdvice.mjs {@code buyMarginAdvice}). Pure.
 *
 * <p>A running buy is the one place cancelling is free on every unfilled unit, so this is the cheapest warning EVI can
 * give. ARITHMETIC ON CURRENT PRICES AND THE TAX, NEVER A FORECAST: "margin gone" -- selling at what buyers pay now, after
 * tax, no longer clears the price being paid; "nobody selling" -- no sell-side price at all, said plainly rather than
 * treated as a margin of zero. Anything unreadable produces nothing (fail open). It never cancels or edits an offer.
 *
 * <p>Deliberately NOT included: "the market moved above your buy price". The plugin's offerDriftHint already says that, and
 * repeating it would put two sentences about one offer in the sidebar.
 */
public final class BuyAdvice {
  private BuyAdvice() {}

  /** MIN_MARGIN_SHARE: relative to the price being paid; prices wobble by less than this between polls. */
  public static final double MIN_MARGIN_SHARE = 0.005;

  /** An in-progress BUY offer: {itemId, name, price, total, filled, spent}; NaN for what the caller did not have. */
  public static final class BuyOffer {
    public final int itemId;
    public final String name;
    public final double price;
    public final double total;
    public final double filled;
    public final double spent;

    public BuyOffer(int itemId, String name, double price, double total, double filled, double spent) {
      this.itemId = itemId;
      this.name = name;
      this.price = price;
      this.total = total;
      this.filled = filled;
      this.spent = spent;
    }
  }

  /**
   * buyMarginAdvice for ONE account (server.mjs): its live buys, priced from /latest (market-wide, named as such) for
   * exactly those items. Another account's buy of the same item is not in the view, so it never draws a note here (the
   * 7 Oct M06 widening).
   */
  public static List<AdviceNote> forAccount(AccountView view, LatestPrices marketLatest) {
    List<BuyOffer> offers = new ArrayList<>();
    for (Offer o : view.liveBuys) offers.add(new BuyOffer(o.itemId, o.name, o.price, o.total, o.filled, o.spent));
    return buyMarginAdvice(offers, AdviceNotes.pricesFor(marketLatest, view.liveBuys));
  }

  /**
   * buyMarginAdvice: one note per offer worth mentioning, in offer order. {@code prices}: item id to its live price.
   * <p>Package-private on purpose: outside the engine the only way in is {@link #forAccount}, which reads ONE account's
   * records from its {@link AccountView}. The parity harness (same package) drives this record-level core directly.
   */
  static List<AdviceNote> buyMarginAdvice(List<BuyOffer> offers, Map<Integer, AdviceNote.Price> prices) {
    List<AdviceNote> out = new ArrayList<>();
    if (offers == null) return out;
    for (BuyOffer offer : offers) {
      if (offer == null || !(offer.price > 0) || !(offer.total > offer.filled)) continue;
      AdviceNote.Price p = prices == null ? null : prices.get(offer.itemId);
      String name = offer.name != null && !offer.name.isEmpty() ? offer.name : "item " + offer.itemId;
      double remaining = offer.total - offer.filled;
      String bought = offer.filled > 0
        ? " You have " + JsValues.gp(offer.filled) + " already" + (JsValues.isFinite(offer.spent) && offer.spent > 0 ? " at " + JsValues.gp(offer.spent / offer.filled) + " gp each" : "")
          + "; cancelling keeps those and only drops the " + JsValues.gp(remaining) + " still to buy."
        : " Nothing has filled yet, so cancelling costs nothing.";
      if (p == null || p.sellPrice == null || !(p.sellPrice > 0)) {
        if (p != null && p.buyPrice != null && p.buyPrice > 0) {
          AdviceNote n = new AdviceNote(offer.itemId);
          n.name = name;
          n.level = "caution";
          n.label = "Nobody selling";
          n.figures = JsValues.gp(remaining) + " still buying at " + JsValues.gp(offer.price);
          n.message = name + ": nobody is selling to buyers at the moment, so EVI cannot price an exit for the " + JsValues.gp(remaining)
            + " you are still buying at " + JsValues.gp(offer.price) + " gp." + bought + " EVI has no view on whether that changes.";
          out.add(n);
        }
        continue;
      }
      double netAtMarket = p.sellPrice - Tax.estimateUnitTax(offer.itemId, p.sellPrice);
      double marginPerUnit = netAtMarket - offer.price;
      if (marginPerUnit > offer.price * MIN_MARGIN_SHARE) continue;
      AdviceNote n = new AdviceNote(offer.itemId);
      n.name = name;
      n.marginPerUnit = marginPerUnit;
      n.netAtMarket = netAtMarket;
      n.level = marginPerUnit < 0 ? "warn" : "caution";
      n.label = "Margin gone";
      n.figures = JsValues.gp(offer.price) + " paid · " + JsValues.gp(netAtMarket) + " after tax";
      n.message = name + ": your buy at " + JsValues.gp(offer.price) + " gp no longer has a margin -- buyers are paying " + JsValues.gp(p.sellPrice)
        + " gp, which is " + JsValues.gp(netAtMarket) + " gp after tax, "
        + (marginPerUnit < 0 ? JsValues.gp(-marginPerUnit) + " gp BELOW" : "only " + JsValues.gp(marginPerUnit) + " gp above")
        + " what you are paying." + bought + " Whether to keep it is your call -- EVI never cancels anything, and it has no view on where the price goes next.";
      out.add(n);
    }
    return out;
  }
}
