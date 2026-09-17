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
 * suggested quantity for an item with no track record. persisted is true only when this
 * suggestion came from the bridge's own on-disk reconstruction of open positions (see
 * pickPersistentOpenPosition in suggestions.mjs), used as a restart-safe fallback when the
 * plugin's own live holdItemId/holdQty signal is empty -- never set for a suggestion the bridge
 * built from something observed live this session. That reconstruction can go stale (a position
 * that, in reality, was fully resold through some combination of separate sale offers the
 * automatic matcher didn't perfectly reconcile), so EviLivePlugin.verifyPersistedHolding() checks
 * a persisted suggestion against the player's actual current inventory before trusting it, rather
 * than assuming the journal is always right.
 *
 * buyId identifies the specific GE buy offer behind a "sell" (holding) suggestion, when the bridge
 * can trace one -- both the live path (EviLivePlugin.heldForResale) and the persisted-fallback
 * path carry one when available. Null for a "buy" suggestion (nothing bought yet to identify), and
 * for a holding signal with no single identifiable buy behind it. Sent back unchanged via POST
 * /api/suggestion/personal-use if the player flags this specific holding as personal use --
 * bought for their own use via the GE, not to flip -- so the bridge can exclude that exact buy
 * from ever being surfaced again or counted toward profit, without silently suppressing a real,
 * separate flip of the same item bought some other time. See EviLivePlugin.flagPersonalUse.
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
  boolean persisted;
  String buyId;
  // "Sell" (holding) suggestions only, and only when the real price paid is known -- otherwise
  // null, never estimated. breakEvenPrice: the lowest sell price per unit that doesn't lose money
  // after GE tax. lossIfSoldNow: set only when selling at sellPrice right now would lose money, the
  // total GP lost. Shown as a warning; a losing sell is never hidden or blocked.
  Integer breakEvenPrice;
  Long lossIfSoldNow;

  boolean sellsAtLoss() {
    return "sell".equals(action) && lossIfSoldNow != null && lossIfSoldNow > 0;
  }
}
