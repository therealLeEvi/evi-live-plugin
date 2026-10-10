package com.evi.live;

/**
 * Which kind of item EVI suggests buying in game, as a plugin config dropdown. Sent as ?focus=gear|bulk; ALL_ITEMS sends
 * nothing, and the engine's default with no focus is any item (Focus.resolveFocus), so a default install's query is unchanged.
 *
 * <p>ALL_ITEMS is the default (4.0.0): an unset focus means any item. The old default, "Same as scanner", followed the
 * companion app's scanner switch and was removed with it; a stored value of it is replaced by this default when the plugin
 * loads (see EviLiveConfig.suggestionFocus).
 *
 * <p>The split is each item's own Grand Exchange buy limit, which is game data rather than a guess:
 * consumables and ammunition sell by the thousand (limits of 2,000 to 18,000), gear in single or
 * double figures (4 to 125). An item whose limit is unknown is only ever suggested under ALL_ITEMS.
 * Only buy suggestions are affected; a reminder to sell stock the player already holds still appears
 * whatever this is set to.
 */
public enum SuggestionFocus {
  ALL_ITEMS, GEAR, BULK;

  /** RuneLite renders enum dropdowns with toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case GEAR: return "Gear";
      case BULK: return "Bulk (consumables & ammo)";
      default: return "All items";
    }
  }

  /** The value sent as ?focus=, or null for ALL_ITEMS: nothing is sent and the engine takes any item. */
  String param() {
    switch (this) {
      case GEAR: return "gear";
      case BULK: return "bulk";
      default: return null;
    }
  }
}
