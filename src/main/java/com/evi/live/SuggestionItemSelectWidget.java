package com.evi.live;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.VarClientStr;
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
 *
 * Placement, and living next to other plugins (2026-09-20): the row sits one row BELOW the top of
 * the results area, not on it. The top row is where the game shows its own "previous search" line
 * and where Flipping Copilot draws its "Copilot item" row -- at exactly the position this row used
 * to occupy, and into child slots 0-3, which it writes by index. With both plugins running, whichever
 * drew last covered the other, and Copilot's indexed writes could replace this row's widgets outright,
 * so EVI's row simply vanished while Copilot had a suggestion. Now:
 *   * the row is always at ROW_Y, directly under the top line, with or without Copilot, so it is in
 *     the same place either way and never hides the game's previous-search line or anyone's row;
 *   * its three widgets are created at fixed child slots from CHILD_BASE, far past the handful the
 *     top line uses, instead of appended wherever the list happens to end;
 *   * every tick it checks those slots still hold its own widgets and recreates them if not -- the
 *     game rebuilds the list when the player types or clears the search, and anything else writing
 *     into those slots would otherwise leave this holding detached widgets and showing nothing;
 *   * while the player is typing a search, the live results own that space, so the row is hidden
 *     (the same rule Copilot's row follows) and comes back when the search box is empty again.
 */
@Singleton
class SuggestionItemSelectWidget {
  // The exact script ID and argument values Flipping Copilot's own published, Plugin-Hub-approved
  // source wires onto a genuine GE search-result row -- see this class's own doc for the source
  // and why their deeper meaning wasn't independently decoded, only faithfully reproduced.
  private static final int GE_SELECT_SEARCH_ITEM_SCRIPT = 754;
  private static final int GE_SELECT_ON_OP_ARG = 84;
  private static final int GE_SELECT_ON_KEY_ARG = -2147483640;

  private static final int ROW_X = 114, ROW_Y = 32, ROW_WIDTH = 256, ROW_HEIGHT = 32;
  private static final int ICON_X = 118, ICON_Y = ROW_Y + 6, ICON_SIZE = 20;
  // Fixed child slots for the row, text and icon: well past the top line's own slots (the game's
  // previous-search widgets, and Copilot's, use 0-3), so neither can overwrite them by index.
  private static final int CHILD_BASE = 60;
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
    // Typing: the live results use this space. Hide rather than recreate; the game rebuilds the
    // list as the player types, and the attachment check below restores the row afterwards.
    String typed = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
    if (typed != null && !typed.isEmpty()) {
      if (attached(results)) setHidden(true);
      return;
    }
    if (!attached(results)) {
      clear();
      if (!create(results)) return;
    }
    setHidden(false);
    if (shownForItemId != s.itemId) apply(s.itemId, s.name);
  }

  // True only while all three widgets this holds are still the ones in their slots -- false after the
  // game rebuilt the list, or anything else wrote into those slots.
  private boolean attached(Widget parent) {
    if (row == null) return false;
    try {
      return parent.getChild(CHILD_BASE) == row && parent.getChild(CHILD_BASE + 1) == text && parent.getChild(CHILD_BASE + 2) == icon;
    } catch (Exception ex) {
      return false;
    }
  }

  private void setHidden(boolean hidden) {
    for (Widget w : new Widget[] {row, text, icon}) if (w != null && w.isSelfHidden() != hidden) w.setHidden(hidden);
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
      row = parent.createChild(CHILD_BASE, WidgetType.RECTANGLE);
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

      text = parent.createChild(CHILD_BASE + 1, WidgetType.TEXT);
      text.setTextColor(EviTheme.BRAND_RGB);
      text.setOriginalX(TEXT_X);
      text.setOriginalY(ROW_Y);
      text.setOriginalWidth(TEXT_WIDTH);
      text.setOriginalHeight(ROW_HEIGHT);
      text.setYTextAlignment(1);

      icon = parent.createChild(CHILD_BASE + 2, WidgetType.GRAPHIC);
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
