package com.evi.live.journal;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A character's FORMER display names, as the player states them for the one-time import (the maintainer's decision, 8 Oct 2026):
 * {@code "oldNames": {"OldName": "Current Name"}} in {@code import/preferences.json}. The bridge's flip histories from another
 * tracker are kept by the display name each trade was made under; a character that has since been renamed would otherwise never
 * take the trades made under its old name. Read ONLY by the import; nothing else uses it.
 *
 * <p>The rules, each deliberate:
 * <ul>
 *   <li><b>Explicit only.</b> A name is a former name only when the file says so; nothing is ever inferred.</li>
 *   <li>Names are compared as {@link CharacterNames#key} compares them (case; space / underscore / hyphen / non-breaking space
 *       alike), on both sides.</li>
 *   <li>A former name is RESOLVED to its current name before any comparison, so trades under it are taken by the character now
 *       holding the current name and by no other -- not even by a character whose own name is the former one.</li>
 *   <li><b>Refused</b> (the trades under that former name are taken by NO character): a former name mapped to two different
 *       current names (exact duplicate keys, or two spellings of one name); a current name that is not a non-blank string; and
 *       a CHAIN -- a current name that is itself listed as a former name ({@code "A": "B", "B": "C"} refuses A). Chains are
 *       refused rather than followed: the file is meant to name each former name's CURRENT name, so a current name that is
 *       itself a former one is a contradiction, and following it would guess which character the player meant.</li>
 *   <li>An entry mapping a name to itself means nothing and is dropped; a blank former name matches nothing and is dropped.</li>
 * </ul>
 */
public final class OldNames {
  /** No former names: every flip is compared by its own name, as before 8 Oct 2026. */
  public static final OldNames NONE = new OldNames(Collections.emptyMap(), Collections.emptySet(), false);

  /** Former name key -> current name key. */
  private final Map<String, String> current;
  /** Former name keys refused (see the class note). */
  private final Set<String> refused;
  /** Whether the file held an {@code oldNames} object at all (even an empty one). */
  public final boolean present;

  private OldNames(Map<String, String> current, Set<String> refused, boolean present) {
    this.current = Collections.unmodifiableMap(current);
    this.refused = Collections.unmodifiableSet(refused);
    this.present = present;
  }

  /** How many former names are mapped (usable), and how many were refused. */
  public int mapped() {
    return current.size();
  }

  public int refusedCount() {
    return refused.size();
  }

  /** Whether trades kept under {@code key} (a {@link CharacterNames#key}) are refused. */
  boolean refused(String key) {
    return key != null && refused.contains(key);
  }

  /** The current name key a former name key maps to, or null when it is not a (usable) former name. */
  String currentOf(String key) {
    return key == null ? null : current.get(key);
  }

  /** From explicit pairs (former name, current name; a null current name is an invalid entry). Package-private: tests. */
  static OldNames of(List<String[]> pairs, boolean present) {
    Map<String, Set<String>> targets = new LinkedHashMap<>();
    Set<String> refused = new LinkedHashSet<>();
    for (String[] p : pairs) {
      String k = CharacterNames.key(p[0]);
      if (k == null) continue; // a blank former name matches nothing
      String v = p[1] == null ? null : CharacterNames.key(p[1]);
      if (v == null) {
        refused.add(k); // stated as a former name, with no usable current name: its trades go to no one
        continue;
      }
      targets.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(v);
    }
    Map<String, String> current = new HashMap<>();
    for (Map.Entry<String, Set<String>> e : targets.entrySet()) {
      String k = e.getKey();
      if (refused.contains(k)) continue;
      if (e.getValue().size() > 1) {
        refused.add(k); // one former name, two different current names
        continue;
      }
      String v = e.getValue().iterator().next();
      if (!v.equals(k)) current.put(k, v); // a name mapped to itself means nothing
    }
    // Chains: a current name that is itself a stated former name (mapped or refused) -- refused, never followed.
    Set<String> former = new HashSet<>(current.keySet());
    former.addAll(refused);
    for (Map.Entry<String, String> e : new ArrayList<>(current.entrySet())) {
      if (former.contains(e.getValue())) {
        current.remove(e.getKey());
        refused.add(e.getKey());
      }
    }
    return new OldNames(current, refused, present);
  }

  /**
   * The {@code oldNames} object of a preferences file's text (every other field is skipped unread). No such field (or null):
   * {@link #NONE}. Throws {@link IllegalArgumentException} when the text is not one JSON object, or {@code oldNames} is not an
   * object: the import is then refused rather than run without names the player meant to give (it runs once per account).
   * Duplicate keys are seen here, which a parsed tree would hide (the last one silently wins).
   */
  public static OldNames parse(String text) {
    List<String[]> pairs = new ArrayList<>();
    boolean present = false;
    // A leading byte-order mark (Notepad may save one) is skipped by JsonReader itself (mutation-checked 8 Oct: stripping it here
    // changed nothing); the test pins that.
    try (JsonReader r = new JsonReader(new StringReader(text))) {
      if (r.peek() != JsonToken.BEGIN_OBJECT) throw new IllegalArgumentException("not a JSON object");
      r.beginObject();
      while (r.hasNext()) {
        if (!"oldNames".equals(r.nextName())) {
          r.skipValue();
          continue;
        }
        if (r.peek() == JsonToken.NULL) {
          r.nextNull();
          continue;
        }
        if (r.peek() != JsonToken.BEGIN_OBJECT) throw new IllegalArgumentException("oldNames is not an object");
        present = true;
        r.beginObject();
        while (r.hasNext()) {
          String k = r.nextName();
          if (r.peek() == JsonToken.STRING) pairs.add(new String[]{k, r.nextString()});
          else {
            r.skipValue();
            pairs.add(new String[]{k, null});
          }
        }
        r.endObject();
      }
      r.endObject();
      if (r.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("more than one JSON value");
    } catch (IOException | IllegalStateException e) {
      throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
    }
    return of(pairs, present);
  }
}
