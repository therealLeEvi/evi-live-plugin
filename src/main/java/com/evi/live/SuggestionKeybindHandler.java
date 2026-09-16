package com.evi.live;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.VarClientStr;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;

/**
 * Fills the currently-open GE quantity or price prompt with the cached suggestion, using the same
 * two-line widget/varclient-string write a currently published RuneLite Hub plugin (Flipping
 * Copilot) uses for the same purpose, and the same pattern RuneLite's own built-in GE plugin uses
 * for its 25%/50%/100% quantity-preset buttons. Never touches the Confirm button, never sends a
 * menu action, never selects an item, never types anything outside the single field already open.
 *
 * Resolves which suggestion to fill via GEOffer.resolveOpenSuggestion(suggestionCache,
 * openItemPriceCache) -- see SuggestionHintWidget's own class doc for why: this makes the hotkey
 * work for any item currently open, not only EVI's own top-ranked pick, using exactly the same
 * eligibility gate the hint widget uses so the hotkey never fills a value the player wasn't also
 * shown first.
 */
@Singleton
class SuggestionKeybindHandler {
  private final KeyManager keyManager;
  private final ClientThread clientThread;
  private final Client client;
  private final GEOffer geOffer;
  private final SuggestionCache suggestionCache;
  private final OpenItemPriceCache openItemPriceCache;
  private final EviLiveConfig config;

  private final KeyListener listener = new KeyListener() {
    @Override public void keyTyped(KeyEvent e) { }
    @Override public void keyReleased(KeyEvent e) { }
    @Override public void keyPressed(KeyEvent e) {
      if (e.getKeyCode() == KeyEvent.VK_ENTER) return; // Enter already submits the field; never intercept it.
      if (!config.suggestionKeybind().matches(e)) return;
      clientThread.invokeLater(SuggestionKeybindHandler.this::fill);
    }
  };

  @Inject
  SuggestionKeybindHandler(KeyManager keyManager, ClientThread clientThread, Client client, GEOffer geOffer, SuggestionCache suggestionCache, OpenItemPriceCache openItemPriceCache, EviLiveConfig config) {
    this.keyManager = keyManager;
    this.clientThread = clientThread;
    this.client = client;
    this.geOffer = geOffer;
    this.suggestionCache = suggestionCache;
    this.openItemPriceCache = openItemPriceCache;
    this.config = config;
  }

  void register() { keyManager.registerKeyListener(listener); }
  void unregister() { keyManager.unregisterKeyListener(listener); }

  private void fill() {
    // Same eligibility gate the on-screen hint widget uses to decide what to show, so the hotkey
    // never fills a value the player wasn't also shown first.
    Suggestion s = geOffer.resolveOpenSuggestion(suggestionCache.get(), openItemPriceCache.get());
    switch (geOffer.openFieldFor(s)) {
      case QUANTITY: fillValue(s.quantity); break;
      case BUY_PRICE: fillValue(s.buyPrice); break;
      case SELL_PRICE: fillValue(s.sellPrice); break;
      default: break;
    }
  }

  private void fillValue(int value) {
    Widget input = client.getWidget(ComponentID.CHATBOX_FULL_INPUT);
    if (input == null) return;
    input.setText(value + "*");
    client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(value));
  }
}
