package com.evi.live;

/**
 * How large a position EVI is willing to build, and how much it lets an item's thinness count
 * against it. Careful is the default and is exactly what EVI has always done.
 *
 * BIGGER exists because a large cash stack cannot be deployed otherwise, and because EVI was
 * measurably wrong about one whole class of trade. Three things had to move together, and each one
 * alone measured neutral or worse, which is why none of them shipped on its own:
 *
 *  1. the liquidity floor is read over the player's whole trade window rather than per hour, so an
 *     item trading 3 an hour is not excluded from a 12-hour trade that will see 36 of them change
 *     hands;
 *  2. the ranking stops multiplying by log(liquidity), which spans about 1.4 for an item trading 3
 *     an hour against 9.5 for one trading 12,000 -- a near-sevenfold handicap that meant a thin item
 *     won the ranking only 3 times in 193 however much more the trade was worth;
 *  3. the order is capped at a share of the volume that will trade in the WINDOW rather than a
 *     multiple of one hour, so a thin item can be bought in a size worth holding.
 *
 * Measured over 14 archived days at 20m, 78m and 200m stacks, at a 12-hour pace: the median trade
 * goes from about 150,000 gp to 380,000-480,000, the median order from 6.6m to 15.2m, and the share
 * of picks that lose GP from 8% to 9-10%. In the thin band this is meant to reach -- items trading
 * under 25 an hour -- EVI today picks 1 to 3 of them and LOSES on 67-100%, while this picks 15 to 21
 * and loses on 10-13%. Every one of Flipping Copilot's genuinely good picks on 28 Sept 2026 was in
 * that band (Abyssal dagger at 6 an hour, Light ballista at 3, Seers icon at 0), and every one of its
 * losers was a liquid thin-margin item EVI already refuses on tax.
 *
 * It is NOT the default, for two reasons. It raises the loss rate, modestly but really. And the
 * result does not hold at the longest pace: at a two-day trade the same settings take the loss rate
 * from 8% to 13%, because 10% of two days' volume is a far larger order than 10% of twelve hours'.
 * Best suited to trades up to about half a day.
 */
public enum PositionSizing {
  CAREFUL, BIGGER;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    return this == BIGGER ? "Bigger positions" : "Careful";
  }

  /** The query parameter, or null when there is nothing to say (Careful changes nothing). */
  String param() {
    return this == BIGGER ? "bigger" : null;
  }
}
