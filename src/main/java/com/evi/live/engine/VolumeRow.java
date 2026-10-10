package com.evi.live.engine;

import com.evi.live.market.HourBucket;
import com.evi.live.market.MarketAggregates;

/**
 * One item's row of the latest hour ({@code volumes[String(itemId)]} in suggestions.mjs: the Wiki /1h data entry),
 * read out of the price layer's {@link HourBucket}. A missing average stays {@link HourBucket#NONE}; a missing
 * volume is 0, exactly as the JS reads {@code v.highPriceVolume || 0}. An item ABSENT from the hour is a null row,
 * never a row of zeros: absent means "no reading" and a listed zero is a measurement, and the engine keeps the two
 * apart everywhere (see {@link Sizing#sizingLiquidityFor}).
 */
public final class VolumeRow {
  public final long avgHigh;
  public final long highVolume;
  public final long avgLow;
  public final long lowVolume;

  public VolumeRow(long avgHigh, long highVolume, long avgLow, long lowVolume) {
    this.avgHigh = avgHigh;
    this.highVolume = highVolume;
    this.avgLow = avgLow;
    this.lowVolume = lowVolume;
  }

  /** The item's row of the hour, or null when the hour is missing or the item is absent from it. */
  public static VolumeRow of(HourBucket hour, int itemId) {
    if (hour == null) return null;
    int i = hour.indexOf(itemId);
    if (i < 0) return null;
    return new VolumeRow(hour.avgHigh(i), hour.highVolume(i), hour.avgLow(i), hour.lowVolume(i));
  }

  /** {@code volumeReadingFor}: the thinner side, or null with no row. The same answer as {@link MarketAggregates#volumeReadingFor}. */
  public static Long reading(VolumeRow row) {
    return row == null ? null : Math.min(row.highVolume, row.lowVolume);
  }

  /** {@code Math.min(v.highPriceVolume || 0, v.lowPriceVolume || 0)}, or 0 with no row: the liquidity a duration check reads. */
  public static long liquidity(VolumeRow row) {
    return row == null ? 0 : Math.min(row.highVolume, row.lowVolume);
  }
}
