package com.evi.live;

/**
 * The three suggestion-tuning risk tiers offered in the plugin's config, mirrored on the bridge
 * side in bridge/suggestions.mjs's RISK_TIERS. Sent to the bridge as ?risk=low|medium|high on
 * GET /api/suggestion; MEDIUM (the default) is left off the query string entirely since it's
 * also the bridge's own default when no risk parameter is given.
 */
public enum RiskLevel {
  LOW, MEDIUM, HIGH;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case LOW: return "Low";
      case HIGH: return "High";
      default: return "Medium";
    }
  }

  /** The exact ?risk= value the bridge expects. */
  String param() { return name().toLowerCase(); }
}
