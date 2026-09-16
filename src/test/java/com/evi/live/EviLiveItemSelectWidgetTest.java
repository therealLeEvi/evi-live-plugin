package com.evi.live;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;

/**
 * Fake widget/client only; never launches a game client and never dispatches a real keyboard or
 * mouse event -- setOnOpListener/setOnKeyListener calls are captured as plain data
 * (EviLiveSuggestionTest.FakeWidget.onOpArgs/onKeyArgs), never invoked. Covers
 * SuggestionItemSelectWidget: the same visibility gate the row highlight and hint widget share (the
 * display toggle, a GE slot open, NOT already at the quantity/price prompt, a cached suggestion,
 * the suggested item not already selected), plus its own gate on the item-search results widget
 * actually existing (search genuinely open). Covers that update() creates exactly three child
 * widgets (a clickable backdrop, a text label, an item icon) wired with the exact script/argument
 * values Flipping Copilot's own published source uses for its equivalent row (see the widget's own
 * class doc for where that came from and why), that repeated updates for the same item reuse those
 * three widgets rather than creating duplicates, that a changed suggested item updates them in
 * place (still exactly three), and that every gating condition drops the widget references without
 * creating anything. Reuses EviLiveSuggestionTest's FakeClient/FakeWidget/suggestion() fixtures.
 */
public class EviLiveItemSelectWidgetTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

  static void set(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  public static void main(String[] args) throws Exception {
    EviLiveSuggestionTest.FakeClient fc = new EviLiveSuggestionTest.FakeClient();
    fc.inputType = 0; // item-picking phase, not the quantity/price prompt
    Client client = fc.proxy();
    GEOffer geOffer = new GEOffer();
    set(geOffer, "client", client);
    SuggestionCache cache = new SuggestionCache();
    EviLiveConfig enabled = (EviLiveConfig) Proxy.newProxyInstance(EviLiveConfig.class.getClassLoader(), new Class[]{EviLiveConfig.class},
      (p, m, a) -> "showSuggestionHint".equals(m.getName()) ? Boolean.TRUE : null);

    SuggestionItemSelectWidget widget = new SuggestionItemSelectWidget();
    set(widget, "client", client);
    set(widget, "geOffer", geOffer);
    set(widget, "suggestionCache", cache);
    set(widget, "config", enabled);

    // -- Gating: none of these must create anything. --
    widget.update();
    check(fc.searchResults == null, "sanity: no search-results widget wired up yet");

    fc.searchResults = new EviLiveSuggestionTest.FakeWidget();
    widget.update();
    check(fc.searchResults.createdChildren.isEmpty(), "No GE slot open: nothing must be created");

    fc.openSlotVarbit = 1;
    widget.update();
    check(fc.searchResults.createdChildren.isEmpty(), "No cached suggestion: nothing must be created");

    cache.set(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 65));
    fc.inputType = 7; // the real quantity/price prompt: already past item-picking
    widget.update();
    check(fc.searchResults.createdChildren.isEmpty(), "Already at the quantity/price prompt: nothing must be created");
    fc.inputType = 0;

    fc.currentItemId = 1; // the suggested item is already the one selected
    widget.update();
    check(fc.searchResults.createdChildren.isEmpty(), "Suggested item already selected: nothing left to point at, nothing created");
    fc.currentItemId = -1;

    EviLiveSuggestionTest.FakeWidget savedResults = fc.searchResults;
    fc.searchResults = null;
    widget.update();
    check(savedResults.createdChildren.isEmpty(), "No search-results widget (search not open yet): nothing must be created");
    fc.searchResults = savedResults;

    EviLiveConfig disabled = (EviLiveConfig) Proxy.newProxyInstance(EviLiveConfig.class.getClassLoader(), new Class[]{EviLiveConfig.class},
      (p, m, a) -> Boolean.FALSE);
    set(widget, "config", disabled);
    widget.update();
    check(fc.searchResults.createdChildren.isEmpty(), "Display toggle off: nothing must be created");
    set(widget, "config", enabled);

    // -- The actual creation, with everything eligible. --
    widget.update();
    check(fc.searchResults.createdChildren.size() == 3, "Must create exactly three child widgets: a clickable row, a text label, and an item icon");
    EviLiveSuggestionTest.FakeWidget row = fc.searchResults.createdChildren.get(0);
    EviLiveSuggestionTest.FakeWidget text = fc.searchResults.createdChildren.get(1);
    EviLiveSuggestionTest.FakeWidget icon = fc.searchResults.createdChildren.get(2);
    check(row.onOpArgs != null && row.onOpArgs.length == 3 && (int) row.onOpArgs[0] == 754 && (int) row.onOpArgs[1] == 1 && (int) row.onOpArgs[2] == 84,
      "The clickable row must be wired with the exact script/itemId/arg triple a genuine GE search-result row carries (on click)");
    check(row.onKeyArgs != null && row.onKeyArgs.length == 3 && (int) row.onKeyArgs[0] == 754 && (int) row.onKeyArgs[1] == 1 && (int) row.onKeyArgs[2] == -2147483640,
      "The clickable row must be wired with the exact script/itemId/arg triple a genuine GE search-result row carries (on Enter/key-select)");
    check(text.text != null && text.text.contains("EVI item:") && text.text.contains("Test item"), "The label must name the suggested item: " + text.text);
    check(icon.itemId == 1, "The icon must be the suggested item's own icon");

    // -- Reuse: updating again for the SAME item must not create more widgets. --
    widget.update();
    check(fc.searchResults.createdChildren.size() == 3, "Repeated updates for the same item must reuse the same three widgets, not duplicate them");

    // -- Item change while still open: still exactly three widgets, but updated to the new item. --
    cache.set(EviLiveSuggestionTest.suggestion(2, "buy", 50, 200, 260));
    widget.update();
    check(fc.searchResults.createdChildren.size() == 3, "A changed suggested item must update the existing three widgets, not create new ones");
    check(row.onOpArgs != null && (int) row.onOpArgs[1] == 2, "The row's click target must switch to the new suggested item");
    check(icon.itemId == 2, "The icon must switch to the new suggested item");

    // -- Clearing: once the item becomes the one selected, references are dropped -- a later fresh
    // eligibility (a different, not-yet-selected suggestion) must create a fresh set of widgets. --
    fc.currentItemId = 2;
    widget.update();
    check(fc.searchResults.createdChildren.size() == 3, "Selecting the item must not itself create anything more");
    fc.currentItemId = -1;
    cache.set(EviLiveSuggestionTest.suggestion(3, "buy", 20, 400, 480));
    widget.update();
    check(fc.searchResults.createdChildren.size() == 6, "After the widget was cleared (item selected), a new eligible suggestion must create a fresh set of widgets");

    System.out.println("PASS: gating on the display toggle, GE-slot-open, the quantity/price prompt, a cached suggestion, an already-selected item, and the search-results widget existing; exactly-three-widget creation wired with Flipping Copilot's own verified script/argument values; reuse across repeated updates and item changes; and fresh creation after a clear");
  }
}
