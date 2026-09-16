package com.evi.live;

/**
 * The player's own preferred trade length, offered as a plugin config dropdown. Sent to the
 * bridge as ?duration=<minutes> on GET /api/suggestion; NONE (the default) is left off the query
 * string entirely, exactly like RiskLevel.MEDIUM, so an untouched config behaves exactly as
 * before this setting existed. The bridge checks it against the OSRS Wiki price API's own recent
 * trade-volume data (bridge/suggestions.mjs's estimatedFillMinutes) as a rough feasibility
 * estimate for whether a candidate trade could realistically complete within that window --
 * not a guarantee, since that data is a coarse per-item volume figure, not a live order book.
 */
public enum TradeDuration {
  NONE, FIVE, TEN, THIRTY, SIXTY;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case FIVE: return "~5 minutes";
      case TEN: return "~10 minutes";
      case THIRTY: return "~30 minutes";
      case SIXTY: return "~1 hour";
      default: return "No preference (default)";
    }
  }

  /** Minutes to send as ?duration=, or 0 for NONE (meaning: leave the query parameter off entirely). */
  int minutes() {
    switch (this) {
      case FIVE: return 5;
      case TEN: return 10;
      case THIRTY: return 30;
      case SIXTY: return 60;
      default: return 0;
    }
  }
}
