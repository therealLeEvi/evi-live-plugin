package com.evi.live.inprocess;

import com.evi.live.engine.AccountView;
import com.evi.live.engine.Engine;
import com.evi.live.engine.FillModelCache;
import com.evi.live.engine.Forecasts;
import com.evi.live.engine.SafetyChecks;
import com.evi.live.engine.ThinMarket;
import com.evi.live.journal.Offer;
import com.evi.live.journal.PluginJournal;
import com.evi.live.journal.StoreState;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.LatestPrices;
import com.evi.live.market.MarketAggregates;
import com.evi.live.market.MarketSnapshot;
import com.evi.live.market.PriceDataService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import net.runelite.client.util.Filepath;

/**
 * THE ONE COMPOSITION of {@link Engine#decide}'s inputs from the plugin's own layers: the journal ({@link PluginJournal}), the
 * price layer ({@link PriceDataService}) and its archive, and a preferences file, for the in-process engine
 * ({@link InProcessEngine}, which answers the sidebar).
 *
 * <p>The statics are pure. An INSTANCE holds the archive readings ONE consumer thread keeps, exactly as the bridge keeps them
 * (see {@link #refresh}); it is not thread-safe and belongs to that consumer's own thread.
 *
 * <p>No network of its own, and no file but the preferences file: the prices are the ones the price layer already fetched
 * (all-items endpoints only), the archive is read on the price layer's own thread ({@link PriceDataService#readHours}) and the
 * journal on the journal's ({@link PluginJournal#engineView}). The sell-support reading uses the archive, never a per-item
 * Wiki request.
 */
public final class EngineFeed {
  /** archive.recentHourly keeps this many hours (priceArchive.mjs RECENT_HOURS); the sell-support window is cut from them. */
  public static final int RECENT_HOURS = 26;
  /** thinMarketIndex: two weeks of the archive, rebuilt at most every six hours (server.mjs). */
  public static final long THIN_SPAN_S = 14L * 86400;
  public static final long THIN_EVERY_MS = 6L * 3600_000;

  // ------------------------------------------------------------------------------------------- the inputs

  /** The plugin's journal: what {@link Engine#decide} reads from it at one moment. A null future: no journal running. */
  public interface JournalSource {
    CompletableFuture<PluginJournal.EngineView> view(long nowMs);
  }

  /** The plugin's price layer: its snapshot (null when none runs), and its archive's hours from a time on (oldest first). */
  public interface MarketSource {
    Market market(long nowMs);

    CompletableFuture<List<HourBucket>> hoursFrom(long fromTs);
  }

  /** What the price layer knew at one moment, as the engine reads it (null fields: not available yet). */
  public static final class Market {
    public final LatestPrices latest;
    public final long latestFetchedAtMs;
    public final HourBucket latestHour;
    public final ItemCatalog catalog;
    /** Null before the first build: the engine then sizes on the live hour, as the bridge does before its cache is warm. */
    public final Map<Integer, Long> typical;
    public final Map<Integer, MarketAggregates.RobustPrice> robust;
    public final long newestStoredTs;
    public final int hoursStored, windowHours, robustHours, typicalHours;
    /** {@link MarketSnapshot#archiveRevision}: moves whenever the price layer learns of another hour, older ones included. */
    public final long archiveRevision;
    /** {@link MarketSnapshot#hoursUnavailable}: hours of the window the Wiki would not give this session. */
    public final int hoursUnavailable;
    /** {@link MarketSnapshot#catchingUp}: hours of the window are still to be FETCHED this session. */
    public final boolean catchingUp;
    /** {@link MarketSnapshot#readingsHoursStored}: the window's stored hours when the readings the engine ranks on were built. */
    public final int readingsHoursStored;

    /** No hour given up, catching up while any hour is missing, and readings over every stored hour. */
    public Market(LatestPrices latest, long latestFetchedAtMs, HourBucket latestHour, ItemCatalog catalog, Map<Integer, Long> typical,
                  Map<Integer, MarketAggregates.RobustPrice> robust, long newestStoredTs, int hoursStored, int windowHours, int robustHours, int typicalHours,
                  long archiveRevision) {
      this(latest, latestFetchedAtMs, latestHour, catalog, typical, robust, newestStoredTs, hoursStored, windowHours, robustHours, typicalHours,
        archiveRevision, 0, hoursStored < windowHours, hoursStored);
    }

    public Market(LatestPrices latest, long latestFetchedAtMs, HourBucket latestHour, ItemCatalog catalog, Map<Integer, Long> typical,
                  Map<Integer, MarketAggregates.RobustPrice> robust, long newestStoredTs, int hoursStored, int windowHours, int robustHours, int typicalHours,
                  long archiveRevision, int hoursUnavailable, boolean catchingUp, int readingsHoursStored) {
      this.latest = latest;
      this.latestFetchedAtMs = latestFetchedAtMs;
      this.latestHour = latestHour;
      this.catalog = catalog;
      this.typical = typical;
      this.robust = robust == null ? Collections.emptyMap() : robust;
      this.newestStoredTs = newestStoredTs;
      this.hoursStored = hoursStored;
      this.windowHours = windowHours;
      this.robustHours = robustHours;
      this.typicalHours = typicalHours;
      this.archiveRevision = archiveRevision;
      this.hoursUnavailable = hoursUnavailable;
      this.catchingUp = catchingUp;
      this.readingsHoursStored = readingsHoursStored;
    }

    /** From the price layer's published snapshot. */
    public static Market of(MarketSnapshot s, long nowMs) {
      boolean built = s.readingsBuiltAtMs != 0;
      return new Market(s.latest, s.latestFetchedAtMs, s.latestHour(nowMs), s.catalog, built ? s.typical : null, s.robust, s.newestStoredTs,
        s.hoursStored, s.windowHours, s.robustHours, s.typicalHours, s.archiveRevision, s.hoursUnavailable, s.catchingUp, s.readingsHoursStored);
    }
  }

  /** The plugin's journal, wherever the plugin currently keeps it (it is replaced on every startUp). */
  public static JournalSource journalOf(Supplier<PluginJournal> journal) {
    return now -> {
      PluginJournal j = journal.get();
      return j == null ? null : j.engineView(now);
    };
  }

  /** The plugin's price layer, wherever the plugin currently keeps it. */
  public static MarketSource marketOf(Supplier<PriceDataService> prices) {
    return new MarketSource() {
      @Override public Market market(long nowMs) {
        PriceDataService p = prices.get();
        return p == null ? null : Market.of(p.snapshot(), nowMs);
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        PriceDataService p = prices.get();
        if (p != null) return p.readHours(fromTs);
        CompletableFuture<List<HourBucket>> f = new CompletableFuture<>();
        f.completeExceptionally(new IllegalStateException("the price layer is not running"));
        return f;
      }
    };
  }

  // ------------------------------------------------------------------------------------------- pure

  /** The engine's market inputs from the price layer's: the crash watch is absent (no five-minute stream in the plugin yet). */
  public static Engine.MarketData marketData(Market m, List<HourBucket> recentHours, ThinMarket.Index thin, Engine.FillModelSource fill) {
    Engine.MarketData d = new Engine.MarketData();
    d.latest = m.latest;
    d.latestHour = m.latestHour;
    d.catalog = m.catalog;
    d.typicalVolumes = m.typical;
    d.rankPrices = m.robust;
    d.recentHours = recentHours;
    d.thin = thin;
    d.crashWatch = null;
    d.fillModel = fill;
    return d;
  }

  /**
   * One request decided from the plugin's own inputs: the query's account's view (which MUST be the account the query names --
   * {@link AccountView#of} on {@code query.get("account")}), the journal's shared parts with the profit line's reset time, and no
   * hooks but an optional per-item series (production passes none: the archive serves the sell-support reading; the parity
   * tests pass the bridge's recorded series). No forecast, cushion or correlation (all off by default in the bridge).
   */
  public static Engine.Response decide(Engine.Query query, AccountView view, StoreState state, Collection<Offer> journalOffers, Engine.MarketData market,
                                       Engine.Prefs prefs, Engine.SellSeries series, Long profitSince, long now) {
    return decide(query, view, state, journalOffers, market, prefs, series, null, profitSince, now);
  }

  /**
   * As {@link #decide(Engine.Query, AccountView, StoreState, Collection, Engine.MarketData, Engine.Prefs, Engine.SellSeries, Long, long)}, with
   * the opt-in forecast hook ({@link #forecastHook}: the ~6 hours setting read from the plugin's archive; null: none). No
   * cushion or correlation.
   */
  public static Engine.Response decide(Engine.Query query, AccountView view, StoreState state, Collection<Offer> journalOffers, Engine.MarketData market,
                                       Engine.Prefs prefs, Engine.SellSeries series, Engine.ForecastHook forecast, Long profitSince, long now) {
    Engine.Hooks hooks = new Engine.Hooks();
    hooks.sellSeries = series;
    hooks.forecast = forecast;
    return Engine.decide(query, view, Engine.SharedJournal.of(state, profitSince), journalOffers, market, prefs, hooks, now);
  }

  /**
   * A preferences file (the shape of the bridge's data/preferences.json), else the bridge's defaults. {@code source[0]} says
   * which, naming the file as {@code label}: the file, "defaults (no label)", or "defaults (label unreadable: Why)".
   */
  public static Engine.Prefs readPreferences(Filepath file, String label, Function<String, JsonElement> parser, String[] source) {
    if (!file.isFile()) {
      source[0] = "defaults (no " + label + ")";
      return Engine.Prefs.DEFAULT;
    }
    try (InputStream in = file.openInputStream()) {
      Engine.Prefs p = Engine.Prefs.fromJson(parser.apply(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      source[0] = label;
      return p;
    } catch (IOException | RuntimeException e) {
      source[0] = "defaults (" + label + " unreadable: " + e.getClass().getSimpleName() + ")";
      return Engine.Prefs.DEFAULT;
    }
  }

  /**
   * The preferences with the plugin's own Block-button list added (the plugin keeps those blocks itself, per character; the old
   * companion app kept them in this very file's {@code blocked}). The engine reads the list only as a set of items never to BUY, so the union is
   * kept sorted; nothing else in the preferences changes. {@code extra} null or empty: {@code prefs} itself.
   */
  public static Engine.Prefs withBlocked(Engine.Prefs prefs, java.util.Collection<Integer> extra) {
    if (extra == null || extra.isEmpty()) return prefs;
    java.util.TreeSet<Integer> all = new java.util.TreeSet<>(prefs.blocked);
    for (Integer id : extra) if (id != null && id > 0) all.add(id);
    return new Engine.Prefs(prefs.focus, new java.util.ArrayList<>(all), prefs.spread, prefs.captureCap);
  }

  /** Waits for a layer's answer on the CALLER's thread (a consumer's own), bounded. A null future: the layer is not running. */
  public static <T> T await(CompletableFuture<T> f, long ms) throws InterruptedException, ExecutionException, TimeoutException {
    if (f == null) throw new IllegalStateException("not running");
    return f.get(ms, TimeUnit.MILLISECONDS);
  }

  /** A failure's short name and message, for a log line. */
  public static String why(Throwable e) {
    Throwable c = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
    return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
  }

  // ------------------------------------------------------------------------------------------- one consumer's readings

  private final MarketSource market;
  private final long archiveWaitMs;
  private List<HourBucket> recent;
  private long recentFor;
  private ThinMarket.Index thin;
  private long thinAt;
  /** The thin index was built while hours of the window were still to be fetched ({@link Market#catchingUp}). */
  private boolean thinBuiltCatchingUp;
  /** The six-hour forecast's series per item, for one archive state and one newest hour (built on the first forecast asked). */
  private Forecasts.Index forecastIndex;
  private long forecastRevision = Long.MIN_VALUE, forecastEnd = Long.MIN_VALUE;
  /** The Wiki's per-item hourly series holds 365 points: the forecast reads the archive over the same span (it keeps 337). */
  public static final long FORECAST_SPAN_S = 364L * 3600;
  /** The hourly fill model over the archive (the bridge's playerFillModel cache). */
  public final FillModelCache fill;

  /** @param archiveWaitMs how long one archive read may take before it counts as failed */
  public EngineFeed(MarketSource market, long archiveWaitMs) {
    this.market = market;
    this.archiveWaitMs = archiveWaitMs;
    this.fill = new FillModelCache(from -> await(market.hoursFrom(from), archiveWaitMs));
  }

  /**
   * The archive readings the engine needs beside the snapshot, read on the price layer's thread and kept as the bridge keeps
   * them. THE LAST 26 HOURS again whenever the price layer has learned of another hour ({@link Market#archiveRevision}): the
   * bridge's archive.recentHourly takes every hour it stores, backfilled ones included, and the plugin backfills NEWEST
   * FIRST, so the older hours of a gap arrive after the newest -- keyed on the newest stored hour, the window would miss them
   * until the next hour closed. THE THIN INDEX as server.mjs thinMarketIndex keeps it: a built index for six hours, but a
   * NULL one (under {@link ThinMarket#MIN_HOURS} archived hours, or a failed read) is built again on the next poll. A fresh
   * archive passes 72 hours minutes after the first poll; a null cached for six hours would differ from the bridge on every
   * poll meanwhile (the decide-thin-index-retried transcript pins the bridge's rule). An index built while the archive was
   * still CATCHING UP is built once more when the catch-up ends: the plugin backfills newest first, so the older hours of a
   * gap are missing from it, and kept for six hours it read "N of the last 330 hours" all morning after a night away
   * (10 Oct). The bridge never meets this, its archive being filled around the clock. A failed read is noted in {@code notes}.
   */
  public void refresh(Market m, long at, JsonObject notes) {
    long nowS = Math.floorDiv(at, 1000L);
    if (recent == null || recentFor != m.archiveRevision) {
      try {
        recent = await(market.hoursFrom(nowS - RECENT_HOURS * 3600L), archiveWaitMs);
        recentFor = m.archiveRevision;
      } catch (Exception e) {
        recent = null;
        notes.addProperty("recentHoursFailed", why(e));
      }
    }
    if (thin == null || at - thinAt >= THIN_EVERY_MS || (thinBuiltCatchingUp && !m.catchingUp)) {
      thinAt = at;
      thinBuiltCatchingUp = m.catchingUp;
      try {
        thin = SafetyChecks.thinIndex(await(market.hoursFrom(nowS - THIN_SPAN_S), archiveWaitMs));
      } catch (Exception e) {
        thin = null;
        notes.addProperty("thinIndexFailed", why(e));
      }
    }
  }

  /** The last 26 hours as last read (null: none read, or the read failed). */
  public List<HourBucket> recent() {
    return recent;
  }

  /** The thin index as last built (null: too little archive, or the read failed). */
  public ThinMarket.Index thin() {
    return thin;
  }

  /**
   * THE OPT-IN FORECAST from the plugin's own hourly archive (8 Oct 2026, approved): the ~6 hours setting only -- the one the
   * fill-outlook table was measured for, and the one whose series is hourly. Its series is the item's archived hours up to and
   * including {@code newestTs} (the newest stored hour). The ~1 hour and Overnight settings were removed in 4.0.0: nothing asks for them.
   *
   * <p>The archive is read on the price layer's thread the first time a forecast is asked for, and the per-item series kept
   * until the archive learns of another hour or {@code newestTs} moves; a failed read is no forecast (as the bridge's failed
   * fetch is), noted in {@code notes}, and not retried for that archive state. Nothing is read while the setting is off.
   */
  public Engine.ForecastHook forecastHook(Market m, long newestTs, JsonObject notes) {
    return (itemId, horizon) -> {
      if (!Forecasts.SIX_HOURS.equals(horizon)) return null;
      Forecasts.Index idx = forecastIndex(m, newestTs, notes);
      return idx == null ? null : Forecasts.forHook(Forecasts.forecastFromSeries(idx.series(itemId), horizon), horizon);
    };
  }

  private Forecasts.Index forecastIndex(Market m, long newestTs, JsonObject notes) {
    if (newestTs == Long.MIN_VALUE) return null;
    if (forecastRevision == m.archiveRevision && forecastEnd == newestTs) return forecastIndex;
    forecastRevision = m.archiveRevision;
    forecastEnd = newestTs;
    try {
      forecastIndex = Forecasts.Index.build(await(market.hoursFrom(newestTs - FORECAST_SPAN_S), archiveWaitMs), newestTs);
    } catch (Exception e) {
      forecastIndex = null;
      if (notes != null) notes.addProperty("forecastFailed", why(e));
    }
    return forecastIndex;
  }

  /** The engine's market inputs: {@code m} with this consumer's archive readings and fill model. */
  public Engine.MarketData marketData(Market m) {
    return marketData(m, recent, thin, fill);
  }
}
