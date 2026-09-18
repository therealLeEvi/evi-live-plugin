package com.evi.live;

/**
 * How large a predicted profit has to be before EVI will suggest it, offered as a plugin config
 * dropdown of plain preset tiers instead of a free-form gp number -- easier to reason about for
 * someone with no trading experience, and it keeps a thin-margin, high-volume flip available to
 * anyone who deliberately wants it (AUTO) without a blanket filter silently ruling it out for
 * everyone else. Sent to the bridge as ?minProfit=<gp> on GET /api/suggestion; AUTO (the default)
 * is left off the query string entirely, exactly like the old free-form setting's 0 ("disables
 * the filter"), so an untouched config behaves exactly as before this setting existed.
 */
public enum MinProfitTier {
  AUTO, T100K, T200K, T500K, T1M, T2M;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case T100K: return "100k+";
      case T200K: return "200k+";
      case T500K: return "500k+";
      case T1M: return "1m+";
      case T2M: return "2m+";
      default: return "Auto";
    }
  }

  /** The gp floor to send as ?minProfit=, or 0 for AUTO (meaning: leave the query parameter off entirely). */
  int gp() {
    switch (this) {
      case T100K: return 100000;
      case T200K: return 200000;
      case T500K: return 500000;
      case T1M: return 1000000;
      case T2M: return 2000000;
      default: return 0;
    }
  }
}
