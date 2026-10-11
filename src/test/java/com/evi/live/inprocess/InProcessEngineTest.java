package com.evi.live.inprocess;

import com.evi.live.TestFiles;
import com.evi.live.inprocess.EngineFeed;
import com.evi.live.inprocess.InProcessEngine;
import com.evi.live.journal.PluginJournal;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.MarketAggregates;
import com.evi.live.market.WikiJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.util.Filepath;

/**
 * THE IN-PROCESS ENGINE (com.evi.live.inprocess), which answers the sidebar. Uses {@link EngineRig}'s transcript rig (a REAL
 * {@link PluginJournal} fed the transcript's packets, and the transcript's market as the price layer would hold it).
 * <ol>
 *   <li>THE COMPOSITION REPRODUCES THE BRIDGE: with the price history complete, every poll of every transcript in the in-process
 *       scope, asked through the real engine thread, gives the body the real bridge sent (the one field the bridge never sends,
 *       {@code sellBreakEven}, aside -- and it is checked against the relist notes' own break-even);</li>
 *   <li>THE FIRST-RUN GATE: one hour short of the basis ({@link InProcessEngine#BASIS_HOURS}, the engine's own
 *       {@link MarketAggregates#ROBUST_PRICE_HOURS}) no poll offers a buy, a further position or a reachable figure; every
 *       poll whose bridge answer was a SELL still gets that very sell; the advice, prices, fill estimates, slots and profit
 *       line are the bridge's; at the basis the buys come back;</li>
 *   <li>the price-history line's words and numbers, ASCII; the query without the buy tiers;</li>
 *   <li>the thread: the hand-over returns at once, a newer query replaces a waiting one, the work refuses the client thread
 *       and the EDT, decide runs on evi-engine only, and a shut-down engine answers nothing.</li>
 * </ol>
 */
public final class InProcessEngineTest {
  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    pure();
    int[] open = transcripts(true, false);
    int[] openSeries = transcripts(true, true);
    int[] closed = transcripts(false, true);
    // the whole basis, but the latest prices one millisecond past STALE_PRICES_MS old: buys held back exactly as one hour short
    staleRun = true;
    int[] stale = transcripts(false, true);
    staleRun = false;
    check(stale[2] > 0 && stale[2] == closed[2] && stale[3] == closed[3], "stale prices withhold the same buys and keep the same sells as a short history: "
      + stale[2] + "/" + stale[3] + " against " + closed[2] + "/" + closed[3]);
    threads();
    int readHours = com.evi.live.market.ReadHoursCheck.run(); // the price layer's archive door the engine reads through
    checks += readHours;
    System.out.println("PASS: in-process engine (" + checks + " checks, " + readHours + " of them the archive read) -- " + open[0] + " transcripts / " + open[1] + " polls through the real engine"
      + " thread AGREEING with the real bridge's body once the price history holds the basis (" + openSeries[0] + " / " + openSeries[1] + " whole, with"
      + " the bridge's recorded per-item series handed in, " + openSeries[2] + " of them buys); one hour short, " + closed[2] + " bridge buys withheld"
      + " (no buy, no further position, no reachable figure) while " + closed[3] + " sells still spoke as the bridge's; the price-history line, the"
      + " query without the buy tiers, the hand-over never blocking, newest query wins, never on the client thread or the EDT");
  }

  // ------------------------------------------------------------------------------------------- 1. pure

  static EngineFeed.Market market(int stored, int window, int unavailable, boolean catchingUp, int readings) {
    return new EngineFeed.Market(null, 0, null, null, null, null, Long.MIN_VALUE, stored, window, 0, 0, 0, unavailable, catchingUp, readings);
  }

  static void pure() throws IOException {
    int basis = InProcessEngine.BASIS_HOURS;
    check(basis == MarketAggregates.ROBUST_PRICE_HOURS, "the gate is the engine's own 336-hour constant, not a new number");
    int window = basis + 1;
    check(!InProcessEngine.buysReady(null) && InProcessEngine.backfillLine(null) == null, "no market known: no gate line, no buys");
    check(!InProcessEngine.buysReady(market(0, window, 0, true, 0)), "an empty archive withholds buys");
    check(!InProcessEngine.buysReady(market(basis - 1, window, 0, true, basis - 1)), "one hour short withholds buys");
    check(InProcessEngine.buysReady(market(basis, window, 0, true, basis)), "the basis releases them");
    check(InProcessEngine.buysReady(market(window, window, 0, false, window)), "the whole window releases them");
    check(!InProcessEngine.buysReady(market(window, window, 0, false, basis - 1)),
      "every hour downloaded but the readings not yet rebuilt over them: still withheld (the engine ranks on the READINGS)");
    check(("Loading price history: 140 of " + basis + " hours. Buy suggestions start when it's done.").equals(InProcessEngine.backfillLine(market(140, window, 0, true, 120))),
      "the progress line: " + InProcessEngine.backfillLine(market(140, window, 0, true, 120)));
    check(("Loading price history: 0 of " + basis + " hours. Buy suggestions start when it's done.").equals(InProcessEngine.backfillLine(market(0, window, 0, true, 0))),
      "a first run starts at 0");
    check(("Loading price history: " + basis + " of " + basis + " hours. Buy suggestions start when it's done.").equals(InProcessEngine.backfillLine(market(window, window, 0, false, 100))),
      "downloaded but not rebuilt: never more than the basis");
    check(InProcessEngine.backfillLine(market(basis, window, 0, false, basis)) == null, "no line once buys are released");
    String gaveUp = InProcessEngine.backfillLine(market(300, window, 37, false, 300));
    check(gaveUp != null && gaveUp.startsWith("Price history: 300 of " + basis + " hours.") && gaveUp.contains("next starts")
      && gaveUp.endsWith("Buy suggestions start once it is complete."), "hours the Wiki would not send: said so, with the retry: " + gaveUp);
    for (String l : new String[]{InProcessEngine.backfillLine(market(140, window, 0, true, 120)), gaveUp})
      for (char c : l.toCharArray()) check(c >= 32 && c < 127, "ASCII only: " + l);
    // the query without the buy tiers
    check(InProcessEngine.withoutBuyTiers("").equals("source=market"), "an empty query");
    check(InProcessEngine.withoutBuyTiers("minProfit=500&includeMarket=1&account=a&source=both&cash=5")
      .equals("minProfit=500&account=a&cash=5&source=market"), InProcessEngine.withoutBuyTiers("minProfit=500&includeMarket=1&account=a&source=both&cash=5"));
    check(InProcessEngine.withoutBuyTiers("includeMarket=1&includeMarket=1&source=market&source=history").equals("source=market"), "repeated names all go");
    check(InProcessEngine.withoutBuyTiers("include%4Darket=1&sourc%65=history&x=1").equals("x=1&source=market"),
      "names compared as the engine DECODES them (URLSearchParams): " + InProcessEngine.withoutBuyTiers("include%4Darket=1&sourc%65=history&x=1"));
    check(InProcessEngine.withoutBuyTiers("myincludeMarket=1&holdItemId=5").equals("myincludeMarket=1&holdItemId=5&source=market"), "only those two names");
    thinIndexAfterCatchUp();
    // STALE PRICES (review, 10 Oct): past STALE_PRICES_MS buys wait for fresh ones, with a line saying how old they are
    long fetched = 1_000_000_000L;
    check(InProcessEngine.STALE_PRICES_MS == 10 * 60_000L, "the maintainer's ten minutes");
    check(!InProcessEngine.pricesStale(fetched, fetched + InProcessEngine.STALE_PRICES_MS), "exactly ten minutes old: still quoted");
    check(InProcessEngine.pricesStale(fetched, fetched + InProcessEngine.STALE_PRICES_MS + 1), "past ten minutes: stale");
    check(!InProcessEngine.pricesStale(0, fetched), "no prices at all is not 'stale' (nothing is quoted; the waiting line speaks)");
    String staleLine = InProcessEngine.staleLine(fetched, fetched + 25 * 60_000L + 59_000L);
    check(("The latest prices from the OSRS Wiki are 25 minutes old. Buy suggestions are paused until fresh prices arrive (they load"
      + " while you are logged in).").equals(staleLine), "the stale line: " + staleLine);
    for (char c : staleLine.toCharArray()) check(c >= 32 && c < 127, "ASCII only: " + staleLine);
  }

  /**
   * THE THIN INDEX AFTER A CATCH-UP (10 Oct): built while the archive was still catching up it misses the older hours
   * of the gap; it is built once more when the catch-up ends, and otherwise kept for the bridge's six hours.
   */
  static void thinIndexAfterCatchUp() throws IOException {
    AtomicLong stored = new AtomicLong(330);
    AtomicReference<Boolean> catching = new AtomicReference<>(true);
    AtomicLong reads = new AtomicLong();
    EngineFeed feed = new EngineFeed(new EngineFeed.MarketSource() {
      @Override public EngineFeed.Market market(long nowMs) {
        return null;
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        reads.incrementAndGet();
        List<HourBucket> hours = new ArrayList<>();
        try {
          for (int i = 0; i < stored.get(); i++) hours.add(HourBucket.parseLine("{\"ts\":" + (3600L * (1000 + i)) + ",\"d\":{\"2\":[5,10,4,10]}}"));
        } catch (IOException e) {
          throw new AssertionError(e);
        }
        return CompletableFuture.completedFuture(hours);
      }
    }, 5_000);
    long t0 = 1_000_000_000L;
    feed.refresh(market(330, 337, 0, catching.get(), 330), t0, new JsonObject());
    check(feed.thin() != null && feed.thin().hours == 330, "built mid catch-up on the hours stored then");
    stored.set(334);
    feed.refresh(market(334, 337, 0, true, 334), t0 + 60_000, new JsonObject());
    check(feed.thin().hours == 330, "still catching up: kept, not rebuilt on every poll");
    feed.refresh(market(337, 337, 0, false, 337), t0 + 120_000, new JsonObject());
    long readsAtEnd = reads.get();
    check(feed.thin().hours == 334, "the catch-up ended: built once more over the whole archive, got " + feed.thin().hours);
    stored.set(336);
    feed.refresh(market(337, 337, 0, false, 337), t0 + 180_000, new JsonObject());
    check(feed.thin().hours == 334, "built on a complete archive: kept for the six hours as the bridge keeps it");
    feed.refresh(market(337, 337, 0, false, 337), t0 + 120_000 + EngineFeed.THIN_EVERY_MS, new JsonObject());
    check(feed.thin().hours == 336, "and rebuilt when the six hours are up");
    check(reads.get() >= readsAtEnd + 1, "the rebuilds read the archive");
    // a complete archive from the start: the old clock alone
    EngineFeed whole = new EngineFeed(new EngineFeed.MarketSource() {
      @Override public EngineFeed.Market market(long nowMs) {
        return null;
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        List<HourBucket> hours = new ArrayList<>();
        try {
          for (int i = 0; i < stored.get(); i++) hours.add(HourBucket.parseLine("{\"ts\":" + (3600L * (1000 + i)) + ",\"d\":{\"2\":[5,10,4,10]}}"));
        } catch (IOException e) {
          throw new AssertionError(e);
        }
        return CompletableFuture.completedFuture(hours);
      }
    }, 5_000);
    whole.refresh(market(337, 337, 0, false, 337), t0, new JsonObject());
    stored.set(300);
    whole.refresh(market(337, 337, 0, false, 337), t0 + 60_000, new JsonObject());
    check(whole.thin().hours == 336, "never catching up: unchanged behaviour, kept between polls");
  }
  // ------------------------------------------------------------------------------------------- 2. the transcripts

  /** The rig's market with the backfill state the gate reads: {@code readings} stored hours behind the built readings. */
  static EngineFeed.MarketSource withHistory(EngineRig.TestMarket t, int readings) {
    int window = InProcessEngine.BASIS_HOURS + 1;
    return new EngineFeed.MarketSource() {
      @Override public EngineFeed.Market market(long nowMs) {
        EngineFeed.Market m = t.market(nowMs);
        long fetched = staleRun ? nowMs - InProcessEngine.STALE_PRICES_MS - 1 : m.latestFetchedAtMs;
        return new EngineFeed.Market(m.latest, fetched, m.latestHour, m.catalog, m.typical, m.robust, m.newestStoredTs, readings, window,
          m.robustHours, m.typicalHours, m.archiveRevision, 0, readings < window, readings);
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        return t.hoursFrom(fromTs);
      }
    };
  }

  static boolean isBuy(JsonElement s) {
    return s != null && s.isJsonObject() && s.getAsJsonObject().has("action") && "buy".equals(s.getAsJsonObject().get("action").getAsString());
  }

  static boolean isSell(JsonElement s) {
    return s != null && s.isJsonObject() && s.getAsJsonObject().has("action") && "sell".equals(s.getAsJsonObject().get("action").getAsString());
  }

  static JsonObject without(JsonObject body, String... keys) {
    JsonObject o = body.deepCopy();
    for (String k : keys) o.remove(k);
    return o;
  }

  /**
   * Every in-scope transcript through the real engine thread and a real plugin journal. {@code complete}: the readings rest on
   * the basis (every body must agree with the bridge's); else one hour short (buys withheld, sells and advice kept).
   * Returns {transcripts, polls, buys withheld, sells kept}.
   */
  /** While set, {@link #withHistory} reports the latest prices as just past {@link InProcessEngine#STALE_PRICES_MS} old, and
   *  {@link #transcripts} stores the WHOLE basis while expecting buys withheld (review, 10 Oct). */
  static boolean staleRun;

  static int[] transcripts(boolean complete, boolean withSeries) throws Exception {
    int transcripts = 0, polls = 0, withheld = 0, sellsKept = 0, breakEvens = 0, buys = 0;
    for (String name : EngineRig.index()) {
      JsonObject t = EngineRig.transcript(name);
      if (EngineRig.outOfScope(t) != null) continue;
      // Production asks no per-item series: compared only up to the bridge's first one. With the recorded series handed in
      // (tests only), the whole transcript.
      long cutoff = withSeries ? Long.MAX_VALUE : EngineRig.firstSeries(t);
      if (EngineRig.pollsBefore(t, cutoff) == 0) continue;
      transcripts++;
      int upTo = EngineRig.stepBefore(t, cutoff);
      Filepath root = TestFiles.tempDir("evi-inprocess-test");
      AtomicLong clock = new AtomicLong();
      List<String> logged = Collections.synchronizedList(new ArrayList<>());
      JsonObject boot = t.getAsJsonObject("input").getAsJsonObject("boot");
      JsonElement prefs = boot.get("preferences");
      if (prefs != null && !prefs.isJsonNull()) root.joinSegment(InProcessEngine.PREFERENCES).write(prefs.toString().getBytes(StandardCharsets.UTF_8));
      PluginJournal journal = new PluginJournal(root, clock::get, EngineRig.PARSER, () -> false, logged::add);
      journal.start();
      List<HourBucket> archive = null;
      JsonElement a = boot.get("archive1h");
      if (a != null && a.isJsonArray()) {
        archive = new ArrayList<>();
        for (JsonElement b : a.getAsJsonArray()) archive.add(HourBucket.parseLine(b.toString()));
      }
      EngineRig.TestMarket tm = new EngineRig.TestMarket(archive, t.getAsJsonArray("wiki"));
      AtomicReference<Long> since = new AtomicReference<>();
      List<String> journalThreads = Collections.synchronizedList(new ArrayList<>());
      EngineFeed.JournalSource real = EngineFeed.journalOf(() -> journal);
      InProcessEngine engine = new InProcessEngine(root, now -> {
        journalThreads.add(Thread.currentThread().getName());
        return real.view(now);
      }, withHistory(tm, complete || staleRun ? InProcessEngine.BASIS_HOURS : InProcessEngine.BASIS_HOURS - 1), EngineRig.PARSER, logged::add, () -> false, since::get,
        withSeries ? com.evi.live.engine.RecordedSeries.of(id -> tm.body(API + "timeseries?id=" + id + "&timestep=1h")) : null);
      try {
        for (JsonElement se : t.getAsJsonArray("steps")) {
          JsonObject s = se.getAsJsonObject();
          if (s.get("i").getAsInt() >= upTo) break;
          if (s.has("store1h")) {
            List<HourBucket> hours = new ArrayList<>();
            for (JsonElement b : s.getAsJsonArray("store1h")) hours.add(HourBucket.parseLine(b.toString()));
            tm.store(hours);
            continue;
          }
          if (!s.has("request")) continue;
          JsonObject r = s.getAsJsonObject("request");
          long at = s.get("at").getAsLong();
          if (r.get("method").getAsString().equals("POST")) {
            clock.set(at);
            journal.offerPacket(r.get("body").toString());
            continue;
          }
          if (!r.get("path").getAsString().equals("/api/suggestion")) continue;
          tm.at = at;
          JsonObject bridge = s.getAsJsonObject("response").getAsJsonObject("body");
          // the plugin keeps the profit reset itself in this mode; the transcripts in scope never press Reset
          JsonElement p = bridge.get("profit");
          since.set(p != null && p.isJsonObject() && p.getAsJsonObject().get("since") != null && !p.getAsJsonObject().get("since").isJsonNull()
            ? p.getAsJsonObject().get("since").getAsLong() : null);
          String query = r.get("query").getAsString();
          InProcessEngine.Answer ans = engine.request(query, at).get(60, TimeUnit.SECONDS);
          String where = name + " step " + s.get("i").getAsInt() + (complete ? " (history complete)" : " (one hour short)");
          check(ans != null && ans.body != null, where + ": no body (" + (ans == null ? "replaced" : ans.unavailable) + ")");
          check(ans.buysReady == complete, where + ": buysReady");
          check(complete == (ans.backfillLine == null), where + ": the price-history line is shown exactly while buys wait: " + ans.backfillLine);
          check(ans.pricesStale == staleRun, where + ": pricesStale");
          if (staleRun) check(ans.backfillLine.startsWith("The latest prices from the OSRS Wiki are 10 minutes old."), where + ": the stale line: " + ans.backfillLine);
          JsonObject plugin = EngineRig.j(ans.body).getAsJsonObject();
          check(plugin.has("sellBreakEven") && plugin.get("sellBreakEven").isJsonObject(), where + ": the one-voice break-evens are sent");
          polls++;
          if (isBuy(bridge.get("suggestion"))) buys++;
          if (complete) {
            List<BodyDiff.Difference> d = BodyDiff.diff(bridge, without(plugin, "sellBreakEven"));
            check(d.isEmpty(), where + ": the in-process body differs from the bridge's: " + d.subList(0, Math.min(3, d.size())));
            // the break-even the plugin will compare with is the relist advice's own
            for (JsonElement n : bridge.getAsJsonArray("relistAdvice")) {
              JsonObject o = n.getAsJsonObject();
              if (!o.has("offerPrice") || !o.has("breakEven") || o.get("breakEven").isJsonNull()) continue;
              JsonElement be = plugin.getAsJsonObject("sellBreakEven").get(o.get("itemId").getAsString());
              check(be != null && be.getAsLong() == o.get("breakEven").getAsLong(), where + ": sellBreakEven for " + o.get("itemId") + " is not the relist's " + o.get("breakEven"));
              breakEvens++;
            }
          } else {
            check(!isBuy(plugin.get("suggestion")), where + ": a BUY was offered one hour short of the basis");
            check(plugin.getAsJsonArray("additional").size() == 0, where + ": a further position was offered one hour short");
            check(BodyDiff.isNullish(plugin.get("reachable")), where + ": a reachable figure one hour short");
            if (isBuy(bridge.get("suggestion"))) withheld++;
            if (isSell(bridge.get("suggestion"))) {
              sellsKept++;
              JsonObject bs = new JsonObject(), ps = new JsonObject(); // wrapped, so the suggestion log's id/accepted stay ignored
              bs.add("suggestion", bridge.get("suggestion"));
              ps.add("suggestion", plugin.get("suggestion"));
              List<BodyDiff.Difference> d = BodyDiff.diff(bs, ps);
              check(d.isEmpty(), where + ": the bridge's SELL did not speak one hour short: " + d);
            }
            // everything about what the player already has is the bridge's (the advice when no buy shaped it)
            String[] buySide = isBuy(bridge.get("suggestion"))
              ? new String[]{"suggestion", "additional", "reachable", "heldBack", "fellThroughFromHistory", "relistAdvice", "sellBreakEven"}
              : new String[]{"suggestion", "additional", "reachable", "heldBack", "fellThroughFromHistory", "sellBreakEven"};
            List<BodyDiff.Difference> d = BodyDiff.diff(without(bridge, buySide), without(plugin, buySide));
            check(d.isEmpty(), where + ": prices, fill estimates, slots, profit or advice differ one hour short: " + d.subList(0, Math.min(3, d.size())));
          }
        }
        check(engine.decisions() > 0 && InProcessEngine.THREAD_NAME.equals(engine.lastDecidedOn()), name + ": decide ran on " + engine.lastDecidedOn());
        for (String th : journalThreads) check(InProcessEngine.THREAD_NAME.equals(th), name + ": the journal was asked from '" + th + "'");
        for (String th : tm.threads) check(InProcessEngine.THREAD_NAME.equals(th), name + ": the market was read from '" + th + "'");
      } finally {
        engine.shutdown();
        journal.shutdown();
        journal.awaitIdle(30_000);
        root.deleteRecursively();
      }
    }
    check(transcripts >= 40 && polls >= 70, "too few transcripts in scope: " + transcripts + " / " + polls);
    if (complete) check(breakEvens >= 1, "no relist note with a break-even was compared: " + breakEvens);
    if (complete && withSeries) check(buys >= 10, "too few bridge buys reproduced to say the buy tiers run: " + buys);
    if (!complete) check(withheld >= 10 && sellsKept >= 10, "too few withheld buys (" + withheld + ") or kept sells (" + sellsKept + ") to say anything");
    return new int[]{transcripts, polls, complete ? buys : withheld, sellsKept};
  }

  // ------------------------------------------------------------------------------------------- 3. the thread

  static final String API = "https://prices.runescape.wiki/api/v1/osrs/";
  static final String LATEST = "{\"data\":{\"554\":{\"high\":6,\"highTime\":1,\"low\":5,\"lowTime\":1}}}";
  static final String MAPPING = "[{\"id\":554,\"name\":\"Fire rune\",\"members\":false,\"limit\":25000}]";

  static EngineFeed.MarketSource tiny(int readings) throws Exception {
    EngineFeed.Market m = new EngineFeed.Market(WikiJson.latest(LATEST), 1_000, null, ItemCatalog.parse(MAPPING), null, null, Long.MIN_VALUE, readings,
      InProcessEngine.BASIS_HOURS + 1, 0, 0, 1, 0, false, readings);
    return new EngineFeed.MarketSource() {
      @Override public EngineFeed.Market market(long nowMs) {
        return m;
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        return CompletableFuture.completedFuture(new ArrayList<>());
      }
    };
  }

  static void threads() throws Exception {
    Filepath root = TestFiles.tempDir("evi-inprocess-threads");
    try {
      // the hand-over returns at once while the engine waits on a journal that has not answered; a newer query replaces a waiting one
      CompletableFuture<PluginJournal.EngineView> held = new CompletableFuture<>();
      List<String> logged = Collections.synchronizedList(new ArrayList<>());
      InProcessEngine e = new InProcessEngine(root, now -> held, tiny(0), EngineRig.PARSER, logged::add, () -> false, () -> null);
      long t0 = System.nanoTime();
      CompletableFuture<InProcessEngine.Answer> first = e.request("account=a", 1);
      CompletableFuture<InProcessEngine.Answer> second = e.request("account=a&x=2", 2);
      CompletableFuture<InProcessEngine.Answer> third = e.request("account=a&x=3", 3);
      check((System.nanoTime() - t0) / 1_000_000 < 500, "the hand-over must return at once");
      check(second.get(5, TimeUnit.SECONDS) == null, "a waiting query is replaced by a newer one (its future completes with null)");
      check(!first.isDone() && !third.isDone(), "the running one is still waiting for the journal");
      held.complete(null); // a journal that has stopped
      InProcessEngine.Answer a1 = first.get(10, TimeUnit.SECONDS), a3 = third.get(10, TimeUnit.SECONDS);
      check(a1 != null && a1.body == null && a1.unavailable.contains("trade record"), "a stopped journal: a plain sentence, no body: " + (a1 == null ? null : a1.unavailable));
      check(a3 != null && "account=a&x=3".equals(a3.query), "the newest query was answered after the running one");
      check(e.decisions() == 0, "decide never ran without a journal");
      check(("Loading price history: 0 of " + InProcessEngine.BASIS_HOURS + " hours. Buy suggestions start when it's done.").equals(a1.backfillLine),
        "the line travels with an answer that has no body too: " + a1.backfillLine);
      e.shutdown();
      check(e.awaitTermination(10_000), "shut down");
      CompletableFuture<InProcessEngine.Answer> late = e.request("account=a", 4);
      check(late.isDone() && late.get() == null, "a shut-down engine answers nothing");

      // the client thread and the EDT are refused: no decide, a plain sentence
      InProcessEngine refusing = new InProcessEngine(root, now -> held, tiny(InProcessEngine.BASIS_HOURS), EngineRig.PARSER, logged::add, () -> true,
        () -> null);
      InProcessEngine.Answer r = refusing.request("account=a", 5).get(10, TimeUnit.SECONDS);
      check(r != null && r.body == null && refusing.decisions() == 0, "work on the client thread / EDT must be refused");
      check(logged.stream().anyMatch(l -> l.contains("not " + InProcessEngine.THREAD_NAME)), "the refusal is logged: " + logged);
      refusing.shutdown();

      // no price data yet: no decide, the reason said plainly
      InProcessEngine noPrices = new InProcessEngine(root, now -> held, new EngineFeed.MarketSource() {
        @Override public EngineFeed.Market market(long nowMs) {
          return new EngineFeed.Market(null, 0, null, null, null, null, Long.MIN_VALUE, 3, InProcessEngine.BASIS_HOURS + 1, 0, 0, 0, 0, true, 0);
        }

        @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
          return CompletableFuture.completedFuture(new ArrayList<>());
        }
      }, EngineRig.PARSER, logged::add, () -> false, () -> null);
      InProcessEngine.Answer np = noPrices.request("account=a", 6).get(10, TimeUnit.SECONDS);
      check(np != null && np.body == null && np.unavailable.startsWith("Loading the item list") && noPrices.decisions() == 0, "no item list yet: " + np.unavailable);
      noPrices.shutdown();
    } finally {
      root.deleteRecursively();
    }
  }
}
