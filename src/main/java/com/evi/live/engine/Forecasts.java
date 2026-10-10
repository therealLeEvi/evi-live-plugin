package com.evi.live.engine;

import com.evi.live.journal.Js;
import com.evi.live.market.HourBucket;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The opt-in price-pattern reading behind the "Exit risk" sentence (bridge/suggestions.mjs {@code forecastFromSeries},
 * bridge/fillOutlook.mjs {@code fillOutlook} / {@code fillOutlookSentence}), ported 8 Oct 2026 for the ~6 HOURS setting only.
 * Pure: the series is handed in, nothing is fetched.
 *
 * <p>WHAT IS SHOWN, AND WHY ONLY FOR SIX HOURS. The forecast's direction is measured to be wrong more often than chance, so it
 * is never shown; what the same label does separate is which side of a flip completes, and that table
 * ({@link #FILL_OUTLOOK_MEASURED}) was measured for the six-hour setting alone (65,006 offer pairs). The bridge reads the Wiki's
 * per-item HOURLY series for it; the plugin reads its own hourly archive, whose hours are the Wiki series' own (identical on
 * every one of 336 overlapping hours, and so are the forecasts -- the maintainer's investigation, 8 Oct). The ~1 hour and
 * Overnight settings read the Wiki's 5-minute and 6-hourly series, which the plugin does not keep, and have no measured table:
 * they are RETIRED in the plugin (no forecast at all; the bridge keeps them).
 *
 * <p>JS NUMBERS: every step is the JS arithmetic in the JS order; {@code Math.log} is {@link StrictMath#log} (fdlibm, which V8's
 * is a port of), {@code Math.round} is {@link JsValues#jsRound}. Proven against the JS by recorded vectors (ForecastVectorsTest).
 */
public final class Forecasts {
  private Forecasts() {}

  /** The one horizon the plugin forecasts for (the measured table's). */
  public static final String SIX_HOURS = "6h";
  /** How many of the newest usable points the six-hour reading looks at: the last n=18, and the n before them. */
  public static final int SIX_HOUR_POINTS = 36;

  /** A forecastFromSeries answer. {@code shortSeries}: under 8 usable points, the bare "Uncertain" answer. */
  public static final class Reading {
    public final String label;
    public final double dir, confidence, move, volatility, deviation, imbalance, noise, threshold;
    public final boolean shortSeries;

    Reading(String label, double dir, double confidence, double move, double volatility, double deviation, double imbalance, double noise,
            double threshold, boolean shortSeries) {
      this.label = label;
      this.dir = dir;
      this.confidence = confidence;
      this.move = move;
      this.volatility = volatility;
      this.deviation = deviation;
      this.imbalance = imbalance;
      this.noise = noise;
      this.threshold = threshold;
      this.shortSeries = shortSeries;
    }

    /** The object the bridge attaches as {@code candidate.forecast}, in its key order. */
    public JsonObject json() {
      JsonObject o = new JsonObject();
      o.addProperty("label", label);
      o.addProperty("dir", dir);
      o.addProperty("confidence", confidence);
      o.addProperty("move", move);
      if (shortSeries) return o;
      o.addProperty("volatility", volatility);
      o.addProperty("deviation", deviation);
      o.addProperty("imbalance", imbalance);
      o.addProperty("noise", noise);
      o.addProperty("threshold", threshold);
      return o;
    }
  }

  /** midpointPoint: the mid of both sides when both are positive, else whichever is, else none (null). */
  static Double midpoint(SellSupport.Point x) {
    double hi = num(x.avgHighPrice), lo = num(x.avgLowPrice);
    if (JsValues.isFinite(hi) && hi > 0 && JsValues.isFinite(lo) && lo > 0) return (hi + lo) / 2;
    if (JsValues.isFinite(hi) && hi > 0) return hi;
    if (JsValues.isFinite(lo) && lo > 0) return lo;
    return null;
  }

  /** {@code Number(v)}: a null field is Number(null), 0. */
  private static double num(Double v) {
    return v == null ? 0 : v;
  }

  static double linSlope(List<Double> vals) {
    List<Double> a = new ArrayList<>();
    for (Double v : vals) if (v != null && JsValues.isFinite(v)) a.add(v);
    if (a.size() < 3) return 0;
    int n = a.size();
    double mx = (n - 1) / 2.0, sum = 0;
    for (double v : a) sum += v;
    double my = sum / n, num = 0, den = 0;
    for (int i = 0; i < n; i++) {
      num += (i - mx) * (a.get(i) - my);
      den += (i - mx) * (i - mx);
    }
    return den != 0 ? num / den : 0;
  }

  static double ret(double a, double b) {
    return JsValues.isFinite(a) && a > 0 && JsValues.isFinite(b) ? b / a - 1 : 0;
  }

  static double volOf(List<Double> vals) {
    List<Double> rs = new ArrayList<>();
    for (int i = 1; i < vals.size(); i++) if (vals.get(i - 1) > 0 && vals.get(i) > 0) rs.add(StrictMath.log(vals.get(i) / vals.get(i - 1)));
    if (rs.size() < 2) return 0;
    double s = 0;
    for (double r : rs) s += r;
    double m = s / rs.size(), sq = 0;
    for (double r : rs) sq += (r - m) * (r - m);
    return Math.sqrt(sq / (rs.size() - 1));
  }

  /** {@code arr.slice(start, end)} with JS's negative-index rules. */
  private static <T> List<T> slice(List<T> arr, int start, int end) {
    int len = arr.size();
    int s = start < 0 ? Math.max(len + start, 0) : Math.min(start, len);
    int e = end < 0 ? Math.max(len + end, 0) : Math.min(end, len);
    return e <= s ? new ArrayList<>() : new ArrayList<>(arr.subList(s, e));
  }

  private static double clamp(double x, double a, double b) {
    return Math.max(a, Math.min(b, x));
  }

  /** {@code x.highPriceVolume || 0}: null, NaN and 0 are 0. */
  private static double orZero(Double v) {
    return v == null || Double.isNaN(v) ? 0 : v;
  }

  /**
   * forecastFromSeries(series, horizon). {@code series}: hourly points for the 6h horizon (any order; points with no usable
   * price are dropped). Under 8 usable points: the bare "Uncertain" answer. Never throws on a well-formed list.
   */
  public static Reading forecastFromSeries(List<SellSupport.Point> series, String horizon) {
    List<SellSupport.Point> clean = new ArrayList<>();
    if (series != null) for (SellSupport.Point p : series) if (p != null && midpoint(p) != null) clean.add(p);
    clean.sort((a, b) -> Js.compare(a.timestamp, b.timestamp)); // stable, as Array.prototype.sort is
    if (clean.size() < 8) return new Reading("Uncertain", 0, 20, 0, 0, 0, 0, 0, 0, true);
    List<Double> mids = new ArrayList<>();
    for (SellSupport.Point p : clean) mids.add(midpoint(p));
    double last = mids.get(mids.size() - 1);
    int n = "1h".equals(horizon) ? 12 : "6h".equals(horizon) ? 18 : 28;
    List<Double> recent = slice(mids, -n, mids.size()), prev = slice(mids, -Math.min(mids.size(), n * 2), -n);
    double slope = linSlope(recent) / (last != 0 && !Double.isNaN(last) ? last : 1);
    double momentum = ret(recent.get(0), recent.get(recent.size() - 1));
    double longer = prev.size() >= 3 ? ret(prev.get(0), prev.get(prev.size() - 1)) : momentum;
    double volatility = volOf(recent);
    double buyVol = 0, sellVol = 0;
    for (SellSupport.Point x : slice(clean, -n, clean.size())) buyVol = buyVol + orZero(x.highPriceVolume);
    for (SellSupport.Point x : slice(clean, -n, clean.size())) sellVol = sellVol + orZero(x.lowPriceVolume);
    double imbalance = (buyVol - sellVol) / Math.max(1, buyVol + sellVol);
    double sum = 0;
    for (double v : recent) sum = sum + v;
    double mean = sum / recent.size();
    double deviation = (last - mean) / Math.max(1, mean);
    double signal = 0.45 * momentum + 4.0 * slope + 0.012 * imbalance + 0.12 * longer;
    if (Math.abs(deviation) > .025) signal += -0.08 * deviation;
    double noise = Math.max(.002, volatility * Math.sqrt(Math.max(1, recent.size() / 4.0)));
    double strength = Math.abs(signal) / noise;
    double confidence = JsValues.jsRound(clamp(42 + strength * 22, 42, 88));
    if (clean.size() < 20) confidence = Math.min(confidence, 60);
    double threshold = Math.max(.0025, noise * .35);
    String label = "Stable";
    double dir = 0;
    if (signal > threshold) {
      label = "Likely rising";
      dir = 1;
    } else if (signal < -threshold) {
      label = "Likely falling";
      dir = -1;
    } else if (momentum < -.02 && deviation < -.015 && signal >= -threshold) {
      label = "Possible rebound";
      dir = 0.5;
    }
    return new Reading(label, dir, confidence, signal, volatility, deviation, imbalance, noise, threshold, false);
  }

  // ------------------------------------------------------------------------------------------- the fill outlook

  /** One row of the measured table. */
  static final class Rate {
    final double buy, sell;
    final int samples;

    Rate(double buy, double sell, int samples) {
      this.buy = buy;
      this.sell = sell;
      this.samples = samples;
    }
  }

  /** FILL_OUTLOOK_MEASURED (fillOutlook.mjs, measured 2026-09-18): the six-hour setting only. */
  public static final String MEASURED_HORIZON = "6h";
  static final int HORIZON_HOURS = 6;
  static final Rate BASELINE = new Rate(0.850, 0.833, 65006);
  static final Map<String, Rate> FILL_OUTLOOK_MEASURED;

  static {
    Map<String, Rate> m = new HashMap<>();
    m.put("Likely rising", new Rate(0.912, 0.744, 15137));
    m.put("Likely falling", new Rate(0.775, 0.878, 22977));
    m.put("Stable", new Rate(0.887, 0.837, 24948));
    m.put("Possible rebound", new Rate(0.768, 0.949, 1944));
    FILL_OUTLOOK_MEASURED = Collections.unmodifiableMap(m);
  }

  public static final double MIN_INTERESTING_GAP = 0.05;
  public static final int MIN_LABEL_SAMPLES = 1000;

  /** fillOutlook's answer. */
  public static final class Outlook {
    public final double buy, sell, buyGap, sellGap;
    public final String worst;
    public final int samples;
    public final boolean notable;

    Outlook(double buy, double sell, double buyGap, double sellGap, String worst, int samples, boolean notable) {
      this.buy = buy;
      this.sell = sell;
      this.buyGap = buyGap;
      this.sellGap = sellGap;
      this.worst = worst;
      this.samples = samples;
      this.notable = notable;
    }

    /** The object the bridge attaches as {@code candidate.fillOutlook}, in its key order. */
    public JsonObject json() {
      JsonObject o = new JsonObject();
      o.addProperty("buy", buy);
      o.addProperty("sell", sell);
      o.addProperty("buyGap", buyGap);
      o.addProperty("sellGap", sellGap);
      o.addProperty("worst", worst);
      JsonObject base = new JsonObject();
      base.addProperty("buy", BASELINE.buy);
      base.addProperty("sell", BASELINE.sell);
      o.add("baseline", base);
      o.addProperty("samples", samples);
      o.addProperty("horizonHours", HORIZON_HOURS);
      o.addProperty("notable", notable);
      return o;
    }
  }

  /** fillOutlook(forecast, FILL_OUTLOOK_MEASURED, horizon): null for any other horizon, an unmeasured label, or too few samples. */
  public static Outlook fillOutlook(Reading forecast, String horizon) {
    if (forecast == null) return null;
    if (!MEASURED_HORIZON.equals(horizon)) return null;
    Rate entry = FILL_OUTLOOK_MEASURED.get(forecast.label);
    if (entry == null || !(entry.samples >= MIN_LABEL_SAMPLES)) return null;
    double buyGap = entry.buy - BASELINE.buy, sellGap = entry.sell - BASELINE.sell;
    String worst = Math.abs(sellGap) >= Math.abs(buyGap) ? "sell" : "buy";
    return new Outlook(entry.buy, entry.sell, buyGap, sellGap, worst, entry.samples, Math.max(Math.abs(buyGap), Math.abs(sellGap)) >= MIN_INTERESTING_GAP);
  }

  private static String pct(double v) {
    return Js.numberToString(JsValues.jsRound(v * 100));
  }

  /** fillOutlookSentence: one plain sentence, or null when nothing is worth saying. Never names the direction. */
  public static String fillOutlookSentence(Outlook o) {
    if (o == null || !o.notable) return null;
    String hours = String.valueOf(HORIZON_HOURS), samples = JsValues.localeUs(o.samples);
    if ("sell".equals(o.worst) && o.sellGap < 0)
      return "Exit risk: items whose recent price pattern looks like this sold within " + hours + " hours only " + pct(o.sell) + "% of the time (against "
        + pct(o.buy) + "% for the buy side), across " + samples + " past offers in your price archive. The risk here is being left holding it rather than"
        + " missing the buy -- consider a smaller quantity or a keener sell price.";
    if ("sell".equals(o.worst) && o.sellGap > 0)
      return "Exit outlook: items with this recent price pattern sold within " + hours + " hours " + pct(o.sell) + "% of the time, above the "
        + pct(BASELINE.sell) + "% norm, though the buy side filled less often (" + pct(o.buy) + "%). Getting in is the harder half here. Based on "
        + samples + " past offers, not a guarantee.";
    if (o.buyGap < 0)
      return "Entry risk: items with this recent price pattern had their buy offer fill within " + hours + " hours only " + pct(o.buy)
        + "% of the time, across " + samples + " past offers in your price archive. You may simply not get the stock at this price.";
    return "Fill outlook: items with this recent price pattern filled the buy " + pct(o.buy) + "% and the sell " + pct(o.sell) + "% of the time within "
      + hours + " hours, across " + samples + " past offers. Rates from your own archive, not a forecast of price.";
  }

  /** What the forecast hook hands the pick chain for one item: the reading, its outlook and sentence. */
  public static PickChain.Forecast forHook(Reading r, String horizon) {
    if (r == null) return null;
    Outlook o = fillOutlook(r, horizon);
    return new PickChain.Forecast(r.json(), r.dir == -1 ? -1 : r.dir == 1 ? 1 : 0, r.confidence, fillOutlookSentence(o), o == null ? null : o.json());
  }

  // ------------------------------------------------------------------------------------------- the series, from the archive

  /**
   * Every item's newest {@link #SIX_HOUR_POINTS} usable hourly points, from the plugin's archive -- the same points the Wiki's
   * per-item series carries for those hours. Only the newest 36 usable points (and whether there are fewer than 8 or 20) can
   * change a six-hour reading, so nothing older is kept. Built once per archive state; immutable.
   *
   * <p>Expected difference from the bridge, documented: the Wiki series reaches 365 hours back, the archive 337; an item with
   * fewer than 36 usable hours in the archive's window but more in the Wiki's reads a shorter series (only very thin items).
   */
  public static final class Index {
    private final Map<Integer, List<SellSupport.Point>> byItem;
    public final long newestTs;
    public final int hours;

    private Index(Map<Integer, List<SellSupport.Point>> byItem, long newestTs, int hours) {
      this.byItem = byItem;
      this.newestTs = newestTs;
      this.hours = hours;
    }

    /** From archived hours (any order), using only hours up to and including {@code newestTs}. */
    public static Index build(List<HourBucket> archived, long newestTs) {
      List<HourBucket> hours = new ArrayList<>();
      if (archived != null) for (HourBucket b : archived) if (b != null && b.ts <= newestTs) hours.add(b);
      hours.sort((a, b) -> Long.compare(a.ts, b.ts));
      Map<Integer, ArrayDeque<SellSupport.Point>> keep = new HashMap<>();
      for (HourBucket b : hours) {
        for (int i = 0; i < b.size(); i++) {
          SellSupport.Point p = new SellSupport.Point(b.ts, side(b.avgHigh(i)), (double) b.highVolume(i), side(b.avgLow(i)), (double) b.lowVolume(i));
          if (midpoint(p) == null) continue;
          ArrayDeque<SellSupport.Point> q = keep.computeIfAbsent(b.id(i), k -> new ArrayDeque<>());
          q.addLast(p);
          if (q.size() > SIX_HOUR_POINTS) q.removeFirst();
        }
      }
      Map<Integer, List<SellSupport.Point>> out = new HashMap<>();
      for (Map.Entry<Integer, ArrayDeque<SellSupport.Point>> e : keep.entrySet()) out.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
      return new Index(out, newestTs, hours.size());
    }

    /** The item's points, oldest first (empty: none -- the Wiki's series would hold only empty hours: "Uncertain"). */
    public List<SellSupport.Point> series(int itemId) {
      List<SellSupport.Point> s = byItem.get(itemId);
      return s == null ? Collections.emptyList() : s;
    }
  }

  private static Double side(long v) {
    return v == HourBucket.NONE ? null : (double) v;
  }
}
