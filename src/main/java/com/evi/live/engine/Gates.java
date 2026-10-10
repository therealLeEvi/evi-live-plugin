package com.evi.live.engine;

import com.evi.live.market.HourBucket;
import java.util.Set;

/**
 * The checks that decide whether a candidate may be offered at all, ported from bridge/suggestions.mjs. Pure.
 *
 * <p>EVERY ONE FAILS OPEN. A missing or non-finite input never blocks a candidate: "we could not tell" must never
 * read as "we found a problem" (the standing rule). Each answers exactly as the JS does on NaN, infinities and
 * absent values, which is what EngineVectorsTest replays.
 *
 * <p>The bars the risk redesign may move (the margin multiples, the hourly floor) come from {@link LevelSettings};
 * the multiples here are only the JS functions' own parameter defaults.
 */
public final class Gates {
  private Gates() {}

  /** marginClearsTax's default bar, MARGIN_TAX_MULTIPLE (the tier with a track record). */
  public static final double MARGIN_TAX_MULTIPLE = 0.5;
  /** MARGIN_TAX_NO_HISTORY_MULTIPLE: the market-wide and scanner tiers. */
  public static final double MARGIN_TAX_NO_HISTORY_MULTIPLE = 1;
  /** MARGIN_TAX_WARN_MULTIPLE: below it a personal pick carries a warning. */
  public static final double MARGIN_TAX_WARN_MULTIPLE = 1;
  /** IMPLAUSIBLE_MARGIN_MULTIPLE: a margin over five times the buy price, on an hour nobody traded, is a dead spread. */
  public static final double IMPLAUSIBLE_MARGIN_MULTIPLE = 5;
  /** BUY_PRINT_MULTIPLE: a buy print under half its own hour's average sell is a print, not a price. */
  public static final double BUY_PRINT_MULTIPLE = 0.5;

  /** UNFLIPPABLE_ITEM_IDS: 13190, the Old school bond, which arrives untradeable. Never bought by any tier. */
  public static final Set<Integer> UNFLIPPABLE_ITEM_IDS = Set.of(13190);

  public static boolean isUnflippable(int itemId) {
    return UNFLIPPABLE_ITEM_IDS.contains(itemId);
  }

  /**
   * marginClearsTax: does the per-unit margin after tax cover {@code multiple} times the item's own tax? Unknown or
   * non-finite inputs never block, and neither does a tax-free item.
   */
  public static boolean marginClearsTax(double net, double tax, double multiple) {
    if (!JsValues.isFinite(net) || !JsValues.isFinite(tax)) return true;
    if (!(tax > 0)) return true;
    return net >= tax * multiple;
  }

  /**
   * implausibleSpread: a margin both extreme ({@code net / buyPrice > multiple}) and on an item that did not trade this
   * hour. {@code hourVolume} is NaN when the item is absent from the hour, which here counts as "did not trade" (the
   * one place absence is read that way, because the Wiki omits an item that changed no hands).
   */
  public static boolean implausibleSpread(double net, double buyPrice, double hourVolume, double multiple) {
    if (!JsValues.isFinite(net) || !JsValues.isFinite(buyPrice) || buyPrice <= 0 || net <= 0) return false;
    if (hourVolume > 0) return false; // someone is trading it
    return net / buyPrice > multiple;
  }

  /**
   * implausibleBuyPrint: a buy price under {@code multiple} times its own hour's average sell price, on an hour where
   * sells happened. False whenever there is nothing to judge against. {@code avgLowPrice}/{@code lowVolume} take NaN
   * when absent.
   */
  public static boolean implausibleBuyPrint(double low, double avgLowPrice, double lowVolume, double multiple) {
    if (!JsValues.isFinite(low) || low <= 0) return false;
    if (!(avgLowPrice > 0) || !(lowVolume > 0)) return false;
    return low < avgLowPrice * multiple;
  }

  /** The same, against the item's row of the latest hour (null when the item is absent from it). */
  public static boolean implausibleBuyPrint(double low, VolumeRow row, double multiple) {
    if (row == null) return implausibleBuyPrint(low, Double.NaN, Double.NaN, multiple);
    return implausibleBuyPrint(low, row.avgLow == HourBucket.NONE ? Double.NaN : row.avgLow, row.lowVolume, multiple);
  }

  /**
   * The market tier's liquidity floor ({@code liquidityFloorMet} in computeMarketSuggestion): the per-WINDOW floor when
   * {@code minVolumeInWindow} is a finite value above 0 ("Bigger positions"), otherwise the hourly floor -- the
   * level's minimum, raised by {@code minHourlyVolume} when that is set ({@code options.minHourlyVolume || 0}).
   *
   * @param liquidity the latest hour's thinner side (0 when the item is absent)
   * @param targetDurationMinutes the normalised pace, null for none (a window of one hour)
   * @param minVolumeInWindow NaN when unset
   * @param minHourlyVolume NaN when unset
   */
  public static boolean liquidityFloorMet(double liquidity, Double targetDurationMinutes, double minVolumeInWindow, double minHourlyVolume,
                                          LevelSettings level) {
    double windowHours = targetDurationMinutes != null ? targetDurationMinutes / 60 : 1;
    if (JsValues.isFinite(minVolumeInWindow) && minVolumeInWindow > 0) return liquidity * windowHours >= minVolumeInWindow;
    double raised = Double.isNaN(minHourlyVolume) || minHourlyVolume == 0 ? 0 : minHourlyVolume; // (x || 0)
    return liquidity >= Math.max(level.minHourlyVolume, raised);
  }
}
