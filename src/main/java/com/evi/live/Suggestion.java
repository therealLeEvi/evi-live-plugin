package com.evi.live;

/**
 * Mirrors the JSON shape of both the "suggestion" and "openItemPrice" fields returned by GET
 * /api/suggestion (see SuggestionCache and OpenItemPriceCache respectively). Gson-populated; field
 * names must match exactly. buyPrice and sellPrice are always both present together (the bridge's
 * estimate of the current low/high for this item) so the plugin can show/fill whichever one
 * matches the GE prompt actually open, regardless of what action names. For an openItemPrice
 * instance specifically: name/action/source/reasoning are not populated by the bridge (left at
 * their Gson defaults, null) and quantity is always 0 -- see OpenItemPriceCache's own class doc
 * for why, and GEOffer.openFieldFor for how a 0 quantity is used to never offer/fill a fabricated
 * suggested quantity for an item with no track record.
 */
class Suggestion {
  int itemId;
  String name;
  String action;
  int quantity;
  int buyPrice;
  int sellPrice;
  String source;
  String reasoning;
}
