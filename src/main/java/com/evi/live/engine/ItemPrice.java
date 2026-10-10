package com.evi.live.engine;

/**
 * An item's plain live price for an open GE offer ({@code lookupItemPrice}), optionally made aware of what the player
 * paid ({@code withCostBasis}). Immutable. {@code action}, {@code breakEvenPrice} and {@code lossIfSoldNow} are null
 * until a cost basis is known: never a guessed cost.
 */
public final class ItemPrice {
  public final int itemId;
  /** The last instant-sell price ({@code /latest} low): what to bid. */
  public final long buyPrice;
  /** The last instant-buy price ({@code /latest} high): what to ask. */
  public final long sellPrice;
  /** "sell" once a cost basis marks this as a sale of stock the player holds; otherwise null. */
  public final String action;
  public final Long breakEvenPrice;
  /** The total loss of selling the held units at {@link #sellPrice}, rounded half-up; null when it is not a loss. */
  public final Long lossIfSoldNow;

  public ItemPrice(int itemId, long buyPrice, long sellPrice, String action, Long breakEvenPrice, Long lossIfSoldNow) {
    this.itemId = itemId;
    this.buyPrice = buyPrice;
    this.sellPrice = sellPrice;
    this.action = action;
    this.breakEvenPrice = breakEvenPrice;
    this.lossIfSoldNow = lossIfSoldNow;
  }
}
