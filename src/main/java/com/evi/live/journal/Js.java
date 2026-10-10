package com.evi.live.journal;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * The few JavaScript semantics the journal engine depends on, in one place so each is written once and
 * tested once (JournalVectorsTest replays what the real JS engine produced for each).
 *
 * <p>Ported for the self-contained plugin. Pure: no I/O, no clock, no threads.
 *
 * <ul>
 *   <li>Reading a JSON value the way {@code JSON.parse} would: every number is an IEEE double, so
 *       {@code 1.0} is the integer 1, {@code 1e400} is Infinity, and an integer past 2^53 has already
 *       been rounded before any check sees it. {@link #number} parses the original text with
 *       {@link Double#parseDouble}, which rounds exactly as JS does.</li>
 *   <li>{@code Number.isSafeInteger} and the store's own {@code integer(x,min,max)} check.</li>
 *   <li>Writing a journal line exactly as {@code JSON.stringify} does, so a line the Java engine writes
 *       is byte-identical to one the bridge would have written and other readers of the same format still read it:
 *       strings ({@link #quote}) and numbers ({@link #numberToString}, the ECMAScript Number::toString
 *       algorithm: shortest round-trip digits, exponent only outside 1e-7..1e21).</li>
 *   <li>{@code toLocaleString('en-US')} grouping, pinned to {@link Locale#US} whatever the host locale is
 *       (the 6 Oct F2 fix in the bridge is the same rule).</li>
 * </ul>
 */
public final class Js {
  private Js() {}

  /** Number.MAX_SAFE_INTEGER. */
  public static final double MAX_SAFE = 9007199254740991.0;
  /** The default upper bound of store.mjs's integer(): 2^31-1. Beyond Max Cash is the open question here. */
  public static final double INT32_MAX = 2147483647.0;

  /** Number.MAX_SAFE_INTEGER as a long. */
  public static final long MAX_SAFE_LONG = 9007199254740991L;
  // 2^63: the first magnitude a long cannot hold (and -2^63 the last one it can).
  private static final double TWO_63 = 0x1p63;

  /** Number.isSafeInteger for a long. */
  public static boolean isSafe(long v) {
    return v >= -MAX_SAFE_LONG && v <= MAX_SAFE_LONG;
  }

  // ------------------------------------------------------------------------------- GP arithmetic
  //
  // Every GP figure the JS engine computes is a DOUBLE. While every operand and the result are safe
  // integers (|x| <= 2^53 - 1, about 9 quadrillion gp: thousands of times any real Grand Exchange wealth)
  // double arithmetic is exact, so these return exactly what long arithmetic would. Past 2^53 they round
  // exactly as JS does -- the result is the double nearest the true value, which a long then holds
  // exactly -- so Java and JS agree to the last digit there too, instead of Java quietly being "more
  // right" than the journal it shares with the bridge. Past 2^63 a long cannot hold JS's answer at all:
  // these THROW (ArithmeticException), never wrap round to the other sign or saturate at Long.MAX_VALUE.
  //
  // The invariant that makes the past-2^53 branch exact: every long these produce, and every long the
  // journal decodes, is a value a double holds exactly, so (double) v loses nothing.

  /** a + b, as JS adds two integers (see above). */
  public static long add(long a, long b) {
    if (isSafe(a) && isSafe(b)) {
      long r = a + b; // cannot overflow: both are under 2^53
      if (isSafe(r)) return r;
    }
    return exactLong((double) a + (double) b);
  }

  /** a - b, as JS subtracts two integers. */
  public static long subtract(long a, long b) {
    if (isSafe(a) && isSafe(b)) {
      long r = a - b;
      if (isSafe(r)) return r;
    }
    return exactLong((double) a - (double) b);
  }

  /** a x b, as JS multiplies two integers (a price times a quantity). */
  public static long multiply(long a, long b) {
    if (isSafe(a) && isSafe(b)) {
      long r = a * b;
      // The 128-bit product fits a long exactly when its high half is just the sign of the low half.
      if (Math.multiplyHigh(a, b) == (r >> 63) && isSafe(r)) return r;
    }
    return exactLong((double) a * (double) b);
  }

  /**
   * Math.round as JavaScript has it (half-up: 2.5 -> 3, -1.5 -> -1), for a value that must become a
   * long. Throws ArithmeticException for NaN and for anything at or past 2^63, where {@link Math#round}
   * would silently saturate at Long.MAX_VALUE / MIN_VALUE.
   */
  public static long round(double x) {
    if (Double.isNaN(x) || x >= TWO_63 || x < -TWO_63) throw new ArithmeticException("GP figure " + numberToString(x) + " is past what a long can hold");
    return Math.round(x);
  }

  // An integral double as a long, refusing (loudly) anything a long cannot hold.
  private static long exactLong(double d) {
    if (Double.isNaN(d) || d >= TWO_63 || d < -TWO_63) throw new ArithmeticException("GP figure " + numberToString(d) + " is past what a long can hold");
    return (long) d;
  }

  /** Number.isSafeInteger. */
  public static boolean isSafeInteger(double d) {
    return !Double.isNaN(d) && !Double.isInfinite(d) && Math.floor(d) == d && Math.abs(d) <= MAX_SAFE;
  }

  /** Number.isInteger: any finite integral double, however large. */
  public static boolean isInteger(double d) {
    return !Double.isNaN(d) && !Double.isInfinite(d) && Math.floor(d) == d;
  }

  /** The value as JSON.parse would give it, when it is a JSON number; null for anything else (absent too). */
  public static Double number(JsonElement e) {
    if (e == null || !e.isJsonPrimitive()) return null;
    JsonPrimitive p = e.getAsJsonPrimitive();
    if (!p.isNumber()) return null;
    return Double.parseDouble(p.getAsString());
  }

  /** store.mjs: {@code integer(x,min,max) = Number.isSafeInteger(x) && x >= min && x <= max}. */
  public static boolean integer(JsonElement e, double min, double max) {
    Double d = number(e);
    return d != null && isSafeInteger(d) && d >= min && d <= max;
  }

  /** store.mjs: {@code integer(x)} with its defaults, min 0 and max 2^31-1. */
  public static boolean integer(JsonElement e) {
    return integer(e, 0, INT32_MAX);
  }

  /** typeof x === 'string'. */
  public static boolean isString(JsonElement e) {
    return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
  }

  /** typeof x === 'boolean'. */
  public static boolean isBoolean(JsonElement e) {
    return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean();
  }

  /** A string value, or null when the JSON value is anything else. */
  public static String string(JsonElement e) {
    return isString(e) ? e.getAsString() : null;
  }

  /**
   * The sign of a JS sort comparator's {@code a - b}: a NaN difference counts as "equal", exactly as
   * Array.prototype.sort treats it. Double.compare would order NaN last and reorder ties.
   */
  public static int compare(double a, double b) {
    double d = a - b;
    return d < 0 ? -1 : d > 0 ? 1 : 0;
  }

  /** (n).toLocaleString('en-US') for a whole number: grouping commas, nothing else. Never the host locale. */
  public static String groupedUs(long n) {
    return NumberFormat.getIntegerInstance(Locale.US).format(n);
  }

  /** JSON.stringify(s) for a string: the quotes, the short escapes, \\u00xx for other controls and lone surrogates. */
  public static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) { hex4(sb, c); break; }
          if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
            sb.append(c).append(s.charAt(++i));
          } else if (Character.isSurrogate(c)) {
            hex4(sb, c); // well-formed JSON.stringify (ES2019): a lone surrogate is escaped, lowercase
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }

  private static void hex4(StringBuilder sb, char c) {
    String h = Integer.toHexString(c);
    sb.append("\\u");
    for (int k = h.length(); k < 4; k++) sb.append('0');
    sb.append(h);
  }

  /** JSON.stringify(s) for a string. */
  public static String quote(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    quote(sb, s);
    return sb.toString();
  }

  /**
   * String(d), the ECMAScript Number::toString algorithm, which JSON.stringify also uses for finite numbers.
   * Double.toString differs in format ("3.0", "1.0E21") and, on Java 11, is not always the shortest.
   */
  public static String numberToString(double d) {
    if (Double.isNaN(d)) return "NaN";
    if (d == Double.POSITIVE_INFINITY) return "Infinity";
    if (d == Double.NEGATIVE_INFINITY) return "-Infinity";
    if (d == 0) return "0"; // -0 prints as 0 too
    if (d < 0) return "-" + numberToString(-d);
    BigDecimal exact = new BigDecimal(d);
    BigDecimal best = null;
    for (int p = 1; p <= 17 && best == null; p++) {
      BigDecimal r = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
      BigDecimal ulp = r.ulp();
      // The nearest p-digit decimal can miss the round-trip interval where it is lopsided (at a power of
      // two) while a neighbour hits it; the spec then wants the round-tripping one closest to d.
      for (BigDecimal c : new BigDecimal[]{r, r.subtract(ulp), r.add(ulp)}) {
        if (c.signum() <= 0 || c.doubleValue() != d) continue;
        if (best == null || c.subtract(exact).abs().compareTo(best.subtract(exact).abs()) < 0) best = c;
      }
    }
    if (best == null) best = exact; // unreachable: 17 digits always round-trip
    best = best.stripTrailingZeros();
    String s = best.unscaledValue().toString();
    int k = s.length();
    int n = k - best.scale();
    if (k <= n && n <= 21) return s + "0".repeat(n - k);
    if (0 < n && n <= 21) return s.substring(0, n) + "." + s.substring(n);
    if (-6 < n && n <= 0) return "0." + "0".repeat(-n) + s;
    int e = n - 1;
    String exp = "e" + (e >= 0 ? "+" : "-") + Math.abs(e);
    return k == 1 ? s + exp : s.charAt(0) + "." + s.substring(1) + exp;
  }
}
