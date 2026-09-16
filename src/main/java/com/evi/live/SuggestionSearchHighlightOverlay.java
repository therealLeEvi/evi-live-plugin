package com.evi.live;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.api.Client;

/**
 * Display-only help finding EVI's currently suggested item in the Grand Exchange's item-search
 * results list (the list shown once you've clicked the search icon and started typing): a
 * translucent rectangle over the suggestion's own row, matched by item ID. Same visual technique
 * a currently published RuneLite Hub plugin (Flipping Copilot's
 * WidgetHighlightOverlay/HighlightController.highlightItemInSearch) uses for its own item-search
 * highlight, read directly from its published source.
 *
 * An earlier version of this class also painted an "EVI suggests: <name>" label of its own near
 * the offer-setup panel, through several repositioning attempts (see UPDATE-NOTES.md's dated
 * entries) that never quite landed reliably -- that display-only row was removed entirely.
 * SuggestionItemSelectWidget now covers that same job properly instead: a real, clickable widget
 * placed inside the GE's own item-search results list (matching where Flipping Copilot's own
 * "Copilot item: <icon> <name>" row actually lives and behaves, confirmed against its published
 * source), not a painted guess at pixel coordinates.
 *
 * This overlay itself never adds a click handler or touches the widget tree -- cannot select,
 * confirm, or fill anything by itself; picking the item is up to you, or the click-to-jump row in
 * SuggestionItemSelectWidget. Governed by the same showSuggestionHint config toggle as the chatbox
 * hint line and that widget.
 */
@Singleton
final class SuggestionSearchHighlightOverlay extends Overlay {
  // Shared with the sidebar panel and every other overlay via EviTheme, instead of a locally
  // hand-rolled color -- see EviTheme's own class doc for why.
  private static final Color HIGHLIGHT = EviTheme.brand(90);

  @Inject private Client client;
  @Inject private GEOffer geOffer;
  @Inject private SuggestionCache suggestionCache;
  @Inject private EviLiveConfig config;

  @Inject
  SuggestionSearchHighlightOverlay() {
    setPosition(OverlayPosition.DYNAMIC);
    setLayer(OverlayLayer.ABOVE_WIDGETS);
  }

  @Override
  public Dimension render(Graphics2D graphics) {
    if (!config.showSuggestionHint() || !geOffer.isSlotOpen() || geOffer.isPromptOpen()) return null;
    Suggestion s = suggestionCache.get();
    // Nothing left to point at once the suggested item is actually the one selected -- the
    // quantity/price prompt (and SuggestionHintWidget) takes over from here.
    if (s == null || geOffer.currentItemId() == s.itemId) return null;
    Widget row = findSuggestionRow(s.itemId);
    if (row != null) {
      Rectangle bounds = row.getBounds();
      if (bounds != null && !bounds.isEmpty()) {
        graphics.setColor(HIGHLIGHT);
        graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
      }
    }
    return null;
  }

  // The GE's own item-search results list; a distinct widget group from the quantity/price
  // chatbox prompt SuggestionHintWidget/GEOffer work with, only populated/visible while the
  // search box is actually open and showing matches.
  private Widget findSuggestionRow(int itemId) {
    Widget results = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
    if (results == null || results.isHidden()) return null;
    Widget[] children = results.getDynamicChildren();
    if (children == null) return null;
    for (Widget child : children) {
      if (child != null && !child.isHidden() && child.getItemId() == itemId) return child;
    }
    return null;
  }
}
