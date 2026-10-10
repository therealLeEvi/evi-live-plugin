package com.evi.live.engine;

import com.evi.live.journal.Js;
import com.evi.live.journal.Tax;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.LatestPrices;
import com.evi.live.market.MarketAggregates;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The market-wide tier (bridge/suggestions.mjs {@code computeMarketSuggestion}): the best BUY in the whole item catalogue
 * for a player with no history on it, and the request's three ways of asking for one (server.mjs's {@code rank} and
 * {@code marketRankAt}, as {@link Ranking}). Pure; the clock is passed in.
 *
 * <p>THE ORDER OF THE CHECKS IS THE JS ORDER, item by item in the MAPPING's order:
 * <ol>
 *   <li>a valid id, not blocklisted, not a members item on a free world;</li>
 *   <li>both sides priced in /latest;</li>
 *   <li>the liquidity floor on the LATEST hour ({@link Gates#liquidityFloorMet}: 5 an hour, or 24 over the window under
 *       "Bigger positions");</li>
 *   <li>tax-free only (the old Starter profile), when asked;</li>
 *   <li>not a stale spread: the staler side at most an hour old ({@link Prices#MAX_PRICE_AGE_MINUTES}; unknown times fail open);</li>
 *   <li>a positive margin after tax that clears the NO-HISTORY bar, 1x the item's own tax ({@link LevelSettings#noHistoryMarginTaxMultiple}),
 *       on the live quote AND on the steady (336-hour robust) price -- an item has to clear both views, not whichever flatters it;</li>
 *   <li>not an implausible spread on an hour nobody traded, and not an implausible buy print;</li>
 *   <li>sized ({@link Sizing#marketQuantity}: the buy limit across the pace's windows, cash, the trade length, the stack
 *       share, the window or volume share on the typical hour, the 4-hour buy-limit clip);</li>
 *   <li>the more cautious of the live and the steady total reaches the minimum, and the score is above zero.</li>
 * </ol>
 *
 * <p>RANK ON A STEADY PRICE, QUOTE THE LIVE ONE. The score is the steady margin times the quantity (once cash or a pace
 * constrains), times {@code log(liquidity + 1)} on the LATEST hour; the quote, the predicted profit and the reasoning are
 * the live figures. {@link StrictMath#log} (fdlibm, which V8's Math.log is a port of), so a tie in the score is a tie in
 * both engines -- and a tie keeps the MAPPING's order (a stable sort; {@link Js#compare}, NaN as equal), because the
 * order and the ties decide which item {@code spreadRank} hands each account.
 *
 * <p>THE RANKING RULE is {@link Options#rankBy}: null is the shipped score. The other three are measured variants the
 * bridge never passes ({@link LevelSettings#marketRankBy} is null at every level); they are ported so the tools'
 * replays and this port answer alike, not to be switched on.
 *
 * <p>GP is a {@code long} wherever a price, a margin or a quantity is held; a margin TIMES a quantity is the double JS
 * computes ({@code net * quantity}), exact below 2^53, and only ever compared or printed.
 */
public final class MarketTier {
  private MarketTier() {}

  /** computeMarketSuggestion's options. Unset numbers are NaN; unset objects null. */
  public static final class Options {
    /** Below this (the more cautious total) a candidate is not offered; NaN or not above 0 means no floor. */
    public double minProfit = Double.NaN;
    public Set<Integer> blocklist;
    /** The cash stack: NaN unknown (no cap), 0 carrying nothing (afford nothing). */
    public double maxSpend = Double.NaN;
    public double targetDurationMinutes = Double.NaN;
    /**
     * The clock, epoch ms, for the stale-spread check. The JS falls back to Date.now(); this port has no clock of its own,
     * so the caller always passes one -- NaN judges nothing stale (an age against no clock is no age).
     */
    public double now = Double.NaN;
    /** {@code options.maxPriceAgeMinutes}; NaN takes {@link Prices#MAX_PRICE_AGE_MINUTES}. */
    public double maxPriceAgeMinutes = Double.NaN;
    /** {@code options.maxVolumeShare}: NaN takes the pace's share ({@link Sizing#volumeShareForDuration}); 0 or below turns the cap off. */
    public double maxVolumeShare = Double.NaN;
    /** "Bigger positions": a share of the WHOLE window's volume instead of the per-hour cap; NaN unset. */
    public double volumeWindowShare = Double.NaN;
    /** "Bigger positions": the per-WINDOW liquidity floor instead of the hourly one; NaN unset. */
    public double minVolumeInWindow = Double.NaN;
    /** Raises the hourly floor above the level's (never below it); NaN unset. */
    public double minHourlyVolume = Double.NaN;
    /** The share of the cash stack one trade may commit (stackShare / 100); NaN unset. Only with a known stack. */
    public double maxStackShare = Double.NaN;
    /** Only items the GE charges no tax on. */
    public boolean taxFreeOnly;
    /** {@code options.requireMarginOverTax !== false}: only "no minimum at all" turns the tax bar off. */
    public boolean requireMarginOverTax = true;
    /** {@code options.marginTaxMultiple}: null takes the level's no-history bar (the bridge never passes one). */
    public Double marginTaxMultiple;
    /** The steady price to RANK on, by item ({@code robustPricesCached}); null or a missing item ranks on the live quote. */
    public Map<Integer, MarketAggregates.RobustPrice> rankPrices;
    /** The ranking rule: null (today's), 'profit-per-hour', 'profit' or 'soft-liquidity'. */
    public String rankBy;
    /** Which candidate from the top this account is handed (0 the best); NaN is 0, and it is CLAMPED to the last. */
    public double spreadRank = Double.NaN;
    /** The 168-hour typical volume per item (null: none), the basis an order is SIZED on. */
    public Map<Integer, ? extends Number> typicalVolumes;
    /** {@code options.volumes}: an hour for SIZING only. The bridge never passes it (the hour is the volumes argument). */
    public HourBucket volumes;
    /** The opt-in capture-curve cap (falsified 4 Oct, off by default). */
    public boolean captureCap;
    /** Members world, focus and the buy limit left. */
    public Sizing.ItemGate gate = Sizing.ItemGate.NONE;
    /** The level whose bars apply; null is LOW (today the market tier reads the same bars at every level). */
    public LevelSettings level;
  }

  private static final class Candidate {
    final ItemCatalog.Item item;
    final LatestPrices.Quote p;
    final double predictedProfit;
    final double score;
    final Sizing.Sized sized;
    final boolean limitKnown;
    final long fullSize;

    Candidate(ItemCatalog.Item item, LatestPrices.Quote p, double predictedProfit, double score, Sizing.Sized sized, boolean limitKnown, long fullSize) {
      this.item = item;
      this.p = p;
      this.predictedProfit = predictedProfit;
      this.score = score;
      this.sized = sized;
      this.limitKnown = limitKnown;
      this.fullSize = fullSize;
    }
  }

  // A /latest timestamp as priceAgeMinutes reads it: NaN when the Wiki sent none.
  private static double side(long v) {
    return v == HourBucket.NONE ? Double.NaN : v;
  }

  private static double typicalFor(Map<Integer, ? extends Number> typical, int itemId) {
    Number n = typical == null ? null : typical.get(itemId);
    return n == null ? Double.NaN : n.doubleValue();
  }

  /** windowShareOn: is the "Bigger positions" window share in force (a finite share above 0)? The same test the sizing makes. */
  static boolean windowShareOn(double volumeWindowShare) {
    return JsValues.isFinite(volumeWindowShare) && volumeWindowShare > 0;
  }

  /**
   * computeMarketSuggestion: the best market-wide buy, or null. {@code mapping} is the Wiki /mapping in its own order,
   * {@code latest} the /latest quotes, {@code volumes} the latest Wiki hour (the floor, the fill check and the score read
   * it; null for none).
   */
  public static Pick computeMarketSuggestion(List<ItemCatalog.Item> mapping, LatestPrices latest, HourBucket volumes, Options options) {
    if (mapping == null || latest == null) return null;
    Options o = options == null ? new Options() : options;
    double minProfit = JsValues.isFinite(o.minProfit) && o.minProfit > 0 ? o.minProfit : 0;
    Set<Integer> blocklist = o.blocklist == null ? Collections.emptySet() : o.blocklist;
    Double maxSpend = Sizing.maxSpend(o.maxSpend);
    Double target = Sizing.duration(o.targetDurationMinutes);
    double maxPriceAge = JsValues.isFinite(o.maxPriceAgeMinutes) ? o.maxPriceAgeMinutes : Prices.MAX_PRICE_AGE_MINUTES;
    double volumeShare = JsValues.isFinite(o.maxVolumeShare) ? o.maxVolumeShare : Sizing.volumeShareForDuration(target == null ? Double.NaN : target);
    LevelSettings level = o.level == null ? LevelSettings.LOW : o.level;
    double marginMultiple = o.marginTaxMultiple != null ? o.marginTaxMultiple : level.noHistoryMarginTaxMultiple;
    Sizing.SizingOptions sizing = new Sizing.SizingOptions(o.maxVolumeShare, o.volumeWindowShare, o.captureCap);
    Sizing.ItemGate gate = o.gate == null ? Sizing.ItemGate.NONE : o.gate;
    List<Candidate> candidates = new ArrayList<>();
    for (ItemCatalog.Item item : mapping) {
      if (item == null || blocklist.contains(item.id)) continue;
      // A members-only item cannot be traded on a free-to-play world at all.
      if (gate.membersBlocked(item.id)) continue;
      LatestPrices.Quote p = latest.get(item.id);
      if (p == null || !(p.low > 0) || !(p.high > 0)) continue; // NONE (no price) is Long.MIN_VALUE: not above 0
      VolumeRow row = VolumeRow.of(volumes, item.id);
      long liquidity = VolumeRow.liquidity(row);
      // "Does this item trade at all" is about NOW: the floor reads the latest hour, only the SIZE reads a typical one.
      if (!Gates.liquidityFloorMet(liquidity, target, o.minVolumeInWindow, o.minHourlyVolume, level)) continue;
      if (o.taxFreeOnly && Tax.estimateUnitTax(item.id, p.high) > 0) continue;
      // No track record backs this tier, so a spread nobody has traded on either side within the hour is not ranked.
      Double ageMinutes = Prices.priceAgeMinutes(side(p.highTime), side(p.lowTime), o.now);
      if (ageMinutes != null && ageMinutes > maxPriceAge) continue;
      long tax = Tax.estimateUnitTax(item.id, p.high);
      long net = Js.subtract(Js.subtract(p.high, p.low), tax);
      if (net <= 0) continue;
      // The tier that suggested the blood runes: an edge thinner than the item's own tax is not offered at all.
      if (o.requireMarginOverTax && !Gates.marginClearsTax(net, tax, marginMultiple)) continue;
      // RANK on the steady price, QUOTE the live one -- and clear every bar on BOTH.
      MarketAggregates.RobustPrice r = o.rankPrices == null ? null : o.rankPrices.get(item.id);
      long rankHigh = r != null && r.high > 0 ? r.high : p.high;
      long rankLow = r != null && r.low > 0 ? r.low : p.low;
      long rankTax = Tax.estimateUnitTax(item.id, rankHigh);
      long netRank = Js.subtract(Js.subtract(rankHigh, rankLow), rankTax);
      if (netRank <= 0) continue;
      if (o.requireMarginOverTax && !Gates.marginClearsTax(netRank, rankTax, marginMultiple)) continue;
      Long reading = VolumeRow.reading(row);
      if (Gates.implausibleSpread(net, p.low, reading == null ? Double.NaN : reading, Gates.IMPLAUSIBLE_MARGIN_MULTIPLE)) continue;
      if (Gates.implausibleBuyPrint(p.low, row, Gates.BUY_PRINT_MULTIPLE)) continue;
      // The item's own buy limit when known, across every 4-hour window the pace spans, else the flat cap of 100.
      Long catalogLimit = item.limit();
      boolean limitKnown = catalogLimit != null;
      double limit = limitKnown ? catalogLimit : Double.NaN;
      long fullSize = JsValues.exactLong(Math.max(1, limitKnown
        ? Sizing.limitAllowance(limit, 0, Double.NaN, target == null ? Double.NaN : target, 0) : Sizing.DEFAULT_MARKET_QUANTITY_CAP));
      Long optionsReading = o.volumes == null ? null : VolumeRow.reading(VolumeRow.of(o.volumes, item.id));
      Sizing.Sized sized = Sizing.marketQuantity(item.id, limit, p.low, maxSpend, o.maxStackShare, row, optionsReading,
        typicalFor(o.typicalVolumes, item.id), target, sizing, gate);
      if (sized == null) continue;
      long quantity = sized.quantity;
      // The floor is judged on the more cautious of the live and the steady total; the reasoning quotes the live one.
      double predictedProfit = (double) net * quantity;
      double rankProfit = (double) netRank * quantity;
      if (Math.min(predictedProfit, rankProfit) < minProfit) continue;
      // Once cash or a pace constrains, the best TOTAL reachable within it, log-damped by real liquidity; else per unit.
      boolean constrained = maxSpend != null || target != null;
      double score = (constrained ? rankProfit : netRank) * StrictMath.log(liquidity + 1);
      if ("profit-per-hour".equals(o.rankBy)) {
        double hours = Math.max(Sizing.correctedFillMinutes(Sizing.estimatedFillMinutes(quantity, liquidity, Sizing.VOLUME_WINDOW_MINUTES)) * 2,
          Sizing.FILL_FLOOR_MINUTES) / 60;
        score = rankProfit / hours;
      }
      if ("profit".equals(o.rankBy)) score = rankProfit;
      if ("soft-liquidity".equals(o.rankBy)) score = (constrained ? rankProfit : netRank) * Math.sqrt(StrictMath.log(liquidity + 1));
      if (score <= 0) continue;
      candidates.add(new Candidate(item, p, predictedProfit, score, sized, limitKnown, fullSize));
    }
    if (candidates.isEmpty()) return null;
    candidates.sort((a, b) -> Js.compare(b.score, a.score)); // stable: an equal score keeps the mapping's order
    Candidate c = candidates.get(spreadIndex(o.spreadRank, candidates.size()));
    return pick(c, o, target, volumeShare);
  }

  /**
   * {@code Math.min(Math.max(0, Math.floor(spreadRank) || 0), candidates.length - 1)}: NaN, -0 and anything below 0 are
   * the top pick, a fraction is floored, and a rank past the end CLAMPS to the last candidate rather than answering
   * nothing (a thin hour with two candidates still answers a spread rank of 4).
   */
  static int spreadIndex(double spreadRank, int size) {
    double floored = Math.floor(spreadRank);
    double rank = Double.isNaN(floored) || floored == 0 ? 0 : floored; // (x || 0)
    return (int) Math.min(Math.max(0, rank), size - 1);
  }

  private static Pick pick(Candidate c, Options o, Double target, double volumeShare) {
    ItemCatalog.Item item = c.item;
    Sizing.Sized s = c.sized;
    List<String> notes = new ArrayList<>();
    // fullSize counts EVERY window the pace spans, so it is never quoted as the per-window limit (5 Oct: Ancient essence's
    // 300,000 was told as 3,600,000 per 4 hours on Slow); the total is named beside it only past one window.
    long windows = c.limitKnown ? Js.round(c.fullSize / (double) item.limit()) : 1;
    notes.add(c.limitKnown
      ? "sized to this item's own GE buy limit of " + Js.groupedUs(item.limit()) + " per 4 hours"
        + (windows > 1 ? ", counted over the " + windows + " four-hour windows your trade spans (" + Js.groupedUs(c.fullSize) + " in all)" : "")
      : "capped at " + Js.groupedUs(c.fullSize) + " because this item's GE buy limit is unknown");
    if (s.cashLimited) notes.add("capped to what your current cash stack can afford");
    if (s.durationLimited) notes.add("capped to fit an estimated ~" + JsValues.text(target) + "-minute trade");
    if (s.limitLimited) notes.add("capped to what EVI has seen left of this item's GE buy limit, which is all that can fill before the limit resets in 4 hours -- a bigger offer would sit part-filled until then");
    if (s.stackLimited) notes.add("capped so this one trade commits at most " + JsValues.text(JsValues.jsRound(o.maxStackShare * 100)) + "% of your cash stack");
    // Worded from whichever cap actually bound (the maintainer, 7 Oct 2026): under "Bigger positions" the WINDOW share
    // replaces the per-hour one, so it is the one named.
    if (s.shareLimited) notes.add(windowShareOn(o.volumeWindowShare)
      ? "capped to " + JsValues.text(JsValues.jsRound(o.volumeWindowShare * 100))
        + "% of what this item trades over your whole trade window, so the order isn't larger than the market absorbs"
      : "capped to " + JsValues.text(JsValues.jsRound(volumeShare * 100))
        + "% of what this item trades in a typical hour, so the order isn't larger than the market absorbs"
        + (volumeShare > Sizing.DEFAULT_MAX_VOLUME_SHARE ? " over your " + windowWords(target) + " trade window" : ""));
    Pick out = new Pick(item.id, item.name, "buy", s.quantity, c.p.low, c.p.high, "market");
    out.reasoning = "Market-wide pick -- you have no flip history for this item yet. Current margin after tax is ~" + JsValues.gp(c.predictedProfit)
      + " gp at quantity " + s.quantity + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")") + " (buy near " + Js.groupedUs(c.p.low)
      + " gp, sell near " + Js.groupedUs(c.p.high) + " gp). Verify liquidity and news yourself before committing capital; this ranking has no track record behind it.";
    return out;
  }

  /**
   * {@code targetDurationMinutes >= 120 ? Math.round(t / 60) + '-hour' : t + '-minute'} -- and with NO pace (an explicit
   * per-hour share above 10% and no duration) the JS prints "undefined-minute", which is kept as it is: the sentence is
   * the bridge's, and changing it belongs in the JS first.
   */
  private static String windowWords(Double target) {
    if (target == null) return "undefined-minute";
    return target >= 120 ? JsValues.text(JsValues.jsRound(target / 60)) + "-hour" : JsValues.text(target) + "-minute";
  }

  // ------------------------------------------------------------------------------------------- the request's rankers

  /**
   * One request's market-tier rankers, as server.mjs builds them inside GET /api/suggestion from its {@code marketOpts}
   * (cash, pace, the stack share, tax-free only, the steady prices, the ranking rule, the sizing spread and the gates):
   * <ul>
   *   <li>{@link #rank}: the main pick, at the request's own minimum and cash, handed this account's {@code spreadRank};</li>
   *   <li>{@link #rankAt}: {@code marketRankAt(bl, spend, mp)} -- the Auto step-down, the reachable probe and the further
   *       positions, at a budget and a minimum of their own, with the SAME spread rank.</li>
   * </ul>
   * Until 8 Oct the second carried no spread rank (the 5 Oct wiring gap: above 10m most picks are the step-down's, and
   * 60-68% of accounts shared one); the maintainer approved closing it, in the bridge first. The bridge's dead
   * {@code marketAt}/{@code rankAtMinimum} closures were deleted the same day. The scanner-pushed shortlist the bridge may
   * prefer inside marketRankAt has no source in the self-contained plugin, so this is always the catalogue ranking.
   */
  public static final class Ranking {
    private final List<ItemCatalog.Item> mapping;
    private final LatestPrices latest;
    private final HourBucket volumes;
    private final Double maxSpend;
    private final double spreadRank;
    private final Options shared;

    /**
     * @param shared the request's {@code marketOpts}: everything but the minimum, the blocklist, the cash and the spread
     *               rank, which each call sets (its own blocklist, minimum and cash fields are ignored)
     * @param maxSpend the request's cash cap ({@link QueryValues#maxSpendFromQuery}): null unknown, 0 carrying nothing
     * @param spreadRank {@link SpreadRank#spreadRankFor} for the asking account
     */
    public Ranking(List<ItemCatalog.Item> mapping, LatestPrices latest, HourBucket volumes, Options shared, Double maxSpend, double spreadRank) {
      this.mapping = mapping;
      this.latest = latest;
      this.volumes = volumes;
      this.shared = shared;
      this.maxSpend = maxSpend;
      this.spreadRank = spreadRank;
    }

    private Options with(Set<Integer> blocklist, Double spend, double minProfit, double spread) {
      Options o = new Options();
      o.minProfit = minProfit;
      o.blocklist = blocklist;
      o.maxSpend = spend == null ? Double.NaN : spend;
      o.spreadRank = spread;
      o.targetDurationMinutes = shared.targetDurationMinutes;
      o.now = shared.now;
      o.maxPriceAgeMinutes = shared.maxPriceAgeMinutes;
      o.maxVolumeShare = shared.maxVolumeShare;
      o.volumeWindowShare = shared.volumeWindowShare;
      o.minVolumeInWindow = shared.minVolumeInWindow;
      o.minHourlyVolume = shared.minHourlyVolume;
      o.maxStackShare = shared.maxStackShare;
      o.taxFreeOnly = shared.taxFreeOnly;
      o.requireMarginOverTax = shared.requireMarginOverTax;
      o.marginTaxMultiple = shared.marginTaxMultiple;
      o.rankPrices = shared.rankPrices;
      o.rankBy = shared.rankBy;
      o.typicalVolumes = shared.typicalVolumes;
      o.volumes = shared.volumes;
      o.captureCap = shared.captureCap;
      o.gate = shared.gate;
      o.level = shared.level;
      return o;
    }

    /** server.mjs {@code rank(bl)}: the request's minimum and cash, and this account's spread rank. */
    public Pick rank(Set<Integer> blocklist, double minProfit) {
      return computeMarketSuggestion(mapping, latest, volumes, with(blocklist, maxSpend, minProfit, spreadRank));
    }

    /**
     * server.mjs {@code marketRankAt(bl, spend, mp)}: a budget and a minimum of its own, and the SAME spread rank as
     * {@link #rank} (8 Oct fix 2: the Auto step-down, the reachable probe and further positions all carry it).
     */
    public Pick rankAt(Set<Integer> blocklist, Double spend, double minProfit) {
      return computeMarketSuggestion(mapping, latest, volumes, with(blocklist, spend, minProfit, spreadRank));
    }

    /** {@link #rank} as a pick chain's tier. */
    public PickChain.Rank ranked(double minProfit) {
      return bl -> rank(bl, minProfit);
    }

    /** {@link #rankAt} as a pick chain's tier. */
    public PickChain.Rank rankedAt(Double spend, double minProfit) {
      return bl -> rankAt(bl, spend, minProfit);
    }
  }
}
