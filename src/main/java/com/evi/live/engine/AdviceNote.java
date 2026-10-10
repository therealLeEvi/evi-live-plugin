package com.evi.live.engine;

/**
 * One line of advice about something the player already has: an offer standing in a slot, or stock held. The shape the
 * bridge sends in {@code relistAdvice} (the list the plugin draws as cards under "Active offers"), and the shape each
 * advice module returns before the server trims it ({@link Relist}, {@link SellAdvice}, {@link BuyAdvice},
 * {@link BuyProgress}, {@link HoldingsAdvice}, and the crash notes in {@link AdviceNotes}).
 *
 * <p>A null field is ABSENT (or JSON null, which the plugin reads the same way). Numbers are doubles because they are the
 * JS numbers the bridge computed -- the plugin never parses a fractional one: every field it reads as a whole number is
 * whole by construction (an offer's own price, a {@code Math.round}ed figure).
 *
 * <p>NEVER AN INSTRUCTION. Every module states facts and leaves the decision with the player: EVI never places, edits,
 * relists or cancels an offer.
 */
public final class AdviceNote {
  public int itemId;
  public String name;
  public String message;
  /** The tooltip under a short message (8 Oct 2026: crash notes only). Null: absent, and the tooltip is the message. */
  public String detail;
  /** 'warn', 'caution' or 'info'. */
  public String level;
  public String label;
  public String figures;

  // relist.mjs
  public Double offerPrice;
  public Double marketPrice;
  public Double breakEven;
  public Double suggestedPrice;
  public Double openMinutes;
  public Boolean belowBreakEven;

  // sellAdvice.mjs
  public Double lossEach;
  public Double lossTotal;

  // buyAdvice.mjs: both null on a "Nobody selling" note (JS sends them as null)
  public Double marginPerUnit;
  public Double netAtMarket;

  // buyProgress.mjs
  public Double filled;
  public Double total;

  // holdingsAdvice.mjs
  public Double quantity;
  public Double unitCost;
  public Double cost;
  public Double worth;
  public Double net;
  public Boolean holding;
  /** The lot's buy offer id (8 Oct 2026): what the sidebar's line menu marks (Personal use, Gone). Null: absent. */
  public String buyId;

  AdviceNote(int itemId) {
    this.itemId = itemId;
  }

  /** An advice module's input price for one item: what sellers ask ({@code buyPrice}) and buyers pay ({@code sellPrice}). */
  public static final class Price {
    public final Double buyPrice;
    public final Double sellPrice;

    public Price(Double buyPrice, Double sellPrice) {
      this.buyPrice = buyPrice;
      this.sellPrice = sellPrice;
    }

    /** From {@link Prices#lookupItemPrice}'s answer (null in, null out). */
    public static Price of(ItemPrice p) {
      return p == null ? null : new Price((double) p.buyPrice, (double) p.sellPrice);
    }
  }
}
