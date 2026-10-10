package com.evi.live.engine;

import com.evi.live.journal.Offer;
import com.evi.live.journal.Tax;
import com.evi.live.market.LatestPrices;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "This hasn't sold. Here is what it would take." (bridge/relist.mjs {@code relistAdvice}). Pure; the clock is passed in.
 *
 * <p>EVI never relists anything itself -- it has never placed, edited or cancelled an offer. This produces a sentence for
 * the sidebar; the player decides and clicks. Three rules keep it honest:
 * <ul>
 *   <li>It speaks only when the offer has genuinely sat: a share ({@link #RELIST_AFTER_SHARE}) of the player's own target
 *       duration, never before {@link #MIN_WAIT_MINUTES} -- OR when the market has run away ({@link #DRIFT_SPEAKS_NOW}),
 *       after {@link #DRIFT_MIN_WAIT_MINUTES} so a fresh offer is not second-guessed on the spot.</li>
 *   <li>It never suggests a price below break-even. Below it, it says the loss plainly and stops.</li>
 *   <li>With no cost basis it stays quiet about break-even rather than guessing what was paid.</li>
 * </ul>
 *
 * <p>THE HAND-OFF AT 5%, AND ITS ONE EXCEPTION (2 Oct 2026). Above {@link #PLUGIN_SPEAKS_ABOVE} the plugin's own
 * {@code offerDriftHint} (OFFER_DRIFT_THRESHOLD, also 0.05) speaks, so the early path stays quiet and one offer never draws
 * two sentences. EXCEPT BELOW BREAK-EVEN, where the early path speaks WHATEVER THE GAP: the plugin's hint knows nothing of
 * cost basis and ends "or take the current price", which there locks in a loss, and this is the only sentence that names
 * it. The hole was widest when the news was worst. It was never permanent (the clock speaks regardless after a quarter
 * of the pace), but that is 12 hours on Slow. Merging the two speakers is a Phase 6 behaviour change, not this port's.
 */
public final class Relist {
  private Relist() {}

  /** RELIST_AFTER_SHARE: a quarter of the target duration (the backtest's 6 hours into a 24-hour window). */
  public static final double RELIST_AFTER_SHARE = 0.25;
  public static final double MIN_WAIT_MINUTES = 30;
  /** MIN_GAP: below this the market has barely moved and repricing is noise. */
  public static final double MIN_GAP = 0.005;
  /**
   * DRIFT_SPEAKS_NOW: past 1% over the going rate asks took 6-7 hours and a third never sold (a few hundred sell offers
   * watched from placement). 2% was tried first and stayed silent on the hauberk at 1.59% that prompted it, so 1.5%.
   */
  public static final double DRIFT_SPEAKS_NOW = 0.015;
  /** PLUGIN_SPEAKS_ABOVE: the plugin's own OFFER_DRIFT_THRESHOLD; the early path hands over here, unless below break-even. */
  public static final double PLUGIN_SPEAKS_ABOVE = 0.05;
  /** DRIFT_MIN_WAIT_MINUTES: an ask within 1% of the market typically fills in 6 to 24 minutes. */
  public static final double DRIFT_MIN_WAIT_MINUTES = 15;

  /** An in-progress SELL offer: {itemId, name, price, remaining, firstSeen}. NaN/null for what the caller did not have. */
  public static final class SellOffer {
    public final int itemId;
    public final String name;
    public final double price;
    public final double remaining;
    public final Double firstSeen;

    public SellOffer(int itemId, String name, double price, double remaining, Double firstSeen) {
      this.itemId = itemId;
      this.name = name;
      this.price = price;
      this.remaining = remaining;
      this.firstSeen = firstSeen;
    }
  }

  /**
   * relistAdvice for ONE account (server.mjs): its live sells, priced from /latest (market-wide, named as such) for those
   * items, against ITS OWN cost basis -- all three from the view. {@code targetDurationMinutes}: the player's pace, or 1440
   * when none is set (the bridge's {@code targetDurationMinutes ?? 1440}).
   */
  public static List<AdviceNote> forAccount(AccountView view, LatestPrices marketLatest, double targetDurationMinutes, double now) {
    List<SellOffer> offers = new ArrayList<>();
    for (Offer o : view.liveSells)
      offers.add(new SellOffer(o.itemId, o.name, o.price, Math.max(0, o.total - o.filled), o.firstSeen == null ? null : (double) o.firstSeen));
    return relistAdvice(offers, AdviceNotes.pricesFor(marketLatest, view.liveSells), view.costBasis, targetDurationMinutes, now);
  }

  /**
   * relistAdvice: one note per offer worth mentioning, in offer order. An offer priced at or under the market is left
   * alone (it is simply queueing). {@code prices}: item id to its live price (only {@code sellPrice} is read);
   * {@code costBasis}: item id to what was actually paid per unit, where the journal knows it (null: knows nothing).
   * {@code targetDurationMinutes}: the bridge passes the player's pace, or 1440 when none is set.
   * <p>Package-private on purpose: outside the engine the only way in is {@link #forAccount}, which reads ONE account's
   * records from its {@link AccountView}. The parity harness (same package) drives this record-level core directly.
   */
  static List<AdviceNote> relistAdvice(List<SellOffer> offers, Map<Integer, AdviceNote.Price> prices, Map<Integer, Double> costBasis,
                                              double targetDurationMinutes, double now) {
    double waitMinutes = Math.max(MIN_WAIT_MINUTES, targetDurationMinutes * RELIST_AFTER_SHARE);
    List<AdviceNote> out = new ArrayList<>();
    if (offers == null) return out;
    for (SellOffer offer : offers) {
      if (offer == null || !(offer.price > 0) || offer.firstSeen == null || !JsValues.isFinite(offer.firstSeen)) continue;
      double openMinutes = (now - offer.firstSeen) / 60000;
      AdviceNote.Price p = prices == null ? null : prices.get(offer.itemId);
      Double marketBoxed = p == null ? null : p.sellPrice;
      if (marketBoxed == null || !(marketBoxed > 0)) continue;
      double market = marketBoxed;
      if (offer.price <= market) continue; // at or under the market already: it is simply queueing
      double gap = (offer.price - market) / offer.price;
      if (gap < MIN_GAP) continue;
      boolean pastTheClock = openMinutes >= waitMinutes;
      Double paid = costBasis == null ? null : costBasis.get(offer.itemId);
      Long breakEven = paid != null && paid > 0 ? Tax.breakEvenSellPrice(offer.itemId, paid) : null;
      boolean marketUnderBreakEven = breakEven != null && market < breakEven;
      // Below break-even the early path speaks whatever the gap: the plugin's hint would say "take the current price".
      boolean drifted = openMinutes >= DRIFT_MIN_WAIT_MINUTES
        && gap >= DRIFT_SPEAKS_NOW
        && (gap < PLUGIN_SPEAKS_ABOVE || marketUnderBreakEven);
      if (!pastTheClock && !drifted) continue;
      double hours = openMinutes / 60;
      String rounded = ThinMarket.stripPointZero(JsValues.toFixed(hours, hours < 10 ? 1 : 0)); // "8 hours", not "8.0 hours"
      String waited = hours >= 1 ? rounded + " hour" + ("1".equals(rounded) ? "" : "s") : JsValues.text(JsValues.jsRound(openMinutes)) + " minutes";
      String what = offer.remaining > 0 ? JsValues.localeUs(offer.remaining) + " still unsold" : "unsold";
      // The 6-7h figure is EVI's OWN measurement over one player's sell offers, never "your own asks".
      String head = pastTheClock
        ? what + " after " + waited + "."
        : JsValues.toFixed(gap * 100, 1) + "% over the going rate, " + what + ". Past 1%, asks have taken 6-7h to sell in EVI's measurements, and a third never did.";
      String message;
      double suggestedPrice;
      if (breakEven == null) {
        suggestedPrice = market;
        message = head + " Relisting nearer " + JsValues.localeUs(market) + " would be likelier to sell. EVI doesn't know what you paid, so check it is still a profit.";
      } else if (!marketUnderBreakEven) {
        suggestedPrice = market;
        message = head + " Relisting at market still clears your break-even of " + JsValues.localeUs(breakEven) + " after tax. Your call -- EVI never relists for you.";
      } else {
        // The market is under water. Say so and stop; cutting a loss is the player's decision.
        suggestedPrice = breakEven;
        message = head + " The market is now BELOW your break-even (" + JsValues.localeUs(breakEven) + " after tax), so relisting there would lock in a loss. Hold or cut -- EVI won't choose for you.";
      }
      AdviceNote n = new AdviceNote(offer.itemId);
      n.name = offer.name;
      n.message = message;
      n.offerPrice = offer.price;
      n.marketPrice = market;
      n.level = marketUnderBreakEven ? "warn" : "caution";
      n.label = pastTheClock ? "Not selling" : "Market moved away";
      n.figures = JsValues.localeUs(offer.price) + " asked · " + JsValues.localeUs(market) + " market";
      n.breakEven = breakEven == null ? null : (double) breakEven;
      n.suggestedPrice = suggestedPrice;
      n.openMinutes = JsValues.jsRound(openMinutes);
      n.belowBreakEven = marketUnderBreakEven;
      out.add(n);
    }
    return out;
  }
}
