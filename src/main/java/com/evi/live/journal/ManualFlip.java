package com.evi.live.journal;

import java.util.List;

/**
 * A flip the player reviewed and confirmed by hand (store.mjs {@code confirm}), as journalled. The
 * {@link #removed} and {@link #reopened} flags are not part of the journal line: later records set them,
 * exactly as store.mjs mutates the stored object. Null means the flag was never set, which is not the
 * same as false (a restored flip carries removed:false).
 */
public final class ManualFlip {
  public final String id;
  public final String buyId;
  /** Null when several sales were matched (the line still carries "sellId":null). */
  public final String sellId;
  /** Null only on a flip journalled before the field existed; read through {@link #sellIdsOrSingle}. */
  public final List<String> sellIds;
  public final String account;
  public final int itemId;
  public final String item;
  public final long quantity;
  public final long capital;
  public final long netProceeds;
  public final long profit;
  public final Long firstBuy;
  public final Long lastSell;
  public final double hold;
  public final Long confirmedAt;
  public final String source;
  public final String proceedsMethod;
  Boolean removed;
  Boolean reopened;

  public ManualFlip(String id, String buyId, String sellId, List<String> sellIds, String account, int itemId, String item,
                    long quantity, long capital, long netProceeds, long profit, Long firstBuy, Long lastSell, double hold,
                    Long confirmedAt, String source, String proceedsMethod) {
    this.id = id;
    this.buyId = buyId;
    this.sellId = sellId;
    this.sellIds = sellIds;
    this.account = account;
    this.itemId = itemId;
    this.item = item;
    this.quantity = quantity;
    this.capital = capital;
    this.netProceeds = netProceeds;
    this.profit = profit;
    this.firstBuy = firstBuy;
    this.lastSell = lastSell;
    this.hold = hold;
    this.confirmedAt = confirmedAt;
    this.source = source;
    this.proceedsMethod = proceedsMethod;
  }

  /** {@code f.sellIds ?? [f.sellId]}. */
  public List<String> sellIdsOrSingle() {
    return sellIds != null ? sellIds : java.util.Collections.singletonList(sellId);
  }

  public Boolean removed() {
    return removed;
  }

  public Boolean reopened() {
    return reopened;
  }

  boolean isRemoved() {
    return Boolean.TRUE.equals(removed);
  }

  boolean isReopened() {
    return Boolean.TRUE.equals(reopened);
  }
}
