package com.evi.live.market;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Turns the Wiki's v2 response bodies into the plugin's types, ROUNDING EVERY NUMBER HALF-UP ON ARRIVAL.
 *
 * <p>Why on arrival: v2's aggregates carry up to two decimals while every calibrated constant in the engine was
 * measured on v1's integers, and v1 is exactly {@code Math.round(v2)}. Rounding before anything is stored or
 * computed keeps the stored basis identical to v1 and to the bridge's archive, so nothing downstream ever sees a
 * fraction -- and nothing fractional can reach a quote ({@code Suggestion.buyPrice} is a {@code long}, and the
 * outcome tracker matches prices by exact equality).
 *
 * <p>Parsed with Gson's STREAMING reader, so a 340 KB {@code /latest} body never becomes a tree of objects. A whole
 * number stays exact as a {@code long} (prices can exceed 2^31); a number with a fraction is parsed from its text to
 * the same double JavaScript would parse it to, and rounded with {@link Math#round(double)}.
 * No networking here: {@link WikiPriceClient} fetches, this only reads text.
 */
public final class WikiJson {
  private WikiJson() {}

  private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");

  /**
   * A JSON number's text, rounded half-up: {@code "102.5"} is 103, {@code "102.49"} is 102, {@code "-2.5"} is -2,
   * exactly as JavaScript's {@code Math.round} (Java's {@code Math.round(double)} is {@code floor(x + 0.5)}).
   */
  static long rounded(String number) {
    if (INTEGER.matcher(number).matches()) return asJavaScript(Long.parseLong(number));
    return Math.round(Double.parseDouble(number));
  }

  /** Beyond 2^53 JavaScript holds a whole number as the NEAREST double ({@code 9007199254740993} reads as {@code ...992});
   *  a long converts to that same double. No GE price comes near this; it keeps the two readers identical anyway. */
  static long asJavaScript(long whole) {
    return whole > MAX_SAFE || whole < -MAX_SAFE ? (long) (double) whole : whole;
  }

  private static final long MAX_SAFE = 9007199254740991L;

  /**
   * The next JSON number, rounded half-up. A whole number is read by Gson's own long path, which allocates nothing (the
   * plugin's archive files hold only whole numbers and are read by the hundred); a number with a fraction makes
   * {@code nextLong} refuse WITHOUT consuming it, and it is then read as text and rounded by {@link #rounded}.
   */
  static long readRounded(JsonReader r) throws IOException {
    try {
      return asJavaScript(r.nextLong());
    } catch (NumberFormatException fraction) {
      return rounded(r.nextString());
    }
  }

  /** A JSON number's text as a long when it is a whole number; null for a fraction or anything out of range. */
  static Long integral(String number) {
    if (INTEGER.matcher(number).matches()) return Long.parseLong(number);
    double d;
    try {
      d = Double.parseDouble(number);
    } catch (NumberFormatException e) {
      return null;
    }
    if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d) || Math.abs(d) > 9007199254740991d) return null;
    return (long) d;
  }

  /**
   * An object key as an item id, or null. The Wiki's keys are item ids in decimal; JavaScript orders such keys
   * numerically, which is the order the archive line is written in. A key that is not a canonical id (no sign, no
   * leading zero, at most 2^31-1) cannot be an item, and is skipped.
   */
  static Integer itemId(String key) {
    int v = itemIdOrMinus(key);
    return v < 0 ? null : v;
  }

  /** {@link #itemId} without boxing: the id, or -1. Hand-written (no regex) because it runs once per row of every hour read. */
  static int itemIdOrMinus(String key) {
    int n = key.length();
    if (n == 0 || n > 10 || (n > 1 && key.charAt(0) == '0')) return -1;
    long v = 0;
    for (int i = 0; i < n; i++) {
      char c = key.charAt(i);
      if (c < '0' || c > '9') return -1;
      v = v * 10 + (c - '0');
    }
    return v > Integer.MAX_VALUE ? -1 : (int) v;
  }

  /**
   * A {@code /1h} (or {@code /5m}) body as an hour bucket: compactBucket with v1's rounding. {@code timestamp} is the
   * bucket's start in unix seconds, as the Wiki labels it; null when the body has none (nothing is stored then,
   * as priceArchive.mjs stores only a safe-integer ts). A {@code null} row is skipped ({@code if (!x) continue});
   * an average that is null or absent stays absent; a volume that is null or absent is 0.
   */
  public static HourBucket hour(String body) throws IOException {
    JsonReader r = new JsonReader(new StringReader(body));
    Long ts = null;
    TreeMap<Integer, long[]> rows = new TreeMap<>();
    r.beginObject();
    while (r.hasNext()) {
      String name = r.nextName();
      if ("timestamp".equals(name) && r.peek() == JsonToken.NUMBER) {
        ts = integral(r.nextString());
      } else if ("data".equals(name) && r.peek() == JsonToken.BEGIN_OBJECT) {
        r.beginObject();
        while (r.hasNext()) {
          Integer id = itemId(r.nextName());
          if (id == null || r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            continue;
          }
          long[] row = {HourBucket.NONE, 0, HourBucket.NONE, 0};
          r.beginObject();
          while (r.hasNext()) {
            String field = r.nextName();
            int k = "avgHighPrice".equals(field) ? 0 : "highPriceVolume".equals(field) ? 1 : "avgLowPrice".equals(field) ? 2
              : "lowPriceVolume".equals(field) ? 3 : -1;
            if (k < 0 || r.peek() != JsonToken.NUMBER) {
              r.skipValue(); // an absent or null field keeps its default: NONE for a price, 0 for a volume
              continue;
            }
            row[k] = readRounded(r);
          }
          r.endObject();
          rows.put(id, row); // a repeated key: the last one wins, as in JSON.parse
        }
        r.endObject();
      } else {
        r.skipValue();
      }
    }
    r.endObject();
    return ts == null ? null : HourBucket.of(ts, rows);
  }

  /**
   * A {@code /latest} body: each item's last instant-buy ({@code high}) and instant-sell ({@code low}) price and when
   * they printed. Integers on both API versions, rounded anyway so the rule has no exceptions.
   */
  public static LatestPrices latest(String body) throws IOException {
    JsonReader r = new JsonReader(new StringReader(body));
    TreeMap<Integer, LatestPrices.Quote> quotes = new TreeMap<>();
    r.beginObject();
    while (r.hasNext()) {
      String name = r.nextName();
      if (!"data".equals(name) || r.peek() != JsonToken.BEGIN_OBJECT) {
        r.skipValue();
        continue;
      }
      r.beginObject();
      while (r.hasNext()) {
        Integer id = itemId(r.nextName());
        if (id == null || r.peek() != JsonToken.BEGIN_OBJECT) {
          r.skipValue();
          continue;
        }
        long[] q = {HourBucket.NONE, HourBucket.NONE, HourBucket.NONE, HourBucket.NONE};
        r.beginObject();
        while (r.hasNext()) {
          String field = r.nextName();
          int k = "high".equals(field) ? 0 : "highTime".equals(field) ? 1 : "low".equals(field) ? 2 : "lowTime".equals(field) ? 3 : -1;
          if (k < 0 || r.peek() != JsonToken.NUMBER) {
            r.skipValue();
            continue;
          }
          q[k] = readRounded(r);
        }
        r.endObject();
        quotes.put(id, new LatestPrices.Quote(q[0], q[1], q[2], q[3]));
      }
      r.endObject();
    }
    r.endObject();
    return new LatestPrices(quotes);
  }
}
