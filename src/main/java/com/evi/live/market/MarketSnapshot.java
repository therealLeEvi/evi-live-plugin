package com.evi.live.market;

import java.util.Collections;
import java.util.Map;

/**
 * Everything the price-data layer knows at one moment, published as ONE immutable object (a volatile reference in
 * {@link PriceDataService}), so a reader on any thread sees a consistent set and never touches the disk or the
 * network. The plugin's own engine reads it (com.evi.live.inprocess).
 */
public final class MarketSnapshot {
  public static final MarketSnapshot EMPTY = new MarketSnapshot(null, null, 0, null, null, 0, 0, 0, 0, Long.MIN_VALUE, true, 0, 0);

  /** The item list, or null before the first mapping arrived (from the Wiki or the disk cache). */
  public final ItemCatalog catalog;
  /** The last {@code /latest}, or null before one arrived (it is only fetched while logged in). */
  public final LatestPrices latest;
  public final long latestFetchedAtMs;
  /** The newest stored hour, or null. Read it through {@link #latestHour(long)}, which knows when it is too old. */
  private final HourBucket newestHour;
  /** Robust prices and typical volumes, from the last {@link MarketAggregates#build}; empty before the first. */
  public final Map<Integer, MarketAggregates.RobustPrice> robust;
  public final Map<Integer, Long> typical;
  public final int robustHours;
  public final int typicalHours;
  public final long readingsBuiltAtMs;
  /** Hours stored (or answered empty, which needs no fetch) inside the backfill window, and the window's size. */
  public final int hoursStored;
  /**
   * Hours of the window the Wiki would not give this session ({@value PriceDataService#HOUR_TRIES} failures in a row):
   * given up until the next session. Absent, but NOT still being fetched -- see {@link #catchingUp}.
   */
  public final int hoursUnavailable;
  public final int windowHours;
  /**
   * True while hours of the window are still to be FETCHED this session ({@code hoursStored + hoursUnavailable <
   * windowHours}). An hour given up is not "still coming", so it cannot hold this true for the two weeks it takes to
   * leave the window; a reader that needs every hour compares {@code hoursStored} with {@code windowHours} itself.
   */
  public final boolean catchingUp;
  public final long newestStoredTs;
  /**
   * Moves each time the layer learns of an hour it had not (loaded, stored, answered empty, or written by the other client);
   * never moves back. The engine reads its copy of the last 26 hours again when it changes (a backfilled
   * OLDER hour does not change {@link #newestStoredTs}).
   */
  public final long archiveRevision;
  /**
   * {@link #hoursStored} as it stood when the readings ({@link #robust}, {@link #typical}) were last built; 0 before the first
   * build. The readings are rebuilt on a cadence, so an hour stored since then is not in them yet: a reader that needs the
   * readings to rest on a full window (the in-process engine's first-run gate on buy suggestions) compares THIS, not
   * {@link #hoursStored}.
   */
  public final int readingsHoursStored;

  MarketSnapshot(ItemCatalog catalog, LatestPrices latest, long latestFetchedAtMs, HourBucket newestHour, MarketAggregates.Readings readings,
                 long readingsBuiltAtMs, int hoursStored, int hoursUnavailable, int windowHours, long newestStoredTs, boolean catchingUp,
                 long archiveRevision, int readingsHoursStored) {
    this.catalog = catalog;
    this.latest = latest;
    this.latestFetchedAtMs = latestFetchedAtMs;
    this.newestHour = newestHour;
    this.robust = readings == null ? Collections.emptyMap() : readings.robust;
    this.typical = readings == null ? Collections.emptyMap() : readings.typical;
    this.robustHours = readings == null ? 0 : readings.robustHours;
    this.typicalHours = readings == null ? 0 : readings.typicalHours;
    this.readingsBuiltAtMs = readingsBuiltAtMs;
    this.hoursStored = hoursStored;
    this.hoursUnavailable = hoursUnavailable;
    this.windowHours = windowHours;
    this.newestStoredTs = newestStoredTs;
    this.catchingUp = catchingUp;
    this.archiveRevision = archiveRevision;
    this.readingsHoursStored = readingsHoursStored;
  }

  /**
   * The latest hour's volumes for SIZING ({@link MarketAggregates#volumeReadingFor} reads it): the newest closed
   * hour, or the one before it while the newest is still settling or being fetched. Anything older is no longer
   * "the latest hour" and reads as null -- no reading -- rather than an old hour passed off as the present.
   */
  public HourBucket latestHour(long nowMs) {
    HourBucket h = newestHour;
    if (h == null) return null;
    return h.ts >= PriceDataService.newestClosedHour(nowMs) - MarketAggregates.HOUR ? h : null;
  }
}
