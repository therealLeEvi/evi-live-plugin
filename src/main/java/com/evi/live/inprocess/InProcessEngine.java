package com.evi.live.inprocess;

import com.evi.live.engine.AccountView;
import com.evi.live.engine.Engine;
import com.evi.live.engine.ResponseJson;
import com.evi.live.journal.Offer;
import com.evi.live.journal.PluginJournal;
import com.evi.live.journal.Tax;
import com.evi.live.market.MarketAggregates;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.runelite.client.util.Filepath;

/**
 * THE IN-PROCESS ENGINE: the plugin answering its own suggestion poll, from its own journal and the OSRS Wiki's public prices.
 *
 * <p>Each poll's query is handed to {@link #request}, which returns at once;
 * the work runs on ONE plugin-owned daemon thread, {@value #THREAD_NAME}: the journal is read on the journal's own thread,
 * the archive on the price layer's, {@link Engine#decide} runs here, and the body is written by the production serialiser
 * ({@link ResponseJson}) -- the answer shape the plugin's poll path, DTOs and panel read.
 * The composition is {@link EngineFeed}'s. This thread refuses to run on the client
 * thread or the Swing EDT. At most one request waits: a newer one replaces it (the older one's future completes with null).
 *
 * <p>THE FIRST-RUN GATE ON BUYS (plan decision 7, risk 4: ranking on a short window was measured less safe). Until the
 * readings the engine ranks on rest on {@link #BASIS_HOURS} hours of the plugin's own archive -- the engine's own constant,
 * {@link MarketAggregates#ROBUST_PRICE_HOURS} -- the query is asked WITHOUT the buy tiers ({@link #withoutBuyTiers}): no
 * history, market, step-down or further positions, so no buy and no "reachable" figure. Everything about what the player
 * already has still speaks from its own data: the holding and idle-stock tiers (they price from /latest), the open item's
 * price, the running offers' prices and fill estimates, and the whole advice channel (relist, below break-even, margin
 * gone, buy progress, holdings). Nothing is fabricated for the missing hours; the sidebar says how many are in
 * ({@link #backfillLine}).
 *
 * <p>No network of its own and no file but an optional {@value #PREFERENCES} in the plugin's data folder (the bridge's
 * preferences.json shape: spread, focus, blocked items, capture cap; without it, the bridge's defaults) and, when the plugin hands
 * it {@link SuggestionRecords}, the suggestion log those keep (the id "I took this one" echoes, as server.mjs logs each answer).
 * The sidebar's Block list is handed in too and merged into the preferences' blocked items at every decide (decision 12): the
 * list of the account the query names, only (blocks are per character since 8 Oct 2026; the bridge's list is one for the machine).
 */
public final class InProcessEngine implements Answerer {
  public static final String THREAD_NAME = "evi-engine";
  /** The optional preferences file, in the plugin's data folder. */
  public static final String PREFERENCES = "preferences.json";
  /** The hours the engine's steady prices are built over: buys wait until the readings rest on this many. */
  public static final int BASIS_HOURS = MarketAggregates.ROBUST_PRICE_HOURS;
  static final long JOURNAL_WAIT_MS = 30_000;
  static final long ARCHIVE_WAIT_MS = 120_000;
  /**
   * How old the latest prices may be before buys are held back (review, 10 Oct; the maintainer chose ten minutes). They arrive about
   * once a minute while logged in and are kept when a fetch fails, so past this the Wiki is not answering (or the player was
   * logged out) and a buy would be quoted from old prices. The market tier drops prints over an hour old item by item; the
   * history tier had no such check. Sells and the advice about the player's own offers still speak.
   */
  public static final long STALE_PRICES_MS = 10 * 60_000L;

  /** One answer: the body the bridge would have sent, or why there is none, and what the sidebar says about price data. */
  public static final class Answer {
    /** The query this answers. */
    public final String query;
    /** The suggestion body ({@code GET /api/suggestion}'s shape), or null when there is none ({@link #unavailable} says why). */
    public final String body;
    /** A plain sentence for the suggestion card when there is no body; null when there is one. */
    public final String unavailable;
    /** The sidebar's price-history line while buys wait for the basis; null once they no longer do (or nothing is known). */
    public final String backfillLine;
    /** Whether the buy tiers were asked. */
    public final boolean buysReady;
    /** When the latest prices were fetched (epoch ms), or 0 before any. */
    public final long latestFetchedAtMs;
    /** The buy tiers were not asked because the latest prices are older than {@link #STALE_PRICES_MS}. */
    public final boolean pricesStale;

    public Answer(String query, String body, String unavailable, String backfillLine, boolean buysReady, long latestFetchedAtMs) {
      this(query, body, unavailable, backfillLine, buysReady, latestFetchedAtMs, false);
    }

    public Answer(String query, String body, String unavailable, String backfillLine, boolean buysReady, long latestFetchedAtMs,
                  boolean pricesStale) {
      this.pricesStale = pricesStale;
      this.query = query;
      this.body = body;
      this.unavailable = unavailable;
      this.backfillLine = backfillLine;
      this.buysReady = buysReady;
      this.latestFetchedAtMs = latestFetchedAtMs;
    }
  }

  // ------------------------------------------------------------------------------------------- pure

  /** Whether the readings the engine ranks on rest on the full basis: the window's stored hours when they were BUILT. */
  public static boolean buysReady(EngineFeed.Market m) {
    return m != null && m.readingsHoursStored >= BASIS_HOURS;
  }

  /**
   * The sidebar's line while buys wait for the basis (null once they do not). ASCII only; a market-wide count of hours, never
   * anything from the player's own trading.
   */
  public static String backfillLine(EngineFeed.Market m) {
    if (m == null || buysReady(m)) return null;
    int have = Math.min(Math.max(0, m.hoursStored), BASIS_HOURS);
    if (!m.catchingUp && m.hoursStored < BASIS_HOURS && m.hoursUnavailable > 0)
      return "Price history: " + have + " of " + BASIS_HOURS + " hours. The OSRS Wiki did not send the rest this time; EVI asks again"
        + " when RuneLite next starts. Buy suggestions start once it is complete.";
    return "Loading price history: " + have + " of " + BASIS_HOURS + " hours. Buy suggestions start when it's done.";
  }

  /** Whether the latest prices are too old to quote a buy from at {@code now}: none at all counts as not stale (nothing is quoted). */
  public static boolean pricesStale(long latestFetchedAtMs, long now) {
    return latestFetchedAtMs > 0 && now - latestFetchedAtMs > STALE_PRICES_MS;
  }

  /** The sidebar line while buys wait for fresh prices. ASCII only. */
  public static String staleLine(long latestFetchedAtMs, long now) {
    long minutes = Math.max(0, (now - latestFetchedAtMs) / 60_000L);
    return "The latest prices from the OSRS Wiki are " + minutes + " minutes old. Buy suggestions are paused until fresh prices"
      + " arrive (they load while you are logged in).";
  }

  /**
   * The query with the buy tiers switched off: {@code includeMarket} dropped and {@code source=market} (so the history tier is
   * not asked either). Under those two the engine runs no history chain, no market tier, no Auto step-down, no further
   * positions and no reachable probe; every sell-side tier and the advice channel are untouched.
   */
  public static String withoutBuyTiers(String query) {
    StringBuilder out = new StringBuilder();
    String q = query == null ? "" : query.startsWith("?") ? query.substring(1) : query;
    for (String part : q.split("&", -1)) {
      if (part.isEmpty()) continue;
      Engine.Query one = Engine.Query.parse(part); // the name as the engine decodes it (URLSearchParams)
      if (one.has("includeMarket") || one.has("source")) continue;
      if (out.length() > 0) out.append('&');
      out.append(part);
    }
    if (out.length() > 0) out.append('&');
    return out.append("source=market").toString();
  }

  /**
   * The break-even sell price of each of this account's live SELL offers whose cost basis the journal knows -- exactly the
   * figure the relist advice compares the market with ({@link Tax#breakEvenSellPrice} on the view's cost basis). The plugin
   * reads it to give each offer ONE voice: below break-even the relist sentence, never its own "take the current price".
   */
  public static Map<Integer, Long> sellBreakEven(AccountView view) {
    Map<Integer, Long> out = new TreeMap<>();
    for (Offer o : view.liveSells) {
      Double paid = view.costBasis.get(o.itemId);
      Long be = paid != null && paid > 0 ? Tax.breakEvenSellPrice(o.itemId, paid) : null;
      if (be != null) out.put(o.itemId, be);
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------- the thread

  private final class Job implements Runnable {
    final String query;
    final long at;
    final CompletableFuture<Answer> done = new CompletableFuture<>();

    Job(String query, long at) {
      this.query = query;
      this.at = at;
    }

    @Override public void run() {
      InProcessEngine.this.run(this);
    }
  }

  private final Filepath pluginDir;
  private final EngineFeed.JournalSource journal;
  private final EngineFeed.MarketSource market;
  private final Function<String, JsonElement> parser;
  private final Consumer<String> log;
  private final BooleanSupplier onUiThread;
  private final Supplier<Long> profitSince;
  /** Null in production (the archive serves the sell-support reading; no per-item request is ever made). */
  private final Engine.SellSeries series;
  /** The plugin's own Block-button list for the account a query names (null/"" for none), read at every decide. Per character. */
  private final Function<String, ? extends java.util.Collection<Integer>> blocked;
  /** The suggestion ids and "I took this one" (null: no id is issued, as before decision 12's persistence). */
  private final SuggestionRecords records;
  /** server.mjs lastInventory: the bag the idle-stock tier last read, per query account ("" for none), with when. */
  private final Map<String, Reading> lastInventory = new java.util.concurrent.ConcurrentHashMap<>();

  private static final class Reading {
    final Map<Integer, Double> items;
    final long at;

    Reading(Map<Integer, Double> items, long at) {
      this.items = items;
      this.at = at;
    }
  }

  /** server.mjs /personal-use: a bag read more than this long ago is ignored (the mark falls back to the blanket kind). */
  public static final long KEPT_FRESH_MS = 60_000;
  private final ThreadPoolExecutor executor;
  private volatile Thread thread;
  private volatile boolean closed;
  /** How many times {@link Engine#decide} ran, and on which thread it last did (diagnostics and tests). */
  private volatile int decided;
  private volatile String decidedOn;

  // ---- engine thread only
  private final EngineFeed feed;

  /**
   * @param pluginDir   the plugin's data folder (only {@value #PREFERENCES} is read from it)
   * @param parser      JSON text to a tree: the plugin passes the client's injected Gson
   * @param log         one line of local diagnostics (the client log)
   * @param onUiThread  true on the client thread or the Swing EDT, where this engine refuses to run
   * @param profitSince the player's last profit-line reset (epoch ms), or null for never
   */
  public InProcessEngine(Filepath pluginDir, EngineFeed.JournalSource journal, EngineFeed.MarketSource market, Function<String, JsonElement> parser,
                         Consumer<String> log, BooleanSupplier onUiThread, Supplier<Long> profitSince) {
    this(pluginDir, journal, market, parser, log, onUiThread, profitSince, null);
  }

  /**
   * TESTS ONLY: as above, with a per-item series for the sell-support reading -- the parity test hands in the bridge's recorded
   * Wiki series so the transcripts whose buys needed one can be replayed. The plugin never passes one: it makes no per-item request.
   */
  public InProcessEngine(Filepath pluginDir, EngineFeed.JournalSource journal, EngineFeed.MarketSource market, Function<String, JsonElement> parser,
                         Consumer<String> log, BooleanSupplier onUiThread, Supplier<Long> profitSince, Engine.SellSeries series) {
    this(pluginDir, journal, market, parser, log, onUiThread, profitSince, series, account -> java.util.Collections.emptyList(), null);
  }

  /**
   * The plugin's engine with the sidebar's stores (SELF-CONTAINED decision 12): {@code blocked}, the Block button's items of the
   * account a query names (merged into the preferences' blocked list at every decide, for that query only), and {@code records}, which issues the suggestion's id and reads back
   * whether it was taken. {@code series}: tests only, as above.
   */
  public InProcessEngine(Filepath pluginDir, EngineFeed.JournalSource journal, EngineFeed.MarketSource market, Function<String, JsonElement> parser,
                         Consumer<String> log, BooleanSupplier onUiThread, Supplier<Long> profitSince, Engine.SellSeries series,
                         Function<String, ? extends java.util.Collection<Integer>> blocked, SuggestionRecords records) {
    this.series = series;
    this.blocked = blocked == null ? account -> java.util.Collections.emptyList() : blocked;
    this.records = records;
    this.pluginDir = pluginDir;
    this.journal = journal;
    this.market = market;
    this.parser = parser;
    this.log = log;
    this.onUiThread = onUiThread;
    this.profitSince = profitSince;
    this.feed = new EngineFeed(market, ARCHIVE_WAIT_MS);
    this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
      Thread t = new Thread(r, THREAD_NAME);
      t.setDaemon(true); // never holds the client open
      thread = t;
      return t;
    }, (r, ex) -> {
      if (ex.isShutdown()) {
        ((Job) r).done.complete(null);
        return;
      }
      Runnable waiting = ex.getQueue().poll(); // the newest request replaces the one still waiting
      if (waiting != null) ((Job) waiting).done.complete(null);
      if (!ex.getQueue().offer(r)) ((Job) r).done.complete(null);
    });
  }

  /**
   * Hands one poll's query over and returns at once (any thread; the plugin calls it from its poll thread). The future
   * completes with the answer, or with null if a newer request replaced this one or the engine is shut down.
   */
  @Override public CompletableFuture<Answer> request(String query, long at) {
    Job j = new Job(query == null ? "" : query, at);
    if (closed) {
      j.done.complete(null);
      return j.done;
    }
    try {
      executor.execute(j);
    } catch (RejectedExecutionException e) {
      j.done.complete(null);
    }
    return j.done;
  }

  /** How many times {@link Engine#decide} has run. */
  public int decisions() {
    return decided;
  }

  /** The thread {@link Engine#decide} last ran on (always {@value #THREAD_NAME}), or null before the first. */
  public String lastDecidedOn() {
    return decidedOn;
  }

  /**
   * The kept count for a "Yours, not stock" mark pressed at {@code now} by {@code account} (null: none named), as server.mjs's
   * /personal-use reads it: the bag the idle-stock tier last read for that account (else for a poll naming none), if under
   * {@link #KEPT_FRESH_MS} old; with no account named and that reading absent or stale, the bag of the ONE account read in the
   * last {@link #KEPT_FRESH_MS} (two or more: nobody can say who pressed, so no count -- the 8 Oct 2026 bridge fix). Null when
   * no such bag holds the item: the blanket mark. Any thread. The plugin names its own account, which is always exact here.
   */
  public Double keptFor(String account, int itemId, long now) {
    boolean named = account != null && !account.isEmpty();
    Reading seen = lastInventory.get(named ? account : "");
    if (seen == null) seen = lastInventory.get(""); // lastInventory.get(b.account||'')||lastInventory.get('')
    Map<Integer, Double> fresh = seen != null && now - seen.at < KEPT_FRESH_MS ? seen.items : null;
    if (fresh == null && !named) {
      Reading only = null;
      int recent = 0;
      for (Reading r : lastInventory.values())
        if (now - r.at < KEPT_FRESH_MS) {
          only = r;
          recent++;
        }
      if (recent == 1) fresh = only.items;
    }
    if (fresh == null) return null;
    Double q = fresh.get(itemId);
    return q != null && Double.isFinite(q) ? q : null;
  }

  /** Stops taking requests; one already running finishes. Never interrupts. */
  public void shutdown() {
    closed = true;
    executor.shutdown();
  }

  public boolean isShutdown() {
    return executor.isShutdown();
  }

  /** Tests only (it blocks; never the client thread): waits for the engine's thread to finish after {@link #shutdown}. */
  public boolean awaitTermination(long ms) throws InterruptedException {
    return executor.awaitTermination(ms, TimeUnit.MILLISECONDS);
  }

  private void run(Job j) {
    try {
      Thread t = Thread.currentThread();
      if (t != thread || onUiThread.getAsBoolean()) throw new IllegalStateException("EVI engine work attempted on " + t.getName() + ", not " + THREAD_NAME);
      j.done.complete(once(j));
    } catch (Throwable e) { // a failure here must never reach the game; the poll shows a plain sentence
      log.accept("EVI engine: " + e);
      j.done.complete(new Answer(j.query, null, "EVI could not work out a suggestion just now. Details are in the client log.", null, false, 0));
    }
  }

  private Answer once(Job j) {
    EngineFeed.Market m = market.market(j.at);
    if (m == null) return new Answer(j.query, null, "EVI's price data has not started. Details are in the client log.", null, false, 0);
    String backfill = backfillLine(m);
    boolean ready = buysReady(m);
    if (m.catalog == null) return new Answer(j.query, null, "Loading the item list from the OSRS Wiki...", backfill, ready, m.latestFetchedAtMs);
    if (m.latest == null)
      return new Answer(j.query, null, "Waiting for the latest prices from the OSRS Wiki. They load while you are logged in.", backfill, ready, m.latestFetchedAtMs);
    PluginJournal.EngineView jv;
    try {
      jv = EngineFeed.await(journal.view(j.at), JOURNAL_WAIT_MS);
    } catch (Exception e) {
      log.accept("EVI engine: the journal could not be read (" + EngineFeed.why(e) + ")");
      jv = null;
    }
    if (jv == null) return new Answer(j.query, null, "EVI could not read its trade record just now. Details are in the client log.", backfill, ready, m.latestFetchedAtMs);
    feed.refresh(m, j.at, new JsonObject());
    // Buys wait for fresh prices as they wait for the basis: the same query without the buy tiers, and a line saying why.
    boolean stale = ready && pricesStale(m.latestFetchedAtMs, j.at);
    if (stale) {
      ready = false;
      backfill = staleLine(m.latestFetchedAtMs, j.at);
    }
    String[] prefsSource = {null};
    Engine.Query q = Engine.Query.parse(ready ? j.query : withoutBuyTiers(j.query));
    // the Block list of the account this query is for (per character); none named: none
    Engine.Prefs prefs = EngineFeed.withBlocked(EngineFeed.readPreferences(pluginDir.joinSegment(PREFERENCES), PREFERENCES, parser, prefsSource),
      blocked.apply(q.get("account")));
    AccountView view = AccountView.of(jv.state, jv.offers, q.get("account"), j.at);
    decidedOn = Thread.currentThread().getName();
    decided++;
    // The opt-in forecast: ~6 hours only, from the archive up to its newest stored hour (the ~1 hour and Overnight settings are retired).
    Engine.Response r = EngineFeed.decide(q, view, jv.state, jv.offers, feed.marketData(m), prefs, series, feed.forecastHook(m, m.newestStoredTs, null),
      profitSince.get(), j.at);
    String account = q.get("account") == null || q.get("account").isEmpty() ? null : q.get("account"); // get('account')||undefined
    if (r.inventoryRead != null) lastInventory.put(account == null ? "" : account, new Reading(r.inventoryRead, j.at));
    if (r.failed()) {
      log.accept("EVI engine: no answer (" + r.error + ")");
      return new Answer(j.query, null, "EVI could not work out a suggestion just now. Details are in the client log.", backfill, ready, m.latestFetchedAtMs, stale);
    }
    JsonObject body = ResponseJson.response(r);
    // The handle "I took this one" echoes (server.mjs: suggestion.id, suggestion.accepted from the log), added after the body is
    // written, as the bridge adds them after the suggestion is built. Absent when the log line could not be written.
    if (records != null && r.suggestion != null && body.get("suggestion") != null && body.get("suggestion").isJsonObject()) {
      String id = records.record(account, r.suggestion, r.demotedPicks, j.at);
      if (id != null) {
        body.getAsJsonObject("suggestion").addProperty("id", id);
        body.getAsJsonObject("suggestion").addProperty("accepted", records.wasAccepted(id));
      }
    }
    JsonObject be = new JsonObject();
    for (Map.Entry<Integer, Long> e : sellBreakEven(view).entrySet()) be.addProperty(String.valueOf(e.getKey()), e.getValue());
    body.add("sellBreakEven", be);
    return new Answer(j.query, body.toString(), null, backfill, ready, m.latestFetchedAtMs, stale);
  }
}
