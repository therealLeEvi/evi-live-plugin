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
  long buyPrice;
  long sellPrice;
  String source;
  String reasoning;
  boolean persisted;
  String buyId;
  /** The bridge's handle for this exact shown suggestion, sent back by the "I took this one" button
   * (POST /api/suggestion/accept). It is what lets EVI say what following it is actually worth:
   * without it, the track record is assembled by guessing that an offer placed soon after a
   * suggestion means the suggestion was followed, which cannot tell a followed pick from a trade
   * the player meant to make anyway. Null from an older bridge, in which case the button is hidden
   * rather than shown doing nothing. `accepted` is what the bridge has already recorded for this
   * id, so the button reads as pressed after a reconnect instead of inviting a second press. */
  String id;
  boolean accepted;
  // "Sell" (holding) suggestions only, and only when the real price paid is known -- otherwise
  // null, never estimated. breakEvenPrice: the lowest sell price per unit that doesn't lose GP
  // after GE tax. lossIfSoldNow: set only when selling at sellPrice right now would lose GP, the
  // total GP lost. Shown as a warning; a losing sell is never hidden or blocked.
  Long breakEvenPrice;
  Long lossIfSoldNow;
  /** What the whole trade is worth after tax, computed by the bridge so the panel does not have to
   *  know the Grand Exchange's tax rules to print the headline figure. Null from an older bridge. */
  Long expectedProfit;
  /** What EVI makes of its own pick, as something the sidebar can draw rather than prose to read
   *  (bridge/verdict.mjs). Null when nothing was measured, or when the bridge predates it -- either
   *  way the panel falls back to the reasoning paragraph, so old and new versions still agree. */
  Verdict verdict;

  /** Deliberately a summary of checks that already ran, never a new judgement: every line restates a
   *  figure the bridge had already computed. */
  static class Verdict {
    /** "clear", "caution" or "warn". Anything else is treated as caution rather than trusted. */
    String level;
    String label;
    java.util.List<Check> checks;
  }

  static class Check {
    /** TRUE passed, FALSE failed, null neither -- a stated figure like a break-even price. */
    Boolean ok;
    String text;
  }

  boolean sellsAtLoss() {
    return "sell".equals(action) && lossIfSoldNow != null && lossIfSoldNow > 0;
  }
}
