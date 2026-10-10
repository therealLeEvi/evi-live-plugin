package com.evi.live.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The player's trading focus by the item's own buy limit (bridge/suggestions.mjs FOCUSES, resolveFocus, focusAllows):
 * "bulk" is an item bought by the thousand, "gear" one with a known smaller limit, "any" everything. Game data, not a
 * price or profit threshold. Pure.
 */
public final class Focus {
  private Focus() {}

  /** BULK_MIN_LIMIT. */
  public static final double BULK_MIN_LIMIT = 1000;
  /** FOCUSES, in the JS order. */
  public static final List<String> FOCUSES = Collections.unmodifiableList(Arrays.asList("any", "bulk", "gear"));

  /** The plugin's own setting when it names a focus, otherwise the scanner's stored switch, otherwise "any". */
  public static String resolveFocus(String requested, String stored) {
    if (requested != null && FOCUSES.contains(requested)) return requested;
    return stored != null && FOCUSES.contains(stored) ? stored : "any";
  }

  /**
   * Whether an item with this buy limit fits the focus. An item whose limit is unknown ({@code limit} null or not
   * finite) cannot be placed in either group, so only "any" includes it.
   */
  public static boolean focusAllows(String focus, Double limit) {
    boolean known = limit != null && JsValues.isFinite(limit);
    if ("bulk".equals(focus)) return known && limit >= BULK_MIN_LIMIT;
    if ("gear".equals(focus)) return known && limit > 0 && limit < BULK_MIN_LIMIT;
    return true;
  }
}
