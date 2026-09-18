package com.evi.live;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.client.config.ModifierlessKeybind;

/**
 * Fake widget/client only; never launches a game client. Covers the eligibility gate the hint
 * widget shares with the fill hotkey (GEOffer.openFieldFor) -- including that it resolves to
 * BUY_PRICE or SELL_PRICE by which side the player is actually on, independent of the
 * suggestion's own "action" field, so the same suggestion can show/fill both sides of one flip --
 * and the widget lifecycle itself: created once per fresh prompt-open, reused (never duplicated)
 * while it stays open, cleared on mismatch or close, and respects the display on/off config
 * toggle. Reuses EviLiveSuggestionTest's FakeClient/FakeWidget/suggestion() fixtures rather than
 * duplicating them.
 */
public class EviLiveHintTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

  static void set(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  public static void main(String[] args) throws Exception {
    EviLiveSuggestionTest.FakeClient fc = new EviLiveSuggestionTest.FakeClient();
    Client client = fc.proxy();
    GEOffer geOffer = new GEOffer();
    set(geOffer, "client", client);

    // -- GEOffer.openFieldFor / isPromptOpen: the same gate SuggestionKeybindHandler uses, so the
    // hint and the hotkey never disagree about what's eligible. --
    check(!geOffer.isPromptOpen(), "No GE slot open: prompt must not be considered open");
    check(geOffer.openFieldFor(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 65)) == GEOffer.PromptField.NONE, "No slot open: no field");

    fc.openSlotVarbit = 1;
    check(geOffer.isPromptOpen(), "Slot open with the quantity/price input type and both widgets present must be open");

    fc.currentItemId = 1;
    fc.chatboxTitle.text = "How many do you wish to buy?";
    check(geOffer.openFieldFor(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 65)) == GEOffer.PromptField.QUANTITY, "Matching quantity prompt");
    check(geOffer.openFieldFor(EviLiveSuggestionTest.suggestion(2, "buy", 100, 50, 65)) == GEOffer.PromptField.NONE, "Different item: no field");
    check(geOffer.openFieldFor(null) == GEOffer.PromptField.NONE, "Null suggestion: no field");
    // A quantity of 0 (a plain OpenItemPriceCache price, never a ranked suggestion -- see its own
    // class doc) must never offer QUANTITY: there's no track record to size one by.
    check(geOffer.openFieldFor(EviLiveSuggestionTest.openItemPrice(1, 50, 65)) == GEOffer.PromptField.NONE, "Matching item but zero quantity: no field on the quantity prompt");

    // Price prompt while buying resolves to BUY_PRICE regardless of the suggestion's own "action"
    // (a "sell"-labelled suggestion still has a usable buy price and must still offer it here).
    fc.chatboxTitle.text = "Set a price for each item:";
    fc.offerContainer.children.put(20, new EviLiveSuggestionTest.FakeWidget());
    fc.offerContainer.children.get(20).text = "Buy offer";
    check(geOffer.openFieldFor(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 65)) == GEOffer.PromptField.BUY_PRICE, "Buying resolves to BUY_PRICE");
    check(geOffer.openFieldFor(EviLiveSuggestionTest.suggestion(1, "sell", 100, 50, 65)) == GEOffer.PromptField.BUY_PRICE, "Buying resolves to BUY_PRICE even for an action:sell suggestion");
    check(geOffer.openFieldFor(EviLiveSuggestionTest.suggestion(1, "buy", 100, 0, 65)) == GEOffer.PromptField.NONE, "Buying with no usable buy price: no field");

    // Price prompt while selling the SAME item resolves to SELL_PRICE -- this is the case that was
    // missing before: a suggestion always carries both prices now, so selling an item you already
    // hold still gets a hint/fill, not just buying it.
    fc.offerCreationType = 1; // selling
    fc.offerContainer.children.get(20).text = "Sell offer";
    check(geOffer.openFieldFor(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 65)) == GEOffer.PromptField.SELL_PRICE, "Selling resolves to SELL_PRICE even for an action:buy suggestion");
    check(geOffer.openFieldFor(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 0)) == GEOffer.PromptField.NONE, "Selling with no usable sell price: no field");
    fc.offerCreationType = 0; // back to buying for the widget-lifecycle section below

    // -- SuggestionHintWidget: end-to-end create/update/clear using the same fake. --
    SuggestionCache cache = new SuggestionCache();
    EviLiveConfig enabled = (EviLiveConfig) Proxy.newProxyInstance(EviLiveConfig.class.getClassLoader(), new Class[]{EviLiveConfig.class},
      (p, m, a) -> {
        if ("suggestionKeybind".equals(m.getName())) return new ModifierlessKeybind(KeyEvent.VK_F8, 0);
        if ("showSuggestionHint".equals(m.getName())) return Boolean.TRUE;
        return null;
      });
    OpenItemPriceCache openItemPriceCache = new OpenItemPriceCache();
    SuggestionHintWidget hint = new SuggestionHintWidget();
    set(hint, "client", client);
    set(hint, "geOffer", geOffer);
    set(hint, "suggestionCache", cache);
    set(hint, "openItemPriceCache", openItemPriceCache);
    set(hint, "config", enabled);

    // Prompt already open (price, buy side, from above) but no suggestion cached yet.
    fc.offerContainer.children.get(20).text = "Buy offer";
    hint.update();
    check(fc.chatboxContainer.createdChildren.size() == 1, "Hint widget must be created as soon as the prompt is open");
    check(fc.chatboxContainer.createdChildren.get(0).text.isEmpty(), "No suggestion cached: hint widget must show no text");

    cache.set(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 65));
    hint.update();
    check(fc.chatboxContainer.createdChildren.size() == 1, "Exactly one hint child widget must be created per open prompt");
    String shownBuy = fc.chatboxContainer.createdChildren.get(0).text;
    check(shownBuy.contains("50") && shownBuy.toLowerCase().contains("buy"), "Buy-side hint text must name the suggested buy price: " + shownBuy);
    check(fc.chatboxTitle.originalY == 7, "Chatbox title must be nudged down exactly once on hint creation");

    // Updating again while still open must reuse the same child, not create a second one, and must
    // not nudge the title down again (that would drift it further every tick).
    hint.update();
    check(fc.chatboxContainer.createdChildren.size() == 1, "Hint widget must be reused across ticks, not recreated");
    check(fc.chatboxTitle.originalY == 7, "Repeated updates must not nudge the title down again");

    // Switching to the sell side of the same item, same open widget: text must switch to the sell
    // price, not stay on the buy price or go blank -- this is the exact behaviour the on-screen
    // hint was missing before.
    fc.offerCreationType = 1;
    fc.offerContainer.children.get(20).text = "Sell offer";
    hint.update();
    check(fc.chatboxContainer.createdChildren.size() == 1, "Switching sides must not create another child widget");
    String shownSell = fc.chatboxContainer.createdChildren.get(0).text;
    check(shownSell.contains("65") && shownSell.toLowerCase().contains("sell"), "Sell-side hint text must name the suggested sell price: " + shownSell);
    check(!shownSell.contains("LOSS"), "A buy-labelled suggestion with no loss data must not warn: " + shownSell);
    // A holding sell that would lose GP: the hint still offers the price (never blocked) but says so.
    Suggestion losing = EviLiveSuggestionTest.suggestion(1, "sell", 100, 50, 65);
    losing.lossIfSoldNow = 1100L; losing.breakEvenPrice = 1234;
    cache.set(losing);
    hint.update();
    String shownLoss = fc.chatboxContainer.createdChildren.get(0).text;
    check(shownLoss.contains("65") && shownLoss.contains("LOSS") && shownLoss.contains(String.format("%,d", 1234)), "A losing sell must still show its price, flagged with the break-even price: " + shownLoss);
    cache.set(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 65));
    fc.offerCreationType = 0;
    fc.offerContainer.children.get(20).text = "Buy offer";

    // Item changes to something the cached suggestion no longer matches: text must clear, but the
    // prompt is still open, so no extra child widget should appear.
    fc.currentItemId = 999;
    hint.update();
    check(fc.chatboxContainer.createdChildren.size() == 1, "A mismatch must not create another child widget");
    check(fc.chatboxContainer.createdChildren.get(0).text.isEmpty(), "A mismatch must clear the hint text");

    // -- Fallback to a plain open-item price when there's no ranked suggestion for this item at
    // all: this is what makes the hint work for ANY item being traded, not only EVI's current #1
    // pick (see GEOffer.resolveOpenSuggestion and OpenItemPriceCache's own class doc). --
    openItemPriceCache.set(EviLiveSuggestionTest.openItemPrice(999, 210, 240));
    hint.update();
    check(fc.chatboxContainer.createdChildren.size() == 1, "An open-item fallback must reuse the same hint widget, not create another one");
    String shownOpenItem = fc.chatboxContainer.createdChildren.get(0).text;
    check(shownOpenItem.contains("210") && shownOpenItem.toLowerCase().contains("buy"), "Open-item fallback must show its buy price when there's no ranked suggestion for this item: " + shownOpenItem);

    // The reported loss: a held Eclipse Moon chestplate (broken) sold through this fallback, which
    // never knew what the player paid, so the prompt offered 595,350 -- their own buy price -- with
    // no warning, and the sale lost exactly the tax. Parsed exactly as the bridge now sends it (see
    // withCostBasis in bridge/suggestions.mjs).
    fc.offerCreationType = 1;
    fc.offerContainer.children.get(20).text = "Sell offer";
    openItemPriceCache.set(new com.google.gson.Gson().fromJson(
      "{\"itemId\":999,\"buyPrice\":595350,\"sellPrice\":595350,\"action\":\"sell\",\"breakEvenPrice\":607499,\"lossIfSoldNow\":11907}", Suggestion.class));
    hint.update();
    String shownHeldLoss = fc.chatboxContainer.createdChildren.get(0).text;
    check(shownHeldLoss.contains("LOSS") && shownHeldLoss.contains(String.format("%,d", 607499)), "Selling a held item below its break-even through the open-item fallback must warn with the break-even: " + shownHeldLoss);
    check(shownHeldLoss.contains(String.format("%,d", 595350)), "A warning, never a block: the price is still offered: " + shownHeldLoss);
    // And the same fallback with no cost basis (an item the player does not hold) stays silent.
    openItemPriceCache.set(EviLiveSuggestionTest.openItemPrice(999, 595350, 595350));
    hint.update();
    check(!fc.chatboxContainer.createdChildren.get(0).text.contains("LOSS"), "With no known cost there is nothing to warn about, and nothing is guessed");
    fc.offerCreationType = 0;
    fc.offerContainer.children.get(20).text = "Buy offer";
    openItemPriceCache.set(null);

    // Prompt closes entirely, then a fresh one opens: must drop the old widget reference and
    // create a genuinely new one rather than reusing a widget from a torn-down chatbox.
    fc.openSlotVarbit = 0;
    hint.update();
    fc.currentItemId = 1;
    fc.openSlotVarbit = 1;
    cache.set(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 65));
    hint.update();
    check(fc.chatboxContainer.createdChildren.size() == 2, "A fresh prompt-open must get a fresh hint widget");
    check(fc.chatboxContainer.createdChildren.get(1).text.contains("50"), "The fresh widget must show the current suggestion");

    // The display toggle, off: must clear existing text and never show anything, even with a
    // matching suggestion and an open prompt.
    EviLiveConfig disabled = (EviLiveConfig) Proxy.newProxyInstance(EviLiveConfig.class.getClassLoader(), new Class[]{EviLiveConfig.class},
      (p, m, a) -> "showSuggestionHint".equals(m.getName()) ? Boolean.FALSE : new ModifierlessKeybind(KeyEvent.VK_F8, 0));
    set(hint, "config", disabled);
    hint.update();
    check(fc.chatboxContainer.createdChildren.stream().allMatch(w -> w.text.isEmpty()), "Toggling the hint off must clear any shown text");

    System.out.println("PASS: prompt-open gating, buy/sell price resolution independent of action, zero-quantity never offers QUANTITY, create-once-per-open, side-switch updates text, mismatch clears without leaking widgets, open-item price fallback for a non-ranked item, fresh-open replacement, and the display toggle");
  }
}
