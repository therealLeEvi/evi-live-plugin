package com.evi.live.market;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;

/**
 * The engine's readings of the hourly archive, PORTED WITH PARITY from the bridge:
 * <ul>
 *   <li>{@link #robustPrices} -- suggestions.mjs {@code robustPrices}: the median hourly average price per side over
 *       {@link #ROBUST_PRICE_HOURS} hours, at least {@link #MIN_ROBUST_HOURS} readings a side;</li>
 *   <li>{@link #robustPricesAt} -- server.mjs {@code robustPricesCached}: the same, over
 *       {@code readArchive(dir, now - 336h)}, with "nothing" reported as an empty map;</li>
 *   <li>{@link #typicalVolumesAt} -- server.mjs {@code typicalVolumesCached}: each side's median hourly volume over
 *       {@link #TYPICAL_VOLUME_HOURS} hours, combined at the END by taking the thinner side, at least
 *       {@link #MIN_TYPICAL_VOLUME_HOURS} readings, a zero never published;</li>
 *   <li>{@link #volumeReadingFor} -- suggestions.mjs {@code volumeReadingFor}: the latest hour's thinner side, where an
 *       item ABSENT from the hour is "no reading" (null) and one LISTED with a zero side is a measured 0;</li>
 *   <li>{@link #readArchive} -- priceArchive.mjs {@code readArchive}: buckets in [from, to], one per ts (the last
 *       wins), oldest first.</li>
 * </ul>
 *
 * <p>THE RULES THAT MAKE IT BYTE-IDENTICAL, each one a place a port goes quietly wrong:
 * <ul>
 *   <li>the MEDIAN is the UPPER middle, {@code sorted[floor(n/2)]}, with NO averaging of the two middles -- both
 *       JavaScript readers use it (it differs from suggestions.mjs's own module-level {@code median}, which
 *       averages; that one is not used here);</li>
 *   <li>robust prices: the window is relative to the NEWEST bucket ({@code ts > newest - hours*3600}, strict), a side
 *       counts only when its price is above 0, and an item needs BOTH sides; typical volumes: the window is relative
 *       to NOW ({@code ts >= floor(now/1000) - 168h}), and every listed row counts, zeros included;</li>
 *   <li>robustPrices answers null for nothing at all, which the server turns into an empty map.</li>
 * </ul>
 *
 * <p>MEMORY: the production path, {@link #build}, reads ONE hour file at a time into per-item primitive arrays and
 * keeps only the answers (a few numbers per item). It never holds the raw window: the bridge measured 167 MB of
 * retained heap for one 336-hour read in Node, and this runs inside the game client.
 */
public final class MarketAggregates {
  private MarketAggregates() {}

  public static final int ROBUST_PRICE_HOURS = 336;
  public static final int MIN_ROBUST_HOURS = 6;
  public static final int TYPICAL_VOLUME_HOURS = 168;
  public static final int MIN_TYPICAL_VOLUME_HOURS = 24;
  static final long HOUR = 3600;

  /** An item's steady price: the median hourly average a buyer paid ({@code high}) and a seller got ({@code low}). */
  public static final class RobustPrice {
    public final long high;
    public final long low;

    RobustPrice(long high, long low) {
      this.high = high;
      this.low = low;
    }

    /** One item's steady price as the market tier reads it ({@code options.rankPrices[id]}), for a caller outside this package. */
    public static RobustPrice of(long high, long low) {
      return new RobustPrice(high, low);
    }

    @Override public boolean equals(Object o) {
      return o instanceof RobustPrice && ((RobustPrice) o).high == high && ((RobustPrice) o).low == low;
    }

    @Override public int hashCode() {
      return Long.hashCode(high) * 31 + Long.hashCode(low);
    }

    @Override public String toString() {
      return "{high:" + high + ",low:" + low + "}";
    }
  }

  /**
   * stretch.mjs {@code stretchBasis}'s value for one item: its usual price ({@code level}, the upper median of the hourly
   * lows) and its typical daily move ({@code move}, the upper median of |ln(low_t / low_(t-24h))|), for the stretched-price
   * WARNING (engine/Stretch). Only items with enough of both, and a move above 0, have one.
   */
  public static final class StretchBasis {
    public final long level;
    public final double move;

    public StretchBasis(long level, double move) {
      this.level = level;
      this.move = move;
    }

    @Override public String toString() {
      return "{level:" + level + ",move:" + move + "}";
    }
  }

  /**
   * patience.mjs {@code patienceBasis}'s value for one item: its typical high ({@code typicalHigh}, the floor of the TRUE
   * median -- the mean of the two middle values for an even count -- of the hourly average highs over the newest 24 stored
   * hours, counting hours where buyers paid at all), and how many such hours it rests on. For the patient price on a
   * holding below break-even (engine/Patience). Only items with enough priced hours and trade, at or above the floor, have one.
   */
  public static final class PatienceBasis {
    public final long typicalHigh;
    public final int hours;

    public PatienceBasis(long typicalHigh, int hours) {
      this.typicalHigh = typicalHigh;
      this.hours = hours;
    }

    @Override public String toString() {
      return "{typicalHigh:" + typicalHigh + ",hours:" + hours + "}";
    }
  }

  /** patience.mjs's constants for the basis (the measured ones, PATIENCE-BAR-2026-10-23; not to be tuned against results). */
  public static final int PATIENCE_HOURS = 24;
  public static final int MIN_PATIENCE_HOURS = 12;
  public static final long PATIENCE_MIN_UNITS = 5 * 24;
  public static final long PATIENCE_PRICE_FLOOR = 1000;

  /** stretch.mjs's constants for the basis (the measured ones, STRETCH-BAR-2026-10-15; not to be tuned against results). */
  public static final int STRETCH_HOURS = 336;
  public static final int MIN_STRETCH_HOURS = 48;
  public static final int MIN_STRETCH_PAIRS = 48;
  static final long DAY = 86400;

  /** The answers of one {@link #build}: what the engine reads, and how many archived hours they came from. */
  public static final class Readings {
    /** Robust prices by item id; empty (never null) when there is nothing, as robustPricesCached serves it. */
    public final Map<Integer, RobustPrice> robust;
    /** Typical hourly volume by item id (only values above 0). */
    public final Map<Integer, Long> typical;
    /** The stretched-price basis by item id (server.mjs {@code stretchBasisCached}); empty (never null) for nothing. */
    public final Map<Integer, StretchBasis> stretch;
    /** The patient-price basis by item id (server.mjs {@code patienceBasisCached}); empty (never null) for nothing. */
    public final Map<Integer, PatienceBasis> patience;
    /** Hour files read into the robust window, and into the typical window. */
    public final int robustHours;
    public final int typicalHours;

    Readings(Map<Integer, RobustPrice> robust, Map<Integer, Long> typical, Map<Integer, StretchBasis> stretch,
             Map<Integer, PatienceBasis> patience, int robustHours, int typicalHours) {
      this.robust = Collections.unmodifiableMap(robust);
      this.typical = Collections.unmodifiableMap(typical);
      this.stretch = Collections.unmodifiableMap(stretch);
      this.patience = Collections.unmodifiableMap(patience);
      this.robustHours = robustHours;
      this.typicalHours = typicalHours;
    }
  }

  // ------------------------------------------------------------------------------------------- accumulators

  /** A growable long array, allocated once at the expected size. */
  static final class LongList {
    long[] a;
    int n;

    LongList(int capacity) {
      a = new long[Math.max(1, capacity)];
    }

    void add(long v) {
      if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
      a[n++] = v;
    }

    /** {@code s[Math.floor(s.length / 2)]} of the sorted values: the UPPER median, no averaging. Sorts in place. */
    long upperMedian() {
      Arrays.sort(a, 0, n);
      return a[n / 2];
    }
  }

  /**
   * Item id to a dense slot number, by open addressing over primitive arrays: the accumulators look an id up once per
   * row of every hour read (about a million times a rebuild), so this boxes nothing and allocates only when it grows.
   */
  static final class Slots {
    private int[] keys = new int[8192];
    private int[] slotOf = new int[8192];
    int[] ids = new int[4096];
    int size;

    Slots() {
      Arrays.fill(keys, -1);
    }

    /** The id's slot, adding it when new. Ids are never negative (-1 marks an empty cell). */
    int slot(int id) {
      int mask = keys.length - 1, i = mix(id) & mask;
      while (keys[i] != -1) {
        if (keys[i] == id) return slotOf[i];
        i = (i + 1) & mask;
      }
      if (size == ids.length) ids = Arrays.copyOf(ids, size * 2);
      ids[size] = id;
      keys[i] = id;
      slotOf[i] = size;
      if (++size * 2 > keys.length) grow();
      return size - 1;
    }

    private static int mix(int id) {
      int h = id * 0x9E3779B9;
      return h ^ (h >>> 16);
    }

    private void grow() {
      int[] k = keys, v = slotOf;
      keys = new int[k.length * 2];
      slotOf = new int[k.length * 2];
      Arrays.fill(keys, -1);
      int mask = keys.length - 1;
      for (int j = 0; j < k.length; j++) {
        if (k[j] == -1) continue;
        int i = mix(k[j]) & mask;
        while (keys[i] != -1) i = (i + 1) & mask;
        keys[i] = k[j];
        slotOf[i] = v[j];
      }
    }
  }

  /** robustPrices' loop: prices above 0 per side, for buckets with ts > cut (strict). */
  static final class RobustAcc {
    final long cut;
    final int capacity;
    final Slots slots = new Slots();
    LongList[] highs = new LongList[4096];
    LongList[] lows = new LongList[4096];

    RobustAcc(long cut, int capacity) {
      this.cut = cut;
      this.capacity = capacity;
    }

    boolean add(HourBucket b) {
      if (b == null || !(b.ts > cut)) return false;
      for (int i = 0; i < b.size(); i++) {
        long h = b.avgHigh(i), l = b.avgLow(i);
        boolean hk = h != HourBucket.NONE && h > 0, lk = l != HourBucket.NONE && l > 0;
        if (!hk && !lk) continue;
        int s = slots.slot(b.id(i));
        if (s >= highs.length) {
          highs = Arrays.copyOf(highs, highs.length * 2);
          lows = Arrays.copyOf(lows, lows.length * 2);
        }
        if (hk) (highs[s] == null ? highs[s] = new LongList(capacity) : highs[s]).add(h);
        if (lk) (lows[s] == null ? lows[s] = new LongList(capacity) : lows[s]).add(l);
      }
      return true;
    }

    /** null when nothing qualifies, as robustPrices returns null for an empty answer. */
    TreeMap<Integer, RobustPrice> result() {
      TreeMap<Integer, RobustPrice> out = new TreeMap<>();
      for (int s = 0; s < slots.size; s++) {
        LongList h = highs[s], l = lows[s];
        if (h == null || l == null || l.n == 0 || h.n == 0) continue;
        // Too few hours is one print wearing a median's clothes: left out, so the item falls back to its live price.
        if (h.n < MIN_ROBUST_HOURS || l.n < MIN_ROBUST_HOURS) continue;
        out.put(slots.ids[s], new RobustPrice(h.upperMedian(), l.upperMedian()));
      }
      return out.isEmpty() ? null : out;
    }
  }

  /** typicalVolumesCached's loop: every listed row's two volumes (a missing one is 0), each side kept separately. */
  static final class TypicalAcc {
    final int capacity;
    final Slots slots = new Slots();
    LongList[] highs = new LongList[4096];
    LongList[] lows = new LongList[4096];

    TypicalAcc(int capacity) {
      this.capacity = capacity;
    }

    void add(HourBucket b) {
      for (int i = 0; i < b.size(); i++) {
        int s = slots.slot(b.id(i));
        if (s >= highs.length) {
          highs = Arrays.copyOf(highs, highs.length * 2);
          lows = Arrays.copyOf(lows, lows.length * 2);
        }
        if (highs[s] == null) {
          highs[s] = new LongList(capacity);
          lows[s] = new LongList(capacity);
        }
        highs[s].add(b.highVolume(i));
        lows[s].add(b.lowVolume(i));
      }
    }

    TreeMap<Integer, Long> result() {
      TreeMap<Integer, Long> out = new TreeMap<>();
      for (int s = 0; s < slots.size; s++) {
        // Too few hours to call anything typical: fall back rather than invent one.
        if (highs[s].n < MIN_TYPICAL_VOLUME_HOURS) continue;
        // An order is limited by the thinner side of the book; the sides' medians are combined only at the end.
        long v = Math.min(highs[s].upperMedian(), lows[s].upperMedian());
        // A zero is not published: it would read as a measured zero and size the item at one unit.
        if (v > 0) out.put(slots.ids[s], v);
      }
      return out;
    }
  }

  /**
   * stretchBasis's loop: each item's hourly lows above 0 with their hour, for buckets with ts > cut (strict). The archive
   * holds one bucket per hour, so the "last bucket per ts wins" of the JS map is the only bucket.
   */
  static final class StretchAcc {
    final long cut;
    final int capacity;
    final Slots slots = new Slots();
    LongList[] ts = new LongList[4096];
    LongList[] lows = new LongList[4096];

    StretchAcc(long cut, int capacity) {
      this.cut = cut;
      this.capacity = capacity;
    }

    void add(HourBucket b) {
      if (b == null || !(b.ts > cut)) return;
      for (int i = 0; i < b.size(); i++) {
        long l = b.avgLow(i);
        if (l == HourBucket.NONE || !(l > 0)) continue;
        int s = slots.slot(b.id(i));
        if (s >= ts.length) {
          ts = Arrays.copyOf(ts, ts.length * 2);
          lows = Arrays.copyOf(lows, lows.length * 2);
        }
        if (ts[s] == null) {
          ts[s] = new LongList(capacity);
          lows[s] = new LongList(capacity);
        }
        ts[s].add(b.ts);
        lows[s].add(l);
      }
    }

    /** Never null; items short of either minimum, or with a move of 0, are left out. */
    TreeMap<Integer, StretchBasis> result() {
      TreeMap<Integer, StretchBasis> out = new TreeMap<>();
      for (int s = 0; s < slots.size; s++) {
        LongList t = ts[s], l = lows[s];
        if (t == null || l.n < MIN_STRETCH_HOURS) continue;
        Map<Long, Long> byTs = new java.util.HashMap<>(l.n * 2);
        for (int k = 0; k < l.n; k++) byTs.put(t.a[k], l.a[k]);
        if (byTs.size() < MIN_STRETCH_HOURS) continue;
        double[] moves = new double[byTs.size()];
        int m = 0;
        for (Map.Entry<Long, Long> e : byTs.entrySet()) {
          Long before = byTs.get(e.getKey() - DAY);
          if (before != null && before > 0) moves[m++] = Math.abs(StrictMath.log((double) e.getValue() / before));
        }
        if (m < MIN_STRETCH_PAIRS) continue;
        Arrays.sort(moves, 0, m);
        double move = moves[m / 2];
        if (!(move > 0)) continue;
        LongList levels = new LongList(byTs.size());
        for (long v : byTs.values()) levels.add(v);
        out.put(slots.ids[s], new StretchBasis(levels.upperMedian(), move));
      }
      return out;
    }
  }

  /**
   * patienceBasis's loop: per item, the hourly average highs of hours where buyers paid (highVolume > 0 and a high above
   * 0), and the units traded on both sides, for buckets with ts > cut (strict). One bucket per hour in the archive, so the
   * JS map's "last bucket per ts wins" is the only bucket.
   */
  static final class PatienceAcc {
    final long cut;
    final Slots slots = new Slots();
    LongList[] highs = new LongList[4096];
    long[] units = new long[4096];
    boolean[] seen = new boolean[4096];

    PatienceAcc(long cut) {
      this.cut = cut;
    }

    void add(HourBucket b) {
      if (b == null || !(b.ts > cut)) return;
      for (int i = 0; i < b.size(); i++) {
        int s = slots.slot(b.id(i));
        if (s >= highs.length) {
          highs = Arrays.copyOf(highs, highs.length * 2);
          units = Arrays.copyOf(units, units.length * 2);
          seen = Arrays.copyOf(seen, seen.length * 2);
        }
        seen[s] = true;
        long hv = b.highVolume(i) > 0 ? b.highVolume(i) : 0, lv = b.lowVolume(i) > 0 ? b.lowVolume(i) : 0;
        units[s] += hv + lv;
        long h = b.avgHigh(i);
        if (hv > 0 && h != HourBucket.NONE && h > 0) {
          if (highs[s] == null) highs[s] = new LongList(PATIENCE_HOURS);
          highs[s].add(h);
        }
      }
    }

    /** Never null; items short of the priced hours or the trade, or under the floor, are left out. */
    TreeMap<Integer, PatienceBasis> result() {
      TreeMap<Integer, PatienceBasis> out = new TreeMap<>();
      for (int s = 0; s < slots.size; s++) {
        LongList h = highs[s];
        if (!seen[s] || h == null || h.n < MIN_PATIENCE_HOURS || units[s] < PATIENCE_MIN_UNITS) continue;
        Arrays.sort(h.a, 0, h.n);
        int n = h.n;
        // (a + b) / 2 then Math.floor, exactly: a whole sum, floored division (the values are positive).
        long typicalHigh = n % 2 == 1 ? h.a[(n - 1) / 2] : Math.floorDiv(h.a[n / 2 - 1] + h.a[n / 2], 2);
        if (!(typicalHigh >= PATIENCE_PRICE_FLOOR)) continue;
        out.put(slots.ids[s], new PatienceBasis(typicalHigh, n));
      }
      return out;
    }
  }

  // ------------------------------------------------------------------------------------- list-based ports

  /**
   * suggestions.mjs {@code robustPrices(hourlyBuckets, hours)}: null for no buckets or no qualifying item. The newest
   * ts is {@code max(0, every ts)}, and only buckets with {@code ts > newest - hours*3600} count.
   */
  public static Map<Integer, RobustPrice> robustPrices(List<HourBucket> buckets, int hours) {
    if (buckets == null || buckets.isEmpty()) return null;
    long newest = 0;
    for (HourBucket b : buckets) if (b != null) newest = Math.max(newest, b.ts);
    RobustAcc acc = new RobustAcc(newest - hours * HOUR, buckets.size());
    for (HourBucket b : buckets) acc.add(b);
    return acc.result();
  }

  /** priceArchive.mjs {@code readArchive} over buckets already in memory: ts in [from, to], the LAST per ts wins, oldest first. */
  public static List<HourBucket> readArchive(List<HourBucket> buckets, long fromTs, long toTs) {
    Map<Long, HourBucket> byTs = new LinkedHashMap<>();
    for (HourBucket b : buckets) if (b.ts >= fromTs && b.ts <= toTs) byTs.put(b.ts, b);
    List<HourBucket> out = new ArrayList<>(byTs.values());
    out.sort((x, y) -> Long.compare(x.ts, y.ts));
    return out;
  }

  /** server.mjs {@code robustPricesCached}'s build, at {@code nowMs}: never null (nothing is an empty map). */
  public static Map<Integer, RobustPrice> robustPricesAt(List<HourBucket> archive, long nowMs) {
    Map<Integer, RobustPrice> p = robustPrices(readArchive(archive, Math.floorDiv(nowMs, 1000) - ROBUST_PRICE_HOURS * HOUR, Long.MAX_VALUE),
      ROBUST_PRICE_HOURS);
    return p == null ? new TreeMap<>() : p;
  }

  /**
   * stretch.mjs {@code stretchBasis(hourlyBuckets, hours)}: never null. The newest ts is {@code max(0, every ts)}, and only
   * buckets with {@code ts > newest - hours*3600} count, as in {@link #robustPrices}.
   */
  public static Map<Integer, StretchBasis> stretchBasis(List<HourBucket> buckets, int hours) {
    if (buckets == null || buckets.isEmpty()) return new TreeMap<>();
    long newest = 0;
    for (HourBucket b : buckets) if (b != null) newest = Math.max(newest, b.ts);
    StretchAcc acc = new StretchAcc(newest - hours * HOUR, buckets.size());
    for (HourBucket b : buckets) acc.add(b);
    return acc.result();
  }

  /** server.mjs {@code stretchBasisCached}'s build, at {@code nowMs}: never null (nothing is an empty map). */
  public static Map<Integer, StretchBasis> stretchBasisAt(List<HourBucket> archive, long nowMs) {
    return stretchBasis(readArchive(archive, Math.floorDiv(nowMs, 1000) - STRETCH_HOURS * HOUR, Long.MAX_VALUE), STRETCH_HOURS);
  }

  /**
   * patience.mjs {@code patienceBasis(hourlyBuckets, hours)}: never null. The newest ts is {@code max(0, every ts)}, and only
   * buckets with {@code ts > newest - hours*3600} count.
   */
  public static Map<Integer, PatienceBasis> patienceBasis(List<HourBucket> buckets, int hours) {
    if (buckets == null || buckets.isEmpty()) return new TreeMap<>();
    long newest = 0;
    for (HourBucket b : buckets) if (b != null) newest = Math.max(newest, b.ts);
    PatienceAcc acc = new PatienceAcc(newest - hours * HOUR);
    for (HourBucket b : buckets) acc.add(b);
    return acc.result();
  }

  /**
   * server.mjs {@code patienceBasisCached}'s build, at {@code nowMs}: never null. It reads back TWO windows (48 hours) so the
   * newest 24 STORED hours are inside the read whatever the Wiki's lag; the basis then keeps only those 24.
   */
  public static Map<Integer, PatienceBasis> patienceBasisAt(List<HourBucket> archive, long nowMs) {
    return patienceBasis(readArchive(archive, Math.floorDiv(nowMs, 1000) - 2L * PATIENCE_HOURS * HOUR, Long.MAX_VALUE), PATIENCE_HOURS);
  }

  /** server.mjs {@code typicalVolumesCached}'s build, at {@code nowMs}. */
  public static Map<Integer, Long> typicalVolumesAt(List<HourBucket> archive, long nowMs) {
    List<HourBucket> window = readArchive(archive, Math.floorDiv(nowMs, 1000) - TYPICAL_VOLUME_HOURS * HOUR, Long.MAX_VALUE);
    TypicalAcc acc = new TypicalAcc(window.size());
    for (HourBucket b : window) acc.add(b);
    return acc.result();
  }

  /**
   * suggestions.mjs {@code volumeReadingFor}: how much traded in the latest hour, for SIZING. Null -- no reading,
   * constrains nothing -- when there is no hour or the item is ABSENT from it; otherwise the thinner side, where a
   * listed zero is a measured 0 and must constrain.
   */
  public static Long volumeReadingFor(HourBucket latestHour, int itemId) {
    if (latestHour == null) return null;
    int i = latestHour.indexOf(itemId);
    if (i < 0) return null;
    return Math.min(latestHour.highVolume(i), latestHour.lowVolume(i));
  }

  // ------------------------------------------------------------------------------------- production path

  /**
   * Both readings at {@code nowMs}, streamed from the archive's hour files ONE AT A TIME -- the same answers as
   * {@link #robustPricesAt} and {@link #typicalVolumesAt} over the same hours, without holding the window.
   *
   * <p>Hours are read NEWEST FIRST, so the newest hour actually readable defines the robust window before any
   * older hour is added: an hour whose file cannot be read is then simply absent, exactly as if it had never
   * been stored, rather than shifting the window. Must run on the archive's own thread (it reads files).
   */
  public static Readings build(HourlyArchive archive, long nowMs) throws IOException {
    long nowS = Math.floorDiv(nowMs, 1000);
    long robustFrom = nowS - ROBUST_PRICE_HOURS * HOUR, typicalFrom = nowS - TYPICAL_VOLUME_HOURS * HOUR;
    long patienceFrom = nowS - 2L * PATIENCE_HOURS * HOUR; // inside the robust window, so the same hours list covers it
    NavigableSet<Long> hours = archive.storedHours().tailSet(Math.min(robustFrom, typicalFrom), true);
    RobustAcc robust = null;
    StretchAcc stretch = null; // the same window as the robust prices (STRETCH_HOURS == ROBUST_PRICE_HOURS), read in the same pass
    PatienceAcc patience = null; // its window is set by the newest readable hour inside the 48-hour read, as patienceBasisAt's
    TypicalAcc typical = new TypicalAcc(hours.tailSet(typicalFrom, true).size());
    int robustHours = 0, typicalHours = 0;
    for (Long ts : hours.descendingSet()) {
      HourBucket b = archive.read(ts);
      if (b == null) continue; // unreadable: absent, as if never stored
      if (ts >= robustFrom) {
        if (robust == null) robust = new RobustAcc(Math.max(0, ts) - ROBUST_PRICE_HOURS * HOUR, hours.size()); // the newest readable hour
        if (robust.add(b)) robustHours++;
        if (stretch == null) stretch = new StretchAcc(Math.max(0, ts) - STRETCH_HOURS * HOUR, hours.size());
        stretch.add(b);
      }
      if (ts >= patienceFrom) {
        if (patience == null) patience = new PatienceAcc(Math.max(0, ts) - PATIENCE_HOURS * HOUR); // the newest readable hour
        patience.add(b);
      }
      if (ts >= typicalFrom) {
        typical.add(b);
        typicalHours++;
      }
    }
    TreeMap<Integer, RobustPrice> r = robust == null ? null : robust.result();
    return new Readings(r == null ? new TreeMap<>() : r, typical.result(), stretch == null ? new TreeMap<>() : stretch.result(),
      patience == null ? new TreeMap<>() : patience.result(), robustHours, typicalHours);
  }
}
