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
//
// The hour-plus options (2h-24h) were added for players who hold trades for hours rather than
// minutes. New constants are appended, so a value RuneLite already stored by name (e.g. "SIXTY")
// still loads unchanged.
public enum TradeDuration {
  NONE, FIVE, TEN, THIRTY, SIXTY, TWO_HOURS, FOUR_HOURS, EIGHT_HOURS, TWELVE_HOURS, DAY;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case FIVE: return "~5 minutes";
      case TEN: return "~10 minutes";
      case THIRTY: return "~30 minutes";
      case SIXTY: return "~1 hour";
      case TWO_HOURS: return "~2 hours";
      case FOUR_HOURS: return "~4 hours";
      case EIGHT_HOURS: return "~8 hours";
      case TWELVE_HOURS: return "~12 hours";
      case DAY: return "~24 hours";
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
      case TWO_HOURS: return 120;
      case FOUR_HOURS: return 240;
      case EIGHT_HOURS: return 480;
      case TWELVE_HOURS: return 720;
      case DAY: return 1440;
      default: return 0;
    }
  }
}
