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
  AUTO, T100K, T200K, T500K, T1M, T2M, NONE;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case T100K: return "100k+";
      case T200K: return "200k+";
      case T500K: return "500k+";
      case T1M: return "1m+";
      case T2M: return "2m+";
      // Appended after AUTO gained a small floor of its own: this is the way to say "really none".
      case NONE: return "No minimum at all";
      default: return "Auto";
    }
  }

  /**
   * The gp floor to send as ?minProfit=, or 0 for AUTO (meaning: leave the query parameter off).
   *
   * AUTO is not "no floor": the bridge applies a small one of its own (500 gp) so that a player who
   * has expressed no preference is not offered a trade worth 81 gp after tax, which is what
   * happened on 26 Sept. NONE sends 1, the smallest positive floor there is, which the bridge reads
   * as a deliberate choice and leaves alone -- the way to keep thin, high-volume flipping available
   * to anyone whose cash stack depends on it.
   */
  int gp() {
    switch (this) {
      case T100K: return 100000;
      case T200K: return 200000;
      case T500K: return 500000;
      case T1M: return 1000000;
      case T2M: return 2000000;
      case NONE: return 1;
      default: return 0;
    }
  }
}
