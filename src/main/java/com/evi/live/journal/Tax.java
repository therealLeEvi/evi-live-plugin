package com.evi.live.journal;

import java.util.Set;

/**
 * The Grand Exchange tax, ported from bridge/tax.mjs (and {@code breakEvenSellPrice} from
 * bridge/suggestions.mjs). Pure.
 *
 * <p>The rules, each one checked against the JS engine by JournalVectorsTest:
 * <ul>
 *   <li>2% of the sale price, ROUNDED DOWN per unit: {@code floor(price / 50)}. So anything under 50 gp
 *       pays nothing (the under-50 rule falls out of the floor, it is not a separate branch).</li>
 *   <li>Capped at {@link #CAP} (5,000,000) a unit, which binds from 250,000,000 gp up.</li>
 *   <li>An exempt set of item ids pays nothing at all (cross-checked by the bridge against Flipping
 *       Utilities' Hub-pinned source; policy date 2025-05-29).</li>
 *   <li>A completed sale first seen before {@link #TAX_ERA_START_MS} (30 May 2025) is never
 *       reinterpreted: {@link #saleProceeds} returns null.</li>
 * </ul>
 *
 * <p>Every price and every total is a {@code long}. Where the JS engine works in doubles (a per-unit
 * average, a break-even search) this does the same double arithmetic in the same order, so the answer is
 * the same number. Integer GP arithmetic goes through {@link Js#add} and friends, which are exact below
 * 2^53 and round exactly as JS does above it, so the two agree to the last digit everywhere a long can hold.
 */
public final class Tax {
  private Tax() {}

  /** The per-unit cap, in gp. */
  public static final long CAP = 5_000_000L;
  /** Date.UTC(2025, 4, 30): the start of the tax regime this engine can reason about. */
  public static final long TAX_ERA_START_MS = 1748563200000L;

  private static final Set<Integer> EXEMPT = Set.of(1755, 5325, 2347, 1733, 13190, 233, 5341, 8794, 5329, 5343, 1735,
    952, 5331, 8011, 365, 2309, 882, 806, 1891, 8010, 28824, 2140, 2142, 3008, 3010, 3012, 3014, 8009, 3853, 347, 884,
    807, 28790, 379, 8008, 355, 2327, 558, 351, 2552, 329, 315, 886, 808, 8013, 361, 8007);

  public static boolean isExempt(int itemId) {
    return EXEMPT.contains(itemId);
  }

  /**
   * Estimated per-unit tax for a hypothetical future sale. Zero for an exempt item, and zero (not a
   * guess) for a price that is not a positive finite number.
   */
  public static long estimateUnitTax(int itemId, double sellPrice) {
    if (EXEMPT.contains(itemId)) return 0;
    if (Double.isNaN(sellPrice) || Double.isInfinite(sellPrice) || sellPrice <= 0) return 0;
    return (long) Math.min((double) CAP, Math.floor(sellPrice / 50));
  }

  /** What a completed sale actually paid: tax.mjs's {net, tax, exact}. */
  public static final class Proceeds {
    public final long net;
    public final long tax;
    /** False when the sale filled at several prices: the aggregate counter cannot say how each unit rounded. */
    public final boolean exact;

    Proceeds(long net, long tax, boolean exact) {
      this.net = net;
      this.tax = tax;
      this.exact = exact;
    }
  }

  /**
   * The exact tax on a completed sale. Null when the counters are unusable (nothing filled, a negative
   * spend) or the offer predates the tax regime; never an estimate dressed as a fact.
   */
  public static Proceeds saleProceeds(Offer o) {
    long q = o.filled, gross = o.spent;
    if (!Js.isSafeInteger(q) || q < 1 || !Js.isSafeInteger(gross) || gross < 0) return null;
    // An absent firstSeen is NOT before the era in JS (undefined < x is false), so it is taxed.
    if (o.firstSeen != null && o.firstSeen < TAX_ERA_START_MS) return null;
    if (EXEMPT.contains(o.itemId)) return new Proceeds(gross, 0, true);
    double unit = (double) gross / q;
    boolean exact = q == 1 || gross == Js.multiply(o.price, q);
    long tax = Js.multiply((long) Math.min((double) CAP, Math.floor(unit / 50)), q);
    return new Proceeds(Js.subtract(gross, tax), tax, exact);
  }

  /**
   * The lowest whole sell price that still returns the unit cost after tax, or null when the cost is not
   * a positive finite number (never a guessed cost). suggestions.mjs's search, step for step.
   */
  public static Long breakEvenSellPrice(int itemId, double unitCost) {
    if (Double.isNaN(unitCost) || Double.isInfinite(unitCost) || unitCost <= 0) return null;
    long p = (long) Math.ceil(unitCost);
    if (clears(itemId, p, unitCost)) return p; // tax-exempt, or too cheap to be taxed
    p = (long) Math.min(Math.ceil(unitCost * 50 / 49), Math.ceil(unitCost) + (double) CAP);
    for (int i = 0; i < 1000 && !clears(itemId, p, unitCost); i++) p++;
    for (int i = 0; i < 1000 && p > 1 && clears(itemId, p - 1, unitCost); i++) p--;
    return clears(itemId, p, unitCost) ? p : null;
  }

  private static boolean clears(int itemId, long p, double unitCost) {
    return (double) (p - estimateUnitTax(itemId, p)) >= unitCost;
  }
}
