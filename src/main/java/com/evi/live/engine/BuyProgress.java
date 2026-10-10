package com.evi.live.engine;

import com.evi.live.journal.Offer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "This buy has been open a while and here is how far it has got." (bridge/buyProgress.mjs {@code buyProgressAdvice}).
 * Pure; the clock is passed in.
 *
 * <p>IT STATES FACTS AND NEVER PREDICTS A FILL. A planned "this buy is dead" warning rested on "no buy gained a unit after
 * six hours", and re-measured (1 Oct, tools/buy-gain-timing.mjs) that was an ARTIFACT: packets only arrive while RuneLite is
 * open, no offer was watched past 4.51 hours, so of course none was seen to gain after six. So this says how long the buy
 * has been open, how much has filled, and the pace the player THEMSELVES set -- never that the offer is finished, never a
 * fill time, never "cancel". With no pace there is no bar and nothing is said: EVI must not invent an expectation the player
 * never expressed.
 */
public final class BuyProgress {
  private BuyProgress() {}

  /** An in-progress BUY offer: {itemId, name, total, filled, firstSeen}; NaN/null for what the caller did not have. */
  public static final class BuyOffer {
    public final Integer itemId;
    public final String name;
    public final double total;
    public final double filled;
    public final Double firstSeen;

    public BuyOffer(Integer itemId, String name, double total, double filled, Double firstSeen) {
      this.itemId = itemId;
      this.name = name;
      this.total = total;
      this.filled = filled;
      this.firstSeen = firstSeen;
    }
  }

  /** since(): "45m", "6h", "6.5h", "14h" -- short enough for the card's figures row. */
  static String since(double ms) {
    double mins = JsValues.jsRound(ms / 60000);
    if (mins < 90) return JsValues.text(mins) + "m";
    double h = mins / 60;
    // A whole number of hours reads as "6h", not "6.0h": the decimal implies a precision this does not have.
    if (Math.abs(h - JsValues.jsRound(h)) < 0.05) return JsValues.text(JsValues.jsRound(h)) + "h";
    return (h < 10 ? JsValues.toFixed(h, 1) : JsValues.text(JsValues.jsRound(h))) + "h";
  }

  /**
   * buyProgressAdvice: at most {@code max} notes, longest-open first (ties keep offer order). {@code targetDurationMinutes}
   * null, not finite or not above 0: nothing. {@code alreadyFlagged}: items the sidebar is already warning about through
   * slotFill ("May not fill in time"), skipped so one offer never produces two overlapping cards (null: none).
   * <p>Package-private on purpose: outside the engine the only way in is {@link #forAccount}, which reads ONE account's
   * records from its {@link AccountView}. The parity harness (same package) drives this record-level core directly.
   */
  static List<AdviceNote> buyProgressAdvice(List<BuyOffer> offers, Double targetDurationMinutes, Set<Integer> alreadyFlagged, double now, double max) {
    List<AdviceNote> out = new ArrayList<>();
    if (targetDurationMinutes == null || !JsValues.isFinite(targetDurationMinutes) || targetDurationMinutes <= 0) return out;
    List<Double> order = new ArrayList<>();
    for (BuyOffer o : offers) {
      if (o == null || o.itemId == null || o.firstSeen == null || !JsValues.isFinite(o.firstSeen)) continue;
      double total = JsValues.isFinite(o.total) ? o.total : 0;
      double filled = JsValues.isFinite(o.filled) ? o.filled : 0;
      if (!(total > 0) || filled >= total) continue; // complete: nothing to say
      if (alreadyFlagged != null && alreadyFlagged.contains(o.itemId)) continue; // slotFill is already speaking about it
      double openMs = now - o.firstSeen;
      if (!(openMs > targetDurationMinutes * 60000)) continue;
      String name = o.name != null && !o.name.isEmpty() ? o.name : "item " + o.itemId;
      AdviceNote n = new AdviceNote(o.itemId);
      n.name = name;
      // 'caution' rather than 'warn': nothing has gone wrong, and the player may be content to wait.
      n.level = "caution";
      n.label = filled == 0 ? "No fills yet" : "Part filled";
      n.figures = JsValues.gp(filled) + "/" + JsValues.gp(total) + " · open " + since(openMs);
      n.openMinutes = JsValues.jsRound(openMs / 60000);
      n.filled = filled;
      n.total = total;
      n.message = "Your pace is " + since(targetDurationMinutes * 60000) + ". "
        + "EVI is not predicting whether it will fill -- this is the clock and the progress, nothing more.";
      out.add(n);
      order.add(openMs);
    }
    // Longest open first: a stable sort on (b - a), as the JS sorts.
    List<Integer> idx = new ArrayList<>();
    for (int i = 0; i < out.size(); i++) idx.add(i);
    idx.sort((a, b) -> {
      double d = order.get(b) - order.get(a);
      return d > 0 ? 1 : d < 0 ? -1 : 0;
    });
    List<AdviceNote> sorted = new ArrayList<>();
    for (int i : idx) sorted.add(out.get(i));
    int keep = (int) Math.min(sorted.size(), sliceEnd(Math.max(0, max)));
    return new ArrayList<>(sorted.subList(0, keep));
  }

  /**
   * buyProgressAdvice for ONE account (server.mjs): its live buys, against the pace the player set (null: none -- nothing is
   * said), skipping the items slotFill already flags, with the default cap.
   */
  public static List<AdviceNote> forAccount(AccountView view, Double targetDurationMinutes, Set<Integer> alreadyFlagged, double now) {
    List<BuyOffer> offers = new ArrayList<>();
    for (Offer o : view.liveBuys) offers.add(new BuyOffer(o.itemId, o.name, o.total, o.filled, o.firstSeen == null ? null : (double) o.firstSeen));
    return buyProgressAdvice(offers, targetDurationMinutes, alreadyFlagged, now);
  }

  /** The default cap: {@code max = 3}. */
  static List<AdviceNote> buyProgressAdvice(List<BuyOffer> offers, Double targetDurationMinutes, Set<Integer> alreadyFlagged, double now) {
    return buyProgressAdvice(offers, targetDurationMinutes, alreadyFlagged, now, 3);
  }

  /** {@code slice(0, end)}'s end: ToIntegerOrInfinity (NaN is 0, a fraction truncates). */
  static double sliceEnd(double end) {
    if (Double.isNaN(end)) return 0;
    if (Double.isInfinite(end)) return end;
    return end < 0 ? Math.ceil(end) : Math.floor(end);
  }
}
