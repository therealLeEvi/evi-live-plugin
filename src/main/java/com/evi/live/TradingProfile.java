package com.evi.live;

/**
 * RETIRED 6 Oct 2026. This was the "Trading profile" dropdown; it is now a hidden config item that
 * nothing reads, and the plugin never sends ?profile= at all. The enum stays only so a stored value
 * (most installs hold "STARTER", the old default) is still a valid value for RuneLite to load --
 * deleting the type under the same keyName is the config-loader risk the project notes warn about.
 *
 * Why it went: on market-wide picks, untaxed-only meant sub-50 gp bulk and nothing else. The
 * measurement below is kept as the record of why it was once the default.
 *
 * STARTER is training wheels, and it was chosen from measured evidence rather than intuition.
 * Replaying 90 archived days through EVI's own ranking, restricting market-wide
 * picks to items the Grand Exchange charges no tax on -- anything under 50 gp, plus the exemption
 * list -- changed the results dramatically for a small stack:
 *
 *   2m stack, 25% cash cap:  standard  220 sales, 85% won, 29% of capital left stuck, worst -132,164
 *                            starter   325 sales, 98% won,  5% of capital left stuck, worst  -14,920
 *
 * The same pattern held at 10m and 50m. Individual trades earn less (about 40k rather than 52k on a
 * 2m stack), but far more of them complete, almost none end up stuck, and the worst case is an order
 * of magnitude smaller -- which is what matters when you are learning and cannot afford to have your
 * whole stack frozen in something that will not sell.
 *
 * Two honest caveats. The simulation fills orders optimistically and ignores queue position, which
 * flatters cheap high-volume items most, so the real advantage is smaller than those numbers. And
 * tax-free items are cheap items, so profit per trade is small: this is a way to learn the mechanics
 * and grow steadily, not a way to make a fortune quickly.
 */
// (Historical) STARTER was the default: someone installing this for the first time is more likely to be learning
// than to be running a large stack, and the measured downside of starting here is smaller profit per
// trade rather than risk. Anyone who isn't a beginner switches to Standard in one click.
public enum TradingProfile {
  STANDARD, STARTER;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    return this == STARTER ? "Starter" : "Standard";
  }
}
