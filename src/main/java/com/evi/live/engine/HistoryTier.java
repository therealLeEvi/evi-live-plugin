package com.evi.live.engine;

import com.evi.live.journal.Js;
import com.evi.live.journal.Tax;
import com.evi.live.market.HourBucket;
import com.evi.live.market.LatestPrices;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The personal-history tier (bridge/suggestions.mjs {@code computeSuggestion}): the best BUY among items the player has
 * flipped before, ranked on their own track record. Pure; the clock is passed in.
 *
 * <p>THE ORDER OF THE CHECKS IS THE JS ORDER, and each one is the shipped rule:
 * <ol>
 *   <li>eligible at the risk level ({@link History#eligible}: trades, win rate, a typical size of at least one, not blocked);</li>
 *   <li>both sides priced in /latest;</li>
 *   <li>a positive margin after tax, and one that clears the level's tax bar ({@link Gates#marginClearsTax}, 0.5x on this tier
 *       -- a winning history does not make a 2 gp edge survive a 1 gp tick), unless "no minimum at all" switched it off;</li>
 *   <li>not an implausible spread on an hour nobody traded, and not an implausible buy print;</li>
 *   <li>sized ({@link Sizing#historyQuantity}: own median, cash, stack share, volume share, trade length, buy limit);</li>
 *   <li>the predicted profit (margin x quantity) reaches the minimum, and the score is above zero.</li>
 * </ol>
 * The best SCORE wins (a stable sort, ties keep the history's order). Never dropped for a stale print here: the player's
 * own record makes an old last trade context, so it is named in the reasoning instead.
 *
 * <p>The risk level reaches this tier through {@link LevelSettings} only (the seam the redesign fills in).
 */
public final class HistoryTier {
  private HistoryTier() {}

  /** computeSuggestion's options. Unset numbers are NaN; unset objects null. */
  public static final class Options {
    /** Below this predicted total a candidate is not offered; NaN or not above 0 means no floor. */
    public double minProfit = Double.NaN;
    public Set<Integer> blocklist;
    /** "low", "medium" or "high"; anything else (null included) is MEDIUM, this tier's own fallback. */
    public String risk;
    /** The cash stack: NaN unknown (no cap), 0 carrying nothing. */
    public double maxSpend = Double.NaN;
    public double targetDurationMinutes = Double.NaN;
    /** The latest Wiki hour (null: none was fetched). */
    public HourBucket volumes;
    /** The 168-hour typical volume per item (null: none). */
    public Map<Integer, ? extends Number> typicalVolumes;
    public double maxStackShare = Double.NaN;
    public double maxVolumeShare = Double.NaN;
    public double volumeWindowShare = Double.NaN;
    public boolean captureCap;
    /** Members world, focus and the buy limit left. */
    public Sizing.ItemGate gate = Sizing.ItemGate.NONE;
    /** {@code options.requireMarginOverTax !== false}: only "no minimum at all" turns the tax bar off. */
    public boolean requireMarginOverTax = true;
    /** {@code options.marginTaxMultiple}: null takes the level's bar (the bridge never passes one). */
    public Double marginTaxMultiple;
  }

  /** recencyWeight: 1 within a day, 0.85 within a week, 0.6 within a month, else (and for no date) 0.35. */
  public static double recencyWeight(double lastTradeAt, double now) {
    double days = (now - lastTradeAt) / 86400000;
    if (days <= 1) return 1;
    if (days <= 7) return 0.85;
    if (days <= 30) return 0.6;
    return 0.35;
  }

  /**
   * RISK_TIERS[level].score: the average profit, times the win rate {@link LevelSettings#historyWinRatePower} times,
   * times the recency weight -- multiplied LEFT TO RIGHT as the JS writes it ({@code avgProfit * winRate * winRate * rw}),
   * so the rounding of a tie is the same.
   */
  public static double score(History.ItemHistory h, double recency, LevelSettings level) {
    double s = h.avgProfit;
    for (int i = 0; i < level.historyWinRatePower; i++) s *= h.winRate;
    return s * recency;
  }

  private static final class Candidate {
    final History.ItemHistory h;
    final LatestPrices.Quote p;
    final double score;
    final Sizing.Sized sized;
    final double predictedProfit;
    final Double ageMinutes;

    Candidate(History.ItemHistory h, LatestPrices.Quote p, double score, Sizing.Sized sized, double predictedProfit, Double ageMinutes) {
      this.h = h;
      this.p = p;
      this.score = score;
      this.sized = sized;
      this.predictedProfit = predictedProfit;
      this.ageMinutes = ageMinutes;
    }
  }

  /** {@code latestPrices[String(itemId)]}: only a whole id in int range names an item (String(1.5) finds nothing). */
  private static LatestPrices.Quote quote(LatestPrices latest, double itemId) {
    if (latest == null || !JsValues.isInteger(itemId) || itemId > Integer.MAX_VALUE || itemId < Integer.MIN_VALUE) return null;
    return latest.get((int) itemId);
  }

  private static double typicalFor(Map<Integer, ? extends Number> typical, int itemId) {
    Number n = typical == null ? null : typical.get(itemId);
    return n == null ? Double.NaN : n.doubleValue();
  }

  /** computeSuggestion: one best suggestion from the player's own history, or null. */
  public static Pick computeSuggestion(List<History.Flip> flips, LatestPrices latest, double now, Options options) {
    Options o = options == null ? new Options() : options;
    double minProfit = JsValues.isFinite(o.minProfit) && o.minProfit > 0 ? o.minProfit : 0;
    Double maxSpend = Sizing.maxSpend(o.maxSpend);
    Double target = Sizing.duration(o.targetDurationMinutes);
    LevelSettings level = LevelSettings.forHistoryTier(o.risk);
    double marginMultiple = o.marginTaxMultiple != null ? o.marginTaxMultiple : level.historyMarginTaxMultiple;
    Sizing.SizingOptions sizing = new Sizing.SizingOptions(o.maxVolumeShare, o.volumeWindowShare, o.captureCap);
    List<Candidate> candidates = new ArrayList<>();
    for (History.ItemHistory h : History.personalHistory(flips)) {
      if (!History.eligible(h, level, o.blocklist)) continue;
      LatestPrices.Quote p = quote(latest, h.itemId);
      if (p == null || !(p.low > 0) || !(p.high > 0)) continue;
      int itemId = (int) h.itemId;
      long tax = Tax.estimateUnitTax(itemId, p.high);
      long net = Js.subtract(Js.subtract(p.high, p.low), tax); // JS arithmetic on GP, exact below 2^53
      if (net <= 0) continue;
      // An edge that does not cover this item's own tax, however good the track record.
      if (o.requireMarginOverTax && !Gates.marginClearsTax(net, tax, marginMultiple)) continue;
      // A margin too good to be true on something nobody is trading; this tier keeps stale prints, which is how a
      // ten-hour-old 10 gp print on Rune dart(p++) became a buy of 1,301.
      VolumeRow row = VolumeRow.of(o.volumes, itemId);
      Long reading = VolumeRow.reading(row);
      if (Gates.implausibleSpread(net, p.low, reading == null ? Double.NaN : reading, Gates.IMPLAUSIBLE_MARGIN_MULTIPLE)) continue;
      if (Gates.implausibleBuyPrint(p.low, row, Gates.BUY_PRINT_MULTIPLE)) continue;
      Sizing.Sized sized = Sizing.historyQuantity(itemId, h.medianQty, p.low, maxSpend, o.maxStackShare, row, typicalFor(o.typicalVolumes, itemId),
        target, sizing, o.gate);
      if (sized == null) continue;
      double predictedProfit = (double) net * sized.quantity;
      if (predictedProfit < minProfit) continue;
      double score = score(h, recencyWeight(h.lastTradeAt, now), level);
      if (score <= 0) continue;
      Double ageMinutes = Prices.priceAgeMinutes(side(p.highTime), side(p.lowTime), now);
      candidates.add(new Candidate(h, p, score, sized, predictedProfit, ageMinutes));
    }
    if (candidates.isEmpty()) return null;
    candidates.sort((a, b) -> Js.compare(b.score, a.score)); // stable: an equal score keeps the history's order
    Candidate c = candidates.get(0);
    History.ItemHistory h = c.h;
    Sizing.Sized s = c.sized;
    double shareUsed = JsValues.isFinite(o.maxVolumeShare) ? o.maxVolumeShare : Sizing.volumeShareForDuration(target == null ? Double.NaN : target);
    List<String> notes = new ArrayList<>();
    if (s.cashLimited) notes.add("reduced from your usual size to what your current cash stack can afford");
    if (s.stackLimited) notes.add("reduced so this one trade commits at most " + JsValues.text(JsValues.jsRound(o.maxStackShare * 100)) + "% of your cash stack");
    // Worded from whichever cap actually bound (the maintainer, 7 Oct 2026): under "Bigger positions" the WINDOW share
    // replaces the per-hour one (Sizing.orderSizeCap), so it is the one named.
    if (s.shareLimited) notes.add(JsValues.isFinite(o.volumeWindowShare) && o.volumeWindowShare > 0
      ? "reduced to " + JsValues.text(JsValues.jsRound(o.volumeWindowShare * 100))
        + "% of what this item trades over your whole trade window, so the order isn't larger than the market absorbs"
      : "reduced to " + JsValues.text(JsValues.jsRound(shareUsed * 100))
        + "% of what this item trades in a typical hour, so the order isn't larger than the market absorbs");
    if (s.durationLimited) notes.add("reduced to fit an estimated ~" + JsValues.text(target) + "-minute trade");
    if (s.limitLimited) notes.add("reduced to what EVI has seen left of this item's GE buy limit, which is all that can fill before the limit resets in 4 hours -- a bigger offer would sit part-filled until then");
    if (c.ageMinutes != null && c.ageMinutes > Prices.MAX_PRICE_AGE_MINUTES)
      notes.add("note that one side of this item's price is about " + JsValues.text(JsValues.jsRound(c.ageMinutes / 60)) + " hour(s) old, so the current spread may not be real");
    Pick out = new Pick((int) h.itemId, h.name, "buy", s.quantity, c.p.low, c.p.high, "personal");
    out.trades = h.trades;
    out.reasoning = "You've flipped this " + h.trades + " time" + (h.trades == 1 ? "" : "s") + " with a " + JsValues.text(JsValues.jsRound(h.winRate * 100))
      + "% win rate and ~" + JsValues.gp(h.avgProfit) + " GP average profit. Suggested quantity matches your typical size (" + s.quantity + ")"
      + (notes.isEmpty() ? "" : " -- " + String.join("; ", notes)) + "; buy near " + Js.groupedUs(c.p.low) + " gp, aim to sell near "
      + Js.groupedUs(c.p.high) + " gp for a predicted ~" + JsValues.gp(c.predictedProfit) + " gp.";
    return out;
  }

  // A /latest timestamp as priceAgeMinutes reads it: NaN when the Wiki sent none.
  private static double side(long v) {
    return v == HourBucket.NONE ? Double.NaN : v;
  }
}
