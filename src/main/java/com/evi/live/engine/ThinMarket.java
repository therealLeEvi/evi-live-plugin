package com.evi.live.engine;

import com.evi.live.market.HourBucket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * "EVI quotes the price it last sold for -- how realistic is buying at that price again?" (bridge/thinMarket.mjs). Pure:
 * the archived hours are handed in, nothing is read or cached here (the bridge rebuilds its index at most every six hours
 * from a fortnight of archive; whoever wires this owns that cache).
 *
 * <p>WHAT IT MEASURES, per item, from the archive only: CADENCE -- in how many of the archived hours the item traded at
 * all (a low price AND sell-side units both above zero), and units a day; RECURRENCE -- for each hour it traded, whether
 * any hour within the window traded at or below THAT hour's own price. Measured at the item's own level, so an item whose
 * price drifted is not scored "always available" because it used to be cheaper. Every figure is a count over hours that
 * really happened.
 *
 * <p>WHY IT MATTERS: it gates the history tier, which has no liquidity floor. The Berserker icon that prompted it traded
 * in 43 of 1,439 hours and its own price came back within 4 hours in 7% of cases. The measured bands (60 days, 400 items):
 * under 10% of hours 29% within 4h and a median of ONE unit; 10-40% of hours 67% within 4h with 7 units (a real trade --
 * which is why {@link #THIN_CADENCE} is a tenth and not a quarter).
 *
 * <p>FAIL OPEN: an item with too little archive gets no note at all, never a quiet pass ("EVI has not watched this long
 * enough" must never read as "this is fine"). But an item ABSENT from an archive long enough to judge traded in NONE of
 * those hours -- a count, not missing data -- and that is the strongest warning.
 *
 * <p>Numbers only, never a forecast, and never a block: the caller demotes the pick and shows the note.
 */
public final class ThinMarket {
  private ThinMarket() {}

  /** WINDOWS: the windows worth precomputing; a player's own trade duration is matched to the nearest. */
  public static final int[] WINDOWS = {1, 4, 12, 24};
  /** MIN_HOURS: below this many archived hours there is nothing to judge an item on. */
  public static final int MIN_HOURS = 72;
  /** THIN_RECURRENCE: fewer than half the item's own price levels coming back is a coin flip at best. */
  public static final double THIN_RECURRENCE = 0.5;
  /** THIN_CADENCE: trading in under a tenth of hours is thin however well its prices recur. */
  public static final double THIN_CADENCE = 0.10;

  /**
   * One item's reading. {@link #recurrence} and {@link #unitsWithin} are per window ({@link #WINDOWS} order): null when no
   * hour could be judged for that window (JS {@code null}), and null for a window the reading does not carry at all (JS
   * {@code undefined}, which only a hand-made reading can produce) -- both read as "nothing to say".
   */
  public static final class Stats {
    public final double hours;
    public final double hoursTraded;
    public final double cadence;
    public final double unitsPerDay;
    final Double[] recurrence;
    final Double[] unitsWithin;
    /** The LAST window's sample count, as the JS leaves it ({@code stats.samples} is overwritten per window). */
    public final double samples;

    public Stats(double hours, double hoursTraded, double cadence, double unitsPerDay, Double[] recurrence, Double[] unitsWithin, double samples) {
      this.hours = hours;
      this.hoursTraded = hoursTraded;
      this.cadence = cadence;
      this.unitsPerDay = unitsPerDay;
      this.recurrence = recurrence.clone();
      this.unitsWithin = unitsWithin.clone();
      this.samples = samples;
    }

    /** {@code stats.recurrence[w]}: null for none or for a window that is not one of {@link #WINDOWS}. */
    public Double recurrence(double w) {
      int i = windowIndex(w);
      return i < 0 ? null : recurrence[i];
    }

    /** {@code stats.unitsWithin[w]}. */
    public Double unitsWithin(double w) {
      int i = windowIndex(w);
      return i < 0 ? null : unitsWithin[i];
    }
  }

  /** The index: how many archived hours it was built on, and each item that traded in any of them. */
  public static final class Index {
    public final int hours;
    private final TreeMap<Integer, Stats> byItem;

    Index(int hours, TreeMap<Integer, Stats> byItem) {
      this.hours = hours;
      this.byItem = byItem;
    }

    /** The item's reading, or null: an item absent from a non-empty index traded in NONE of its hours. */
    public Stats get(int itemId) {
      return byItem.get(itemId);
    }

    /** Every item, in ascending id order. */
    public Map<Integer, Stats> all() {
      return Collections.unmodifiableMap(byItem);
    }
  }

  static int windowIndex(double w) {
    for (int i = 0; i < WINDOWS.length; i++) if (WINDOWS[i] == w) return i;
    return -1;
  }

  /** {@code WINDOWS.includes(w) ? w : 12}. */
  static int windowOr12(Double w) {
    return w != null && windowIndex(w) >= 0 ? (int) (double) w : 12;
  }

  /**
   * buildThinMarketIndex: archived hourly buckets, oldest first (null or empty: an index of 0 hours). An hour counts for
   * an item only when its low price AND its sell-side volume are both above zero.
   */
  public static Index build(List<HourBucket> buckets) {
    int hours = buckets == null ? 0 : buckets.size();
    TreeMap<Integer, Stats> out = new TreeMap<>();
    if (hours == 0) return new Index(0, out);
    // The grid, per item: the hour's low price and units (0 where it did not trade). Integers, as the archive is.
    TreeMap<Integer, long[][]> grid = new TreeMap<>();
    TreeMap<Integer, double[]> totals = new TreeMap<>(); // {traded, units}
    for (int i = 0; i < hours; i++) {
      HourBucket b = buckets.get(i);
      for (int r = 0; r < b.size(); r++) {
        long lo = b.avgLow(r), lv = b.lowVolume(r);
        if (lo == HourBucket.NONE || !(lo > 0) || !(lv > 0)) continue;
        int id = b.id(r);
        long[][] s = grid.computeIfAbsent(id, k -> new long[][]{new long[hours], new long[hours]});
        double[] t = totals.computeIfAbsent(id, k -> new double[2]);
        s[0][i] = lo;
        s[1][i] = lv;
        t[0]++;
        t[1] += lv;
      }
    }
    for (Map.Entry<Integer, long[][]> e : grid.entrySet()) {
      long[] lo = e.getValue()[0], lv = e.getValue()[1];
      double[] t = totals.get(e.getKey());
      Double[] recurrence = new Double[WINDOWS.length], unitsWithin = new Double[WINDOWS.length];
      int lastSamples = 0;
      for (int k = 0; k < WINDOWS.length; k++) {
        int w = WINDOWS[k];
        int samples = 0, matched = 0;
        double[] units = new double[Math.max(0, hours - w)];
        int n = 0;
        for (int i = 0; i + w < hours; i++) {
          long price = lo[i];
          if (!(price > 0)) continue;
          samples++;
          double available = 0;
          for (int j = i + 1; j <= i + w; j++) if (lo[j] > 0 && lo[j] <= price) available += lv[j];
          if (available > 0) matched++;
          units[n++] = available;
        }
        recurrence[k] = samples > 0 ? (double) matched / samples : null;
        unitsWithin[k] = samples > 0 ? upperMedian(Arrays.copyOf(units, n)) : null;
        lastSamples = samples;
      }
      out.put(e.getKey(), new Stats(hours, t[0], t[0] / hours, t[1] / (hours / 24.0), recurrence, unitsWithin, lastSamples));
    }
    return new Index(hours, out);
  }

  // The thin-market's median: the UPPER middle of the sorted values, never an average (Medians.upper's rule).
  private static double upperMedian(double[] v) {
    Arrays.sort(v);
    return v.length > 0 ? v[v.length / 2] : 0;
  }

  /** windowFor: the precomputed window nearest the player's own trade duration; 12 hours with none. */
  public static int windowFor(Double targetDurationMinutes) {
    double hours = targetDurationMinutes != null && JsValues.isFinite(targetDurationMinutes) && targetDurationMinutes > 0 ? targetDurationMinutes / 60 : 12;
    int best = WINDOWS[0];
    for (int w : WINDOWS) if (Math.abs(w - hours) < Math.abs(best - hours)) best = w;
    return best;
  }

  private static String plural(double n) {
    return n == 1 ? "" : "s";
  }

  /**
   * thinMarketNote: one warning sentence when a buy pick's own history says the price may simply not come back, or null.
   * {@code stats} null: the item is absent from the index -- the strongest warning when the archive is long enough
   * ({@code archivedHours} at least {@link #MIN_HOURS}), silence otherwise. Every parameter but {@code stats} may be null.
   */
  public static String note(Stats stats, String name, Double windowHours, Double quantity, Double archivedHours) {
    String who = name == null || name.isEmpty() ? "this item" : name;
    if (stats == null) {
      if (!(archivedHours != null && archivedHours >= MIN_HOURS)) return null;
      return "Warning: " + who + " did not trade at all in the last " + JsValues.gp(archivedHours) + " hours EVI has records for, "
        + "on either side of the market. There is no recent price to buy or sell it at, so an offer may sit indefinitely. "
        + "That is EVI's own record of the market, not a forecast.";
    }
    if (stats.hours < MIN_HOURS) return null;
    int w = windowOr12(windowHours);
    Double recurrence = stats.recurrence(w);
    if (recurrence == null) return null;
    boolean thin = stats.cadence < THIN_CADENCE || recurrence < THIN_RECURRENCE;
    if (!thin) return null;
    String perDay = stats.unitsPerDay >= 10 ? JsValues.gp(stats.unitsPerDay) : stripPointZero(JsValues.toFixed(stats.unitsPerDay, 1));
    Double availableBoxed = stats.unitsWithin(w);
    double available = availableBoxed == null ? Double.NaN : availableBoxed;
    Double wanted = quantity != null && JsValues.isFinite(quantity) && quantity > 0 ? quantity : null;
    return "Warning: " + who + " barely trades -- in " + JsValues.gp(stats.hoursTraded) + " of the last " + JsValues.gp(stats.hours) + " hours "
      + "(" + JsValues.text(JsValues.jsRound(stats.cadence * 100)) + "%), about " + perDay + " a day. Over those hours, a price like this one was matched again "
      + "within " + w + " hour" + (w == 1 ? "" : "s") + " only " + JsValues.text(JsValues.jsRound(recurrence * 100)) + "% of the time, with typically "
      + JsValues.gp(available) + " unit" + plural(available) + " available at or under it"
      + (wanted != null && available < wanted ? ", against the " + JsValues.gp(wanted) + " you would be buying" : "")
      + ". Your offer may simply sit unfilled. That is this item's own record, not a forecast.";
  }

  /**
   * thinMarketContext: the same figures as a plain statement rather than a warning, for every buy pick whose item EVI has
   * records for (the thresholds above are a cliff and real items sit on it). Facts only, no verdict; null when there is
   * nothing to say.
   */
  public static String context(Stats stats, Double windowHours, Double quantity) {
    if (stats == null || stats.hours < MIN_HOURS) return null;
    int w = windowOr12(windowHours);
    Double recurrence = stats.recurrence(w);
    if (recurrence == null) return null;
    Double availableBoxed = stats.unitsWithin(w);
    double available = availableBoxed == null ? Double.NaN : availableBoxed;
    Double wanted = quantity != null && JsValues.isFinite(quantity) && quantity > 0 ? quantity : null;
    return "Fill history: this item traded in " + JsValues.gp(stats.hoursTraded) + " of the last " + JsValues.gp(stats.hours) + " hours "
      + "(" + JsValues.text(JsValues.jsRound(stats.cadence * 100)) + "%), and a price like this one was matched again within " + w + " hour" + (w == 1 ? "" : "s") + " "
      + "in " + JsValues.text(JsValues.jsRound(recurrence * 100)) + "% of those cases, typically " + JsValues.gp(available) + " unit" + plural(available) + " "
      + "at or under it" + (wanted != null ? " (you would be buying " + JsValues.gp(wanted) + ")" : "") + ".";
  }

  /** {@code s.replace(/\.0$/, '')}. */
  static String stripPointZero(String s) {
    return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
  }
}
