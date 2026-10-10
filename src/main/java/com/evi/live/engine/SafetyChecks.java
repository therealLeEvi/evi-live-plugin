package com.evi.live.engine;

import com.evi.live.journal.Offer;
import com.evi.live.market.HourBucket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Phase 4 readings that sit around a buy pick in server.mjs's GET /api/suggestion, as pure functions the orchestration
 * (Engine.decide, not ported yet) and the test composer call in the bridge's order:
 * <ol>
 *   <li>{@link #precheck}: the first two readings of {@code supportForSuggestion} -- a pick CRASHING right now is demoted
 *       with the crash alert as its warning, before anything else (its recent average says nothing about where it is
 *       heading); then a pick whose price may never come back on a thin market ({@link ThinMarket}) is demoted with that
 *       note. They run BEFORE the sell-support reading, and the crash check runs OUTSIDE the fail-open try in the bridge
 *       (see {@link PickChain#failOpen}), so its owner must call it outside that wrapper too.</li>
 *   <li>{@link #fillSentence}: after the pick, the player's own fill record for an offer this size ({@link FillModel}).</li>
 *   <li>{@link #withThinContext}: the fill-history figures stated (not warned) for whichever buy pick survived, unless a
 *       warning already said it.</li>
 * </ol>
 * Neither reading ever BLOCKS a pick: a demoted pick is still shown, flagged, if nothing better passes. Absent data means
 * no reading (an archive under {@link ThinMarket#MIN_HOURS}, no crash baseline, a thin fill band), never a guess.
 *
 * <p>CORRELATION: see {@link AdviceNotes} -- not ported, a documented seam on {@link PickChain.Options#correlationFor}.
 */
public final class SafetyChecks {
  private SafetyChecks() {}

  /**
   * The crash and thin-market readings for a candidate, or null when neither speaks (the sell-support reading then
   * runs). {@code crashing}: the crash watch's {@code isCrashing(candidate.itemId)} (null: not crashing, or no watch);
   * {@code thin}: the thin-market index, or null when the archive is too short to have one.
   */
  public static SellSupport.Result precheck(Pick candidate, CrashWatch.Crash crashing, ThinMarket.Index thin, Double targetDurationMinutes) {
    if (crashing != null) {
      SellSupport.Detail d = new SellSupport.Detail(null, null, null, null, false, null, null);
      d.crashing = true;
      return new SellSupport.Result(false, "Warning: " + CrashWatch.crashMessage(crashing, candidate.name, null), d);
    }
    if (thin != null) {
      String note = ThinMarket.note(thin.get(candidate.itemId), candidate.name, (double) ThinMarket.windowFor(targetDurationMinutes),
        (double) candidate.quantity, (double) thin.hours);
      if (note != null) {
        SellSupport.Detail d = new SellSupport.Detail(null, null, null, null, false, null, null);
        d.thinMarket = true;
        return new SellSupport.Result(false, note, d);
      }
    }
    return null;
  }

  /**
   * The offers the player fill model learns from: every journal offer watched from placement ({@code knownStart} and a
   * {@code firstSeen}), in the journal's order. Empty: no model at all.
   *
   * <p>EVERY ACCOUNT'S OFFERS, DELIBERATELY -- the one place the advice and safety code reads past the poll's
   * {@link AccountView}, and the parameter is named for it. server.mjs {@code playerFillModel} builds from
   * {@code store.offers} unscoped (an item fills the same whoever placed the offer), and the port keeps parity: pinned by
   * tier-safety-fill-sentence, where another account's sixteen fills give the sentence. Pass {@code store.offers()} here,
   * never a view's offers.
   */
  public static List<Offer> watchedOffers(Collection<Offer> allAccountsJournalOffers) {
    List<Offer> out = new ArrayList<>();
    for (Offer o : allAccountsJournalOffers) if (o.knownStart && o.firstSeen != null) out.add(o);
    return out;
  }

  /** The archive read the fill model needs: from an hour before the earliest watched offer, in seconds. */
  public static double fillModelFrom(List<Offer> watched) {
    double min = Double.POSITIVE_INFINITY;
    for (Offer o : watched) min = Math.min(min, o.firstSeen);
    return Math.floor(min / 1000) - 3600;
  }

  /**
   * The fill sentence for a BUY pick: {@code (reasoning || '') + ' ' + sentence} when the player's own record has one,
   * the reasoning unchanged otherwise. {@code volumes}: the pick's row of the latest hour; the window is the player's pace,
   * or a day without one.
   */
  public static String fillSentence(String reasoning, FillModel.Model model, Pick pick, VolumeRow volumes, Double targetDurationMinutes) {
    double window = targetDurationMinutes != null ? targetDurationMinutes : 1440;
    String sentence = FillModel.fillChanceSentence(FillModel.fillChance(model, pick.quantity, volumes, window), window);
    return sentence == null ? reasoning : (reasoning == null ? "" : reasoning) + " " + sentence;
  }

  private static final Pattern ALREADY_SAID = Pattern.compile("barely trades|did not trade at all|Fill history:");

  /**
   * The fill-history statement for whichever buy pick survived: appended once, never when a warning (or an earlier
   * statement) already carries the figures. {@code stats}: the pick's thin-market reading (null: none -- nothing appended).
   */
  public static String withThinContext(String reasoning, ThinMarket.Stats stats, Pick pick, Double targetDurationMinutes) {
    if (stats == null) return reasoning;
    String context = ThinMarket.context(stats, (double) ThinMarket.windowFor(targetDurationMinutes), (double) pick.quantity);
    String r = reasoning == null ? "" : reasoning;
    if (context == null || ALREADY_SAID.matcher(r).find()) return reasoning;
    return (r.isEmpty() ? "" : r + " ") + context;
  }

  /** The thin-market index the bridge builds: null when fewer than {@link ThinMarket#MIN_HOURS} hours are archived. */
  public static ThinMarket.Index thinIndex(List<HourBucket> fortnight) {
    return fortnight != null && fortnight.size() >= ThinMarket.MIN_HOURS ? ThinMarket.build(fortnight) : null;
  }
}
