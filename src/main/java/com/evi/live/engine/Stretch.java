package com.evi.live.engine;

import com.evi.live.market.MarketAggregates;
import java.util.Map;

/**
 * A buy quoted far above the item's OWN usual price (bridge/stretch.mjs {@code stretchOf}, {@code stretchLine},
 * {@code stretchSentence}, and server.mjs's enrich): a card line and a sentence, NEVER a block, a demotion or a change of
 * pick. Pure.
 *
 * <p>Asked for on 15 Oct 2026 after a Tome of Earth (empty) was bought at 1.72m against weeks at ~1.10m. Measured first
 * (STRETCH-VERDICT-2026-10-15, private): with z = ln(buy / level) / move, buys at z >= 2 lost money more often, on real
 * trades and market-wide; a market-tier BLOCK was replayed the same day and failed, so this only warns. Dips (z below 0) are
 * the best-measured group and never speak. Fails open: no basis (thin data, a move of 0, before the first build) or a level
 * under {@link #STRETCH_PRICE_FLOOR} is no reading and no line.
 */
public final class Stretch {
  private Stretch() {}

  public static final int STRETCH_Z = 2;
  public static final long STRETCH_PRICE_FLOOR = 1000;

  /** {@code {level, abovePct}}: the usual price and how far above it, as a whole percent (at least 1). */
  public static final class Reading {
    public final long level;
    public final long abovePct;

    Reading(long level, long abovePct) {
      this.level = level;
      this.abovePct = abovePct;
    }
  }

  /** stretchOf: null unless the price sits at least {@link #STRETCH_Z} of the item's typical daily moves above its level. */
  public static Reading of(MarketAggregates.StretchBasis basis, long buyPrice) {
    if (basis == null || !(buyPrice > 0) || !(basis.level >= STRETCH_PRICE_FLOOR) || !(basis.move > 0)) return null;
    if (!(StrictMath.log((double) buyPrice / basis.level) / basis.move >= STRETCH_Z)) return null;
    return new Reading(basis.level, Math.max(1, (long) JsValues.jsRound(((double) buyPrice / basis.level - 1) * 100)));
  }

  /** stretchLine: the card line. */
  static String line(Reading r) {
    return r.abovePct + "% above its usual price -- often falls back. Consider waiting.";
  }

  /** stretchSentence: a market-wide statement only, never a rate from anyone's own trades, never odds. */
  static String sentence(Reading r) {
    return "This price is " + r.abovePct + "% above where the item usually traded over the last two weeks (about "
      + JsValues.localeUs(r.level) + " gp). Across the market, buys made this far above an item's usual price have lost money "
      + "more often than buys near it.";
  }

  /** server.mjs's enrich, for one pick: on a buy with a reading, attach it and append the sentence. A null basis map is no warning. */
  static void apply(Pick s, Map<Integer, MarketAggregates.StretchBasis> basis) {
    if (s == null || !s.isBuy() || basis == null) return;
    Reading r = of(basis.get(s.itemId), s.buyPrice);
    if (r == null) return;
    s.stretch = r;
    s.reasoning = ((s.reasoning == null ? "" : s.reasoning) + " " + sentence(r)).trim();
  }
}
