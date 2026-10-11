package com.evi.live;

import com.evi.live.engine.Pick;
import com.evi.live.inprocess.EngineFeed;
import com.evi.live.inprocess.InProcessEngine;
import com.evi.live.inprocess.SuggestionOutcomes;
import com.evi.live.inprocess.SuggestionRecords;
import com.evi.live.journal.Offer;
import com.evi.live.journal.PluginJournal;
import com.evi.live.journal.StoreState;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.WikiJson;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Filepath;

/**
 * THE SIDEBAR'S STORES (SELF-CONTAINED decision 12) -- what each button keeps, where, for whom and for how long. The transcript
 * replay (inprocess/InProcessButtonsTest) proves the engine is fed what the recorded companion app fed itself; this proves the
 * pieces and the plugin's own wiring:
 * <ol>
 *   <li>THE ID: the plugin's ids are the bridge's (bridge/suggestionLog.mjs record, recorded from the JS: repeats, the 30-minute
 *       memory, accounts, the demoted picks in the key, the 1296 wrap);</li>
 *   <li>"I took this one": newest record wins, read back by a NEW instance over the same folder, nothing without an id;</li>
 *   <li>THE OUTCOME JOIN against the JS (bridge/suggestionOutcomes.mjs): an exact price and quantity is MATCHED, one gp either way
 *       is not; an acceptance is OBSERVED at whatever price, and taken with no offer;</li>
 *   <li>the block list, PER CHARACTER since 8 Oct 2026 (sorted, lenient, refuses what setBlocked refuses, one list per profile,
 *       Unblock), and the kept-count reading (the presser's own bag, else a poll naming no account, else the ONE account read in
 *       the last minute -- the bridge's 8 Oct fix -- under a minute old);</li>
 *   <li>the one-time import of the bridge's preferences.json: blocks once per character, never again after an Unblock; the
 *       profit reset once, never over the plugin's own;</li>
 *   <li>THE PLUGIN, through a real RuneLite ConfigManager and a real plugin journal: Block is PER CHARACTER (on the presser's
 *       RuneScape profile; another profile does not see it; a NEW plugin instance sees it once the account is worked out; the
 *       engine is handed each account's own list; before login the LAST logged-in character's list
 *       applies, kept across a restart, with the list hidden and Unblock writing nothing), the Blocked items list and its Unblock,
 *       the import through the plugin, Reset survives a new plugin instance, Personal use and
 *       Gone land in the journal of the account that owns the buy and survive a journal restart while another account's position
 *       of the same item stays open, "Yours, not stock" with no account known saves nothing and says so, Took it is recorded and
 *       read back; no "not saved in this mode yet" wording anywhere, every sentence ASCII;</li>
 *   <li>the holding lines' Personal use / Gone: the card's presses for the line's lot.</li>
 * </ol>
 * (The folder comes from TestFiles.tempDir; every file here is read and written through Filepath.)
 */
public final class EviLiveInProcessStoresTest {
  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  static final Function<String, JsonElement> PARSER = s -> new JsonParser().parse(s);

  public static void main(String[] args) throws Exception {
    ids();
    acceptances();
    outcomes();
    blockList();
    keptCount();
    preferencesImport();
    loginRecordsProfile();
    loginTellsCharacterName();
    pluginInProcess();
    holdingLines();
    System.out.println("PASS: in-process sidebar stores (" + checks + " checks) -- the holding lines' Personal use / Gone: the card's presses for the line's lot"
      + " (the owner's journal), no lot on any other note; suggestion ids identical to the JS log's (repeats, accounts,"
      + " demoted picks, the wrap); acceptances newest-wins and read back after a restart; the outcome join identical to the JS (exact price"
      + " matched, +-1 gp not); the block list per character; the kept count; the bridge-preferences import; the plugin: Block per character and handed to the engine, Unblock, Reset, Personal use and"
      + " Gone in the owner's journal across a restart and not another account's, Took it saved");
  }

  static JsonElement resource(String path) throws Exception {
    InputStream raw = EviLiveInProcessStoresTest.class.getResourceAsStream(path);
    check(raw != null, "missing test resource " + path);
    InputStream in = path.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
    try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return new JsonParser().parse(r);
    }
  }

  static Filepath temp(String prefix) throws Exception {
    return TestFiles.tempDir(prefix);
  }

  // ------------------------------------------------------------------------------------------- 1. the id

  static Pick pick(JsonObject s) {
    return new Pick(s.get("itemId").getAsInt(), null, s.get("action").getAsString(), s.get("quantity").getAsLong(), s.get("buyPrice").getAsLong(),
      s.get("sellPrice").getAsLong(), s.has("source") ? s.get("source").getAsString() : null);
  }

  static List<Pick> demoted(JsonArray ids) {
    List<Pick> out = new ArrayList<>();
    if (ids != null) for (JsonElement e : ids) out.add(new Pick(e.getAsInt(), null, "buy", 1, 1, 1, "market"));
    return out;
  }

  static void ids() throws Exception {
    JsonObject v = resource("/inprocess/suggestion-log-ids.json").getAsJsonObject();
    JsonArray expected = v.getAsJsonArray("ids");
    Filepath root = temp("evi-ids");
    try {
      SuggestionRecords r = new SuggestionRecords(root, PARSER, m -> { });
      List<String> got = new ArrayList<>();
      for (JsonElement e : v.getAsJsonArray("ops")) {
        JsonObject o = e.getAsJsonObject();
        String account = o.get("account").isJsonNull() ? null : o.get("account").getAsString();
        got.add(r.record(account, pick(o.getAsJsonObject("s")), demoted(o.getAsJsonArray("demoted")), o.get("now").getAsLong()));
      }
      JsonObject bulk = v.getAsJsonObject("bulk");
      JsonObject s1 = v.getAsJsonArray("ops").get(0).getAsJsonObject().getAsJsonObject("s");
      for (int k = 0; k < bulk.get("count").getAsInt(); k++) {
        Pick p = new Pick(s1.get("itemId").getAsInt(), null, "buy", k + 1, s1.get("buyPrice").getAsLong(), s1.get("sellPrice").getAsLong(), "personal");
        got.add(r.record(bulk.get("account").getAsString(), p, Collections.emptyList(), bulk.get("from").getAsLong() + k));
      }
      check(got.size() == expected.size(), "id count " + got.size() + " vs " + expected.size());
      for (int i = 0; i < got.size(); i++)
        check(expected.get(i).getAsString().equals(got.get(i)), "id " + i + ": JS " + expected.get(i).getAsString() + ", plugin " + got.get(i));
      // one log line per id issued (repeats are not written), under suggestions/ only
      Set<String> distinct = new HashSet<>(got);
      check(r.logged().size() == distinct.size(), "one log line per new id: " + r.logged().size() + " vs " + distinct.size());
      JsonObject first = r.logged().get(0);
      check(first.get("id").getAsString().equals(got.get(0)) && first.get("buyPrice").getAsLong() == 200 && first.get("quantity").getAsLong() == 2000
        && "buy".equals(first.get("action").getAsString()) && first.get("ts").getAsLong() == v.getAsJsonArray("ops").get(0).getAsJsonObject().get("now").getAsLong(),
        "the log line carries what the outcome join reads: " + first);
      check(root.joinSegment(SuggestionRecords.DIR).joinSegment(SuggestionRecords.LOG).isFile(), "the log is suggestions/suggestion-log.jsonl");
      // a NEW instance (a plugin restart) starts the repeat memory and the counter afresh, as a bridge restart does
      SuggestionRecords again = new SuggestionRecords(root, PARSER, m -> { });
      long now = v.getAsJsonArray("ops").get(0).getAsJsonObject().get("now").getAsLong();
      String id = again.record("acct-a", pick(s1), Collections.emptyList(), now + 1000);
      check(id.endsWith("-561-00") && !id.equals(got.get(0)), "after a restart the same pick is a new entry from 00: " + id);
      // a log that cannot be written: no id, and the button is not offered (the bridge's written:false)
      Filepath blocked = temp("evi-ids-ro");
      blocked.joinSegment(SuggestionRecords.DIR).write(new byte[0]); // a FILE where the folder should be
      List<String> said = new ArrayList<>();
      SuggestionRecords broken = new SuggestionRecords(blocked, PARSER, said::add);
      check(broken.record("acct-a", pick(s1), null, now) == null && said.size() == 1, "an unwritable log gives no id, and says so: " + said);
      check(broken.record("acct-a", pick(s1), null, now + 1) != null, "...and, as suggestionLog.mjs, the next repeat returns the id it remembered");
      blocked.deleteRecursively();
    } finally {
      root.deleteRecursively();
    }
  }

  // ------------------------------------------------------------------------------------------- 2. acceptances

  static void acceptances() throws Exception {
    Filepath root = temp("evi-accept");
    try {
      SuggestionRecords r = new SuggestionRecords(root, PARSER, m -> { });
      check(!r.wasAccepted("x-1-00") && !r.wasAccepted(null) && !r.wasAccepted(""), "nothing accepted yet");
      check(!r.accept(null, "acct-a", 1, true, 1) && !r.accept("", "acct-a", 1, true, 1), "an acceptance needs an id (acceptances.mjs)");
      check(r.accept("a-561-00", "acct-a", 561, true, 10) && r.wasAccepted("a-561-00"), "accepted");
      check(r.accept("b-565-01", "acct-a", 565, true, 11) && r.accept("b-565-01", "acct-a", 565, false, 12) && !r.wasAccepted("b-565-01"),
        "taken back: the newest record wins");
      SuggestionRecords after = new SuggestionRecords(root, PARSER, m -> { });
      check(after.wasAccepted("a-561-00") && !after.wasAccepted("b-565-01"), "a NEW instance over the same folder reads them back");
      Filepath file = root.joinSegment(SuggestionRecords.DIR).joinSegment(SuggestionRecords.ACCEPTED);
      String text = TestFiles.text(file);
      String[] lines = text.split("\n");
      check(lines.length == 3, "one append-only line per press: " + lines.length);
      JsonObject l0 = PARSER.apply(lines[0]).getAsJsonObject();
      check(l0.keySet().equals(new java.util.LinkedHashSet<>(List.of("id", "account", "itemId", "accepted", "at")))
        && l0.get("at").getAsLong() == 10 && l0.get("itemId").getAsInt() == 561, "acceptances.mjs's record shape: " + lines[0]);
      // a damaged line is skipped, the rest still read
      file.write(("{broken\n" + text).getBytes(StandardCharsets.UTF_8));
      check(new SuggestionRecords(root, PARSER, m -> { }).wasAccepted("a-561-00"), "a damaged line does not lose the others");
    } finally {
      root.deleteRecursively();
    }
  }

  // ------------------------------------------------------------------------------------------- 3. the outcome join

  static Offer offer(JsonObject o) {
    return new Offer(0, o.get("state").getAsString(), o.get("offerId").getAsString(), o.get("itemId").getAsInt(), null, o.get("price").getAsLong(),
      o.get("total").getAsLong(), o.get("filled").getAsLong(), o.get("spent").getAsLong(), o.get("knownStart").getAsBoolean(), o.get("ticksToFill").getAsInt(),
      "acct-a", "s", o.get("firstSeen").getAsLong(), o.get("firstSeen").getAsLong(), o.get("completedAt").isJsonNull() ? null : o.get("completedAt").getAsLong(), null);
  }

  static boolean same(JsonElement js, Object java) {
    if (js == null || js.isJsonNull()) return java == null || (java instanceof Double && ((Double) java).isNaN());
    if (java == null) return false;
    if (js.getAsJsonPrimitive().isBoolean()) return java.equals(js.getAsBoolean());
    if (js.getAsJsonPrimitive().isString()) return java.equals(js.getAsString());
    return ((Number) java).doubleValue() == js.getAsDouble();
  }

  static void outcomes() throws Exception {
    int matched = 0, priceOff = 0, observed = 0;
    for (JsonElement ce : resource("/inprocess/outcome-join-cases.json").getAsJsonArray()) {
      JsonObject c = ce.getAsJsonObject();
      String name = c.get("name").getAsString();
      List<JsonObject> s = new ArrayList<>();
      for (JsonElement e : c.getAsJsonArray("s")) s.add(e.getAsJsonObject());
      List<Offer> offers = new ArrayList<>();
      for (JsonElement e : c.getAsJsonArray("o")) offers.add(offer(e.getAsJsonObject()));
      List<SuggestionOutcomes.Flip> flips = new ArrayList<>();
      if (c.has("flips"))
        for (JsonElement e : c.getAsJsonArray("flips")) {
          JsonObject f = e.getAsJsonObject();
          flips.add(new SuggestionOutcomes.Flip(f.get("buyId").getAsString(), f.get("profit").getAsLong(), f.get("firstBuy").getAsLong(), f.get("lastSell").getAsLong()));
        }
      Set<String> acc = new HashSet<>();
      for (JsonElement e : c.getAsJsonArray("acc")) acc.add(e.getAsString());
      List<SuggestionOutcomes.Row> rows = SuggestionOutcomes.join(s, offers, flips, SuggestionOutcomes.TAKEN_WINDOW_MS, acc::contains);
      JsonArray want = c.getAsJsonArray("rows");
      check(rows.size() == want.size(), name + ": rows " + rows.size() + " vs JS " + want.size());
      for (int i = 0; i < rows.size(); i++) {
        JsonObject w = want.get(i).getAsJsonObject();
        SuggestionOutcomes.Row r = rows.get(i);
        String at = name + " row " + i;
        check(r.taken == w.get("taken").getAsBoolean(), at + ": taken");
        check(same(w.get("attribution"), r.attribution), at + ": attribution JS " + w.get("attribution") + ", plugin " + r.attribution);
        check(same(w.get("noOfferFound"), r.noOfferFound), at + ": noOfferFound");
        check(same(w.get("offerId"), r.offerId), at + ": offerId JS " + w.get("offerId") + ", plugin " + r.offerId);
        check(same(w.get("actualPrice"), r.actualPrice) && same(w.get("filledFully"), r.filledFully) && same(w.get("stillOpen"), r.stillOpen),
          at + ": the offer's figures");
        check(same(w.get("minutesToComplete"), r.minutesToComplete) && same(w.get("profit"), r.profit) && same(w.get("soldWithinHours"), r.soldWithinHours),
          at + ": completion and flip figures");
        if ("matched".equals(r.attribution)) matched++;
        if ("observed".equals(r.attribution)) observed++;
      }
      if (name.startsWith("one gp")) priceOff++;
    }
    check(matched >= 4 && observed >= 3 && priceOff == 2, "the join cases still cover exact / off-by-one / observed: " + matched + " " + priceOff + " " + observed);
    // and as intent, independent of the recorded cases: the exact price is matched, price + 1 is not
    long t = 1_789_000_000_000L;
    JsonObject sugg = PARSER.apply("{\"id\":\"x\",\"ts\":" + t + ",\"action\":\"buy\",\"itemId\":561,\"quantity\":1000,\"buyPrice\":205,\"sellPrice\":214}").getAsJsonObject();
    for (int delta = -1; delta <= 1; delta++) {
      Offer o = new Offer(0, "BOUGHT", "o", 561, null, 205 + delta, 1000, 1000, (205 + delta) * 1000L, true, 30, "acct-a", "s", t + 1, t + 1, t + 2, null);
      String a = SuggestionOutcomes.join(List.of(sugg), List.of(o), List.of(), SuggestionOutcomes.TAKEN_WINDOW_MS, id -> false).get(0).attribution;
      check(delta == 0 ? "matched".equals(a) : "inferred".equals(a), "a buy at " + (205 + delta) + " against 205 suggested: " + a);
      String obs = SuggestionOutcomes.join(List.of(sugg), List.of(o), List.of(), SuggestionOutcomes.TAKEN_WINDOW_MS, "x"::equals).get(0).attribution;
      check("observed".equals(obs), "an accepted suggestion is observed at " + (205 + delta) + ": " + obs);
    }
  }

  // ------------------------------------------------------------------------------------------- 4. block list, kept count

  /** A per-profile store over one map ("profile.key" -> value), as RuneLite keys RS-profile config. */
  static final class MapStore implements BlockedItems.Store {
    final Map<String, String> map;

    MapStore(Map<String, String> map) {
      this.map = map;
    }

    @Override public String read(String profile) {
      return map.get(profile + "." + BlockedItems.CONFIG_KEY);
    }

    @Override public void write(String profile, String value) {
      if (value.isEmpty()) map.remove(profile + "." + BlockedItems.CONFIG_KEY);
      else map.put(profile + "." + BlockedItems.CONFIG_KEY, value);
    }
  }

  static void blockList() {
    Map<String, String> m = new TreeMap<>();
    BlockedItems b = new BlockedItems(new MapStore(m));
    check(b.all("p-a").isEmpty(), "nothing blocked");
    check(b.block("p-a", 4151) && b.block("p-a", 561) && b.block("p-a", 561), "blocks taken (a repeat is no change)");
    check("561,4151".equals(m.get("p-a." + BlockedItems.CONFIG_KEY)), "stored sorted, once each, on that profile: " + m);
    check(!b.block("p-a", 0) && !b.block("p-a", -5), "setBlocked refuses an id that is not above 0");
    // PER CHARACTER (8 Oct 2026): another profile has its own list, and a press with no profile saves nothing
    check(b.all("p-b").isEmpty(), "another character sees none of p-a's blocks");
    check(b.block("p-b", 565) && b.all("p-b").equals(List.of(565)) && b.all("p-a").equals(List.of(561, 4151)), "each character its own list");
    check(!b.block(null, 2) && !b.block("", 2) && b.all(null).isEmpty() && !b.unblock(null, 561), "no profile: nothing saved, nothing read");
    check(new BlockedItems(new MapStore(m)).all("p-a").equals(List.of(561, 4151)), "a NEW instance over the same store reads it back");
    // UNBLOCK: off the list, on that character only; the last one removes the key
    check(b.unblock("p-a", 4151) && b.all("p-a").equals(List.of(561)) && b.all("p-b").equals(List.of(565)), "unblocked on p-a only");
    check(b.unblock("p-a", 4151) && b.all("p-a").equals(List.of(561)), "unblocking what is not blocked: no change");
    check(b.unblock("p-a", 561) && !m.containsKey("p-a." + BlockedItems.CONFIG_KEY), "the last unblock removes the key: " + m);
    check(b.blockAll("p-a", java.util.Arrays.asList(7, null, -1, 4151, 7)) && b.all("p-a").equals(List.of(7, 4151)), "blockAll: whole ids above 0, once");
    m.put("p-a." + BlockedItems.CONFIG_KEY, "565, x,,-3,4151,99999999999,561");
    check(new BlockedItems(new MapStore(m)).all("p-a").equals(List.of(561, 565, 4151)), "lenient: a damaged entry is dropped, never the list");
    BlockedItems failing = new BlockedItems(new BlockedItems.Store() {
      @Override public String read(String profile) {
        throw new IllegalStateException("unreadable");
      }

      @Override public void write(String profile, String value) {
        throw new IllegalStateException("unwritable");
      }
    });
    check(failing.all("p-a").isEmpty() && !failing.block("p-a", 561) && !failing.unblock("p-a", 561),
      "a store that fails: nothing blocked, and the press says it was not saved");
    // the engine's merge: the preferences file's list and the plugin's, as one sorted set; nothing else changes
    com.evi.live.engine.Engine.Prefs p = new com.evi.live.engine.Engine.Prefs("gear", List.of(4151, 2), 3, true);
    com.evi.live.engine.Engine.Prefs merged = EngineFeed.withBlocked(p, List.of(561, 2));
    check(merged.blocked.equals(List.of(2, 561, 4151)) && "gear".equals(merged.focus) && merged.spread == 3 && merged.captureCap,
      "merged into the preferences: " + merged.blocked);
    check(EngineFeed.withBlocked(p, List.of()) == p && EngineFeed.withBlocked(p, null) == p, "nothing blocked by the plugin: the preferences as read");
  }

  /** A config map for BridgePreferencesImport: "profile.key", or the bare key for the global group. */
  static BridgePreferencesImport.Config configOf(Map<String, String> m) {
    return new BridgePreferencesImport.Config() {
      @Override public String get(String profile, String key) {
        return m.get(profile == null ? key : profile + "." + key);
      }

      @Override public void set(String profile, String key, String value) {
        m.put(profile == null ? key : profile + "." + key, value);
      }
    };
  }

  static void writePrefs(Filepath root, String text) throws Exception {
    Filepath d = root.joinSegment(com.evi.live.journal.PluginJournal.IMPORT_DIR);
    d.createDirectories();
    d.joinSegment(BridgePreferencesImport.FILE).write(text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * THE ONE-TIME IMPORT of the bridge's preferences.json (8 Oct 2026): its blocked items go to EACH character once (the bridge's
   * list is one for the machine), never again after an Unblock; its profit reset time is taken once, globally, and never over the
   * plugin's own reset. Off, without a character, without the file, or with an unreadable file: nothing, and no record of an import.
   */
  static void preferencesImport() throws Exception {
    Filepath root = temp("evi-prefs-import");
    Map<String, String> m = new TreeMap<>();
    BlockedItems b = new BlockedItems(new MapStore(m));
    BridgePreferencesImport.Config c = configOf(m);
    List<String> said = new ArrayList<>();
    java.util.Set<String> once = new java.util.HashSet<>();
    try {
      check(BridgePreferencesImport.run(true, root, "p-a", b, c, null, PARSER, 100, said::add, once) == null && m.isEmpty(), "no file: nothing, and no record");
      writePrefs(root, "{\"focus\":\"gear\",\"blocked\":[4151,561,-1,\"x\",2.5,561],\"profitSince\":1789000000000,\"spread\":3}");
      check(BridgePreferencesImport.run(false, root, "p-a", b, c, null, PARSER, 100, said::add, once) == null && m.isEmpty(), "the setting off: nothing read");
      check(BridgePreferencesImport.run(true, root, null, b, c, null, PARSER, 100, said::add, once) == null && m.isEmpty(), "no character: nothing");
      b.block("p-a", 2);
      BridgePreferencesImport.Result r = BridgePreferencesImport.run(true, root, "p-a", b, c, null, PARSER, 100, said::add, once);
      check(r != null && r.blocksAdded == 2 && b.all("p-a").equals(List.of(2, 561, 4151)), "the bridge's blocks (its own filter) join p-a's own: " + b.all("p-a"));
      check("100".equals(m.get("p-a." + BridgePreferencesImport.DONE_KEY)), "recorded on the character, with the time: " + m);
      check(Long.valueOf(1789000000000L).equals(r.profitSince) && "1789000000000".equals(m.get(EviLivePlugin.PROFIT_SINCE_KEY))
        && "100".equals(m.get(BridgePreferencesImport.PROFIT_DONE_KEY)), "no reset of the plugin's own: the bridge's is taken, once: " + m);
      check(said.size() == 1 && said.get(0).equals("EVI: imported 2 blocked items from import/preferences.json for this character, and the profit line's reset time"),
        "the log line: " + said);
      for (char ch : said.get(0).toCharArray()) check(ch >= 32 && ch < 127, "ASCII only");
      // UNBLOCK, then the import is never run again for p-a
      check(b.unblock("p-a", 561) && BridgePreferencesImport.run(true, root, "p-a", b, c, null, PARSER, 200, said::add, once) == null
        && b.all("p-a").equals(List.of(2, 4151)), "an Unblock is never undone by the import: " + b.all("p-a"));
      // ANOTHER character takes the same list once; the profit reset is not looked at again
      m.remove(EviLivePlugin.PROFIT_SINCE_KEY);
      r = BridgePreferencesImport.run(true, root, "p-b", b, c, null, PARSER, 300, said::add, once);
      check(r != null && r.blocksAdded == 2 && b.all("p-b").equals(List.of(561, 4151)) && r.profitSince == null && !m.containsKey(EviLivePlugin.PROFIT_SINCE_KEY),
        "p-b: the blocks, and no second profit reset: " + m);
      check(said.get(said.size() - 1).equals("EVI: imported 2 blocked items from import/preferences.json for this character"), "said: " + said);
      // the plugin's OWN reset is never overwritten (a fresh install with one)
      Map<String, String> m2 = new TreeMap<>();
      m2.put(EviLivePlugin.PROFIT_SINCE_KEY, "5");
      r = BridgePreferencesImport.run(true, root, "p-a", new BlockedItems(new MapStore(m2)), configOf(m2), 5L, PARSER, 400, said::add, once);
      check(r != null && r.profitSince == null && "5".equals(m2.get(EviLivePlugin.PROFIT_SINCE_KEY)) && "400".equals(m2.get(BridgePreferencesImport.PROFIT_DONE_KEY)),
        "the plugin's own reset stands: " + m2);
      // a file with no blocks still records the import (one look per character)
      writePrefs(root, "{\"profitSince\":null}");
      r = BridgePreferencesImport.run(true, root, "p-c", b, c, null, PARSER, 500, said::add, once);
      check(r != null && r.blocksAdded == 0 && "500".equals(m.get("p-c." + BridgePreferencesImport.DONE_KEY)) && b.all("p-c").isEmpty(), "no blocks: recorded, nothing added");
      // UNREADABLE: logged once a session, not recorded, imported once corrected
      writePrefs(root, "{not json");
      int before = said.size();
      check(BridgePreferencesImport.run(true, root, "p-d", b, c, null, PARSER, 600, said::add, once) == null
        && BridgePreferencesImport.run(true, root, "p-d", b, c, null, PARSER, 601, said::add, once) == null, "unreadable: nothing");
      check(said.size() == before + 1 && said.get(before).startsWith("EVI: import/preferences.json could not be read, so no blocked items were imported"),
        "logged once: " + said.subList(before, said.size()));
      check(!m.containsKey("p-d." + BridgePreferencesImport.DONE_KEY), "not recorded as imported");
      writePrefs(root, "{\"blocked\":[565]}");
      check(BridgePreferencesImport.run(true, root, "p-d", b, c, null, PARSER, 700, said::add, once) != null && b.all("p-d").equals(List.of(565)), "a corrected copy imports");
      // a list that cannot be saved is not recorded either (tried again at the next poll)
      BlockedItems failing = new BlockedItems(new BlockedItems.Store() {
        @Override public String read(String profile) {
          return null;
        }

        @Override public void write(String profile, String value) {
          throw new IllegalStateException("unwritable");
        }
      });
      check(BridgePreferencesImport.run(true, root, "p-e", failing, c, null, PARSER, 800, said::add, once) == null && !m.containsKey("p-e." + BridgePreferencesImport.DONE_KEY),
        "not saved: not recorded");
    } finally {
      root.deleteRecursively();
    }
  }

  static final String LATEST = "{\"data\":{\"554\":{\"high\":6,\"highTime\":1,\"low\":5,\"lowTime\":1},\"4151\":{\"high\":1560000,\"highTime\":1,\"low\":1500000,\"lowTime\":1}}}";
  static final String MAPPING = "[{\"id\":554,\"name\":\"Fire rune\",\"members\":false,\"limit\":25000},{\"id\":4151,\"name\":\"Abyssal whip\",\"members\":true,\"limit\":70}]";

  static EngineFeed.MarketSource tiny() throws Exception {
    EngineFeed.Market m = new EngineFeed.Market(WikiJson.latest(LATEST), 1_000, null, ItemCatalog.parse(MAPPING), null, null, Long.MIN_VALUE,
      InProcessEngine.BASIS_HOURS, InProcessEngine.BASIS_HOURS + 1, 0, 0, 1, 0, false, InProcessEngine.BASIS_HOURS);
    return new EngineFeed.MarketSource() {
      @Override public EngineFeed.Market market(long nowMs) {
        return m;
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        return CompletableFuture.completedFuture(new ArrayList<>());
      }
    };
  }

  static void keptCount() throws Exception {
    Filepath root = temp("evi-kept");
    PluginJournal journal = new PluginJournal(root, System::currentTimeMillis, PARSER, () -> false, m -> { });
    journal.start();
    InProcessEngine e = new InProcessEngine(root, EngineFeed.journalOf(() -> journal), tiny(), PARSER, m -> { }, () -> false, () -> null);
    try {
      long t = 1_000_000L;
      InProcessEngine.Answer a = e.request("includeInventory=1&inventory=4151:3,554:9000", t).get(30, TimeUnit.SECONDS);
      check(a != null && a.body != null && a.body.contains("\"source\":\"inventory\""), "the idle-stock tier read the bag: " + (a == null ? null : a.body));
      check(Double.valueOf(3).equals(e.keptFor(null, 4151, t)) && Double.valueOf(3).equals(e.keptFor(null, 4151, t + InProcessEngine.KEPT_FRESH_MS - 1)),
        "the count held when marked, from the bag a poll naming no account read: " + e.keptFor(null, 4151, t));
      check(e.keptFor(null, 4151, t + InProcessEngine.KEPT_FRESH_MS) == null, "a minute old: ignored (server.mjs: under 60 seconds)");
      check(e.keptFor(null, 565, t) == null, "an item not in the bag: no count");
      // a poll that NAMES an account is the plugin's normal one: the bridge keys it by that account. A mark naming no account
      // still reads the no-account bag while it is fresh (server.mjs: lastInventory.get('') first)
      e.request("account=acct-a&includeInventory=1&inventory=4151:7", t + 10).get(30, TimeUnit.SECONDS);
      check(Double.valueOf(3).equals(e.keptFor(null, 4151, t + 20)), "a fresh no-account bag still wins for a mark naming none: " + e.keptFor(null, 4151, t + 20));
      // THE 8 OCT FIX: the presser's own bag, by name -- and, naming none once the no-account bag is stale, the ONE account read
      // in the last minute (before the fix: always the blanket mark for a logged-in player)
      check(Double.valueOf(7).equals(e.keptFor("acct-a", 4151, t + 20)), "the presser's own bag: " + e.keptFor("acct-a", 4151, t + 20));
      long late = t + InProcessEngine.KEPT_FRESH_MS + 5;
      check(Double.valueOf(7).equals(e.keptFor(null, 4151, late)), "no account named, the no-account bag stale: the one fresh bag: " + e.keptFor(null, 4151, late));
      check(e.keptFor("acct-b", 4151, late) == null, "another account NAMED with no bag of its own: no count (never acct-a's)");
      e.request("account=acct-b&includeInventory=1&inventory=4151:9", t + 20).get(30, TimeUnit.SECONDS);
      check(e.keptFor(null, 4151, late) == null, "two accounts read in the last minute: nobody can say who pressed, so no count");
      check(Double.valueOf(9).equals(e.keptFor("acct-b", 4151, late)), "named: exact even then: " + e.keptFor("acct-b", 4151, late));
      // a poll whose earlier tier answered never reaches the idle-stock tier, so it reads no bag (server.mjs sets lastInventory there)
      e.request("includeInventory=1&inventory=4151:5&holdItemId=554&holdQty=100&holdName=Fire+rune", t + 30).get(30, TimeUnit.SECONDS);
      check(Double.valueOf(3).equals(e.keptFor(null, 4151, t + 40)), "a poll answered by the holding tier reads no bag: " + e.keptFor(null, 4151, t + 40));
    } finally {
      e.shutdown();
      journal.shutdown();
      journal.awaitIdle(30_000);
      root.deleteRecursively();
    }
  }

  // ------------------------------------------------------------------------------------------- 5. the plugin, IN_PROCESS

  /**
   * The login path (onGameTick's fifth logged-in tick) records which RuneScape profile the account it works out belongs to: the
   * join the per-character Block list is read through. A stand-in client (a Proxy answering LOGGED_IN, an ordinary world and
   * nothing else) is enough to reach it.
   */
  static net.runelite.api.Client standInClient() {
    return (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(EviLivePlugin.class.getClassLoader(),
      new Class<?>[]{net.runelite.api.Client.class}, (proxy, m, args) -> {
        if (m.getName().equals("getGameState")) return net.runelite.api.GameState.LOGGED_IN;
        if (m.getName().equals("getWorldType")) return java.util.EnumSet.noneOf(net.runelite.api.WorldType.class);
        Class<?> t = m.getReturnType();
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
      });
  }

  /** {@link #standInClient()} whose local player is named {@code name} (null: no local player yet). */
  static net.runelite.api.Client namedClient(String[] name) {
    net.runelite.api.Client base = standInClient();
    return (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(EviLivePlugin.class.getClassLoader(),
      new Class<?>[]{net.runelite.api.Client.class}, (proxy, m, args) -> {
        if (m.getName().equals("getLocalPlayer")) {
          if (name[0] == null) return null;
          return java.lang.reflect.Proxy.newProxyInstance(EviLivePlugin.class.getClassLoader(), new Class<?>[]{net.runelite.api.Player.class},
            (pp, pm, pa) -> pm.getName().equals("getName") ? name[0] : null);
        }
        return m.invoke(base, args);
      });
  }

  /**
   * 8 Oct 2026: the login path tells the plugin's journal the character's DISPLAY NAME for the account it works out -- the
   * journal's one-time import takes the bridge's flip histories from another tracker, kept by name, for that character only --
   * at the tick the account is worked out (before its first packet). No local player yet: told at a later packet instead. The
   * journal is started expecting names, and takes the imported item marks.
   */
  @SuppressWarnings("unchecked")
  static void loginTellsCharacterName() throws Exception {
    {
      String mode = "the plugin";
      ConfigManager cm = EviLiveRiskSkipTest.realConfigManager(new net.runelite.client.eventbus.EventBus());
      Field profileKey = ConfigManager.class.getDeclaredField("rsProfileKey");
      profileKey.setAccessible(true);
      profileKey.set(cm, "rsprofile.eeee");
      EviLivePlugin p = loginPlugin(cm);
      String[] name = {null};
      set(p, "client", namedClient(name));
      Filepath jroot = temp("evi-stores-names");
      call(p, "startJournal", new Class<?>[]{Filepath.class}, jroot);
      PluginJournal j = (PluginJournal) get(p, "journal");
      check((Boolean) get(j, "namesExpected"), mode + ": the journal is told names will come");
      check(((java.util.function.BooleanSupplier) get(j, "importItemMarks")).getAsBoolean(), mode + ": the import takes the bridge's item marks");
      Map<String, String> told = (Map<String, String>) get(j, "characterNames");
      ticks(p);
      String acct = (String) get(p, "account");
      check(acct != null && told.isEmpty(), mode + ": no local player yet: no name told");
      name[0] = "Ash\u00a0Fen";
      for (int i = 0; i < 15; i++) {
        try {
          call(p, "onGameTick", new Class<?>[]{net.runelite.api.events.GameTick.class}, (Object) null);
        } catch (java.lang.reflect.InvocationTargetException e) {
          // the stand-in client cannot answer every part of a tick
        }
      }
      // the stand-in answers no offers, so the login never completes and the account is worked out again on each tick
      check("Ash\u00a0Fen".equals(told.get((String) get(p, "account"))), mode + ": the name is told for the account worked out: " + told);
      call(p, "stopJournal", new Class<?>[0]);
      j.awaitIdle(30_000);
      jroot.deleteRecursively();
    }
  }

  /** Six game ticks, enough for the login path to work out the account (the fifth logged-in tick). */
  static void ticks(EviLivePlugin p) throws Exception {
    for (int i = 0; i < 6; i++) {
      try {
        call(p, "onGameTick", new Class<?>[]{net.runelite.api.events.GameTick.class}, (Object) null);
      } catch (java.lang.reflect.InvocationTargetException e) {
        // a part of the tick this stand-in client cannot answer; the account is worked out before the offers are read
      }
    }
  }

  static EviLivePlugin loginPlugin(ConfigManager cm) throws Exception {
    EviLivePlugin p = new EviLivePlugin();
    set(p, "config", EviLiveInProcessWiringTest.config());
    set(p, "configManager", cm);
    set(p, "client", standInClient());
    set(p, "salt", "test-salt");
    set(p, "running", true);
    return p;
  }

  /**
   * ...and remembers that profile as the LAST logged-in character (the maintainer's decision, 8 Oct 2026): a query naming no
   * account (the login screen, after a logout) reads that character's Block list; switching characters moves the pointer; it
   * survives a new plugin instance; with no character ever logged in no list applies; Unblock with no account known writes nothing.
   */
  @SuppressWarnings("unchecked")
  static void loginRecordsProfile() throws Exception {
    ConfigManager cm = EviLiveRiskSkipTest.realConfigManager(new net.runelite.client.eventbus.EventBus());
    Field profileKey = ConfigManager.class.getDeclaredField("rsProfileKey");
    profileKey.setAccessible(true);
    profileKey.set(cm, "rsprofile.cccc");
    cm.setConfiguration("evilive", "rsprofile.cccc", BlockedItems.CONFIG_KEY, "4151");
    cm.setConfiguration("evilive", "rsprofile.dddd", BlockedItems.CONFIG_KEY, "565");
    EviLivePlugin p = loginPlugin(cm);
    check(p.blockedFor(null).isEmpty() && p.blockedFor("").isEmpty(), "no character ever logged in: no list before login");
    ticks(p);
    String acct = (String) get(p, "account");
    check(acct != null && acct.length() == 64, "the fifth logged-in tick works out the account: " + acct);
    check("rsprofile.cccc".equals(((Map<String, String>) get(p, "accountProfiles")).get(acct)), "and records its RuneScape profile: " + get(p, "accountProfiles"));
    check("rsprofile.cccc".equals(call(p, "currentProfile", new Class<?>[0])), "the current account's profile is the one Block writes to");
    check("rsprofile.cccc".equals(cm.getConfiguration("evilive", EviLivePlugin.LAST_PROFILE_KEY)), "the last character is remembered in the config: "
      + cm.getConfiguration("evilive", EviLivePlugin.LAST_PROFILE_KEY));
    check(new ArrayList<>(p.blockedFor(acct)).equals(List.of(4151)), "logged in: the character's own list");
    // LOGOUT (no account known): the last character's list still applies, so its blocked item is not offered on the login screen
    call(p, "reset", new Class<?>[0]);
    check(get(p, "account") == null, "logged out: no account");
    check(new ArrayList<>(p.blockedFor(null)).equals(List.of(4151)) && new ArrayList<>(p.blockedFor("")).equals(List.of(4151)),
      "before login: the LAST character's list: " + p.blockedFor(null));
    check(p.blockedFor("acct-never-seen").isEmpty(), "an account NAMED but never worked out still gets no list (not the last one's)");
    // UNBLOCK with no account known: nothing is written anywhere
    Set<String> keysBefore = new java.util.TreeSet<>(cm.getConfigurationKeys("evilive"));
    p.unblockItem(4151);
    check("4151".equals(cm.getConfiguration("evilive", "rsprofile.cccc", BlockedItems.CONFIG_KEY))
      && "565".equals(cm.getConfiguration("evilive", "rsprofile.dddd", BlockedItems.CONFIG_KEY))
      && cm.getConfiguration("evilive", BlockedItems.CONFIG_KEY) == null
      && keysBefore.equals(new java.util.TreeSet<>(cm.getConfigurationKeys("evilive"))), "Unblock with no account known writes nothing: " + cm.getConfigurationKeys("evilive"));
    check(new ArrayList<>(p.blockedFor(null)).equals(List.of(4151)), "...and the last character's block stands");
    // ANOTHER character logs in: its own list, and it becomes the last character
    profileKey.set(cm, "rsprofile.dddd");
    ticks(p);
    String acct2 = (String) get(p, "account");
    check(acct2 != null && !acct2.equals(acct), "the other character's account: " + acct2);
    check(new ArrayList<>(p.blockedFor(acct2)).equals(List.of(565)) && new ArrayList<>(p.blockedFor(acct)).equals(List.of(4151)), "each character its own list");
    check("rsprofile.dddd".equals(cm.getConfiguration("evilive", EviLivePlugin.LAST_PROFILE_KEY)), "the pointer moved to the character now logged in");
    call(p, "reset", new Class<?>[0]);
    check(new ArrayList<>(p.blockedFor(null)).equals(List.of(565)), "logged out again: the NEW last character's list: " + p.blockedFor(null));
    // A NEW PLUGIN INSTANCE (a client restart) over the same config: before any login, the last character's list, handed to its engine
    EviLivePlugin p2 = loginPlugin(cm);
    set(p2, "gson", new Gson());
    Filepath root = temp("evi-last-profile");
    try {
      call(p2, "startInProcess", new Class<?>[]{Filepath.class}, root);
      Function<String, Collection<Integer>> handed = (Function<String, Collection<Integer>>) get(get(p2, "inProcess"), "blocked");
      check(new ArrayList<>(handed.apply(null)).equals(List.of(565)), "the pointer survives a new plugin instance: the engine is handed "
        + handed.apply(null) + " for a query naming no account");
    } finally {
      call(p2, "stopInProcess", new Class<?>[0]);
      root.deleteRecursively();
    }
  }

  /** Presses Unblock as the panel's button does (EviLivePlugin.unblockItem). */
  static void unblock(EviLivePlugin p, int itemId) {
    try {
      call(p, "unblockItem", new Class<?>[]{int.class}, itemId);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static javax.swing.JButton findButton(java.awt.Container c, String text) {
    for (java.awt.Component k : c.getComponents()) {
      if (k instanceof javax.swing.JButton && text.equals(((javax.swing.JButton) k).getText())) return (javax.swing.JButton) k;
      if (k instanceof java.awt.Container) {
        javax.swing.JButton b = findButton((java.awt.Container) k, text);
        if (b != null) return b;
      }
    }
    return null;
  }

  static void set(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  static Object get(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
    Method m = target.getClass().getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(target, args);
  }

  static void press(EviLivePlugin p, String button) throws Exception {
    call(p, button, new Class<?>[0]);
  }

  static void edt() throws Exception {
    SwingUtilities.invokeAndWait(() -> { });
  }

  /** A plugin as startUp leaves it, over a real ConfigManager, with a sender that runs only when released. */
  static final class Rig {
    final EviLivePlugin plugin = new EviLivePlugin();
    final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
    EviLivePanel panel;
    CountDownLatch gate;

    Rig(ConfigManager cm) throws Exception {
      EviLivePlugin p = plugin;
      set(p, "gson", new Gson());
      set(p, "config", EviLiveInProcessWiringTest.config());
      set(p, "configManager", cm);
      set(p, "account", "acct-a");
      set(p, "running", true);
      set(p, "lifecycle", 1L);
      set(p, "suggestionCache", new SuggestionCache());
      set(p, "openItemPriceCache", new OpenItemPriceCache());
      set(p, "sender", sender);
      SwingUtilities.invokeAndWait(() -> panel = new EviLivePanel(() -> { }, () -> { }, () -> { }, () -> { }, () -> { }));
      set(p, "panel", panel);
    }

    /** Holds the sender, so the sentence a press shows can be read before the poll that follows replaces it. */
    void hold() {
      gate = new CountDownLatch(1);
      CountDownLatch g = gate;
      sender.execute(() -> {
        try {
          g.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
          // nothing interrupts this test's sender
        }
      });
    }

    /** Chooses a holding line's menu item (the plugin method it calls) for the given line, and returns the sentence it showed. */
    String pressLine(String method, EviLivePlugin.AdviceCard line) throws Exception {
      hold();
      SwingUtilities.invokeAndWait(() -> {
        try {
          call(plugin, method, new Class<?>[]{EviLivePlugin.AdviceCard.class}, line);
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      });
      edt();
      edt();
      String said = ((JTextArea) get(panel, "suggestion")).getText();
      gate.countDown();
      sender.submit(() -> { }).get(30, TimeUnit.SECONDS);
      return said;
    }

    /** Presses a button with the given suggestion on screen, and returns the sentence it showed. */
    String press(String button, Suggestion s) throws Exception {
      ((SuggestionCache) get(plugin, "suggestionCache")).set(s);
      hold();
      EviLiveInProcessStoresTest.press(plugin, button);
      edt();
      edt();
      String said = ((JTextArea) get(panel, "suggestion")).getText();
      gate.countDown();
      sender.submit(() -> { }).get(30, TimeUnit.SECONDS);
      return said;
    }

    void close() {
      sender.shutdown();
    }
  }

  static Suggestion suggestion(int itemId, String action, String buyId, String id) {
    Suggestion s = EviLiveSuggestionTest.suggestion(itemId, action, 10, 20, 30);
    s.name = "Test item";
    s.buyId = buyId;
    s.id = id;
    return s;
  }

  /** The transcript's packets up to (not including) the step labelled {@code until}, with every acct-a / a1 name renamed to {@code tag}. */
  static List<String> packets(String transcript, String until, String account, String session, String tag) throws Exception {
    JsonObject t = resource("/parity/transcripts/" + transcript + ".json.gz").getAsJsonObject();
    List<String> out = new ArrayList<>();
    for (JsonElement se : t.getAsJsonArray("steps")) {
      JsonObject s = se.getAsJsonObject();
      if (s.has("label") && s.get("label").getAsString().equals(until)) break;
      if (!s.has("request") || !"/api/events".equals(s.getAsJsonObject("request").get("path").getAsString())) continue;
      out.add(s.getAsJsonObject("request").get("body").toString().replace("\"acct-a\"", "\"" + account + "\"").replace("\"sess-a1\"", "\"" + session + "\"")
        .replace("\"a1-", "\"" + tag + "-"));
    }
    return out;
  }

  static String buyIdAt(String transcript, String label) throws Exception {
    JsonObject t = resource("/parity/transcripts/" + transcript + ".json.gz").getAsJsonObject();
    for (JsonElement se : t.getAsJsonArray("steps")) {
      JsonObject s = se.getAsJsonObject();
      if (s.has("label") && s.get("label").getAsString().equals(label)) return s.getAsJsonObject("request").getAsJsonObject("body").get("buyId").getAsString();
    }
    throw new IllegalStateException("no step " + label);
  }

  static StoreState state(PluginJournal j) throws Exception {
    return j.engineView(System.currentTimeMillis()).get(30, TimeUnit.SECONDS).state;
  }

  static String fileText(Filepath f) throws Exception {
    return f.isFile() ? TestFiles.text(f) : "";
  }

  static void noUnsavedWording(String said) {
    check(!said.contains("not saved in this mode") && !said.contains("this mode yet"), "the session-only wording is gone: " + said);
    for (char c : said.toCharArray()) check(c >= 32 && c < 127, "ASCII only: " + said);
  }

  @SuppressWarnings("unchecked")
  static void pluginInProcess() throws Exception {
    net.runelite.client.eventbus.EventBus bus = new net.runelite.client.eventbus.EventBus();
    ConfigManager cm = EviLiveRiskSkipTest.realConfigManager(bus);
    Field profileKey = ConfigManager.class.getDeclaredField("rsProfileKey");
    profileKey.setAccessible(true);
    profileKey.set(cm, "rsprofile.aaaa");
    Filepath root = temp("evi-stores-plugin");
    long t0 = System.currentTimeMillis();
    PluginJournal journal = new PluginJournal(root, System::currentTimeMillis, PARSER, () -> false, m -> { });
    journal.start();
    // two accounts' journals: acct-a with the transcript's Nature and Blood buys held; acct-b with the same buys under its own ids
    for (String pk : packets("sidebar-marks-persist", "held-nat", "acct-a", "sess-a1", "a1")) journal.offerPacket(pk);
    for (String pk : packets("sidebar-marks-persist", "held-nat", "acct-b", "sess-b1", "b1")) journal.offerPacket(pk);
    String nat = buyIdAt("sidebar-marks-persist", "mark-nat"), blood = buyIdAt("sidebar-marks-persist", "gone-blood");
    String natB = nat.replace("a1-", "b1-"), bloodB = blood.replace("a1-", "b1-");
    StoreState st0 = state(journal);
    check(st0.autoOpenPositions.size() == 4, "both accounts hold Nature and Blood runes: " + st0.autoOpenPositions.size());
    Rig rig = new Rig(cm);
    EviLivePlugin p = rig.plugin;
    set(p, "journal", journal);
    // the accounts the plugin has worked out this lifetime, each with its RuneScape profile (onGameTick does this at login)
    Map<String, String> profiles = (Map<String, String>) get(p, "accountProfiles");
    profiles.put("acct-a", "rsprofile.aaaa");
    profiles.put("acct-b", "rsprofile.bbbb");
    try {
      call(p, "startInProcess", new Class<?>[]{Filepath.class}, root);
      check(get(p, "suggestionRecords") != null && get(p, "inProcess") != null && get(p, "answers") instanceof InProcessTransport,
        "startInProcess builds the engine, the suggestion records and the poll's answer source");

      // BLOCK: PER CHARACTER (8 Oct 2026), on the presser's RuneScape profile, handed to the engine for THAT account's queries only
      String said = rig.press("blockSuggestion", suggestion(4151, "buy", null, null));
      noUnsavedWording(said);
      check(said.equals("Blocked Test item on this character. EVI won't suggest buying it again; undo it under Blocked items. Checking for the next suggestion..."),
        "the Block sentence: " + said);
      check(!said.contains("no unblock"), "the no-unblock wording is gone");
      check("4151".equals(cm.getRSProfileConfiguration("evilive", BlockedItems.CONFIG_KEY)), "stored on acct-a's RuneScape profile: " + cm.getRSProfileConfiguration("evilive", BlockedItems.CONFIG_KEY));
      check(cm.getConfiguration("evilive", BlockedItems.CONFIG_KEY) == null, "...and NOT in the global group");
      edt();
      edt();
      check(rig.panel.blockedHeader.isVisible() && rig.panel.blockedList.isVisible(), "the Blocked items list appears with the first block");
      check("Blocked on this character".equals(rig.panel.blockedHeader.getText()), "its heading: " + rig.panel.blockedHeader.getText());
      // another character: its own list (a profile switch is a new account, as onGameTick works it out)
      set(p, "account", "acct-b");
      profileKey.set(cm, "rsprofile.bbbb");
      rig.press("blockSuggestion", suggestion(565, "buy", null, null));
      check("565".equals(cm.getRSProfileConfiguration("evilive", BlockedItems.CONFIG_KEY)), "acct-b's press is on acct-b's profile only");
      check("4151".equals(cm.getConfiguration("evilive", "rsprofile.aaaa", BlockedItems.CONFIG_KEY)), "acct-a's list is untouched");
      set(p, "account", "acct-a");
      profileKey.set(cm, "rsprofile.aaaa");
      Object engine = get(p, "inProcess");
      Function<String, Collection<Integer>> handed = (Function<String, Collection<Integer>>) get(engine, "blocked");
      check(new ArrayList<>(handed.apply("acct-a")).equals(List.of(4151)) && new ArrayList<>(handed.apply("acct-b")).equals(List.of(565)),
        "the engine is handed each account's own list: a " + handed.apply("acct-a") + ", b " + handed.apply("acct-b"));
      check(handed.apply(null).isEmpty() && handed.apply("").isEmpty() && handed.apply("acct-unknown").isEmpty(), "no account, or one never worked out: no list");
      // UNBLOCK (the panel's button): off acct-a's list, back in the session, the list hidden when empty
      Set<Integer> skipped = (Set<Integer>) get(p, "skippedItemIds");
      check(skipped.contains(4151), "Block also set the item aside for the session");
      rig.panel.onUnblock(id -> unblock(p, id));
      p.refreshBlockedSection(); // the next poll redraws the list for the account now logged in (acct-a)
      edt();
      edt();
      javax.swing.JButton undo = findButton(rig.panel.blockedList, "Unblock");
      check(undo != null && "Let EVI suggest buying Item 4151 again on this character.".equals(EviLivePanel.plainTip(undo.getToolTipText())), "an Unblock button per row, named");
      SwingUtilities.invokeAndWait(undo::doClick);
      edt();
      edt();
      check(cm.getRSProfileConfiguration("evilive", BlockedItems.CONFIG_KEY) == null, "unblocked: acct-a's list is empty, its key removed");
      check("565".equals(cm.getConfiguration("evilive", "rsprofile.bbbb", BlockedItems.CONFIG_KEY)), "acct-b's block stays");
      check(!skipped.contains(4151), "unblocked: no longer set aside for the session");
      check(!rig.panel.blockedHeader.isVisible() && !rig.panel.blockedList.isVisible(), "an empty list takes no room");
      rig.press("blockSuggestion", suggestion(4151, "buy", null, null)); // blocked again, for the restart below
      // no account known: nothing saved, and the sentence says so
      set(p, "account", null);
      said = rig.press("blockSuggestion", suggestion(554, "buy", null, null));
      check(said.equals("Set Test item aside for this session only: EVI could not save the block. Checking for the next suggestion..."), "said: " + said);
      // BEFORE LOGIN with a last character (8 Oct 2026): that character's list reaches the engine for a query naming no account,
      // the Blocked items list is HIDDEN (no account is known, so nothing there could be undone on the right character), and an
      // Unblock pressed now writes nothing and says why
      set(p, "account", "acct-a");
      p.refreshBlockedSection();
      edt();
      edt();
      check(rig.panel.blockedHeader.isVisible(), "the list shows while acct-a is logged in");
      set(p, "account", null);
      call(p, "rememberLastProfile", new Class<?>[]{String.class}, "rsprofile.aaaa");
      check("rsprofile.aaaa".equals(cm.getConfiguration("evilive", EviLivePlugin.LAST_PROFILE_KEY)), "the last character is kept in the config");
      check(new ArrayList<>(handed.apply(null)).equals(List.of(4151)) && new ArrayList<>(handed.apply("")).equals(List.of(4151)),
        "no account named: the last character's list: " + handed.apply(null));
      p.refreshBlockedSection();
      edt();
      edt();
      check(!rig.panel.blockedHeader.isVisible() && !rig.panel.blockedList.isVisible(), "before login the Blocked items list is hidden");
      unblock(p, 4151);
      edt();
      edt();
      check("4151".equals(cm.getConfiguration("evilive", "rsprofile.aaaa", BlockedItems.CONFIG_KEY)) && "565".equals(cm.getConfiguration("evilive", "rsprofile.bbbb", BlockedItems.CONFIG_KEY)),
        "Unblock with no account known writes nothing");
      String note = rig.panel.blockedNote.getText();
      check(note.equals("EVI could not unblock that item just now. Try again once you are logged in."), "and says so: " + note);
      for (char ch : note.toCharArray()) check(ch >= 32 && ch < 127, "ASCII only: " + note);
      rig.panel.blockedNotice(null);
      set(p, "account", "acct-a");
      check("4151".equals(cm.getRSProfileConfiguration("evilive", BlockedItems.CONFIG_KEY)), "nothing else saved");
      check(get(engine, "records") == get(p, "suggestionRecords"), "the engine issues ids through the plugin's own records");

      // RESET: the time in the config, read back by a NEW plugin instance
      long before = System.currentTimeMillis();
      rig.press("resetProfit", suggestion(554, "buy", null, null));
      String since = cm.getConfiguration("evilive", EviLivePlugin.PROFIT_SINCE_KEY);
      check(since != null && Long.parseLong(since) >= before && get(p, "inProcessProfitSince").equals(Long.valueOf(since)), "Reset kept: " + since);

      // PERSONAL USE on acct-a's Nature buy, and GONE on its Blood buy
      said = rig.press("flagPersonalUse", suggestion(561, "sell", nat, null));
      noUnsavedWording(said);
      check(said.equals("Marked as personal use. Checking for the next suggestion..."), "the bridge's sentence: " + said);
      said = rig.press("flagNotHeld", suggestion(565, "sell", blood, null));
      noUnsavedWording(said);
      check(said.equals("Marked as no longer held. Checking for the next suggestion..."), "the bridge's sentence: " + said);
      journal.awaitIdle(30_000);
      StoreState st1 = state(journal);
      check(st1.personalUseBuyIds.equals(List.of(nat)), "the buy is personal use: " + st1.personalUseBuyIds);
      check(st1.closedPositions.size() == 1 && st1.closedPositions.get(0).buyId.equals(blood) && "sold-untracked".equals(st1.closedPositions.get(0).reason),
        "the Blood lot is closed as sold-untracked");
      Set<String> open = new HashSet<>();
      for (com.evi.live.journal.FifoMatcher.OpenPosition o : st1.autoOpenPositions) open.add(o.buyId);
      check(open.equals(new HashSet<>(List.of(natB, bloodB))), "acct-b's own Nature and Blood lots are untouched: " + open);
      Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
      String fileA = fileText(dir.joinSegment("events-acct-a.jsonl")), fileB = fileText(dir.joinSegment("events-acct-b.jsonl"));
      check(fileA.contains("\"type\":\"personal-use\"") && fileA.contains("\"type\":\"position-closed\""), "the marks are in acct-a's journal");
      check(!fileB.contains("personal-use") && !fileB.contains("position-closed"), "and in no other account's");
      // "Yours, not stock" (idle stock, no buy): an item mark, in the presser's journal
      said = rig.press("flagPersonalUse", suggestion(22325, "sell", null, null));
      check(said.equals("Marked as yours, not stock. EVI won't suggest selling it again. Checking for the next suggestion..."), "the bridge's sentence: " + said);
      journal.awaitIdle(30_000);
      check(state(journal).personalUseItems.contains(22325) && fileText(dir.joinSegment("events-acct-a.jsonl")).contains("\"type\":\"personal-use-item\""),
        "the item mark is journalled in acct-a's file");
      // the kept count: the bag the engine last read for the presser's account (put in place here as the engine records it)
      Class<?> reading = Class.forName("com.evi.live.inprocess.InProcessEngine$Reading");
      java.lang.reflect.Constructor<?> rc = reading.getDeclaredConstructor(Map.class, long.class);
      rc.setAccessible(true);
      // two accounts' bags read just now: only naming the presser (acct-a) gives its count (naming none would give no count)
      ((Map<String, Object>) get(engine, "lastInventory")).put("acct-a", rc.newInstance(Map.of(4153, 2.0), System.currentTimeMillis()));
      ((Map<String, Object>) get(engine, "lastInventory")).put("acct-b", rc.newInstance(Map.of(4153, 5.0), System.currentTimeMillis()));
      rig.press("flagPersonalUse", suggestion(4153, "sell", null, null));
      journal.awaitIdle(30_000);
      check(Long.valueOf(2).equals(state(journal).personalUseKept.get(4153)), "the item mark carries the count held when marked: " + state(journal).personalUseKept);
      check(!state(journal).personalUseKept.containsKey(22325), "the earlier mark, with no bag read, is the blanket kind");
      // a buy is journalled in the file of the account that OWNS it, whoever presses (BridgeImport's attribution)
      check(journal.markPersonalUse("acct-b", blood).get(30, TimeUnit.SECONDS) == null, "a mark on acct-a's Blood buy");
      journal.awaitIdle(30_000);
      check(fileText(dir.joinSegment("events-acct-a.jsonl")).contains("\"buyId\":\"" + blood + "\"") && !fileText(dir.joinSegment("events-acct-b.jsonl")).contains("personal-use"),
        "...goes to acct-a's journal, not the presser's");
      check(journal.markPersonalUse("acct-a", "a1-999").get(30, TimeUnit.SECONDS).equals("Unknown or not-yet-observed buy"), "an unknown buy: the bridge's refusal");
      // with no account known there is nowhere to journal an item mark: nothing is saved, and the sentence says so
      set(p, "account", null);
      said = rig.press("flagPersonalUse", suggestion(4152, "sell", null, null));
      noUnsavedWording(said);
      check(said.equals("Set aside for this session only: EVI could not save the mark just now. Checking for the next suggestion..."), "said: " + said);
      journal.awaitIdle(30_000);
      check(!state(journal).personalUseItems.contains(4152), "nothing journalled without an account");
      set(p, "account", "acct-a");

      // TOOK IT: recorded, read back by a new instance, and taken back by a second press
      SuggestionRecords rec = (SuggestionRecords) get(p, "suggestionRecords");
      Suggestion shown = suggestion(561, "buy", null, "mub70s7k-561-00");
      ((SuggestionCache) get(p, "suggestionCache")).set(shown);
      press(p, "acceptSuggestion");
      rig.sender.submit(() -> { }).get(30, TimeUnit.SECONDS);
      check(shown.accepted && rec.wasAccepted("mub70s7k-561-00"), "Took it recorded");
      check(new SuggestionRecords(root, PARSER, m -> { }).wasAccepted("mub70s7k-561-00"), "read back after a restart");
      press(p, "acceptSuggestion");
      rig.sender.submit(() -> { }).get(30, TimeUnit.SECONDS);
      check(!shown.accepted && !new SuggestionRecords(root, PARSER, m -> { }).wasAccepted("mub70s7k-561-00"), "pressed again: taken back, for good");
      check(fileText(root.joinSegment(SuggestionRecords.DIR).joinSegment(SuggestionRecords.ACCEPTED)).contains("\"account\":\"acct-a\""), "the presser is named");
    } finally {
      call(p, "stopInProcess", new Class<?>[0]);
      rig.close();
      journal.shutdown();
      journal.awaitIdle(30_000);
    }

    // A RESTART: a new plugin instance, the same ConfigManager and folder, a new journal
    PluginJournal again = new PluginJournal(root, System::currentTimeMillis, PARSER, () -> false, m -> { });
    again.start(journal);
    Rig rig2 = new Rig(cm);
    try {
      EviLivePlugin p2 = rig2.plugin;
      set(p2, "journal", again);
      call(p2, "startInProcess", new Class<?>[]{Filepath.class}, root);
      check(get(p2, "inProcessProfitSince") != null && get(p2, "inProcessProfitSince").equals(Long.valueOf(cm.getConfiguration("evilive", EviLivePlugin.PROFIT_SINCE_KEY))),
        "Reset survives a new plugin instance");
      Function<String, Collection<Integer>> handed = (Function<String, Collection<Integer>>) get(get(p2, "inProcess"), "blocked");
      check(handed.apply("acct-a").isEmpty(), "before the new instance has worked out acct-a's profile (a login): no list for acct-a by name");
      check(new ArrayList<>(handed.apply(null)).equals(List.of(4151)), "...but a query naming no account reads the last character's list: " + handed.apply(null));
      ((Map<String, String>) get(p2, "accountProfiles")).put("acct-a", "rsprofile.aaaa");
      ((Map<String, String>) get(p2, "accountProfiles")).put("acct-b", "rsprofile.bbbb");
      check(new ArrayList<>(handed.apply("acct-a")).equals(List.of(4151)) && new ArrayList<>(handed.apply("acct-b")).equals(List.of(565)),
        "each character's blocks survive a new plugin instance: " + handed.apply("acct-a") + " / " + handed.apply("acct-b"));
      // THE IMPORT through the plugin (the setting on): the bridge's blocks join acct-a's own, once; the plugin's Reset stands
      set(p2, "config", new EviLiveConfig() {
        public boolean importBridgeHistory() {
          return true;
        }
      });
      writePrefs(root, "{\"blocked\":[561,4151],\"profitSince\":5}");
      String ownReset = cm.getConfiguration("evilive", EviLivePlugin.PROFIT_SINCE_KEY);
      call(p2, "importBridgePreferences", new Class<?>[0]);
      check(new ArrayList<>(handed.apply("acct-a")).equals(List.of(561, 4151)) && new ArrayList<>(handed.apply("acct-b")).equals(List.of(565)),
        "imported for the character logged in (acct-a) only: " + handed.apply("acct-a") + " / " + handed.apply("acct-b"));
      check(cm.getConfiguration("evilive", "rsprofile.aaaa", BridgePreferencesImport.DONE_KEY) != null, "recorded on acct-a's profile");
      check(ownReset.equals(cm.getConfiguration("evilive", EviLivePlugin.PROFIT_SINCE_KEY)) && ownReset.equals(String.valueOf(get(p2, "inProcessProfitSince"))),
        "the plugin's own Reset is not overwritten by the bridge's");
      unblock(p2, 561);
      call(p2, "importBridgePreferences", new Class<?>[0]);
      check(new ArrayList<>(handed.apply("acct-a")).equals(List.of(4151)), "never imported again after an Unblock: " + handed.apply("acct-a"));
      set(p2, "account", "acct-b");
      profileKey.set(cm, "rsprofile.bbbb");
      call(p2, "importBridgePreferences", new Class<?>[0]);
      check(new ArrayList<>(handed.apply("acct-b")).equals(List.of(561, 565, 4151)), "and once for acct-b: " + handed.apply("acct-b"));
      set(p2, "account", "acct-a");
      profileKey.set(cm, "rsprofile.aaaa");
      // THE WIRING: the panel startUp builds says where Block is undone and sends Unblock to the plugin; the journal startUp
      // starts takes the bridge's item marks with an import
      EviLivePanel[] built = new EviLivePanel[1];
      SwingUtilities.invokeAndWait(() -> built[0] = p2.buildPanel());
      built[0].actions(true, false, false, false, true);
      edt();
      check(EviLivePanel.BLOCK_TIP_HERE.equals(EviLivePanel.plainTip(((javax.swing.JButton) get(built[0], "blockButton")).getToolTipText())), "Block's tooltip names this panel");
      check(!EviLivePanel.BLOCK_TIP_HERE.contains("dashboard") && !EviLivePanel.BLOCK_TIP_HERE.contains("scanner"), "...and no dashboard or scanner");
      ((java.util.function.IntConsumer) get(built[0], "unblock")).accept(4151);
      check(handed.apply("acct-a").isEmpty(), "IN_PROCESS: the built panel's Unblock reaches the plugin: " + handed.apply("acct-a"));
      Filepath jroot = temp("evi-stores-journal");
      call(p2, "startJournal", new Class<?>[]{Filepath.class}, jroot);
      PluginJournal startedJournal = (PluginJournal) get(p2, "journal");
      check(((java.util.function.BooleanSupplier) get(startedJournal, "importItemMarks")).getAsBoolean(), "IN_PROCESS: the import takes the bridge's item marks");
      call(p2, "stopJournal", new Class<?>[0]);
      startedJournal.awaitIdle(30_000);
      jroot.deleteRecursively();
      set(p2, "journal", again);
      StoreState st2 = state(again);
      check(st2.personalUseBuyIds.equals(List.of(nat, blood)) && st2.closedPositions.size() == 1 && st2.personalUseItems.contains(22325)
        && Long.valueOf(2).equals(st2.personalUseKept.get(4153)),
        "the journal's marks survive a restart");
      check(((SuggestionRecords) get(p2, "suggestionRecords")) != null, "the new instance has its records");
      check(System.currentTimeMillis() >= t0, "clock");
    } finally {
      call(rig2.plugin, "stopInProcess", new Class<?>[0]);
      rig2.close();
      again.shutdown();
      again.awaitIdle(30_000);
      root.deleteRecursively();
    }
  }

  // ------------------------------------------------------------------------------------------- 7. holding lines' menu

  /** A holding line as the engine's relistAdvice names it, through the plugin's own reading of the body (Gson, offerCards). */
  static EviLivePlugin.AdviceCard line(int itemId, String buyId) {
    EviLivePlugin.SuggestionResponse r = new Gson().fromJson("{\"relistAdvice\":[{\"itemId\":" + itemId + ",\"name\":\"Test item\",\"message\":\"Bought for 100.\","
      + "\"level\":\"info\",\"label\":\"Holding\",\"figures\":\"1\",\"holding\":true,\"belowBreakEven\":false,\"buyId\":\"" + buyId + "\"}]}", EviLivePlugin.SuggestionResponse.class);
    List<EviLivePlugin.AdviceCard> cards = EviLivePlugin.offerCards(new ArrayList<>(), null, null, r.relistAdvice, null, false);
    check(cards.size() == 1 && cards.get(0).itemId == itemId && buyId.equals(cards.get(0).buyId), "the line keeps its item and lot");
    return cards.get(0);
  }

  /**
   * HOLDING LINES (8 Oct 2026). The lines under Active offers that state what this account holds (holdingsAdvice) carry their
   * lot's buyId, and the panel offers Personal use / I don't have this anymore on them. Each choice does EXACTLY what the card's
   * button does for the same buy: the same journal write, in the file of the account that owns the buy, another account's lot of
   * the same item untouched, and the same sentence. Only a holding line
   * with a lot gets a lot: an offer note, a crash note, or a holding line from an engine naming none, carries no buyId.
   */
  @SuppressWarnings("unchecked")
  static void holdingLines() throws Exception {
    // offerCards: which notes keep a lot
    EviLivePlugin.SuggestionResponse r = new Gson().fromJson("{\"relistAdvice\":["
      + "{\"itemId\":561,\"name\":\"Nature rune\",\"message\":\"m1\",\"level\":\"info\",\"label\":\"Holding\",\"figures\":\"f\",\"holding\":true,\"buyId\":\"a1-4\"},"
      + "{\"itemId\":565,\"name\":\"Blood rune\",\"message\":\"m2\",\"level\":\"info\",\"label\":\"Holding\",\"figures\":\"f\",\"holding\":true},"
      + "{\"itemId\":554,\"name\":\"Fire rune\",\"message\":\"m3\",\"level\":\"warn\",\"label\":\"Crashing\",\"figures\":\"One of yours\",\"buyId\":\"a1-5\"},"
      + "{\"itemId\":555,\"name\":\"Water rune\",\"message\":\"m4\",\"level\":\"info\",\"label\":\"Holding\",\"figures\":\"f\",\"holding\":true,\"buyId\":\"\"}]}",
      EviLivePlugin.SuggestionResponse.class);
    List<EviLivePlugin.AdviceCard> cards = EviLivePlugin.offerCards(new ArrayList<>(), null, null, r.relistAdvice, null, true);
    check(cards.size() == 4, "four cards");
    check("a1-4".equals(cards.get(0).buyId) && cards.get(0).itemId == 561, "a holding line keeps its lot");
    check(cards.get(1).buyId == null && cards.get(2).buyId == null && cards.get(3).buyId == null,
      "no lot without one, on a note that is not a holding, or an empty one");
    // A crash note's detail (8 Oct 2026) reaches the card through the plugin's own reading of the body; no detail, no field.
    EviLivePlugin.SuggestionResponse withDetail = new Gson().fromJson("{\"relistAdvice\":["
      + "{\"itemId\":554,\"name\":\"Fire rune\",\"message\":\"short\",\"detail\":\"the rest\",\"level\":\"warn\",\"label\":\"Crashing\",\"figures\":\"One of yours\"},"
      + "{\"itemId\":555,\"name\":\"Water rune\",\"message\":\"m\",\"level\":\"info\",\"label\":\"Holding\",\"figures\":\"f\",\"detail\":\"\"}]}",
      EviLivePlugin.SuggestionResponse.class);
    List<EviLivePlugin.AdviceCard> detailCards = EviLivePlugin.offerCards(new ArrayList<>(), null, null, withDetail.relistAdvice, null, true);
    check("short".equals(detailCards.get(0).message) && "the rest".equals(detailCards.get(0).detail) && detailCards.get(1).detail == null,
      "the card keeps the short line and the detail; an empty detail is none");

    // the journal write, in the owner's file; another account's lot of the same item untouched
    net.runelite.client.eventbus.EventBus bus = new net.runelite.client.eventbus.EventBus();
    ConfigManager cm = EviLiveRiskSkipTest.realConfigManager(bus);
    Field profileKey = ConfigManager.class.getDeclaredField("rsProfileKey");
    profileKey.setAccessible(true);
    profileKey.set(cm, "rsprofile.aaaa");
    Filepath root = temp("evi-stores-lines-ip");
    PluginJournal journal = new PluginJournal(root, System::currentTimeMillis, PARSER, () -> false, m -> { });
    journal.start();
    for (String pk : packets("sidebar-marks-persist", "held-nat", "acct-a", "sess-a1", "a1")) journal.offerPacket(pk);
    for (String pk : packets("sidebar-marks-persist", "held-nat", "acct-b", "sess-b1", "b1")) journal.offerPacket(pk);
    String nat = buyIdAt("sidebar-marks-persist", "mark-nat"), blood = buyIdAt("sidebar-marks-persist", "gone-blood");
    String natB = nat.replace("a1-", "b1-"), bloodB = blood.replace("a1-", "b1-");
    Rig rig = new Rig(cm);
    EviLivePlugin p = rig.plugin;
    set(p, "journal", journal);
    ((Map<String, String>) get(p, "accountProfiles")).put("acct-a", "rsprofile.aaaa");
    try {
      call(p, "startInProcess", new Class<?>[]{Filepath.class}, root);
      // through the holding line's menu, wired the way buildPanel wires it: item 0 is Personal use, item 1 Gone
      EviLivePanel[] built = new EviLivePanel[1];
      SwingUtilities.invokeAndWait(() -> built[0] = p.buildPanel());
      check(EviLivePanel.LINE_PERSONAL_USE.equals(((javax.swing.JMenuItem) built[0].holdingMenu(line(561, nat)).getComponent(0)).getText())
        && EviLivePanel.LINE_GONE.equals(((javax.swing.JMenuItem) built[0].holdingMenu(line(565, blood)).getComponent(1)).getText()), "the menu's two items, in order");
      String use = rig.pressLine("holdingLinePersonalUse", line(561, nat));
      String gone = rig.pressLine("holdingLineGone", line(565, blood));
      noUnsavedWording(use);
      noUnsavedWording(gone);
      check(use.equals("Marked as personal use. Checking for the next suggestion...") && gone.equals("Marked as no longer held. Checking for the next suggestion..."),
        "the card's IN_PROCESS sentences: " + use + " / " + gone);
      journal.awaitIdle(30_000);
      StoreState st = state(journal);
      check(st.personalUseBuyIds.equals(List.of(nat)), "the line's buy is personal use: " + st.personalUseBuyIds);
      check(st.closedPositions.size() == 1 && st.closedPositions.get(0).buyId.equals(blood) && "sold-untracked".equals(st.closedPositions.get(0).reason),
        "the line's lot is closed as sold-untracked, as the card's Gone closes it");
      Set<String> open = new HashSet<>();
      for (com.evi.live.journal.FifoMatcher.OpenPosition o : st.autoOpenPositions) open.add(o.buyId);
      check(open.equals(new HashSet<>(List.of(natB, bloodB))), "acct-b's own lots of the same items are untouched: " + open);
      Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
      check(fileText(dir.joinSegment("events-acct-a.jsonl")).contains("\"type\":\"personal-use\"") && fileText(dir.joinSegment("events-acct-a.jsonl")).contains("\"type\":\"position-closed\"")
        && !fileText(dir.joinSegment("events-acct-b.jsonl")).contains("personal-use") && !fileText(dir.joinSegment("events-acct-b.jsonl")).contains("position-closed"),
        "journalled in the owner's file only");
      // a card with no lot does nothing at all
      journal.awaitIdle(30_000);
      String fileBefore = fileText(dir.joinSegment("events-acct-a.jsonl"));
      Set<Integer> skippedBefore = new HashSet<>((Set<Integer>) get(p, "skippedItemIds"));
      String shown = ((JTextArea) get(rig.panel, "suggestion")).getText();
      String said = rig.pressLine("holdingLinePersonalUse", new EviLivePlugin.AdviceCard("info", "Holding", "Test item", "f", "m", 4153, null));
      String shown2 = ((JTextArea) get(rig.panel, "suggestion")).getText();
      String said2 = rig.pressLine("holdingLineGone", new EviLivePlugin.AdviceCard("info", "Holding", "Test item", "f", "m", 4153, ""));
      journal.awaitIdle(30_000);
      check(fileText(dir.joinSegment("events-acct-a.jsonl")).equals(fileBefore) && state(journal).personalUseItems.isEmpty(), "a line with no lot journals nothing");
      check(skippedBefore.equals(get(p, "skippedItemIds")) && said.equals(shown) && said2.equals(shown2), "...sets nothing aside and says nothing: " + said + " / " + said2);
    } finally {
      call(p, "stopInProcess", new Class<?>[0]);
      rig.close();
      journal.shutdown();
      journal.awaitIdle(30_000);
      root.deleteRecursively();
    }
  }
}
