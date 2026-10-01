package com.evi.live;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Every client id EVI reads, under a name that says what it is.
 *
 * <p>RuneLite deprecated {@code ComponentID}, {@code Varbits}, {@code VarPlayer} and
 * {@code VarClientInt} in favour of {@code net.runelite.api.gameval}, whose names come straight from
 * the game cache and are considerably less readable: the chatbox TITLE is {@code Chatbox.MES_TEXT},
 * the Grand Exchange offer panel is {@code GeOffers.SETUP}, and the chatbox input type is
 * {@code VarClientID.MESLAYERMODE}. Swapping those in at fifteen call sites would have removed a
 * deprecation warning and cost the reader the meaning of every one of them.
 *
 * <p>So the ids live here once, named for what they do, and the call sites keep reading the way they
 * did. It also gives one place to audit after a game update, which matters more than it sounds:
 * **this is the exact family of constant that broke EVI on 30 September 2026.** {@code INPUT_TYPE}
 * kept its name and its index and the VALUE the client reports through it moved from 7 to 30, which
 * silently killed the price hint and the hotkey for every user.
 *
 * <p>{@code GamevalConstantsTest} asserts each of these equals the deprecated constant it replaced,
 * so the swap is verified rather than trusted. Keep that test: it is the only thing standing between
 * a future rename and a repeat of 30 September.
 */
final class GeIds {
  private GeIds() {}

  /** The chatbox line that asks the question -- "Set a price for each item:". */
  static final int CHATBOX_TITLE = InterfaceID.Chatbox.MES_TEXT;
  /** The chatbox container EVI parents its own hint widget to. */
  static final int CHATBOX_CONTAINER = InterfaceID.Chatbox.MES_LAYER;
  /** The chatbox's editable input line -- what the hotkey fills. */
  static final int CHATBOX_FULL_INPUT = InterfaceID.Chatbox.MES_TEXT2;
  /** The item-search results list, where EVI draws its own suggestion row. */
  static final int CHATBOX_GE_SEARCH_RESULTS = InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS;
  /** The Grand Exchange offer panel itself. */
  static final int GRAND_EXCHANGE_OFFER_CONTAINER = InterfaceID.GeOffers.SETUP;

  /** 0 while setting up a BUY offer, 1 while setting up a SELL. */
  static final int GE_OFFER_CREATION_TYPE = VarbitID.GE_NEWOFFER_TYPE;
  /** The item currently open in the offer screen, or -1 when none is chosen yet. */
  static final int CURRENT_GE_ITEM = VarPlayerID.TRADINGPOST_SEARCH;
  /**
   * Which kind of chatbox input is open; 0 means none.
   *
   * <p>Read as a BOOLEAN and never as an identity -- see GEOffer.isPromptOpen. Jagex changed the
   * price prompt's value here from 7 to 30 on 30 September 2026.
   */
  static final int CHATBOX_INPUT_TYPE = VarClientID.MESLAYERMODE;
  /** The text currently typed into the chatbox input -- what the hotkey writes and reads back. */
  static final int CHATBOX_INPUT_TEXT = VarClientID.MESLAYERINPUT;
  /** The player's inventory container. */
  static final int INVENTORY = net.runelite.api.gameval.InventoryID.INV;
}
