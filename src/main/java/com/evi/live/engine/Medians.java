package com.evi.live.engine;

import com.evi.live.journal.Js;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * THE ENGINE HAS TWO DIFFERENT MEDIANS, and swapping them changes answers (bridge-inventory risk 7):
 * <ul>
 *   <li>{@link #average}: suggestions.mjs's {@code median()}, used by {@code personalHistory} for a player's typical
 *       quantity and hold. An even count AVERAGES the middle pair, so a typical size can be x.5 and is then rounded
 *       half-up.</li>
 *   <li>{@link #upper}: the robust price's, the typical volume's and the thin-market's
 *       {@code s[Math.floor(s.length / 2)]}. An even count takes the UPPER middle, never an average, so the answer is
 *       always one of the readings (the market layer's {@code LongList.upperMedian} is the same rule).</li>
 * </ul>
 * Both sort with the JS comparator {@code (a, b) => a - b} (a stable sort that treats -0 and 0 as equal).
 */
public final class Medians {
  private Medians() {}

  /** median(): null for no values; the middle value, or the mean of the middle two for an even count. */
  public static Double average(List<Double> values) {
    if (values.isEmpty()) return null;
    List<Double> s = new ArrayList<>(values);
    s.sort((a, b) -> Js.compare(a, b));
    int mid = s.size() / 2;
    return s.size() % 2 == 1 ? s.get(mid) : (s.get(mid - 1) + s.get(mid)) / 2;
  }

  /** {@code s[Math.floor(s.length / 2)]} of the sorted values; null for none. */
  public static Long upper(long[] values) {
    if (values.length == 0) return null;
    long[] s = values.clone();
    Arrays.sort(s);
    return s[s.length / 2];
  }
}
