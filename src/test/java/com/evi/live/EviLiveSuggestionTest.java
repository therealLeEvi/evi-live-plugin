package com.evi.live;

import com.google.gson.Gson;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ModifierlessKeybind;

/**
 * Fake widget/client only; never launches a game client and never touches the Confirm button
 * or a menu action. Asserts the hotkey writes the exact expected string and nothing else.
 */
public class EviLiveSuggestionTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

  static class FakeWidget {
    String text = "";
    int originalY;
    int itemId = -1;
    boolean hidden = false;
    java.awt.Rectangle bounds;
    // Captured for SuggestionItemSelectWidget's tests: the exact (script, itemId, arg) triple
    // passed to setOnOpListener/setOnKeyListener, and the widget's name (used for its tooltip/menu
    // label, same as a real GE search-result row).
    Object[] onOpArgs;
    Object[] onKeyArgs;
    String name;
    Map<Integer, FakeWidget> children = new HashMap<>();
    // Populated by createChild(), so a test can inspect every child widget a caller created on
    // this fake (e.g. the chatbox container in EviLiveHintTest), in creation order.
    java.util.List<FakeWidget> createdChildren = new java.util.ArrayList<>();
    // Distinct from children/createdChildren (which key by fixed child index): models
    // getDynamicChildren(), the list-style children of a search results widget, keyed by nothing
    // but position, for EviLiveSearchHighlightTest.
    java.util.List<FakeWidget> dynamicChildren = new java.util.ArrayList<>();
    Widget proxy() {
      return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class[]{Widget.class}, (p, m, a) -> {
        switch (m.getName()) {
          case "getText": return text;
          case "setText": text = (String) a[0]; return null;
          case "getChild": return children.containsKey(a[0]) ? children.get((Integer) a[0]).proxy() : null;
          case "createChild": {
            FakeWidget child = new FakeWidget();
            createdChildren.add(child);
            return child.proxy();
          }
          case "getOriginalY": return originalY;
          case "setOriginalY": originalY = (Integer) a[0]; return null;
          case "getItemId": return itemId;
          case "isHidden": return hidden;
          case "getBounds": return bounds;
          case "getDynamicChildren": return dynamicChildren.stream().map(FakeWidget::proxy).toArray(Widget[]::new);
          case "setOnOpListener": onOpArgs = (Object[]) a[0]; return null;
          case "setOnKeyListener": onKeyArgs = (Object[]) a[0]; return null;
          case "setName": name = (String) a[0]; return null;
          case "setItemId": itemId = (Integer) a[0]; return null;
          // Styling/layout/listener setters the hint widget calls but this fixture doesn't need to
          // model beyond accepting the call; revalidate/setOriginalX/etc. are no-ops here.
          default: return null;
        }
      });
    }
  }

  static class FakeClient {
    int openSlotVarbit = 0; // CURRENTLY_OPEN_GE_SLOT_VARBIT_ID raw value; 0 means "no slot open" (getOpenSlot() == -1)
    int offerCreationType = 0; // 0 = buying, 1 = selling (Varbits.GE_OFFER_CREATION_TYPE)
    int currentItemId = -1;
    int inputType = 7;
    FakeWidget chatboxTitle = new FakeWidget();
    FakeWidget chatboxInput = new FakeWidget();
    FakeWidget offerContainer = new FakeWidget();
    FakeWidget chatboxContainer = new FakeWidget();
    // Null by default, modelling the GE item-search results widget not existing yet (e.g. the
    // search box isn't open); EviLiveSearchHighlightTest assigns one in to exercise the rest.
    FakeWidget searchResults;
    String lastVarcStr;

    Client proxy() {
      return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class[]{Client.class}, (p, m, a) -> {
        switch (m.getName()) {
          case "getVarbitValue": {
            boolean isSlotVarbit = a[0] instanceof Integer && (Integer) a[0] == 4439;
            return isSlotVarbit ? openSlotVarbit : offerCreationType;
          }
          case "getVarpValue": return currentItemId;
          case "getVarcIntValue": return inputType;
          case "getWidget": {
            Object id = a[0];
            if (ComponentID.CHATBOX_TITLE == asInt(id)) return chatboxTitle.proxy();
            if (ComponentID.CHATBOX_FULL_INPUT == asInt(id)) return chatboxInput.proxy();
            if (ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER == asInt(id)) return offerContainer.proxy();
            if (ComponentID.CHATBOX_CONTAINER == asInt(id)) return chatboxContainer.proxy();
            if (ComponentID.CHATBOX_GE_SEARCH_RESULTS == asInt(id)) return searchResults == null ? null : searchResults.proxy();
            return null;
          }
          case "setVarcStrValue": lastVarcStr = (String) a[1]; return null;
          default: return primitiveDefault(m.getReturnType());
        }
      });
    }
    private static int asInt(Object o) { return o instanceof Integer ? (Integer) o : -1; }
    private static Object primitiveDefault(Class<?> t) {
      if (t == boolean.class) return false;
      if (t == int.class) return 0;
      return null;
    }
  }

  /** buyPrice and sellPrice are always both set, matching what the bridge actually sends now. */
  static Suggestion suggestion(int itemId, String action, int quantity, int buyPrice, int sellPrice) {
    Suggestion s = new Suggestion();
    s.itemId = itemId; s.action = action; s.quantity = quantity; s.buyPrice = buyPrice; s.sellPrice = sellPrice; s.name = "Test item"; s.source = "personal";
    return s;
  }

  /** Models a bridge openItemPrice response: quantity always 0, name/action/source/reasoning left
   *  unset, exactly as OpenItemPriceCache's own class doc describes. */
  static Suggestion openItemPrice(int itemId, int buyPrice, int sellPrice) {
    Suggestion s = new Suggestion();
    s.itemId = itemId; s.buyPrice = buyPrice; s.sellPrice = sellPrice;
    return s;
  }

  public static void main(String[] args) throws Exception {
    // The bridge's real JSON shape must still deserialize into the plugin's field names, including
    // the separate openItemPrice field (a plain {itemId, buyPrice, sellPrice} shape -- no name,
    // action, quantity, source, or reasoning -- see OpenItemPriceCache's own class doc).
    String json = "{\"suggestion\":{\"itemId\":13190,\"name\":\"Fire rune\",\"action\":\"buy\",\"quantity\":500,\"buyPrice\":4,\"sellPrice\":5,\"source\":\"personal\",\"reasoning\":\"test\"},\"openItemPrice\":{\"itemId\":314,\"buyPrice\":180,\"sellPrice\":210}}";
    EviLivePlugin.SuggestionResponse parsed = new Gson().fromJson(json, EviLivePlugin.SuggestionResponse.class);
    check(parsed.suggestion.itemId == 13190 && "buy".equals(parsed.suggestion.action) && parsed.suggestion.quantity == 500
      && parsed.suggestion.buyPrice == 4 && parsed.suggestion.sellPrice == 5, "Bridge JSON shape must round-trip into plugin fields");
    check(parsed.openItemPrice.itemId == 314 && parsed.openItemPrice.buyPrice == 180 && parsed.openItemPrice.sellPrice == 210
      && parsed.openItemPrice.quantity == 0 && parsed.openItemPrice.name == null, "openItemPrice must round-trip as a plain price with no name/quantity");

    FakeClient fc = new FakeClient();
    Client client = fc.proxy();
    GEOffer geOffer = new GEOffer();
    Field clientField = GEOffer.class.getDeclaredField("client"); clientField.setAccessible(true); clientField.set(geOffer, client);
    SuggestionCache cache = new SuggestionCache();
    OpenItemPriceCache openItemPriceCache = new OpenItemPriceCache();
    SuggestionKeybindHandler handler = new SuggestionKeybindHandler(null, null, client, geOffer, cache, openItemPriceCache, null);
    Method fill = SuggestionKeybindHandler.class.getDeclaredMethod("fill"); fill.setAccessible(true);

    // No GE slot open at all: nothing should be touched even with a cached suggestion.
    cache.set(suggestion(1, "buy", 100, 50, 65));
    fc.currentItemId = 1;
    fill.invoke(handler);
    check(fc.chatboxInput.text.isEmpty() && fc.lastVarcStr == null, "Hotkey must do nothing when no GE slot is open");

    // Slot open, but neither the quantity nor price prompt is the open chatbox.
    fc.openSlotVarbit = 1;
    fill.invoke(handler);
    check(fc.chatboxInput.text.isEmpty(), "Hotkey must do nothing when the open chatbox is not the quantity/price prompt");

    // Quantity prompt open, matching item: fills quantity, exact string, touches nothing else.
    fc.chatboxTitle.text = "How many do you wish to buy?";
    fill.invoke(handler);
    check("100*".equals(fc.chatboxInput.text), "Hotkey must fill the exact suggested quantity");
    check("100".equals(fc.lastVarcStr), "Hotkey must set the matching VarClientStr value");

    // Different item selected in-game than the cached suggestion: must not fill.
    fc.chatboxInput.text = "";
    fc.lastVarcStr = null;
    fc.currentItemId = 999;
    fill.invoke(handler);
    check(fc.chatboxInput.text.isEmpty(), "Hotkey must not fill a suggestion for a different item than is currently selected");

    // Price prompt, buy side, matching item: fills the suggestion's buy price.
    fc.currentItemId = 1;
    fc.chatboxTitle.text = "Set a price for each item:";
    fc.offerContainer.children.put(20, new FakeWidget());
    fc.offerContainer.children.get(20).text = "Buy offer";
    fill.invoke(handler);
    check("50*".equals(fc.chatboxInput.text), "Hotkey must fill the exact suggested buy price");

    // Price prompt, same item, but the player is actually selling: this is the same flip's other
    // side, so it must fill the suggestion's SELL price, not stay empty and not reuse the buy price.
    fc.chatboxInput.text = "";
    fc.offerCreationType = 1; // selling
    fc.offerContainer.children.get(20).text = "Sell offer";
    fill.invoke(handler);
    check("65*".equals(fc.chatboxInput.text), "Hotkey must fill the exact suggested sell price while selling the same item");

    // A suggestion with no usable sell price (e.g. a malformed 0) must not fill anything while selling.
    fc.chatboxInput.text = "";
    cache.set(suggestion(1, "buy", 100, 50, 0));
    fill.invoke(handler);
    check(fc.chatboxInput.text.isEmpty(), "Hotkey must not fill a non-positive sell price");

    // No cached suggestion at all: never touches the input widget.
    cache.set(null);
    fc.offerCreationType = 0;
    fc.offerContainer.children.get(20).text = "Buy offer";
    fc.chatboxTitle.text = "How many do you wish to buy?";
    fill.invoke(handler);
    check(fc.chatboxInput.text.isEmpty(), "Hotkey must do nothing with no cached suggestion");

    // -- GEOffer.resolveOpenSuggestion: prefers the ranked pick when it matches the item currently
    // open, falls back to a plain OpenItemPriceCache price when only that matches, null otherwise.
    // This is what lets the hint/hotkey work for ANY item being traded, not only EVI's current #1
    // suggestion (the original limitation reported: an item with no reviewed history, or one that
    // simply wasn't the single top-ranked pick, got no hint and filled nothing at all). --
    check(geOffer.resolveOpenSuggestion(null, null) == null, "resolveOpenSuggestion: neither known: null");
    fc.currentItemId = 1;
    check(geOffer.resolveOpenSuggestion(suggestion(1, "buy", 100, 50, 65), openItemPrice(1, 40, 55)).source != null,
      "resolveOpenSuggestion: the ranked pick is preferred over the open-item price when both match the open item");
    check(geOffer.resolveOpenSuggestion(suggestion(2, "buy", 100, 50, 65), openItemPrice(1, 40, 55)).quantity == 0,
      "resolveOpenSuggestion: falls back to the open-item price when the ranked pick doesn't match the open item");
    check(geOffer.resolveOpenSuggestion(suggestion(2, "buy", 100, 50, 65), openItemPrice(3, 40, 55)) == null,
      "resolveOpenSuggestion: null when neither known suggestion matches the open item");

    // End-to-end via the hotkey: no ranked suggestion at all for this item, but a plain live
    // open-item price is known for it -- the price must still fill, but a quantity (always 0 for
    // an open-item price -- there's no track record to size one by) must never be offered/filled.
    cache.set(null);
    openItemPriceCache.set(openItemPrice(1, 42, 58));
    fc.currentItemId = 1;
    fc.offerCreationType = 0;
    fc.chatboxTitle.text = "How many do you wish to buy?";
    fc.chatboxInput.text = "";
    fill.invoke(handler);
    check(fc.chatboxInput.text.isEmpty(), "Hotkey must never fill a quantity for a plain open-item price");
    fc.chatboxTitle.text = "Set a price for each item:";
    fc.offerContainer.children.get(20).text = "Buy offer";
    fill.invoke(handler);
    check("42*".equals(fc.chatboxInput.text), "Hotkey must fill the open-item buy price for an item with no ranked suggestion");
    fc.chatboxInput.text = "";
    fc.offerCreationType = 1;
    fc.offerContainer.children.get(20).text = "Sell offer";
    fill.invoke(handler);
    check("58*".equals(fc.chatboxInput.text), "Hotkey must fill the open-item sell price for an item with no ranked suggestion");
    openItemPriceCache.set(null);
    fc.offerCreationType = 0;
    fc.offerContainer.children.get(20).text = "Buy offer";

    // The keybind listener itself must never treat Enter as the configured hotkey, even if somehow bound to it.
    EviLiveConfig config = (EviLiveConfig) Proxy.newProxyInstance(EviLiveConfig.class.getClassLoader(), new Class[]{EviLiveConfig.class},
      (p, m, a) -> "suggestionKeybind".equals(m.getName()) ? new ModifierlessKeybind(KeyEvent.VK_ENTER, 0) : null);
    SuggestionKeybindHandler enterBound = new SuggestionKeybindHandler(null, null, client, geOffer, cache, openItemPriceCache, config);
    Field listenerField = SuggestionKeybindHandler.class.getDeclaredField("listener"); listenerField.setAccessible(true);
    Object listener = listenerField.get(enterBound);
    Method keyPressed = listener.getClass().getMethod("keyPressed", KeyEvent.class);
    cache.set(suggestion(1, "buy", 100, 50, 65));
    fc.currentItemId = 1;
    fc.offerCreationType = 0; // buying, so the buy price is the one that could get filled
    fc.chatboxTitle.text = "Set a price for each item:";
    fc.offerContainer.children.get(20).text = "Buy offer";
    fc.chatboxInput.text = "";
    // A plain Component (not Label/Button/etc.) never touches a native peer, so this stays safe
    // under java.awt.headless=true, which this Gradle task sets the same way panelTest does.
    java.awt.Component dummy = new java.awt.Component() { };
    KeyEvent enterEvent = new KeyEvent(dummy, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED);
    // clientThread is null in this handler, so if Enter were not excluded first, the invokeLater
    // call below would throw immediately (synchronously, from within keyPressed) instead of quietly
    // returning; either way nothing gets filled.
    keyPressed.invoke(listener, enterEvent);
    check(fc.chatboxInput.text.isEmpty(), "Enter must never be treated as the fill hotkey even if configured that way");

    System.out.println("PASS: no-slot, wrong-prompt, quantity fill, item mismatch, buy price fill, sell price fill, non-positive sell price, no-suggestion, resolveOpenSuggestion precedence/fallback/null, open-item price fill without a fabricated quantity, and Enter-key exclusion");
  }
}
