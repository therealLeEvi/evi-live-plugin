package com.evi.live.engine;

import com.evi.live.market.HourBucket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Crash alerts: an item's price collapsing right now, measured against the item's own recent behaviour and stated without
 * predicting what happens next (bridge/crashWatch.mjs: {@code buildBaselines}, {@code detectCrashes},
 * {@code crashMessage}, {@code measuredBandFor} and {@code createCrashWatch}'s state machine, whose {@code isCrashing} is
 * the gate that demotes a crashing buy).
 *
 * <p>WHAT THESE ALERTS DELIBERATELY DO NOT DO is what competing tools' dump alerts do: assume a crashed price rebounds.
 * When a crash is a permanent repricing, that is the adamant-arrows loss with a notification attached. Each alert carries
 * what tools/crash-recovery.mjs measured over 90 days for crashes of the same kind and says plainly it is no forecast.
 *
 * <p>A CRASH, per item, by the rules the measurement used: BOTH sides down (what buyers pay AND what sellers accept) by at
 * least {@link #MIN_DROP} and by {@link #Z} of the item's own typical hourly deviations -- one side alone is the wide-spread
 * instant-sell artefact the first measurement fell for; on real volume ({@link #VOL_RATIO} times the item's own average
 * for the window), so a misclick is not a crash; on an item that trades in most hours, so the baseline means something.
 *
 * <p>PURE. The baselines and the five-minute buckets are handed in; the {@link Watch} takes the clock on every call and
 * reads the archive only through the {@link Watch.HourlySource} its owner supplies, so it does no I/O of its own. NEEDS
 * DEPTH: an item must trade in 117.6 of 168 hours inside a 192-hour window, so a fresh archive never arms it -- which is
 * the documented fail-open state (no baseline, no alert), never a guess.
 */
public final class CrashWatch {
  private CrashWatch() {}

  /** CRASH.z: how many of the item's own hourly deviations both sides must fall. */
  public static final double Z = 4;
  /** CRASH.minDrop: the least fall, on both sides. */
  public static final double MIN_DROP = 0.10;
  /** CRASH.volRatio: units over the window against the item's own normal for that time. */
  public static final double VOL_RATIO = 3;
  /** CRASH.windowMinutes: the five-minute buckets read. */
  public static final int WINDOW_MINUTES = 30;
  static final int BASE_HOURS = 24, WEEK_HOURS = 168, HOUR = 3600;
  /** MAX_ALERT_HOURS: an alert still down after a day is ended as "still down", never as recovered. */
  public static final int MAX_ALERT_HOURS = 24;

  /** The rules detectCrashes applies ({@code CRASH}, or a caller's own). */
  public static final class Rules {
    public final double z;
    public final double minDrop;
    public final double volRatio;
    public final double windowMinutes;

    public Rules(double z, double minDrop, double volRatio, double windowMinutes) {
      this.z = z;
      this.minDrop = minDrop;
      this.volRatio = volRatio;
      this.windowMinutes = windowMinutes;
    }
  }

  public static final Rules CRASH = new Rules(Z, MIN_DROP, VOL_RATIO, WINDOW_MINUTES);

  /** One band of CRASH_MEASURED (19 Sep 2026, 90 days, 548 crashes with a day of data after them). */
  public static final class Band {
    public final String label;
    public final double max;
    public final int n;
    public final double back;
    public final double lower;

    Band(String label, double max, int n, double back, double lower) {
      this.label = label;
      this.max = max;
      this.n = n;
      this.back = back;
      this.lower = lower;
    }
  }

  public static final String MEASURED_ON = "2026-09-19";
  public static final int MEASURED_DAYS = 90;
  public static final List<Band> BANDS = Collections.unmodifiableList(java.util.Arrays.asList(
    new Band("items under 10k", 1e4, 432, 0.57, 0.06),
    new Band("items from 10k to 1m", 1e6, 98, 0.56, 0.06),
    new Band("items over 1m", Double.POSITIVE_INFINITY, 18, 0.22, 0.17)));
  /** CRASH_MEASURED.stillDownAfterHour. */
  public static final double STILL_DOWN_AFTER_HOUR = 0.75;

  /** One side's baseline: its 24-hour volume-weighted average and the deviation of its log gap. */
  public static final class Side {
    public final double base;
    public final double sd;

    public Side(double base, double sd) {
      this.base = base;
      this.sd = sd;
    }
  }

  public static final class Baseline {
    public final Side hi;
    public final Side lo;
    public final double hourlyUnits;

    public Baseline(Side hi, Side lo, double hourlyUnits) {
      this.hi = hi;
      this.lo = lo;
      this.hourlyUnits = hourlyUnits;
    }
  }

  /** A detected crash: prices rounded half-up, the drop and the units unrounded, as detectCrashes reports them. */
  public static final class Crash {
    public final int itemId;
    public final double hi;
    public final double lo;
    public final double baseHi;
    public final double baseLo;
    public final double drop;
    public final double units;
    public final double normalPerHour;

    public Crash(int itemId, double hi, double lo, double baseHi, double baseLo, double drop, double units, double normalPerHour) {
      this.itemId = itemId;
      this.hi = hi;
      this.lo = lo;
      this.baseHi = baseHi;
      this.baseLo = baseLo;
      this.drop = drop;
      this.units = units;
      this.normalPerHour = normalPerHour;
    }

    Crash with(double hi, double lo, double drop, double units) {
      return new Crash(itemId, hi, lo, baseHi, baseLo, drop, units, normalPerHour);
    }
  }

  /**
   * buildBaselines: per item and side, the 24-hour volume-weighted average up to {@code endTs} (seconds), and how far the
   * item's hourly price normally strays from its trailing 24-hour average over the week before. Only buckets inside the
   * 192 hours before {@code endTs} are read; an item with too little history is left OUT, never guessed. Keyed by item id.
   */
  public static Map<Integer, Baseline> buildBaselines(List<HourBucket> hourly, double endTs) {
    double t0 = endTs - (BASE_HOURS + WEEK_HOURS) * (double) HOUR;
    int n = BASE_HOURS + WEEK_HOURS;
    // JS Map keyed by the id STRING, so insertion order; the result is looked up by id, so order never shows.
    Map<Integer, double[][]> grid = new LinkedHashMap<>();
    if (hourly != null) for (HourBucket b : hourly) {
      if (!(b.ts >= t0 && b.ts < endTs)) continue;
      double idx = (b.ts - t0) / HOUR;
      // A typed array ignores a non-integer index (an hour not on the grid): the item is still entered, with nothing set.
      boolean onGrid = idx == Math.floor(idx);
      int i = (int) idx;
      for (int r = 0; r < b.size(); r++) {
        double[][] s = grid.computeIfAbsent(b.id(r), k -> {
          double[] hi = new double[n], lo = new double[n];
          java.util.Arrays.fill(hi, Double.NaN);
          java.util.Arrays.fill(lo, Double.NaN);
          return new double[][]{hi, new double[n], lo, new double[n]};
        });
        if (!onGrid) continue;
        double hi = side(b.avgHigh(r)), hv = b.highVolume(r), lo = side(b.avgLow(r)), lv = b.lowVolume(r);
        if (hi > 0 && hv > 0) {
          s[0][i] = hi;
          s[1][i] = hv;
        }
        if (lo > 0 && lv > 0) {
          s[2][i] = lo;
          s[3][i] = lv;
        }
      }
    }
    Map<Integer, Baseline> out = new TreeMap<>();
    for (Map.Entry<Integer, double[][]> e : grid.entrySet()) {
      double[][] s = e.getValue();
      double traded = 0, units = 0;
      for (int i = BASE_HOURS; i < n; i++) {
        double u = s[1][i] + s[3][i];
        units += u;
        if (u > 0) traded++;
      }
      if (traded < WEEK_HOURS * 0.7) continue;
      Side hi = side(s[0], s[1], n), lo = side(s[2], s[3], n);
      if (hi != null && lo != null) out.put(e.getKey(), new Baseline(hi, lo, units / WEEK_HOURS));
    }
    return out;
  }

  private static double side(long v) {
    return v == HourBucket.NONE ? Double.NaN : v;
  }

  private static double avg(double[] p, double[] v, int from, int to) {
    double gp = 0, u = 0;
    int h = 0;
    for (int j = from; j < to; j++)
      if (v[j] > 0) {
        gp += p[j] * v[j];
        u += v[j];
        h++;
      }
    return h >= BASE_HOURS / 2 ? gp / u : Double.NaN;
  }

  private static Side side(double[] p, double[] v, int n) {
    double sum = 0, sq = 0;
    int c = 0;
    for (int i = BASE_HOURS; i < n; i++) {
      if (!(v[i] > 0)) continue;
      double b = avg(p, v, i - BASE_HOURS, i);
      if (!(b > 0)) continue;
      double d = StrictMath.log(p[i] / b);
      sum += d;
      sq += d * d;
      c++;
    }
    double base = avg(p, v, n - BASE_HOURS, n);
    if (!(c >= 24 && base > 0)) return null;
    double mean = sum / c;
    return new Side(base, Math.sqrt(Math.max(1e-12, sq / c - mean * mean)));
  }

  /** One item's sums over the window (JS windowSums): gp and units per side, counted only where price and units are above 0. */
  static final class Sums {
    double hiGp, hv, loGp, lv;
  }

  /** windowSums, in the JS Map's insertion order: the order buckets come in, ids ascending within each. */
  static Map<Integer, Sums> windowSums(List<HourBucket> fiveMinute) {
    Map<Integer, Sums> sums = new LinkedHashMap<>();
    if (fiveMinute != null) for (HourBucket b : fiveMinute)
      for (int r = 0; r < b.size(); r++) {
        Sums s = sums.computeIfAbsent(b.id(r), k -> new Sums());
        double hi = side(b.avgHigh(r)), hv = b.highVolume(r), lo = side(b.avgLow(r)), lv = b.lowVolume(r);
        if (hi > 0 && hv > 0) {
          s.hiGp += hi * hv;
          s.hv += hv;
        }
        if (lo > 0 && lv > 0) {
          s.loGp += lo * lv;
          s.lv += lv;
        }
      }
    return sums;
  }

  /** detectCrashes: which items are crashing in the given five-minute buckets, in window order. */
  public static List<Crash> detectCrashes(Map<Integer, Baseline> baselines, List<HourBucket> fiveMinute, Rules rules) {
    return detect(baselines, windowSums(fiveMinute), rules);
  }

  public static List<Crash> detectCrashes(Map<Integer, Baseline> baselines, List<HourBucket> fiveMinute) {
    return detectCrashes(baselines, fiveMinute, CRASH);
  }

  private static List<Crash> detect(Map<Integer, Baseline> baselines, Map<Integer, Sums> sums, Rules rules) {
    List<Crash> found = new ArrayList<>();
    double floor = StrictMath.log(1 - rules.minDrop);
    for (Map.Entry<Integer, Sums> e : sums.entrySet()) {
      int itemId = e.getKey();
      Sums s = e.getValue();
      Baseline base = baselines == null ? null : baselines.get(itemId);
      if (base == null || !(s.hv > 0) || !(s.lv > 0)) continue;
      double hi = s.hiGp / s.hv, lo = s.loGp / s.lv;
      double dHi = StrictMath.log(hi / base.hi.base), dLo = StrictMath.log(lo / base.lo.base);
      if (!(dHi < floor && dLo < floor)) continue;
      if (dHi > -rules.z * base.hi.sd || dLo > -rules.z * base.lo.sd) continue;
      double units = s.hv + s.lv, normal = base.hourlyUnits * rules.windowMinutes / 60;
      if (units < rules.volRatio * normal) continue;
      found.add(new Crash(itemId, JsValues.jsRound(hi), JsValues.jsRound(lo), JsValues.jsRound(base.hi.base), JsValues.jsRound(base.lo.base),
        1 - hi / base.hi.base, units, base.hourlyUnits));
    }
    return found;
  }

  /** measuredBandFor: the first band whose ceiling the price is under, or null (a NaN price is under none). */
  public static Band measuredBandFor(double price) {
    for (Band b : BANDS) if (price < b.max) return b;
    return null;
  }

  /**
   * crashMessage: one plain sentence per alert. {@code mine} says why the player is being told (an offer, stock held, or
   * EVI's own current pick); null or empty adds nothing. The numbers are the measured ones; the history is quoted, never
   * extrapolated.
   */
  public static String crashMessage(Crash c, String name, String mine) {
    Band band = measuredBandFor(c.baseHi);
    String perHour = c.normalPerHour >= 10 ? JsValues.gp(c.normalPerHour) : ThinMarket.stripPointZero(JsValues.toFixed(c.normalPerHour, 1));
    String who = name == null || name.isEmpty() ? "Item " + c.itemId : name;
    String head = who + " is crashing: buyers are paying about " + JsValues.gp(c.hi) + " gp, " + JsValues.text(JsValues.jsRound(c.drop * 100))
      + "% under its 24-hour average of " + JsValues.gp(c.baseHi) + " gp, with " + JsValues.gp(c.units) + " traded in the last " + WINDOW_MINUTES
      + " minutes (normally about " + perHour + " an hour).";
    String why = mine != null && !mine.isEmpty() ? " " + mine : "";
    String history = band != null ? " Cause unknown. Of " + band.n + " crashes like this in " + band.label + " over the last " + MEASURED_DAYS + " days, "
      + JsValues.text(JsValues.jsRound(band.back * 100)) + "% had made back most of the drop a day later and " + JsValues.text(JsValues.jsRound(band.lower * 100))
      + "% were lower still" + (band.n < 30 ? " (a small sample)" : "") + ". That is history, not a forecast for this one." : " Cause unknown.";
    return head + why + history;
  }

  /** crashNote's answer: the sidebar line and its tooltip. */
  public static final class Note {
    public final String message;
    public final String detail;

    Note(String message, String detail) {
      this.message = message;
      this.detail = detail;
    }
  }

  /**
   * crashNote (8 Oct 2026, the maintainer: the one-sentence form was a single tooltip line too long to read): the sidebar's crash
   * note in two parts. {@code message} is the line read at a glance (the price buyers pay, the drop, the 24-hour average,
   * and {@code mine}, the player's reason); {@code detail} is the tooltip (the volume against normal, "Cause unknown.", the
   * measured history and its hedge). The same facts as {@link #crashMessage}, tightened; "a day later" because the
   * measurement read the price at the one-day mark.
   */
  public static Note crashNote(Crash c, String name, String mine) {
    Band band = measuredBandFor(c.baseHi);
    String perHour = c.normalPerHour >= 10 ? JsValues.gp(c.normalPerHour) : ThinMarket.stripPointZero(JsValues.toFixed(c.normalPerHour, 1));
    String who = name == null || name.isEmpty() ? "Item " + c.itemId : name;
    String message = who + " is crashing: buyers pay about " + JsValues.gp(c.hi) + " gp, " + JsValues.text(JsValues.jsRound(c.drop * 100))
      + "% under its 24-hour average of " + JsValues.gp(c.baseHi) + " gp." + (mine != null && !mine.isEmpty() ? " " + mine : "");
    String volume = JsValues.gp(c.units) + " traded in the last " + WINDOW_MINUTES + " minutes (normally about " + perHour + " an hour). Cause unknown.";
    String history = band != null ? " Of " + band.n + " similar crashes in " + band.label + " (" + MEASURED_DAYS + " days" + (band.n < 30 ? ", a small sample" : "")
      + "), " + JsValues.text(JsValues.jsRound(band.back * 100)) + "% had made back most of the drop a day later and "
      + JsValues.text(JsValues.jsRound(band.lower * 100)) + "% were lower still. History, not a forecast." : "";
    return new Note(message, volume + history);
  }

  /**
   * createCrashWatch's live state, with the clock passed in on every call: baselines rebuilt at most hourly, the last
   * {@link #WINDOW_MINUTES} of five-minute buckets held, alerts kept for a day after they end, every start and end handed
   * to the {@link Recorder}.
   *
   * <p>Once an alert runs it is judged against the price from BEFORE the crash, not the rolling average (crash hours drag
   * the 24-hour average down to meet the new price, and the alert would "end" with the price still on the floor). It ends
   * when buyers pay within half the minimum drop of that old price, or after {@link #MAX_ALERT_HOURS}, marked still down.
   * A window with no buying at all changes nothing.
   *
   * <p>NOT THREAD-SAFE, deliberately: like every engine piece it belongs to the one worker that owns the engine.
   */
  public static final class Watch {
    /** The hourly archive from {@code fromTs} (seconds) on, oldest first. May throw: a failure keeps the last baselines. */
    public interface HourlySource {
      List<HourBucket> load(double fromTs) throws Exception;
    }

    /** The five-minute buckets from {@code fromTs} on, read once when the window is first needed. May throw (nothing read). */
    public interface FiveMinuteSource {
      List<HourBucket> load(double fromTs) throws Exception;
    }

    /** Where each alert's start and end go, so these calls can be scored later. */
    public interface Recorder {
      void record(String event, double at, Crash crash, String because);
    }

    /** One alert: the crash (updated while it runs), when it started and was last updated, and how it ended. */
    public static final class Alert {
      public final int itemId;
      public Crash crash;
      public final double since;
      public double updated;
      public Double ended;
      public String endedBecause;

      Alert(int itemId, Crash crash, double since) {
        this.itemId = itemId;
        this.crash = crash;
        this.since = since;
        this.updated = since;
      }
    }

    private final HourlySource loadHourly;
    private final FiveMinuteSource loadFiveMinute;
    private final Recorder recorder;
    private Map<Integer, Baseline> baselines;
    private double baselinesFor = Double.NaN;
    private List<HourBucket> window;
    private final Map<Integer, Alert> alerts = new LinkedHashMap<>();

    /** Any source may be null: no archive (an empty one), no five-minute read at start, nothing recorded. */
    public Watch(HourlySource loadHourly, FiveMinuteSource loadFiveMinute, Recorder recorder) {
      this.loadHourly = loadHourly;
      this.loadFiveMinute = loadFiveMinute;
      this.recorder = recorder;
    }

    private Map<Integer, Baseline> ensureBaselines(double nowMs) {
      double hourStart = Math.floor(nowMs / 3600000) * 3600;
      if (baselines != null && baselinesFor == hourStart) return baselines;
      try {
        List<HourBucket> hourly = loadHourly == null ? null : loadHourly.load(hourStart - (BASE_HOURS + WEEK_HOURS + 1) * (double) HOUR);
        baselines = buildBaselines(hourly, hourStart);
        baselinesFor = hourStart;
      } catch (Exception e) {
        // The JS logs and keeps what it had (or nothing). An Error is a fault, never "no archive".
        if (baselines == null) baselines = new TreeMap<>();
      }
      return baselines;
    }

    private void end(int id, Alert a, double t, String because) {
      a.ended = t;
      a.endedBecause = because;
      if (recorder != null) recorder.record("crash-ended", t, a.crash, because);
    }

    /**
     * addFiveMinute: one stored five-minute bucket (null: just re-judge the window). Returns the crashes detected in the
     * window now -- running alerts included, as the JS returns them.
     */
    public List<Crash> addFiveMinute(HourBucket bucket, double nowMs) {
      if (window == null) {
        window = new ArrayList<>();
        try {
          List<HourBucket> first = loadFiveMinute == null ? null : loadFiveMinute.load(Math.floor(nowMs / 1000) - WINDOW_MINUTES * 60.0);
          if (first != null) window.addAll(first);
        } catch (Exception e) {
          // nothing read: the window starts empty
        }
      }
      if (bucket != null) {
        boolean seen = false;
        for (HourBucket b : window) if (b.ts == bucket.ts) seen = true;
        if (!seen) window.add(bucket);
      }
      if (window.isEmpty()) return new ArrayList<>();
      long newest = Long.MIN_VALUE;
      for (HourBucket b : window) newest = Math.max(newest, b.ts);
      List<HourBucket> kept = new ArrayList<>();
      for (HourBucket b : window) if (b.ts > newest - WINDOW_MINUTES * 60L) kept.add(b);
      kept.sort((a, b) -> Long.compare(a.ts, b.ts)); // stable, as JS's sort
      window = kept;
      List<Crash> found = detectCrashes(ensureBaselines(nowMs), window);
      double t = nowMs;
      Map<Integer, Sums> sums = windowSums(window);
      for (Crash c : found) {
        Alert a = alerts.get(c.itemId);
        if (a != null && a.ended == null) continue;
        // A re-set key keeps its place in a JS Map: an ended alert replaced by a new crash stays where it was.
        alerts.put(c.itemId, new Alert(c.itemId, c, t));
        if (recorder != null) recorder.record("crash", t, c, null);
      }
      List<Integer> expired = new ArrayList<>();
      for (Map.Entry<Integer, Alert> e : alerts.entrySet()) {
        int id = e.getKey();
        Alert a = e.getValue();
        if (a.ended != null) {
          if (t - a.ended > 24 * 3600000.0) expired.add(id);
          continue;
        }
        Sums s = sums.get(id);
        if (s != null && s.hv > 0) {
          double hi = s.hiGp / s.hv;
          a.crash = a.crash.with(JsValues.jsRound(hi), s.lv > 0 ? JsValues.jsRound(s.loGp / s.lv) : a.crash.lo, 1 - hi / a.crash.baseHi, s.hv + s.lv);
          a.updated = t;
          if (hi >= a.crash.baseHi * (1 - MIN_DROP / 2)) {
            end(id, a, t, "recovered");
            continue;
          }
        }
        if (t - a.since > MAX_ALERT_HOURS * 3600000.0) end(id, a, t, "still down after a day");
      }
      for (Integer id : expired) alerts.remove(id);
      return found;
    }

    /** isCrashing: the running alert's crash, or null. THE GATE: a crashing buy is demoted with the alert as its warning. */
    public Crash isCrashing(int itemId) {
      Alert a = alerts.get(itemId);
      return a != null && a.ended == null ? a.crash : null;
    }

    /** active(): the running alerts, in the order they started. */
    public List<Alert> active() {
      List<Alert> out = new ArrayList<>();
      for (Alert a : alerts.values()) if (a.ended == null) out.add(a);
      return out;
    }

    /** recent(): every alert still held (running, or ended within a day), newest first. */
    public List<Alert> recent() {
      List<Alert> out = new ArrayList<>(alerts.values());
      out.sort((a, b) -> {
        double d = b.since - a.since;
        return d > 0 ? 1 : d < 0 ? -1 : 0;
      });
      return out;
    }

    /** status(): whether a window is held, its size, and how many items have a baseline. */
    public int windowBuckets() {
      return window == null ? 0 : window.size();
    }

    public boolean watching() {
      return window != null && !window.isEmpty();
    }

    public int itemsWithBaseline() {
      return baselines == null ? 0 : baselines.size();
    }
  }
}
