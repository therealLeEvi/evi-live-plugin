package com.evi.live.engine;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Offer;
import com.evi.live.market.LatestPrices;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The advice channel the plugin draws under "Active offers": server.mjs's {@code relistAdvice} array, composed for ONE
 * account, in the bridge's order -- crash notes, then sell-below-break-even, margin-gone buys, relist advice, buy progress
 * and holdings. Pure: the account's view, the prices, the pace and the crash watch are handed in.
 *
 * <p>ACCOUNT SCOPING HOLDS BY CONSTRUCTION. Everything this class (and every module it composes) knows about offers,
 * positions and cost basis comes from one {@link AccountView}, built once per poll for the account the poll names. There is
 * no journal state here at all -- no {@code state().active}, no {@code autoOpenPositions}, no other account's lots -- so no
 * line below can widen to another account however it is edited. A poll with no account gets {@link AccountView#nobody()}
 * and therefore no advice (the 6 Oct pure/main bug, fixed in JS first). The only other inputs are MARKET-WIDE by nature
 * and named so: /latest and the crash watch.
 *
 * <p>Each module's own answer is TRIMMED to the fields the server sends (only the relist notes go out whole), so the
 * notes here are exactly the JSON the plugin parses.
 *
 * <p>CORRELATION IS NOT HERE AND NOT PORTED (a documented seam): the bridge's correlation gate needs more than 60 six-hour
 * blocks of archive (over 15 days) while the plugin keeps 14, so it could never arm. When retention allows, it plugs into
 * {@link PickChain.Options#correlationFor}, which every chain already honours.
 */
public final class AdviceNotes {
  private AdviceNotes() {}

  /** The inputs of one poll's advice. Everything may be null; a null view is {@link AccountView#nobody()}. */
  public static final class Inputs {
    /** The poll's account, as {@link AccountView#of} built it: its offers, lots and cost basis, and nothing else's. */
    public AccountView view;
    /** MARKET-WIDE: /latest as the bridge last fetched it (null: no prices -- no market-relative advice, holdings unpriced). */
    public LatestPrices marketLatest;
    /** The player's pace in minutes (null: none set -- relist falls back to 1440, buy progress says nothing). */
    public Double targetDurationMinutes;
    /** Items slotFill already says "may not fill in time" about ({@code likelyToFillInTime === false}). */
    public Set<Integer> slowFillItemIds;
    /** The item this poll suggests (null: none), which the holdings lines skip. */
    public Integer suggestedItemId;
    /** MARKET-WIDE: the crash watch (null: none running, so no crash notes). */
    public CrashWatch.Watch crashWatch;
    /** The clock, in ms. */
    public double now;
  }

  /** relistAdvice: [...crashNotes, ...sellNotes, ...buyNotes, ...relist, ...buyProgressNotes, ...holdingNotes]. */
  public static List<AdviceNote> compose(Inputs in) {
    AccountView view = in.view == null ? AccountView.nobody() : in.view;

    List<AdviceNote> sellNotes = new ArrayList<>();
    for (AdviceNote n : SellAdvice.forAccount(view)) {
      AdviceNote t = new AdviceNote(n.itemId);
      t.message = n.message;
      t.belowBreakEven = true;
      sellNotes.add(t);
    }

    List<AdviceNote> buyNotes = new ArrayList<>();
    for (AdviceNote n : BuyAdvice.forAccount(view, in.marketLatest)) {
      AdviceNote t = new AdviceNote(n.itemId);
      t.name = n.name;
      t.message = n.message;
      t.belowBreakEven = false;
      buyNotes.add(t);
    }

    List<AdviceNote> relist = Relist.forAccount(view, in.marketLatest, in.targetDurationMinutes != null ? in.targetDurationMinutes : 1440, in.now);

    List<AdviceNote> progressNotes = new ArrayList<>();
    for (AdviceNote n : BuyProgress.forAccount(view, in.targetDurationMinutes, in.slowFillItemIds == null ? new HashSet<>() : in.slowFillItemIds, in.now)) {
      AdviceNote t = card(n);
      t.belowBreakEven = false;
      progressNotes.add(t);
    }

    List<AdviceNote> holdingNotes = new ArrayList<>();
    for (AdviceNote n : HoldingsAdvice.forAccount(view, in.marketLatest, in.suggestedItemId)) {
      AdviceNote t = card(n);
      t.holding = true;
      t.belowBreakEven = false;
      t.buyId = n.buyId;                                  // the lot, for the line menu; absent with no id
      holdingNotes.add(t);
    }

    List<AdviceNote> out = new ArrayList<>(crashNotes(in.crashWatch, view));
    out.addAll(sellNotes);
    out.addAll(buyNotes);
    out.addAll(relist);
    out.addAll(progressNotes);
    out.addAll(holdingNotes);
    return out;
  }

  /** {itemId, name, message, level, label, figures}: the card fields the server keeps from a module's note. */
  private static AdviceNote card(AdviceNote n) {
    AdviceNote t = new AdviceNote(n.itemId);
    t.name = n.name;
    t.message = n.message;
    t.level = n.level;
    t.label = n.label;
    t.figures = n.figures;
    return t;
  }

  /**
   * {@code Object.fromEntries(offers.map(o => [String(o.itemId), lookupItemPrice(latest, o.itemId)]).filter(([, p]) => p))}:
   * MARKET-WIDE prices, keyed only by the items of the offers given (a view's own).
   */
  static Map<Integer, AdviceNote.Price> pricesFor(LatestPrices marketLatest, List<Offer> offers) {
    Map<Integer, AdviceNote.Price> out = new LinkedHashMap<>();
    for (Offer o : offers) {
      ItemPrice p = Prices.lookupItemPrice(marketLatest, o.itemId);
      if (p != null) out.put(o.itemId, AdviceNote.Price.of(p));
    }
    return out;
  }

  /**
   * Crash alerts for this player's OWN items -- running offers, then stock EVI knows is held -- one per item (the first
   * reason wins), each a complete sentence: what is happening, why the player is being told, the measured history, no
   * forecast -- as a short line and a tooltip detail (crashNote). Nothing is cancelled or relisted for them. Both lists come from the view, so an account holding nothing hears
   * of nothing (the 7 Oct M14 widening fell back to other accounts' positions exactly then).
   */
  static List<AdviceNote> crashNotes(CrashWatch.Watch watch, AccountView view) {
    List<AdviceNote> out = new ArrayList<>();
    if (watch == null) return out;
    Set<Integer> told = new HashSet<>();
    for (Offer o : view.active) {
      long left = Math.max(0, o.total - o.filled);
      if ("BUYING".equals(o.state) && left > 0)
        crashNote(out, told, watch, o.itemId, o.name, "Your buy for " + JsValues.localeUs(o.total) + " is still running (" + JsValues.localeUs(o.filled) + " bought).");
      else if ("SELLING".equals(o.state) && left > 0)
        crashNote(out, told, watch, o.itemId, o.name, "Your sell has " + JsValues.localeUs(left) + " still listed at " + JsValues.localeUs(o.price) + " gp.");
    }
    // A lot whose sell listing ENDED while EVI was not watching may have been sold, so its note never says "You hold": it
    // states EVI's record and that the outcome is unknown, like its holding card (server.mjs, 8 Oct 2026).
    for (FifoMatcher.OpenPosition p : view.positions) {
      String record = JsValues.localeUs(p.remaining) + " bought at " + JsValues.gp(p.unitCost) + " gp each";
      FifoMatcher.EndedUnseenMark gone = p.endedUnseen != null && p.endedUnseen.price > 0 ? p.endedUnseen : null;
      boolean some = gone != null && gone.quantity > 0 && gone.quantity < p.remaining;
      crashNote(out, told, watch, p.itemId, p.item, gone != null
        ? "EVI's record: " + record + ". A listing of " + (some ? JsValues.localeUs(gone.quantity) + " of them" : "them") + " at "
          + JsValues.localeUs(gone.price) + " gp ended while EVI wasn't watching, so whether it sold is unknown."
        : "You hold " + record + ".");
    }
    return out;
  }

  private static void crashNote(List<AdviceNote> out, Set<Integer> told, CrashWatch.Watch watch, int itemId, String name, String mine) {
    CrashWatch.Crash c = watch.isCrashing(itemId);
    if (c == null || told.contains(itemId)) return;
    told.add(itemId);
    AdviceNote n = new AdviceNote(itemId);
    n.name = name;
    // A short line and the tooltip's detail (crashNote, 8 Oct 2026), not the one long sentence.
    CrashWatch.Note text = CrashWatch.crashNote(c, name, mine);
    n.message = text.message;
    n.detail = text.detail;
    n.belowBreakEven = false;
    n.level = "warn";
    n.label = "Crashing";
    n.figures = "One of yours"; // `mine` is always given here, so never 'Watch before buying'
    out.add(n);
  }
}
