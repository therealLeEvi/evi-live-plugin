package com.evi.live.engine;

/**
 * THE RISK-LEVEL SEAM. Every value the engine reads that the risk redesign is still deciding lives here, holding
 * exactly what the bridge does TODAY, so the ports never hard-code one deep inside a function and the redesign
 * can fill these in once its levels are calibrated (SELF-CONTAINED-PLAN Phase 5; EVI-REDESIGN-PLAN step 3).
 *
 * <p>TODAY THE RISK LEVEL CHANGES ONE THING: the personal-history tier's eligibility ({@code RISK_TIERS} in
 * suggestions.mjs: minimum trades, minimum win rate, and how strongly the win rate weighs in the score). The
 * margin bars and the liquidity floor are the same at every level, and the market tier does not read the level at
 * all. So {@link #LOW}, {@link #MEDIUM} and {@link #HIGH} differ only in those three history fields; the market tier's
 * bar, floor and ranking ({@link #noHistoryMarginTaxMultiple}, {@link #minHourlyVolume}, {@link #marketRankBy}) are the same
 * at every level, as in the bridge. Each value is
 * asserted against the JS constant it stands for by EngineVectorsTest, so a change on either side is caught.
 *
 * <p>Two defaults, deliberately different, because they are in the bridge: a request naming no (or an unknown) risk
 * is LOW ({@code server.mjs}: anything else falls back to 'low'), while the history tier's own fallback for an
 * unknown tier name is MEDIUM ({@code RISK_TIERS[options.risk] || RISK_TIERS.medium}).
 */
public final class LevelSettings {
  /** The bar for a tier with the player's own track record on the item ({@code MARGIN_TAX_MULTIPLE}). */
  public final double historyMarginTaxMultiple;
  /** The bar for the market-wide and scanner tiers, which have no track record ({@code MARGIN_TAX_NO_HISTORY_MULTIPLE}). */
  public final double noHistoryMarginTaxMultiple;
  /** Below this a personal-tier pick is offered with a warning ({@code MARGIN_TAX_WARN_MULTIPLE}). */
  public final double warnMarginTaxMultiple;
  /** The market tier's hourly liquidity floor ({@code MIN_HOURLY_VOLUME}). */
  public final double minHourlyVolume;
  /** {@code RISK_TIERS[level].minTrades}. */
  public final int historyMinTrades;
  /** {@code RISK_TIERS[level].minWinRate}. */
  public final double historyMinWinRate;
  /** The power of the win rate in {@code RISK_TIERS[level].score}: 2 for low, 1 for medium, 0 for high. */
  public final int historyWinRatePower;
  /**
   * The market tier's ranking rule ({@code options.rankBy} in computeMarketSuggestion): {@code null} -- the shipped
   * score, the margin times log(liquidity + 1) -- at EVERY level today, because server.mjs's suggestionPolicy passes
   * {@code marketRankBy: undefined} on purpose (4 Oct: the BIGGER_POSITIONS 'profit' ranking was a shipped defect, and
   * nothing may fill this seam without a replay behind it). The other values {@link MarketTier} understands are
   * 'profit-per-hour', 'profit' and 'soft-liquidity', each measured and none in use.
   */
  public final String marketRankBy;

  private LevelSettings(int minTrades, double minWinRate, int winRatePower) {
    this.historyMarginTaxMultiple = 0.5;
    this.noHistoryMarginTaxMultiple = 1;
    this.warnMarginTaxMultiple = 1;
    this.minHourlyVolume = 5;
    this.historyMinTrades = minTrades;
    this.historyMinWinRate = minWinRate;
    this.historyWinRatePower = winRatePower;
    this.marketRankBy = null;
  }

  public static final LevelSettings LOW = new LevelSettings(3, 0.75, 2);
  public static final LevelSettings MEDIUM = new LevelSettings(1, 0.5, 1);
  public static final LevelSettings HIGH = new LevelSettings(1, 0.4, 0);

  /** The level a request asks for: 'low', 'medium' or 'high', anything else (absent included) LOW, as server.mjs reads it. */
  public static LevelSettings forRequest(String risk) {
    if ("medium".equals(risk)) return MEDIUM;
    if ("high".equals(risk)) return HIGH;
    return LOW;
  }

  /** The history tier's own lookup: an unknown name is MEDIUM ({@code RISK_TIERS[options.risk] || RISK_TIERS.medium}). */
  public static LevelSettings forHistoryTier(String risk) {
    if ("low".equals(risk)) return LOW;
    if ("high".equals(risk)) return HIGH;
    return MEDIUM;
  }
}
