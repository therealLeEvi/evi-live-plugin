package com.evi.live.inprocess;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * TEST HELPER (moved from the retired developer shadow mode): the field-by-field comparison of a recorded GET /api/suggestion body with the one the plugin's own
 * {@code Engine.decide} wrote for the same poll. The rules are the golden-transcript comparator's (SELF-CONTAINED
 * parity-testing.md, Layer 2), applied to the body as it really arrived over the wire:
 * <ul>
 *   <li>ABSENT AND NULL ARE THE SAME: JSON.stringify drops {@code undefined}, and writes a non-finite number as {@code null}
 *       (so the Java side's NaN/Infinity tag counts as null too);</li>
 *   <li>NUMBERS COMPARE BY VALUE, as the JS double the bridge held ({@code 5} and {@code 5.0} are equal, and so are the bridge's
 *       shortest text of a double and the plugin's exact expansion of the same double; anything else, however small, differs);</li>
 *   <li>ARRAYS COMPARE IN ORDER (the advice and the further positions are display order); a length difference is ONE
 *       difference at the array's path, carrying both arrays;</li>
 *   <li>STRINGS COMPARE EXACTLY: the hedged wording is the product;</li>
 *   <li>ONLY the suggestion log's handle is left out: {@code suggestion.id} (wall clock and a sequence) and
 *       {@code suggestion.accepted} (read back from that log). Nothing else is ever skipped.</li>
 * </ul>
 * Pure; Gson tree classes only.
 */
public final class BodyDiff {
  private BodyDiff() {}

  /** The only paths left out: what the bridge's own suggestion log adds, which no engine computes. */
  public static final Set<String> IGNORED = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList("$.suggestion.id", "$.suggestion.accepted")));

  /** One difference: where, and what each side said there (null: absent or null). */
  public static final class Difference {
    public final String path;
    public final JsonElement bridge;
    public final JsonElement plugin;

    Difference(String path, JsonElement bridge, JsonElement plugin) {
      this.path = path;
      this.bridge = bridge;
      this.plugin = plugin;
    }

    @Override public String toString() {
      return path + ": bridge " + bridge + ", plugin " + plugin;
    }
  }

  /** Every difference between the two bodies, in path order (object keys sorted, array elements in order). */
  public static List<Difference> diff(JsonElement bridge, JsonElement plugin) {
    List<Difference> out = new ArrayList<>();
    diff(bridge, plugin, "$", out);
    return out;
  }

  /** Absent, JSON null, or a non-finite number (which JSON.stringify writes as null). */
  static boolean isNullish(JsonElement e) {
    return e == null || e.isJsonNull() || e.isJsonObject() && e.getAsJsonObject().size() == 1 && e.getAsJsonObject().has("$num");
  }

  static boolean isNumber(JsonElement e) {
    return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
  }

  /**
   * By value, as the bridge holds a number: a JS double. The bridge writes the SHORTEST text that reads back as its double
   * ({@code 214.00000606060607}); the plugin's serialiser writes a double's EXACT decimal expansion and a long as written.
   * So a plugin fraction is equal when it reads back as the same double, and a plugin whole number when it is EXACTLY the
   * bridge's double (so a long past 2^53 that no double can hold is never passed off as equal). A text that is not a
   * finite number is compared as written.
   */
  static boolean sameNumber(String b, String p) {
    try {
      double bd = Double.parseDouble(b);
      if (Double.isNaN(bd) || Double.isInfinite(bd)) return b.equals(p);
      boolean whole = p.indexOf('.') < 0 && p.indexOf('e') < 0 && p.indexOf('E') < 0;
      return whole ? new BigDecimal(p).compareTo(new BigDecimal(bd)) == 0 : Double.parseDouble(p) == bd;
    } catch (NumberFormatException e) {
      return b.equals(p);
    }
  }

  private static void diff(JsonElement b, JsonElement p, String path, List<Difference> out) {
    if (IGNORED.contains(path)) return;
    boolean bn = isNullish(b), pn = isNullish(p);
    if (bn || pn) {
      if (bn != pn) out.add(new Difference(path, bn ? null : b, pn ? null : p));
      return;
    }
    if (isNumber(b) || isNumber(p)) {
      if (!isNumber(b) || !isNumber(p) || !sameNumber(b.getAsString(), p.getAsString()))
        out.add(new Difference(path, b, p));
      return;
    }
    if (b.isJsonArray() && p.isJsonArray()) {
      JsonArray x = b.getAsJsonArray(), y = p.getAsJsonArray();
      if (x.size() != y.size()) {
        out.add(new Difference(path, b, p));
        return;
      }
      for (int i = 0; i < x.size(); i++) diff(x.get(i), y.get(i), path + "[" + i + "]", out);
      return;
    }
    if (b.isJsonObject() && p.isJsonObject()) {
      JsonObject x = b.getAsJsonObject(), y = p.getAsJsonObject();
      TreeSet<String> keys = new TreeSet<>(x.keySet());
      keys.addAll(y.keySet());
      for (String k : keys) diff(x.get(k), y.get(k), path + "." + k, out);
      return;
    }
    if (!b.equals(p)) out.add(new Difference(path, b, p));
  }
}
