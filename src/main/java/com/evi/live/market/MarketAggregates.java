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

  /** The answers of one {@link #build}: what the engine reads, and how many archived hours they came from. */
  public static final class Readings {
    /** Robust prices by item id; empty (never null) when there is nothing, as robustPricesCached serves it. */
    public final Map<Integer, RobustPrice> robust;
    /** Typical hourly volume by item id (only values above 0). */
    public final Map<Integer, Long> typical;
    /** Hour files read into the robust window, and into the typical window. */
    public final int robustHours;
    public final int typicalHours;

    Readings(Map<Integer, RobustPrice> robust, Map<Integer, Long> typical, int robustHours, int typicalHours) {
      this.robust = Collections.unmodifiableMap(robust);
      this.typical = Collections.unmodifiableMap(typical);
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
    NavigableSet<Long> hours = archive.storedHours().tailSet(Math.min(robustFrom, typicalFrom), true);
    RobustAcc robust = null;
    TypicalAcc typical = new TypicalAcc(hours.tailSet(typicalFrom, true).size());
    int robustHours = 0, typicalHours = 0;
    for (Long ts : hours.descendingSet()) {
      HourBucket b = archive.read(ts);
      if (b == null) continue; // unreadable: absent, as if never stored
      if (ts >= robustFrom) {
        if (robust == null) robust = new RobustAcc(Math.max(0, ts) - ROBUST_PRICE_HOURS * HOUR, hours.size()); // the newest readable hour
        if (robust.add(b)) robustHours++;
      }
      if (ts >= typicalFrom) {
        typical.add(b);
        typicalHours++;
      }
    }
    TreeMap<Integer, RobustPrice> r = robust == null ? null : robust.result();
    return new Readings(r == null ? new TreeMap<>() : r, typical.result(), robustHours, typicalHours);
  }
}
