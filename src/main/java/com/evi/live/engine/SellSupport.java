package com.evi.live.engine;

import com.evi.live.journal.Js;
import com.evi.live.journal.Tax;
import com.evi.live.market.HourBucket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Does a buy's margin survive at the price buyers have ACTUALLY been paying? (bridge/suggestions.mjs
 * {@code sellPriceSupport}, {@code mergeArchiveHours}, {@code sellSupportNote}, {@code supportIsStale},
 * {@code supportedPriceForHeadline}, and the judgement inside server.mjs's {@code supportForSuggestion}.) Pure: the
 * series and the archive hours are handed in, and so is the clock.
 *
 * <p>WHY: the sell price EVI quotes is /latest's "high", one print. On a thin item one trade can sit far above the
 * market -- the Eclipse Moon chestplate was quoted at 618,004 while 48 buyers had paid an average of 587,104 over twelve
 * hours, below the buy price. The 12-hour buyer average names that mechanism, so it is the reading used.
 *
 * <p>NEVER A GUESS: no series, no reading (null). A reading with no buyers says so. An unmeasurable staleness is NOT
 * stale ("we could not tell" must never read as "we found a problem").
 *
 * <p>THE ARCHIVE AND THE SERIES ARE DOUBLES HERE, on purpose. The Wiki's v2 per-item series carries two decimals and
 * this reading only RANKS and WARNS -- it never becomes a quoted price (the 4 Oct rule: if a quote ever comes from an
 * aggregate, round it at the source, buy ceil / sell floor). Every GP figure it prints goes through JS
 * {@code Math.round} first ({@link JsValues#gp}).
 */
public final class SellSupport {
  private SellSupport() {}

  /** SELL_SUPPORT_HOURS: the window the buyer average is taken over. */
  public static final int SELL_SUPPORT_HOURS = 12;
  /** STALE_SUPPORT_RATIO: above this the 12-hour average is mostly history (measured, 584,245 item-hours). */
  public static final double STALE_SUPPORT_RATIO = 1.5;
  /** SOFT_STALE_SUPPORT_RATIO: worth STATING but not acting on. */
  public static final double SOFT_STALE_SUPPORT_RATIO = 1.25;

  /**
   * One point of the Wiki's per-item hourly series ({timestamp, avgHighPrice, highPriceVolume, avgLowPrice,
   * lowPriceVolume}). A field the Wiki sent as null or did not send is null.
   */
  public static final class Point {
    public final double timestamp;
    public final Double avgHighPrice;
    public final Double highPriceVolume;
    public final Double avgLowPrice;
    public final Double lowPriceVolume;

    public Point(double timestamp, Double avgHighPrice, Double highPriceVolume, Double avgLowPrice, Double lowPriceVolume) {
      this.timestamp = timestamp;
      this.avgHighPrice = avgHighPrice;
      this.highPriceVolume = highPriceVolume;
      this.avgLowPrice = avgLowPrice;
      this.lowPriceVolume = lowPriceVolume;
    }
  }

  /**
   * A reading. {@code averagePaid}, {@code netAtAverage}, {@code latestPaid} and {@code staleness} are null when there is
   * nothing to measure them on. The flags are null unless a check set them (the crash and thin-market readings, which
   * have no window, carry null {@code units} and {@code hours}). {@link #stale} is set by the headline enrichment.
   */
  public static final class Detail {
    public final Double units;
    public final Integer hours;
    public final Double averagePaid;
    public final Double netAtAverage;
    public final boolean supported;
    public final Double latestPaid;
    public final Double staleness;
    public Boolean crashing;
    public Boolean thinMarket;
    public Boolean thinnerThanTax;
    public Boolean belowMinimumAtSupportedPrice;
    public Boolean stale;

    public Detail(Double units, Integer hours, Double averagePaid, Double netAtAverage, boolean supported, Double latestPaid, Double staleness) {
      this.units = units;
      this.hours = hours;
      this.averagePaid = averagePaid;
      this.netAtAverage = netAtAverage;
      this.supported = supported;
      this.latestPaid = latestPaid;
      this.staleness = staleness;
    }

    /** {@code {...detail, <flag>: true}}: a copy, so the reading a caller already holds is not changed under it. */
    Detail copy() {
      Detail d = new Detail(units, hours, averagePaid, netAtAverage, supported, latestPaid, staleness);
      d.crashing = crashing;
      d.thinMarket = thinMarket;
      d.thinnerThanTax = thinnerThanTax;
      d.belowMinimumAtSupportedPrice = belowMinimumAtSupportedPrice;
      d.stale = stale;
      return d;
    }
  }

  /**
   * {@code Number(x)} for a series field. A {@link Point} carries null both for a field the Wiki sent as JSON null and for
   * one it left out, and both read as NaN here. JS would read the first as 0 and the second as NaN; the only use below is
   * {@code > 0}, which both fail, so the two never answer differently.
   */
  private static double num(Double x) {
    return x == null ? Double.NaN : x;
  }

  /**
   * A reading's field as JS arithmetic sees it: the readings this engine builds carry {@code null} (never undefined) for
   * "nothing measured", and JS reads null as 0 in {@code Math.round}/{@code Math.abs} ("an average of 0 gp"). Only reached
   * on readings sellPriceSupport cannot build; kept so the wording never says "NaN".
   */
  static double nullAsZero(Double x) {
    return x == null ? 0 : x;
  }

  /**
   * sellPriceSupport: the buyer average over the {@code hours} FULL hours before {@code nowMs}, and the margin at it.
   * Null for no series, an empty one, or a buy price that is not above 0. The window is aligned to the hour:
   * {@code end = floor(nowMs / 3,600,000) * 3600} (seconds), {@code start = end - hours * 3600}; a point counts when
   * {@code start <= timestamp < end} and both its average buy price and its buy volume are above 0.
   */
  public static Detail sellPriceSupport(List<Point> series, int itemId, double buyPrice, int hours, double nowMs) {
    if (series == null || series.isEmpty() || !(buyPrice > 0)) return null;
    double end = Math.floor(nowMs / 3600000) * 3600, start = end - hours * 3600.0;
    double units = 0, gp = 0, latestTs = Double.NEGATIVE_INFINITY;
    Double latestPaid = null;
    for (Point p : series) {
      // A null point has no answer in JS (p.timestamp throws), so it is not skipped here either; reading() turns it into
      // "no reading", as server.mjs's catch does.
      if (p == null) throw new IllegalArgumentException("a null point in the sell-support series (the JS throws here)");
      if (!(p.timestamp >= start && p.timestamp < end)) continue;
      double price = num(p.avgHighPrice), vol = num(p.highPriceVolume);
      if (price > 0 && vol > 0) {
        units += vol;
        gp += price * vol;
        // The most recent hour anyone actually bought in: what tells the average apart from the present.
        if (p.timestamp > latestTs) {
          latestTs = p.timestamp;
          latestPaid = price;
        }
      }
    }
    if (units == 0) return new Detail(0.0, hours, null, null, false, null, null);
    double averagePaid = gp / units;
    double netAtAverage = averagePaid - Tax.estimateUnitTax(itemId, averagePaid) - buyPrice;
    Double staleness = latestPaid != null && latestPaid > 0 ? averagePaid / latestPaid : null;
    return new Detail(units, hours, averagePaid, netAtAverage, netAtAverage > 0, latestPaid, staleness);
  }

  /** The default window: {@link #SELL_SUPPORT_HOURS}. */
  public static Detail sellPriceSupport(List<Point> series, int itemId, double buyPrice, double nowMs) {
    return sellPriceSupport(series, itemId, buyPrice, SELL_SUPPORT_HOURS, nowMs);
  }

  /** supportIsStale: a measured staleness at or above {@code ratio}. Null-safe and fail-open. */
  public static boolean supportIsStale(Detail detail, double ratio) {
    return detail != null && detail.staleness != null && JsValues.isFinite(detail.staleness) && detail.staleness >= ratio;
  }

  public static boolean supportIsStale(Detail detail) {
    return supportIsStale(detail, STALE_SUPPORT_RATIO);
  }

  /**
   * supportedPriceForHeadline: the buyer average, capped at what buyers pay NOW when the support is stale. It caps the
   * NUMBER and never withholds the pick. Null without an average.
   */
  public static Double supportedPriceForHeadline(Detail detail) {
    if (detail == null || detail.averagePaid == null || !JsValues.isFinite(detail.averagePaid)) return null;
    if (!supportIsStale(detail)) return detail.averagePaid;
    return detail.latestPaid != null && JsValues.isFinite(detail.latestPaid) ? Math.min(detail.averagePaid, detail.latestPaid) : detail.averagePaid;
  }

  /**
   * mergeArchiveHours: the series with the local archive's hours laid over it. The archive's hour wins wherever it has
   * one (the Wiki's per-item series lags about an hour); an hour it lacks keeps the series' point; an archived hour
   * without the item adds nothing. Sorted by timestamp, oldest first (a stable sort, as JS's).
   */
  public static List<Point> mergeArchiveHours(List<Point> series, List<HourBucket> buckets, int itemId) {
    // A JS Map keyed by the timestamp NUMBER: insertion order, a re-set key keeps its place, -0 and 0 are one key.
    Map<Double, Point> byTs = new LinkedHashMap<>();
    if (series != null) for (Point p : series) byTs.put(p.timestamp == 0 ? 0.0 : p.timestamp, p);
    if (buckets != null) for (HourBucket b : buckets) {
      int i = b == null ? -1 : b.indexOf(itemId);
      if (i < 0) continue;
      double ts = b.ts;
      byTs.put(ts == 0 ? 0.0 : ts, new Point(ts, side(b.avgHigh(i)), (double) b.highVolume(i), side(b.avgLow(i)), (double) b.lowVolume(i)));
    }
    List<Point> out = new ArrayList<>(byTs.values());
    out.sort((a, b) -> Js.compare(a.timestamp, b.timestamp));
    return out;
  }

  private static Double side(long v) {
    return v == HourBucket.NONE ? null : (double) v;
  }

  /**
   * sellSupportNote: one warning sentence, or null when the margin holds at what buyers really pay. A warning, never a
   * block: the player may know something the average does not, and the price is still offered.
   */
  public static String sellSupportNote(Detail support, double sellPrice) {
    if (support == null || support.supported) return null;
    String quoted = JsValues.isFinite(sellPrice) ? JsValues.gp(sellPrice) + " gp" : "the sell price";
    if (support.units == null || support.units == 0 || Double.isNaN(support.units))
      return "Warning: nobody bought this item at all in the last " + hoursText(support) + " hours, so the sell price of " + quoted
        + " rests on almost no trading. Your sale may not fill.";
    String perItem = JsValues.gp(Math.abs(nullAsZero(support.netAtAverage)));
    return "Warning: the sell price of " + quoted + " rests on very few trades. Over the last " + hoursText(support) + " hours, "
      + JsValues.localeUs(support.units) + " buyers paid an average of " + JsValues.gp(nullAsZero(support.averagePaid))
      + " gp -- at that price this flip loses about " + perItem + " gp per item after tax.";
  }

  private static String hoursText(Detail d) {
    return d.hours == null ? "null" : String.valueOf(d.hours);
  }

  // ------------------------------------------------------------------------------------------ the server's judgement

  /** What the sell-support check tells the pick chain: hold it back, warn (demote) it, or carry the reading. */
  public static final class Result {
    public final boolean blocked;
    public final String warning;
    public final Detail detail;

    public Result(boolean blocked, String warning, Detail detail) {
      this.blocked = blocked;
      this.warning = warning;
      this.detail = detail;
    }
  }

  /**
   * The reading server.mjs takes for a candidate: the Wiki series ({@code series}, null when it could not be fetched)
   * with the archive's last hours merged in. With no USABLE series -- none, or an EMPTY one (the maintainer, 7 Oct 2026,
   * fixed in JS first: an empty series used to pass this gate and let a few archived hours pose as a 12-hour reading) --
   * AND fewer archived hours than the window there is no reading (null), and the check stays silent.
   */
  public static Detail reading(List<Point> series, List<HourBucket> archived, int itemId, double buyPrice, double nowMs) {
    int archivedHours = archived == null ? 0 : archived.size();
    if ((series == null || series.isEmpty()) && archivedHours < SELL_SUPPORT_HOURS) return null;
    // A series point the Wiki sent as null: mergeArchiveHours reads p.timestamp and THROWS in JS, and
    // supportForSuggestion's catch answers null -- NO reading, even when the archive alone covers the window. Never a
    // reading with that hour quietly skipped.
    if (series != null) for (Point p : series) if (p == null) return null;
    return sellPriceSupport(mergeArchiveHours(series == null ? new ArrayList<>() : series, archived, itemId), itemId, buyPrice, nowMs);
  }

  /**
   * The reading when the ARCHIVE IS THE SERIES: the plugin, which makes no per-item request (no {@link Engine.SellSeries}
   * hook at all -- not a series that failed). What the bridge computes from its series merged with its archive, rebuilt
   * from the archive alone (8 Oct 2026, approved by the maintainer).
   *
   * <p>The bridge's Wiki series ends at H-2 or H-1 (it is cached per item until hh+1:00:10; measured 8 Oct at 09:03 UTC: 10 of
   * 12 items already held H-1, 2 still ended at H-2), and its hours are the archive's own (measured identical, 19 Sept and
   * 8 Oct). Inside the window [H-12, H) the bridge therefore reads H-12..H-2 always, plus H-1 from the series or -- from
   * hh:05, when the archive asks for it -- from the archive. The old gate ({@link #reading} with no series: twelve archived
   * hours or nothing) had NO reading from hh:00 to about hh:05 every hour, while the bridge had one.
   *
   * <p>So: a reading exactly when the archive holds EVERY hour from H-12 to H-2 (the hours always settled); H-1 is used when
   * stored, and not required. From about hh:05 that IS the bridge's merged series (archive with no gap); from hh:00 to hh:05
   * it is the bridge's for items whose series still ends at H-2, and one hour short of it (eleven hours, not twelve) for the
   * rest -- the plugin has no H-1 before its archive fetches it. An archive with a gap the series would have filled still
   * gives no reading (fail open, never a guess).
   */
  public static Detail readingFromArchive(List<HourBucket> archived, int itemId, double buyPrice, double nowMs) {
    if (!archiveCoversSettledWindow(archived, nowMs)) return null;
    return sellPriceSupport(mergeArchiveHours(new ArrayList<>(), archived, itemId), itemId, buyPrice, nowMs);
  }

  /** Whether {@code archived} holds every hour from H-{@value #SELL_SUPPORT_HOURS} to H-2, where H is the hour {@code nowMs} is in. */
  public static boolean archiveCoversSettledWindow(List<HourBucket> archived, double nowMs) {
    if (archived == null) return false;
    long end = (long) (Math.floor(nowMs / 3600000.0) * 3600);
    java.util.Set<Long> have = new java.util.HashSet<>();
    for (HourBucket b : archived) if (b != null) have.add(b.ts);
    for (long ts = end - SELL_SUPPORT_HOURS * 3600L; ts <= end - 2 * 3600L; ts += 3600) if (!have.contains(ts)) return false;
    return true;
  }

  /**
   * The judgement inside server.mjs's {@code supportForSuggestion}, once the crash and thin-market readings have had
   * their say (they come first, and are Phase 4's). Null when there is no reading.
   * <ul>
   *   <li>HELD BACK when the margin at the price buyers really pay is thinner than the tier's own tax bar
   *       ({@link LevelSettings#historyMarginTaxMultiple} for the personal tier, the no-history bar otherwise) -- the
   *       blood runes of 27 Sept: quoted 7 gp against a 6 gp tax, really 1 gp. Only while the margin-over-tax check is
   *       on ({@code requireMarginOverTax}: off only for "no minimum at all").</li>
   *   <li>WARNED (demoted) when the trade is worth less than the minimum at that price -- always checked, chosen
   *       minimum or the Auto floor, because the default Auto tier sends no minimum (27 Sept).</li>
   *   <li>otherwise the plain support note, if any, and the reading itself, carried so a clean pick can show what was
   *       checked.</li>
   * </ul>
   */
  public static Result judge(Detail detail, Pick candidate, double minProfit, boolean minProfitChosen, boolean requireMarginOverTax, LevelSettings level) {
    String warning = sellSupportNote(detail, candidate.sellPrice);
    if (detail != null && detail.netAtAverage != null && JsValues.isFinite(detail.netAtAverage)) {
      double qty = candidate.quantity != 0 ? candidate.quantity : 1; // candidate.quantity || 1
      double supportedTotal = detail.netAtAverage * qty;
      double taxAtSupport = Tax.estimateUnitTax(candidate.itemId, nullAsZero(detail.averagePaid));
      double bar = "personal".equals(candidate.source) ? level.historyMarginTaxMultiple : level.noHistoryMarginTaxMultiple;
      if (requireMarginOverTax && !Gates.marginClearsTax(detail.netAtAverage, taxAtSupport, bar)) {
        Detail d = detail.copy();
        d.thinnerThanTax = true;
        return new Result(true, "Set aside: at the price buyers are actually paying this makes about " + JsValues.gp(detail.netAtAverage)
          + " gp a unit, against the " + JsValues.gp(taxAtSupport) + " gp tax on each one. An edge thinner than its own tax does not survive a single"
          + " price step -- measured over 335 hours, trades like this lose GP a third of the time,"
          + " however large the quantity makes the total look.", d);
      }
      if (supportedTotal < minProfit) {
        double quotedTotal = (double) (candidate.sellPrice - Tax.estimateUnitTax(candidate.itemId, candidate.sellPrice) - candidate.buyPrice) * qty;
        Detail d = detail.copy();
        d.belowMinimumAtSupportedPrice = true;
        return new Result(false, "Warning: " + (minProfitChosen ? "below your " + JsValues.gp(minProfit) + " gp minimum" : "worth under " + JsValues.gp(minProfit) + " gp")
          + " at the price buyers are actually paying. The quoted spread makes this look like " + JsValues.gp(quotedTotal) + " gp, but over the last "
          + hoursText(detail) + " hours " + (detail.units == null ? "null" : JsValues.localeUs(detail.units)) + " buyers paid an average of " + JsValues.gp(nullAsZero(detail.averagePaid))
          + " gp, which makes it about " + JsValues.gp(supportedTotal) + " gp.", d);
      }
    }
    if (warning != null) return new Result(false, warning, detail);
    return detail != null ? new Result(false, null, detail) : null;
  }
}
