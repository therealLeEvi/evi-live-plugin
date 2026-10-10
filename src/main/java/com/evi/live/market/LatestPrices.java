package com.evi.live.market;

import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The Wiki's {@code /latest}: per item, the last price someone paid in an instant buy ({@code high}) and the last
 * price someone accepted in an instant sell ({@code low}), with the unix second each printed. Any of the four may
 * be {@link HourBucket#NONE} (the Wiki sends null for a side that has never traded). Immutable.
 */
public final class LatestPrices {
  public static final class Quote {
    public final long high;
    public final long highTime;
    public final long low;
    public final long lowTime;

    Quote(long high, long highTime, long low, long lowTime) {
      this.high = high;
      this.highTime = highTime;
      this.low = low;
      this.lowTime = lowTime;
    }
  }

  private final NavigableMap<Integer, Quote> quotes;

  LatestPrices(TreeMap<Integer, Quote> quotes) {
    this.quotes = Collections.unmodifiableNavigableMap(quotes);
  }

  /** The quote for an item, or null when the Wiki has none. */
  public Quote get(int itemId) {
    return quotes.get(itemId);
  }

  public int size() {
    return quotes.size();
  }

  public NavigableMap<Integer, Quote> all() {
    return quotes;
  }
}
