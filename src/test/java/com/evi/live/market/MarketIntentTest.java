package com.evi.live.market;

import com.evi.live.TestFiles;
import static com.evi.live.market.MarketTestSupport.check;

import com.evi.live.EviLiveConfig;
import com.evi.live.EviLivePlugin;
import com.evi.live.market.MarketTestSupport.Answer;
import com.evi.live.market.MarketTestSupport.FakeWiki;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.util.Filepath;

/**
 * The price-data layer's rules written as INTENT, each asserting a value (what was sent, stored, scheduled or shown),
 * never a restatement of the code. Recorded responses only: no request leaves this machine.
 */
public final class MarketIntentTest {
  /** 1 Oct 2026 12:10:00 UTC: the newest closed hour is then 11:00 (asked for at 12:05 or later). */
  static final long T0 = 1790856600000L;
  static final long H = 3600;
  static final String UA = "EVI-Live/" + WikiPriceClient.VERSION + " (RuneLite plugin; %s; +https://github.com/therealLeEvi/evi-live-plugin)";

  public static void main(String[] args) throws Exception {
    disclosure();
    oneNetworkClass();
    rounding();
    bucketLine();
    filesNeverReplaced();
    twoClients();
    archiveRepairAndPrune();
    wikiClient();
    scheduler();
    backoffAndOddAnswers();
    restartRepairsOnlyTheGap();
    diskFailureBacksOff();
    backoffPerKind();
    mappingRefusedDoesNotBlock();
    catchingUpIgnoresGivenUpHours();
    wallClockSetBack();
    monotonicStamps();
    serviceHandover();
    autoScheduledAndShutdown();
    pluginWiring();
    pluginToggle();
    neverUnstampedHour();
    System.out.println("MARKET INTENT PASS: " + MarketTestSupport.checks + " checks -- half-up rounding, the archive line, files never moved over (even "
      + "while open), two clients on one hour, damaged files refetched, pruning, the one network class (URLs, User-Agent, no player data, "
      + "no cache, no redirects, never on the client thread or EDT, one in flight, size cap), the scheduler (mapping, latest only while logged in, "
      + "newest hour first, 2.5 s spacing, back-off per kind, an item list that will not come, skipped hours counted as unavailable, empty and "
      + "odd answers, another client's files, retention), restart gap repair, the computer clock set back (spacing on the monotonic clock), the next-hour wake-up and a failed rebuild stamped monotonically, orderly shutdown with a request in flight, off/on handover "
      + "(never two requests, spacing kept), the plugin's wiring, the disclosure text tied to the code, Wiki traffic in one class only and to prices.runescape.wiki only, the /1h only ever by timestamp, and no loopback address (127.0.0.1, localhost, 51743, ::1) anywhere in the plugin's sources");
  }

  // ------------------------------------------------------------------------------------------- rounding

  static void rounding() throws Exception {
    String[] in = {"102.5", "102.49", "102.51", "0.5", "1.5", "2.5", "-2.5", "-2.51", "295.07", "295.0", "3000000000.5", "1e3", "2147483647.5", "7"};
    long[] want = {103, 102, 103, 1, 2, 3, -2, -3, 295, 295, 3000000001L, 1000, 2147483648L, 7};
    for (int i = 0; i < in.length; i++)
      check(WikiJson.rounded(in[i]) == want[i], "half-up rounding of " + in[i] + " gave " + WikiJson.rounded(in[i]) + ", JavaScript Math.round gives " + want[i]);
    // Every .5 tie from 0.5 to 999.5 goes UP -- the case where Python's (and HALF_EVEN's) answer differs every other time.
    for (int k = 0; k < 1000; k++) check(WikiJson.rounded(k + ".5") == k + 1, "the tie " + k + ".5 must round up to " + (k + 1));
    // Beyond 2^53 JavaScript keeps the nearest double: Math.round(9007199254740993) is 9007199254740992 there. Both of
    // Java's paths (the text one and Gson's whole-number one) must give the same; 2^53 - 1 itself stays exact.
    String[] big = {"9007199254740993", "9007199254740991", "-9007199254740993", "9007199254740995"};
    long[] js = {9007199254740992L, 9007199254740991L, -9007199254740992L, 9007199254740996L};
    for (int i = 0; i < big.length; i++) {
      com.google.gson.stream.JsonReader r = new com.google.gson.stream.JsonReader(new java.io.StringReader("[" + big[i] + "]"));
      r.beginArray();
      long streamed = WikiJson.readRounded(r);
      check(WikiJson.rounded(big[i]) == js[i] && streamed == js[i], big[i] + " read as " + WikiJson.rounded(big[i]) + " / " + streamed
        + ", JavaScript holds " + js[i]);
    }
  }

  static void bucketLine() throws Exception {
    HourBucket b = WikiJson.hour("{\"timestamp\":1790812800,\"data\":{\"10\":{\"avgHighPrice\":295.07,\"highPriceVolume\":1,\"avgLowPrice\":null,"
      + "\"lowPriceVolume\":0},\"9\":{\"avgHighPrice\":0.5,\"highPriceVolume\":7},\"11\":null}}");
    check("{\"ts\":1790812800,\"d\":{\"9\":[1,7,null,0],\"10\":[295,1,null,0]}}\n".equals(b.line()),
      "the archive line must be the bridge's bytes (ids in numeric order, null kept, a null row skipped): " + b.line());
    check(b.equals(HourBucket.parseLine(b.line())), "a line must read back as the same bucket");
    check(WikiJson.hour("{\"data\":{\"2\":{\"avgHighPrice\":1}}}") == null, "a body with no timestamp is not an hour");
    LatestPrices l = WikiJson.latest(MarketTestSupport.LATEST_BODY);
    check(l.size() == 2 && l.get(2).high == 180 && l.get(2).low == 176 && l.get(2).lowTime == 1790000010L && l.get(561).low == HourBucket.NONE,
      "latest prices parsed wrong");
  }

  // ------------------------------------------------------------------------------------------- files

  static List<String> names(Filepath dir) throws IOException {
    return TestFiles.names(dir);
  }

  static void filesNeverReplaced() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-files");
    try {
      Filepath dir = root.joinSegment("x");
      dir.createDirectories();
      Filepath f = dir.joinSegment("hour-1.json.gz");
      check(ArchiveFiles.writeNew(f, "first".getBytes(StandardCharsets.UTF_8)), "a new file must be written");
      check(names(dir).equals(List.of("hour-1.json.gz")), "no temporary file may be left behind: " + names(dir));
      // The Windows lesson: never move over an existing file -- not even (especially) while another process reads it.
      try (FileChannel reader = f.openFileChannel(StandardOpenOption.READ)) {
        check(!ArchiveFiles.writeNew(f, "second".getBytes(StandardCharsets.UTF_8)), "writing over an existing file must be refused");
        check(reader.size() == 5, "the open reader's file changed");
      }
      check("first".equals(TestFiles.text(f)), "an existing file was replaced");
      check(names(dir).equals(List.of("hour-1.json.gz")), "a refused write left a temporary file: " + names(dir));

      // Two writers of the SAME file at the same moment: A is paused just before its rename while B writes and renames.
      // B must not touch A's temporary file, and A's late rename must change nothing.
      Filepath g = dir.joinSegment("hour-2.json.gz");
      CountDownLatch aReady = new CountDownLatch(1), bDone = new CountDownLatch(1);
      AtomicReference<List<String>> duringA = new AtomicReference<>();
      ArchiveFiles.moveSeam = target -> {
        if (!"writer-A".equals(Thread.currentThread().getName())) return;
        aReady.countDown();
        try {
          bDone.await(10, TimeUnit.SECONDS);
          duringA.set(names(dir));
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      };
      AtomicBoolean aWrote = new AtomicBoolean(true);
      Thread a = new Thread(() -> {
        try {
          aWrote.set(ArchiveFiles.writeNew(g, "from A".getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }, "writer-A");
      a.start();
      check(aReady.await(10, TimeUnit.SECONDS), "writer A did not reach its rename");
      check(ArchiveFiles.writeNew(g, "from B".getBytes(StandardCharsets.UTF_8)), "writer B must create the file");
      bDone.countDown();
      a.join(10000);
      ArchiveFiles.moveSeam = null;
      long tmpWhileA = duringA.get().stream().filter(n -> n.startsWith("hour-2.json.gz.") && n.endsWith(".tmp")).count();
      check(tmpWhileA == 1, "writer B removed or replaced writer A's temporary file (temporary files then: " + duringA.get() + ")");
      check(!aWrote.get(), "writer A's late rename must report the file was already there");
      check("from B".equals(TestFiles.text(g)), "the first rename must stand");
      check(names(dir).equals(List.of("hour-1.json.gz", "hour-2.json.gz")), "temporary files were left: " + names(dir));
    } finally {
      ArchiveFiles.moveSeam = null;
      MarketTestSupport.deleteTree(root);
    }
  }

  /** Two clients (two archives on one folder) storing the same hour at the same instant: one intact file. */
  static void twoClients() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-two");
    try {
      long ts = 1790812800L;
      HourBucket b = WikiJson.hour(MarketTestSupport.hourBody(ts, 400, true));
      HourlyArchive one = new HourlyArchive(root, m -> { }), two = new HourlyArchive(root, m -> { });
      for (int round = 0; round < 20; round++) {
        long t = ts + round * H;
        HourBucket x = WikiJson.hour(MarketTestSupport.hourBody(t, 400, true));
        CyclicBarrier go = new CyclicBarrier(2);
        List<Boolean> wrote = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Thread[] th = new Thread[2];
        HourlyArchive[] arch = {one, two};
        for (int i = 0; i < 2; i++) {
          HourlyArchive mine = arch[i];
          th[i] = new Thread(() -> {
            try {
              go.await();
              wrote.add(mine.store(x));
            } catch (Throwable e) {
              errors.add(e);
            }
          });
          th[i].start();
        }
        for (Thread t2 : th) t2.join(10000);
        check(errors.isEmpty(), "a client failed storing a shared hour: " + errors);
        check(wrote.stream().filter(w -> w).count() == 1, "exactly one client's write must land, round " + round + ": " + wrote);
        check(x.equals(one.read(t)) && x.equals(two.read(t)), "the shared hour must read back intact by both clients");
      }
      check(one.storedHours().size() == 20 && names(one.dir()).size() == 20, "twenty hours, twenty files, nothing else: " + names(one.dir()));
      check(b.size() == 400, "setup");
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  static void archiveRepairAndPrune() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-prune");
    List<String> log = new CopyOnWriteArrayList<>();
    try {
      HourlyArchive a = new HourlyArchive(root, log::add);
      long ts = 1790812800L;
      for (int i = 0; i < 6; i++) a.store(WikiJson.hour(MarketTestSupport.hourBody(ts + i * H, 3, false)));
      Filepath dir = a.dir();
      // A damaged file (not gzip) and one holding a different hour than its name: both removed, so they are fetched again.
      dir.joinSegment(HourlyArchive.dataName(ts + H)).write("not gzip".getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
      dir.joinSegment(HourlyArchive.dataName(ts + 2 * H)).write(HourlyArchive.gzip(WikiJson.hour(MarketTestSupport.hourBody(ts, 3, false)).line()),
        StandardOpenOption.TRUNCATE_EXISTING);
      check(a.read(ts + H) == null && !dir.joinSegment(HourlyArchive.dataName(ts + H)).exists(), "a damaged hour file must be removed");
      check(a.read(ts + 2 * H) == null && !dir.joinSegment(HourlyArchive.dataName(ts + 2 * H)).exists(), "a file holding the wrong hour must be removed");
      check(a.storedHours().equals(new java.util.TreeSet<>(List.of(ts, ts + 3 * H, ts + 4 * H, ts + 5 * H))), "stored hours after repair: " + a.storedHours());
      // Pruning: older data and markers go; a crash's old temporary file goes; a live write's fresh one stays.
      a.markEmpty(ts + 3 * H);
      Filepath oldTmp = dir.joinSegment(HourlyArchive.dataName(ts) + ".0123abcd-0123-4567-89ab-0123456789ab.tmp");
      Filepath freshTmp = dir.joinSegment(HourlyArchive.dataName(ts + 5 * H) + ".fedcba98-0123-4567-89ab-0123456789ab.tmp");
      oldTmp.write(new byte[3]);
      freshTmp.write(new byte[3]);
      long now = System.currentTimeMillis();
      int removed;
      ArchiveFiles.modifiedSeam = f -> f.equals(oldTmp) ? now - HourlyArchive.STALE_TMP_MS - 60_000 : now;
      try {
        removed = a.prune(ts + 4 * H, now);
      } finally {
        ArchiveFiles.modifiedSeam = null;
      }
      check(removed == 4, "prune must remove the two old hours, the old marker and the stale temporary file, removed " + removed + ": " + names(a.dir()));
      check(names(a.dir()).equals(List.of(HourlyArchive.dataName(ts + 4 * H), freshTmp.getFileName(), HourlyArchive.dataName(ts + 5 * H)).stream()
        .sorted().collect(Collectors.toList())), "after pruning: " + names(a.dir()));
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  // ------------------------------------------------------------------------------------------- the network class

  static Function<String, Answer> wikiAnswers() {
    return url -> {
      if (url.endsWith("/mapping")) return Answer.ok(MarketTestSupport.MAPPING_BODY);
      if (url.endsWith("/latest")) return Answer.ok(MarketTestSupport.LATEST_BODY);
      int i = url.indexOf("/1h?timestamp=");
      if (i >= 0) return Answer.ok(MarketTestSupport.hourBody(Long.parseLong(url.substring(i + 14)), 5, true));
      if (url.endsWith("/api/v2/osrs/1h")) return Answer.ok(MarketTestSupport.hourBody(1790812800L, 5, true)); // the shadow's unstamped hour
      return Answer.status(404);
    };
  }

  static void wikiClient() throws Exception {
    FakeWiki fake = new FakeWiki(wikiAnswers());
    AtomicBoolean onClientThread = new AtomicBoolean(false);
    // RuneLite's injected client carries a SHARED disk cache (20 MB, every plugin's) and follows redirects: stand in for
    // both, so the derivation is shown to turn them off rather than merely inheriting nothing.
    okhttp3.OkHttpClient injected = fake.injected().newBuilder().cache(new okhttp3.Cache(TestFiles.okHttpCacheDir("evi-okhttp-cache"), 1024 * 1024)).build();
    check(injected.cache() != null && injected.followRedirects(), "setup: the stand-in injected client has a cache and follows redirects");
    WikiPriceClient c = new WikiPriceClient(injected, onClientThread::get);
    check(c.http.cache() == null && !c.http.followRedirects() && !c.http.followSslRedirects() && c.http.callTimeoutMillis() == 20_000,
      "the derived client must have no cache, follow no redirects and limit each call to 20 s");

    c.latest();
    c.mapping();
    c.hour(1790812800L);
    check(fake.urls().equals(List.of("https://prices.runescape.wiki/api/v2/osrs/latest", "https://prices.runescape.wiki/api/v2/osrs/mapping",
      "https://prices.runescape.wiki/api/v2/osrs/1h?timestamp=1790812800")), "exactly these URLs, nothing else in them: " + fake.urls());
    String[] purposes = {"live prices", "item list", "hourly price history"};
    for (int i = 0; i < 3; i++) {
      okhttp3.Request r = fake.seen.get(i).request;
      check(r.headers().names().equals(java.util.Set.of("User-Agent")), "the only header EVI sets is its User-Agent: " + r.headers().names());
      check(String.format(UA, purposes[i]).equals(r.header("User-Agent")), "User-Agent was: " + r.header("User-Agent"));
      check("GET".equals(r.method()) && r.body() == null, "a GET with no body");
    }
    check(WikiPriceClient.userAgent("x").startsWith("EVI-Live/4.0.0 (RuneLite plugin; x; +https://github.com/therealLeEvi/evi-live-plugin)"),
      "the User-Agent names the plugin, its version and its public source page");

    // Never on the client thread, never on the EDT: refused BEFORE anything is sent.
    int before = fake.seen.size();
    onClientThread.set(true);
    check(throwsIllegalState(c::latest), "a request on the client thread must be refused");
    onClientThread.set(false);
    AtomicBoolean refusedOnEdt = new AtomicBoolean();
    javax.swing.SwingUtilities.invokeAndWait(() -> refusedOnEdt.set(throwsIllegalState(c::latest)));
    check(refusedOnEdt.get(), "a request on the Swing EDT must be refused");
    check(fake.seen.size() == before, "a refused request reached the network");

    // One in flight: a second request while one is out is refused, not queued -- by ANY instance of the class, so a plugin
    // turned off and on (a new WikiPriceClient) cannot add a second request beside the old instance's.
    WikiPriceClient another = new WikiPriceClient(fake.injected(), () -> false);
    CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
    fake.during = () -> {
      inside.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    };
    Thread first = new Thread(() -> {
      try {
        c.mapping();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    });
    first.start();
    check(inside.await(10, TimeUnit.SECONDS), "setup: the first request did not start");
    check(throwsIllegalState(c::latest), "a second request while one is in flight must be refused");
    check(throwsIllegalState(another::latest), "a request from ANOTHER instance while one is in flight must be refused too");
    release.countDown();
    first.join(10000);
    fake.during = null;
    check(fake.maxInFlight.get() == 1, "two requests were in flight at once");
    another.latest();
    check(fake.urls().get(fake.urls().size() - 1).endsWith("/latest"), "once the first request is done, another instance may ask");

    fake.answer = url -> Answer.status(429);
    check(ioMessage(c::latest).contains("HTTP 429"), "a non-200 answer is an error naming its status");
    fake.answer = url -> Answer.ok("x".repeat((int) WikiPriceClient.MAX_BYTES + 1));
    check(ioMessage(c::latest).contains("larger than"), "an answer over 10 MB must be refused");
    fake.answer = url -> Answer.ok("x".repeat((int) WikiPriceClient.MAX_BYTES));
    check(c.latest().length() == WikiPriceClient.MAX_BYTES, "an answer of exactly 10 MB is accepted");
    check(throwsIllegalArgument(() -> c.hour(1790812801L)) && throwsIllegalArgument(() -> c.hour(0)), "only the start of an hour may be asked for");
  }

  interface Call {
    void run() throws Exception;
  }

  static boolean throwsIllegalState(Call c) {
    try {
      c.run();
      return false;
    } catch (IllegalStateException e) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static boolean throwsIllegalArgument(Call c) {
    try {
      c.run();
      return false;
    } catch (IllegalArgumentException e) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static String ioMessage(Call c) {
    try {
      c.run();
      return "(no error)";
    } catch (IOException e) {
      return e.getMessage();
    } catch (Exception e) {
      return "(" + e + ")";
    }
  }

  // ------------------------------------------------------------------------------------------- the scheduler

  static long tsOf(String url) {
    int i = url.indexOf("timestamp=");
    return i < 0 ? -1 : Long.parseLong(url.substring(i + 10));
  }

  static PriceDataService service(Filepath root, FakeWiki fake, AtomicLong clock, List<String> log, int retention) {
    return new PriceDataService(root, new WikiPriceClient(fake.injected(), () -> false), clock::get, clock::get, log::add,
      new PriceDataService.Settings(2500, 60_000, retention, false));
  }

  static void scheduler() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-sched");
    List<String> log = new CopyOnWriteArrayList<>();
    try {
      AtomicLong clock = new AtomicLong(T0);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      PriceDataService s = service(root, fake, clock, log, 24);
      long newest = PriceDataService.newestClosedHour(T0);
      check(newest == 1790852400L, "at 12:10 the newest closed hour is 11:00, was " + newest);

      // 1. The item list first, then -- logged out -- the archive, NEWEST hour first, never /latest.
      check(s.step() == PriceDataService.Work.MAPPING && fake.urls().get(0).endsWith("/mapping"), "the item list comes first");
      // 2. At least 2.5 s between requests: nothing at +2,499 ms, the next one at +2,500.
      check(s.step() == PriceDataService.Work.WAIT && fake.seen.size() == 1, "a request straight after another must wait");
      clock.addAndGet(2499);
      check(s.step() == PriceDataService.Work.WAIT && fake.seen.size() == 1, "2,499 ms after a request is too soon");
      clock.addAndGet(1);
      check(s.step() == PriceDataService.Work.HOUR && tsOf(fake.urls().get(1)) == newest, "the newest closed hour comes first: " + fake.urls());
      for (int i = 1; i <= 24; i++) {
        clock.addAndGet(2500);
        s.step();
        check(tsOf(fake.urls().get(1 + i)) == newest - i * H, "backfill goes newest first, one hour back each time: " + fake.urls().get(1 + i));
      }
      clock.addAndGet(2500);
      check(s.step() == PriceDataService.Work.IDLE && fake.seen.size() == 26, "24 hours' retention is 25 hours (both ends) and then nothing: "
        + fake.seen.size());
      check(fake.urls().stream().noneMatch(u -> u.endsWith("/latest")), "/latest must never be fetched while logged out");
      for (MarketTestSupport.Seen seen : fake.seen) check(PriceDataService.THREAD_NAME.equals(seen.thread), "a request ran on " + seen.thread);

      // 3. What the snapshot then says: the readings (hourBody: item 2 averages 100.5 -> 101 and 97; volumes 10 and 5).
      MarketSnapshot snap = s.snapshot();
      check(snap.hoursStored == 25 && snap.hoursUnavailable == 0 && snap.windowHours == 25 && !snap.catchingUp && snap.newestStoredTs == newest, "archive status wrong");
      check(snap.robust.get(2).high == 101 && snap.robust.get(2).low == 97 && snap.robust.size() == 5, "robust prices: " + snap.robust);
      check(snap.typical.get(2) == 5 && snap.typical.get(6) == 9, "typical volumes (thinner side's median): " + snap.typical);
      check(snap.robust.equals(MarketAggregates.robustPricesAt(s.archive().read(0, Long.MAX_VALUE), clock.get())),
        "the snapshot's robust prices must be the readers' answer over the stored hours");
      check(snap.catalog != null && snap.catalog.limitFor(561) == 18000L, "the item list");
      check(snap.latestHour(clock.get()) != null && snap.latestHour(clock.get()).ts == newest, "the latest hour is the newest closed one");
      check(MarketAggregates.volumeReadingFor(snap.latestHour(clock.get()), 2) == 5L, "latest-hour reading for item 2");
      check(snap.latestHour(clock.get() + 3 * 3600_000L) == null, "an hour three hours old is no longer 'the latest hour'");
      check(names(s.archive().dir()).size() == 25, "25 hour files on disk: " + names(s.archive().dir()).size());

      // 4. Logged in: /latest first, then once a minute; logged out again: never.
      s.loggedIn(true);
      clock.addAndGet(2500);
      check(s.step() == PriceDataService.Work.LATEST && fake.urls().get(26).endsWith("/latest"), "logging in fetches /latest");
      check(s.snapshot().latest != null && s.snapshot().latest.get(2).high == 180, "the latest prices reach the snapshot");
      clock.addAndGet(59_999);
      check(s.step() == PriceDataService.Work.IDLE && fake.seen.size() == 27, "/latest is not fetched again within a minute");
      clock.addAndGet(1);
      check(s.step() == PriceDataService.Work.LATEST, "/latest again after 60 s");
      s.loggedIn(false);
      clock.addAndGet(120_000);
      check(s.step() == PriceDataService.Work.IDLE && fake.seen.size() == 28, "no /latest after logging out");

      // 5. The next hour, at five past: fetched, and the oldest pruned so the window keeps 25 hours.
      clock.set(T0 + 3600_000L);
      check(s.step() == PriceDataService.Work.HOUR && tsOf(fake.urls().get(28)) == newest + H, "the new hour is fetched once it settles");
      check(s.archive().storedHours().first() == newest + H - 24 * H && names(s.archive().dir()).size() == 25,
        "the oldest hour must be pruned when a new one arrives: " + s.archive().storedHours().first());

      // 6. Another client's file counts: an hour stored by a second archive on the same folder is never fetched.
      clock.set(T0 + 2 * 3600_000L);
      HourlyArchive other = new HourlyArchive(root.joinSegment(PriceDataService.DIR), m -> { });
      other.store(WikiJson.hour(MarketTestSupport.hourBody(newest + 2 * H, 5, false)));
      clock.addAndGet(2500);
      check(s.step() == PriceDataService.Work.IDLE && fake.seen.size() == 29, "an hour another client stored must not be fetched again");
      check(s.snapshot().latestHour(clock.get()) != null && s.snapshot().latestHour(clock.get()).ts == newest + 2 * H,
        "the latest hour must follow the archive even when the other client fetched it");

      // 7. Every file operation belongs to the price-data thread.
      check(throwsIllegalState(() -> s.tickOnce(clock.get())), "a step on any other thread must be refused");
      s.shutdown();
      check(s.awaitTermination(10_000), "the price-data thread did not stop");
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /**
   * 4.0.0: the unstamped /1h (the hour the old companion app sized on, about two hours behind) went with the developer shadow
   * mode. Logged in, stepping through every kind of work for well over a minute, the price layer asks for the /1h ONLY with a
   * timestamp -- and the client has no method that could ask without one.
   */
  static void neverUnstampedHour() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-never-unstamped");
    List<String> log = new CopyOnWriteArrayList<>();
    try {
      AtomicLong clock = new AtomicLong(T0);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      PriceDataService s = service(root, fake, clock, log, 2);
      s.loggedIn(true);
      for (int i = 0; i < 40; i++) {
        s.step();
        clock.addAndGet(2500);
      }
      check(fake.urls().stream().anyMatch(u -> u.endsWith("/latest")) && fake.urls().stream().anyMatch(u -> u.contains("/1h?timestamp=")),
        "setup: the layer did its ordinary work: " + fake.urls());
      check(fake.urls().stream().noneMatch(u -> u.endsWith("/1h")), "the /1h is only ever asked for by timestamp: " + fake.urls());
      for (java.lang.reflect.Method m : WikiPriceClient.class.getDeclaredMethods())
        check(!m.getName().equals("currentHour"), "WikiPriceClient.currentHour (the unstamped /1h) must be gone");
      check(java.util.Arrays.stream(PriceDataService.Work.values()).noneMatch(w -> w.name().equals("UNSTAMPED")), "no UNSTAMPED kind of work");
      s.shutdown();
      check(s.awaitTermination(10_000), "the price-data thread did not stop");
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  static void backoffAndOddAnswers() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-backoff");
    List<String> log = new CopyOnWriteArrayList<>();
    try {
      AtomicLong clock = new AtomicLong(T0);
      long newest = PriceDataService.newestClosedHour(T0);
      Function<String, Answer> ok = wikiAnswers();
      FakeWiki fake = new FakeWiki(url -> {
        long ts = tsOf(url);
        if (ts == newest) return Answer.fail("connection reset");
        if (ts == newest - H) return Answer.ok("{\"data\":{},\"timestamp\":" + (newest - H) + "}");                       // empty
        if (ts == newest - 2 * H) return Answer.ok(MarketTestSupport.hourBody(newest - 9 * H, 5, false));                 // a different hour
        if (ts == newest - 3 * H) return Answer.status(503);
        return ok.apply(url);
      });
      PriceDataService s = service(root, fake, clock, log, 12);
      check(s.step() == PriceDataService.Work.MAPPING, "mapping first");
      // Back-off after failures: 10 s, then 20 s, then the hour is left for the next session (and 40 s before the next request).
      long[] waits = {2500, 10_000, 20_000};
      for (int k = 0; k < 3; k++) {
        clock.addAndGet(waits[k] - 1);
        check(s.step() == PriceDataService.Work.WAIT, "attempt " + (k + 1) + " came before its wait of " + waits[k] + " ms");
        clock.addAndGet(1);
        check(s.step() == PriceDataService.Work.HOUR && tsOf(fake.urls().get(fake.seen.size() - 1)) == newest, "attempt " + (k + 1) + " asks the failing hour");
      }
      clock.addAndGet(39_999);
      check(s.step() == PriceDataService.Work.WAIT, "the third failure backs off 40 s");
      clock.addAndGet(1);
      s.step();
      check(tsOf(fake.urls().get(fake.seen.size() - 1)) == newest - H, "after three failures the hour is skipped and the next older one asked");
      check(log.stream().anyMatch(m -> m.contains("failed 3 times") && m.contains("left for the next session")), "the skip is logged: " + log);
      // An empty answer is remembered on disk and never asked again; an answer for a different hour is stored as that hour.
      Filepath dir = s.archive().dir();
      check(dir.joinSegment(HourlyArchive.noneName(newest - H)).exists(), "an empty answer leaves its marker");
      clock.addAndGet(2500);
      s.step();
      check(tsOf(fake.urls().get(fake.seen.size() - 1)) == newest - 2 * H, "next, the hour after the empty one");
      check(dir.joinSegment(HourlyArchive.dataName(newest - 9 * H)).exists() && dir.joinSegment(HourlyArchive.noneName(newest - 2 * H)).exists(),
        "a different hour's answer is stored under ITS hour, and the asked-for hour is marked answered");
      clock.addAndGet(2500);
      s.step(); // 503
      check(tsOf(fake.urls().get(fake.seen.size() - 1)) == newest - 3 * H && log.stream().anyMatch(m -> m.contains("HTTP 503")), "a 503 is a failure");
      // Run the rest out: the hour stored as a different answer (newest-9h) is never asked for itself.
      for (int i = 0; i < 40; i++) {
        clock.addAndGet(60_000);
        s.step();
      }
      List<Long> asked = fake.urls().stream().map(MarketIntentTest::tsOf).filter(t -> t > 0).collect(Collectors.toList());
      check(!asked.contains(newest - 9 * H), "an hour already stored from another answer was asked for again");
      check(asked.stream().filter(t -> t == newest - H).count() == 1, "the empty hour was asked for again");
      check(asked.stream().filter(t -> t == newest).count() == 3, "the skipped hour was asked for again in the same session");
      s.shutdown();
      // A new session (the next login) asks for the skipped hour again, and still not for the empty one.
      AtomicLong clock2 = new AtomicLong(clock.get());
      FakeWiki fake2 = new FakeWiki(ok);
      PriceDataService s2 = service(root, fake2, clock2, log, 12);
      for (int i = 0; i < 6; i++) {
        clock2.addAndGet(2500);
        s2.step();
      }
      List<Long> asked2 = fake2.urls().stream().map(MarketIntentTest::tsOf).filter(t -> t > 0).collect(Collectors.toList());
      check(asked2.contains(newest) && !asked2.contains(newest - H), "the next session retries the skipped hour, not the empty one: " + asked2);
      s2.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  static void restartRepairsOnlyTheGap() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-restart");
    List<String> log = new CopyOnWriteArrayList<>();
    try {
      AtomicLong clock = new AtomicLong(T0);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      PriceDataService s = service(root, fake, clock, log, 24);
      for (int i = 0; i < 30; i++) {
        clock.addAndGet(2500);
        s.step();
      }
      check(fake.seen.size() == 26, "first run: the item list and 25 hours");
      s.shutdown();
      check(s.awaitTermination(10_000), "stopped");
      // A crash once left two item-list temporary files: an old one (removed at the next start) and a fresh one, which
      // could be another client's save in progress (kept).
      Filepath prices = root.joinSegment(PriceDataService.DIR);
      Filepath oldTmp = prices.joinSegment("mapping-20727.json.gz.0123abcd-0123-4567-89ab-0123456789ab.tmp");
      Filepath freshTmp = prices.joinSegment("mapping-20727.json.gz.fedcba98-0123-4567-89ab-0123456789ab.tmp");
      oldTmp.write(new byte[2]);
      freshTmp.write(new byte[2]);
      ArchiveFiles.modifiedSeam = f -> f.equals(oldTmp) ? T0 + 5 * 3600_000L - HourlyArchive.STALE_TMP_MS - 60_000
        : f.equals(freshTmp) ? T0 + 5 * 3600_000L - 1000 : T0;
      // Five hours later, the same day: the item list comes from disk, and only the five missing hours are fetched.
      clock.set(T0 + 5 * 3600_000L);
      FakeWiki again = new FakeWiki(wikiAnswers());
      PriceDataService s2 = service(root, again, clock, log, 24);
      for (int i = 0; i < 10; i++) {
        clock.addAndGet(2500);
        s2.step();
      }
      long newest = PriceDataService.newestClosedHour(clock.get());
      List<Long> asked = again.urls().stream().map(MarketIntentTest::tsOf).collect(Collectors.toList());
      check(asked.equals(List.of(newest, newest - H, newest - 2 * H, newest - 3 * H, newest - 4 * H)), "a relog repairs only the gap, newest first: " + asked);
      check(s2.snapshot().catalog != null && s2.snapshot().catalog.size() == 2, "the item list was read from disk");
      ArchiveFiles.modifiedSeam = null;
      check(!oldTmp.exists() && freshTmp.exists(), "a crash's old item-list temporary file is removed at start, a fresh one kept");
      check(names(s2.archive().dir()).size() == 25, "the window still holds 25 hours: " + names(s2.archive().dir()).size());
      // The next day the item list is refreshed -- after the hours, which matter more.
      clock.set(T0 + 18 * 3600_000L); // 06:10 the next morning
      for (int i = 0; i < 20; i++) {
        clock.addAndGet(2500);
        s2.step();
      }
      List<String> urls = again.urls().subList(5, again.urls().size());
      check(urls.size() == 14 && urls.get(13).endsWith("/mapping") && urls.subList(0, 13).stream().allMatch(u -> u.contains("/1h?")),
        "the next day: the 13 missing hours, then the item list once: " + urls);
      s2.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /** A disk that refuses an hour (full, read-only) must back off like a failed request, never re-ask every 2.5 s. */
  static void diskFailureBacksOff() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-disk");
    List<String> log = new CopyOnWriteArrayList<>();
    try {
      AtomicLong clock = new AtomicLong(T0);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      PriceDataService s = service(root, fake, clock, log, 12);
      check(s.step() == PriceDataService.Work.MAPPING, "mapping first");
      ArchiveFiles.moveSeam = target -> {
        throw new java.io.UncheckedIOException(new IOException("There is not enough space on the disk"));
      };
      long newest = PriceDataService.newestClosedHour(T0);
      long[] waits = {2500, 10_000, 20_000};
      for (int k = 0; k < 3; k++) {
        clock.addAndGet(waits[k] - 1);
        check(s.step() == PriceDataService.Work.WAIT, "after a failed save, attempt " + (k + 1) + " came before its wait of " + waits[k] + " ms");
        clock.addAndGet(1);
        s.step();
        check(tsOf(fake.urls().get(fake.seen.size() - 1)) == newest, "attempt " + (k + 1) + " re-asks the hour that could not be saved");
      }
      check(fake.seen.size() == 4, "one request per back-off period, not one every 2.5 s: " + fake.seen.size());
      check(log.stream().anyMatch(m -> m.contains("could not be saved") && m.contains("left for the next session")), "the given-up save is logged: " + log);
      ArchiveFiles.moveSeam = null;
      clock.addAndGet(40_000);
      s.step();
      check(tsOf(fake.urls().get(fake.seen.size() - 1)) == newest - H && s.archive().storedHours().contains(newest - H),
        "once the disk takes writes again, the backfill goes on with the next hour");
      s.shutdown();
    } finally {
      ArchiveFiles.moveSeam = null;
      MarketTestSupport.deleteTree(root);
    }
  }

  /** Steps a service every {@code stepMs} of its clock for {@code totalMs}; returns each request made as {clock ms, url}. */
  static List<Object[]> run(PriceDataService s, FakeWiki fake, AtomicLong clock, long stepMs, long totalMs) throws Exception {
    List<Object[]> out = new ArrayList<>();
    long end = clock.get() + totalMs;
    while (clock.get() < end) {
      int before = fake.seen.size();
      s.step();
      List<String> u = fake.urls();
      for (int i = before; i < u.size(); i++) out.add(new Object[] {clock.get(), u.get(i)});
      clock.addAndGet(stepMs);
    }
    return out;
  }

  static List<Long> timesOf(List<Object[]> reqs, java.util.function.Predicate<String> which) {
    return reqs.stream().filter(r -> which.test((String) r[1])).map(r -> (Long) r[0]).collect(Collectors.toList());
  }

  /**
   * The back-off belongs to each KIND of request: a /latest that succeeds every minute must not keep restarting the
   * back-off of a /1h the Wiki refuses (the 7 Oct review measured that as about a thousand failed requests in a few hours).
   */
  static void backoffPerKind() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-kinds");
    try {
      AtomicLong clock = new AtomicLong(T0);
      Function<String, Answer> ok = wikiAnswers();
      FakeWiki fake = new FakeWiki(url -> url.contains("/1h?") ? Answer.status(500) : ok.apply(url));
      PriceDataService s = service(root, fake, clock, new CopyOnWriteArrayList<>(), 12);
      s.loggedIn(true);
      List<Object[]> reqs = run(s, fake, clock, 500, 1_500_000);
      List<Long> hourAt = timesOf(reqs, u -> u.contains("/1h?"));
      long latest = timesOf(reqs, u -> u.endsWith("/latest")).size();
      check(latest >= 24, "/latest kept working once a minute beside the failing history: " + latest + " in 25 minutes");
      for (int k = 1; k < hourAt.size(); k++) {
        long want = Math.min(PriceDataService.BACKOFF_MAX_MS, PriceDataService.BACKOFF_FIRST_MS << (k - 1)), got = hourAt.get(k) - hourAt.get(k - 1);
        check(got >= want && got <= want + 3_000, "the wait after /1h failure " + k + " was " + got + " ms; the back-off says " + want
          + " (a /latest success must not reset it)");
      }
      check(hourAt.size() == 9, "a refused history settles at one attempt every 5 minutes: 9 attempts in 25 minutes, not " + hourAt.size());
      s.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /** No item list on disk and the Wiki refusing one: it is retried with back-off, and the prices and history go on meanwhile. */
  static void mappingRefusedDoesNotBlock() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-nomap");
    try {
      AtomicLong clock = new AtomicLong(T0);
      Function<String, Answer> ok = wikiAnswers();
      FakeWiki fake = new FakeWiki(url -> url.endsWith("/mapping") ? Answer.status(503) : ok.apply(url));
      PriceDataService s = service(root, fake, clock, new CopyOnWriteArrayList<>(), 6);
      s.loggedIn(true);
      List<Object[]> reqs = run(s, fake, clock, 500, 120_000);
      check(reqs.get(1)[1].toString().endsWith("/latest") && (Long) reqs.get(1)[0] == T0 + 2500,
        "after the refused item list, /latest goes 2.5 s later rather than waiting behind it: " + reqs.get(1)[1] + " at " + reqs.get(1)[0]);
      List<Long> mapAt = timesOf(reqs, u -> u.endsWith("/mapping"));
      check(mapAt.size() == 4, "the item list is retried at 10, 20 and 40 s waits: 4 attempts in 2 minutes, not " + mapAt.size());
      for (int k = 1; k < mapAt.size(); k++) {
        long want = PriceDataService.BACKOFF_FIRST_MS << (k - 1), got = mapAt.get(k) - mapAt.get(k - 1);
        check(got >= want && got <= want + 3_000, "the wait after item-list failure " + k + " was " + got + " ms, the back-off says " + want);
      }
      MarketSnapshot snap = s.snapshot();
      check(snap.catalog == null && snap.latest != null && snap.hoursStored == 7 && !snap.catchingUp,
        "without an item list the latest prices and all 7 hours still arrive: stored " + snap.hoursStored + ", latest " + (snap.latest != null));
      s.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /**
   * "Catching up" means hours are still to be FETCHED. An hour the Wiki keeps refusing is given up for the session and
   * counted as unavailable; it must not hold the flag true until it ages out of the window two weeks later (the flag is
   * what "withhold buys until the history is in" would read).
   */
  static void catchingUpIgnoresGivenUpHours() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-catching");
    try {
      AtomicLong clock = new AtomicLong(T0);
      long newest = PriceDataService.newestClosedHour(T0), bad = newest - 2 * H;
      Function<String, Answer> ok = wikiAnswers();
      FakeWiki fake = new FakeWiki(url -> tsOf(url) == bad ? Answer.status(500) : ok.apply(url));
      PriceDataService s = service(root, fake, clock, new CopyOnWriteArrayList<>(), 6);
      s.step();
      MarketSnapshot first = s.snapshot();
      check(first.catchingUp && first.hoursStored == 0 && first.hoursUnavailable == 0 && first.windowHours == 7,
        "with every hour still to fetch, the layer is catching up: " + first.hoursStored + "/" + first.windowHours);
      clock.addAndGet(2500);
      List<Object[]> reqs = run(s, fake, clock, 1000, 300_000);
      MarketSnapshot snap = s.snapshot();
      check(timesOf(reqs, u -> tsOf(u) == bad).size() == 3, "the refused hour is tried 3 times this session");
      check(snap.hoursStored == 6 && snap.hoursUnavailable == 1 && snap.windowHours == 7,
        "stored " + snap.hoursStored + ", unavailable " + snap.hoursUnavailable + " of " + snap.windowHours);
      check(!snap.catchingUp, "an hour given up for the session is not 'still catching up'");
      check(snap.robust.size() == 5 && snap.robust.get(2).high == 101, "the readings are built from the hours there are: " + snap.robust);
      s.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /**
   * The computer's clock set BACK (a time sync, a manual fix) must not silence the layer: every spacing, back-off, the
   * minute between /latest requests and the rebuild interval run on the MONOTONIC clock, and the wall clock decides only
   * which hours exist and the times published. Before this, a clock set back a day held every request for a day.
   */
  static void wallClockSetBack() throws Exception {
    long day = 24 * 3600_000L, minute = 60_000L;
    // A: set back a whole day straight after a /latest request.
    Filepath root = MarketTestSupport.tempRoot("evi-prices-wallback");
    try {
      AtomicLong wall = new AtomicLong(T0), mono = new AtomicLong(5_000_000L);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      PriceDataService s = new PriceDataService(root, new WikiPriceClient(fake.injected(), () -> false), wall::get, mono::get,
        new CopyOnWriteArrayList<String>()::add, new PriceDataService.Settings(2500, 60_000, 2, false));
      check(s.step() == PriceDataService.Work.MAPPING, "setup: the item list first");
      for (int i = 0; i < 3; i++) {
        wall.addAndGet(2500);
        mono.addAndGet(2500);
        check(s.step() == PriceDataService.Work.HOUR, "setup: hour " + i + " of the window");
      }
      s.loggedIn(true);
      wall.addAndGet(2500);
      mono.addAndGet(2500);
      check(s.step() == PriceDataService.Work.LATEST && fake.seen.size() == 5, "setup: /latest once logged in: " + fake.urls());
      wall.addAndGet(-day);
      long wait = s.delay();
      check(wait == 2500, "with the clock set back a day, the next tick must come when the 2,500 ms spacing ends, not a day later: " + wait + " ms");
      mono.addAndGet(2499);
      check(s.step() == PriceDataService.Work.WAIT && fake.seen.size() == 5, "the spacing still holds on the monotonic clock: 2,499 ms is too soon");
      mono.addAndGet(1);
      check(s.step() == PriceDataService.Work.HOUR && fake.seen.size() == 6,
        "2,500 monotonic ms later the layer goes on (the hours the clock now names) instead of waiting a day: " + fake.urls());
      wall.addAndGet(minute);
      mono.addAndGet(minute);
      check(s.step() == PriceDataService.Work.LATEST && s.snapshot().latestFetchedAtMs == wall.get(),
        "a minute later (monotonic) /latest comes again, and the time published is the WALL time: " + s.snapshot().latestFetchedAtMs);
      s.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
    // B: the clock runs 50 minutes fast, a /latest fails (back-off) and the readings were built, then it is corrected.
    root = MarketTestSupport.tempRoot("evi-prices-wallfix");
    try {
      AtomicLong wall = new AtomicLong(T0), mono = new AtomicLong(7_000_000L);
      AtomicBoolean refuseLatest = new AtomicBoolean(true);
      Function<String, Answer> ok = wikiAnswers();
      FakeWiki fake = new FakeWiki(url -> url.endsWith("/latest") && refuseLatest.get() ? Answer.status(500) : ok.apply(url));
      PriceDataService s = new PriceDataService(root, new WikiPriceClient(fake.injected(), () -> false), wall::get, mono::get,
        new CopyOnWriteArrayList<String>()::add, new PriceDataService.Settings(2500, 60_000, 2, false));
      check(s.step() == PriceDataService.Work.MAPPING, "setup: the item list first");
      for (int i = 0; i < 3; i++) {
        wall.addAndGet(2500);
        mono.addAndGet(2500);
        check(s.step() == PriceDataService.Work.HOUR, "setup: hour " + i + " of the window");
      }
      long builtAt = s.snapshot().readingsBuiltAtMs;
      check(builtAt == T0 + 7500, "setup: the readings are built once the window is in, stamped with the wall time: " + builtAt);
      s.loggedIn(true);
      wall.set(T0 + 50 * minute); // the same newest hour (11:00) as T0, so no new hour is due
      mono.addAndGet(2500);
      check(s.step() == PriceDataService.Work.LATEST && fake.seen.size() == 5, "setup: a refused /latest: " + fake.urls());
      wall.set(T0); // corrected: 50 minutes back
      refuseLatest.set(false);
      long wait = s.delay();
      check(wait == PriceDataService.BACKOFF_FIRST_MS, "the 10 s back-off must end 10 s later, not 50 minutes later: " + wait + " ms");
      mono.addAndGet(PriceDataService.BACKOFF_FIRST_MS - 1);
      check(s.step() == PriceDataService.Work.WAIT && fake.seen.size() == 5, "the back-off still holds on the monotonic clock");
      mono.addAndGet(1);
      check(s.step() == PriceDataService.Work.LATEST && fake.seen.size() == 6 && s.snapshot().latestFetchedAtMs == T0,
        "the retry comes when the back-off ends, and is stamped with the corrected wall time: " + s.snapshot().latestFetchedAtMs);
      wall.addAndGet(15 * minute);
      mono.addAndGet(15 * minute);
      s.step();
      check(s.snapshot().readingsBuiltAtMs == wall.get(), "15 monotonic minutes on, the readings are rebuilt (stamped "
        + s.snapshot().readingsBuiltAtMs + ", want " + wall.get() + "), not held until the wall clock passes its old reading");
      s.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /**
   * Two monotonic stamps the 7 Oct verifier found unguarded. (C) The wake-up for the next hour to settle: the hour
   * settles at a WALL time, so the wait is that wall distance added to the monotonic now -- never the wall time itself
   * compared with a monotonic reading, which (with any monotonic origin) leaves the hourly fetch to the one-minute idle
   * tick. (D) A rebuild that FAILS is stamped on the monotonic clock too, so it is tried again with the next due rebuild
   * and not on every tick (each attempt reads the whole 336-hour window).
   */
  static void monotonicStamps() throws Exception {
    long minute = 60_000L;
    // C: 12:10 UTC, the window complete, logged out. The 12:00 hour closes at 13:00 and is asked for from 13:05 (the
    // service wakes a second after that): 55 min 1 s on. A negative monotonic origin, as System.nanoTime() may have.
    Filepath root = MarketTestSupport.tempRoot("evi-prices-settlewake");
    try {
      AtomicLong wall = new AtomicLong(T0), mono = new AtomicLong(-9_000_000_000_000L);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      PriceDataService s = new PriceDataService(root, new WikiPriceClient(fake.injected(), () -> false), wall::get, mono::get,
        new CopyOnWriteArrayList<String>()::add, new PriceDataService.Settings(2500, 60_000, 2, false));
      check(s.step() == PriceDataService.Work.MAPPING, "setup: the item list first");
      for (int i = 0; i < 3; i++) {
        wall.addAndGet(2500);
        mono.addAndGet(2500);
        check(s.step() == PriceDataService.Work.HOUR, "setup: hour " + i + " of the window");
      }
      long settles = T0 + 55 * minute + 1000;
      wall.set(settles - 20_000);
      mono.addAndGet(2500);
      check(s.step() == PriceDataService.Work.IDLE && fake.seen.size() == 4, "setup: 20 s before the 12:00 hour settles nothing is due: " + fake.urls());
      long wait = s.delay();
      check(wait == 20_000, "the service must wake when the next hour settles, 20 s away, not at the one-minute idle tick: " + wait + " ms");
      wall.addAndGet(wait);
      mono.addAndGet(wait);
      check(s.step() == PriceDataService.Work.HOUR && fake.seen.size() == 5 && fake.urls().get(4).endsWith("/1h?timestamp=" + (T0 / 1000 - 600)),
        "at that wake-up the 12:00 hour is fetched: " + fake.urls());
      s.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
    // D: the readings built, then the hour folder cannot be listed when the next rebuild falls due.
    root = MarketTestSupport.tempRoot("evi-prices-rebuildfail");
    List<String> log = new CopyOnWriteArrayList<>();
    try {
      AtomicLong wall = new AtomicLong(T0), mono = new AtomicLong(3_000_000L);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      PriceDataService s = new PriceDataService(root, new WikiPriceClient(fake.injected(), () -> false), wall::get, mono::get,
        log::add, new PriceDataService.Settings(2500, 60_000, 2, false));
      check(s.step() == PriceDataService.Work.MAPPING, "setup: the item list first");
      for (int i = 0; i < 3; i++) {
        wall.addAndGet(2500);
        mono.addAndGet(2500);
        check(s.step() == PriceDataService.Work.HOUR, "setup: hour " + i + " of the window");
      }
      check(s.snapshot().readingsBuiltAtMs == T0 + 7500, "setup: the readings are built: " + s.snapshot().readingsBuiltAtMs);
      Filepath hours = s.archive().dir();
      java.util.function.Predicate<String> failed = m -> m.contains("could not be rebuilt");
      try (AutoCloseable denied = denyListing(hours)) {
        wall.addAndGet(15 * minute);
        mono.addAndGet(15 * minute);
        check(s.step() == PriceDataService.Work.IDLE && log.stream().filter(failed).count() == 1,
          "setup: 15 minutes on, the due rebuild is tried and fails: " + log);
        for (int i = 1; i <= 5; i++) {
          wall.addAndGet(1000);
          mono.addAndGet(1000);
          s.step();
        }
        check(log.stream().filter(failed).count() == 1,
          "a failed rebuild must not be tried again on every tick (each attempt reads the whole window): " + log.stream().filter(failed).count() + " attempts");
        wall.addAndGet(15 * minute - 5000 - 1);
        mono.addAndGet(15 * minute - 5000 - 1);
        s.step();
        check(log.stream().filter(failed).count() == 1, "nor before the rebuild interval has run again from the failure (monotonic)");
        wall.addAndGet(1);
        mono.addAndGet(1);
        s.step();
        check(log.stream().filter(failed).count() == 2, "it is tried again when the interval has run: " + log.stream().filter(failed).count());
      }
      wall.addAndGet(15 * minute);
      mono.addAndGet(15 * minute);
      s.step();
      check(s.snapshot().readingsBuiltAtMs == wall.get() && log.stream().filter(failed).count() == 2,
        "once the folder reads again, the next due rebuild succeeds: " + s.snapshot().readingsBuiltAtMs + " " + log);
      s.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /**
   * Test code only: makes the archive's hour folder {@code dir} unlistable until closed, through HourlyArchive's list seam
   * (a real DENY entry or POSIX bit needs file-system calls Filepath does not offer), and proves the denial bites before
   * returning -- a test that only thinks the folder is unreadable proves nothing.
   */
  static AutoCloseable denyListing(Filepath dir) throws IOException {
    HourlyArchive.listSeam = d -> {
      if (d.equals(dir)) throw new java.nio.file.AccessDeniedException(d.toString());
    };
    AutoCloseable restore = () -> HourlyArchive.listSeam = null;
    boolean bites = false;
    try {
      new HourlyArchive(dir.getParent(), x -> { }).storedHours();
    } catch (IOException expected) {
      bites = true;
    }
    if (!bites) {
      try {
        restore.close();
      } catch (Exception e) {
        throw new IOException(e);
      }
      check(false, "setup: could not make " + dir + " unlistable, so a failed rebuild cannot be shown");
    }
    return restore;
  }

  /**
   * Off and on while a request is out (the 7 Oct review reproduced two requests 10 ms apart this way): the new service
   * reads and asks NOTHING until every earlier generation has finished -- through one that was itself stopped before it
   * did anything -- then keeps the old one's 2.5 s spacing, and uses the item list the old one fetched.
   */
  static void serviceHandover() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-handover");
    List<String> log = new CopyOnWriteArrayList<>();
    try {
      CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicBoolean hold = new AtomicBoolean(true);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      fake.during = () -> {
        if (!hold.getAndSet(false)) return;
        inside.countDown();
        try {
          release.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
      };
      PriceDataService.Settings real = new PriceDataService.Settings(2500, 60_000, 6, true);
      java.util.function.Supplier<PriceDataService> make = () -> new PriceDataService(root, new WikiPriceClient(fake.injected(), () -> false),
        System::currentTimeMillis, PriceDataService.MONOTONIC_MS, log::add, real);
      PriceDataService g1 = make.get();
      g1.start();
      check(inside.await(10, TimeUnit.SECONDS), "setup: generation 1's first request did not start");
      g1.shutdown();
      PriceDataService g2 = make.get();
      g2.start(g1);
      g2.shutdown();
      PriceDataService g3 = make.get();
      g3.start(g2);
      long quietUntil = System.currentTimeMillis() + 1_500;
      while (System.currentTimeMillis() < quietUntil) pause(20);
      check(fake.seen.size() == 1 && g3.snapshot() == MarketSnapshot.EMPTY,
        "while generation 1's request is out, generation 3 must neither ask nor read: " + fake.urls());
      long releasedNanos = System.nanoTime();
      release.countDown();
      long until = System.currentTimeMillis() + 10_000;
      while (fake.seen.size() < 2 && System.currentTimeMillis() < until) pause(10);
      check(fake.seen.size() >= 2, "generation 3 never took over: " + fake.urls() + " " + log);
      long gapMs = (fake.seen.get(1).atNanos - releasedNanos) / 1_000_000;
      check(gapMs >= 2_450, "generation 3's first request came " + gapMs + " ms after generation 1's ended; the spacing is 2,500 ms");
      check(fake.urls().get(1).contains("/1h?"), "generation 3 must use the item list generation 1 fetched and saved: " + fake.urls());
      check(fake.maxInFlight.get() == 1, "two Wiki requests were in flight at once");
      check(log.stream().noneMatch(m -> m.contains("one Wiki request at a time") || m.contains("not fetched")), "a request was refused or failed: " + log);
      check(g1.isTerminated() && g2.isTerminated(), "the earlier generations have finished");
      g3.shutdown();
      check(g3.awaitTermination(25_000), "generation 3 did not stop");
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  static void autoScheduledAndShutdown() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-auto");
    List<String> log = new CopyOnWriteArrayList<>();
    try {
      FakeWiki fake = new FakeWiki(wikiAnswers());
      PriceDataService s = new PriceDataService(root, new WikiPriceClient(fake.injected(), () -> false), System::currentTimeMillis,
        PriceDataService.MONOTONIC_MS, log::add, new PriceDataService.Settings(150, 2_000, 6, true));
      s.start();
      long until = System.currentTimeMillis() + 15_000;
      while (fake.seen.size() < 8 && System.currentTimeMillis() < until) pause(20);
      check(fake.seen.size() >= 8, "the scheduler did not fetch the list and seven hours by itself: " + fake.urls());
      for (int i = 1; i < 8; i++) {
        long gapMs = (fake.seen.get(i).atNanos - fake.seen.get(i - 1).atNanos) / 1_000_000;
        check(gapMs >= 149, "requests " + (i - 1) + " and " + i + " were only " + gapMs + " ms apart (the gap is 150)");
      }
      check(fake.maxInFlight.get() == 1, "two requests in flight at once");
      // Logging in fetches /latest promptly; then hold the NEXT /latest in flight and shut down underneath it.
      CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicBoolean interrupted = new AtomicBoolean(), finished = new AtomicBoolean();
      AtomicBoolean holdNext = new AtomicBoolean(false);
      fake.during = () -> {
        if (!holdNext.get()) return;
        inside.countDown();
        try {
          if (!release.await(20, TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) {
          interrupted.set(true);
        }
        if (Thread.currentThread().isInterrupted()) interrupted.set(true);
        finished.set(true);
      };
      s.loggedIn(true);
      until = System.currentTimeMillis() + 5_000;
      while (fake.urls().stream().noneMatch(u -> u.endsWith("/latest")) && System.currentTimeMillis() < until) pause(20);
      check(fake.urls().stream().anyMatch(u -> u.endsWith("/latest")), "logging in did not fetch /latest promptly");
      holdNext.set(true);
      check(inside.await(10, TimeUnit.SECONDS), "the next /latest (2 s later) did not start");
      long began = System.nanoTime();
      s.shutdown();
      long tookMs = (System.nanoTime() - began) / 1_000_000;
      check(tookMs < 200, "shutdown() blocked the caller for " + tookMs + " ms");
      int countAtShutdown = fake.seen.size();
      release.countDown();
      check(s.awaitTermination(10_000), "the price-data thread did not finish after shutdown");
      check(finished.get() && !interrupted.get(), "the request in flight was interrupted or cut short by shutdown");
      long quietUntil = System.currentTimeMillis() + 2_500;
      while (System.currentTimeMillis() < quietUntil) pause(20);
      check(fake.seen.size() == countAtShutdown, "a request was made after shutdown");
      check(s.thread().isDaemon() && PriceDataService.THREAD_NAME.equals(s.thread().getName()), "a daemon thread named evi-prices");
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /** Test code only (tests do not ship): a short wait while polling for the scheduler to act. */
  static void pause(long ms) throws InterruptedException {
    Thread.sleep(ms);
  }

  // ------------------------------------------------------------------------------------------- the plugin

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

  static void pluginWiring() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-plugin");
    try {
      // startPrices: the plugin's injected OkHttp, through WikiPriceClient, on the evi-prices thread.
      FakeWiki fake = new FakeWiki(wikiAnswers());
      EviLivePlugin p = new EviLivePlugin();
      set(p, "okHttpClient", fake.injected());
      Method start = EviLivePlugin.class.getDeclaredMethod("startPrices", Filepath.class);
      start.setAccessible(true);
      start.invoke(p, root);
      PriceDataService svc = (PriceDataService) get(p, "prices");
      check(svc != null && !svc.isLoggedIn(), "startUp's price layer must exist, logged out without a client");
      check(get(svc, "mono") == PriceDataService.MONOTONIC_MS, "the plugin must pace the Wiki on the monotonic clock, not the wall clock");
      // ... and that clock's VALUE: System.nanoTime() in whole milliseconds, read between two readings of nanoTime. (The
      // identity above passes whatever the constant holds; the wall clock in it would bring the set-back stall back.)
      for (int i = 0; i < 1_000; i++) {
        long lo = Math.floorDiv(System.nanoTime(), 1_000_000L);
        long v = PriceDataService.MONOTONIC_MS.getAsLong();
        long hi = Math.floorDiv(System.nanoTime(), 1_000_000L);
        if (lo <= v && v <= hi) continue;
        check(false, "MONOTONIC_MS must be System.nanoTime() in milliseconds: read " + v + " between " + lo + " and " + hi
          + " (the wall clock reads " + System.currentTimeMillis() + ")");
      }
      check(true, "MONOTONIC_MS is System.nanoTime() in milliseconds");
      long until = System.currentTimeMillis() + 10_000;
      while (fake.seen.size() < 2 && System.currentTimeMillis() < until) pause(20);
      check(fake.seen.size() >= 2 && fake.urls().get(0).equals("https://prices.runescape.wiki/api/v2/osrs/mapping")
        && fake.seen.get(0).thread.equals(PriceDataService.THREAD_NAME) && String.format(UA, "item list").equals(fake.seen.get(0).request.header("User-Agent")),
        "the plugin's price layer must fetch through WikiPriceClient on evi-prices: " + fake.urls());
      check(root.joinSegment("prices").joinSegment("1h").isDirectory(), "the archive lives in <plugin data>/prices/1h");

      // Game state: logged in turns /latest on, the login screen turns it off.
      Method onGs = EviLivePlugin.class.getMethod("onGameStateChanged", GameStateChanged.class);
      GameStateChanged in = new GameStateChanged();
      in.setGameState(GameState.LOGGED_IN);
      onGs.invoke(p, in);
      check(svc.isLoggedIn(), "LOGGED_IN must reach the price layer");
      GameStateChanged out = new GameStateChanged();
      out.setGameState(GameState.LOGIN_SCREEN);
      try {
        onGs.invoke(p, out);
      } catch (InvocationTargetException expected) {
        // this harness has no client for the rest of the handler; the price layer is told first
      }
      check(!svc.isLoggedIn(), "LOGIN_SCREEN must reach the price layer");

      // shutDown: the layer is stopped (orderly) and let go, even though later teardown steps throw in this harness.
      Method shutDown = EviLivePlugin.class.getDeclaredMethod("shutDown");
      shutDown.setAccessible(true);
      try {
        shutDown.invoke(p);
      } catch (InvocationTargetException expected) {
        // no keybind handler or overlay manager injected here
      }
      check(svc.isShutdown() && get(p, "prices") == null, "shutDown() must stop the price layer and let it go");
      check(svc.awaitTermination(25_000), "the price layer's thread did not finish");
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /** The plugin's own off/on: stopPrices() keeps the stopped layer and startPrices() hands it to the new one. */
  static void pluginToggle() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-toggle");
    try {
      CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicBoolean hold = new AtomicBoolean(true);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      fake.during = () -> {
        if (!hold.getAndSet(false)) return;
        inside.countDown();
        try {
          release.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
      };
      EviLivePlugin p = new EviLivePlugin();
      set(p, "okHttpClient", fake.injected());
      Method start = EviLivePlugin.class.getDeclaredMethod("startPrices", Filepath.class);
      Method stop = EviLivePlugin.class.getDeclaredMethod("stopPrices");
      start.setAccessible(true);
      stop.setAccessible(true);
      start.invoke(p, root);
      PriceDataService first = (PriceDataService) get(p, "prices");
      check(inside.await(10, TimeUnit.SECONDS), "setup: the first /mapping did not start");
      stop.invoke(p);
      start.invoke(p, root);
      stop.invoke(p);
      start.invoke(p, root);
      PriceDataService last = (PriceDataService) get(p, "prices");
      check(last != null && last != first && first.isShutdown(), "two toggles make a third layer and stop the first");
      long quietUntil = System.currentTimeMillis() + 1_500;
      while (System.currentTimeMillis() < quietUntil) pause(20);
      check(fake.seen.size() == 1, "while the old layer's request is out, the plugin must send nothing new: " + fake.urls());
      long releasedNanos = System.nanoTime();
      release.countDown();
      long until = System.currentTimeMillis() + 10_000;
      while (fake.seen.size() < 2 && System.currentTimeMillis() < until) pause(10);
      check(fake.seen.size() >= 2, "the new layer never took over: " + fake.urls());
      long gapMs = (fake.seen.get(1).atNanos - releasedNanos) / 1_000_000;
      check(gapMs >= 2_450 && fake.urls().get(1).contains("/1h?") && fake.maxInFlight.get() == 1,
        "after an off/on the next request must be the history, one at a time, at least 2.5 s after the old one ended: " + gapMs + " ms, "
          + fake.urls() + ", in flight at most " + fake.maxInFlight.get());
      stop.invoke(p);
      check(last.awaitTermination(25_000) && get(p, "retiredPrices") == last, "the last layer stopped and is kept for the next start");
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /**
   * The settings text a player reads about the network, pinned word for word: SHORT, because it is a hover tooltip (the
   * maintainer's decision, 7 Oct; the first version ran to about 760 characters). The full account lives in the plugin's
   * README, and every figure in THAT text is still tied here to the constant that makes it true, so a change of pace,
   * cadence or retention fails this test until the README is revisited. (The 7 Oct review found the first version
   * leaving out the hourly and daily requests made while logged out, and the catch-up after an absence.)
   */
  static void disclosure() throws Exception {
    String text = "Downloads public GE prices from the OSRS Wiki. Nothing about you is sent. Details in the README.";
    check(text.equals(EviLiveConfig.PRICE_DATA_DISCLOSURE), "the disclosure text changed: " + EviLiveConfig.PRICE_DATA_DISCLOSURE);
    for (char ch : EviLiveConfig.PRICE_DATA_DISCLOSURE.toCharArray()) check(ch >= 0x20 && ch < 0x7f, "the disclosure must be plain ASCII: " + (int) ch);
    check(EviLiveConfig.PRICE_DATA_DISCLOSURE.length() <= 120, "the tooltip must stay short: " + EviLiveConfig.PRICE_DATA_DISCLOSURE.length() + " characters");
    ConfigSection sec = EviLiveConfig.class.getField("priceDataSection").getAnnotation(ConfigSection.class);
    check(sec != null && "Price data".equals(sec.name()) && text.equals(sec.description()), "a 'Price data' settings section carrying the disclosure");

    // The README's figures (its "Price data" section), each against the constant behind it.
    PriceDataService.Settings d = PriceDataService.Settings.DEFAULT;
    check(d.latestEveryMs == 60_000, "the README says 'the latest prices about once a minute'; the code asks every " + d.latestEveryMs + " ms");
    check(PriceDataService.MAPPING_EVERY_MS == 24L * 3600_000, "the README says 'the item list once a day'; the code refreshes it every "
      + PriceDataService.MAPPING_EVERY_MS + " ms");
    check(d.retentionHours == 14 * 24, "the README says 'up to two weeks back'; the window is " + d.retentionHours + " hours");
    check(d.gapMs == 2500, "the README says 'at least 2.5 seconds apart'; the spacing is " + d.gapMs + " ms");
    // The README states the MEASURED first run (16 to 21 minutes: the spacing plus the Wiki's own answer time). The
    // spacing alone is the floor under that, and must not rise past the low end stated.
    double floorMinutes = (d.retentionHours + 1) * d.gapMs / 60_000.0;
    check(floorMinutes > 13.5 && floorMinutes <= 16, "the README says 'about 16 to 21 minutes' with a 14-minute floor from the spacing; "
      + (d.retentionHours + 1) + " hours " + d.gapMs + " ms apart take " + floorMinutes + " minutes before any answer time");
    // 'logged in or not': a logged-out service DOES fetch the item list and the history, and never /latest.
    Filepath root = MarketTestSupport.tempRoot("evi-prices-disclosure");
    try {
      AtomicLong clock = new AtomicLong(T0);
      FakeWiki fake = new FakeWiki(wikiAnswers());
      PriceDataService s = service(root, fake, clock, new CopyOnWriteArrayList<>(), 2);
      for (int i = 0; i < 6; i++) {
        s.step();
        clock.addAndGet(2500);
      }
      check(!s.isLoggedIn() && fake.urls().get(0).endsWith("/mapping") && fake.urls().stream().filter(u -> u.contains("/1h?")).count() == 3
        && fake.urls().stream().noneMatch(u -> u.endsWith("/latest")), "logged out: the item list and the history, never /latest: " + fake.urls());
      s.shutdown();
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  /**
   * The Wiki is reached from ONE class, and the plugin's version is the one it identifies as. The markers are broad on
   * purpose: the host however it is spelled or put together ("runescape" or "prices." opening or closing a string, a
   * string opening with ".wiki"), and every way to open a connection (an OkHttp call or web socket, a request or URL builder, java.net's URL,
   * socket and HTTP clients). hubRulesTest separately refuses every network client but OkHttp.
   */
  /**
   * THE LOOPBACK BAN (4.0.0): the companion app on 127.0.0.1:51743 is gone, and nothing in the plugin may reach this machine's
   * own network either. Raw text, comments included -- a comment naming the address is the first step back to code using it.
   */
  static final java.util.regex.Pattern LOOPBACK_ANYWHERE = java.util.regex.Pattern.compile("(?i)127\\.0\\.0\\.1|localhost|\\b51743\\b|\\[::1\\]|\\b0\\.0\\.0\\.0\\b|loopback");

  /** The contents of every string literal and text block in a Java source, escapes left as written. */
  static List<String> javaLiterals(String s) {
    List<String> out = new ArrayList<>();
    scanJava(s, null, out, new ArrayList<>(), false);
    return out;
  }

  /** The source with comments and string/char literals removed. */
  static String javaCodeOnly(String s) {
    StringBuilder code = new StringBuilder();
    scanJava(s, code, new ArrayList<>(), new ArrayList<>(), false);
    return code.toString();
  }

  /**
   * Splits a Java source into code, string literals (text blocks included) and char literals, dropping comments. With
   * {@code mark}, each string literal is left in the code as {@code "#<its index in literals>"}.
   */
  private static void scanJava(String s, StringBuilder code, List<String> literals, List<String> chars, boolean mark) {
    int i = 0, n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
        while (i < n && s.charAt(i) != '\n') i++;
      } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
        int end = s.indexOf("*/", i + 2);
        i = end < 0 ? n : end + 2;
      } else if (s.startsWith("\"\"\"", i)) {
        int end = s.indexOf("\"\"\"", i + 3);
        literals.add(s.substring(i + 3, end < 0 ? n : end));
        if (mark && code != null) code.append("\"#").append(literals.size() - 1).append("\"");
        i = end < 0 ? n : end + 3;
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') j += s.charAt(j) == '\\' ? 2 : 1;
        if (c == '"') {
          literals.add(s.substring(i + 1, Math.min(j, n)));
          if (mark && code != null) code.append("\"#").append(literals.size() - 1).append("\"");
        } else {
          chars.add(s.substring(i + 1, Math.min(j, n)));
          if (mark && code != null) code.append("'#'");
        }
        i = j + 1;
      } else {
        if (code != null) code.append(c);
        i++;
      }
    }
  }

  static void oneNetworkClass() throws Exception {
    Filepath src = TestFiles.at(TestFiles.project(), "src", "main", "java");
    java.util.regex.Pattern host = java.util.regex.Pattern.compile("(?i)runescape\\s*\\.|\"\\s*\\.\\s*wiki\\b|\"\\s*runescape|runescape\\s*\"|\"\\s*prices\\s*\\.");
    java.util.regex.Pattern net = java.util.regex.Pattern.compile("\\.newCall\\(|\\.newWebSocket\\(|Request\\.Builder|HttpUrl|URLConnection"
      + "|java\\.net\\.Socket|java\\.net\\.http|java\\.net\\.URL\\b|new\\s+URL\\(|\\.openStream\\(|DatagramSocket|SocketChannel");
    // The detectors must find what they are for, and only that.
    String[] bad = {"String h = \"prices.\" + \"runescape\" + \".wiki\";", "String h = \"RUNESCAPE\" ;", "String h = \"x\" + \".wiki\";", "x.newWebSocket(r, l)",
      "new Request.Builder()", "new URL(\"http://a\")", "import java.net.Socket;"};
    for (String b : bad) check(host.matcher(b).find() || net.matcher(b).find(), "the network detector missed: " + b);
    String[] fine = {"FontManager.getRunescapeBoldFont()", "the OSRS Wiki's public prices", "java.net.URLEncoder.encode(x)", "\"EVI prices: \"", "this.wiki = wiki;"};
    for (String g : fine) check(!host.matcher(g).find() && !net.matcher(g).find(), "the network detector misfired on: " + g);
    List<String> wiki = new ArrayList<>(), calls = new ArrayList<>();
    for (Filepath f : TestFiles.files(src, ".java")) {
      String text = TestFiles.text(f);
      if (host.matcher(text).find()) wiki.add(f.getFileName());
      if (net.matcher(text).find()) calls.add(f.getFileName());
    }
    wiki.sort(null);
    calls.sort(null);
    check(wiki.equals(List.of("WikiPriceClient.java")), "the Wiki's host may appear only in WikiPriceClient: " + wiki);
    check(calls.equals(List.of("WikiPriceClient.java")), "requests may be made only by WikiPriceClient: " + calls);

    // NO LOOPBACK ANYWHERE. The detector proves itself first, then reads every main source file, comments included.
    for (String b : new String[]{"\"http://127.0.0.1:51743/api/events\"", "// the bridge on 127.0.0.1", "String h = \"localhost\";", "int port = 51743;",
      "String h = \"[::1]\";", "bind(\"0.0.0.0\")", "InetAddress.getLoopbackAddress()"})
      check(LOOPBACK_ANYWHERE.matcher(b).find(), "the loopback detector missed: " + b);
    for (String g : new String[]{"https://prices.runescape.wiki/api/v2/osrs/", "String v = \"4.0.0\";", "long n = 517430L;", "price 1270001"})
      check(!LOOPBACK_ANYWHERE.matcher(g).find(), "the loopback detector misfired on: " + g);
    List<String> loopback = new ArrayList<>();
    int files = 0;
    for (Filepath f : TestFiles.files(src, ".java")) {
      files++;
      String text = TestFiles.text(f);
      java.util.regex.Matcher m = LOOPBACK_ANYWHERE.matcher(text);
      while (m.find()) loopback.add(f.getFileName() + ": " + m.group());
    }
    check(files > 50 && loopback.isEmpty(), "no class may name 127.0.0.1, localhost, 51743 or any loopback address (" + files + " files): " + loopback);
    // THE ONE HOST. WikiPriceClient builds every URL from HOST (https, the v2 API path) and nothing else: no other host,
    // scheme or URL appears in its literals but the User-Agent's public source page, which is never requested.
    String client = TestFiles.text(TestFiles.at(src, "com", "evi", "live", "market", "WikiPriceClient.java"));
    check("prices.runescape.wiki".equals(WikiPriceClient.HOST), "the one host: " + WikiPriceClient.HOST);
    List<String> addressy = new ArrayList<>();
    for (String lit : javaLiterals(client))
      if (lit.matches("(?is).*(https?:|wss?:|ftp:|\\.com|\\.net|\\.org|\\.wiki|\\.io|www\\.).*")) addressy.add(lit);
    check(addressy.equals(List.of("prices.runescape.wiki", "https://github.com/therealLeEvi/evi-live-plugin")),
      "WikiPriceClient's only addresses are the Wiki host and the User-Agent's source page: " + addressy);
    String code = javaCodeOnly(client);
    java.util.regex.Matcher hostCalls = java.util.regex.Pattern.compile("\\.host\\(([^)]*)\\)").matcher(code);
    List<String> hosts = new ArrayList<>();
    while (hostCalls.find()) hosts.add(hostCalls.group(1).trim());
    check(hosts.equals(List.of("HOST")), "every URL is built on HOST: " + hosts);
    check(!code.contains("followRedirects(true") && !code.contains("followSslRedirects(true") && code.contains(".followRedirects(false)")
      && code.contains(".followSslRedirects(false)"), "the Wiki client follows no redirect, so a request cannot be sent to another host");
    check(!javaLiterals(client).contains("http"), "and asks over https only");
    Properties props = new Properties();
    try (java.io.InputStream in = TestFiles.project().joinSegment("runelite-plugin.properties").openInputStream()) {
      props.load(in);
    }
    check(WikiPriceClient.VERSION.equals(System.getProperty("evi.gradleVersion")) && WikiPriceClient.VERSION.equals(props.getProperty("version")),
      "WikiPriceClient.VERSION (" + WikiPriceClient.VERSION + ") must equal build.gradle (" + System.getProperty("evi.gradleVersion")
        + ") and runelite-plugin.properties (" + props.getProperty("version") + ")");
  }
}
