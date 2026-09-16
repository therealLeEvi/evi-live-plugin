package com.evi.live;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/**
 * Adds a clickable "EVI item: <icon> <name>" row inside the GE's own item-search results widget
 * while EVI's suggested item isn't yet the one selected -- clicking it (or pressing Enter while
 * it's highlighted) jumps straight to that item, the same one-click behaviour a currently
 * published, Plugin-Hub-approved RuneLite plugin (Flipping Copilot) ships today.
 *
 * This project previously declined to build this: the fix available at the time (either an
 * unverified ScriptID.GE_ITEM_SEARCH call, or dispatching synthetic KeyEvents into the game's own
 * input pipeline to fake real typing) looked, on the information available then, like it crossed
 * into the kind of input-automation OSRS's rules are cautious about, and the player's own read on
 * that was accepted rather than argued with. What changed: Flipping Copilot's actual source
 * (github.com/cbrewitt/flipping-copilot, specifically
 * controller/GePreviousSearch.java -- fetched via GitHub's REST API, api.github.com/repos/..., a
 * route that worked here where a plain raw.githubusercontent.com/GitHub-web fetch of this same
 * fork had been blocked earlier in this project) shows its equivalent row uses neither of those
 * two paths. It does this instead:
 *
 *   widget.setOnOpListener(754, itemId, 84);
 *   widget.setOnKeyListener(754, itemId, -2147483640);
 *
 * -- RuneLite's own documented widget action-listener API (Widget.setOnOpListener /
 * setOnKeyListener, the exact mechanism every interactive RuneLite plugin already uses for its own
 * clickable buttons, including this plugin's sidebar buttons and Copilot's own OfferEditor.java
 * hint text) wired with the SAME script ID (754) and argument shape a genuine GE search-result row
 * already carries. Clicking (or pressing Enter on) this widget runs the game's own real
 * "select this GE search item" script exactly as a real click on a real row would -- it is a
 * widget click through RuneLite's sanctioned plugin-widget API, not a synthesized keyboard/mouse
 * hardware event injected into the client's input pipeline (the technique this project actually
 * declined). The deeper meaning of script 754's own internals, or of the literal 84 / -2147483640
 * argument values, was not independently decoded -- they're reproduced here exactly as Copilot's
 * own shipped, approved code uses them, which is what makes this a faithful replication of a
 * precedented, accepted technique rather than a guess at undocumented behaviour.
 *
 * Three child widgets are created under ComponentID.CHATBOX_GE_SEARCH_RESULTS, mirroring Copilot's
 * own composition (their version splits the text into two widgets and reuses the game's own
 * "previous search" convenience row when available; this version always creates its own three
 * fresh children -- a clickable backdrop rectangle, one combined "EVI item: <name>" text, and the
 * item's own icon -- for a single, simpler, always-available code path). Created once per search
 * session (tracked by `row != null`) and updated in place (text/icon/listener args only) when the
 * suggested item changes while search stays open, rather than recreated -- RuneLite's Widget API
 * has no "delete child" call, so avoiding re-creation is how this avoids leaving stale duplicate
 * rows behind. Dropped (references nulled, nothing more to do -- RuneLite tears the real widgets
 * down itself once the chatbox closes) the moment the gate no longer holds: display toggle off, no
 * GE slot open, already past item search (the quantity/price prompt is open), no suggestion cached,
 * the suggested item already selected, or the search-results widget itself isn't present (search
 * not actually open -- this only ever applies while buying, since selling never goes through item
 * search at all, so this naturally never fires there without needing its own check for it).
 * Governed by the same showSuggestionHint config toggle as the hint text and search highlight.
 */
@Singleton
class SuggestionItemSelectWidget {
  // The exact script ID and argument values Flipping Copilot's own published, Plugin-Hub-approved
  // source wires onto a genuine GE search-result row -- see this class's own doc for the source
  // and why their deeper meaning wasn't independently decoded, only faithfully reproduced.
  private static final int GE_SELECT_SEARCH_ITEM_SCRIPT = 754;
  private static final int GE_SELECT_ON_OP_ARG = 84;
  private static final int GE_SELECT_ON_KEY_ARG = -2147483640;

  private static final int ROW_X = 114, ROW_Y = 0, ROW_WIDTH = 256, ROW_HEIGHT = 32;
  private static final int ICON_X = 118, ICON_Y = 6, ICON_SIZE = 20;
  private static final int TEXT_X = 144, TEXT_WIDTH = 224;

  @Inject private Client client;
  @Inject private GEOffer geOffer;
  @Inject private SuggestionCache suggestionCache;
  @Inject private EviLiveConfig config;

  private Widget row, text, icon;
  private int shownForItemId = -1;

  /** Called once per game tick (client thread only) from EviLivePlugin.onGameTick. */
  void update() {
    if (!config.showSuggestionHint() || !geOffer.isSlotOpen() || geOffer.isPromptOpen()) {
      clear();
      return;
    }
    Suggestion s = suggestionCache.get();
    if (s == null || geOffer.currentItemId() == s.itemId) {
      clear();
      return;
    }
    Widget results = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
    if (results == null) {
      clear();
      return;
    }
    if (row == null && !create(results)) return;
    if (shownForItemId != s.itemId) apply(s.itemId, s.name);
  }

  /** Drops the widget references; called on mismatch/close and on plugin shutdown. Never attempts
   *  to destroy the real widgets themselves -- RuneLite's Widget API has no such call, and they're
   *  torn down by the client itself once the chatbox closes. */
  void clear() {
    row = text = icon = null;
    shownForItemId = -1;
  }

  private boolean create(Widget parent) {
    try {
      row = parent.createChild(-1, WidgetType.RECTANGLE);
      row.setFilled(true);
      row.setOpacity(255);
      row.setOriginalX(ROW_X);
      row.setOriginalY(ROW_Y);
      row.setOriginalWidth(ROW_WIDTH);
      row.setOriginalHeight(ROW_HEIGHT);
      row.setHasListener(true);
      row.setAction(0, "Select");
      row.setOnMouseOverListener((JavaScriptCallback) ev -> row.setOpacity(200));
      row.setOnMouseLeaveListener((JavaScriptCallback) ev -> row.setOpacity(255));

      text = parent.createChild(-1, WidgetType.TEXT);
      text.setTextColor(EviTheme.BRAND_RGB);
      text.setOriginalX(TEXT_X);
      text.setOriginalY(ROW_Y);
      text.setOriginalWidth(TEXT_WIDTH);
      text.setOriginalHeight(ROW_HEIGHT);
      text.setYTextAlignment(1);

      icon = parent.createChild(-1, WidgetType.GRAPHIC);
      icon.setItemQuantity(1);
      icon.setOriginalX(ICON_X);
      icon.setOriginalY(ICON_Y);
      icon.setOriginalWidth(ICON_SIZE);
      icon.setOriginalHeight(ICON_SIZE);
      return true;
    } catch (Exception ex) {
      row = text = icon = null;
      return false;
    }
  }

  private void apply(int itemId, String name) {
    row.setName("<col=53cdb4>" + name + "</col>");
    row.setOnOpListener(GE_SELECT_SEARCH_ITEM_SCRIPT, itemId, GE_SELECT_ON_OP_ARG);
    row.setOnKeyListener(GE_SELECT_SEARCH_ITEM_SCRIPT, itemId, GE_SELECT_ON_KEY_ARG);
    row.revalidate();
    text.setText("EVI item: " + name);
    text.revalidate();
    icon.setItemId(itemId);
    icon.revalidate();
    shownForItemId = itemId;
  }
}
