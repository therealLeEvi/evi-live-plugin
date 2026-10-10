package com.evi.live.engine;

import com.evi.live.journal.BuyLimitUsage;
import com.evi.live.journal.CostBasis;
import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Offer;
import com.evi.live.journal.StoreState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * ONE account's slice of the journal, built ONCE per poll: its live offers, its open lots, the cost basis read from them,
 * the items it has listed, and its buy-limit usage. The advice channel ({@link AdviceNotes} and the modules it composes)
 * is handed this and nothing else of the journal, so ACCOUNT SCOPING HOLDS BY CONSTRUCTION: no advice function can reach
 * {@code state().active}, {@code state().autoOpenPositions} or another account's lots, because it is never given them.
 *
 * <p>The scoping rule is the server's {@code ownedBy}, applied here and only here: a record belongs to the view when the poll
 * NAMES an account (not null, not empty) and the record carries the same one. A poll with no account (the login screen, a
 * world hop) gets {@link #nobody()}: an empty view, so it gets no advice at all -- never another account's offers (the 6 Oct
 * pure/main bug, fixed in JS first).
 *
 * <p>Why construction rather than a filter at each call site: a widening that only shows when two accounts touch the SAME
 * item, or only when this account holds nothing, slipped past every transcript three times over (the 7 Oct mutation passes:
 * the margin-gone buys, the cost basis, the crash notes' "You hold"). A view with no path back to the whole journal cannot
 * widen at all.
 *
 * <p>THE SUGGESTION TIERS READ THROUGH IT TOO (7 Oct, {@link Engine#decide}): the slot reserve ({@link #slotExposure}), the
 * listed-holding test ({@link #hasLiveSellOffer}), the persisted holding ({@link #pickPersistentOpenPosition}) and the open
 * offer's cost basis ({@link #heldCostBasis}) are methods of the view, and their list-taking kernels in {@link GeSlots},
 * {@link HoldingTiers} and {@link Prices} are package-private (kept for the JS vectors, which call them with the lists the
 * bridge passes). The JS functions scope with {@code !account || x.account === account} and are safe only because server.mjs
 * pre-filters (the PORT WARNING); here no caller can hand them a wider list than the view's own.
 *
 * <p>DELIBERATELY NOT HERE, and named as such where it is used: the player fill model reads EVERY account's offers
 * (server.mjs {@code playerFillModel}: {@code store.offers}, unscoped -- pinned by tier-safety-fill-sentence), so it takes
 * the journal's offers explicitly ({@link SafetyChecks#watchedOffers}); and the market readings (/latest, the crash watch,
 * the archive) are market-wide by nature.
 */
public final class AccountView {
  /** The account the poll names; null for {@link #nobody()}. */
  public final String account;
  /** This account's live offers, in {@code state().active} order. */
  public final List<Offer> active;
  /** Its live sells still unfilled (SELLING, and CANCELLED_SELL as server.mjs names it -- which can never be live). */
  public final List<Offer> liveSells;
  /** Its live buys still unfilled. */
  public final List<Offer> liveBuys;
  /** Its occupied slots ({@code state().occupied}: live offers and finished ones not yet collected), in that order. */
  public final List<Offer> occupied;
  /** Its open lots, in the journal's order. */
  public final List<FifoMatcher.OpenPosition> positions;
  /** Item id to unit cost from ITS lots only: {@code new Map(positions.map(p => [p.itemId, p.unitCost]))}, a later lot winning. */
  public final Map<Integer, Double> costBasis;
  /** Items it has standing in a SELLING offer (a listed holding is not stated again). */
  public final Set<Integer> listedItemIds;
  /** Its own buys in the journal (any state), the only input of {@link #buyLimitUsage}. */
  private final List<Offer> ownBuys;
  private final long now;

  private AccountView(String account, List<Offer> active, List<Offer> occupied, List<FifoMatcher.OpenPosition> positions, List<Offer> ownBuys,
                      long now) {
    this.account = account;
    this.active = Collections.unmodifiableList(active);
    this.occupied = Collections.unmodifiableList(occupied);
    List<Offer> sells = new ArrayList<>(), buys = new ArrayList<>();
    Set<Integer> listed = new LinkedHashSet<>();
    for (Offer o : active) {
      // CANCELLED_SELL is kept because server.mjs names it, but it can never match: store.mjs finished() counts a cancelled
      // offer as finished, so state().active never holds one (pinned by advice-lots-and-cancelled-sell). A JS dead clause,
      // recorded for the maintainer, not "fixed" here.
      if (("SELLING".equals(o.state) || "CANCELLED_SELL".equals(o.state)) && o.filled < o.total) sells.add(o);
      if ("BUYING".equals(o.state) && o.filled < o.total) buys.add(o);
      if ("SELLING".equals(o.state)) listed.add(o.itemId);
    }
    this.liveSells = Collections.unmodifiableList(sells);
    this.liveBuys = Collections.unmodifiableList(buys);
    this.positions = Collections.unmodifiableList(positions);
    Map<Integer, Double> cost = new LinkedHashMap<>();
    for (FifoMatcher.OpenPosition p : positions) cost.put(p.itemId, p.unitCost);
    this.costBasis = Collections.unmodifiableMap(cost);
    this.listedItemIds = Collections.unmodifiableSet(listed);
    this.ownBuys = Collections.unmodifiableList(ownBuys);
    this.now = now;
  }

  /** The server's {@code ownedBy}: a named account, a record, and the same account on it. */
  static boolean ownedBy(String account, String recordAccount) {
    return account != null && !account.isEmpty() && account.equals(recordAccount);
  }

  /** The view of a poll that names no account: nothing at all. */
  public static AccountView nobody() {
    return new AccountView(null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0);
  }

  /**
   * The view for {@code account}, from one {@code store.state(now)} and the journal's offers ({@code store.offers()}, read
   * only for this account's buy-limit usage; null: no usage known). Null or empty account: {@link #nobody()}.
   */
  public static AccountView of(StoreState state, Collection<Offer> journalOffers, String account, long now) {
    if (account == null || account.isEmpty() || state == null) return nobody();
    List<Offer> active = new ArrayList<>();
    for (Offer o : state.active) if (ownedBy(account, o.account)) active.add(o);
    List<Offer> occupied = new ArrayList<>();
    if (state.occupied != null) for (Offer o : state.occupied) if (ownedBy(account, o.account)) occupied.add(o);
    List<FifoMatcher.OpenPosition> positions = new ArrayList<>();
    if (state.autoOpenPositions != null) for (FifoMatcher.OpenPosition p : state.autoOpenPositions) if (ownedBy(account, p.account)) positions.add(p);
    List<Offer> buys = new ArrayList<>();
    if (journalOffers != null) for (Offer o : journalOffers) if (ownedBy(account, o.account) && "buy".equals(o.side())) buys.add(o);
    return new AccountView(account, active, occupied, positions, buys, now);
  }

  /** True for {@link #nobody()} (and for an account with nothing at all). */
  public boolean isEmpty() {
    return active.isEmpty() && occupied.isEmpty() && positions.isEmpty() && ownBuys.isEmpty();
  }

  // ------------------------------------------------------------------------------------- the suggestion tiers' readings

  /**
   * slotExposure for this account: of its open lots, only those the plugin CONFIRMED are in the inventory right now
   * ({@code heldPositions=}; an unconfirmed lot never invents a constraint), against its occupied slots. What it is
   * committed to, and how many sells need a slot they do not already have.
   */
  public GeSlots.Exposure slotExposure(Set<Integer> confirmedHeld) {
    List<FifoMatcher.OpenPosition> confirmed = new ArrayList<>();
    for (FifoMatcher.OpenPosition p : positions) if (confirmedHeld != null && confirmedHeld.contains(p.itemId)) confirmed.add(p);
    return GeSlots.slotExposure(confirmed, occupied, account);
  }

  /** hasLiveSellOffer: is this item standing in one of THIS account's live SELLING offers? (Never the journal's ghosts.) */
  public boolean hasLiveSellOffer(int itemId) {
    return HoldingTiers.hasLiveSellOffer(active, itemId, account);
  }

  /**
   * pickPersistentOpenPosition: this account's oldest open lot not on {@code blocklist} and not already listed for sale
   * ({@link #hasLiveSellOffer}), or null.
   */
  public FifoMatcher.OpenPosition pickPersistentOpenPosition(Set<Integer> blocklist) {
    return HoldingTiers.pickPersistentOpenPosition(positions, account, blocklist, this::hasLiveSellOffer);
  }

  /** heldCostBasis: what this account paid for {@code itemId} still held -- its lots, plus its running buys' filled units. */
  public CostBasis heldCostBasis(int itemId) {
    return Prices.heldCostBasis(positions, active, itemId, account);
  }

  /** The items of its open lots, ascending, once each ({@code slots.positionItems}). */
  public List<Integer> positionItems() {
    return new ArrayList<>(new TreeSet<Integer>(lotItems(false)));
  }

  /**
   * The items the idle-stock tier must leave to the holding tier: its lots with units left and a known unit cost (only the
   * holding tier can say what was paid for them).
   */
  public Set<Integer> positionItemsWithCost() {
    return lotItems(true);
  }

  private Set<Integer> lotItems(boolean withCost) {
    Set<Integer> out = new LinkedHashSet<>();
    for (FifoMatcher.OpenPosition p : positions)
      if (!withCost || (p.remaining > 0 && JsValues.isFinite(p.unitCost))) out.add(p.itemId);
    return out;
  }

  /**
   * How much of {@code itemId} THIS account has bought in the GE's four-hour window, at the view's clock (store.mjs
   * {@code buyLimitUsage}, an under-estimate by design). Another account's buys never count: they are not in the view.
   */
  public BuyLimitUsage buyLimitUsage(int itemId) {
    return BuyLimitUsage.of(ownBuys, account, itemId, now);
  }
}
