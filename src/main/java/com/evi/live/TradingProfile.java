package com.evi.live;

/**
 * Which kind of market-wide suggestions EVI should look for, as a plugin config dropdown. Sent to
 * the bridge as ?profile=starter on GET /api/suggestion; STANDARD is left off the query string
 * entirely, so it behaves exactly as before this existed.
 *
 * STARTER is training wheels, and it was chosen from measured evidence rather than intuition.
 * Replaying 90 archived days through EVI's own ranking (tools/backtest.mjs), restricting market-wide
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
// STARTER is the default: someone installing this for the first time is more likely to be learning
// than to be running a large stack, and the measured downside of starting here is smaller profit per
// trade rather than risk. Anyone who isn't a beginner switches to Standard in one click.
public enum TradingProfile {
  STANDARD, STARTER;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    return this == STARTER ? "Starter" : "Standard";
  }

  /** Value to send as ?profile=, or null for STANDARD (meaning: leave the parameter off entirely). */
  String param() {
    return this == STARTER ? "starter" : null;
  }
}
