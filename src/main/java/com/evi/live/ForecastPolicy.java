package com.evi.live;

/**
 * What the bridge should do when EviLiveConfig.forecastHorizon() is enabled and the resulting
 * forecast for a "buy" suggestion is meaningfully unfavorable. Sent to the bridge as
 * ?onForecast=warn|skip on GET /api/suggestion, only when forecastHorizon() isn't OFF (there's
 * nothing to have a policy about otherwise). WARN is the default: the suggestion stays, with the
 * forecast folded into its reasoning text so you can weigh it yourself, same spirit as every other
 * EVI hint -- informational, never a decision made for you. SKIP instead drops that candidate and
 * asks the bridge to rank the next-best one, retrying a bounded number of times (see
 * MAX_FORECAST_ATTEMPTS in bridge/server.mjs) before falling back exactly as if forecasting were off.
 */
public enum ForecastPolicy {
  WARN, SKIP;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    return this == SKIP ? "Skip it, rank the next-best instead" : "Warn me, keep the suggestion";
  }

  /** The exact ?onForecast= value the bridge expects. */
  String param() { return name().toLowerCase(); }
}
