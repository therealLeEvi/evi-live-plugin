package com.evi.live.engine;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Offer;
import com.evi.live.market.HourBucket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A fill model learned from the player's OWN offers, rather than assumed (bridge/fillModel.mjs {@code bandFor},
 * {@code buildFillModel}, {@code fillChance}, {@code fillChanceSentence}, with fillCalibration.mjs's runtime pieces
 * {@code bucketAt} and {@code predictedFillMinutes}). Pure: the offers, the archived hours and the clock are handed in.
 *
 * <p>The volume estimate answers "how long should this take if volume were spread evenly"; this answers what a player
 * asks: "how often did an offer like this one actually finish in time?" Grouped by size relative to what the item trades
 * in an hour. Price aggression matters more, but EVI only ever suggests passive prices, so every sample is the kind of
 * offer it suggests.
 *
 * <p>HONEST BY CONSTRUCTION: the offers that did NOT fill are counted (filled / gave up / still open and censored), never
 * dropped -- an early version kept only completed offers and reported "100% filled" in every band. A band with fewer than
 * {@link #MIN_SAMPLES} usable offers reports NOTHING (null), never a confident number from four trades. A one-item price
 * probe ({@link FifoMatcher#isMarginCheck}) is skipped: it would teach that tiny orders fill in seconds.
 */
public final class FillModel {
  private FillModel() {}

  /** One band of order size as a share of the item's hourly volume (the thinner side). */
  public static final class Band {
    public final String label;
    public final double max;

    Band(String label, double max) {
      this.label = label;
      this.max = max;
    }
  }

  public static final List<Band> SHARE_BANDS = Collections.unmodifiableList(java.util.Arrays.asList(
    new Band("tiny (under 1% of an hour's volume)", 0.01),
    new Band("small (1-10%)", 0.10),
    new Band("moderate (10-50%)", 0.50),
    new Band("large (50-200%)", 2),
    new Band("huge (over 200%)", Double.POSITIVE_INFINITY)));
  public static final int MIN_SAMPLES = 15;
  static final double MINUTES_PER_HOUR = 60;

  /** bandFor: the first band whose ceiling the share is under; -1 for none (NaN, Infinity). */
  public static int bandFor(double share) {
    for (int i = 0; i < SHARE_BANDS.size(); i++) if (share < SHARE_BANDS.get(i).max) return i;
    return -1;
  }

  /** One observed offer: the minutes it was up and how it ended (filled, gave up, or still open when last seen). */
  public static final class Sample {
    public final double minutes;
    public final boolean filled;
    public final boolean gaveUp;
    public final boolean open;

    Sample(double minutes, boolean filled, boolean gaveUp, boolean open) {
      this.minutes = minutes;
      this.filled = filled;
      this.gaveUp = gaveUp;
      this.open = open;
    }
  }

  public static final class BandStats {
    public final String band;
    public final List<Sample> samples;
    public final int count;
    public final int filledCount;
    /** The upper-middle filled time, or null with none filled. */
    public final Double median;
    public final boolean enough;

    BandStats(String band, List<Sample> samples, int count, int filledCount, Double median, boolean enough) {
      this.band = band;
      this.samples = Collections.unmodifiableList(samples);
      this.count = count;
      this.filledCount = filledCount;
      this.median = median;
      this.enough = enough;
    }
  }

  public static final class Model {
    public final List<BandStats> bands;
    public final int skipped;

    Model(List<BandStats> bands, int skipped) {
      this.bands = Collections.unmodifiableList(bands);
      this.skipped = skipped;
    }
  }

  /** bucketAt: the archived hour covering {@code whenMs}, by binary search over buckets sorted ascending; null for none. */
  public static HourBucket bucketAt(List<HourBucket> buckets, double whenMs) {
    double ts = Math.floor(whenMs / 1000 / 3600) * 3600;
    int lo = 0, hi = buckets.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >> 1;
      HourBucket b = buckets.get(mid);
      if (b.ts == ts) return b;
      if (b.ts < ts) lo = mid + 1;
      else hi = mid - 1;
    }
    return null;
  }

  /** predictedFillMinutes: the even-volume prediction on the thinner side; null with no usable volume. */
  public static Double predictedFillMinutes(double quantity, VolumeRow entry) {
    if (!(quantity > 0) || entry == null) return null;
    double liquidity = VolumeRow.liquidity(entry);
    if (!(liquidity > 0)) return null;
    return quantity / (liquidity / MINUTES_PER_HOUR);
  }

  /**
   * buildFillModel: one entry per band. {@code offers} are the journal's offers watched from placement (the bridge passes
   * every offer with {@code knownStart} and a finite {@code firstSeen}); {@code buckets} the archived hours, oldest first.
   * The size is the quantity OFFERED, not what happened to fill -- that is what a suggestion has to predict.
   */
  public static Model build(Iterable<Offer> offers, List<HourBucket> buckets, double now) {
    List<List<Sample>> bands = new ArrayList<>();
    for (int i = 0; i < SHARE_BANDS.size(); i++) bands.add(new ArrayList<>());
    int skipped = 0;
    if (offers != null) for (Offer o : offers) {
      double offered = o.total > 0 ? o.total : o.filled;
      if (!(offered > 0) || o.firstSeen == null || FifoMatcher.isMarginCheck(o)) {
        skipped++;
        continue;
      }
      HourBucket hour = bucketAt(buckets, o.firstSeen);
      int row = hour == null ? -1 : hour.indexOf(o.itemId);
      if (row < 0) {
        skipped++;
        continue;
      }
      double liquidity = Math.min(hour.highVolume(row), hour.lowVolume(row));
      if (!(liquidity > 0)) {
        skipped++;
        continue;
      }
      int index = bandFor(offered / liquidity);
      if (index < 0) {
        skipped++;
        continue;
      }
      boolean cancelled = o.state != null && o.state.startsWith("CANCELLED");
      boolean completedFully = o.filled >= offered && o.completedAt != null && o.completedAt != 0;
      double endedAt = o.completedAt != null ? o.completedAt : o.updated != null ? o.updated : now;
      double minutes = Math.max(0, (endedAt - o.firstSeen) / 60000);
      if (completedFully) bands.get(index).add(new Sample(minutes, true, false, false));
      else if (cancelled) bands.get(index).add(new Sample(minutes, false, true, false));
      else bands.get(index).add(new Sample(minutes, false, false, true));
    }
    List<BandStats> out = new ArrayList<>();
    for (int i = 0; i < SHARE_BANDS.size(); i++) {
      List<Sample> samples = bands.get(i);
      List<Double> filledTimes = new ArrayList<>();
      for (Sample s : samples) if (s.filled) filledTimes.add(s.minutes);
      filledTimes.sort(Double::compare);
      Double median = filledTimes.isEmpty() ? null : filledTimes.get(filledTimes.size() / 2);
      out.add(new BandStats(SHARE_BANDS.get(i).label, samples, samples.size(), filledTimes.size(), median, samples.size() >= MIN_SAMPLES));
    }
    return new Model(out, skipped);
  }

  /** How often an offer of this size actually finished within the window, from the player's own record. */
  public static final class Chance {
    public final double probability;
    public final int samples;
    public final Double medianMinutes;
    public final String band;

    Chance(double probability, int samples, Double medianMinutes, String band) {
      this.probability = probability;
      this.samples = samples;
      this.medianMinutes = medianMinutes;
      this.band = band;
    }
  }

  /**
   * fillChance: null -- never a guess -- when the band is too thin or the inputs are unusable. An offer still running when
   * last seen says nothing about a window longer than it was up for, so it is left out of that window's denominator.
   */
  public static Chance fillChance(Model model, double quantity, VolumeRow volumeEntry, double withinMinutes) {
    if (model == null || !(quantity > 0) || !(withinMinutes > 0) || volumeEntry == null) return null;
    double liquidity = VolumeRow.liquidity(volumeEntry);
    if (!(liquidity > 0)) return null;
    int i = bandFor(quantity / liquidity);
    BandStats band = i < 0 || i >= model.bands.size() ? null : model.bands.get(i);
    if (band == null || !band.enough) return null;
    int usable = 0, succeeded = 0;
    for (Sample s : band.samples) {
      if (!(s.filled || s.gaveUp || s.minutes >= withinMinutes)) continue;
      usable++;
      if (s.filled && s.minutes <= withinMinutes) succeeded++;
    }
    if (usable < MIN_SAMPLES) return null;
    return new Chance((double) succeeded / usable, usable, band.median, band.band);
  }

  /**
   * fillChanceSentence: one plain sentence for a suggestion's reasoning, naming how many of the player's own offers it rests
   * on so a thin-but-usable band reads as the weak evidence it is; null with nothing trustworthy to say.
   */
  public static String fillChanceSentence(Chance chance, double withinMinutes) {
    if (chance == null) return null;
    double hours = withinMinutes / 60;
    String window = hours >= 1
      ? (hours % 1 == 0 ? JsValues.text(hours) : JsValues.toFixed(hours, 1)) + " hour" + (hours == 1 ? "" : "s")
      : JsValues.text(JsValues.jsRound(withinMinutes)) + " minutes";
    // No typical time when NONE of the offers filled (the maintainer, 7 Oct 2026, fixed in JS first): the band's median is
    // then null, which used to print "typically 0 minutes" beside "0% finished".
    Double median = chance.medianMinutes;
    String typical = median == null || !JsValues.isFinite(median) ? ""
      : ", typically " + (median < 90 ? JsValues.text(JsValues.jsRound(median)) + " minutes" : JsValues.toFixed(median / 60, 1) + " hours");
    return "Of your own past offers this size relative to the item's trading, " + JsValues.text(JsValues.jsRound(chance.probability * 100)) + "% finished within "
      + window + " (" + chance.samples + " offers" + typical + "). That is your own record, not a forecast.";
  }
}
