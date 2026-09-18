package com.evi.live;

/**
 * How far ahead, if at all, the bridge should run a price-direction forecast (the same
 * momentum/volume model the scanner's own Predict button uses, see bridge/suggestions.mjs's
 * forecastForItem) before returning a "buy" suggestion. Sent to the bridge as ?forecast=1h|6h|
 * overnight on GET /api/suggestion; OFF (the default) is left off the query string entirely, so an
 * untouched config sends no forecast request and suggestions look exactly as before this existed --
 * no extra Wiki API call, no change to reasoning text. Deliberately separate from
 * EviLiveConfig.tradeDuration(), which only judges whether a trade can realistically fill within a
 * short window (minutes) from recent volume; this instead asks "which way is the price likely to
 * move over roughly this long," matching the scanner's own three horizons (1h/6h/overnight).
 */
public enum ForecastHorizon {
  OFF, ONE_HOUR, SIX_HOUR, OVERNIGHT;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case ONE_HOUR: return "~1 hour";
      case SIX_HOUR: return "~6 hours";
      case OVERNIGHT: return "Overnight";
      default: return "Off";
    }
  }

  /** The exact ?forecast= value the bridge expects, or null for OFF (meaning: omit the parameter). */
  String param() {
    switch (this) {
      case ONE_HOUR: return "1h";
      case SIX_HOUR: return "6h";
      case OVERNIGHT: return "overnight";
      default: return null;
    }
  }
}
