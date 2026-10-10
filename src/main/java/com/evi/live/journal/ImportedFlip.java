package com.evi.live.journal;

/**
 * A completed trade imported from another tracker (store.mjs {@code importFlips}). Feeds ranking, never
 * the profit total: EVI did not observe it.
 */
public final class ImportedFlip {
  public final String fp;
  public final String source;
  public final int itemId;
  public final String item;
  public final long quantity;
  public final long capital;
  public final long netProceeds;
  public final long profit;
  public final long firstBuy;
  public final long lastSell;
  public final double hold;
  /** Null when the import named no account (the line carries "account":null). */
  public final String account;

  public ImportedFlip(String fp, String source, int itemId, String item, long quantity, long capital, long netProceeds,
                      long profit, long firstBuy, long lastSell, double hold, String account) {
    this.fp = fp;
    this.source = source;
    this.itemId = itemId;
    this.item = item;
    this.quantity = quantity;
    this.capital = capital;
    this.netProceeds = netProceeds;
    this.profit = profit;
    this.firstBuy = firstBuy;
    this.lastSell = lastSell;
    this.hold = hold;
    this.account = account;
  }

  /**
   * What the trade IS, so the same trade cannot be imported twice through two routes with different
   * fingerprints: {@code [itemId, account ?? '', quantity, profit, firstBuy, lastSell].join('|')}.
   */
  public String contentKey() {
    return itemId + "|" + (account == null ? "" : account) + "|" + quantity + "|" + profit + "|" + firstBuy + "|" + lastSell;
  }
}
