package com.evi.live.journal;

import java.util.List;

/**
 * What ONE account paid, on average, for the units of an item it holds (suggestions.mjs
 * {@code heldCostBasis}): finished purchases still held (FIFO open positions, each at its own unit cost)
 * plus buy offers still RUNNING that have filled some units. Never double counted, because FIFO only
 * admits finished offers. Null when nothing held has a known price: never a guessed cost.
 *
 * <p>THE PORT WARNING, honoured (the self-contained port's notes): the JS function scopes with
 * {@code !account || x.account === account}, so called WITHOUT an account it averages every account's
 * stock together, and it is safe today only because server.mjs pre-filters. This port scopes itself: the
 * account is REQUIRED, a null account returns null, and the vectors record the JS answer for the
 * no-account call beside the Java one so the difference is visible rather than hidden.
 */
public final class CostBasis {
  public final double unitCost;
  public final long quantity;

  CostBasis(double unitCost, long quantity) {
    this.unitCost = unitCost;
    this.quantity = quantity;
  }

  public static CostBasis held(List<FifoMatcher.OpenPosition> openPositions, List<Offer> activeOffers, int itemId, String account) {
    // server.mjs's ownedBy is !!account && ...: an EMPTY account is no account too (it owns nothing).
    if (account == null || account.isEmpty()) return null;
    long units = 0;
    double gp = 0;
    // (openPositions || []) and (activeOffers || []): a missing list holds nothing, as in the JS.
    if (openPositions != null) for (FifoMatcher.OpenPosition p : openPositions) {
      if (p == null || p.itemId != itemId || !account.equals(p.account) || !(p.unitCost > 0) || !(p.remaining > 0)) continue;
      units = Js.add(units, p.remaining);
      gp += p.unitCost * p.remaining;
    }
    if (activeOffers != null) for (Offer o : activeOffers) {
      if (o == null || o.itemId != itemId || !account.equals(o.account) || !"BUYING".equals(o.state) || !(o.filled > 0) || !(o.spent > 0)) continue;
      units = Js.add(units, o.filled);
      gp += o.spent;
    }
    return units > 0 ? new CostBasis(gp / units, units) : null;
  }
}
