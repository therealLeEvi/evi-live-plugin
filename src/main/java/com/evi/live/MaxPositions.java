package com.evi.live;

/**
 * How many trades EVI may suggest at once. One by default, and that default is the point.
 *
 * The objection to the original "plan all eight Grand Exchange slots" idea still governs this
 * setting: allocating a cash stack across eight trades divides the cash by eight, and an
 * eighth-sized trade cannot make the profit they trade for. The only form they would accept was
 * "up to N, where the player chooses N, defaulting to 1".
 *
 * It exists because a large stack cannot be deployed any other way. Measured on 28 Sept 2026, at an
 * 89m stack the market tier's median suggestion commits about 3.6m; the median trade is worth
 * roughly 600k gp whatever the ranking rule, because a single order is held to about 4% of the
 * volume that will trade in the player's window. More capital can only go out through more
 * positions, never through bigger ones.
 *
 * Each further suggestion is ranked on the cash still UNSPENT after the ones before it, never on a
 * pre-divided stack, and has to pass every check the first did -- including the correlation check,
 * so two slots never end up holding one bet in two shapes. Sent as ?maxSuggestions=.
 */
public enum MaxPositions {
  ONE, TWO, THREE;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case TWO: return "Up to 2";
      case THREE: return "Up to 3";
      default: return "Just one";
    }
  }

  int count() {
    switch (this) {
      case TWO: return 2;
      case THREE: return 3;
      default: return 1;
    }
  }
}
