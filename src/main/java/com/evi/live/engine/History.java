package com.evi.live.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A player's own reviewed flips, aggregated per item (bridge/suggestions.mjs {@code personalHistory}), and the risk
 * level's eligibility bar for the personal-history tier. Pure. Every input came from this account's own confirmed
 * trades, never a shared pool. (Which flips are passed in is the caller's choice; today the bridge passes every
 * account's, deliberately -- CLAUDE.md "Known and deliberate: EVI is only partly per-account".)
 */
public final class History {
  private History() {}

  /**
   * One flip as personalHistory reads it. Numbers are NaN when absent or not a number; {@code lastSell} and
   * {@code confirmedAt} are null when absent or null (the JS reads them with {@code ??}, where a NaN still counts).
   */
  public static final class Flip {
    public final boolean removed;
    public final double itemId;
    public final double profit;
    public final double quantity;
    public final double hold;
    public final Double lastSell;
    public final Double confirmedAt;
    public final String item;

    public Flip(boolean removed, double itemId, double profit, double quantity, double hold, Double lastSell, Double confirmedAt, String item) {
      this.removed = removed;
      this.itemId = itemId;
      this.profit = profit;
      this.quantity = quantity;
      this.hold = hold;
      this.lastSell = lastSell;
      this.confirmedAt = confirmedAt;
      this.item = item;
    }
  }

  /** One item's track record. */
  public static final class ItemHistory {
    public final double itemId;
    /** The most recent non-empty name, or the first flip's (possibly null). */
    public final String name;
    public final int trades;
    public final double winRate;
    public final double avgProfit;
    /** {@link Medians#average} of the quantities: can be x.5. */
    public final Double medianQty;
    public final Double medianHoldH;
    public final double lastTradeAt;

    ItemHistory(double itemId, String name, int trades, double winRate, double avgProfit, Double medianQty, Double medianHoldH, double lastTradeAt) {
      this.itemId = itemId;
      this.name = name;
      this.trades = trades;
      this.winRate = winRate;
      this.avgProfit = avgProfit;
      this.medianQty = medianQty;
      this.medianHoldH = medianHoldH;
      this.lastTradeAt = lastTradeAt;
    }
  }

  private static final class Acc {
    final double itemId;
    String name;
    int trades;
    int wins;
    double totalProfit;
    final List<Double> quantities = new ArrayList<>();
    final List<Double> holds = new ArrayList<>();
    double lastTradeAt;

    Acc(double itemId, String name) {
      this.itemId = itemId;
      this.name = name;
    }
  }

  /**
   * personalHistory: items in the order their FIRST flip appears (a JS Map keeps insertion order), skipping removed
   * flips and any whose id, profit or quantity is not a finite number.
   */
  public static List<ItemHistory> personalHistory(List<Flip> flips) {
    Map<Double, Acc> byItem = new LinkedHashMap<>();
    if (flips != null) for (Flip f : flips) {
      if (f == null || f.removed || !JsValues.isFinite(f.itemId) || !JsValues.isFinite(f.profit) || !JsValues.isFinite(f.quantity)) continue;
      double key = f.itemId == 0 ? 0.0 : f.itemId; // a JS Map key: -0 and 0 are the same item
      Acc h = byItem.get(key);
      if (h == null) {
        h = new Acc(f.itemId, f.item);
        byItem.put(key, h);
      }
      h.trades++;
      if (f.profit > 0) h.wins++;
      h.totalProfit += f.profit;
      h.quantities.add(f.quantity);
      if (JsValues.isFinite(f.hold)) h.holds.add(f.hold);
      Double at = f.lastSell != null ? f.lastSell : f.confirmedAt != null ? f.confirmedAt : Double.valueOf(0);
      if (at > h.lastTradeAt) h.lastTradeAt = at;
      if (f.item != null && !f.item.isEmpty()) h.name = f.item; // the most recent name wins
    }
    List<ItemHistory> out = new ArrayList<>(byItem.size());
    for (Acc h : byItem.values())
      out.add(new ItemHistory(h.itemId, h.name, h.trades, (double) h.wins / h.trades, h.totalProfit / h.trades,
        Medians.average(h.quantities), Medians.average(h.holds), h.lastTradeAt));
    return Collections.unmodifiableList(out);
  }

  /**
   * The personal tier's eligibility at a risk level: enough trades, a high enough win rate, a typical size of at
   * least one unit, and not blocked. ({@code h.trades >= tier.minTrades && h.winRate >= tier.minWinRate &&
   * h.medianQty >= 1 && !blocklist.has(h.itemId)}.)
   */
  public static boolean eligible(ItemHistory h, LevelSettings level, Set<Integer> blocklist) {
    boolean blocked = blocklist != null && JsValues.isInteger(h.itemId) && blocklist.contains((int) h.itemId);
    return h.trades >= level.historyMinTrades && h.winRate >= level.historyMinWinRate && h.medianQty != null && h.medianQty >= 1 && !blocked;
  }
}
