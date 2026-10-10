package com.evi.live.engine;

import com.evi.live.journal.Js;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * The JavaScript value rules the engine's ports lean on that {@code journal.Js} does not already cover: turning a
 * query-string value into a number exactly as {@code Number(string)} does, {@code String.prototype.trim}, and
 * converting an integral double to a quantity without ever wrapping or saturating.
 *
 * <p>Pure: no I/O, no clock, no threads. Each rule is checked against the real JS by EngineVectorsTest.
 *
 * <p>WHY {@code Number(string)} IS NOT {@code Double.parseDouble}. They disagree on exactly the inputs a query string
 * can carry: JS reads {@code " "} and {@code ""} as 0, {@code "0x10"} as 16, {@code "0b11"} as 3, and accepts
 * {@code "Infinity"}; Java throws on the first three and also accepts {@code "1d"}, {@code "NaN"}, {@code "0x1p3"}
 * and trims every control character, where JS trims only Unicode white space and line terminators.
 */
public final class JsValues {
  private JsValues() {}

  // StrDecimalLiteral without the Infinity forms: ASCII digits only, no separators, an optional sign.
  private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
  private static final Pattern NON_DECIMAL = Pattern.compile("0(?:[xX][0-9a-fA-F]+|[oO][0-7]+|[bB][01]+)");

  /**
   * ECMAScript WhiteSpace and LineTerminator: what {@code trim} removes and what {@code Number(" 3 ")} ignores.
   * TAB, VT, FF, ZWNBSP, every Zs space, LF, CR, LS and PS -- and NOT the other control characters, which Java's
   * {@link String#trim} would strip. Every non-ASCII character here is a unicode ESCAPE, never a literal: an invisible
   * literal does not survive a mis-encoded round trip of the file, and nobody reviewing it can see what it is.
   */
  public static boolean isJsSpace(char c) {
    switch (c) {
      case '\t': case '\u000B': case '\f': case ' ': case '\u00A0': case '\uFEFF':
      case '\n': case '\r': case '\u2028': case '\u2029':
      case '\u1680': case '\u202F': case '\u205F': case '\u3000':
        return true;
      default:
        return c >= '\u2000' && c <= '\u200A';
    }
  }

  /** {@code String.prototype.trim}. */
  public static String trim(String s) {
    int a = 0, b = s.length();
    while (a < b && isJsSpace(s.charAt(a))) a++;
    while (b > a && isJsSpace(s.charAt(b - 1))) b--;
    return s.substring(a, b);
  }

  /**
   * {@code Number(s)} for a string (ECMAScript StringToNumber): white space trimmed; empty is 0; a decimal literal,
   * {@code Infinity} with an optional sign, or an unsigned 0x/0o/0b integer, each rounded to the nearest double;
   * anything else NaN.
   */
  public static double toNumber(String s) {
    String t = trim(s);
    if (t.isEmpty()) return 0;
    switch (t) {
      case "Infinity": case "+Infinity": return Double.POSITIVE_INFINITY;
      case "-Infinity": return Double.NEGATIVE_INFINITY;
      default: break;
    }
    if (DECIMAL.matcher(t).matches()) return Double.parseDouble(t);
    if (NON_DECIMAL.matcher(t).matches()) {
      char k = Character.toLowerCase(t.charAt(1));
      int radix = k == 'x' ? 16 : k == 'o' ? 8 : 2;
      return new BigInteger(t.substring(2), radix).doubleValue(); // nearest double, ties to even, as JS rounds
    }
    return Double.NaN;
  }

  /** {@code Number.isFinite}. */
  public static boolean isFinite(double d) {
    return !Double.isNaN(d) && !Double.isInfinite(d);
  }

  /** {@code Number.isInteger}. */
  public static boolean isInteger(double d) {
    return isFinite(d) && Math.floor(d) == d;
  }

  // 2^63: the first magnitude a long cannot hold.
  private static final double TWO_63 = 0x1p63;
  // 2^52: from here on every double is a whole number, so Math.round has nothing to do.
  private static final double TWO_52 = 0x1p52;

  /**
   * {@code Math.round} exactly as JavaScript has it, as a DOUBLE: half-up ({@code 2.5 -> 3}, {@code -2.5 -> -2}), NaN and
   * the infinities unchanged, and -- the part a {@code long} cannot carry -- MINUS ZERO for anything in [-0.5, 0) and for
   * -0 itself. That sign is observable: {@code Math.round(-0.3).toLocaleString('en-US')} is "-0" in the bridge's wording,
   * so a sentence quoting a tiny negative margin says "-0 gp" there and must say it here too ({@link #gp}).
   */
  public static double jsRound(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x)) return x;
    if (x < 0 && x >= -0.5 || (x == 0 && 1 / x < 0)) return -0.0;
    if (Math.abs(x) >= TWO_52) return x;
    return (double) Math.round(x); // Java's Math.round is floor(x + 0.5) done exactly, as V8's is
  }

  /**
   * {@code (x).toLocaleString('en-US')} for ANY number, pinned to the US format whatever the host locale is (a Dutch
   * install prints "1.234,5"): grouping commas, at most three fraction digits, rounded half AWAY from zero ("halfExpand")
   * -- and rounded on the number's SHORTEST decimal form, as ICU does, not on its exact binary value: {@code 1.0005}
   * prints "1.001" there although the double is 1.000499999.... A negative value that rounds to zero keeps its sign
   * ("-0"), and so does -0. NaN is "NaN" and the infinities "∞"/"-∞", as ICU writes them.
   */
  public static String localeUs(double x) {
    if (Double.isNaN(x)) return "NaN";
    if (Double.isInfinite(x)) return x > 0 ? "∞" : "-∞";
    boolean negative = x < 0 || (x == 0 && 1 / x < 0);
    BigDecimal shortest = new BigDecimal(Js.numberToString(Math.abs(x)));
    BigDecimal r = shortest.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    String plain = r.signum() == 0 ? "0" : r.toPlainString();
    int dot = plain.indexOf('.');
    String whole = dot < 0 ? plain : plain.substring(0, dot), fraction = dot < 0 ? "" : plain.substring(dot);
    StringBuilder sb = new StringBuilder(negative ? "-" : "");
    for (int i = 0; i < whole.length(); i++) {
      if (i > 0 && (whole.length() - i) % 3 == 0) sb.append(',');
      sb.append(whole.charAt(i));
    }
    return sb.append(fraction).toString();
  }

  /** {@code Math.round(n).toLocaleString('en-US')}: the bridge's {@code gp(n)} helper, "-0" included. */
  public static String gp(double n) {
    return localeUs(jsRound(n));
  }

  /**
   * {@code (x).toFixed(digits)} exactly as JavaScript has it (Number.prototype.toFixed): rounded on the number's EXACT
   * binary value, a tie going to the LARGER magnitude -- so {@code (0.15).toFixed(1)} is "0.1" (0.1499999...) and
   * {@code (0.05).toFixed(1)} is "0.1" (0.0500000...). A negative number keeps its sign even when it rounds to zero
   * ({@code (-0.04).toFixed(1)} is "-0.0"); -0 prints without one. NaN is "NaN", and from 1e21 up the plain
   * {@code String(x)} is returned, as the spec says. Never {@code String.format}, which rounds the shortest decimal form
   * and follows the host locale (a Dutch install prints "0,1").
   */
  public static String toFixed(double x, int digits) {
    if (Double.isNaN(x)) return "NaN";
    if (Math.abs(x) >= 1e21 || Double.isInfinite(x)) return Js.numberToString(x);
    boolean negative = x < 0;
    BigDecimal r = new BigDecimal(Math.abs(x)).setScale(digits, RoundingMode.HALF_UP);
    return (negative ? "-" : "") + r.toPlainString();
  }

  /** {@code `${n}`}: a number interpolated into a template (ECMAScript Number::toString; -0 prints "0"). */
  public static String text(double n) {
    return Js.numberToString(n);
  }

  /**
   * An integral JS number as a long: a quantity the JS engine computed with {@code Math.floor}/{@code Math.max}.
   * Throws (ArithmeticException) for NaN, a fraction, an infinity or anything past what a long holds -- never wraps
   * round to the other sign or saturates, the same refusal {@code journal.Js.round} makes.
   */
  public static long exactLong(double d) {
    if (Double.isNaN(d) || d >= TWO_63 || d < -TWO_63 || Math.floor(d) != d)
      throw new ArithmeticException("quantity " + d + " is not a whole number a long can hold");
    return (long) d;
  }
}
