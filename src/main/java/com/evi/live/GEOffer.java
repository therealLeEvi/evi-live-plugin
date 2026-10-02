package com.evi.live;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

/**
 * Read-only Grand Exchange offer-screen state, mirroring the equivalent accessor in a currently
 * published RuneLite Hub plugin (Flipping Copilot's GrandExchange.java / OfferHandler.java) down
 * to the same widget and varbit IDs. No setters here beyond the fill routine in
 * SuggestionKeybindHandler, and that routine never touches the Confirm button or a menu action.
 */
@Singleton
class GEOffer {
  private static final int CURRENTLY_OPEN_GE_SLOT_VARBIT_ID = 4439;

  /** Which single field, if any, a cached suggestion is currently eligible to be shown/filled for. */
  enum PromptField { NONE, QUANTITY, BUY_PRICE, SELL_PRICE }

  @Inject private Client client;

  boolean isSlotOpen() { return client.getVarbitValue(CURRENTLY_OPEN_GE_SLOT_VARBIT_ID) - 1 != -1; }
  int currentItemId() { return client.getVarpValue(GeIds.CURRENT_GE_ITEM); }
  boolean isBuying() { return client.getVarbitValue(GeIds.GE_OFFER_CREATION_TYPE) == 0; }
  boolean isSelling() { return client.getVarbitValue(GeIds.GE_OFFER_CREATION_TYPE) == 1; }

  private Widget chatboxTitle() { return client.getWidget(GeIds.CHATBOX_TITLE); }

  /** Does the open offer panel identify itself as a buy or a sell offer?
   *
   *  SEARCHES the container's children rather than indexing a fixed child (OFFER_TYPE_CHILD_ID = 20
   *  until 30 September 2026). **That index was NOT what broke** -- a probe in the client the same
   *  day found "Buy offer" still sitting at child 20 after the "Beyond Max Cash" update, so the old
   *  code read it correctly. The real fault was INPUT_TYPE, in isPromptOpen(). This was changed on a
   *  wrong diagnosis, and is kept only because it is genuinely more robust than a magic index into
   *  an interface Jagex owns and reshapes: the panel gained two currency rows that day, and the next
   *  such change may well move it.
   *
   *  Returns null only when no child says either. Callers must treat that as "cannot tell from the
   *  widget", NOT as "not an offer screen" -- see isSettingPrice, which falls back to the varbit. */
  private Widget offerTypeWidget() {
    Widget container = client.getWidget(GeIds.GRAND_EXCHANGE_OFFER_CONTAINER);
    if (container == null) return null;
    Widget found = findOfferType(container.getChildren());
    return found != null ? found : findOfferType(container.getDynamicChildren());
  }

  /** Both child arrays are searched, and BOTH have to be, because an EMPTY array is not a null one.
   *  The first version of this searched getDynamicChildren() and only fell back to getChildren()
   *  when it was null -- but a container with no dynamic children answers with a zero-length array,
   *  not null, so the fallback never ran and the search found nothing. getChildren() is searched
   *  first because that is the array the old fixed index read (Widget.getChild indexes it), so the
   *  offer type provably lives there. */
  private static Widget findOfferType(Widget[] children) {
    if (children == null) return null;
    for (Widget child : children) {
      if (child == null) continue;
      String text = child.getText();
      if ("Buy offer".equals(text) || "Sell offer".equals(text)) return child;
    }
    return null;
  }

  /** True only while the "How many do you wish to buy/sell?" quantity prompt is the open chatbox input. */
  boolean isSettingQuantity() {
    Widget title = chatboxTitle();
    if (title == null) return false;
    String text = title.getText();
    return "How many do you wish to buy?".equals(text) || "How many do you wish to sell?".equals(text);
  }

  /** True only while the "Set a price for each item:" prompt is the open chatbox input.
   *
   *  The offer-type widget is a CROSS-CHECK, never the thing that decides. When it cannot be found
   *  the varbit decides instead, because that is what openFieldFor goes on to read anyway
   *  (isBuying/isSelling, GeIds.GE_OFFER_CREATION_TYPE) -- so refusing here on a missing widget
   *  withhold a price EVI had already worked out, on the strength of a lookup that adds nothing the
   *  varbit does not already say. A widget that IS found and says something else still refuses:
   *  that is a positive signal we are looking at the wrong screen, which absence is not. */
  boolean isSettingPrice() {
    Widget title = chatboxTitle();
    if (title == null || !"Set a price for each item:".equals(title.getText())) return false;
    Widget offerType = offerTypeWidget();
    if (offerType == null) return isBuying() || isSelling();
    String text = offerType.getText();
    return "Buy offer".equals(text) || "Sell offer".equals(text);
  }

  /**
   * True only while the open chatbox is genuinely the GE quantity/price prompt, plus a GE slot
   * actually open. Independent of whether any suggestion currently matches.
   *
   * <p>This asks the chatbox what prompt it IS -- by its own title text -- rather than reading
   * GeIds.CHATBOX_INPUT_TYPE and comparing it to a magic number. It used to require
   * {@code INPUT_TYPE == 7}, copied from a currently published Hub plugin, and Jagex's
   * "Beyond Max Cash" update on 30 September 2026 changed the PRICE prompt's input type to 30
   * while leaving the quantity prompt's alone. The gate went permanently false for prices, so the
   * price hint and the hotkey fill died silently for every user while quantity kept working.
   * Measured in the client, not inferred: the probe read
   * {@code title="Set a price for each item:" inputType=30}.
   *
   * <p>The input type is still read, but as a BOOLEAN -- "is any chatbox input active at all"
   * (non-zero) -- never as an identity. Which prompt it is comes from the title, which the code
   * already depends on exactly (isSettingQuantity/isSettingPrice). That split is what makes this
   * survivable: Jagex renumbering the prompt cannot close the gate, and only retiring the chatbox
   * input mechanism entirely could.
   *
   * <p>All three halves are needed, and the stale title is why. The title widget KEEPS its text
   * after the prompt closes, so the client reported
   * {@code title="Set a price for each item:" inputType=0 currentItem=-1} with nothing open at all.
   * Two different things then go wrong if the other two are missing, and BOTH were seen for real:
   *
   * <ul>
   *   <li>without the active-input test, that stale line reads as an open prompt with the chatbox
   *       shut;</li>
   *   <li>without the chosen-item test, the ITEM SEARCH reads as an open price prompt -- the search
   *       box is an active input ({@code inputType=14}) and the title is still stale, so
   *       isPromptOpen() went true during the search and SuggestionItemSelectWidget, which uses this
   *       as a NEGATIVE gate, stopped drawing the clickable suggestion row. That regression shipped
   *       in the first 3.10.3 commit and was reported within minutes.</li>
   * </ul>
   *
   * <p>A quantity or price is always asked about a CHOSEN item; during the search none is chosen
   * ({@code currentItemId() == -1}). That is a fact about the prompt rather than another number
   * Jagex can renumber, which is why it is the right third test.
   *
   * <p>NOTE for anyone changing this: isPromptOpen() is a negative gate in
   * SuggestionItemSelectWidget and SuggestionSearchHighlightOverlay and a positive one in
   * SuggestionHintWidget and SuggestionKeybindHandler. Loosening it does not merely show more --
   * it HIDES the search row and the highlight. Check all four call sites, in the client.
   */
  boolean isPromptOpen() {
    return (isSettingQuantity() || isSettingPrice())
      && anyChatboxInputActive()
      && currentItemId() > 0
      && client.getWidget(GeIds.GRAND_EXCHANGE_OFFER_CONTAINER) != null
      && isSlotOpen();
  }

  /** Is the chatbox accepting input at all? Zero means none is open; every other value is some
   *  kind of input, and WHICH kind is deliberately not asked -- see isPromptOpen. */
  private boolean anyChatboxInputActive() {
    return client.getVarcIntValue(GeIds.CHATBOX_INPUT_TYPE) != 0;
  }

  /**
   * Which field (if any) the given suggestion is eligible to be shown/filled for right now: the
   * prompt must actually be open and the suggestion's item must match the item currently selected
   * in the GE offer screen. A price prompt resolves to whichever side the player is actually on
   * (BUY_PRICE while buying, SELL_PRICE while selling) using that side's price from the
   * suggestion, independent of the suggestion's own "action" field -- a suggestion always carries
   * both a buy and a sell price, so the same suggestion can show/fill either side of a flip:
   * the buy price while you're buying the item, and later the sell price while you're selling the
   * same item you already hold. Returns NONE for a null suggestion or a missing/non-positive price
   * on the relevant side. QUANTITY is only offered when s.quantity is actually positive -- always
   * true for a ranked/holding/market suggestion, but deliberately false for a plain
   * OpenItemPriceCache price (see its own class doc): there's no track record to size a quantity by
   * for an item that isn't EVI's own pick, so only its buy/sell price is ever shown/filled, never a
   * fabricated quantity.
   */
  PromptField openFieldFor(Suggestion s) {
    if (s == null || !isPromptOpen() || s.itemId != currentItemId()) return PromptField.NONE;
    if (isSettingQuantity()) return s.quantity > 0 ? PromptField.QUANTITY : PromptField.NONE;
    if (isSettingPrice()) {
      if (isBuying()) return s.buyPrice > 0 ? PromptField.BUY_PRICE : PromptField.NONE;
      if (isSelling()) return s.sellPrice > 0 ? PromptField.SELL_PRICE : PromptField.NONE;
    }
    return PromptField.NONE;
  }

  /**
   * Picks whichever of the two known suggestions actually matches the item currently open in the
   * GE offer screen -- ranked (EVI's own personalized/ranked/holding pick, from SuggestionCache)
   * if it matches, since it carries the richer reasoning and a real suggested quantity; otherwise
   * openItemPrice (a plain live-market price for whatever item IS open, from OpenItemPriceCache,
   * present regardless of flip history or ranking) if that matches instead; otherwise null. This is
   * what makes the hint/hotkey work for any item you're actually trading, not only EVI's current
   * #1 suggestion -- the original limitation the player reported: buying or selling something that
   * either had no reviewed history, or simply wasn't the single top-ranked pick right now, showed
   * no hint and filled nothing at all, even though a live GE price for it was readily available.
   */
  Suggestion resolveOpenSuggestion(Suggestion ranked, Suggestion openItemPrice) {
    int id = currentItemId();
    if (ranked != null && ranked.itemId == id) return ranked;
    if (openItemPrice != null && openItemPrice.itemId == id) return openItemPrice;
    return null;
  }
}
