package com.evi.live;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * What the sidebar's Skip button set aside, per RuneScape account, for FOUR HOURS -- across world
 * hops, relogs, lost connections and client restarts.
 *
 * WHY (6 Oct 2026). A Skip used to live in a session set that reset() cleared on every LOGIN_SCREEN,
 * HOPPING and CONNECTION_LOST. Sessions are short, and nearly every return of a skipped item came
 * straight after such a reset, so the history tier
 * appeared to "keep suggesting the same thing" when the player had already said no to it. Four hours
 * is one buy-limit window, which is the maintainer's choice: long enough to outlive any hop, short enough that
 * a skipped item comes back the same day.
 *
 * PER ACCOUNT, through RuneLite's own per-account store: the Store passed in is backed by
 * ConfigManager's RS-profile configuration (getRSProfileConfiguration / setRSProfileConfiguration),
 * which RuneLite keys by account and by world type, so a skip on a main never hides an item on an alt,
 * and a Leagues world is a different profile from the main game. With no profile known (logged out)
 * nothing is stored, and the caller keeps the skip for the session instead -- a press must always
 * apply to the item it was pressed on.
 *
 * Only Skip goes here. Block is permanent and lives in BlockedItems; Personal use and "Gone" are recorded in the plugin's
 * journal, so their session exclusion is only there
 * to make the press feel instant.
 *
 * Stored as "itemId:expiryEpochMillis,..." in one RS-profile key. Expired entries are filtered out on
 * every read and dropped from storage on the next write, so the value never grows past what the
 * player skipped in the last four hours. Thread-safe: the poll thread reads, the Swing thread writes.
 */
final class SkipMemory {
  /** How long a Skip lasts: one Grand Exchange buy-limit window. */
  static final long SKIP_MILLIS = TimeUnit.HOURS.toMillis(4);
  /** The RS-profile config key, inside the "evilive" group. */
  static final String CONFIG_KEY = "skippedUntil";

  /** Where skips are kept. Each call names the profile it means, so a profile change between the
   *  lookup and the read can never read one account's skips into another's. */
  interface Store {
    /** The current RuneScape profile, or null when none is known (e.g. logged out). */
    String profileKey();
    /** The stored value for that profile, or null/empty for none. THROWS when it cannot be read (e.g. the profile is no
     *  longer current): "unreadable" must never look like "nothing stored", or the next Skip would write over the list. */
    String read(String profile);
    /** Replaces the stored value for that profile; an empty string removes it. */
    void write(String profile, String value);
  }

  private final Store store;
  private final LongSupplier clock;
  /** Which profile `until` was loaded for, so a poll does not re-read the config every two seconds. */
  private String loadedFor;
  private Map<Integer, Long> until = new TreeMap<>();

  SkipMemory(Store store, LongSupplier clock) {
    this.store = store;
    this.clock = clock;
  }

  /** Sets the item aside for SKIP_MILLIS on the current account. Returns false when no account is
   *  known, so the caller can fall back to a session-only skip. */
  synchronized boolean skip(int itemId) {
    String profile = profile();
    if (profile == null) return false;
    long now = clock.getAsLong();
    if (!load(profile)) return false; // unreadable just now: a session-only skip, never a write over the stored list
    prune(now);
    until.put(itemId, now + SKIP_MILLIS);
    store.write(profile, format(until));
    return true;
  }

  /** The items still set aside on the current account right now. Empty with no account known. */
  synchronized Set<Integer> active() {
    String profile = profile();
    if (profile == null) return Collections.emptySet();
    load(profile);
    long now = clock.getAsLong();
    Set<Integer> ids = new TreeSet<>();
    for (Map.Entry<Integer, Long> e : until.entrySet()) if (e.getValue() > now) ids.add(e.getKey());
    return ids;
  }

  /** "Show skipped items again": forgets every skip on the current account. */
  synchronized void clear() {
    String profile = profile();
    if (profile == null) return;
    if (!load(profile)) return;
    until.clear();
    store.write(profile, "");
  }

  private String profile() {
    try {
      String p = store.profileKey();
      return p == null || p.isEmpty() ? null : p;
    } catch (Exception ex) {
      return null; // an unreadable profile is an unknown one: fall back to the session, never guess
    }
  }

  /**
   * Loads the profile's skips once. A read that FAILS is not cached (review, 10 Oct): it reads as no skips for this call and
   * is tried again on the next, and returns false so a write does not replace the stored list with what little is in memory.
   */
  private boolean load(String profile) {
    if (profile.equals(loadedFor)) return true;
    String raw;
    try {
      raw = store.read(profile);
    } catch (Exception unreadable) {
      until = new TreeMap<>();
      loadedFor = null;
      return false;
    }
    until = parse(raw);
    loadedFor = profile;
    return true;
  }

  private void prune(long now) {
    until.values().removeIf(expiry -> expiry <= now);
  }

  /** Lenient on purpose: a malformed entry is dropped rather than failing the whole list, because a
   *  hand-edited or truncated value must never stop the plugin suggesting at all. */
  static Map<Integer, Long> parse(String raw) {
    Map<Integer, Long> out = new TreeMap<>();
    if (raw == null || raw.isEmpty()) return out;
    for (String part : raw.split(",")) {
      int colon = part.indexOf(':');
      if (colon <= 0) continue;
      try {
        int id = Integer.parseInt(part.substring(0, colon).trim());
        long expiry = Long.parseLong(part.substring(colon + 1).trim());
        if (id >= 0) out.put(id, expiry);
      } catch (NumberFormatException ignored) { }
    }
    return out;
  }

  static String format(Map<Integer, Long> until) {
    StringBuilder b = new StringBuilder();
    for (Map.Entry<Integer, Long> e : new TreeMap<>(until).entrySet()) {
      if (b.length() > 0) b.append(',');
      b.append(e.getKey()).append(':').append(e.getValue());
    }
    return b.toString();
  }
}
