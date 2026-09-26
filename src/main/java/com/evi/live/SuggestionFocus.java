package com.evi.live;

/**
 * Which kind of item EVI suggests buying in game, as a plugin config dropdown. Sent to the bridge as
 * ?focus=any|gear|bulk; SAME_AS_SCANNER (the default) sends nothing, so the bridge uses whatever the
 * browser scanner's own Focus switch is set to -- exactly the behaviour from before this existed.
 *
 * The split is each item's own Grand Exchange buy limit, which is game data rather than a guess:
 * consumables and ammunition sell by the thousand (limits of 2,000 to 18,000), gear in single or
 * double figures (4 to 125). An item whose limit is unknown is only ever suggested under ALL_ITEMS.
 * Only buy suggestions are affected; a reminder to sell stock the player already holds still appears
 * whatever this is set to.
 */
public enum SuggestionFocus {
  SAME_AS_SCANNER, ALL_ITEMS, GEAR, BULK;

  /** RuneLite renders enum dropdowns with toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case ALL_ITEMS: return "All items";
      case GEAR: return "Gear";
      case BULK: return "Bulk (consumables & ammo)";
      default: return "Same as scanner";
    }
  }

  /** The value sent as ?focus=, or null to send nothing and follow the scanner's switch. */
  String param() {
    switch (this) {
      case ALL_ITEMS: return "any";
      case GEAR: return "gear";
      case BULK: return "bulk";
      default: return null;
    }
  }
}
