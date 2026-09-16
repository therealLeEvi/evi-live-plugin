package com.evi.live;

import javax.inject.Singleton;

/**
 * Holds the most recently fetched plain live-market price for whatever item is currently open in
 * a GE offer (buy or sell), independent of SuggestionCache's own ranked/personalized pick --
 * populated from the bridge response's separate openItemPrice field (see GET /api/suggestion's
 * openItemId parameter). Written by the poller, read by the hint widget and the hotkey handler via
 * GEOffer.resolveOpenSuggestion, exactly the same pattern as SuggestionCache. Its Suggestion.name/
 * action/reasoning/source are not populated by the bridge for this path, and its quantity is always
 * 0 -- there's no track record to size a quantity by for an item that isn't the ranked pick, so
 * GEOffer.openFieldFor deliberately never offers PromptField.QUANTITY for a quantity of 0, only the
 * buy/sell price fields.
 */
@Singleton
class OpenItemPriceCache {
  private volatile Suggestion current;
  Suggestion get() { return current; }
  void set(Suggestion s) { current = s; }
}
