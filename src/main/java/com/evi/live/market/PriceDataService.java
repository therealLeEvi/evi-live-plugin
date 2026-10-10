package com.evi.live.market;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import net.runelite.client.util.Filepath;

/**
 * The price-data layer's scheduler: it owns ONE plugin thread ({@value #THREAD_NAME}), the hourly archive, the item
 * list and the latest prices, and publishes what it knows as one immutable {@link MarketSnapshot}. Data only: in
 * this phase nothing EVI suggests reads it.
 *
 * <h3>What it fetches, through {@link WikiPriceClient} only, one request at a time, at least {@code gapMs} apart</h3>
 * <ol>
 *   <li>the item list ({@code /mapping}) when there is none, then once a day; kept on disk so a restart needs no request;</li>
 *   <li>the latest prices ({@code /latest}) every {@code latestEveryMs} -- ONLY WHILE LOGGED IN;</li>
 *   <li>each missing hour of the archive window ({@code /1h?timestamp=}), NEWEST FIRST, as priceArchive.mjs does:
 *       the first run backfills the whole window, a later login repairs only the gap, and in steady running one
 *       request at about five past each hour (an hour is asked for {@value #SETTLE_S} s after it closes).</li>
 * </ol>
 * A failed request is retried with back-off (10 s doubling to 5 min), never in a loop. The back-off is kept PER KIND of
 * request (item list, latest prices, hours): a success of one kind never resets another kind's back-off, and a kind
 * that is backing off never holds up the others (an item list the Wiki will not give does not stop the history). An
 * hour that fails {@value #HOUR_TRIES} times running is left for the next session rather than blocking the ones behind
 * it. Whatever the kind, two requests are always at least {@code gapMs} apart. Nothing waits by sleeping (the Hub
 * forbids {@code Thread.sleep}): every wait is a scheduled task.
 *
 * <h3>Two clocks</h3>
 * Every SPACING -- the gap between requests, each kind's back-off, the minute between {@code /latest} requests, the
 * rebuild interval -- is measured on a MONOTONIC clock ({@link #MONOTONIC_MS} in the plugin), so the computer's clock
 * being set back (a time sync, a manual change) cannot leave the layer waiting for a moment that has already passed.
 * The WALL clock is used only for what is about calendar time: which hours exist and have settled, the day of the item
 * list, the window kept, and the times published in the snapshot.
 *
 * <h3>Generations</h3>
 * Turning the plugin off and on makes a new service while the old one may still have a request out (shutdown never
 * interrupts it). {@link #start(PriceDataService)} is handed the old one: the new service does NOTHING -- no disk read,
 * no request -- until every earlier generation's thread has finished, and then keeps their spacing, so a toggle never
 * puts two requests in flight or two closer than {@code gapMs}. (WikiPriceClient's own guard is process-wide as well.)
 *
 * <h3>Threads</h3>
 * Every file and network operation runs on this service's own thread and is refused anywhere else; the client thread
 * only flips {@link #loggedIn}, and any thread may read {@link #snapshot()}. {@link #shutdown()} is orderly:
 * {@code shutdown()}, never {@code shutdownNow()}, so nothing is ever interrupted; a request in flight finishes
 * (bounded by the client's 20 s call limit) and nothing new starts.
 */
public final class PriceDataService {
  public static final String THREAD_NAME = "evi-prices";
  /** The price data's folder inside the plugin's data folder. */
  public static final String DIR = "prices";
  /** Ask for an hour this long after it closes, as priceArchive.mjs does (SETTLE_S). */
  static final long SETTLE_S = 300;
  static final long HOUR = 3600;
  static final long MAPPING_EVERY_MS = 24L * 60 * 60 * 1000;
  static final long REBUILD_WHILE_CATCHING_UP_MS = 5L * 60 * 1000;
  static final long REBUILD_EVERY_MS = 15L * 60 * 1000;
  static final long MAX_IDLE_MS = 60_000;
  static final long BACKOFF_FIRST_MS = 10_000;
  static final long BACKOFF_MAX_MS = 5L * 60 * 1000;
  static final int HOUR_TRIES = 3;
  /** How often a new generation looks whether the previous one has finished. */
  static final long HANDOVER_POLL_MS = 250;
  /** The shortest wait between two scheduler ticks, so no condition can ever turn into a busy loop. */
  static final long MIN_TICK_MS = 10;
  private static final int K_MAPPING = 0, K_LATEST = 1, K_HOUR = 2;
  private static final Pattern MAPPING = Pattern.compile("mapping-(\\d{1,9})\\.json\\.gz");
  /** The monotonic clock the plugin passes, in milliseconds. Only the difference between two readings means anything. */
  public static final LongSupplier MONOTONIC_MS = () -> Math.floorDiv(System.nanoTime(), 1_000_000L);
  private static final Pattern MAPPING_TMP = Pattern.compile("mapping-\\d{1,9}\\.json\\.gz\\.[0-9a-f-]{36}\\.tmp");

  /**
   * PROVISIONAL DEFAULTS (the maintainer has not decided these yet; SELF-CONTAINED-PLAN decisions 8, 10, 11):
   * 14 days of hourly history kept, 2.5 s between requests (the bridge's own polite pace), and the latest prices
   * once a minute while logged in (the Wiki's cache keeps /latest for 60 s, so faster buys nothing).
   */
  public static final class Settings {
    public final long gapMs;
    public final long latestEveryMs;
    public final int retentionHours;
    /** False in tests that step the scheduler by hand ({@link #step}). */
    final boolean autoSchedule;

    public Settings(long gapMs, long latestEveryMs, int retentionHours) {
      this(gapMs, latestEveryMs, retentionHours, true);
    }

    Settings(long gapMs, long latestEveryMs, int retentionHours, boolean autoSchedule) {
      this.gapMs = gapMs;
      this.latestEveryMs = latestEveryMs;
      this.retentionHours = retentionHours;
      this.autoSchedule = autoSchedule;
    }

    public static final Settings DEFAULT = new Settings(2500, 60_000, 336);
  }

  /** What one scheduler step did. */
  enum Work { MAPPING, LATEST, HOUR, WAIT, IDLE }

  private final Filepath root;
  private final HourlyArchive archive;
  private final WikiPriceClient wiki;
  /** Wall-clock time: hour buckets, the item list's day, the window, and the times published. */
  private final LongSupplier clock;
  /** Monotonic time ({@link #MONOTONIC_MS}): every spacing and back-off. */
  private final LongSupplier mono;
  private final Consumer<String> log;
  private final Settings settings;
  private final ScheduledThreadPoolExecutor executor;
  private volatile Thread thread;
  private volatile boolean closed;
  private volatile boolean loggedIn;
  private volatile MarketSnapshot snapshot = MarketSnapshot.EMPTY;

  // ---- price-data thread only
  private final Set<Long> known = new HashSet<>();
  private final Set<Long> skipped = new HashSet<>();
  private final Map<Long, Integer> hourFailures = new HashMap<>();
  private ItemCatalog catalog;
  private long catalogDay = Long.MIN_VALUE;
  private LatestPrices latest;
  /** When the latest prices were fetched: wall time, published in the snapshot. */
  private long latestAt = Long.MIN_VALUE;
  /** The same moment on the monotonic clock: the minute between two /latest requests is measured from this. */
  private long latestMono = Long.MIN_VALUE;
  private HourBucket newestHour;
  private MarketAggregates.Readings readings;
  private long builtAt = Long.MIN_VALUE;
  /** The last rebuild on the monotonic clock: the rebuild interval is measured from this, never from {@link #builtAt}. */
  private long builtMono = Long.MIN_VALUE;
  /** The window's stored hours when {@link #readings} were built (published as {@link MarketSnapshot#readingsHoursStored}). */
  private int builtStored;
  private boolean dirty;
  /**
   * Moves (never back) each time an hour joins {@link #known}: loaded from disk, stored after a fetch, answered empty, or found
   * written by the other client. Published as {@link MarketSnapshot#archiveRevision}, so a reader that keeps its own copy of
   * the archive's hours (the engine's recent-archive readings) knows to read them again -- a backfilled OLDER hour leaves the newest
   * stored hour unchanged, which is why that cannot be the signal.
   */
  private long archiveRevision;
  private boolean otherWroteNewer;
  /** No request of any kind before this (the spacing), on the monotonic clock. Volatile: the next generation reads it once this one has finished. */
  private volatile long nextAllowedAt = Long.MIN_VALUE;
  /** Per kind (K_MAPPING, K_LATEST, K_HOUR): no request of that kind before this (monotonic clock), and its failures in a row. */
  private final long[] notBefore = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
  private final int[] failures = new int[3];
  /** The previous generation, until it and every generation before it have finished. Volatile: walked by the next one. */
  private volatile PriceDataService predecessor;
  private boolean loaded;
  private long chain;
  private ScheduledFuture<?> pending;
  private boolean started;

  /**
   * @param clock wall-clock milliseconds ({@code System::currentTimeMillis})
   * @param monotonic monotonic milliseconds ({@link #MONOTONIC_MS}). Both are required, so no caller can make the spacing
   *     follow the wall clock by leaving one out.
   */
  public PriceDataService(Filepath pluginDir, WikiPriceClient wiki, LongSupplier clock, LongSupplier monotonic, Consumer<String> log) {
    this(pluginDir, wiki, clock, monotonic, log, Settings.DEFAULT);
  }

  public PriceDataService(Filepath pluginDir, WikiPriceClient wiki, LongSupplier clock, LongSupplier monotonic, Consumer<String> log,
    Settings settings) {
    this.root = pluginDir.joinSegment(DIR);
    this.archive = new HourlyArchive(root, log);
    this.wiki = wiki;
    this.clock = clock;
    this.mono = monotonic;
    this.log = log;
    this.settings = settings;
    this.executor = new ScheduledThreadPoolExecutor(1, r -> {
      Thread t = new Thread(r, THREAD_NAME);
      t.setDaemon(true); // never holds the client open
      thread = t;
      return t;
    });
    // After shutdown(), a tick still waiting for its time is dropped, not run; one already running finishes.
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    executor.setRemoveOnCancelPolicy(true);
  }

  /** The newest hour that has closed and settled at {@code nowMs}: its start, in unix seconds (nextMissingFor's {@code newest}). */
  static long newestClosedHour(long nowMs) {
    return Math.floorDiv(Math.floorDiv(nowMs, 1000) - SETTLE_S, HOUR) * HOUR - HOUR;
  }

  // ------------------------------------------------------------------------------------- any thread

  /** Loads what is on disk, then starts fetching. Returns at once; all of it runs on the price-data thread. */
  public void start() {
    start(null);
  }

  /**
   * As {@link #start()}, taking over from the service the previous {@code startUp()} made (null if none): nothing is
   * read or fetched until that one -- and any before it -- has finished, and the spacing it was keeping is kept.
   */
  public void start(PriceDataService previous) {
    if (previous == this) throw new IllegalArgumentException("a service cannot follow itself");
    if (previous != null) {
      previous.shutdown(); // idempotent; the caller has normally done it already
      predecessor = previous;
    }
    submit(() -> {
      if (started) return;
      started = true;
      ioCheck();
      if (settings.autoSchedule) schedule(0);
    });
  }

  /** The client thread says whether a player is logged in; {@code /latest} is fetched only while one is. */
  public void loggedIn(boolean in) {
    boolean was = loggedIn;
    loggedIn = in;
    if (in && !was && settings.autoSchedule) submit(() -> schedule(0)); // fetch the latest prices now, not at the next idle tick
  }

  /** Whether the client last said a player is logged in. */
  public boolean isLoggedIn() {
    return loggedIn;
  }

  /** What the layer knows now. Never blocks, never reads the disk. */
  public MarketSnapshot snapshot() {
    return snapshot;
  }

  /**
   * Every readable stored hour from {@code fromTs} (unix seconds) on, oldest first (the engine's recent-archive readings), read on the
   * price-data thread (the archive's files are touched nowhere else). Returns at once. Completes exceptionally when the layer
   * is shut down, or has not loaded yet (a previous generation still running, or no first tick). No request is made.
   */
  public java.util.concurrent.CompletableFuture<java.util.List<HourBucket>> readHours(long fromTs) {
    java.util.concurrent.CompletableFuture<java.util.List<HourBucket>> f = new java.util.concurrent.CompletableFuture<>();
    if (closed) {
      f.completeExceptionally(new IllegalStateException("the price layer is shut down"));
      return f;
    }
    try {
      executor.execute(() -> {
        try {
          ioCheck();
          if (predecessor != null || !loaded) throw new IllegalStateException("the price layer has not loaded its archive yet");
          f.complete(archive.read(fromTs, Long.MAX_VALUE));
        } catch (Throwable t) { // reaches the caller's future, never the game
          f.completeExceptionally(t);
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      f.completeExceptionally(e);
    }
    return f;
  }

  /** Stops taking work. Orderly: never interrupts; a request in flight finishes and nothing new starts. */
  public void shutdown() {
    closed = true;
    executor.shutdown();
  }

  public boolean isShutdown() {
    return executor.isShutdown();
  }

  /** True once shut down AND its thread has finished: nothing of this service can touch the network or the disk again. */
  public boolean isTerminated() {
    return executor.isTerminated();
  }

  /** Waits for the thread to finish after {@link #shutdown()}. Never call on the client thread. */
  public boolean awaitTermination(long timeoutMs) throws InterruptedException {
    return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
  }

  /** TESTS: runs one scheduler step on the price-data thread and says what it did. */
  Work step() throws Exception {
    return executor.submit(() -> tickOnce(clock.getAsLong())).get(60, TimeUnit.SECONDS);
  }

  /** TESTS: how long the scheduler would wait before its next tick if one finished now, worked out on the price-data thread. */
  long delay() throws Exception {
    return executor.submit(this::delayAfterTick).get(60, TimeUnit.SECONDS);
  }

  /** TESTS: waits for everything queued so far. */
  void awaitIdle() throws Exception {
    executor.submit(() -> { }).get(60, TimeUnit.SECONDS);
  }

  HourlyArchive archive() {
    return archive;
  }

  Thread thread() {
    return thread;
  }

  private void submit(Runnable task) {
    if (closed) return;
    try {
      executor.execute(() -> {
        try {
          task.run();
        } catch (Throwable t) { // never reaches the game
          log.accept("EVI prices: " + t);
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // shut down meanwhile: nothing to do
    }
  }

  // -------------------------------------------------------------------------------- price-data thread

  private void ioCheck() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("EVI price data work attempted on " + Thread.currentThread().getName() + ", not " + THREAD_NAME);
  }

  /** The one-shot chain: only the most recently scheduled tick runs; an older one finds its token stale and returns. */
  private void schedule(long delayMs) {
    if (closed) return;
    final long token = ++chain;
    ScheduledFuture<?> before = pending;
    if (before != null) before.cancel(false); // never with interrupt
    try {
      pending = executor.schedule(() -> {
        if (closed || token != chain) return;
        try {
          tickOnce(clock.getAsLong());
        } catch (Throwable t) {
          log.accept("EVI prices: " + t);
        }
        if (!closed && token == chain) schedule(delayAfterTick());
      }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // shut down meanwhile
    }
  }

  /**
   * How long until something could be due: the earliest moment any kind of request may go (its own back-off, and the
   * spacing after the last request of any kind), the next /latest, the next hour settling, or at most a minute.
   */
  private long delayAfterTick() {
    long now = clock.getAsLong(), m = mono.getAsLong();
    if (predecessor != null) return HANDOVER_POLL_MS;
    long due = m + MAX_IDLE_MS; // on the monotonic clock, like every spacing
    if (catalog == null || mappingStale(now)) due = Math.min(due, notBefore[K_MAPPING]);
    if (loggedIn) due = Math.min(due, Math.max(notBefore[K_LATEST], latestMono == Long.MIN_VALUE ? m : latestMono + settings.latestEveryMs));
    if (nextMissingHour(now) != null) due = Math.min(due, notBefore[K_HOUR]);
    long settles = (newestClosedHour(now) + 2 * HOUR + SETTLE_S) * 1000 + 1000; // when the next hour has settled: WALL time
    due = Math.min(due, m + (settles - now));
    due = Math.max(due, Math.max(m, nextAllowedAt)); // the spacing applies to whatever goes next
    return Math.max(MIN_TICK_MS, due - m);
  }

  /**
   * True while an earlier generation (or one before it) is still running. Once none is, this one takes over the
   * latest spacing any of them was keeping and lets them go; from then on it is false without looking again.
   */
  private boolean predecessorBusy() {
    PriceDataService p = predecessor;
    if (p == null) return false;
    long pace = nextAllowedAt;
    for (PriceDataService q = p; q != null; q = q.predecessor) {
      if (!q.executor.isTerminated()) return true;
      pace = Math.max(pace, q.nextAllowedAt);
    }
    nextAllowedAt = pace;
    predecessor = null;
    return false;
  }

  private void load() {
    try {
      root.createDirectories();
      for (Long ts : archive.storedHours()) learned(ts);
      for (Long ts : archive.answeredEmpty()) learned(ts);
      loadMappingCache();
      NavigableSet<Long> stored = archive.storedHours();
      for (Long ts : stored.descendingSet()) {
        HourBucket b = archive.read(ts);
        if (b != null) {
          newestHour = b;
          break;
        }
      }
      long now = clock.getAsLong();
      prune(now);
      if (!stored.isEmpty()) rebuild(now);
    } catch (IOException e) {
      log.accept("EVI prices: could not read the price folder: " + e.getMessage());
    }
    publish(clock.getAsLong());
  }

  /** One step: at most ONE request, then any due rebuild or prune, then a fresh snapshot. */
  Work tickOnce(long now) throws IOException {
    ioCheck();
    if (predecessorBusy()) return Work.WAIT;
    if (!loaded) {
      loaded = true;
      started = true;
      load();
    }
    long m = mono.getAsLong();
    if (m < nextAllowedAt) return Work.WAIT;
    Work w = next(now, m);
    if (w == Work.MAPPING) fetchMapping(now);
    else if (w == Work.LATEST) fetchLatest(now, m);
    else if (w == Work.HOUR) fetchHour(nextMissingHour(now));
    long after = clock.getAsLong();
    if (otherWroteNewer) adoptNewestFromDisk(after);
    if (rebuildDue(after, mono.getAsLong())) {
      try {
        rebuild(after);
      } catch (IOException e) {
        builtAt = after; // tried: not again on every tick; the next attempt comes with the next due rebuild
        builtMono = mono.getAsLong();
        dirty = false;
        log.accept("EVI prices: the price readings could not be rebuilt: " + e.getMessage());
      }
    }
    publish(after);
    return w;
  }

  /**
   * The other client stored an hour newer than this one's newest: read it, so "the latest hour" follows the archive
   * whoever fetched it, and prune to the new window.
   */
  private void adoptNewestFromDisk(long now) throws IOException {
    otherWroteNewer = false;
    for (Long ts : archive.storedHours().descendingSet()) {
      if (newestHour != null && ts <= newestHour.ts) return;
      HourBucket b = archive.read(ts);
      if (b != null) {
        newestHour = b;
        prune(now);
        return;
      }
    }
  }

  /**
   * What to ask for next, in priority order, skipping any kind still backing off: WAIT when something is due but every
   * due kind is backing off, IDLE when nothing is due. Reads state; changes nothing but the stat cache. {@code now} is wall
   * time (which hours, which day), {@code m} monotonic (every back-off, and the minute between /latest requests).
   */
  private Work next(long now, long m) {
    boolean held = false;
    if (catalog == null) {
      if (m >= notBefore[K_MAPPING]) return Work.MAPPING;
      held = true; // no item list, and the Wiki is refusing it for now: the prices and the history need none, so they go on
    }
    if (loggedIn && (latestMono == Long.MIN_VALUE || m - latestMono >= settings.latestEveryMs)) {
      if (m >= notBefore[K_LATEST]) return Work.LATEST;
      held = true;
    }
    if (nextMissingHour(now) != null) {
      if (m >= notBefore[K_HOUR]) return Work.HOUR;
      held = true;
    }
    if (catalog != null && mappingStale(now)) { // a day-old list: refreshed when nothing else waits
      if (m >= notBefore[K_MAPPING]) return Work.MAPPING;
      held = true;
    }
    return held ? Work.WAIT : Work.IDLE;
  }

  private boolean mappingStale(long now) {
    return Math.floorDiv(now, MAPPING_EVERY_MS) > catalogDay;
  }

  /** The newest hour of the window not yet stored or answered (nextMissingFor), or null. Another client's file counts as stored. */
  Long nextMissingHour(long now) {
    long newest = newestClosedHour(now);
    long oldest = newest - (long) settings.retentionHours * HOUR;
    for (long ts = newest; ts >= oldest; ts -= HOUR) {
      if (known.contains(ts) || skipped.contains(ts)) continue;
      if (archive.done(ts)) { // written by the other client meanwhile
        learned(ts);
        dirty = true;
        if (newestHour == null || ts > newestHour.ts) otherWroteNewer = true;
        continue;
      }
      return ts;
    }
    return null;
  }

  /**
   * After a request of one kind: the spacing for every kind, and that kind's own back-off. A success resets ONLY its own
   * kind, so a /latest that works every minute cannot keep restarting the back-off of a /1h that does not.
   */
  private void requested(int kind, boolean ok) {
    long end = mono.getAsLong();
    nextAllowedAt = end + settings.gapMs;
    if (ok) {
      failures[kind] = 0;
      notBefore[kind] = Long.MIN_VALUE;
    } else {
      int n = ++failures[kind];
      long backoff = Math.min(BACKOFF_MAX_MS, BACKOFF_FIRST_MS << Math.min(20, n - 1));
      notBefore[kind] = end + Math.max(settings.gapMs, backoff);
    }
  }

  private void fetchMapping(long now) {
    String body;
    try {
      body = wiki.mapping();
      ItemCatalog c = ItemCatalog.parse(body);
      if (c.size() == 0) throw new IOException("the item list was empty");
      catalog = c;
      catalogDay = Math.floorDiv(now, MAPPING_EVERY_MS);
      requested(K_MAPPING, true);
    } catch (IOException | RuntimeException e) {
      requested(K_MAPPING, false);
      log.accept("EVI prices: item list not fetched (" + e.getMessage() + "); retrying in " + (notBefore[K_MAPPING] - mono.getAsLong()) / 1000 + " s");
      return;
    }
    saveMappingCache(body, catalogDay);
  }

  private void fetchLatest(long now, long m) {
    try {
      latest = WikiJson.latest(wiki.latest());
      latestAt = now;
      latestMono = m;
      requested(K_LATEST, true);
    } catch (IOException | RuntimeException e) {
      requested(K_LATEST, false);
      log.accept("EVI prices: latest prices not fetched (" + e.getMessage() + ")");
    }
  }

  private void fetchHour(Long ts) {
    if (ts == null) return;
    HourBucket b;
    try {
      b = WikiJson.hour(wiki.hour(ts));
    } catch (IOException | RuntimeException e) {
      hourFailed(ts, "not fetched", e);
      return;
    }
    // The Wiki may answer with a different hour than asked for: store what it says it is, and remember the asked-for
    // hour as answered so an empty or odd one is not asked for forever (priceArchive.mjs step()).
    try {
      if (b != null && b.size() > 0 && b.ts > 0 && b.ts % HOUR == 0 && !known.contains(b.ts)) {
        archive.store(b);
        learned(b.ts);
        dirty = true;
        if (newestHour == null || b.ts > newestHour.ts) {
          newestHour = b;
          prune(clock.getAsLong());
        }
      }
      if (!known.contains(ts)) {
        archive.markEmpty(ts);
        learned(ts);
      }
    } catch (IOException | RuntimeException e) {
      // A disk that cannot take the hour (full, read-only, held by another program) must not turn into the same request
      // every 2.5 s: it backs off and is given up for the session exactly like a failed request.
      hourFailed(ts, "could not be saved", e);
      return;
    }
    requested(K_HOUR, true); // only now: a fetch whose hour could not be kept is a failure, and its back-off keeps doubling
    hourFailures.remove(ts);
  }

  /** A failed hour: back off, and after {@value #HOUR_TRIES} failures in a row leave it for the next session. */
  private void hourFailed(long ts, String what, Exception e) {
    requested(K_HOUR, false);
    int n = hourFailures.merge(ts, 1, Integer::sum);
    if (n >= HOUR_TRIES) {
      skipped.add(ts);
      log.accept("EVI prices: hour " + ts + " failed " + n + " times (" + what + ": " + e.getMessage() + "); left for the next session");
    } else {
      log.accept("EVI prices: hour " + ts + " " + what + " (" + e.getMessage() + ")");
    }
  }

  private boolean rebuildDue(long now, long m) {
    if (known.isEmpty() && readings == null) return false;
    if (readings == null) return dirty;
    if (dirty && (!catchingUp(now) || m - builtMono >= REBUILD_WHILE_CATCHING_UP_MS)) return true;
    return m - builtMono >= REBUILD_EVERY_MS;
  }

  private boolean catchingUp(long now) {
    long newest = newestClosedHour(now);
    long oldest = newest - (long) settings.retentionHours * HOUR;
    for (long ts = newest; ts >= oldest; ts -= HOUR) if (!known.contains(ts) && !skipped.contains(ts)) return true;
    return false;
  }

  private void rebuild(long now) throws IOException {
    readings = MarketAggregates.build(archive, now);
    builtStored = window(now)[0];
    builtAt = now;
    builtMono = mono.getAsLong();
    dirty = false;
  }

  private void prune(long now) throws IOException {
    long oldestKept = newestClosedHour(now) - (long) settings.retentionHours * HOUR;
    archive.prune(oldestKept, now);
    known.removeIf(ts -> ts < oldestKept);
    skipped.removeIf(ts -> ts < oldestKept);
  }

  /**
   * Publishes what is known. {@code catchingUp} means hours are still to be FETCHED this session -- not merely absent:
   * an hour given up after {@value #HOUR_TRIES} failures is counted in {@code hoursUnavailable} instead, so one hour the
   * Wiki will not give cannot leave the layer "catching up" until that hour ages out of the window two weeks later.
   */
  /** An hour now known to be stored or answered: {@link #archiveRevision} moves when it is new. */
  private void learned(long ts) {
    if (known.add(ts)) archiveRevision++;
  }

  private void publish(long now) {
    int window = settings.retentionHours + 1;
    int[] w = window(now);
    int stored = w[0], unavailable = w[1];
    snapshot = new MarketSnapshot(catalog, latest, latestAt == Long.MIN_VALUE ? 0 : latestAt, newestHour, readings,
      builtAt == Long.MIN_VALUE ? 0 : builtAt, stored, unavailable, window, newestHour == null ? Long.MIN_VALUE : newestHour.ts,
      stored + unavailable < window, archiveRevision, readings == null ? 0 : builtStored);
  }

  /** The backfill window's hours at {@code now}: {stored (or answered), given up this session}. */
  private int[] window(long now) {
    long newest = newestClosedHour(now);
    int stored = 0, unavailable = 0;
    for (long ts = newest - (long) settings.retentionHours * HOUR; ts <= newest; ts += HOUR) {
      if (known.contains(ts)) stored++;
      else if (skipped.contains(ts)) unavailable++;
    }
    return new int[]{stored, unavailable};
  }

  // -------------------------------------------------------------------------------- the item list on disk

  private void loadMappingCache() throws IOException {
    long bestDay = Long.MIN_VALUE;
    Filepath best = null;
    if (root.isDirectory()) {
      try (Stream<Filepath> s = root.walk(1)) {
        for (Filepath f : (Iterable<Filepath>) s::iterator) {
          Matcher m = MAPPING.matcher(f.getFileName());
          if (m.matches() && Long.parseLong(m.group(1)) > bestDay) {
            bestDay = Long.parseLong(m.group(1));
            best = f;
          }
        }
      }
    }
    pruneMappingFiles(best == null ? Long.MIN_VALUE : bestDay, clock.getAsLong());
    if (best == null) return;
    try (InputStream in = best.openInputStream(); InputStream z = new GZIPInputStream(in)) {
      ItemCatalog c = ItemCatalog.parse(new String(z.readAllBytes(), StandardCharsets.UTF_8));
      if (c.size() > 0) {
        catalog = c;
        catalogDay = bestDay;
      }
    } catch (IOException | RuntimeException e) {
      log.accept("EVI prices: the saved item list is damaged; fetching a new one");
      best.deleteIfExists();
    }
  }

  private void saveMappingCache(String body, long day) {
    try {
      root.createDirectories();
      ArchiveFiles.writeNew(root.joinSegment("mapping-" + day + ".json.gz"), HourlyArchive.gzip(body));
      pruneMappingFiles(day, clock.getAsLong());
    } catch (IOException e) {
      log.accept("EVI prices: could not save the item list: " + e.getMessage());
    }
  }

  /**
   * Keeps one saved item list: removes lists older than {@code keepDay}, and temporary files a crash left behind while
   * saving one (older than {@link HourlyArchive#STALE_TMP_MS}; a live save's own is never that old).
   */
  private void pruneMappingFiles(long keepDay, long nowMs) throws IOException {
    if (!root.isDirectory()) return;
    try (Stream<Filepath> s = root.walk(1)) {
      for (Filepath f : (Iterable<Filepath>) s::iterator) {
        Matcher m = MAPPING.matcher(f.getFileName());
        if (m.matches() && Long.parseLong(m.group(1)) < keepDay) f.deleteIfExists();
        else if (MAPPING_TMP.matcher(f.getFileName()).matches() && nowMs - f.getLastModifiedTime().toMillis() > HourlyArchive.STALE_TMP_MS) f.deleteIfExists();
      }
    }
  }
}
