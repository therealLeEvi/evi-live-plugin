package com.evi.live;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;

/**
 * Fake widget/client only; never launches a game client. Covers SuggestionSearchHighlightOverlay:
 * finds the suggested item's row in the GE's own item-search results list by item ID (skipping a
 * missing or hidden results widget, a non-matching child, or a matching-but-hidden child); that
 * render() itself respects the display toggle, the GE-slot-open gate, a cached suggestion, NOT
 * already being at the quantity/price prompt, and the suggested item not already being the one
 * selected. The clickable "EVI item: <icon> <name>" jump-to-item row lives in
 * SuggestionItemSelectWidget instead (see EviLiveItemSelectWidgetTest), not this overlay -- this
 * overlay only ever paints a translucent highlight, never creates a real widget or a click
 * listener. Reuses EviLiveSuggestionTest's FakeClient/FakeWidget fixtures rather than duplicating
 * them.
 */
public class EviLiveSearchHighlightTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

  static void set(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  static boolean painted(BufferedImage img, int x, int y) { return (img.getRGB(x, y) >>> 24) != 0; }

  public static void main(String[] args) throws Exception {
    EviLiveSuggestionTest.FakeClient fc = new EviLiveSuggestionTest.FakeClient();
    // Not the numeric quantity/price chatbox input type -- represents the item-picking phase,
    // before the prompt SuggestionHintWidget handles. The fixture's own default (7) is that
    // prompt's input type, so it must be changed here or isPromptOpen() reads true immediately.
    fc.inputType = 0;
    Client client = fc.proxy();
    GEOffer geOffer = new GEOffer();
    set(geOffer, "client", client);
    SuggestionCache cache = new SuggestionCache();
    EviLiveConfig enabled = (EviLiveConfig) Proxy.newProxyInstance(EviLiveConfig.class.getClassLoader(), new Class[]{EviLiveConfig.class},
      (p, m, a) -> "showSuggestionHint".equals(m.getName()) ? Boolean.TRUE : null);

    SuggestionSearchHighlightOverlay overlay = new SuggestionSearchHighlightOverlay();
    set(overlay, "client", client);
    set(overlay, "geOffer", geOffer);
    set(overlay, "suggestionCache", cache);
    set(overlay, "config", enabled);

    Method findRow = SuggestionSearchHighlightOverlay.class.getDeclaredMethod("findSuggestionRow", int.class);
    findRow.setAccessible(true);

    // -- findSuggestionRow: the lookup itself. --
    check(findRow.invoke(overlay, 1) == null, "No search-results widget at all: nothing to highlight");

    fc.searchResults = new EviLiveSuggestionTest.FakeWidget();
    fc.searchResults.hidden = true;
    check(findRow.invoke(overlay, 1) == null, "Hidden search-results widget: nothing to highlight");

    fc.searchResults.hidden = false;
    EviLiveSuggestionTest.FakeWidget other = new EviLiveSuggestionTest.FakeWidget();
    other.itemId = 999;
    fc.searchResults.dynamicChildren.add(other);
    check(findRow.invoke(overlay, 1) == null, "No child matches the item ID: nothing to highlight");

    EviLiveSuggestionTest.FakeWidget match = new EviLiveSuggestionTest.FakeWidget();
    match.itemId = 1;
    match.bounds = new Rectangle(5, 10, 40, 32);
    fc.searchResults.dynamicChildren.add(match);
    check(findRow.invoke(overlay, 1) != null, "A visible, matching child must be found");

    match.hidden = true;
    check(findRow.invoke(overlay, 1) == null, "A matching but hidden child must not be used");
    match.hidden = false;

    // -- render(): the config toggle, the GE-slot-open gate, a cached suggestion, not already at
    // the prompt, and the item not already selected, end to end (the row highlight only). --
    fc.openSlotVarbit = 1; // GE slot open, required by the overlay's own gate
    cache.set(EviLiveSuggestionTest.suggestion(1, "buy", 100, 50, 65));
    BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
    overlay.render(img.createGraphics());
    check(painted(img, 10, 15), "A matching, visible row must actually be painted onto the overlay's graphics");

    fc.openSlotVarbit = 0;
    BufferedImage noSlot = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
    overlay.render(noSlot.createGraphics());
    check(!painted(noSlot, 10, 15), "No GE slot open: nothing must be painted even with a matching row cached");
    fc.openSlotVarbit = 1;

    fc.inputType = 7; // the real quantity/price chatbox input type: already past item-picking
    BufferedImage promptOpen = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
    overlay.render(promptOpen.createGraphics());
    check(!painted(promptOpen, 10, 15), "Already at the quantity/price prompt: the row highlight must not paint (SuggestionHintWidget's job now)");
    fc.inputType = 0;

    fc.currentItemId = 1; // the suggested item is already the one selected
    BufferedImage alreadySelected = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
    overlay.render(alreadySelected.createGraphics());
    check(!painted(alreadySelected, 10, 15), "Suggested item already selected: nothing left to point at, must not paint");
    fc.currentItemId = -1;

    EviLiveConfig disabled = (EviLiveConfig) Proxy.newProxyInstance(EviLiveConfig.class.getClassLoader(), new Class[]{EviLiveConfig.class},
      (p, m, a) -> "showSuggestionHint".equals(m.getName()) ? Boolean.FALSE : null);
    set(overlay, "config", disabled);
    BufferedImage toggledOff = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
    overlay.render(toggledOff.createGraphics());
    check(!painted(toggledOff, 10, 15), "Display toggle off: nothing must be painted");
    set(overlay, "config", enabled);

    cache.set(null);
    BufferedImage noSuggestion = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
    overlay.render(noSuggestion.createGraphics());
    check(!painted(noSuggestion, 10, 15), "No cached suggestion: nothing must be painted");

    System.out.println("PASS: search-results row lookup by item ID (missing/hidden widget, no match, hidden match), render() gating on the display toggle, GE-slot-open, a cached suggestion, the quantity/price prompt, and an already-selected item");
  }
}
