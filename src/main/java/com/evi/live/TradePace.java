package com.evi.live;

/**
 * How long the player is willing to wait for one trade, in the scanner's own words.
 *
 * Replaces {@link TradeDuration}, whose nine options were labelled in minutes and promised
 * something the market does not deliver. Measured over 60 days of archived prices by
 * replaying the real ranking and holding each pick for exactly as long as
 * the setting claimed: at ~5 minutes, ~30 minutes and ~1 hour, **not one round trip completed
 * inside its own window** at any cash stack tested (50k, 10m and 380m), leaving 100% of the
 * capital still tied up when the window ended. Those settings were not describing fast trades;
 * they were describing trades that had not finished yet.
 *
 * So the options are now the four the scanner already uses, each set to a length that was measured
 * to actually complete:
 *
 * <pre>
 *   at a 10m cash stack        sales completed   realised per sale   capital left stuck
 *   ~1 hour (dropped)                        0                   -                 100%
 *   Fast, 2 hours                           13              70,298                  83%
 *   Medium, 6 hours                         38             177,287                  61%
 *   Overnight, 12 hours                     39             285,156                  55%
 *   Slow, 48 hours                          74             369,945                  27%
 * </pre>
 *
 * Longer genuinely is better here, which is the only reason Slow exists: the standard set for it
 * was that a longer hold has to be verified to help, or it is only added risk. Beyond two days the
 * gain flattens -- 72 hours earned slightly less per sale and only tied up less capital -- so two
 * days is where the improvement stops being unambiguous and the list stops.
 *
 * A new keyName is used deliberately rather than reusing "tradeDuration": RuneLite stores config by
 * keyName, and a stored value naming an option that no longer exists is not something to gamble on.
 * The old setting is kept and hidden, so nothing stored is lost, and every player picks once.
 */
public enum TradePace {
  NONE, FAST, MEDIUM, OVERNIGHT, SLOW;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case FAST: return "Fast (~2 hours)";
      case MEDIUM: return "Medium (~6 hours)";
      case OVERNIGHT: return "Overnight (~12 hours)";
      case SLOW: return "Slow (~2 days)";
      default: return "No preference";
    }
  }

  /** Minutes to send as ?duration=, or 0 for NONE (meaning: leave the query parameter off entirely). */
  int minutes() {
    switch (this) {
      case FAST: return 120;
      case MEDIUM: return 360;
      case OVERNIGHT: return 720;
      case SLOW: return 2880;
      default: return 0;
    }
  }
}
