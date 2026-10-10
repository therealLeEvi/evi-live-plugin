package com.evi.live.engine;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Js;
import com.evi.live.journal.Offer;
import com.evi.live.journal.Tax;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.LatestPrices;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The sell-side tiers: stock the player already holds (bridge/suggestions.mjs {@code computeHoldingSuggestion},
 * {@code holdingPreempts}, {@code hasLiveSellOffer}, {@code pickPersistentOpenPosition}, {@code computeInventorySuggestion}),
 * and the "Best of both" rule that keeps a sell out of a contest between two BUYS ({@code comparableAsHistoryPick}). Pure.
 *
 * <p>These run BEFORE any buy tier: closing out a position already opened beats being pointed at a new one. They are
 * reminders, never blocks; none of them is gated on the buy-side checks (a player holding an unflippable item is still
 * helped to sell it).
 *
 * <p>ACCOUNT SCOPING (the 6 Oct rule): every function that reads positions or offers takes the asking account and owns
 * nothing for a missing one. A poll from the login screen speaks for nobody.
 */
public final class HoldingTiers {
  private HoldingTiers() {}

  /** Coins: gp itself is never "something to sell". */
  public static final int COINS_ITEM_ID = 995;
  /** Platinum tokens, 1,000 gp each: counted as spending power by the plugin, so never offered for sale either. */
  public static final int PLATINUM_TOKEN_ITEM_ID = 13204;
  /** MIN_INVENTORY_VALUE: below this an idle stack is junk, not a suggestion. */
  public static final double MIN_INVENTORY_VALUE = 100000;

  private static LatestPrices.Quote priced(LatestPrices latest, int itemId) {
    LatestPrices.Quote p = latest == null ? null : latest.get(itemId);
    return p == null || !(p.low > 0) || !(p.high > 0) ? null : p; // HourBucket.NONE (no print) is negative
  }

  /**
   * computeHoldingSuggestion: a reminder that the player holds {@code holdQty} of an item from an earlier buy that has
   * not been resold. Null for a non-positive id or quantity, or an item without both sides priced. With a known unit cost
   * ({@code holdBuyPrice}, NaN when unknown) it states the net of selling now, the break-even, and -- a warning, never an
   * instruction -- the loss when there is one; never a guessed cost.
   *
   * @param holdQty a whole number of units (the plugin sends an int; the type rules out a fraction)
   */
  public static Pick computeHoldingSuggestion(LatestPrices latest, int holdItemId, long holdQty, String holdName, String holdBuyId, double holdBuyPrice) {
    return computeHoldingSuggestion(latest, holdItemId, holdQty, holdName, holdBuyId, holdBuyPrice, null);
  }

  /**
   * computeHoldingSuggestion for a position whose sell listing ENDED while EVI was not watching ({@code endedUnseen}, the
   * open position's mark; null for every other holding). Nobody knows whether that listing sold, so the card states what EVI
   * saw and nothing it did not (8 Oct 2026, a real case, fixed in JS first): no "would net about +", no loss, no
   * break-even, netIfSoldNow null -- an UNKNOWN worth, which {@link #holdingPreempts} always lets speak -- and the enrichment
   * gives it no headline profit. Never suppressed.
   */
  public static Pick computeHoldingSuggestion(LatestPrices latest, int holdItemId, long holdQty, String holdName, String holdBuyId, double holdBuyPrice,
                                              FifoMatcher.EndedUnseenMark endedUnseen) {
    if (holdItemId <= 0 || holdQty <= 0) return null;
    LatestPrices.Quote p = priced(latest, holdItemId);
    if (p == null) return null;
    String name = holdName != null && !holdName.isEmpty() ? holdName : "item " + holdItemId;
    String qtyText = Js.groupedUs(holdQty), high = Js.groupedUs(p.high);
    String reasoning = "You're holding " + qtyText + " " + name + " from an earlier buy that hasn't been resold yet -- sell near " + high + " gp before starting anything new.";
    Long breakEvenPrice = null, lossIfSoldNow = null;
    Double netIfSoldNow = null;
    if (JsValues.isFinite(holdBuyPrice) && holdBuyPrice > 0) {
      long tax = Tax.estimateUnitTax(holdItemId, p.high);
      double netPerUnit = p.high - holdBuyPrice - tax;
      // ROUNDED FIRST, then judged (the maintainer, 7 Oct 2026, fixed in JS first): a loss under half a gp in total rounds
      // to minus zero, and `|| 0` folds that into zero, so the holding takes the rounded branch ("would net about +0 gp")
      // instead of "a LOSS of about 0 gp". A loss that rounds to a whole gp or more is still the loss it is.
      double rounded = JsValues.jsRound(netPerUnit * holdQty);
      double totalNet = rounded == 0 ? 0.0 : rounded;
      breakEvenPrice = Tax.breakEvenSellPrice(holdItemId, holdBuyPrice);
      String breakEvenText = breakEvenPrice != null && breakEvenPrice != 0 ? " Break-even after tax: " + Js.groupedUs(breakEvenPrice) + " gp." : "";
      netIfSoldNow = totalNet;
      if (totalNet < 0) lossIfSoldNow = JsValues.exactLong(Math.abs(totalNet));
      String paid = JsValues.localeUs(holdBuyPrice);
      reasoning = totalNet >= 0
        ? "You're holding " + qtyText + " " + name + " bought at " + paid + " gp -- selling near " + high + " gp now would net about +"
          + JsValues.localeUs(totalNet) + " gp." + breakEvenText
        // A warning, never a block, and not an instruction either way: both numbers, so the choice is informed.
        : "WARNING: you're holding " + qtyText + " " + name + " bought at " + paid + " gp -- selling near " + high + " gp now would be a LOSS of about "
          + JsValues.localeUs(Math.abs(totalNet)) + " gp." + breakEvenText
          + " Not a recommendation either way: sell now to cap the loss at that amount, or list at or above break-even if you'd rather wait for the price to recover (it may not).";
    }
    FifoMatcher.EndedUnseenMark gone = endedUnseen != null && endedUnseen.price > 0 ? endedUnseen : null;
    if (gone != null) {
      String bought = JsValues.isFinite(holdBuyPrice) && holdBuyPrice > 0 ? " bought at " + JsValues.localeUs(holdBuyPrice) + " gp" : "";
      boolean some = gone.quantity > 0 && gone.quantity < holdQty;
      reasoning = "EVI's record: " + qtyText + " " + name + bought + ". EVI last saw "
        + (some ? Js.groupedUs(gone.quantity) + " of them" : "this")
        + " listed at " + Js.groupedUs(gone.price) + " gp; the listing was gone at your next login (it ended while EVI wasn't watching), so EVI can't tell whether it sold. "
        + (some ? "If it sold and the rest are gone too, press Gone." : "If it sold, press Gone.");
      breakEvenPrice = null;
      lossIfSoldNow = null;
      netIfSoldNow = null;
    }
    Pick s = new Pick(holdItemId, name, "sell", holdQty, p.low, p.high, "holding");
    s.reasoning = reasoning;
    s.buyId = holdBuyId != null && !holdBuyId.isEmpty() ? holdBuyId : null;
    s.breakEvenPrice = breakEvenPrice;
    s.lossIfSoldNow = lossIfSoldNow;
    s.netIfSoldNow = netIfSoldNow;
    s.endedUnseen = gone;
    return s;
  }

  /**
   * hasLiveSellOffer: is this item ACTUALLY on the market for this account right now? Pass the plugin's live slots
   * ({@code state().active}), never the journal, which keeps a cancelled offer SELLING for ever. Package-private: production
   * asks {@link AccountView#hasLiveSellOffer}.
   */
  static boolean hasLiveSellOffer(List<Offer> activeOffers, int itemId, String account) {
    if (account == null || account.isEmpty() || activeOffers == null) return false;
    for (Offer o : activeOffers) if (o != null && o.itemId == itemId && "SELLING".equals(o.state) && account.equals(o.account)) return true;
    return false;
  }

  /**
   * holdingPreempts: should a holding reminder take the place of a ranked suggestion? A LOSS always speaks, an UNKNOWN
   * worth always speaks; only a trivial GAIN under the player's minimum steps aside (28 Sept: a 152 gp pie outranking the
   * catalogue at 205m idle). Measured and settled 30 Sept -- do not "fix" it without beating it (tools/holding-gate.mjs).
   */
  public static boolean holdingPreempts(Pick suggestion, double minProfit) {
    if (suggestion == null) return false;
    Double net = suggestion.netIfSoldNow;
    if (net == null || !JsValues.isFinite(net)) return true;
    if (net < 0) return true;
    if (!JsValues.isFinite(minProfit) || minProfit <= 0) return true;
    return net >= minProfit;
  }

  /**
   * pickPersistentOpenPosition: the journal's oldest open position for THIS account with no sell offer behind it -- the
   * first one not already listed, not simply the oldest (29 Sept: one listed position starved three behind it). Null for
   * no account or nothing left after the exclusions. Package-private: production asks
   * {@link AccountView#pickPersistentOpenPosition}.
   */
  static FifoMatcher.OpenPosition pickPersistentOpenPosition(List<FifoMatcher.OpenPosition> openPositions, String account, Set<Integer> blocklist,
                                                                    IntPredicate isListed) {
    if (openPositions == null || account == null || account.isEmpty()) return null;
    List<FifoMatcher.OpenPosition> mine = new ArrayList<>();
    for (FifoMatcher.OpenPosition p : openPositions)
      if (p != null && account.equals(p.account) && p.remaining > 0 && (blocklist == null || !blocklist.contains(p.itemId))) mine.add(p);
    if (mine.isEmpty()) return null;
    // a.firstSeen - b.firstSeen: a JSON null firstSeen subtracts as 0 (JS null arithmetic), so it sorts as the oldest.
    mine.sort((a, b) -> Js.compare(a.firstSeen == null ? 0 : a.firstSeen, b.firstSeen == null ? 0 : b.firstSeen));
    for (FifoMatcher.OpenPosition p : mine) if (isListed == null || !isListed.test(p.itemId)) return p;
    return null;
  }

  /** computeInventorySuggestion's options. Every field may be null (nothing excluded, nothing kept). */
  public static final class InventoryOptions {
    public Set<Integer> blocklist;
    /** This account's own open positions: the holding tier owns those items and can state their cost. */
    public Set<Integer> positionItemIds;
    /** A free-to-play world: a members item cannot be sold there either. */
    public IntPredicate membersBlocked;
    /** How many of each item are kept FOR USE (only the surplus is stock). */
    public Map<Integer, Long> keptForUse;
  }

  /**
   * computeInventorySuggestion: the fallback of last resort -- stock in the bag with no observed buy behind it (a drop, a
   * reward, stock from before EVI watched), worth at least {@link #MIN_INVENTORY_VALUE} at today's high, the most
   * valuable first. {@code inventory} iterates in ascending item id, as the JS object's integer keys do; {@code names}
   * is the item catalogue's id -> name (a missing or empty name reads "item N").
   */
  public static Pick computeInventorySuggestion(LatestPrices latest, NavigableMap<Integer, Long> inventory, Map<Integer, String> names, InventoryOptions options) {
    if (inventory == null || names == null || latest == null) return null;
    InventoryOptions o = options == null ? new InventoryOptions() : options;
    List<Object[]> candidates = new ArrayList<>(); // {itemId, sellable, quote, value, name}
    for (Map.Entry<Integer, Long> e : inventory.entrySet()) {
      int itemId = e.getKey();
      Long qty = e.getValue();
      if (itemId == COINS_ITEM_ID || itemId == PLATINUM_TOKEN_ITEM_ID || qty == null || !(qty > 0)) continue;
      if (o.blocklist != null && o.blocklist.contains(itemId)) continue;
      if (o.positionItemIds != null && o.positionItemIds.contains(itemId)) continue;
      if (o.membersBlocked != null && o.membersBlocked.test(itemId)) continue;
      LatestPrices.Quote p = priced(latest, itemId);
      if (p == null) continue;
      Long kept = o.keptForUse == null ? null : o.keptForUse.get(itemId);
      long sellable = kept != null ? qty - Math.max(1, kept) : qty; // holding exactly what is kept leaves nothing
      if (!(sellable > 0)) continue;
      double value = (double) sellable * p.high;
      if (value < MIN_INVENTORY_VALUE) continue;
      String name = names.get(itemId);
      candidates.add(new Object[]{itemId, sellable, p, value, name != null && !name.isEmpty() ? name : "item " + itemId});
    }
    if (candidates.isEmpty()) return null;
    candidates.sort((a, b) -> Js.compare((double) b[3], (double) a[3]));
    Object[] c = candidates.get(0);
    int itemId = (int) c[0];
    long qty = (long) c[1];
    LatestPrices.Quote p = (LatestPrices.Quote) c[2];
    String name = (String) c[4];
    Pick s = new Pick(itemId, name, "sell", qty, p.low, p.high, "inventory");
    s.buyId = null;
    s.reasoning = "You're holding " + Js.groupedUs(qty) + " " + name + " worth an estimated " + JsValues.gp((double) c[3])
      + " gp at current prices, with no active offer and no buy EVI ever observed for it -- likely a drop, a quest reward, or stock from before EVI started watching. Sell near "
      + Js.groupedUs(p.high) + " gp if you don't need it.";
    return s;
  }

  /** The catalogue's names as computeInventorySuggestion reads them: a later entry for the same id wins. */
  public static Map<Integer, String> namesOf(List<ItemCatalog.Item> mapping) {
    Map<Integer, String> names = new HashMap<>();
    if (mapping != null) for (ItemCatalog.Item m : mapping) if (m != null) names.put(m.id, m.name);
    return Collections.unmodifiableMap(names);
  }

  /**
   * comparableAsHistoryPick: is this one of the two BUYS "Best of both" chooses between? A sell of stock already owned
   * is not: it consumes neither the slot nor the coins the buy needs (30 Sept, the Gilded d'hide vambraces).
   */
  public static boolean comparableAsHistoryPick(Pick suggestion, String wantSource) {
    return "both".equals(wantSource) && suggestion != null && suggestion.isBuy();
  }
}
