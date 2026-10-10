package com.evi.live.journal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Automatic FIFO matching with exact GE tax, ported from store.mjs ({@code computeAutoFlips},
 * {@code isMarginCheck}, {@code availableAt}, {@code dataHealthOf}). Pure: offers in, flips out.
 *
 * <p>Per account AND item (never across accounts), every finished, fully observed offer is walked in the
 * order its goods or GP came into existence; buys become cost-basis lots, sells consume them oldest
 * first, and a lot closes into a flip the moment it is fully sold.
 *
 * <p>ROUNDING: the running capital and proceeds are DOUBLES (a lot's unit cost is spent/filled, which is
 * rarely whole) and are rounded only on the way out, with {@link Js#round(double)} -- half-up, exactly
 * JavaScript's Math.round, never rint or HALF_EVEN, and loud (never saturating) past 2^63. A profit is the
 * rounded DIFFERENCE, not the difference of the rounded parts. Both are asserted on exact .5 ties.
 */
public final class FifoMatcher {
  private FifoMatcher() {}

  /** A probe is filled within this many game ticks (store.mjs MARGIN_CHECK_TICKS). */
  public static final int MARGIN_CHECK_TICKS = 2;
  /** One game tick, for ordering a purchase by its first fill. */
  public static final long GAME_TICK_MS = 600;

  /** A position the player said is gone (store.mjs closePosition): {reason, at}. */
  public static final class Closed {
    public final String reason;
    public final long at;

    public Closed(String reason, long at) {
      this.reason = reason;
      this.at = at;
    }
  }

  /** An automatic flip: one buy lot, fully sold (or, when closed, the part that was sold: partial). */
  public static final class AutoFlip {
    public final String id;
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
    public final String buyId;
    public final List<String> sellIds;
    public final boolean exact;
    public final String source = "auto-fifo";
    /** True for the sold part of a closed lot; null otherwise (the JS object has no such key). */
    public final Boolean partial;
    public final String closedReason;

    AutoFlip(Lot lot, long quantity, Boolean partial, String closedReason) {
      this.id = "auto:" + lot.offerId;
      this.account = lot.account;
      this.itemId = lot.itemId;
      this.item = lot.item;
      this.quantity = quantity;
      this.capital = Js.round(lot.capital);
      this.netProceeds = Js.round(lot.netProceeds);
      this.profit = Js.round(lot.netProceeds - lot.capital);
      this.firstBuy = lot.firstSeen;
      this.lastSell = lot.lastSell;
      this.hold = lot.lastSell == null || lot.firstSeen == null ? Double.NaN : (double) (lot.lastSell - lot.firstSeen) / 3600000;
      this.buyId = lot.offerId;
      this.sellIds = Collections.unmodifiableList(new ArrayList<>(lot.sellIds));
      this.exact = lot.exact;
      this.partial = partial;
      this.closedReason = closedReason;
    }
  }

  /**
   * A SELL offer that ended while EVI was not watching ({@link Store}'s ended-unseen rule), with the packet ts at which it
   * was found gone. NEVER a sale: no flip, no profit, no FIFO match -- it only limits what an open lot may claim.
   */
  public static final class EndedUnseen {
    public final Offer offer;
    public final long at;

    public EndedUnseen(Offer offer, long at) {
      this.offer = offer;
      this.at = at;
    }
  }

  /**
   * What an open lot carries when a vanished sell listing may have held it (store.mjs {@code endedUnseen}): the listing's
   * offerId, price, when it was found gone, and how many of the lot's remaining units it covered (fewer than
   * {@code remaining} = partly covered).
   */
  public static final class EndedUnseenMark {
    public final String sellId;
    public final long price;
    public final long at;
    public final long quantity;

    public EndedUnseenMark(String sellId, long price, long at, long quantity) {
      this.sellId = sellId;
      this.price = price;
      this.at = at;
      this.quantity = quantity;
    }
  }

  /** A lot still (partly) held. unitCost is the real average paid, spent/filled, never the offer price. */
  public static final class OpenPosition {
    public final String account;
    public final int itemId;
    public final String item;
    public final String buyId;
    public final long remaining;
    public final long totalQty;
    public final Long firstSeen;
    public final double unitCost;
    public final boolean partiallySold;
    /** Non-null only when a sell listing that may have held this lot ended unseen (the JS object has no key otherwise). */
    public final EndedUnseenMark endedUnseen;

    public OpenPosition(String account, int itemId, String item, String buyId, long remaining, long totalQty, Long firstSeen,
                        double unitCost, boolean partiallySold) {
      this(account, itemId, item, buyId, remaining, totalQty, firstSeen, unitCost, partiallySold, null);
    }

    public OpenPosition(String account, int itemId, String item, String buyId, long remaining, long totalQty, Long firstSeen,
                        double unitCost, boolean partiallySold, EndedUnseenMark endedUnseen) {
      this.endedUnseen = endedUnseen;
      this.account = account;
      this.itemId = itemId;
      this.item = item;
      this.buyId = buyId;
      this.remaining = remaining;
      this.totalQty = totalQty;
      this.firstSeen = firstSeen;
      this.unitCost = unitCost;
      this.partiallySold = partiallySold;
    }
  }

  /** A sale matching cannot account for: the offer itself, plus how much of it and why. */
  public static final class UnmatchedSell {
    public final Offer offer;
    /** Null when no part could be matched because the sale had no usable tax calculation. */
    public final Long unmatchedQty;
    public final String reason;

    UnmatchedSell(Offer offer, Long unmatchedQty, String reason) {
      this.offer = offer;
      this.unmatchedQty = unmatchedQty;
      this.reason = reason;
    }
  }

  public static final class Result {
    public final List<AutoFlip> flips;
    public final List<OpenPosition> openPositions;
    public final List<UnmatchedSell> unmatchedSells;

    Result(List<AutoFlip> flips, List<OpenPosition> openPositions, List<UnmatchedSell> unmatchedSells) {
      this.flips = Collections.unmodifiableList(flips);
      this.openPositions = Collections.unmodifiableList(openPositions);
      this.unmatchedSells = Collections.unmodifiableList(unmatchedSells);
    }
  }

  static final String NO_TAX = "No usable tax calculation for this sale (pre-tax-era or invalid data)";
  static final String OVERSOLD = "Sold quantity exceeds every known preceding buy for this account/item -- likely a buy observed only partway through, or stock held before EVI started watching";

  private static final class Lot {
    final String offerId, account, item;
    final int itemId;
    long remaining;
    final long totalQty;
    final double unitCost;
    final Long firstSeen;
    /** availableAt of the buy: a sell listing placed before this could not have held the lot's units. */
    final double at;
    double capital, netProceeds;
    final List<String> sellIds = new ArrayList<>();
    boolean exact = true;
    Long lastSell;

    Lot(Offer o, double at) {
      this.at = at;
      offerId = o.offerId;
      account = o.account;
      itemId = o.itemId;
      item = o.name;
      remaining = o.filled;
      totalQty = o.filled;
      unitCost = (double) o.spent / o.filled;
      firstSeen = o.firstSeen;
    }
  }

  /**
   * A "margin check": the one-item probe placed at a deliberately bad price to find what an item really
   * trades at. INVISIBLE ON PURPOSE: never a flip, never an open position. Positive evidence only --
   * exactly one unit, filled within two ticks, AND at a strictly better price than asked (the fingerprint
   * a probe has by construction). An unknown tick count is never a probe.
   */
  public static boolean isMarginCheck(Offer o) {
    return o != null && o.total == 1 && o.filled == 1 && o.ticksToFill != null && o.ticksToFill >= 0
      && o.ticksToFill <= MARGIN_CHECK_TICKS && filledBetterThanAsked(o);
  }

  private static boolean filledBetterThanAsked(Offer o) {
    if (!(o.filled > 0) || !(o.price > 0)) return false;
    double actual = (double) o.spent / o.filled;
    return "buy".equals(o.side()) ? actual < o.price : actual > o.price;
  }

  /**
   * When an offer's goods or GP came into existence, for the FIFO order. A sale: when it completed. A
   * purchase: its FIRST fill (firstSeen + ticks x 600ms), never later than its completion, because part of
   * a buy can be collected and sold while the rest is still open. NaN only when the offer carries no time
   * at all, which the sort then treats as equal to everything, as JS does.
   */
  public static double availableAt(Offer o) {
    Long completed = o.completedAt != null ? o.completedAt : o.firstSeen;
    if ("buy".equals(o.side()) && o.ticksToFill != null && o.ticksToFill >= 0 && o.firstSeen != null) {
      double first = (double) o.firstSeen + (double) o.ticksToFill * GAME_TICK_MS;
      return completed == null ? Double.NaN : Math.min(first, (double) completed);
    }
    return completed == null ? Double.NaN : (double) completed;
  }

  public static Result computeAutoFlips(List<Offer> offers, Map<String, Closed> closed) {
    return computeAutoFlips(offers, closed, Collections.emptyList());
  }

  /** One vanished listing's units still to hand out to the lots. */
  private static final class Vanished {
    final EndedUnseen s;
    long left;

    Vanished(EndedUnseen s) {
      this.s = s;
      this.left = s.offer.total;
    }
  }

  /**
   * computeAutoFlips with the sells that ENDED UNSEEN: never sales; after matching, each one's listed quantity ({@code total}:
   * every unit left the bag when listed) is handed, oldest listing first, to the open lots of the same account and item that
   * existed when it was listed, oldest lot first. A lot that receives any carries {@link EndedUnseenMark} with the FIRST
   * listing that covered it and the units covered.
   *
   * <p>An ended-unseen BUY in the same list (the maintainer's decision, 8 Oct 2026) is different: its FILLED units, as last seen, were certainly
   * bought, so they become an ordinary lot exactly as a cancelled part-filled buy's do -- the same filters (knownStart,
   * filled &gt; 0, not a margin check), cost basis spent/filled, ordered as though it had finished at its last sighting
   * ({@code updated}). Added to its item's group AFTER every completed offer, as store.mjs does. The caller leaves out buys it
   * would leave out of {@code offers} (manually matched, or marked personal use).
   */
  public static Result computeAutoFlips(List<Offer> offers, Map<String, Closed> closed, List<EndedUnseen> endedUnseen) {
    Map<String, List<Vanished>> vanished = new LinkedHashMap<>();
    if (endedUnseen != null) for (EndedUnseen s : endedUnseen) {
      if (s == null || !"sell".equals(s.offer.side()) || !(s.offer.total > 0)) continue;
      vanished.computeIfAbsent(s.offer.account + " " + s.offer.itemId, k -> new ArrayList<>()).add(new Vanished(s));
    }
    for (List<Vanished> list : vanished.values())
      list.sort((a, b) -> Js.compare(seen(a.s.offer), seen(b.s.offer))); // stable, like V8's
    Map<String, List<Offer>> groups = new LinkedHashMap<>();
    for (Offer o : offers) {
      // Margin checks are not trades and must not become flips or open positions.
      if (o == null || !o.knownStart || !o.finished() || !(o.filled > 0) || isMarginCheck(o)) continue;
      groups.computeIfAbsent(o.account + " " + o.itemId, k -> new ArrayList<>()).add(o);
    }
    if (endedUnseen != null) for (EndedUnseen e : endedUnseen) {
      if (e == null) continue;
      Offer b = e.offer;
      if (!"buy".equals(b.side()) || !b.knownStart || !(b.filled > 0) || isMarginCheck(b)) continue;
      // {...b, completedAt: b.completedAt ?? b.updated}
      Offer asFinished = new Offer(b.slot, b.state, b.offerId, b.itemId, b.name, b.price, b.total, b.filled, b.spent, b.knownStart,
        b.ticksToFill, b.account, b.session, b.firstSeen, b.updated, b.completedAt != null ? b.completedAt : b.updated, b.recorded);
      groups.computeIfAbsent(b.account + " " + b.itemId, k -> new ArrayList<>()).add(asFinished);
    }
    List<AutoFlip> flips = new ArrayList<>();
    List<OpenPosition> open = new ArrayList<>();
    List<UnmatchedSell> unmatched = new ArrayList<>();
    for (List<Offer> list : groups.values()) {
      List<Offer> sorted = new ArrayList<>(list);
      sorted.sort((a, b) -> Js.compare(availableAt(a), availableAt(b))); // List.sort is stable, like V8's
      List<Lot> lots = new ArrayList<>();
      for (Offer o : sorted) {
        double at = availableAt(o);
        // Lots closed before this offer: settle their sold part and drop them, walking from the END as
        // store.mjs does (so several closed at once land in reverse order).
        for (int i = lots.size() - 1; i >= 0; i--) {
          Closed c = closed.get(lots.get(i).offerId);
          if (c != null && c.at <= at) {
            soldPart(lots.get(i), closed, flips);
            lots.remove(i);
          }
        }
        if ("buy".equals(o.side())) {
          lots.add(new Lot(o, at));
          continue;
        }
        Tax.Proceeds proceeds = Tax.saleProceeds(o);
        if (proceeds == null) {
          unmatched.add(new UnmatchedSell(o, null, NO_TAX));
          continue;
        }
        long remainingSellQty = o.filled;
        double unitNet = (double) proceeds.net / o.filled;
        while (remainingSellQty > 0 && !lots.isEmpty() && lots.get(0).remaining > 0) {
          Lot lot = lots.get(0);
          long take = Math.min(lot.remaining, remainingSellQty);
          lot.remaining -= take;
          remainingSellQty -= take;
          lot.capital += take * lot.unitCost;
          lot.netProceeds += take * unitNet;
          lot.sellIds.add(o.offerId);
          lot.exact = lot.exact && proceeds.exact;
          lot.lastSell = o.completedAt != null ? o.completedAt : o.firstSeen;
          if (lot.remaining == 0) {
            flips.add(new AutoFlip(lot, lot.totalQty, null, null));
            lots.remove(0);
          }
        }
        if (remainingSellQty > 0) unmatched.add(new UnmatchedSell(o, remainingSellQty, OVERSOLD));
      }
      List<Vanished> gone = vanished.getOrDefault(list.get(0).account + " " + list.get(0).itemId, Collections.emptyList());
      for (Lot lot : lots) {
        if (closed.containsKey(lot.offerId)) {
          soldPart(lot, closed, flips);
          continue;
        }
        if (!(lot.remaining > 0)) continue;
        // Only a listing placed once this lot's goods existed can have held them.
        long need = lot.remaining;
        EndedUnseen first = null;
        for (Vanished v : gone) {
          if (!(need > 0)) break;
          if (!(v.left > 0) || !(seen(v.s.offer) >= lot.at)) continue;
          long take = Math.min(v.left, need);
          v.left -= take;
          need -= take;
          if (first == null) first = v.s;
        }
        EndedUnseenMark mark = first == null ? null : new EndedUnseenMark(first.offer.offerId, first.offer.price, first.at, lot.remaining - need);
        open.add(new OpenPosition(lot.account, lot.itemId, lot.item, lot.offerId, lot.remaining,
          lot.totalQty, lot.firstSeen, lot.unitCost, lot.remaining < lot.totalQty, mark));
      }
    }
    return new Result(flips, open, unmatched);
  }

  /** An offer's firstSeen as JS reads it: NaN when absent, so every comparison with it is false. */
  private static double seen(Offer o) {
    return o.firstSeen == null ? Double.NaN : (double) o.firstSeen;
  }

  private static void soldPart(Lot lot, Map<String, Closed> closed, List<AutoFlip> flips) {
    long qty = lot.totalQty - lot.remaining;
    if (qty > 0) flips.add(new AutoFlip(lot, qty, Boolean.TRUE, closed.get(lot.offerId).reason));
  }

  /** What the profit total does not include, in counts and GP (store.mjs dataHealthOf). Nothing estimated. */
  public static final class DataHealth {
    public final int openPositions;
    public final long openCost;
    public final List<Integer> openItemIds;
    public final int unmatchedSales;
    public final long unmatchedGross;
    public final int personalUseSales;
    public final long personalUseGross;

    DataHealth(int openPositions, long openCost, List<Integer> openItemIds, int unmatchedSales, long unmatchedGross,
               int personalUseSales, long personalUseGross) {
      this.openPositions = openPositions;
      this.openCost = openCost;
      this.openItemIds = Collections.unmodifiableList(openItemIds);
      this.unmatchedSales = unmatchedSales;
      this.unmatchedGross = unmatchedGross;
      this.personalUseSales = personalUseSales;
      this.personalUseGross = personalUseGross;
    }
  }

  /**
   * Unmatched sales are split between "really unmatched" and "sale of stock marked personal use": each
   * unmatched sale (oldest first) is attributed to flagged buys of the SAME account and item placed before
   * it, never more units than those buys bought.
   */
  public static DataHealth dataHealthOf(Result auto, List<Offer> personalUseBuys) {
    double openCost = 0;
    for (OpenPosition p : auto.openPositions) openCost += p.unitCost > 0 && p.remaining > 0 ? p.unitCost * p.remaining : 0;
    Map<String, List<double[]>> pools = new HashMap<>(); // {at, left}
    for (Offer b : personalUseBuys) {
      if (b == null || !(b.filled > 0)) continue;
      pools.computeIfAbsent(b.account + "|" + b.itemId, k -> new ArrayList<>())
        .add(new double[]{b.firstSeen != null ? b.firstSeen : 0, b.filled});
    }
    int unmatchedSales = 0, personalUseSales = 0;
    double unmatchedGross = 0, personalUseGross = 0;
    List<UnmatchedSell> byTime = new ArrayList<>(auto.unmatchedSells);
    byTime.sort((a, b) -> Js.compare(when(a.offer), when(b.offer)));
    for (UnmatchedSell u : byTime) {
      Offer o = u.offer;
      Long filled = o.filled > 0 ? o.filled : null;
      Long qty = u.unmatchedQty != null ? u.unmatchedQty : filled;
      Double gpPerUnit = filled != null ? (double) o.spent / filled : null;
      long covered = 0;
      for (double[] lot : pools.getOrDefault(o.account + "|" + o.itemId, Collections.emptyList())) {
        if (qty == null || !(qty > covered)) break;
        if (lot[0] > when(o) || lot[1] <= 0) continue;
        long take = (long) Math.min(lot[1], (double) (qty - covered));
        lot[1] -= take;
        covered += take;
      }
      if (covered > 0) {
        personalUseSales++;
        if (gpPerUnit != null) personalUseGross += gpPerUnit * covered;
      }
      if (qty == null) {
        unmatchedSales++;
        unmatchedGross += o.spent;
      } else if (qty - covered > 0) {
        unmatchedSales++;
        if (gpPerUnit != null) unmatchedGross += gpPerUnit * (qty - covered);
      }
    }
    Set<Integer> ids = new LinkedHashSet<>();
    for (OpenPosition p : auto.openPositions) ids.add(p.itemId);
    return new DataHealth(auto.openPositions.size(), Js.round(openCost), new ArrayList<>(ids), unmatchedSales,
      Js.round(unmatchedGross), personalUseSales, Js.round(personalUseGross));
  }

  private static double when(Offer o) {
    if (o.completedAt != null) return o.completedAt;
    if (o.updated != null) return o.updated;
    if (o.firstSeen != null) return o.firstSeen;
    return 0;
  }
}
