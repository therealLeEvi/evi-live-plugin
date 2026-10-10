package com.evi.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * The sidebar's Block button (SELF-CONTAINED decision 12): items EVI never suggests BUYING again. The old companion app kept
 * one list for the whole machine; the plugin keeps it, and since the maintainer's decision of 8 Oct 2026 it is PER CHARACTER:
 * <ul>
 *   <li>each RuneScape profile (RuneLite's per-account, per-world-type profile, as {@link SkipMemory} uses) has its own list, so a
 *       block on a main never hides a buy on an alt -- this deliberately differs from the bridge;</li>
 *   <li>permanent until undone with the sidebar's Unblock; only BUYS are refused (a held blocked item is still reminded about --
 *       the engine adds the list to its blocklist after the sell-side tiers, exactly where the bridge adds preferences.json's);</li>
 *   <li>stored sorted, each id a whole number above 0 (setBlocked refuses anything else).</li>
 * </ul>
 * Stored as "561,4151" under the RS-profile key {@value #CONFIG_KEY} in the plugin's group (a NEW keyName; no @ConfigItem). Every
 * call names the profile it means, so a profile change between a lookup and a write can never put one character's block on
 * another. Public only so the in-process tests (another package) can drive the real
 * class. Lenient on read: a malformed entry is dropped rather than failing the list. Thread-safe.
 */
public final class BlockedItems {
  /** The RS-profile config key, inside the "evilive" group. */
  public static final String CONFIG_KEY = "characterBlockedItems";

  /** Where the lists are kept: the RS-profile config in production, a map in a test. */
  public interface Store {
    /** The stored value for that profile, or null/empty for none. */
    String read(String profile);
    /** Replaces the stored value for that profile; an empty string removes it. */
    void write(String profile, String value);
  }

  private final Store store;

  public BlockedItems(Store store) {
    this.store = store;
  }

  /** Blocks one item on one character (server.mjs setBlocked(itemId, true)). False with no profile, an id not above 0, or a failed write. */
  public synchronized boolean block(String profile, int itemId) {
    return change(profile, itemId, true);
  }

  /** Unblocks one item on one character (server.mjs setBlocked(itemId, false)). False with no profile, a bad id or a failed write. */
  public synchronized boolean unblock(String profile, int itemId) {
    return change(profile, itemId, false);
  }

  /** Adds several items at once (the one-time import). False with no profile or a failed write; ids not above 0 are skipped. */
  public synchronized boolean blockAll(String profile, Iterable<Integer> itemIds) {
    if (!known(profile)) return false;
    TreeSet<Integer> all = new TreeSet<>(read(profile));
    for (Integer id : itemIds) if (id != null && id > 0) all.add(id);
    return write(profile, all);
  }

  /** Every item blocked on that character, ascending. Empty with no profile, nothing stored, or an unreadable store. */
  public synchronized List<Integer> all(String profile) {
    if (!known(profile)) return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(read(profile)));
  }

  private boolean change(String profile, int itemId, boolean blocked) {
    if (!known(profile) || itemId <= 0) return false;
    TreeSet<Integer> all = new TreeSet<>(read(profile));
    if (blocked) all.add(itemId);
    else all.remove(itemId);
    return write(profile, all);
  }

  private boolean write(String profile, TreeSet<Integer> all) {
    try {
      store.write(profile, format(all));
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static boolean known(String profile) {
    return profile != null && !profile.isEmpty();
  }

  private TreeSet<Integer> read(String profile) {
    String raw;
    try {
      raw = store.read(profile);
    } catch (RuntimeException e) {
      raw = null;
    }
    return parse(raw);
  }

  static TreeSet<Integer> parse(String raw) {
    TreeSet<Integer> out = new TreeSet<>();
    if (raw == null || raw.isEmpty()) return out;
    for (String part : raw.split(",")) {
      try {
        int id = Integer.parseInt(part.trim());
        if (id > 0) out.add(id);
      } catch (NumberFormatException ignored) {
        // a hand-edited or truncated entry is dropped; it must never stop EVI suggesting at all
      }
    }
    return out;
  }

  static String format(Iterable<Integer> ids) {
    StringBuilder b = new StringBuilder();
    for (Integer id : ids) {
      if (b.length() > 0) b.append(',');
      b.append(id);
    }
    return b.toString();
  }
}
