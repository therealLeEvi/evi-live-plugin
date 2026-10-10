package com.evi.live.journal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The journal: every GE offer EVI has seen, the flips made from them, and the player's own marks. Ported
 * from bridge/store.mjs for the self-contained plugin, and proven equal to it by the parity tests
 * (the public golden transcripts, the generated journal vectors, and a private replay of the real journal).
 *
 * <p>PURE: no file, no clock, no thread. Persistence is the {@link Journal} the caller supplies -- it is
 * handed each record BEFORE the record is applied, exactly as store.mjs writes the line and then applies
 * it, so a write that throws leaves the store unchanged. Time is always passed in. The caller confines a
 * Store to one thread (the journal's own executor); nothing here is synchronised.
 *
 * <p>Three behaviours worth knowing before "fixing" anything here, each deliberate:
 * <ul>
 *   <li>The plugin gives every offer a NEW offerId whenever it first sees it at login (every restart,
 *       relog or world hop). {@link #continuation} links that new id to the earlier record of the same GE
 *       offer -- same account, slot, item, side, price and quantity, still in that slot when its session
 *       last reported, counters not gone backwards, and only when exactly one candidate fits -- and the
 *       alias is rebuilt purely from the journal on replay.</li>
 *   <li>A 1-unit buy filled instantly at a better price than asked is a price probe and is invisible on
 *       purpose (see {@link FifoMatcher#isMarginCheck}).</li>
 *   <li>A logged-out packet ({@code offers: []}) says nothing about the market: it empties the session's
 *       slot list, not the journal's knowledge of the offers.</li>
 * </ul>
 *
 * <h3>Deliberate departures from store.mjs (the divergence list)</h3>
 * Everywhere else this class answers exactly as store.mjs does, and the parity tests hold it to that. These
 * are the only places it does not, each because a long cannot hold what a JS double silently rounds:
 * <ol>
 *   <li>{@link #recordPurchase} refuses a purchase whose total cost is past 2^53 - 1
 *       ({@value #TOO_LARGE_TO_RECORD}); JS records it with a rounded cost.</li>
 *   <li>{@link #importFlips} refuses a row whose capital + profit is past 2^53 - 1, with the bridge's own
 *       message for a bad figure; JS journals the rounded sum. The check runs AFTER both duplicate skips
 *       (same fingerprint, same content), as store.mjs reads nothing of a row it skips, so a re-import of a
 *       held trade is skipped here too (journal vectors {@code import-unsafe-duplicate}).</li>
 *   <li>{@link #state} throws ArithmeticException when a GP total reaches 2^63 (JS returns a rounded double);
 *       {@link #closePosition}, the one mutator that reads state, turns that into a {@link JournalException}
 *       ({@value #TOO_LARGE_TO_ANSWER}).</li>
 *   <li>{@link CostBasis#held} answers null without an account, where JS sums every account (the port
 *       warning of the self-contained port; the journal vectors count those calls).</li>
 * </ol>
 */
public final class Store {
  /** Where records go before they are applied: an events.jsonl appender in the plugin, a list in a test. */
  public interface Journal {
    void append(JournalRecord record);
  }

  /** A client session as the store tracks it (store.mjs {@code this.sessions} values). */
  static final class Session {
    final String account;
    long seq;
    long lastSeen;
    final boolean loggedIn;
    final List<String> slots;
    Long capturedAt;

    Session(String account, long seq, long lastSeen, boolean loggedIn, List<String> slots) {
      this.account = account;
      this.seq = seq;
      this.lastSeen = lastSeen;
      this.loggedIn = loggedIn;
      this.slots = slots;
    }
  }

  private final Journal journal;
  private final Map<String, Offer> offers = new LinkedHashMap<>();
  private final Map<String, Session> sessions = new LinkedHashMap<>();
  private final List<ManualFlip> flips = new ArrayList<>();
  private final Set<String> personalUse = new LinkedHashSet<>();
  private final Set<Integer> personalUseItems = new LinkedHashSet<>();
  private final Map<Integer, Long> personalUseKept = new LinkedHashMap<>();
  private final Map<String, String> alias = new HashMap<>();
  private final Map<String, String[]> lastSlots = new HashMap<>();
  /**
   * ENDED UNSEEN (8 Oct 2026, fixed in JS first). An offer only ends in the journal when EVI SEES it end; one that filled or
   * was cancelled while the player was logged out, its slot collected and reused in a session the plugin never reported,
   * stayed BUYING/SELLING for ever (a real case: the card kept quoting a net profit for an item already
   * sold). At each logged-in packet every slot is compared with the SAME ACCOUNT's previous logged-in session's last report:
   * an unfinished offer no longer in its slot (empty, or an offer {@link #continuation} did not link to it) ended unseen, at
   * the packet's ts. In memory only, rebuilt on replay exactly like the aliases; never a sale. Never from a logged-out
   * packet, a partial one, or within one session; withdrawn if the offer is ever seen again.
   */
  private final Map<String, LastLogin> lastLoggedIn = new HashMap<>();
  private final Map<String, Long> endedUnseen = new LinkedHashMap<>();

  /** An account's most recent logged-in packet: its session and resolved slots. */
  private static final class LastLogin {
    final String session;
    final String[] slots;

    LastLogin(String session, String[] slots) {
      this.session = session;
      this.slots = slots;
    }
  }
  private final Map<String, FifoMatcher.Closed> closed = new LinkedHashMap<>();
  private List<ImportedFlip> importedFlips = new ArrayList<>();
  private Set<String> importedFingerprints = new HashSet<>();
  private Set<String> importedContent = new HashSet<>();
  private int linkedCount;

  /**
   * The imported flip histories as they stand after every record so far (removals applied, a repeated fingerprint skipped):
   * the very objects of the records that hold them. Read-only; the import's selection uses it to take exactly the flips the
   * bridge still holds.
   */
  List<ImportedFlip> importedFlipsNow() {
    return Collections.unmodifiableList(importedFlips);
  }

  /** An empty store (a fresh install). */
  public Store(Journal journal) {
    this.journal = Objects.requireNonNull(journal, "journal");
  }

  /**
   * The bridge's constructor: replay a journal, then mark every session as not seen (a replayed session
   * is history, not a live connection).
   */
  public static Store replay(Iterable<JournalRecord> records, Journal journal) {
    Store s = new Store(journal);
    for (JournalRecord r : records) s.apply(r);
    for (Session x : s.sessions.values()) x.lastSeen = 0;
    return s;
  }

  private void append(JournalRecord r) {
    journal.append(r);
    apply(r);
  }

  /** Every offer the journal knows, in first-seen order. Read-only. */
  public Collection<Offer> offers() {
    return Collections.unmodifiableCollection(offers.values());
  }

  /** The original offerId a login-time id was linked to, or the id itself. */
  public String resolve(String offerId) {
    String a = alias.get(offerId);
    return a != null ? a : offerId;
  }

  void apply(JournalRecord rec) {
    if (rec instanceof JournalRecord.FlipMark) {
      JournalRecord.FlipMark r = (JournalRecord.FlipMark) rec;
      ManualFlip f = findFlip(r.id);
      if (f == null) return;
      if (JournalRecord.FlipMark.REOPENED.equals(r.type())) {
        f.removed = true;
        f.reopened = true;
      } else {
        f.removed = JournalRecord.FlipMark.REMOVED.equals(r.type());
      }
    } else if (rec instanceof JournalRecord.FlipRecord) {
      flips.add(((JournalRecord.FlipRecord) rec).flip);
    } else if (rec instanceof JournalRecord.PersonalUse) {
      JournalRecord.PersonalUse r = (JournalRecord.PersonalUse) rec;
      if (r.undo) personalUse.remove(resolve(r.buyId));
      else personalUse.add(resolve(r.buyId));
    } else if (rec instanceof JournalRecord.PersonalUseItem) {
      // kept = how many of this item the player holds FOR USE. A record with none (pre 1 Oct 2026) stays
      // a blanket exclusion; re-marking RAISES the count, never lowers it.
      JournalRecord.PersonalUseItem r = (JournalRecord.PersonalUseItem) rec;
      personalUseItems.add(r.itemId);
      if (r.kept != null && Js.isSafeInteger(r.kept) && r.kept >= 1 && r.kept <= Js.INT32_MAX) {
        Long had = personalUseKept.get(r.itemId);
        personalUseKept.put(r.itemId, Math.max(r.kept.longValue(), had == null ? 0 : had));
      }
    } else if (rec instanceof JournalRecord.PersonalUseItemUndo) {
      int itemId = ((JournalRecord.PersonalUseItemUndo) rec).itemId;
      personalUseItems.remove(itemId);
      personalUseKept.remove(itemId);
    } else if (rec instanceof JournalRecord.FlipsImported) {
      for (ImportedFlip f : ((JournalRecord.FlipsImported) rec).flips) {
        if (importedFingerprints.contains(f.fp)) continue;
        importedFingerprints.add(f.fp);
        importedContent.add(f.contentKey());
        importedFlips.add(f);
      }
    } else if (rec instanceof JournalRecord.FlipsImportRemoved) {
      String source = ((JournalRecord.FlipsImportRemoved) rec).source;
      List<ImportedFlip> kept = new ArrayList<>();
      for (ImportedFlip f : importedFlips) if (!Objects.equals(f.source, source)) kept.add(f);
      importedFlips = kept;
      importedFingerprints = new HashSet<>();
      importedContent = new HashSet<>();
      for (ImportedFlip f : kept) {
        importedFingerprints.add(f.fp);
        importedContent.add(f.contentKey());
      }
    } else if (rec instanceof JournalRecord.PurchaseRecorded) {
      // A purchase the player made while EVI was not watching: joins the offer pool as a finished buy,
      // marked recorded so what EVI observed and what it was told stay distinguishable.
      JournalRecord.PurchaseRecorded o = (JournalRecord.PurchaseRecorded) rec;
      offers.put(o.offerId, new Offer(-1, "BOUGHT", o.offerId, o.itemId, o.name, o.unitPrice, o.quantity, o.quantity,
        Js.multiply(o.unitPrice, o.quantity), true, null, o.account, "recorded", o.at, o.at, o.at, Boolean.TRUE));
    } else if (rec instanceof JournalRecord.PurchaseRecordRemoved) {
      offers.remove(((JournalRecord.PurchaseRecordRemoved) rec).offerId);
    } else if (rec instanceof JournalRecord.PositionClosed) {
      JournalRecord.PositionClosed r = (JournalRecord.PositionClosed) rec;
      closed.put(r.buyId, new FifoMatcher.Closed(r.reason, r.at));
    } else if (rec instanceof JournalRecord.PositionReopened) {
      closed.remove(((JournalRecord.PositionReopened) rec).buyId);
    } else if (rec instanceof JournalRecord.PacketRecord) {
      applyPacket((JournalRecord.PacketRecord) rec);
    } else {
      throw new IllegalArgumentException("unknown record " + rec.type());
    }
  }

  private void applyPacket(JournalRecord.PacketRecord r) {
    Packet p = r.packet;
    List<String> slotIds = new ArrayList<>();
    for (Packet.SlotOffer o : p.offers) slotIds.add(o.offerId);
    sessions.put(p.session, new Session(p.account, p.seq, r.received, p.loggedIn, Collections.unmodifiableList(slotIds)));
    for (Packet.SlotOffer o : p.offers) {
      if (o.empty()) continue;
      if (!o.knownStart && !offers.containsKey(o.offerId) && !alias.containsKey(o.offerId)) {
        Offer original = continuation(p, o);
        if (original != null) {
          alias.put(o.offerId, original.offerId);
          linkedCount++;
        }
      }
      boolean aliased = alias.containsKey(o.offerId);
      String key = resolve(o.offerId);
      Offer old = offers.get(key);
      if (aliased && old == null) throw new IllegalStateException("alias " + o.offerId + " points at no offer");
      // A linked continuation keeps the original record's coverage: the plugin can only say "false" for
      // an offer it first saw at login, even when EVI watched it start.
      boolean knownStart = aliased ? old.knownStart : (old != null ? old.knownStart : o.knownStart) && o.knownStart;
      // A packet with no tick reading (-1; every cancel sends one) keeps the real reading taken earlier.
      Integer ticks = o.ticksToFill != null && o.ticksToFill >= 0 ? o.ticksToFill : (old != null ? old.ticksToFill : null);
      Long firstSeen = old != null && old.firstSeen != null ? old.firstSeen : Long.valueOf(p.ts);
      Long completedAt = old != null && old.completedAt != null ? old.completedAt
        : (Offer.finished(o.state, o.total, o.filled) ? Long.valueOf(p.ts) : null);
      offers.put(key, new Offer(o.slot, o.state, key, o.itemId, o.name, o.price, o.total, o.filled, o.spent, knownStart, ticks,
        p.account, p.session, firstSeen, p.ts, completedAt, null));
      // Seen again (a linked continuation, or its own id): whatever was concluded about it is withdrawn.
      endedUnseen.remove(key);
    }
    if (p.loggedIn && p.offers.size() == 8) {
      String[] slots = new String[8];
      for (int i = 0; i < 8; i++) {
        for (Packet.SlotOffer o : p.offers) {
          if (o.slot != i) continue;
          slots[i] = o.empty() ? null : resolve(o.offerId);
          break;
        }
      }
      LastLogin prev = lastLoggedIn.get(p.account);
      if (prev != null && !Objects.equals(prev.session, p.session)) for (int i = 0; i < 8; i++) {
        String x = prev.slots[i];
        if (x == null || x.equals(slots[i]) || endedUnseen.containsKey(x)) continue;
        Offer was = offers.get(x);
        if (was == null || was.finished() || Arrays.asList(slots).contains(x)) continue;
        endedUnseen.put(x, p.ts);
      }
      lastSlots.put(p.session, slots);
      lastLoggedIn.put(p.account, new LastLogin(p.session, slots));
    }
  }

  /**
   * The earlier record of the same GE offer, for an offerId first seen at login; null unless exactly ONE
   * candidate fits. See the class comment.
   */
  Offer continuation(Packet p, Packet.SlotOffer o) {
    List<Offer> candidates = new ArrayList<>();
    String side = Offer.side(o.state);
    for (Offer x : offers.values()) {
      if (!Objects.equals(x.account, p.account) || Objects.equals(x.session, p.session) || x.slot != o.slot || x.itemId != o.itemId
        || !x.side().equals(side) || x.price != o.price || x.total != o.total || x.filled > o.filled || x.spent > o.spent) continue;
      String[] last = lastSlots.get(x.session);
      if (last == null || !Objects.equals(last[o.slot], x.offerId)) continue;
      if (x.finished() && (!x.state.equals(o.state) || x.filled != o.filled || x.spent != o.spent)) continue;
      candidates.add(x);
    }
    return candidates.size() == 1 ? candidates.get(0) : null;
  }

  /**
   * One packet from the plugin. Returns true when it was a duplicate (a seq already seen for its session)
   * and was ignored. Throws, with the bridge's exact message, when it is invalid, from a clock more than a
   * minute ahead, from a session that changed account, or when an offer's identity or counters changed.
   * Only a packet that changes something is journalled; heartbeats update freshness in memory.
   */
  public boolean ingest(JsonElement input, long now) {
    Packet p = Packet.validate(input);
    Session oldSession = sessions.get(p.session);
    if (p.ts > now + 60000) throw new JournalException("Plugin clock is ahead of EVI's clock");
    if (oldSession != null && !Objects.equals(oldSession.account, p.account)) throw new JournalException("Session account changed");
    if (oldSession != null && p.seq <= oldSession.seq) return true;
    for (Packet.SlotOffer o : p.offers) {
      Offer old = offers.get(resolve(o.offerId));
      if (old != null && (!Objects.equals(old.session, p.session) || !Objects.equals(old.account, p.account) || old.itemId != o.itemId
        || old.price != o.price || old.total != o.total || old.slot != o.slot || old.filled > o.filled || old.spent > o.spent
        || !old.side().equals(Offer.side(o.state)) || (old.finished() && !Offer.finished(o.state, o.total, o.filled))))
        throw new JournalException("Offer identity or counters changed");
    }
    boolean changed = oldSession == null || oldSession.loggedIn != p.loggedIn || !oldSession.slots.equals(slotIds(p));
    for (Packet.SlotOffer o : p.offers) {
      if (changed) break;
      if (o.empty()) continue;
      changed = differs(o);
    }
    if (changed) append(new JournalRecord.PacketRecord(now, p));
    Session session = sessions.get(p.session);
    session.lastSeen = now;
    session.seq = p.seq;
    // Queued packets are historical, not proof of a live game connection.
    session.capturedAt = p.ts;
    return false;
  }

  private static List<String> slotIds(Packet p) {
    List<String> ids = new ArrayList<>();
    for (Packet.SlotOffer o : p.offers) ids.add(o.offerId);
    return ids;
  }

  // Does this slot disagree with what the journal holds? A linked continuation is stored under its
  // original offerId and coverage, so those two fields legitimately differ; and a packet with no tick
  // reading is not disagreeing with the better value kept from when the offer filled.
  private boolean differs(Packet.SlotOffer o) {
    Offer rec = offers.get(resolve(o.offerId));
    if (rec == null) return true;
    boolean linked = alias.containsKey(o.offerId);
    if (o.slot != rec.slot || !o.state.equals(rec.state) || o.itemId != rec.itemId || !Objects.equals(o.name, rec.name)
      || o.price != rec.price || o.total != rec.total || o.filled != rec.filled || o.spent != rec.spent) return true;
    if (!linked && (!o.offerId.equals(rec.offerId) || o.knownStart != rec.knownStart)) return true;
    return o.ticksToFill != null && o.ticksToFill >= 0 && !o.ticksToFill.equals(rec.ticksToFill);
  }

  /** Everything the journal knows, as of {@code now}. See {@link StoreState}. */
  public StoreState state(long now) {
    List<StoreState.SessionView> sessionViews = new ArrayList<>();
    List<Offer> slots = new ArrayList<>();
    for (Map.Entry<String, Session> e : sessions.entrySet()) {
      Session s = e.getValue();
      boolean live = s.lastSeen > now - 30000 && s.capturedAt != null && s.capturedAt > now - 30000;
      sessionViews.add(new StoreState.SessionView(e.getKey(), s, live));
      if (!live || !s.loggedIn) continue;
      for (String id : s.slots) {
        Offer o = offers.get(resolve(id));
        if (o != null) slots.add(o);
      }
    }
    // Cancellations with nothing filled moved no items or GP: not "completed" trades.
    List<Offer> completedOffers = new ArrayList<>();
    for (Offer o : offers.values()) if (o.finished() && !(o.state.startsWith("CANCELLED") && o.filled == 0)) completedOffers.add(o);
    // Anything claimed by a manual, non-reopened review stays out of automatic matching, so no trade is
    // counted twice; a buy marked personal use stays out entirely.
    Set<String> used = new HashSet<>();
    for (ManualFlip f : flips) {
      if (f.isReopened()) continue;
      used.add(f.buyId);
      used.addAll(f.sellIdsOrSingle());
    }
    List<Offer> pool = new ArrayList<>();
    for (Offer o : completedOffers) if (!used.contains(o.offerId) && !personalUse.contains(o.offerId)) pool.add(o);
    // Offers that ended while EVI was not watching. A sell is never a sale, only what an open position may claim; a buy's
    // filled units, as last seen, are an open lot (FifoMatcher.computeAutoFlips), unless it is matched or personal use.
    List<FifoMatcher.EndedUnseen> unseenOffers = new ArrayList<>();
    List<StoreState.EndedUnseenOffer> unseen = new ArrayList<>();
    for (Map.Entry<String, Long> e : endedUnseen.entrySet()) {
      unseen.add(new StoreState.EndedUnseenOffer(e.getKey(), e.getValue()));
      Offer o = offers.get(e.getKey());
      if (o != null && ("sell".equals(o.side()) || (!used.contains(o.offerId) && !personalUse.contains(o.offerId))))
        unseenOffers.add(new FifoMatcher.EndedUnseen(o, e.getValue()));
    }
    FifoMatcher.Result auto = FifoMatcher.computeAutoFlips(pool, closed, unseenOffers);
    List<ManualFlip> manual = new ArrayList<>(), removed = new ArrayList<>();
    for (ManualFlip f : flips) (f.isRemoved() ? removed : manual).add(f);
    // Two sums, then their sum: JS's order of additions, which matters only past 2^53 (see Js.add).
    long manualProfit = 0, autoProfit = 0;
    for (ManualFlip f : manual) manualProfit = Js.add(manualProfit, f.profit);
    for (FifoMatcher.AutoFlip f : auto.flips) autoProfit = Js.add(autoProfit, f.profit);
    long netProfit = Js.add(manualProfit, autoProfit);
    // Items excluded WHOLESALE from the idle-inventory tier: every buy-derived mark, plus item marks with
    // no kept count. An item mark WITH a count is in personalUseKept instead (only the surplus is offered).
    Set<Integer> puItemIds = new LinkedHashSet<>();
    for (String buyId : personalUse) {
      Offer o = offers.get(buyId);
      if (o != null) puItemIds.add(o.itemId);
    }
    for (Integer id : personalUseItems) if (!personalUseKept.containsKey(id)) puItemIds.add(id);
    List<Offer> active = new ArrayList<>(), occupied = new ArrayList<>();
    for (Offer o : slots) {
      if (!o.finished()) active.add(o);
      if (!"EMPTY".equals(o.state)) occupied.add(o);
    }
    List<Offer> newestFirst = new ArrayList<>(completedOffers);
    newestFirst.sort((a, b) -> Js.compare(num(b.updated), num(a.updated)));
    List<StoreState.CompletedOffer> completed = new ArrayList<>();
    for (Offer o : newestFirst)
      completed.add(new StoreState.CompletedOffer(o, "sell".equals(o.side()) ? Tax.saleProceeds(o) : null, FifoMatcher.isMarginCheck(o)));
    List<Offer> puBuys = new ArrayList<>();
    for (String id : personalUse) {
      Offer o = offers.get(id);
      if (o != null) puBuys.add(o);
    }
    StoreState.ImportedSummary summary = null;
    if (!importedFlips.isEmpty()) {
      Set<Integer> items = new HashSet<>();
      Set<String> sources = new LinkedHashSet<>();
      long profit = 0, from = Long.MAX_VALUE, to = Long.MIN_VALUE;
      for (ImportedFlip f : importedFlips) {
        items.add(f.itemId);
        sources.add(f.source);
        profit = Js.add(profit, f.profit);
        from = Math.min(from, f.firstBuy);
        to = Math.max(to, f.lastSell);
      }
      summary = new StoreState.ImportedSummary(importedFlips.size(), items.size(), profit, from, to, new ArrayList<>(sources));
    }
    List<StoreState.ClosedPosition> closedPositions = new ArrayList<>();
    for (Map.Entry<String, FifoMatcher.Closed> e : closed.entrySet()) {
      Offer o = offers.get(e.getKey());
      closedPositions.add(new StoreState.ClosedPosition(e.getKey(), e.getValue().reason, e.getValue().at,
        o == null ? null : o.name, o == null ? null : o.itemId, o == null ? null : o.account));
    }
    return new StoreState(now, sessionViews, active, occupied, completed, manual, removed, auto, FifoMatcher.dataHealthOf(auto, puBuys),
      new ArrayList<>(personalUse), new ArrayList<>(puItemIds), new ArrayList<>(personalUseItems), new TreeMap<>(personalUseKept),
      new ArrayList<>(importedFlips), summary, closedPositions, linkedCount, unseen, netProfit, manual.size() + auto.flips.size());
  }

  private static double num(Long v) {
    return v == null ? Double.NaN : v;
  }

  /** How much of an item THIS account bought in the last four hours (see {@link BuyLimitUsage}). */
  public BuyLimitUsage buyLimitUsage(String account, int itemId, long now) {
    return BuyLimitUsage.of(offers.values(), account, itemId, now);
  }

  // ------------------------------------------------------------------------------------------ mutators
  //
  // Each takes the request body the bridge route takes, as a JSON object, because the JS checks are about
  // JSON types (typeof personal === 'boolean', closed defaulting only when ABSENT). Typed overloads exist
  // for the plugin's own buttons.

  /** "I don't have the rest of this purchase any more." Reversible with closed:false. */
  public void closePosition(JsonObject args, long now) {
    if (!Packet.id(args.get("buyId"))) throw new JournalException("Invalid buyId");
    String buyId = resolve(args.get("buyId").getAsString());
    JsonElement c = args.get("closed");
    if (c != null && !Js.isBoolean(c)) throw new JournalException("Missing closed flag");
    boolean close = c == null || c.getAsBoolean(); // the JS default applies only when the key is absent
    if (!close) {
      if (closed.containsKey(buyId)) append(new JournalRecord.PositionReopened(buyId));
      return;
    }
    String reason = Js.string(args.get("reason"));
    if (!"used".equals(reason) && !"sold-untracked".equals(reason)) throw new JournalException("Choose why this position is gone");
    if (closed.containsKey(buyId)) return;
    StoreState st;
    try {
      st = state(now);
    } catch (ArithmeticException e) {
      // JAVA ONLY: a journal whose GP totals are past what a long holds (2^63) cannot be answered exactly,
      // and state() refuses rather than wrap. The caller gets the journal's own refusal, never a crash.
      throw new JournalException(TOO_LARGE_TO_ANSWER);
    }
    boolean open = false;
    for (FifoMatcher.OpenPosition p : st.autoOpenPositions) open |= buyId.equals(p.buyId);
    if (!open) throw new JournalException("That purchase is not an open position.");
    append(new JournalRecord.PositionClosed(buyId, reason, now));
  }

  public void closePosition(String buyId, String reason, long now) {
    JsonObject a = new JsonObject();
    if (buyId != null) a.addProperty("buyId", buyId);
    if (reason != null) a.addProperty("reason", reason);
    closePosition(a, now);
  }

  /** Flags one observed, finished buy as bought for use, not to flip. */
  public void markPersonalUse(JsonObject args) {
    if (!Packet.id(args.get("buyId"))) throw new JournalException("Invalid buyId");
    if (!Js.isBoolean(args.get("personal"))) throw new JournalException("Missing personal flag");
    boolean personal = args.get("personal").getAsBoolean();
    String buyId = resolve(args.get("buyId").getAsString());
    Offer o = offers.get(buyId);
    // An ended-unseen buy counts as finished here: its filled units are an open lot, so the card's Personal use reaches it.
    if (o == null || !"buy".equals(o.side()) || !(o.finished() || endedUnseen.containsKey(buyId)) || !o.knownStart || !(o.filled > 0))
      throw new JournalException("Unknown or not-yet-observed buy");
    if (personal == personalUse.contains(buyId)) return;
    append(new JournalRecord.PersonalUse(!personal, buyId));
  }

  public void markPersonalUse(String buyId, boolean personal) {
    JsonObject a = new JsonObject();
    if (buyId != null) a.addProperty("buyId", buyId);
    a.addProperty("personal", personal);
    markPersonalUse(a);
  }

  /** The reply to an item-level mark: kept appears only when there is one (an exact shape test asserts it). */
  public static final class PersonalUseItemReply {
    public final int itemId;
    public final boolean personal;
    public final Long kept;

    PersonalUseItemReply(int itemId, boolean personal, Long kept) {
      this.itemId = itemId;
      this.personal = personal;
      this.kept = kept;
    }
  }

  /**
   * "This thing I own is not stock": keyed by item, for gear EVI never saw bought. kept is how many are
   * held for use, floored at 1 (marking an item not held must not switch the exclusion off).
   */
  public PersonalUseItemReply markPersonalUseItem(JsonObject args) {
    if (!Js.integer(args.get("itemId"), 1, Js.INT32_MAX)) throw new JournalException("Invalid itemId");
    if (!Js.isBoolean(args.get("personal"))) throw new JournalException("Missing personal flag");
    int itemId = (int) (double) Js.number(args.get("itemId"));
    boolean personal = args.get("personal").getAsBoolean();
    Double k = Js.number(args.get("kept"));
    Double keep = k != null && Js.isInteger(k) && k >= 0 ? Math.max(1, k) : null;
    boolean already = personalUseItems.contains(itemId);
    Long had = personalUseKept.get(itemId);
    boolean raises = personal && already && keep != null && keep > (had == null ? 0 : had);
    if (personal != already || raises) {
      if (personal) append(new JournalRecord.PersonalUseItem(itemId, keep));
      else append(new JournalRecord.PersonalUseItemUndo(itemId));
    }
    Long now = personalUseKept.get(itemId);
    return new PersonalUseItemReply(itemId, personal, personal ? now : null);
  }

  public PersonalUseItemReply markPersonalUseItem(int itemId, boolean personal, Long kept) {
    JsonObject a = new JsonObject();
    a.addProperty("itemId", itemId);
    a.addProperty("personal", personal);
    if (kept != null) a.addProperty("kept", kept);
    return markPersonalUseItem(a);
  }

  /** The Java-only refusal in closePosition when the journal's totals are past 2^63 (see there). */
  static final String TOO_LARGE_TO_ANSWER =
    "This journal's GP totals are past what EVI can count exactly, so it cannot tell which positions are open. Treat the journal as corrupted";

  /** The Java-only refusal in recordPurchase (see there). */
  static final String TOO_LARGE_TO_RECORD =
    "That purchase is too large to record: its total cost is past 9,007,199,254,740,991 GP, the most EVI can count exactly";

  /** recordPurchase's reply: {removed} | {recorded:0, offerId, duplicate:true} | {recorded:1, offerId, cost}. */
  public static final class RecordResult {
    public final Integer removed;
    public final Integer recorded;
    public final String offerId;
    public final Boolean duplicate;
    public final Long cost;

    RecordResult(Integer removed, Integer recorded, String offerId, Boolean duplicate, Long cost) {
      this.removed = removed;
      this.recorded = recorded;
      this.offerId = offerId;
      this.duplicate = duplicate;
      this.cost = cost;
    }
  }

  /**
   * A purchase EVI did not see, told to it afterwards: stored as exactly that (recorded:true), never
   * disguised as an observation, and nothing in it estimated. Undone with remove:true and its offerId.
   */
  public RecordResult recordPurchase(JsonObject a, long now) {
    if (a.has("remove") && Js.isBoolean(a.get("remove")) && a.get("remove").getAsBoolean()) {
      String offerId = Js.string(a.get("offerId"));
      if (offerId == null || !offerId.startsWith("recorded:")) throw new JournalException("Name the recorded purchase to remove");
      if (!offers.containsKey(offerId)) return new RecordResult(0, null, null, null, null);
      append(new JournalRecord.PurchaseRecordRemoved(offerId));
      return new RecordResult(1, null, null, null, null);
    }
    if (!Js.integer(a.get("itemId"), 1, Js.INT32_MAX)) throw new JournalException("A recorded purchase needs an item id");
    String name = Js.string(a.get("name"));
    if (name == null || name.isEmpty() || name.length() > 150) throw new JournalException("A recorded purchase needs the item name");
    if (!Js.integer(a.get("quantity"), 1, Js.INT32_MAX)) throw new JournalException("Quantity must be a whole number of at least one");
    if (!Js.integer(a.get("unitPrice"), 1, Js.INT32_MAX)) throw new JournalException("Price each must be a whole number of at least one gp");
    if (!Js.integer(a.get("at"), Tax.TAX_ERA_START_MS, now)) throw new JournalException("The purchase time must be in the past, and after 30 May 2025");
    int itemId = (int) (double) Js.number(a.get("itemId"));
    long quantity = (long) (double) Js.number(a.get("quantity"));
    long unitPrice = (long) (double) Js.number(a.get("unitPrice"));
    long at = (long) (double) Js.number(a.get("at"));
    // JAVA ONLY, and the one place this port refuses what the bridge accepts: a total cost past 2^53 - 1
    // (9,007,199,254,740,991 gp), which JS can no longer hold exactly and Java's running totals could
    // eventually carry past 2^63. Each factor is already capped at 2^31 - 1; a real Grand Exchange trade
    // is under 2.2e12 (max cash plus every platinum token), more than four thousand times below this.
    if (!Js.isSafe(Js.multiply(unitPrice, quantity))) throw new JournalException(TOO_LARGE_TO_RECORD);
    String given = Js.string(a.get("offerId"));
    String id = given != null && !given.isEmpty() && given.startsWith("recorded:") ? given
      : "recorded:" + itemId + ":" + at + ":" + unitPrice + ":" + quantity;
    if (offers.containsKey(id)) return new RecordResult(null, 0, id, Boolean.TRUE, null);
    String account = Js.string(a.get("account"));
    account = account != null && !account.isEmpty() ? (account.length() > 80 ? account.substring(0, 80) : account) : "recorded";
    append(new JournalRecord.PurchaseRecorded(id, itemId, name, quantity, unitPrice, at, account));
    return new RecordResult(null, 1, id, null, Js.multiply(unitPrice, quantity));
  }

  /** importFlips's reply: {removed, total} or {accepted, duplicates, total}. */
  public static final class ImportResult {
    public final Integer removed;
    public final Integer accepted;
    public final Integer duplicates;
    public final int total;

    ImportResult(Integer removed, Integer accepted, Integer duplicates, int total) {
      this.removed = removed;
      this.accepted = accepted;
      this.duplicates = duplicates;
      this.total = total;
    }
  }

  /**
   * Completed flips from another tracker: they feed ranking and NEVER the profit total. A re-import by
   * the same route is caught by fingerprint, by another route by content (item, account, quantity,
   * profit and both timestamps), and a file listing a trade twice by the batch set.
   */
  public ImportResult importFlips(JsonObject a) {
    String source = Js.string(a.get("source"));
    if (source == null || source.isEmpty() || source.length() > 40) throw new JournalException("Name the import source");
    if (a.has("remove") && Js.isBoolean(a.get("remove")) && a.get("remove").getAsBoolean()) {
      int removed = 0;
      for (ImportedFlip f : importedFlips) if (source.equals(f.source)) removed++;
      if (removed > 0) append(new JournalRecord.FlipsImportRemoved(source));
      return new ImportResult(removed, null, null, importedFlips.size());
    }
    JsonElement fe = a.get("flips");
    if (fe == null || !fe.isJsonArray() || fe.getAsJsonArray().size() == 0 || fe.getAsJsonArray().size() > 500)
      throw new JournalException("Import between 1 and 500 flips per request");
    JsonArray flipsIn = fe.getAsJsonArray();
    List<ImportedFlip> clean = new ArrayList<>();
    Set<String> batchContent = new HashSet<>();
    for (JsonElement e : flipsIn) {
      JsonObject f = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
      String fp = f == null ? null : Js.string(f.get("fp"));
      if (fp == null || fp.isEmpty() || fp.length() > 300) throw new JournalException("Every imported flip needs a fingerprint");
      String item = Js.string(f.get("item"));
      if (!Js.integer(f.get("itemId"), 1, Js.INT32_MAX) || item == null || item.isEmpty() || item.length() > 150)
        throw new JournalException("Every imported flip needs a resolved itemId and name");
      Double profitD = Js.number(f.get("profit"));
      if (!Js.integer(f.get("quantity"), 1, Js.INT32_MAX) || profitD == null || !Js.isSafeInteger(profitD) || !Js.integer(f.get("capital")))
        throw new JournalException("Invalid quantity, capital or profit");
      if (!Js.integer(f.get("firstBuy"), 1, Js.MAX_SAFE) || !Js.integer(f.get("lastSell"), 1, Js.MAX_SAFE))
        throw new JournalException("Invalid trade timestamps");
      long capital = (long) (double) Js.number(f.get("capital"));
      long profit = (long) (double) profitD;
      if (importedFingerprints.contains(fp)) continue; // already imported through this same route
      long firstBuy = (long) (double) Js.number(f.get("firstBuy"));
      long lastSell = (long) (double) Js.number(f.get("lastSell"));
      String account = Js.string(f.get("account"));
      if (account != null && account.length() > 80) account = account.substring(0, 80);
      ImportedFlip row = new ImportedFlip(fp, source, (int) (double) Js.number(f.get("itemId")), item,
        (long) (double) Js.number(f.get("quantity")), capital, capital + profit, profit, firstBuy, lastSell,
        (double) (lastSell - firstBuy) / 3600000, account);
      String content = row.contentKey();
      if (importedContent.contains(content) || batchContent.contains(content)) continue;
      // JAVA ONLY (as in recordPurchase): the row's netProceeds, capital + profit, must itself be a safe
      // integer. JS accepts a profit up to 2^53 - 1 on top of a capital up to 2^31 - 1 and journals the
      // ROUNDED sum; this port refuses the row instead, with the bridge's own message for a bad figure,
      // so no line it writes can differ from the bridge's. Both inputs are safe, so the sum cannot overflow.
      // Checked only AFTER both duplicate skips, so a row JS would skip as already held is skipped here too
      // rather than refused (the import-unsafe-duplicate journal vectors pin that order).
      if (!Js.isSafe(capital + profit)) throw new JournalException("Invalid quantity, capital or profit");
      batchContent.add(content);
      clean.add(row);
    }
    if (!clean.isEmpty()) append(new JournalRecord.FlipsImported(clean));
    return new ImportResult(null, clean.size(), flipsIn.size() - clean.size(), importedFlips.size());
  }

  /**
   * Manual review: match a finished buy to the later finished sales of the same account and item. Proceeds
   * are the exact tax calculation unless the sales filled at mixed prices, when the player must state
   * them. newId stands in for randomUUID so a test can pin it.
   */
  public ManualFlip confirm(JsonObject a, long now, Supplier<String> newId) {
    JsonElement sellIdsEl = a.get("sellIds");
    List<JsonElement> ids = new ArrayList<>();
    boolean isArray;
    if (sellIdsEl == null || sellIdsEl.isJsonNull()) {
      isArray = true;
      ids.add(a.get("sellId"));
    } else {
      isArray = sellIdsEl.isJsonArray();
      if (isArray) for (JsonElement e : sellIdsEl.getAsJsonArray()) ids.add(e);
    }
    Set<String> distinct = new HashSet<>();
    boolean bad = !isArray || ids.isEmpty() || ids.size() > 100;
    for (JsonElement e : ids) {
      if (bad) break;
      bad = !Packet.id(e);
      if (!bad) distinct.add(e.getAsString());
    }
    if (bad || distinct.size() != ids.size()) throw new JournalException("Choose distinct completed sales");
    String buyId = resolve(Js.string(a.get("buyId")));
    if (personalUse.contains(buyId))
      throw new JournalException("That purchase is marked as personal use, so it is not a flip. Unmark it first if it really was one.");
    List<String> sorted = new ArrayList<>();
    for (JsonElement e : ids) sorted.add(resolve(e.getAsString()));
    Collections.sort(sorted); // String order is UTF-16 code unit order, as Array.prototype.sort's default
    if (new HashSet<>(sorted).size() != sorted.size()) throw new JournalException("Choose distinct completed sales");
    for (ManualFlip f : flips) {
      if (f.isReopened() || !Objects.equals(f.buyId, buyId)) continue;
      List<String> theirs = new ArrayList<>(f.sellIdsOrSingle());
      theirs.sort(Store::jsDefaultCompare);
      if (!theirs.equals(sorted)) continue;
      if (f.isRemoved()) throw new JournalException("This flip was removed. Restore it before reusing this match.");
      return f;
    }
    Offer b = buyId == null ? null : offers.get(buyId);
    List<Offer> sales = new ArrayList<>();
    for (String id : sorted) sales.add(offers.get(id));
    for (Offer s : sales) {
      if (s == null || b == null || !"sell".equals(s.side()) || !s.finished() || s.filled < 1 || !Objects.equals(s.account, b.account)
        || s.itemId != b.itemId || lessJs(s.firstSeen, b.completedAt))
        throw new JournalException("Choose later completed sales for the same account/item");
    }
    long filledSum = 0, firstSeen = Long.MAX_VALUE, completedAt = Long.MIN_VALUE;
    for (Offer s : sales) {
      filledSum = Js.add(filledSum, s.filled);
      firstSeen = Math.min(firstSeen, s.firstSeen);
      completedAt = Math.max(completedAt, s.completedAt);
    }
    Offer s0 = sales.get(0);
    if (!"buy".equals(b.side()) || !"sell".equals(s0.side()) || !b.finished() || !Offer.finished(s0.state, s0.total, filledSum)
      || !Objects.equals(b.account, s0.account) || b.itemId != s0.itemId || b.filled < 1 || b.filled != filledSum
      || lessJs(firstSeen, b.completedAt) || b.spent < 1)
      throw new JournalException(b.filled != filledSum
        ? "The selected sales total " + Js.groupedUs(filledSum) + " items but the purchase was " + Js.groupedUs(b.filled)
          + ". Select every sale of this purchase, or, if the rest was used or sold outside EVI, close it under \"Still held\" instead -- the part you sold still counts."
        : "Select a completed buy and later completed sales of the same item on the same account");
    for (ManualFlip f : flips) {
      if (f.isReopened()) continue;
      boolean overlap = Objects.equals(f.buyId, buyId);
      for (String x : f.sellIdsOrSingle()) overlap |= sorted.contains(x);
      if (overlap) throw new JournalException("An offer is already matched");
    }
    boolean automatic = !a.has("netProceeds");
    double netProceeds;
    if (automatic) {
      long net = 0;
      for (Offer s : sales) {
        Tax.Proceeds pr = Tax.saleProceeds(s);
        if (pr == null || !pr.exact) throw new JournalException("Execution prices are mixed or historical; review actual net proceeds");
        net = Js.add(net, pr.net);
      }
      netProceeds = net;
    } else {
      Double given = Js.number(a.get("netProceeds"));
      netProceeds = given == null ? Double.NaN : given;
    }
    long maximum = 0;
    for (Offer s : sales) maximum = Js.add(maximum, s.spent);
    if (!Js.isSafeInteger(maximum)) throw new JournalException("The selected sales have invalid GP counters.");
    if (!(Js.isSafeInteger(netProceeds) && netProceeds >= 1))
      throw new JournalException("Net proceeds must be a whole GP amount greater than zero. Leave it blank for automatic tax calculation.");
    long net = (long) netProceeds;
    if (net > maximum)
      throw new JournalException("Net proceeds of " + Js.groupedUs(net) + " GP is more than the GE reported for the selected sale(s): "
        + Js.groupedUs(maximum) + " GP before tax. Enter what you received after tax for exactly these sales (not the profit, and not including other sales).");
    ManualFlip flip = new ManualFlip(newId.get(), buyId, sorted.size() == 1 ? sorted.get(0) : null, Collections.unmodifiableList(sorted),
      b.account, b.itemId, b.name, b.filled, b.spent, net, Js.subtract(net, b.spent), b.firstSeen, completedAt,
      (double) (completedAt - b.firstSeen) / 3600000, now, "runelite-reviewed", automatic ? "calculated-tax" : "user-reviewed");
    append(new JournalRecord.FlipRecord(flip));
    return flip;
  }

  // a < b as JS compares a number with a value that may be null (null is 0 there).
  private static boolean lessJs(Long a, Long b) {
    return (a == null ? 0 : a) < (b == null ? 0 : b);
  }

  // Array.prototype.sort's default comparator for strings (a null sorts as the string "null", as JS does).
  private static int jsDefaultCompare(String a, String b) {
    return String.valueOf(a).compareTo(String.valueOf(b));
  }

  public void setRemoved(JsonObject a) {
    String id = Js.string(a.get("id"));
    ManualFlip f = findFlip(id);
    if (!Js.isBoolean(a.get("removed")) || f == null) throw new JournalException("Unknown flip");
    if (f.isReopened()) throw new JournalException("This version was reopened for correction. Save a new reviewed match instead.");
    append(new JournalRecord.FlipMark(a.get("removed").getAsBoolean() ? JournalRecord.FlipMark.REMOVED : JournalRecord.FlipMark.RESTORED, id));
  }

  public void reopen(JsonObject a) {
    String id = Js.string(a.get("id"));
    ManualFlip f = findFlip(id);
    if (f == null) throw new JournalException("Unknown flip");
    if (!f.isReopened()) append(new JournalRecord.FlipMark(JournalRecord.FlipMark.REOPENED, id));
  }

  private ManualFlip findFlip(String id) {
    for (ManualFlip f : flips) if (Objects.equals(f.id, id)) return f;
    return null;
  }
}
