package com.evi.live.market;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * One hour of the OSRS Wiki's all-items price averages: the bridge's archive bucket
 * {@code {ts, d:{itemId:[avgHighPrice, highPriceVolume, avgLowPrice, lowPriceVolume]}}} (priceArchive.mjs
 * compactBucket), held as primitive arrays sorted by item id.
 *
 * <p>THE BASIS IS v1, i.e. INTEGERS. The Wiki's v2 averages carry up to two decimals, and v1 is exactly
 * {@code Math.round(v2)} (measured on 8,281 of 8,281 readings, CLAUDE.md 4 Oct). Every number is rounded half-up
 * with {@link Math#round(double)} when it ARRIVES ({@link WikiJson#hour}), so what is stored and computed on is the
 * calibrated integer basis. Java's {@code Math.round(double)} is {@code floor(x + 0.5)}, the same as JavaScript's:
 * a {@code .5} tie goes UP (102.5 to 103). Never {@code rint}, HALF_EVEN or a formatter.
 *
 * <p>A missing average stays missing ({@link #NONE}, written as JSON null), never filled in; a missing volume is 0,
 * exactly as compactBucket does ({@code ?? null} for prices, {@code ?? 0} for volumes).
 */
public final class HourBucket {
  /** A price the Wiki did not report for that side of that hour (JSON null). */
  public static final long NONE = Long.MIN_VALUE;

  public final long ts;
  private final int[] ids;
  private final long[] avgHigh;
  private final long[] highVol;
  private final long[] avgLow;
  private final long[] lowVol;

  private HourBucket(long ts, int[] ids, long[] avgHigh, long[] highVol, long[] avgLow, long[] lowVol) {
    this.ts = ts;
    this.ids = ids;
    this.avgHigh = avgHigh;
    this.highVol = highVol;
    this.avgLow = avgLow;
    this.lowVol = lowVol;
  }

  /** A row as four numbers: avgHigh ({@link #NONE} when absent), highVolume, avgLow ({@link #NONE}), lowVolume. */
  static HourBucket of(long ts, Map<Integer, long[]> rows) {
    TreeMap<Integer, long[]> sorted = rows instanceof TreeMap ? (TreeMap<Integer, long[]>) rows : new TreeMap<>(rows);
    int n = sorted.size(), i = 0;
    int[] ids = new int[n];
    long[] ah = new long[n], hv = new long[n], al = new long[n], lv = new long[n];
    for (Map.Entry<Integer, long[]> e : sorted.entrySet()) {
      long[] r = e.getValue();
      ids[i] = e.getKey();
      ah[i] = r[0];
      hv[i] = r[1];
      al[i] = r[2];
      lv[i] = r[3];
      i++;
    }
    return new HourBucket(ts, ids, ah, hv, al, lv);
  }

  public int size() {
    return ids.length;
  }

  public int id(int i) {
    return ids[i];
  }

  public long avgHigh(int i) {
    return avgHigh[i];
  }

  public long highVolume(int i) {
    return highVol[i];
  }

  public long avgLow(int i) {
    return avgLow[i];
  }

  public long lowVolume(int i) {
    return lowVol[i];
  }

  /** The row index of {@code itemId}, or a negative number when this hour has no row for it. */
  public int indexOf(int itemId) {
    return Arrays.binarySearch(ids, itemId);
  }

  /**
   * The archive line, byte for byte what {@code JSON.stringify(compactBucket(json)) + '\n'} writes: {@code ts}
   * first, then {@code d} with its keys in ascending numeric order (JavaScript orders integer-like keys that way),
   * integers in plain decimal, a missing average as {@code null}.
   */
  public String line() {
    StringBuilder sb = new StringBuilder(32 + ids.length * 40);
    sb.append("{\"ts\":").append(ts).append(",\"d\":{");
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(ids[i]).append("\":[");
      num(sb, avgHigh[i]).append(',').append(highVol[i]).append(',');
      num(sb, avgLow[i]).append(',').append(lowVol[i]).append(']');
    }
    return sb.append("}}\n").toString();
  }

  private static StringBuilder num(StringBuilder sb, long v) {
    return v == NONE ? sb.append("null") : sb.append(v);
  }

  /** Parses one archive line (see {@link #line}). Null when it is not a bucket with an integer {@code ts}. */
  public static HourBucket parseLine(String line) throws IOException {
    return parseLine(new StringReader(line));
  }

  /**
   * Parses one archive line from {@code in}. Keys may come in any order; a row that is not an array of four is
   * skipped (robustPrices skips non-arrays too); unknown keys are ignored. A number with a fraction -- which the
   * plugin never writes -- is rounded half-up, so the basis cannot drift to floats by way of a foreign file.
   */
  static HourBucket parseLine(Reader in) throws IOException {
    // Read straight into primitive arrays: hundreds of these are read per rebuild, inside the game client, so no boxed
    // ids and no per-row objects. The plugin writes ids in ascending order; anything else (a foreign file, a repeated
    // id) is put in order afterwards, the LAST of a repeated id winning as in JSON.parse.
    JsonReader r = new JsonReader(in);
    Long ts = null;
    int n = 0;
    int[] ids = new int[4096];
    long[] vals = new long[4 * 4096];
    boolean ascending = true;
    r.beginObject();
    while (r.hasNext()) {
      String name = r.nextName();
      if ("ts".equals(name) && r.peek() == JsonToken.NUMBER) {
        ts = WikiJson.integral(r.nextString());
      } else if ("d".equals(name) && r.peek() == JsonToken.BEGIN_OBJECT) {
        r.beginObject();
        while (r.hasNext()) {
          int id = WikiJson.itemIdOrMinus(r.nextName());
          if (id < 0 || r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue();
            continue;
          }
          if (n == ids.length) {
            ids = Arrays.copyOf(ids, n * 2);
            vals = Arrays.copyOf(vals, n * 8);
          }
          int k = 0;
          boolean ok = true;
          r.beginArray();
          while (r.hasNext()) {
            JsonToken t = r.peek();
            long v;
            if (t == JsonToken.NULL) {
              r.nextNull();
              v = NONE;
            } else if (t == JsonToken.NUMBER) {
              v = WikiJson.readRounded(r);
            } else {
              r.skipValue();
              ok = false;
              v = 0;
            }
            if (k < 4) vals[4 * n + k] = v;
            k++;
          }
          r.endArray();
          if (!ok || k != 4) continue;
          // volumes are never null in a compacted bucket (?? 0); a foreign null volume reads as 0
          if (vals[4 * n + 1] == NONE) vals[4 * n + 1] = 0;
          if (vals[4 * n + 3] == NONE) vals[4 * n + 3] = 0;
          if (n > 0 && id <= ids[n - 1]) ascending = false;
          ids[n++] = id;
        }
        r.endObject();
      } else {
        r.skipValue();
      }
    }
    r.endObject();
    if (ts == null) return null;
    if (!ascending) {
      TreeMap<Integer, long[]> rows = new TreeMap<>();
      for (int i = 0; i < n; i++) rows.put(ids[i], Arrays.copyOfRange(vals, 4 * i, 4 * i + 4));
      return of(ts, rows);
    }
    long[] ah = new long[n], hv = new long[n], al = new long[n], lv = new long[n];
    for (int i = 0; i < n; i++) {
      ah[i] = vals[4 * i];
      hv[i] = vals[4 * i + 1];
      al[i] = vals[4 * i + 2];
      lv[i] = vals[4 * i + 3];
    }
    return new HourBucket(ts, Arrays.copyOf(ids, n), ah, hv, al, lv);
  }

  @Override public boolean equals(Object o) {
    if (!(o instanceof HourBucket)) return false;
    HourBucket b = (HourBucket) o;
    return ts == b.ts && Arrays.equals(ids, b.ids) && Arrays.equals(avgHigh, b.avgHigh) && Arrays.equals(highVol, b.highVol)
      && Arrays.equals(avgLow, b.avgLow) && Arrays.equals(lowVol, b.lowVol);
  }

  @Override public int hashCode() {
    return Long.hashCode(ts) * 31 + Arrays.hashCode(ids);
  }
}
