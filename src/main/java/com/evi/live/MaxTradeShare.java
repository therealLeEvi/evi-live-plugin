package com.evi.live;

/**
 * How much of your cash stack a single market-wide suggestion may commit, as a plugin config
 * dropdown. Sent to the bridge as ?stackShare=&lt;percent&gt; on GET /api/suggestion; OFF is left off the
 * query string entirely, so choosing it restores exactly the behaviour from before this existed.
 *
 * This is the one setting here chosen from measured evidence rather than judgement. Replaying 90
 * days of archived prices through EVI's own market-wide ranking (tools/backtest.mjs, 352 simulated
 * decisions) found that tier would have lost ~93m gp -- and that the losses were not bad item
 * picks but bad sizing: every large loss was a single expensive item bought with nearly the whole
 * stack and still unsold a day later (Hallowfell x8 for 48.5m, Robin Hood hat x4 for 45.5m). Capping
 * one trade at a quarter of the stack turned that -93m into +67m in the same simulation, while still
 * producing exactly as many suggestions -- it only changes how big they are.
 *
 * It is expressed as a share of YOUR OWN cash rather than a flat gp figure, so it means the same
 * thing to a 5m account as to a 500m one, and it never withholds a suggestion: a capped candidate is
 * still suggested, just smaller, with the reason stated in its reasoning text.
 */
public enum MaxTradeShare {
  OFF, TENTH, QUARTER, THIRD, HALF;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case TENTH: return "10% of cash";
      case QUARTER: return "25% of cash";
      case THIRD: return "33% of cash";
      case HALF: return "50% of cash";
      default: return "No limit";
    }
  }

  /** Percent to send as ?stackShare=, or 0 for OFF (meaning: leave the parameter off entirely). */
  int percent() {
    switch (this) {
      case TENTH: return 10;
      case QUARTER: return 25;
      case THIRD: return 33;
      case HALF: return 50;
      default: return 0;
    }
  }
}
