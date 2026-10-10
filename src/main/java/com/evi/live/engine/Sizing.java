package com.evi.live.engine;

import com.evi.live.market.HourBucket;
import java.util.Map;

/**
 * How large one suggested order may be: the sizing half of bridge/suggestions.mjs (and the buy-limit closure in
 * server.mjs), ported for the self-contained plugin. Pure: no I/O, no clock (time is passed in), no threads.
 *
 * <p>THE ARITHMETIC IS THE JS ARITHMETIC. Every cap is computed in doubles, in the same order of operations, and only
 * the final whole number becomes a {@code long} (through {@link JsValues#exactLong}, which refuses rather than wraps).
 * A cash stack is a double here for the same reason: the JS divides it ({@code Math.floor(maxSpend / price)}), and a
 * whole number of gp under 2^53 is exact either way.
 *
 * <p>ABSENT IS NOT ZERO, in three places that have each cost GP once:
 * <ul>
 *   <li>cash: {@code null} is "unknown, no cap"; {@code 0} is "carrying nothing, afford nothing" (29 Sept);</li>
 *   <li>volume: an item ABSENT from the latest hour is no reading and constrains nothing; a LISTED zero is a
 *       measurement and sizes the order at one unit (the Eclipse Moon chestplate);</li>
 *   <li>the typical hour: a zero or missing typical reading falls back to the latest hour, but a measured zero in the
 *       latest hour beats any median ({@link #sizingLiquidityFor}, the 2 Oct precedence table).</li>
 * </ul>
 *
 * <p>THE ORDER OF THE CAPS differs by tier and is part of the behaviour: the quantity is the smallest cap whatever the
 * order, but WHICH cap is reported as having bound (and so which sentence the player reads) depends on it. Each tier's
 * order is ported as its own method ({@link #historyQuantity}, {@link #marketQuantity}, {@link #pushedQuantity}) and
 * proven against the real tier functions, not reconstructed.
 */
public final class Sizing {
  private Sizing() {}

  /** The Wiki /1h endpoint's window, in minutes (VOLUME_WINDOW_MINUTES). */
  public static final double VOLUME_WINDOW_MINUTES = 60;
  /** The volume share one order may take with no pace, or under six hours (DEFAULT_MAX_VOLUME_SHARE). */
  public static final double DEFAULT_MAX_VOLUME_SHARE = 0.10;
  /** VOLUME_SHARE_PER_HOUR, 1/24, as the same double. */
  public static final double VOLUME_SHARE_PER_HOUR = 1.0 / 24;
  /** The most of one hour's volume a single order may be (MAX_VOLUME_SHARE). */
  public static final double MAX_VOLUME_SHARE = 2;
  /** A market-wide pick's size when the item's buy limit is unknown (DEFAULT_MARKET_QUANTITY_CAP). */
  public static final long DEFAULT_MARKET_QUANTITY_CAP = 100;
  /** The GE's buy-limit window (LIMIT_WINDOW_MS). */
  public static final double LIMIT_WINDOW_MS = 4 * 3600 * 1000;
  public static final double FILL_FLOOR_MINUTES = 5;
  public static final double PASSIVE_FILL_FACTOR = 1.5;
  public static final double DURATION_TOLERANCE = 2;
  public static final double CAPTURE_CURVE_ANCHOR_UNITS = 100;
  public static final double CAPTURE_CURVE_AT_ANCHOR = 0.05;
  public static final double CAPTURE_MAX_SHARE = 0.25;

  // ------------------------------------------------------------------------------------------- inputs

  /** The sizing options the caps read: {@code options.maxVolumeShare}, {@code volumeWindowShare}, {@code captureCap}. */
  public static final class SizingOptions {
    /** NaN when the caller set none (the JS checks Number.isFinite, so NaN and absent are the same). */
    public final double maxVolumeShare;
    /** NaN when the caller set none; only a finite value above 0 switches to the per-window rule ("Bigger positions"). */
    public final double volumeWindowShare;
    /** The opt-in capture-curve cap (falsified 4 Oct, off by default). */
    public final boolean captureCap;

    public SizingOptions(double maxVolumeShare, double volumeWindowShare, boolean captureCap) {
      this.maxVolumeShare = maxVolumeShare;
      this.volumeWindowShare = volumeWindowShare;
      this.captureCap = captureCap;
    }

    /** No options: today's default ladder, no window share, no capture cap. */
    public static final SizingOptions DEFAULT = new SizingOptions(Double.NaN, Double.NaN, false);
  }

  /**
   * What only the caller knows about an item: a free-to-play world's members flag, the player's focus, and what is
   * left of the 4-hour buy limit. The three callbacks {@code gateCandidate} takes as options.
   */
  public interface ItemGate {
    boolean membersBlocked(int itemId);

    boolean focusBlocked(int itemId);

    /** {@code limitFor(itemId)?.remaining}: null when there is no limit (unknown limit, or no account); a non-finite value constrains nothing too. */
    Double limitRemaining(int itemId);

    /** Nothing blocked and no limit known. */
    ItemGate NONE = new ItemGate() {
      @Override public boolean membersBlocked(int itemId) {
        return false;
      }

      @Override public boolean focusBlocked(int itemId) {
        return false;
      }

      @Override public Double limitRemaining(int itemId) {
        return null;
      }
    };
  }

  /** A request's trade length as every tier normalises it: a finite value above 0, else null (no pace). */
  public static Double duration(double raw) {
    return JsValues.isFinite(raw) && raw > 0 ? raw : null;
  }

  /** A tier's cash cap: a finite value of at least 0, else null. {@code >= 0}: an empty coin pouch is a real answer. */
  public static Double maxSpend(double raw) {
    return JsValues.isFinite(raw) && raw >= 0 ? raw : null;
  }

  // ------------------------------------------------------------------------------------------- the caps

  /** volumeShareForDuration: 10% under six hours, 25% from six, then hours/12 from twelve hours, capped at 2x. */
  public static double volumeShareForDuration(double targetDurationMinutes) {
    if (!JsValues.isFinite(targetDurationMinutes) || targetDurationMinutes <= 0) return DEFAULT_MAX_VOLUME_SHARE;
    if (targetDurationMinutes >= 12 * 60) return Math.min(MAX_VOLUME_SHARE, (targetDurationMinutes / 60) * VOLUME_SHARE_PER_HOUR * 2);
    if (targetDurationMinutes >= 6 * 60) return 0.25;
    return DEFAULT_MAX_VOLUME_SHARE;
  }

  /**
   * orderSizeCap: the most units one order may be for an item's hourly liquidity and the player's window, floored at
   * one unit, or null when it constrains nothing (no reading, or a share switched off). A liquidity of 0 is a
   * measured zero and gives ONE unit, never "no constraint".
   */
  public static Long orderSizeCap(double liquidity, double targetDurationMinutes, SizingOptions options) {
    if (!JsValues.isFinite(liquidity) || liquidity < 0) return null;
    if (JsValues.isFinite(options.volumeWindowShare) && options.volumeWindowShare > 0) {
      double windowHours = JsValues.isFinite(targetDurationMinutes) && targetDurationMinutes > 0 ? targetDurationMinutes / 60 : 1;
      return JsValues.exactLong(Math.max(1, Math.floor(liquidity * windowHours * options.volumeWindowShare)));
    }
    double share = JsValues.isFinite(options.maxVolumeShare) ? options.maxVolumeShare : volumeShareForDuration(targetDurationMinutes);
    if (!(share > 0)) return null;
    return JsValues.exactLong(Math.max(1, Math.floor(liquidity * share)));
  }

  /**
   * captureFillable: the opt-in capture-curve cap, {@code (V/2)^(2/3)} held under a quarter of the flow, floored at 1;
   * null for no flow. {@link StrictMath#pow} (fdlibm, as V8's own pow), so a perfect cube cannot round differently.
   */
  public static Long captureFillable(double volumeInWindow) {
    if (!JsValues.isFinite(volumeInWindow) || volumeInWindow <= 0) return null;
    double fixedPoint = StrictMath.pow(volumeInWindow / 2, 2.0 / 3);
    return JsValues.exactLong(Math.max(1, Math.floor(Math.min(CAPTURE_MAX_SHARE * volumeInWindow, fixedPoint))));
  }

  /**
   * limitAllowance: what is left of the current 4-hour window, plus one whole limit for every further window that
   * STARTS before the player's duration runs out. Null for an unknown or non-positive limit. {@code used} and
   * {@code windowEndsAt} take NaN for "none" (the JS's undefined and null read the same here).
   */
  public static Double limitAllowance(double limit, double used, double windowEndsAt, double targetDurationMinutes, double now) {
    if (!JsValues.isFinite(limit) || limit <= 0) return null;
    double usedOr0 = Double.isNaN(used) || used == 0 ? 0 : used; // (used || 0)
    double inWindow = Math.max(0, limit - usedOr0);
    double duration = JsValues.isFinite(targetDurationMinutes) && targetDurationMinutes > 0 ? targetDurationMinutes * 60000 : 0;
    if (duration == 0) return inWindow;
    double firstReset = JsValues.isFinite(windowEndsAt) && windowEndsAt > now ? windowEndsAt - now : (used > 0 ? 0 : LIMIT_WINDOW_MS);
    double further = firstReset < duration ? Math.ceil((duration - firstReset) / LIMIT_WINDOW_MS) : 0;
    return inWindow + further * limit;
  }

  /** What server.mjs's {@code limitFor(itemId)} returns: the item's limit, what can fill NOW, and the allowance across windows. */
  public static final class Limit {
    public final double limit;
    /** One window's remainder and nothing more ({@code Math.max(0, limit - used)}): what gateCandidate clips to. */
    public final double remaining;
    public final Double acrossWindows;

    Limit(double limit, double remaining, Double acrossWindows) {
      this.limit = limit;
      this.remaining = remaining;
      this.acrossWindows = acrossWindows;
    }
  }

  /**
   * server.mjs's {@code limitFor} closure: null for an unknown or non-positive catalogue limit (no constraint);
   * otherwise the remainder of this window (a non-finite {@code used} counts as none) and the multi-window allowance.
   */
  public static Limit limitFor(double catalogLimit, double used, double windowEndsAt, double targetDurationMinutes, double now) {
    if (!JsValues.isFinite(catalogLimit) || catalogLimit <= 0) return null;
    Double across = limitAllowance(catalogLimit, used, windowEndsAt, targetDurationMinutes, now);
    return new Limit(catalogLimit, Math.max(0, catalogLimit - (JsValues.isFinite(used) ? used : 0)), across);
  }

  /**
   * sizingLiquidityFor: the hourly volume an order is sized on. The precedence table (2 Oct):
   * <pre>
   *   latest hour is a measured 0          -> 0        one unit; a median cannot say you could have exited this hour
   *   latest hour absent, typical present  -> typical
   *   latest hour trading, typical present -> typical  the point of the change
   *   latest hour trading, no typical      -> latest   the old behaviour
   *   both absent                          -> null     no signal, constrains nothing
   * </pre>
   * {@code typical} takes NaN for "none"; only a finite value above 0 is a typical reading.
   */
  public static Double sizingLiquidityFor(Long latestReading, double typical) {
    if (latestReading != null && latestReading == 0) return 0.0;
    if (JsValues.isFinite(typical) && typical > 0) return typical;
    return latestReading == null ? null : (double) latestReading;
  }

  /** The same, read from the price layer: the latest hour's row and the 168-hour typical volumes. */
  public static Double sizingLiquidityFor(HourBucket latestHour, Map<Integer, Long> typicalVolumes, int itemId) {
    Long typical = typicalVolumes == null ? null : typicalVolumes.get(itemId);
    return sizingLiquidityFor(VolumeRow.reading(VolumeRow.of(latestHour, itemId)), typical == null ? Double.NaN : typical);
  }

  // ------------------------------------------------------------------------------------------- fill time

  /** estimatedFillMinutes: quantity over the hour's rate; Infinity with no usable volume. */
  public static double estimatedFillMinutes(double quantity, double liquidity, double windowMinutes) {
    if (!(liquidity > 0)) return Double.POSITIVE_INFINITY;
    return quantity / (liquidity / windowMinutes);
  }

  /** correctedFillMinutes: floored at five minutes, times the passive-fill factor; a non-finite estimate passes through. */
  public static double correctedFillMinutes(double rawMinutes) {
    if (!JsValues.isFinite(rawMinutes)) return rawMinutes;
    return Math.max(FILL_FLOOR_MINUTES, rawMinutes) * PASSIVE_FILL_FACTOR;
  }

  /** The same for a value that may be absent: the JS returns a non-finite input UNCHANGED, so absent stays absent. */
  public static Double correctedFillMinutes(Double rawMinutes) {
    return rawMinutes == null ? null : correctedFillMinutes(rawMinutes.doubleValue());
  }

  /** estimateOfferFill's answer. */
  public static final class OfferFill {
    /** Rounded half-up; -1 when there is no estimate (no volume). */
    public final long estimatedFillMinutes;
    public final boolean likelyToFillInTime;

    OfferFill(long estimatedFillMinutes, boolean likelyToFillInTime) {
      this.estimatedFillMinutes = estimatedFillMinutes;
      this.likelyToFillInTime = likelyToFillInTime;
    }
  }

  /** estimateOfferFill: null without a remaining quantity, a pace or a row of the latest hour. */
  public static OfferFill estimateOfferFill(double remainingQty, VolumeRow row, double targetDurationMinutes) {
    if (!(remainingQty > 0) || !JsValues.isFinite(targetDurationMinutes) || targetDurationMinutes <= 0) return null;
    if (row == null) return null;
    double minutes = correctedFillMinutes(estimatedFillMinutes(remainingQty, VolumeRow.liquidity(row), VOLUME_WINDOW_MINUTES));
    return new OfferFill(JsValues.isFinite(minutes) ? com.evi.live.journal.Js.round(minutes) : -1, minutes <= targetDurationMinutes);
  }

  // ------------------------------------------------------------------------------------------- the buy-limit clip

  /** gateCandidate's answer: the quantity after the clip, and whether the buy limit is what cut it. */
  public static final class Clip {
    public final long quantity;
    public final boolean limited;

    Clip(long quantity, boolean limited) {
      this.quantity = quantity;
      this.limited = limited;
    }
  }

  /**
   * gateCandidate: never an unflippable item, a members item on a free world, or one outside the player's focus
   * (null); otherwise the quantity clipped to what is left of the 4-hour buy limit. A limit already used up drops
   * the candidate (null); no limit reading constrains nothing.
   */
  public static Clip gate(int itemId, long quantity, ItemGate gate) {
    if (Gates.isUnflippable(itemId)) return null;
    if (gate.membersBlocked(itemId)) return null;
    if (gate.focusBlocked(itemId)) return null;
    Double remaining = gate.limitRemaining(itemId);
    if (remaining == null || !JsValues.isFinite(remaining)) return new Clip(quantity, false);
    if (remaining < 1) return null; // the 4-hour limit for this item is already used up
    return remaining < quantity ? new Clip(JsValues.exactLong(remaining), true) : new Clip(quantity, false);
  }

  // ------------------------------------------------------------------------------------------- the chains

  /** A sized order: its quantity and which caps bound it (each flag is one sentence of the reasoning). */
  public static final class Sized {
    public final long quantity;
    public final boolean cashLimited;
    public final boolean stackLimited;
    public final boolean shareLimited;
    public final boolean durationLimited;
    public final boolean limitLimited;

    Sized(long quantity, boolean cash, boolean stack, boolean share, boolean duration, boolean limit) {
      this.quantity = quantity;
      this.cashLimited = cash;
      this.stackLimited = stack;
      this.shareLimited = share;
      this.durationLimited = duration;
      this.limitLimited = limit;
    }
  }

  /** Math.floor(budget / price): what a budget buys, as a double (the drop test is {@code < 1}). */
  private static double units(double budget, long price) {
    return Math.floor(budget / price);
  }

  /** The duration cap shared by the history and market tiers: null when even one unit would run well past the window. */
  private static final class DurationCap {
    final boolean dropped;
    final Long fillable;

    DurationCap(boolean dropped, Long fillable) {
      this.dropped = dropped;
      this.fillable = fillable;
    }
  }

  private static DurationCap durationCap(double liquidity, double target, boolean captureCap) {
    // Dropped only when a SINGLE unit would run past twice the target; sizing itself gets no tolerance.
    if (correctedFillMinutes(estimatedFillMinutes(1, liquidity, VOLUME_WINDOW_MINUTES)) > target * DURATION_TOLERANCE) return new DurationCap(true, null);
    double volumeInWindow = liquidity / VOLUME_WINDOW_MINUTES * target;
    Long fillable = captureCap ? captureFillable(volumeInWindow) : Long.valueOf(JsValues.exactLong(Math.max(1, Math.floor(volumeInWindow / PASSIVE_FILL_FACTOR))));
    return new DurationCap(false, fillable);
  }

  /**
   * The personal-history tier's order (computeSuggestion): the player's own median size, then cash, then the share of
   * the stack, then the volume share, then the trade length, then the buy-limit clip. Null when a cap drops the
   * candidate. {@code medianQty} is the item's median past quantity (an average of the middle pair for an even count,
   * so it can be x.5, rounded half-up here).
   *
   * @param maxSpend the cash cap, null when unknown ({@link #maxSpend})
   * @param maxStackShare {@code options.maxStackShare}, NaN when unset
   * @param typical the item's typical hourly volume, NaN when none
   * @param targetDurationMinutes the normalised pace, null for none ({@link #duration})
   */
  public static Sized historyQuantity(int itemId, double medianQty, long buyPrice, Double maxSpend, double maxStackShare, VolumeRow latest,
                                      double typical, Double targetDurationMinutes, SizingOptions options, ItemGate gate) {
    long quantity = Math.max(1, com.evi.live.journal.Js.round(medianQty));
    boolean cash = false, stack = false, share = false, duration = false;
    if (maxSpend != null) {
      double affordable = units(maxSpend, buyPrice);
      if (affordable < 1) return null; // can't afford even one unit at the current cash stack
      if (affordable < quantity) {
        quantity = JsValues.exactLong(affordable);
        cash = true;
      }
    }
    if (JsValues.isFinite(maxStackShare) && maxStackShare > 0 && maxSpend != null) {
      double withinShare = units(maxSpend * maxStackShare, buyPrice);
      if (withinShare < 1) return null; // one unit alone would commit more of the stack than allowed
      if (withinShare < quantity) {
        quantity = JsValues.exactLong(withinShare);
        stack = true;
      }
    }
    Double ownLiquidity = sizingLiquidityFor(VolumeRow.reading(latest), typical);
    if (ownLiquidity != null) {
      Long withinVolume = orderSizeCap(ownLiquidity, targetDurationMinutes == null ? Double.NaN : targetDurationMinutes, options);
      if (withinVolume != null && withinVolume < quantity) {
        quantity = withinVolume;
        share = true;
      }
    }
    if (targetDurationMinutes != null) {
      long liquidity = VolumeRow.liquidity(latest);
      if (liquidity > 0) { // no volume data at all: no signal to judge by, so no constraint
        DurationCap d = durationCap(liquidity, targetDurationMinutes, options.captureCap);
        if (d.dropped) return null;
        if (d.fillable != null && d.fillable < quantity) {
          quantity = d.fillable;
          duration = true;
        }
      }
    }
    Clip clip = gate(itemId, quantity, gate);
    if (clip == null) return null;
    return new Sized(clip.quantity, cash, stack, share, duration, clip.limited);
  }

  /**
   * The market-wide tier's order (computeMarketSuggestion), AFTER its liquidity floor ({@link Gates#liquidityFloorMet}):
   * the item's buy limit across the windows the pace spans (100 when the limit is unknown), then cash, then the trade
   * length, then the share of the stack, then the window or volume share (on the typical hour, else the latest), then
   * the buy-limit clip. Null when a cap drops it.
   *
   * @param catalogLimit the mapping's {@code limit}, NaN when absent
   */
  public static Sized marketQuantity(int itemId, double catalogLimit, long buyPrice, Double maxSpend, double maxStackShare, VolumeRow latest,
                                     double typical, Double targetDurationMinutes, SizingOptions options, ItemGate gate) {
    return marketQuantity(itemId, catalogLimit, buyPrice, maxSpend, maxStackShare, latest, null, typical, targetDurationMinutes, options, gate);
  }

  /**
   * The same, with the reading of computeMarketSuggestion's own {@code options.volumes} for the item ({@code optionsReading},
   * {@link VolumeRow#reading}: null when that option is absent or does not list the item). server.mjs never passes that
   * option -- it hands the hour over as an ARGUMENT -- so production sizes on the typical hour, else this hour
   * ({@code sizingLiquidityFor(options, id) ?? liquidity}); a caller that does pass it (the JS tests, the tools) gets
   * the precedence table on it, a measured zero there sizing the order at one unit.
   */
  public static Sized marketQuantity(int itemId, double catalogLimit, long buyPrice, Double maxSpend, double maxStackShare, VolumeRow latest,
                                     Long optionsReading, double typical, Double targetDurationMinutes, SizingOptions options, ItemGate gate) {
    double target = targetDurationMinutes == null ? Double.NaN : targetDurationMinutes;
    long liquidity = VolumeRow.liquidity(latest);
    // With no `volumes` option (the server's call) sizingLiquidityFor sees no latest reading: the typical hour when there
    // is one, else this hour's liquidity (`?? liquidity`). After the floor that is the same number either way, since the
    // floor has already refused a measured zero.
    Double sized = sizingLiquidityFor(optionsReading, typical);
    double sizingLiquidity = sized != null ? sized : liquidity;
    double volumeShare = JsValues.isFinite(options.maxVolumeShare) ? options.maxVolumeShare : volumeShareForDuration(target);
    boolean limitKnown = JsValues.isFinite(catalogLimit) && catalogLimit > 0;
    // limitAllowance with no usage and no running window: one limit, plus one per further window the pace spans.
    double start = limitKnown ? limitAllowance(catalogLimit, 0, Double.NaN, target, 0) : DEFAULT_MARKET_QUANTITY_CAP;
    long quantity = JsValues.exactLong(Math.max(1, start));
    boolean cash = false, stack = false, share = false, duration = false;
    if (maxSpend != null) {
      double affordable = units(maxSpend, buyPrice);
      if (affordable < 1) return null;
      if (affordable < quantity) {
        quantity = JsValues.exactLong(affordable);
        cash = true;
      }
    }
    if (targetDurationMinutes != null) {
      DurationCap d = durationCap(liquidity, targetDurationMinutes, options.captureCap);
      if (d.dropped) return null;
      if (d.fillable != null && d.fillable < quantity) {
        quantity = d.fillable;
        duration = true;
      }
    }
    if (JsValues.isFinite(maxStackShare) && maxStackShare > 0 && maxSpend != null) {
      double byStack = units(maxSpend * maxStackShare, buyPrice);
      if (byStack < 1) return null;
      if (byStack < quantity) {
        quantity = JsValues.exactLong(byStack);
        stack = true;
      }
    }
    if (JsValues.isFinite(options.volumeWindowShare) && options.volumeWindowShare > 0) {
      double windowHours = targetDurationMinutes != null ? targetDurationMinutes / 60 : 1;
      double byWindow = Math.max(1, Math.floor(sizingLiquidity * windowHours * options.volumeWindowShare));
      if (byWindow < quantity) {
        quantity = JsValues.exactLong(byWindow);
        share = true;
      }
    } else if (volumeShare > 0) {
      double byVolume = Math.max(1, Math.floor(sizingLiquidity * volumeShare));
      if (byVolume < quantity) {
        quantity = JsValues.exactLong(byVolume);
        share = true;
      }
    }
    Clip clip = gate(itemId, quantity, gate);
    if (clip == null) return null;
    return new Sized(clip.quantity, cash, stack, share, duration, clip.limited);
  }

  /**
   * The scanner-pushed tier's order (computePushedSuggestion): the item's buy limit across the pace's windows when the
   * catalogue knows it (else the scanner's own quantity, rounded half-up), then the volume share, then the buy-limit
   * clip, then cash -- the clip BEFORE cash, which is this tier's order. Null when a cap drops it.
   *
   * @param limitOf the catalogue limit, NaN when unknown
   * @param scannerQty the quantity the scanner pushed, NaN when absent
   */
  public static Sized pushedQuantity(int itemId, double limitOf, double scannerQty, long buyPrice, Double maxSpend, VolumeRow latest,
                                     double typical, Double targetDurationMinutes, SizingOptions options, ItemGate gate) {
    double target = targetDurationMinutes == null ? Double.NaN : targetDurationMinutes;
    long scanner = Math.max(1, com.evi.live.journal.Js.round(JsValues.isFinite(scannerQty) && scannerQty > 0 ? scannerQty : 1));
    long quantity = JsValues.isFinite(limitOf) && limitOf > 0
      ? JsValues.exactLong(Math.max(1, limitAllowance(limitOf, 0, Double.NaN, target, 0)))
      : scanner;
    boolean share = false, cash = false;
    Double liquidity = sizingLiquidityFor(VolumeRow.reading(latest), typical);
    if (liquidity != null) {
      Long withinVolume = orderSizeCap(liquidity, target, options);
      if (withinVolume != null && withinVolume < quantity) {
        quantity = withinVolume;
        share = true;
      }
    }
    Clip clip = gate(itemId, quantity, gate);
    if (clip == null) return null;
    quantity = clip.quantity;
    if (maxSpend != null) {
      double affordable = units(maxSpend, buyPrice);
      if (affordable < 1) return null;
      if (affordable < quantity) {
        quantity = JsValues.exactLong(affordable);
        cash = true;
      }
    }
    return new Sized(quantity, cash, false, share, false, clip.limited);
  }
}
