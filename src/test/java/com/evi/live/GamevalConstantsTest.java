package com.evi.live;

/**
 * Do the replacement constants in net.runelite.api.gameval resolve to the SAME numbers the
 * deprecated ones do?
 *
 * <p>This exists because of 30 September 2026. {@code VarClientInt.INPUT_TYPE} kept its name and
 * changed its VALUE for the price prompt (7 -> 30), and the plugin's price hint and hotkey died
 * silently for every user. The lesson is that a constant's identity is its number, not its name --
 * so swapping fifteen deprecated references on the strength of a plausible-looking rename would be
 * repeating exactly the mistake that cost a release.
 *
 * <p>Every assertion below compares the deprecated constant with its replacement directly. A rename
 * that is really a rename passes; a rename that quietly points somewhere else fails here rather than
 * in someone's game. The deprecated references in THIS file are deliberate and are the only ones
 * that should remain once the swap is done -- they are the control half of the comparison.
 *
 * <p>Deliberately NOT asserting hard-coded numbers: those would record what the cache happened to
 * say on the day this was written, and Jagex is free to move them. What must hold is that both names
 * mean the same thing at the same moment.
 */
@SuppressWarnings("deprecation")
public class GamevalConstantsTest {
  static void check(boolean ok, String what) {
    if (!ok) throw new AssertionError(what);
  }

  static void same(int oldValue, int newValue, String what) {
    check(oldValue == newValue,
      what + ": the deprecated constant is " + oldValue + " and its replacement is " + newValue
        + ". These must be the same number -- a rename that moves the value is how the price prompt"
        + " broke on 30 Sept 2026. Do NOT swap this reference until the mismatch is understood.");
  }

  public static void main(String[] args) {
    // Widget/component ids used by GEOffer, SuggestionHintWidget, SuggestionItemSelectWidget,
    // SuggestionKeybindHandler and SuggestionSearchHighlightOverlay.
    same(net.runelite.api.widgets.ComponentID.CHATBOX_TITLE,
      net.runelite.api.gameval.InterfaceID.Chatbox.MES_TEXT, "CHATBOX_TITLE");
    same(net.runelite.api.widgets.ComponentID.CHATBOX_CONTAINER,
      net.runelite.api.gameval.InterfaceID.Chatbox.MES_LAYER, "CHATBOX_CONTAINER");
    same(net.runelite.api.widgets.ComponentID.CHATBOX_FULL_INPUT,
      net.runelite.api.gameval.InterfaceID.Chatbox.MES_TEXT2, "CHATBOX_FULL_INPUT");
    same(net.runelite.api.widgets.ComponentID.CHATBOX_GE_SEARCH_RESULTS,
      net.runelite.api.gameval.InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS, "CHATBOX_GE_SEARCH_RESULTS");
    same(net.runelite.api.widgets.ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER,
      net.runelite.api.gameval.InterfaceID.GeOffers.SETUP, "GRAND_EXCHANGE_OFFER_CONTAINER");

    // Varbits / varps. GE_OFFER_CREATION_TYPE decides buy versus sell; CURRENT_GE_ITEM is the item
    // the offer screen has open; INPUT_TYPE is the one whose value moved under us.
    same(net.runelite.api.Varbits.GE_OFFER_CREATION_TYPE,
      net.runelite.api.gameval.VarbitID.GE_NEWOFFER_TYPE, "GE_OFFER_CREATION_TYPE");
    same(net.runelite.api.VarPlayer.CURRENT_GE_ITEM,
      net.runelite.api.gameval.VarPlayerID.TRADINGPOST_SEARCH, "CURRENT_GE_ITEM");
    same(net.runelite.api.VarClientInt.INPUT_TYPE,
      net.runelite.api.gameval.VarClientID.MESLAYERMODE, "INPUT_TYPE");

    same(net.runelite.api.VarClientStr.INPUT_TEXT,
      net.runelite.api.gameval.VarClientID.MESLAYERINPUT, "INPUT_TEXT");
    // InventoryID was an ENUM, not an int, so this one compares the enum's own id with the new
    // integer constant. Confirmed 29 Sept 2026 to be 93 on both sides -- recorded then because a
    // session had wrongly blamed this deprecation for an inventory read that was never broken.
    same(net.runelite.api.InventoryID.INVENTORY.getId(),
      net.runelite.api.gameval.InventoryID.INV, "INVENTORY");

    System.out.println("PASS: every deprecated constant EVI reads resolves to the same number as its "
      + "net.runelite.api.gameval replacement (chatbox title/container/input/search results, the GE "
      + "offer container, the buy-versus-sell varbit, the current GE item varp, and the chatbox "
      + "input type whose value moved on 30 Sept 2026)");
  }
}
