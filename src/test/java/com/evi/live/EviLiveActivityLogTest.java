package com.evi.live;

import com.evi.live.activity.ActivityLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import net.runelite.client.util.Filepath;

/**
 * The local activity log file (12 Nov 2026, schema 1): the writer (one file per account per UTC month, the envelope, a closed
 * month gzipped, failing quietly, dropped records counted) and the events the plugin turns into records (a suggestion only
 * when it changes, how an offer is linked to a suggestion, fills as deltas, what was only seen at login said as such).
 * Run by the activityLogTest task; part of check.
 */
public final class EviLiveActivityLogTest {
  static int checks;

  static void check(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  static final long NOV = 1794441600000L; // 2026-11-12T00:00:00Z
  static final long DEC = 1796083200000L; // 2026-12-01T00:00:00Z
  static final String ACCT = "acct-1";

  static Filepath temp() throws Exception {
    return TestFiles.tempDir("evi-activity");
  }

  static List<JsonObject> lines(Filepath f) throws Exception {
    List<JsonObject> out = new ArrayList<>();
    if (!f.isFile()) return out;
    byte[] b;
    try (InputStream in = f.openInputStream()) {
      b = in.readAllBytes();
    }
    for (String l : new String(b, StandardCharsets.UTF_8).split("\n")) if (!l.isEmpty()) out.add(new JsonParser().parse(l).getAsJsonObject());
    return out;
  }

  static Filepath file(Filepath root, String name) {
    return root.joinSegment(ActivityLog.DIR).joinSegment(name);
  }

  static JsonObject obj(String k, String v) {
    JsonObject o = new JsonObject();
    o.addProperty(k, v);
    return o;
  }

  static EviLivePlugin.Offer offer(String id, String state, int item, long price, int total, int filled, long spent) {
    EviLivePlugin.Offer o = new EviLivePlugin.Offer();
    o.slot = 0; o.offerId = id; o.state = state; o.itemId = item; o.price = price; o.total = total; o.filled = filled; o.spent = spent; o.name = "Test item";
    return o;
  }

  static EviLivePlugin.Offer emptySlot() {
    EviLivePlugin.Offer o = new EviLivePlugin.Offer();
    o.slot = 0; o.offerId = "e"; o.state = "EMPTY";
    return o;
  }

  public static void main(String[] args) throws Exception {
    writer();
    failsQuietly();
    droppedAreCounted();
    events();
    wiring();
    System.out.println("PASS: activity log (" + checks + " checks): one file per account per UTC month with the schema envelope, the envelope never overwritten,"
      + " no account no line, a closed month gzipped and nothing deleted, a failing folder costing nothing but one log line, dropped records counted on the next"
      + " line, a suggestion written only when it changes, offers linked by hotkey / exact / item / none, fills as deltas, at-login sightings said as such");
  }

  static void writer() throws Exception {
    Filepath root = temp();
    AtomicLong clock = new AtomicLong(NOV + 5000);
    List<String> log = new CopyOnWriteArrayList<>();
    ActivityLog t = new ActivityLog(root, clock::get, log::add);
    JsonObject f = obj("reason", "login");
    f.addProperty("v", 9); // a field may not overwrite the envelope
    f.addProperty("type", "nonsense");
    t.record(ACCT, "sid-1", "session", f);
    t.record(null, "sid-1", "alive", null);          // before a login: nowhere to put it
    t.record("../evil", "sid-1", "alive", null);     // never a path
    t.record(ACCT, null, "alive", null);
    check(t.awaitIdle(20000), "drain");
    List<JsonObject> nov = lines(file(root, "activity-acct-1-2026-11.jsonl"));
    check(nov.size() == 2, "two lines for the account, none for a missing or unsafe account: " + nov);
    JsonObject first = nov.get(0);
    check(first.get("v").getAsInt() == ActivityLog.SCHEMA && "session".equals(first.get("type").getAsString()) && first.get("t").getAsLong() == NOV + 5000
      && "sid-1".equals(first.get("sid").getAsString()) && "login".equals(first.get("reason").getAsString()), "the envelope and the record's own field: " + first);
    check(!nov.get(1).has("sid") && "alive".equals(nov.get(1).get("type").getAsString()), "no session id is absent, not null: " + nov.get(1));
    int files = 0;
    try (java.util.stream.Stream<Filepath> s = root.joinSegment(ActivityLog.DIR).walk(1)) {
      files = (int) s.filter(Filepath::isFile).count();
    }
    check(files == 1, "exactly one file so far: " + files);
    // The next month: a new file, and the closed one is gzipped with the same bytes and never lost.
    byte[] before;
    try (InputStream in = file(root, "activity-acct-1-2026-11.jsonl").openInputStream()) {
      before = in.readAllBytes();
    }
    clock.set(DEC + 1000);
    t.record(ACCT, "sid-2", "alive", null);
    check(t.awaitIdle(20000), "drain");
    check(lines(file(root, "activity-acct-1-2026-12.jsonl")).size() == 1, "December's line went to December's file");
    check(!file(root, "activity-acct-1-2026-11.jsonl").exists() && file(root, "activity-acct-1-2026-11.jsonl.gz").isFile(), "November was gzipped");
    byte[] after;
    try (InputStream in = new GZIPInputStream(file(root, "activity-acct-1-2026-11.jsonl.gz").openInputStream())) {
      after = in.readAllBytes();
    }
    check(java.util.Arrays.equals(before, after), "the archive holds exactly what the month held");
    check(log.isEmpty(), "nothing to complain about: " + log);
    t.shutdown();
    t.record(ACCT, "sid-2", "alive", null); // after shutdown: ignored, never thrown
    root.deleteRecursively();
  }

  static void failsQuietly() throws Exception {
    Filepath root = temp();
    // "activity" exists as a FILE, so the folder can never be made: every write fails.
    try (java.io.OutputStream out = root.joinSegment(ActivityLog.DIR).openOutputStream(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
      out.write(1);
    }
    List<String> log = new CopyOnWriteArrayList<>();
    ActivityLog t = new ActivityLog(root, () -> NOV, log::add);
    for (int i = 0; i < 5; i++) t.record(ACCT, "s", "alive", null);
    check(t.awaitIdle(20000), "drain");
    check(log.size() == 1 && log.get(0).contains("EVI itself is unaffected"), "a failing folder is said ONCE and nothing is thrown: " + log);
    t.shutdown();
    root.deleteRecursively();
  }

  static void droppedAreCounted() throws Exception {
    Filepath root = temp();
    ActivityLog t = new ActivityLog(root, () -> NOV, m -> { });
    CountDownLatch hold = new CountDownLatch(1);
    t.record(ACCT, "s", "first", null);
    check(t.awaitIdle(20000), "drain");
    t.onWriter(() -> { try { hold.await(); } catch (InterruptedException ignored) { } });
    for (int i = 0; i < 5000; i++) t.record(ACCT, "s", "flood", null); // far past the queue: the rest are dropped, none blocks
    hold.countDown();
    check(t.awaitIdle(20000), "drain");
    t.record(ACCT, "s", "after", null);
    check(t.awaitIdle(20000), "drain");
    List<JsonObject> all = lines(file(root, "activity-acct-1-2026-11.jsonl"));
    long flood = all.stream().filter(o -> "flood".equals(o.get("type").getAsString())).count();
    long said = all.stream().filter(o -> o.has("dropped")).mapToLong(o -> o.get("dropped").getAsLong()).sum();
    check(flood < 5000 && flood > 0, "some of the flood was written, the rest dropped: " + flood);
    check(said == 5000 - flood, "the lines written afterwards say how many were dropped: said " + said + ", flood written " + flood);
    check("after".equals(all.get(all.size() - 1).get("type").getAsString()), "and writing goes on afterwards");
    t.shutdown();
    root.deleteRecursively();
  }

  static void set(Object target, String name, Object value) throws Exception {
    java.lang.reflect.Field f = EviLivePlugin.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  /** The plugin itself writes the records: a poll's pick, a press, a failed poll -- through the real pollSuggestion(). */
  static void wiring() throws Exception {
    Filepath root = temp();
    ActivityLog t = new ActivityLog(root, () -> NOV, m -> { });
    EviLivePlugin p = new EviLivePlugin();
    java.lang.reflect.Field tf = EviLivePlugin.class.getDeclaredField("tel");
    tf.setAccessible(true);
    ActivityEvents e = (ActivityEvents) tf.get(p);
    e.start(t);
    e.session(ACCT, "W", "login", null, null, null);
    final String[] answer = {"{\"suggestion\":{\"itemId\":4151,\"name\":\"Abyssal whip\",\"action\":\"buy\",\"quantity\":1,\"buyPrice\":1000,\"sellPrice\":1200,\"source\":\"market\",\"reasoning\":\"a long sentence that is not kept\"}}"};
    set(p, "gson", new com.google.gson.Gson());
    set(p, "config", new EviLiveConfig() { });
    set(p, "running", true);
    set(p, "lifecycle", 1L);
    set(p, "suggestionCache", new SuggestionCache());
    set(p, "openItemPriceCache", new OpenItemPriceCache());
    set(p, "answers", (AnswerSource) query -> answer[0]);
    java.lang.reflect.Method poll = EviLivePlugin.class.getDeclaredMethod("pollSuggestion", long.class);
    poll.setAccessible(true);
    poll.invoke(p, 1L);
    poll.invoke(p, 1L); // the same pick again: no second line
    java.lang.reflect.Method skip = EviLivePlugin.class.getDeclaredMethod("skipSuggestion");
    skip.setAccessible(true);
    skip.invoke(p);
    poll.invoke(p, 1L); // shown again after the press
    answer[0] = null;   // the engine has no answer: the pick must end and the state be said
    poll.invoke(p, 1L);
    check(t.awaitIdle(20000), "drain");
    List<String> types = new ArrayList<>();
    List<JsonObject> all = lines(file(root, "activity-acct-1-2026-11.jsonl"));
    for (JsonObject o : all) types.add(o.get("type").getAsString());
    check(types.equals(java.util.List.of("session", "settings", "suggestion", "action", "suggestion-end", "suggestion", "suggestion-end", "empty")),
      "the plugin's own records, in order: " + types);
    check(all.get(1).getAsJsonObject("settings").has("pace") && all.get(2).get("itemId").getAsInt() == 4151 && !all.get(2).has("reasoning") && "W-1".equals(all.get(2).get("id").getAsString()),
      "the settings in force (the session line had none), then the pick as the plugin holds it, without the long sentence: " + all.get(2));
    check("skip".equals(all.get(3).get("kind").getAsString()) && "skipped".equals(all.get(4).get("why").getAsString()), "the press, then why the pick left");
    check("unavailable".equals(all.get(7).get("state").getAsString()), "a poll with no answer says so: " + all.get(7));
    t.shutdown();
    root.deleteRecursively();
  }

  static void events() throws Exception {
    Filepath root = temp();
    AtomicLong clock = new AtomicLong(NOV);
    ActivityLog t = new ActivityLog(root, clock::get, m -> { });
    ActivityEvents e = new ActivityEvents(clock::get);
    e.start(t);
    Suggestion whip = EviLiveSuggestionTest.suggestion(4151, "buy", 3, 1000, 1200);
    e.shown(whip, null, new JsonObject(), null, 5000, 7); // before a session: no account, no line
    e.session(ACCT, "S", "login", "4.0.1", true, obj("pace", "OVERNIGHT"));
    e.offer(null, offer("old-1", "SELLING", 561, 200, 100, 40, 8000), false, null, 1); // standing at login
    e.offer(null, emptySlot(), false, null, 1);
    JsonObject sj = new JsonObject();
    sj.addProperty("itemId", 4151);
    e.shown(whip, null, sj, obj("low", "1000"), 5000000, 9);
    e.shown(whip, null, new JsonObject(), null, 5000000, 10); // the same pick at the next poll: no line
    e.settings(obj("pace", "OVERNIGHT"));                     // unchanged: no line
    e.settings(obj("pace", "FAST"));
    // 1. hotkey: the price was filled from the suggestion, then an offer for that item appears (at another quantity).
    e.assist(whip, "price", 1000);
    clock.addAndGet(30_000);
    EviLivePlugin.Offer buy = offer("o-1", "BUYING", 4151, 1000, 2, 0, 0);
    e.offer(emptySlot(), buy, true, obj("low", "1000"), 20);
    EviLivePlugin.Offer part = offer("o-1", "BUYING", 4151, 1000, 2, 1, 990);
    e.offer(buy, part, true, null, 30);
    EviLivePlugin.Offer done = offer("o-1", "BOUGHT", 4151, 1000, 2, 2, 1985);
    e.offer(part, done, true, null, 40);
    e.offer(done, done, true, null, 41); // nothing changed: no line
    e.offer(done, emptySlot(), true, null, 50);
    // 2. a different pick replaces the first; skipping it ends it as skipped.
    Suggestion rune = EviLiveSuggestionTest.suggestion(561, "buy", 100, 200, 210);
    e.shown(rune, java.util.List.of(EviLiveSuggestionTest.suggestion(563, "buy", 50, 300, 320)), new JsonObject(), null, -1, -1);
    clock.addAndGet(10 * 60_000); // the hotkey fill is long past
    e.offer(emptySlot(), offer("o-2", "BUYING", 561, 200, 100, 0, 0), true, null, 60);  // exact
    e.offer(emptySlot(), offer("o-3", "BUYING", 563, 305, 50, 0, 0), true, null, 61);   // an extra, at another price: item
    e.offer(emptySlot(), offer("o-4", "BUYING", 9999, 5, 1, 0, 0), true, null, 62);     // nothing EVI suggested: none
    e.offer(emptySlot(), offer("o-5", "SELLING", 561, 210, 100, 0, 0), true, null, 63); // a SELL of a suggested BUY item: none
    e.offer(offer("o-5", "SELLING", 561, 210, 100, 0, 0), offer("o-5", "CANCELLED_SELL", 561, 210, 100, 0, 0), true, null, 64);
    e.action("skip", rune);
    e.gone("skipped");
    e.gone("cleared"); // already ended: no second line
    e.empty("none");
    e.empty("none");   // unchanged: no line
    e.empty("slots-full");
    e.action("promote-extra", 563);
    e.alive();
    e.shown(whip, null, new JsonObject(), null, -1, -1);
    e.sessionEnd("LOGIN_SCREEN");
    e.alive();         // after the session ended: no account, no line
    e.sessionEnd("reset");
    check(t.awaitIdle(20000), "drain");
    List<JsonObject> all = lines(file(root, "activity-acct-1-2026-11.jsonl"));
    List<String> types = new ArrayList<>();
    for (JsonObject o : all) types.add(o.get("type").getAsString() + (o.has("ev") ? ":" + o.get("ev").getAsString() : ""));
    List<String> want = java.util.List.of("session", "offer:placed", "suggestion", "settings", "assist", "offer:placed", "offer:fill", "offer:fill", "offer:done",
      "offer:collected", "suggestion-end", "suggestion", "offer:placed", "offer:placed", "offer:placed", "offer:placed", "offer:cancelled", "action",
      "suggestion-end", "empty", "empty", "action", "alive", "suggestion", "suggestion-end", "session-end");
    check(types.equals(want), "the records, in order:\n got  " + types + "\n want " + want);
    JsonObject session = all.get(0);
    check("login".equals(session.get("reason").getAsString()) && "4.0.1".equals(session.get("plugin").getAsString()) && session.get("members").getAsBoolean()
      && ACCT.equals(session.getAsJsonObject("p").get("account").getAsString()) && "OVERNIGHT".equals(session.getAsJsonObject("settings").get("pace").getAsString()),
      "the session line: " + session);
    JsonObject standing = all.get(1);
    check("at-login".equals(standing.get("observed").getAsString()) && !standing.has("link") && standing.get("filled").getAsInt() == 40,
      "an offer standing at login is said to be seen at login and is linked to nothing: " + standing);
    JsonObject sug = all.get(2);
    check("S-1".equals(sug.get("id").getAsString()) && sug.get("itemId").getAsInt() == 4151 && sug.get("tick").getAsInt() == 9
      && sug.getAsJsonObject("p").get("cash").getAsLong() == 5000000 && sug.has("mkt"), "the suggestion line: " + sug);
    check("S-1".equals(all.get(4).get("id").getAsString()) && "price".equals(all.get(4).get("field").getAsString()), "the hotkey fill names the suggestion: " + all.get(4));
    JsonObject placed = all.get(5);
    check("hotkey".equals(placed.get("link").getAsString()) && "S-1".equals(placed.get("sug").getAsString()) && placed.get("dPrice").getAsLong() == 0
      && placed.get("dQty").getAsLong() == -1 && "live".equals(placed.get("observed").getAsString()) && "buy".equals(placed.get("side").getAsString())
      && placed.has("mkt") && "session".equals(placed.get("oidSource").getAsString()),
      "an offer placed after a hotkey fill is linked by hotkey, with how it differs from the suggestion: " + placed);
    check(all.get(6).get("dFilled").getAsLong() == 1 && all.get(6).get("dSpent").getAsLong() == 990 && all.get(7).get("dFilled").getAsLong() == 1
      && all.get(7).get("dSpent").getAsLong() == 995, "fills are deltas: " + all.get(6) + " " + all.get(7));
    check("BOUGHT".equals(all.get(8).get("state").getAsString()) && "o-1".equals(all.get(9).get("oid").getAsString()), "done, then collected");
    check("S-1".equals(all.get(10).get("id").getAsString()) && "replaced".equals(all.get(10).get("why").getAsString()), "the first pick ends as replaced: " + all.get(10));
    check("S-2".equals(all.get(11).get("id").getAsString()) && all.get(11).getAsJsonArray("extras").size() == 1 && !all.get(11).has("p"),
      "the second pick: a new id, its extras, and no cash when cash is unknown: " + all.get(11));
    check("exact".equals(all.get(12).get("link").getAsString()) && "S-2".equals(all.get(12).get("sug").getAsString()), "same price and quantity, no hotkey: exact: " + all.get(12));
    check("item".equals(all.get(13).get("link").getAsString()) && all.get(13).get("dPrice").getAsLong() == 5 && !all.get(13).has("sug"),
      "an extra bought at another price: item, with the difference, and no id it does not have: " + all.get(13));
    check("none".equals(all.get(14).get("link").getAsString()) && !all.get(14).has("dPrice"), "an item EVI did not suggest: none: " + all.get(14));
    check("none".equals(all.get(15).get("link").getAsString()), "a SELL of an item suggested as a BUY is not that suggestion: " + all.get(15));
    check("skip".equals(all.get(17).get("kind").getAsString()) && "S-2".equals(all.get(17).get("id").getAsString())
      && "skipped".equals(all.get(18).get("why").getAsString()), "the press and the reason the pick left: " + all.get(17) + all.get(18));
    check("none".equals(all.get(19).get("state").getAsString()) && "slots-full".equals(all.get(20).get("state").getAsString()), "the empty state, only when it changes");
    check(all.get(21).get("item").getAsInt() == 563, "a press on an extra names its item: " + all.get(21));
    check("session-end".equals(all.get(24).get("why").getAsString()) && "LOGIN_SCREEN".equals(all.get(25).get("reason").getAsString()),
      "a logout ends the pick on screen, then the session, once: " + all.get(24) + all.get(25));
    // The pure link rule.
    check("none".equals(ActivityEvents.link(true, 4151, 1000, 3, null, true)) && "hotkey".equals(ActivityEvents.link(true, 4151, 999, 1, whip, true))
      && "exact".equals(ActivityEvents.link(true, 4151, 1000, 3, whip, false)) && "item".equals(ActivityEvents.link(true, 4151, 1000, 2, whip, false))
      && "none".equals(ActivityEvents.link(false, 4151, 1200, 3, whip, true)), "the link rule");
    e.stop();
    e.session(ACCT, "S2", "login", null, null, null); // stopped: nothing is written, nothing thrown
    t.shutdown();
    root.deleteRecursively();
  }
}
