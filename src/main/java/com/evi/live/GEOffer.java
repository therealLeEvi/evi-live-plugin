package com.evi.live;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.widgets.ComponentID;
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
  private static final int OFFER_TYPE_CHILD_ID = 20;

  /** Which single field, if any, a cached suggestion is currently eligible to be shown/filled for. */
  enum PromptField { NONE, QUANTITY, BUY_PRICE, SELL_PRICE }

  @Inject private Client client;

  boolean isSlotOpen() { return client.getVarbitValue(CURRENTLY_OPEN_GE_SLOT_VARBIT_ID) - 1 != -1; }
  int currentItemId() { return client.getVarpValue(VarPlayer.CURRENT_GE_ITEM); }
  boolean isBuying() { return client.getVarbitValue(Varbits.GE_OFFER_CREATION_TYPE) == 0; }
  boolean isSelling() { return client.getVarbitValue(Varbits.GE_OFFER_CREATION_TYPE) == 1; }

  private Widget chatboxTitle() { return client.getWidget(ComponentID.CHATBOX_TITLE); }
  private Widget offerTypeWidget() {
    Widget container = client.getWidget(ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER);
    return container == null ? null : container.getChild(OFFER_TYPE_CHILD_ID);
  }

  /** True only while the "How many do you wish to buy/sell?" quantity prompt is the open chatbox input. */
  boolean isSettingQuantity() {
    Widget title = chatboxTitle();
    if (title == null) return false;
    String text = title.getText();
    return "How many do you wish to buy?".equals(text) || "How many do you wish to sell?".equals(text);
  }

  /** True only while the "Set a price for each item:" prompt is the open chatbox input. */
  boolean isSettingPrice() {
    Widget title = chatboxTitle();
    if (title == null || !"Set a price for each item:".equals(title.getText())) return false;
    Widget offerType = offerTypeWidget();
    if (offerType == null) return false;
    String text = offerType.getText();
    return "Buy offer".equals(text) || "Sell offer".equals(text);
  }

  /**
   * True only while the open chatbox is genuinely the GE quantity/price prompt: the same gate a
   * currently published Hub plugin uses before touching or drawing over anything (VarClientInt.
   * INPUT_TYPE == 7 is the GE quantity/price chatbox, not just any open chatbox), plus a GE slot
   * actually open. Independent of whether any suggestion currently matches.
   */
  boolean isPromptOpen() {
    return chatboxTitle() != null
      && client.getVarcIntValue(VarClientInt.INPUT_TYPE) == 7
      && client.getWidget(ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER) != null
      && isSlotOpen();
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
