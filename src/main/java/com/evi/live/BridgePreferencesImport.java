package com.evi.live;

import com.evi.live.journal.PluginJournal;
import com.google.gson.JsonElement;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.runelite.client.util.Filepath;

/**
 * The one-time, opt-in import of the old companion app's preferences (the maintainer's decision of 8 Oct 2026), beside the
 * trade-history import and under the SAME setting ("Import old trade history"). The player copies the old app's
 * data/preferences.json to {@code import/preferences.json} in the plugin's data folder; nothing else is read, and nothing is ever
 * fetched.
 * <ul>
 *   <li><b>Blocked items</b> ({@code blocked}, read exactly as the bridge reads it): the bridge's list is one for the machine,
 *       the plugin's is per character, so the list is added to EACH character's own list the first time that character finds
 *       the file -- once per character, recorded on the character ({@value #DONE_KEY}, the time it ran) whether or not the file
 *       held any block. A later Unblock is never undone by a second import.</li>
 *   <li><b>The profit line's reset time</b> ({@code profitSince}): global in both, so it is taken ONCE, and only when the plugin has
 *       no reset of its own (a reset pressed in the plugin is never overwritten). Recorded globally ({@value #PROFIT_DONE_KEY})
 *       either way, so it is looked at once.</li>
 * </ul>
 * Spread, focus and capture cap are not copied: they are not player choices in the plugin (the plugin's own preferences.json, if
 * any, is read as it was). A missing file is looked for again at the next poll; a file that cannot be read is logged once per
 * character a session and NOT recorded as imported, so a corrected copy still imports. Runs on the plugin's poll thread, never the
 * client thread or the Swing thread. Package-private.
 */
final class BridgePreferencesImport {
  /** The file the player places in {@code import/} (a copy of the bridge's data/preferences.json). */
  static final String FILE = "preferences.json";
  /** RS-profile key: when this character took the bridge's blocks (epoch ms). Present = never again. */
  static final String DONE_KEY = "bridgeBlocksImported";
  /** Global key: when the bridge's profit reset time was looked at (epoch ms). Present = never again. */
  static final String PROFIT_DONE_KEY = "bridgeProfitSinceImported";

  /** The config this needs: a profile-scoped read/write and a global one (profile null). */
  interface Config {
    String get(String profile, String key);

    void set(String profile, String key, String value);
  }

  /** What one run did (for the log and the tests). */
  static final class Result {
    /** Blocks added to this character (0 when the file held none or they were all there already). */
    final int blocksAdded;
    /** The reset time taken from the bridge, or null when none was taken. */
    final Long profitSince;

    Result(int blocksAdded, Long profitSince) {
      this.blocksAdded = blocksAdded;
      this.profitSince = profitSince;
    }
  }

  private BridgePreferencesImport() {}

  /** server.mjs readPreferences: {@code Array.isArray(p.blocked) ? p.blocked.filter(id => Number.isSafeInteger(id) && id > 0) : []},
   *  and (the plugin's lists hold ints) at most Integer.MAX_VALUE. */
  static List<Integer> blocked(JsonElement b) {
    List<Integer> out = new java.util.ArrayList<>();
    if (b == null || !b.isJsonArray()) return out;
    for (JsonElement x : b.getAsJsonArray()) {
      if (x == null || !x.isJsonPrimitive() || !x.getAsJsonPrimitive().isNumber()) continue;
      double v = x.getAsDouble();
      if (v == Math.rint(v) && v > 0 && v <= Integer.MAX_VALUE) out.add((int) v);
    }
    return out;
  }

  /**
   * One poll's worth: does nothing (null) when the setting is off, no character is known, this character already imported, or
   * the file is absent or unreadable. {@code ownProfitSince}: the plugin's own reset time (null = never reset).
   */
  static Result run(boolean enabled, Filepath pluginDir, String profile, BlockedItems blocks, Config config, Long ownProfitSince,
                    Function<String, JsonElement> parser, long now, Consumer<String> log, java.util.Set<String> loggedOnce) {
    if (!enabled || profile == null || profile.isEmpty() || pluginDir == null) return null;
    if (config.get(profile, DONE_KEY) != null) return null;
    Filepath f = pluginDir.joinSegment(PluginJournal.IMPORT_DIR).joinSegment(FILE);
    if (!f.isFile()) return null;
    JsonElement el;
    try (InputStream in = f.openInputStream()) {
      el = parser.apply(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      if (el == null || !el.isJsonObject()) throw new IllegalArgumentException("not a JSON object");
    } catch (Exception e) {
      if (loggedOnce.add(profile))
        log.accept("EVI: " + PluginJournal.IMPORT_DIR + "/" + FILE + " could not be read, so no blocked items were imported (" + e.getClass().getSimpleName() + ")");
      return null;
    }
    List<Integer> bridgeBlocks = blocked(el.getAsJsonObject().get("blocked"));
    int before = blocks.all(profile).size();
    if (!bridgeBlocks.isEmpty() && !blocks.blockAll(profile, bridgeBlocks)) return null; // not saved: tried again next poll
    int added = blocks.all(profile).size() - before;
    config.set(profile, DONE_KEY, String.valueOf(now));
    Long since = null;
    if (config.get(null, PROFIT_DONE_KEY) == null) {
      JsonElement ps = el.getAsJsonObject().get("profitSince");
      boolean finite = ps != null && ps.isJsonPrimitive() && ps.getAsJsonPrimitive().isNumber() && Double.isFinite(ps.getAsDouble());
      if (ownProfitSince == null && finite) {
        since = (long) Math.floor(ps.getAsDouble());
        config.set(null, EviLivePlugin.PROFIT_SINCE_KEY, String.valueOf(since)); // where the plugin keeps its own reset
      }
      config.set(null, PROFIT_DONE_KEY, String.valueOf(now));
    }
    log.accept("EVI: imported " + added + " blocked item" + (added == 1 ? "" : "s") + " from " + PluginJournal.IMPORT_DIR + "/" + FILE
      + " for this character" + (since == null ? "" : ", and the profit line's reset time"));
    return new Result(added, since);
  }
}
