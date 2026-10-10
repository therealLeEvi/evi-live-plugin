package com.evi.live.journal;

/**
 * One Grand Exchange offer as the journal knows it: the record store.mjs keeps in {@code this.offers},
 * keyed by its (resolved) offerId. Immutable; every packet that changes it produces a new one, exactly as
 * the JS engine builds a fresh object with {@code {...o, ...}}.
 *
 * <p>ABSENT IS NOT ZERO, and the nullable fields carry that distinction:
 * <ul>
 *   <li>{@link #ticksToFill} null: no reading at all (journalled before the plugin sent it, or never
 *       filled while watched). -1 never appears here: the store keeps the earlier real reading instead.</li>
 *   <li>{@link #completedAt} null: not finished when last seen.</li>
 *   <li>{@link #recorded} true only for a purchase the player told EVI about ({@code recordPurchase});
 *       null for everything EVI observed.</li>
 * </ul>
 *
 * <p>Prices, totals and counters are {@code long}: a recorded purchase's spend can pass 2^31.
 */
public final class Offer {
  public final int slot;
  public final String state;
  public final String offerId;
  public final int itemId;
  public final String name;
  public final long price;
  public final long total;
  public final long filled;
  public final long spent;
  public final boolean knownStart;
  public final Integer ticksToFill;
  public final String account;
  public final String session;
  public final Long firstSeen;
  public final Long updated;
  public final Long completedAt;
  public final Boolean recorded;

  public Offer(int slot, String state, String offerId, int itemId, String name, long price, long total, long filled,
               long spent, boolean knownStart, Integer ticksToFill, String account, String session, Long firstSeen,
               Long updated, Long completedAt, Boolean recorded) {
    this.slot = slot;
    this.state = state;
    this.offerId = offerId;
    this.itemId = itemId;
    this.name = name;
    this.price = price;
    this.total = total;
    this.filled = filled;
    this.spent = spent;
    this.knownStart = knownStart;
    this.ticksToFill = ticksToFill;
    this.account = account;
    this.session = session;
    this.firstSeen = firstSeen;
    this.updated = updated;
    this.completedAt = completedAt;
    this.recorded = recorded;
  }

  /** store.mjs side(): BUYING, BOUGHT and CANCELLED_BUY are buys; EVERYTHING else (EMPTY included) is a sell. */
  public static String side(String state) {
    return "BUYING".equals(state) || "BOUGHT".equals(state) || "CANCELLED_BUY".equals(state) ? "buy" : "sell";
  }

  /** store.mjs finished(): a terminal state, or every unit filled. */
  public static boolean finished(String state, long total, long filled) {
    return "BOUGHT".equals(state) || "SOLD".equals(state) || "CANCELLED_BUY".equals(state) || "CANCELLED_SELL".equals(state)
      || (total > 0 && filled == total);
  }

  public String side() {
    return side(state);
  }

  public boolean finished() {
    return finished(state, total, filled);
  }
}
