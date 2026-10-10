package com.evi.live;

/**
 * The "Exit-risk check" setting (keyName exitRiskCheck since 4.0.0): whether the engine runs its fill-outlook forecast before
 * a "buy" suggestion. Sent as ?forecast=6h; OFF (the default) is left off the query entirely, so an untouched config asks for
 * no forecast and suggestions look exactly as before -- no extra archive read, no change to reasoning text.
 *
 * <p>~6 hours is the only horizon: the plugin's price archive is hourly and the fill-outlook table was measured for that
 * window. The old ~1 hour and Overnight options were removed in 4.0.0 (they gave no forecast in the self-contained engine)
 * together with the old keyName, so no stored value names a constant that no longer exists.
 *
 * <p>Public, like every enum a config item returns: RuneLite's config proxy cannot reach a package-private one. Stored by name,
 * so the names are the stored values and must not change.
 */
public enum ForecastHorizon {
  OFF, SIX_HOUR;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    return this == SIX_HOUR ? "~6 hours" : "Off";
  }

  /** The exact ?forecast= value the engine expects, or null for OFF (meaning: omit the parameter). */
  String param() {
    return this == SIX_HOUR ? "6h" : null;
  }
}
