package com.evi.live;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

/**
 * Shows a one-line text hint inside the open GE quantity/price prompt naming EVI's suggested
 * value and the hotkey that fills it, e.g. "EVI: press F8 for buy price 1,703 gp" while buying, or
 * "EVI: press F8 for sell price 1,850 gp" while selling the same item -- the same
 * technique (and the same CHATBOX_CONTAINER child widget, same position/size mode, same
 * chatbox-title nudge) a currently published RuneLite Hub plugin (Flipping Copilot's
 * OfferEditor.java) uses for its own price/quantity hint text. Display only: no setAction, no
 * setOnOpListener, no click handler of any kind, so this widget cannot itself fill, confirm, or
 * submit anything -- filling is still exclusively the hotkey in SuggestionKeybindHandler.
 *
 * Resolves which suggestion to show via GEOffer.resolveOpenSuggestion(suggestionCache,
 * openItemPriceCache): EVI's own ranked/personalized pick when it's the item currently open,
 * otherwise a plain live-market price for whatever item IS open regardless of ranking or flip
 * history (see OpenItemPriceCache's own class doc) -- this is what lets the hint (and the hotkey)
 * work for any item you're actually buying or selling, not only EVI's current #1 suggestion.
 */
@Singleton
class SuggestionHintWidget {
  // Shared with the sidebar panel and every other overlay via EviTheme, instead of a locally
  // hand-rolled literal -- see EviTheme's own class doc for why.
  private static final int TEXT_COLOR = EviTheme.BRAND_RGB;
  private static final int Y_OFFSET = 40;
  private static final int X_OFFSET = 10;
  private static final int TITLE_NUDGE = 7; // matches the reference plugin's own chatbox-title shift

  @Inject private Client client;
  @Inject private GEOffer geOffer;
  @Inject private SuggestionCache suggestionCache;
  @Inject private OpenItemPriceCache openItemPriceCache;
  @Inject private EviLiveConfig config;

  private Widget hint;

  /** Called once per game tick (client thread only) from EviLivePlugin.onGameTick. */
  void update() {
    if (!config.showSuggestionHint() || !geOffer.isPromptOpen()) {
      clear();
      return;
    }
    Widget parent = client.getWidget(ComponentID.CHATBOX_CONTAINER);
    if (parent == null) {
      clear();
      return;
    }
    if (hint == null) {
      hint = create(parent);
      if (hint == null) return;
    }
    Suggestion s = geOffer.resolveOpenSuggestion(suggestionCache.get(), openItemPriceCache.get());
    GEOffer.PromptField field = geOffer.openFieldFor(s);
    hint.setText(field == GEOffer.PromptField.NONE ? "" : label(field, s));
    hint.revalidate();
  }

  /** Drops the hint widget reference; called when the prompt closes and on plugin shutdown. */
  void clear() {
    if (hint == null) return;
    try { hint.setText(""); hint.revalidate(); } catch (Exception ignored) { }
    hint = null;
  }

  private Widget create(Widget parent) {
    try {
      Widget widget = parent.createChild(-1, WidgetType.TEXT);
      widget.setTextColor(TEXT_COLOR);
      widget.setFontId(FontID.VERDANA_11_BOLD);
      widget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
      widget.setOriginalX(X_OFFSET);
      widget.setOriginalY(Y_OFFSET);
      widget.setOriginalHeight(20);
      widget.setXTextAlignment(WidgetTextAlignment.LEFT);
      widget.setWidthMode(WidgetSizeMode.MINUS);
      widget.setHasListener(false);
      widget.revalidate();
      nudgeTitleDown();
      return widget;
    } catch (Exception ex) {
      return null;
    }
  }

  // Shifts the chatbox question text down once per fresh prompt-open, the same amount and for the
  // same reason as the reference plugin: our hint line sits where the question text would
  // otherwise overlap it. Only called from create(), never from update(), so it never accumulates.
  private void nudgeTitleDown() {
    Widget title = client.getWidget(ComponentID.CHATBOX_TITLE);
    if (title == null) return;
    title.setOriginalY(title.getOriginalY() + TITLE_NUDGE);
    title.revalidate();
  }

  private String label(GEOffer.PromptField field, Suggestion s) {
    String key = keyName();
    switch (field) {
      case QUANTITY: return "EVI: press " + key + " for quantity " + format(s.quantity);
      case BUY_PRICE: return "EVI: press " + key + " for buy price " + format(s.buyPrice) + " gp";
      case SELL_PRICE: return "EVI: press " + key + " for sell price " + format(s.sellPrice) + " gp";
      default: return "";
    }
  }

  private String keyName() {
    net.runelite.client.config.Keybind kb = config.suggestionKeybind();
    String s = kb == null ? null : kb.toString();
    return (s == null || s.isEmpty() || "Not set".equalsIgnoreCase(s)) ? "hotkey" : s;
  }

  private static String format(int value) {
    return String.format("%,d", value);
  }
}
