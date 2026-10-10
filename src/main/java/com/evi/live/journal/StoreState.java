package com.evi.live.journal;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;

/**
 * Everything store.mjs's {@code state(now)} returns, field for field (the bridge serves it as
 * {@code GET /api/state}). Built fresh on every call; FIFO is recomputed each time, as in JS.
 *
 * <p>MACHINE-WIDE BY DESIGN, like the JS: it lists every account's offers, flips and positions, and
 * {@link #netProfit} sums all of them (the maintainer's 26 Sept decision about the profit line). Anything that
 * speaks for ONE account must filter by account itself, by equality, with a non-null account -- never
 * the {@code !account || x.account === account} shape that leaked one account's holdings to another on 6 Oct.
 */
public final class StoreState {
  public final int version = 1;
  public final long serverTime;
  public final List<SessionView> sessions;
  /** Offers in a live, logged-in session's slots that are not finished. */
  public final List<Offer> active;
  /** Offers in a live, logged-in session's slots, finished-but-uncollected included. */
  public final List<Offer> occupied;
  /** Finished offers (empty cancels left out), newest update first. */
  public final List<CompletedOffer> completed;
  public final List<ManualFlip> flips;
  public final List<ManualFlip> removedFlips;
  public final List<FifoMatcher.AutoFlip> autoFlips;
  public final List<FifoMatcher.OpenPosition> autoOpenPositions;
  public final List<FifoMatcher.UnmatchedSell> autoUnmatchedSells;
  public final FifoMatcher.DataHealth dataHealth;
  public final List<String> personalUseBuyIds;
  public final List<Integer> personalUseItemIds;
  public final List<Integer> personalUseItems;
  /** Item id to kept count. Sorted ascending, because a JS object with integer keys iterates that way. */
  public final SortedMap<Integer, Long> personalUseKept;
  public final List<ImportedFlip> importedFlips;
  /** Null when nothing was imported. */
  public final ImportedSummary importedSummary;
  public final List<ClosedPosition> closedPositions;
  public final int linkedLoginOffers;
  /** Offers that ended while EVI was not watching (store.mjs {@code endedUnseen}), in the order they were found gone. */
  public final List<EndedUnseenOffer> endedUnseen;
  public final long netProfit;
  public final int tradeCount;

  StoreState(long serverTime, List<SessionView> sessions, List<Offer> active, List<Offer> occupied, List<CompletedOffer> completed,
             List<ManualFlip> flips, List<ManualFlip> removedFlips, FifoMatcher.Result auto, FifoMatcher.DataHealth dataHealth,
             List<String> personalUseBuyIds, List<Integer> personalUseItemIds, List<Integer> personalUseItems,
             SortedMap<Integer, Long> personalUseKept, List<ImportedFlip> importedFlips, ImportedSummary importedSummary,
             List<ClosedPosition> closedPositions, int linkedLoginOffers, List<EndedUnseenOffer> endedUnseen, long netProfit, int tradeCount) {
    this.serverTime = serverTime;
    this.sessions = Collections.unmodifiableList(sessions);
    this.active = Collections.unmodifiableList(active);
    this.occupied = Collections.unmodifiableList(occupied);
    this.completed = Collections.unmodifiableList(completed);
    this.flips = Collections.unmodifiableList(flips);
    this.removedFlips = Collections.unmodifiableList(removedFlips);
    this.autoFlips = auto.flips;
    this.autoOpenPositions = auto.openPositions;
    this.autoUnmatchedSells = auto.unmatchedSells;
    this.dataHealth = dataHealth;
    this.personalUseBuyIds = Collections.unmodifiableList(personalUseBuyIds);
    this.personalUseItemIds = Collections.unmodifiableList(personalUseItemIds);
    this.personalUseItems = Collections.unmodifiableList(personalUseItems);
    this.personalUseKept = Collections.unmodifiableSortedMap(personalUseKept);
    this.importedFlips = Collections.unmodifiableList(importedFlips);
    this.importedSummary = importedSummary;
    this.closedPositions = Collections.unmodifiableList(closedPositions);
    this.linkedLoginOffers = linkedLoginOffers;
    this.endedUnseen = Collections.unmodifiableList(endedUnseen);
    this.netProfit = netProfit;
    this.tradeCount = tradeCount;
  }

  /** A client session: {id, account, seq, lastSeen, loggedIn, slots, capturedAt?, live}. */
  public static final class SessionView {
    public final String id;
    public final String account;
    public final long seq;
    public final long lastSeen;
    public final boolean loggedIn;
    /** The packet's offerIds as sent (not resolved through aliases), EMPTY slots included. */
    public final List<String> slots;
    /** Null after a bridge restart until the session's next packet: a replayed session was never captured live. */
    public final Long capturedAt;
    public final boolean live;

    SessionView(String id, Store.Session s, boolean live) {
      this.id = id;
      this.account = s.account;
      this.seq = s.seq;
      this.lastSeen = s.lastSeen;
      this.loggedIn = s.loggedIn;
      this.slots = s.slots;
      this.capturedAt = s.capturedAt;
      this.live = live;
    }
  }

  /** A finished offer as listed: the offer, its exact sale proceeds (sells only) and whether it was a probe. */
  public static final class CompletedOffer {
    public final Offer offer;
    /** Null for a buy, and for a sale with no usable tax calculation. */
    public final Tax.Proceeds proceeds;
    public final boolean marginCheck;

    CompletedOffer(Offer offer, Tax.Proceeds proceeds, boolean marginCheck) {
      this.offer = offer;
      this.proceeds = proceeds;
      this.marginCheck = marginCheck;
    }
  }

  public static final class ImportedSummary {
    public final int count;
    public final int items;
    public final long profit;
    public final long from;
    public final long to;
    public final List<String> sources;

    ImportedSummary(int count, int items, long profit, long from, long to, List<String> sources) {
      this.count = count;
      this.items = items;
      this.profit = profit;
      this.from = from;
      this.to = to;
      this.sources = Collections.unmodifiableList(sources);
    }
  }

  /** {offerId, at}: an offer found gone at its account's next login, and that packet's ts. */
  public static final class EndedUnseenOffer {
    public final String offerId;
    public final long at;

    EndedUnseenOffer(String offerId, long at) {
      this.offerId = offerId;
      this.at = at;
    }
  }

  /** A closed position, with the buy's name, item and account when the buy is still known (null otherwise). */
  public static final class ClosedPosition {
    public final String buyId;
    public final String reason;
    public final long at;
    public final String item;
    public final Integer itemId;
    public final String account;

    ClosedPosition(String buyId, String reason, long at, String item, Integer itemId, String account) {
      this.buyId = buyId;
      this.reason = reason;
      this.at = at;
      this.item = item;
      this.itemId = itemId;
      this.account = account;
    }
  }
}
