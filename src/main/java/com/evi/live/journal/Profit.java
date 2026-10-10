package com.evi.live.journal;

import java.util.ArrayList;
import java.util.List;

/**
 * The sidebar's realised-profit line (server.mjs {@code profitSince}): every reviewed and automatic flip
 * whose last sale is at or after {@code since}, plus the two gaps it does NOT include, said out loud.
 *
 * <p>DELIBERATELY ACROSS EVERY ACCOUNT ON THE MACHINE -- the maintainer's 26 Sept 2026 decision ("leave it as it
 * is"), recorded in the project notes. The name says so, so no caller mistakes it for one account's figure. If it
 * is ever made per-account, that is the recommended fix there (the profit line, not the history tier).
 */
public final class Profit {
  /** Null means "since EVI started matching" (the player never pressed Reset). */
  public final Long since;
  public final long gp;
  public final int trades;
  public final int winners;
  public final int losers;
  public final int unmatchedSales;
  public final int openPositions;

  Profit(Long since, long gp, int trades, int winners, int losers, int unmatchedSales, int openPositions) {
    this.since = since;
    this.gp = gp;
    this.trades = trades;
    this.winners = winners;
    this.losers = losers;
    this.unmatchedSales = unmatchedSales;
    this.openPositions = openPositions;
  }

  public static Profit sinceAllAccounts(StoreState st, Long since) {
    List<Long[]> counted = new ArrayList<>(); // {profit, lastSell}
    for (ManualFlip f : st.flips) if (!f.isRemoved() && f.lastSell != null) counted.add(new Long[]{f.profit, f.lastSell});
    for (FifoMatcher.AutoFlip f : st.autoFlips) if (f.lastSell != null) counted.add(new Long[]{f.profit, f.lastSell});
    long gp = 0;
    int trades = 0, winners = 0, losers = 0;
    for (Long[] x : counted) {
      if (since != null && x[1] < since) continue;
      gp = Js.add(gp, x[0]);
      trades++;
      if (x[0] > 0) winners++;
      if (x[0] < 0) losers++;
    }
    return new Profit(since, gp, trades, winners, losers, st.dataHealth.unmatchedSales, st.dataHealth.openPositions);
  }
}
