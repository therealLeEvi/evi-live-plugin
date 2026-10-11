package com.evi.live.inprocess;

import com.evi.live.TestFiles;
import com.evi.live.BlockedItems;
import com.evi.live.engine.RecordedSeries;
import com.evi.live.inprocess.EngineFeed;
import com.evi.live.inprocess.InProcessEngine;
import com.evi.live.inprocess.SuggestionRecords;
import com.evi.live.journal.JournalCodec;
import com.evi.live.journal.JournalRecord;
import com.evi.live.journal.PluginJournal;
import com.evi.live.journal.Store;
import com.evi.live.journal.StoreState;
import com.evi.live.market.HourBucket;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.client.util.Filepath;

/**
 * THE SIDEBAR'S BUTTONS IN IN_PROCESS MODE (SELF-CONTAINED decision 12): every golden transcript in which the real bridge was sent a
 * sidebar button (Block, Personal use on a buy, "Yours, not stock", "I don't have this anymore", Reset, "I took this one"), replayed
 * through the PLUGIN's own stores -- {@link PluginJournal}'s marks, {@link BlockedItems} over a config map, the profit reset in that
 * map, {@link SuggestionRecords} -- and the real {@link InProcessEngine} thread. Where the bridge RESTARTED, the plugin restarts too:
 * a NEW journal, engine, records and block list over the SAME folder and config map (nothing carried in memory). Asserted:
 * <ol>
 *   <li>every poll's body is the bridge's (BodyDiff, the one plugin-only field aside) -- so the engine received the inputs the
 *       bridge fed itself after the same presses, before and after every restart;</li>
 *   <li>THE ID: every suggestion's id is the bridge's id, character for character, and {@code accepted} is the bridge's;</li>
 *   <li>a press the bridge refused (400) is refused by the plugin's store with the bridge's message, and one it took is taken;</li>
 *   <li>after the last step, one more restart leaves the journal's marks (personal use, kept counts, closed positions) as they were.</li>
 * </ol>
 * Flip imports (a scanner action; the plugin has none) are put in place as the account's imported journal file, which the plugin
 * journal replays as it replays any import.
 */
public final class InProcessButtonsTest {
  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  /** Every transcript holding a sidebar POST; the three sidebar-* ones also restart the bridge after each press. */
  static final List<String> REQUIRED = List.of("sidebar-block-persists", "sidebar-accept-persists", "sidebar-marks-persist",
    "version-and-profit-reset", "tier-inventory-kept-count", "tier-inventory-personal-use-blanket", "yours-not-stock-marks",
    "personal-use-buy", "not-held-close");

  public static void main(String[] args) throws Exception {
    int[] totals = new int[5];
    List<String> seen = new ArrayList<>();
    for (String name : EngineRig.index()) {
      JsonObject t = EngineRig.transcript(name);
      if (!hasSidebarPost(t)) continue;
      int[] r = replay(name, t);
      seen.add(name);
      for (int i = 0; i < r.length; i++) totals[i] += r[i];
    }
    for (String r : REQUIRED) check(seen.contains(r), "a transcript with sidebar presses is no longer replayed: " + r);
    check(totals[1] >= 10 && totals[2] >= 8 && totals[3] >= 6 && totals[4] >= 4,
      "too little exercised: " + java.util.Arrays.toString(totals));
    System.out.println("PASS: in-process sidebar buttons (" + checks + " checks) -- " + seen.size() + " transcripts with sidebar presses, "
      + totals[0] + " polls whose body AGREES with the real bridge's after the same presses through the plugin's own stores (" + totals[1]
      + " carrying the bridge's very suggestion id and accepted flag), " + totals[2] + " presses answered as the bridge answered them, "
      + totals[3] + " plugin restarts where the bridge restarted, " + totals[4] + " marks unchanged across a final restart");
  }

  static boolean hasSidebarPost(JsonObject t) {
    for (JsonElement se : t.getAsJsonArray("steps")) {
      JsonObject s = se.getAsJsonObject();
      if (!s.has("request")) continue;
      String p = s.getAsJsonObject("request").get("path").getAsString();
      if (p.startsWith("/api/suggestion/") || p.equals("/api/profit/reset")) return true;
    }
    return false;
  }

  /** The plugin's stores for one plugin lifetime (a restart builds a new one over the same folder and config map). */
  static final class Life {
    PluginJournal journal;
    InProcessEngine engine;
    SuggestionRecords records;
    BlockedItems blocks;
    /** EviLivePlugin.inProcessProfitSince: read from the config at start, set (with the config) by Reset. */
    final java.util.concurrent.atomic.AtomicReference<Long> since = new java.util.concurrent.atomic.AtomicReference<>();
  }

  static Life start(Filepath root, AtomicLong clock, Map<String, String> config, EngineRig.TestMarket tm, Life before, List<String> logged) {
    Life l = new Life();
    l.journal = new PluginJournal(root, clock::get, EngineRig.PARSER, () -> false, logged::add);
    l.journal.start(before == null ? null : before.journal);
    l.records = new SuggestionRecords(root, EngineRig.PARSER, logged::add);
    // per character since 8 Oct 2026: one list per profile (here the account stands for its RuneScape profile)
    l.blocks = new BlockedItems(new BlockedItems.Store() {
      @Override public String read(String profile) {
        return config.get(profile + "." + BlockedItems.CONFIG_KEY);
      }

      @Override public void write(String profile, String value) {
        if (value.isEmpty()) config.remove(profile + "." + BlockedItems.CONFIG_KEY);
        else config.put(profile + "." + BlockedItems.CONFIG_KEY, value);
      }
    });
    String since = config.get("inProcessProfitSince"); // read once at start, as EviLivePlugin.readProfitSince
    l.since.set(since == null ? null : Long.valueOf(since));
    l.engine = new InProcessEngine(root, EngineFeed.journalOf(() -> l.journal),
      InProcessEngineTest.withHistory(tm, InProcessEngine.BASIS_HOURS), EngineRig.PARSER, logged::add, () -> false, l.since::get,
      RecordedSeries.of(id -> tm.body(InProcessEngineTest.API + "timeseries?id=" + id + "&timestep=1h")), l.blocks::all, l.records); // the query's account's list
    return l;
  }

  /** The query without its account parameter: the poll a plugin with no account worked out sends. */
  static String withoutAccount(String query) {
    StringBuilder b = new StringBuilder();
    for (String part : query.split("&")) {
      if (part.startsWith("account=")) continue;
      if (b.length() > 0) b.append('&');
      b.append(part);
    }
    return b.toString();
  }

  /**
   * The REAL plugin's blockedFor (EviLivePlugin, reached by reflection: another package) over these blocks, IN_PROCESS, with
   * {@code last} remembered as the last logged-in character (the rig's profiles are its accounts), or none when null.
   */
  @SuppressWarnings("unchecked")
  static java.util.function.Function<String, java.util.Collection<Integer>> pluginBlocks(BlockedItems blocks, String last) throws Exception {
    com.evi.live.EviLivePlugin p = new com.evi.live.EviLivePlugin();
    java.lang.reflect.Field f = com.evi.live.EviLivePlugin.class.getDeclaredField("blockedItems");
    f.setAccessible(true);
    f.set(p, blocks);
    if (last != null) {
      java.lang.reflect.Method remember = com.evi.live.EviLivePlugin.class.getDeclaredMethod("rememberLastProfile", String.class);
      remember.setAccessible(true);
      remember.invoke(p, last);
    }
    java.lang.reflect.Method m = com.evi.live.EviLivePlugin.class.getDeclaredMethod("blockedFor", String.class);
    m.setAccessible(true);
    return account -> {
      try {
        return (java.util.Collection<Integer>) m.invoke(p, account);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
  }

  /** One query (asked twice, as a cold engine needs) to a side engine over this lifetime's journal and market, with the given block
   *  lists and NO suggestion records (so the replay's own ids are untouched); its suggestion, or null. */
  static JsonElement suggestionFor(Life life, EngineRig.TestMarket tm, Filepath root, List<String> logged,
                                   java.util.function.Function<String, java.util.Collection<Integer>> blocked, String query, long at) throws Exception {
    InProcessEngine side = new InProcessEngine(root, EngineFeed.journalOf(() -> life.journal), InProcessEngineTest.withHistory(tm, InProcessEngine.BASIS_HOURS),
      EngineRig.PARSER, logged::add, () -> false, life.since::get,
      RecordedSeries.of(id -> tm.body(InProcessEngineTest.API + "timeseries?id=" + id + "&timestep=1h")), blocked, null);
    try {
      InProcessEngine.Answer o = null;
      for (int k = 0; k < 2; k++) o = side.request(query, at).get(60, TimeUnit.SECONDS);
      return o == null || o.body == null ? null : EngineRig.j(o.body).getAsJsonObject().get("suggestion");
    } finally {
      side.shutdown();
      check(side.awaitTermination(30_000), "the side engine stopped");
    }
  }

  static void stop(Life l) throws Exception {
    l.engine.shutdown();
    check(l.engine.awaitTermination(30_000), "the engine stopped");
    l.journal.shutdown();
    l.journal.awaitIdle(30_000);
  }

  /** Returns {polls compared, ids compared, presses compared, restarts, marks checked after the final restart}. */
  static int[] replay(String name, JsonObject t) throws Exception {
    int polls = 0, ids = 0, presses = 0, restarts = 0, kept = 0;
    Filepath root = TestFiles.tempDir("evi-buttons-test");
    AtomicLong clock = new AtomicLong();
    List<String> logged = Collections.synchronizedList(new ArrayList<>());
    Map<String, String> config = new ConcurrentHashMap<>();
    JsonObject boot = t.getAsJsonObject("input").getAsJsonObject("boot");
    JsonElement prefs = boot.get("preferences");
    if (prefs != null && !prefs.isJsonNull()) root.joinSegment(InProcessEngine.PREFERENCES).write(prefs.toString().getBytes(StandardCharsets.UTF_8));
    List<HourBucket> archive = null;
    JsonElement a = boot.get("archive1h");
    if (a != null && a.isJsonArray()) {
      archive = new ArrayList<>();
      for (JsonElement b : a.getAsJsonArray()) archive.add(HourBucket.parseLine(b.toString()));
    }
    EngineRig.TestMarket tm = new EngineRig.TestMarket(archive, t.getAsJsonArray("wiki"));
    Life life = start(root, clock, config, tm, null, logged);
    String account = null; // the account the last packet named
    // THE PRESSER: the account of the poll whose pick the button was pressed on (the plugin presses for the account it polls
    // for), else the packets'. One account in every transcript but tier-inventory-personal-use-blanket.
    String presser = null;
    int intended = 0;
    try {
      for (JsonElement se : t.getAsJsonArray("steps")) {
        JsonObject s = se.getAsJsonObject();
        long at = s.get("at").getAsLong();
        String where = name + " step " + s.get("i").getAsInt();
        clock.set(at);
        if (s.has("restart")) {
          stop(life);
          life = start(root, clock, config, tm, life, logged);
          restarts++;
          continue;
        }
        if (s.has("store1h")) {
          List<HourBucket> hours = new ArrayList<>();
          for (JsonElement b : s.getAsJsonArray("store1h")) hours.add(HourBucket.parseLine(b.toString()));
          tm.store(hours);
          continue;
        }
        if (!s.has("request")) continue;
        JsonObject r = s.getAsJsonObject("request");
        String path = r.get("path").getAsString(), method = r.get("method").getAsString();
        JsonObject body = r.has("body") && r.get("body").isJsonObject() ? r.getAsJsonObject("body") : new JsonObject();
        JsonObject response = s.getAsJsonObject("response");
        int status = response.get("status").getAsInt();
        if (method.equals("POST") && path.equals("/api/events")) {
          life.journal.offerPacket(r.get("body").toString());
          if (body.has("account") && body.get("account").isJsonPrimitive()) account = body.get("account").getAsString();
          continue;
        }
        if (method.equals("POST") && path.equals("/api/flips/import")) {
          // the history tier's flips, as an imported file (see the class note); written once, before the next poll reads it
          List<JournalRecord> recs = new ArrayList<>();
          new Store(recs::add).importFlips(body);
          StringBuilder sb = new StringBuilder();
          for (JournalRecord x : recs) sb.append(JournalCodec.encodeLine(x)).append('\n');
          life.journal.awaitIdle(30_000);
          Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
          dir.createDirectories();
          dir.joinSegment("imported-" + account + ".jsonl").write(sb.toString().getBytes(StandardCharsets.UTF_8));
          continue;
        }
        if (method.equals("POST")) {
          String refusal = press(life, path, body, presser != null ? presser : account, at, config);
          String bridgeError = status == 200 ? null : response.getAsJsonObject("body").get("error").getAsString();
          check(java.util.Objects.equals(refusal, bridgeError), where + " " + path + ": the bridge answered " + status + " " + bridgeError
            + ", the plugin's store " + (refusal == null ? "took it" : "refused it: " + refusal));
          presses++;
          continue;
        }
        if (!path.equals("/api/suggestion")) continue;
        tm.at = at;
        presser = queryAccount(r.get("query").getAsString(), presser);
        JsonObject bridge = response.getAsJsonObject("body");
        int repeat = s.has("repeat") ? s.get("repeat").getAsInt() : 1;
        InProcessEngine.Answer ans = null;
        // as the recorder did: the cold first issue (its own query when it differed), then the recorded one
        for (int k = 0; k < repeat; k++) {
          String q = k < repeat - 1 && s.has("warmupQuery") ? s.get("warmupQuery").getAsString() : r.get("query").getAsString();
          ans = life.engine.request(q, at).get(60, TimeUnit.SECONDS);
        }
        check(ans != null && ans.body != null, where + ": no body (" + (ans == null ? "replaced" : ans.unavailable) + ")");
        JsonObject plugin = EngineRig.j(ans.body).getAsJsonObject();
        if (name.equals("tier-inventory-personal-use-blanket") && s.has("label") && s.get("label").getAsString().equals("after-mark")) {
          // THE ONE INTENDED DIFFERENCE (8 Oct 2026). Two accounts' bags were read in the minute before the press and the bridge's
          // request names no presser, so it stores the BLANKET mark and the whip is never offered again. The plugin always names
          // its own account (acct-a, whose pick was on screen), so its mark keeps acct-a's count, 2, and the third whip -- the
          // surplus -- is offered. Asserted as the per-account result, not compared with the bridge.
          JsonElement ps0 = plugin.get("suggestion");
          check(bridge.get("suggestion") == null || bridge.get("suggestion").isJsonNull(), where + ": the bridge's blanket mark hides the whip");
          check(ps0 != null && ps0.isJsonObject() && ps0.getAsJsonObject().get("itemId").getAsInt() == 4151
            && ps0.getAsJsonObject().get("quantity").getAsInt() == 1 && "sell".equals(ps0.getAsJsonObject().get("action").getAsString())
            && "inventory".equals(ps0.getAsJsonObject().get("source").getAsString()),
            where + ": the plugin's mark counts the presser's 2, so the surplus whip is offered: " + ps0);
          intended++;
          polls++;
          continue;
        }
        if (name.equals("sidebar-block-persists") && s.has("label") && s.get("label").getAsString().equals("after-block")) {
          // PER CHARACTER (8 Oct 2026), asserted explicitly: the bridge's block is one for the machine, the plugin's is acct-a's own.
          // The very same poll for ANOTHER account still gets the whip (the shared history tier's pick before the block).
          String other = r.get("query").getAsString().replace("account=" + presser, "account=acct-other");
          check(!other.equals(r.get("query").getAsString()), where + ": the poll names its account");
          InProcessEngine.Answer o = null;
          for (int k = 0; k < 2; k++) o = life.engine.request(other, at).get(60, TimeUnit.SECONDS);
          JsonElement os = o == null || o.body == null ? null : EngineRig.j(o.body).getAsJsonObject().get("suggestion");
          check(os != null && os.isJsonObject() && os.getAsJsonObject().get("itemId").getAsInt() == 4151,
            where + ": another character is not blocked by acct-a's press: " + os);
          intended++;
          // BEFORE LOGIN (the maintainer, 8 Oct 2026): the same poll naming NO account, answered by an engine handed the REAL plugin's
          // blockedFor over this config. With the presser as the last logged-in character the whip stays blocked; a plugin with no
          // last character offers it (no list, as before the change).
          String none = withoutAccount(r.get("query").getAsString());
          check(!none.contains("account="), where + ": the query no longer names an account: " + none);
          JsonElement lastKnown = suggestionFor(life, tm, root, logged, pluginBlocks(life.blocks, presser), none, at);
          check(lastKnown == null || lastKnown.isJsonNull() || lastKnown.getAsJsonObject().get("itemId").getAsInt() != 4151,
            where + ": before login, the last character's block holds: " + lastKnown);
          JsonElement neverKnown = suggestionFor(life, tm, root, logged, pluginBlocks(life.blocks, null), none, at);
          check(neverKnown != null && neverKnown.isJsonObject() && neverKnown.getAsJsonObject().get("itemId").getAsInt() == 4151,
            where + ": no character ever logged in: no list, the whip is offered: " + neverKnown);
          intended++;
          // the press's own account, asked again AFTER the other one, is still blocked (the list is read per query)
          ans = life.engine.request(r.get("query").getAsString(), at).get(60, TimeUnit.SECONDS);
          plugin = EngineRig.j(ans.body).getAsJsonObject();
        }
        List<BodyDiff.Difference> d = BodyDiff.diff(bridge, InProcessEngineTest.without(plugin, "sellBreakEven"));
        check(d.isEmpty(), where + ": the in-process body differs from the bridge's: " + d.subList(0, Math.min(3, d.size())));
        polls++;
        JsonElement bs = bridge.get("suggestion"), ps = plugin.get("suggestion");
        if (bs != null && bs.isJsonObject()) {
          JsonObject b = bs.getAsJsonObject(), p = ps.getAsJsonObject();
          check(b.has("id") && p.has("id") && b.get("id").getAsString().equals(p.get("id").getAsString()),
            where + ": the suggestion id is not the bridge's: bridge " + b.get("id") + ", plugin " + p.get("id"));
          check(b.get("accepted").getAsBoolean() == p.get("accepted").getAsBoolean(),
            where + ": accepted is not the bridge's: bridge " + b.get("accepted") + ", plugin " + p.get("accepted"));
          ids++;
        }
      }
      // one more restart: the journal's marks read back as they were
      StoreState before = state(life, clock.get());
      stop(life);
      life = start(root, clock, config, tm, life, logged);
      StoreState after = state(life, clock.get());
      check(before.personalUseBuyIds.equals(after.personalUseBuyIds), name + ": personal-use buys changed across a restart");
      check(before.personalUseItemIds.equals(after.personalUseItemIds) && before.personalUseItems.equals(after.personalUseItems)
        && before.personalUseKept.equals(after.personalUseKept), name + ": item marks or kept counts changed across a restart");
      check(before.closedPositions.size() == after.closedPositions.size() && before.autoOpenPositions.size() == after.autoOpenPositions.size(),
        name + ": closed or open positions changed across a restart");
      kept++;
      check(intended == (name.equals("sidebar-block-persists") ? 2 : name.equals("tier-inventory-personal-use-blanket") ? 1 : 0),
        name + ": the intended per-account difference was met " + intended + " times");
      for (String l : logged) check(!l.contains("could not be saved") && !l.contains("could not be logged"), name + ": " + l);
    } finally {
      stop(life);
      root.deleteRecursively();
    }
    return new int[]{polls, ids, presses, restarts, kept};
  }

  /** The account a recorded poll query names (account=...), else {@code fallback}. */
  static String queryAccount(String query, String fallback) {
    for (String part : query.split("&")) if (part.startsWith("account=") && part.length() > 8) return part.substring(8);
    return fallback;
  }

  static StoreState state(Life l, long now) throws Exception {
    PluginJournal.EngineView v = l.journal.engineView(now).get(30, TimeUnit.SECONDS);
    check(v != null, "the journal answered");
    return v.state;
  }

  /**
   * One sidebar press, through the plugin's own store for it -- what EviLivePlugin does in IN_PROCESS mode -- with the body the
   * plugin would have sent the bridge. Returns null when taken, else the refusal.
   */
  static String press(Life l, String path, JsonObject body, String account, long at, Map<String, String> config) throws Exception {
    switch (path) {
      case "/api/suggestion/block":
        return l.blocks.block(account, body.get("itemId").getAsInt()) ? null : "refused"; // the presser's character
      case "/api/profit/reset":
        l.since.set(at); // EviLivePlugin.resetProfit: the field the engine reads, and the config write
        config.put("inProcessProfitSince", String.valueOf(at));
        return null;
      case "/api/suggestion/accept":
        return l.records.accept(body.get("id").getAsString(), account, body.get("itemId").getAsInt(), body.get("accepted").getAsBoolean(), at)
          ? null : "refused";
      case "/api/suggestion/not-held":
        return l.journal.closePosition(account, body.get("buyId").getAsString()).get(30, TimeUnit.SECONDS);
      case "/api/suggestion/personal-use":
        if (body.has("buyId") && !body.get("buyId").isJsonNull())
          return l.journal.markPersonalUse(account, body.get("buyId").getAsString()).get(30, TimeUnit.SECONDS);
        int itemId = body.get("itemId").getAsInt();
        // the plugin names the presser (its own account); the bridge, given no account, finds the one bag read in the last
        // minute (the 8 Oct fix) -- the same bag in every replayed transcript, which the bodies and the marks prove
        return l.journal.markPersonalUseItem(account, itemId, l.engine.keptFor(account, itemId, at)).get(30, TimeUnit.SECONDS);
      default:
        throw new IllegalStateException("not a sidebar press: " + path);
    }
  }
}
