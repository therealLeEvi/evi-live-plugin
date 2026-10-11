package com.evi.live.engine;

import com.evi.live.TestFiles;
import static com.evi.live.engine.EngineJson.check;

import com.evi.live.inprocess.EngineFeed;
import com.evi.live.inprocess.InProcessEngine;
import com.evi.live.journal.PluginJournal;
import com.evi.live.journal.Store;
import com.evi.live.journal.StoreState;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.WikiJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.client.util.Filepath;

/**
 * The opt-in forecast, 8 Oct 2026 (approved): the ~6 hours setting computed from the plugin's own hourly archive; the ~1 hour
 * and Overnight settings RETIRED in the plugin.
 * <ol>
 *   <li>PARITY: {@link Forecasts#forecastFromSeries}, {@link Forecasts#fillOutlook} and {@link Forecasts#fillOutlookSentence}
 *       against every answer the real JS gave (tools/parity-forecast-vectors.mjs), EXACT -- each number the very double, each
 *       sentence the very text;</li>
 *   <li>THE ARCHIVE'S SERIES: {@link Forecasts.Index} keeps only the newest 36 usable points of each item, and gives the very
 *       answer the item's whole hourly series gives (what the Wiki's per-item series holds for the same hours), up to the hour
 *       it is cut at;</li>
 *   <li>THE HOOK ({@link EngineFeed#forecastHook}): 6h only; nothing read until a forecast is asked for, then once per archive
 *       state; a failed read is no forecast, said in the notes;</li>
 *   <li>WIRED: Engine.decide attaches the reading, the outlook and its sentence for 6h and nothing for 1h / overnight -- through
 *       the REAL in-process engine thread over a real plugin journal.</li>
 * </ol>
 */
public final class ForecastVectorsTest {
  private static int checks;

  private static void ok(boolean cond, String message) {
    checks++;
    check(cond, message);
  }

  public static void main(String[] args) throws Exception {
    int[] v = vectors();
    int items = indexEquivalence();
    hook();
    decideWiring();
    inProcess();
    System.out.println("PASS: forecast (" + checks + " checks) -- " + v[0] + " JS forecasts and " + v[1] + " outlooks matched EXACTLY (numbers, outlooks,"
      + " sentences); the archive's 36-point series gives the whole series' answer for " + items + " items; the hook reads the archive once per"
      + " state and only when asked, 6h only; decide and the real in-process engine attach it for 6h and nothing for 1h / overnight"
      + " (which the plugin no longer offers)");
  }

  // ------------------------------------------------------------------------------------------- 1. the JS vectors

  /** A Java-side value with every number as its double's EXACT value (ResponseJson.exact), as EngineJson.diff compares. */
  static JsonElement x(JsonElement e) {
    return e == null ? JsonNull.INSTANCE : ResponseJson.exact(e);
  }

  static Double nul(JsonElement e) {
    return e == null || e.isJsonNull() ? null : e.getAsDouble();
  }

  static int[] vectors() throws IOException {
    JsonObject doc = EngineJson.read("/parity/forecast-vectors.json.gz").getAsJsonObject();
    ok("evi-forecast-vectors/1".equals(doc.get("format").getAsString()), "the vectors' format");
    long t0 = doc.get("t0").getAsLong();
    JsonObject measured = doc.getAsJsonObject("measured");
    ok(measured.get("forecastHorizon").getAsString().equals(Forecasts.MEASURED_HORIZON) && measured.get("horizonHours").getAsInt() == Forecasts.HORIZON_HOURS
      && measured.getAsJsonObject("baseline").get("buy").getAsDouble() == Forecasts.BASELINE.buy
      && measured.getAsJsonObject("baseline").get("sell").getAsDouble() == Forecasts.BASELINE.sell, "the measured table's horizon and baseline are the JS's");
    ok(measured.getAsJsonObject("byLabel").size() == Forecasts.FILL_OUTLOOK_MEASURED.size(), "the same labels as the JS table");
    for (String label : measured.getAsJsonObject("byLabel").keySet()) {
      JsonObject e = measured.getAsJsonObject("byLabel").getAsJsonObject(label);
      Forecasts.Rate r = Forecasts.FILL_OUTLOOK_MEASURED.get(label);
      ok(r != null && r.buy == e.get("buy").getAsDouble() && r.sell == e.get("sell").getAsDouble() && r.samples == e.get("samples").getAsInt(),
        "the measured row for " + label + " is the JS's");
    }
    int n = 0;
    for (JsonElement ce : doc.getAsJsonArray("calls")) {
      JsonObject c = ce.getAsJsonObject();
      List<SellSupport.Point> series = new ArrayList<>();
      for (JsonElement pe : c.getAsJsonArray("series")) {
        JsonArray p = pe.getAsJsonArray();
        series.add(new SellSupport.Point(t0 + p.get(0).getAsDouble() * 3600, nul(p.get(1)), nul(p.get(2)), nul(p.get(3)), nul(p.get(4))));
      }
      String horizon = c.get("horizon").getAsString();
      Forecasts.Reading r = Forecasts.forecastFromSeries(series, horizon);
      List<String> d = EngineJson.diff(c.get("forecast"), x(r.json()));
      ok(d.isEmpty() && c.getAsJsonObject("forecast").keySet().equals(r.json().keySet()), "forecast " + n + " (" + c.get("why").getAsString() + ", " + horizon
        + ") differs from the JS: " + d + " keys " + c.getAsJsonObject("forecast").keySet() + " vs " + r.json().keySet());
      Forecasts.Outlook o = Forecasts.fillOutlook(r, horizon);
      d = EngineJson.diff(c.get("outlook"), o == null ? JsonNull.INSTANCE : x(o.json()));
      ok(d.isEmpty(), "outlook " + n + " differs from the JS: " + d);
      String s = Forecasts.fillOutlookSentence(o);
      ok(c.get("sentence").isJsonNull() ? s == null : c.get("sentence").getAsString().equals(s), "sentence " + n + ": " + c.get("sentence") + " vs " + s);
      PickChain.Forecast hooked = Forecasts.forHook(r, horizon);
      ok(EngineJson.diff(c.get("forecast"), x((JsonElement) hooked.detail)).isEmpty() && hooked.confidence == r.confidence
        && hooked.dir == (r.dir == -1 ? -1 : r.dir == 1 ? 1 : 0) && java.util.Objects.equals(hooked.outlookSentence, s)
        && EngineJson.diff(c.get("outlook"), hooked.outlook == null ? JsonNull.INSTANCE : x((JsonElement) hooked.outlook)).isEmpty(),
        "the hook hands the pick chain the same reading, outlook and sentence: " + n);
      n++;
    }
    int m = 0;
    for (JsonElement oe : doc.getAsJsonArray("outlooks")) {
      JsonObject c = oe.getAsJsonObject();
      Forecasts.Reading r = new Forecasts.Reading(c.get("label").getAsString(), 0, 50, 0, 0, 0, 0, 0, 0, true);
      Forecasts.Outlook o = Forecasts.fillOutlook(r, c.get("horizon").getAsString());
      ok(EngineJson.diff(c.get("outlook"), o == null ? JsonNull.INSTANCE : x(o.json())).isEmpty(), "outlook for " + c.get("label") + " at " + c.get("horizon"));
      String s = Forecasts.fillOutlookSentence(o);
      ok(c.get("sentence").isJsonNull() ? s == null : c.get("sentence").getAsString().equals(s), "outlook sentence for " + c.get("label"));
      m++;
    }
    ok(n >= 150 && m == 18, "too few vectors: " + n + " forecasts, " + m + " outlooks");
    return new int[]{n, m};
  }

  // ------------------------------------------------------------------------------------------- 2. the archive's series

  static final long H = 3600;

  /** A synthetic archive: {@code hours} hourly buckets ending at {@code newest}, a few items with gaps, one-sided hours and trends. */
  static List<HourBucket> archive(long newest, int hours, long seed) throws IOException {
    Random r = new Random(seed);
    List<HourBucket> out = new ArrayList<>();
    double[] price = {1000, 18, 250000, 5, 70};
    for (int k = hours - 1; k >= 0; k--) {
      StringBuilder sb = new StringBuilder("{\"ts\":").append(newest - k * H).append(",\"d\":{");
      boolean first = true;
      for (int i = 0; i < price.length; i++) {
        price[i] = Math.max(1, price[i] * (1 + (i - 2) * 0.001 + (r.nextDouble() - 0.5) * 0.03));
        double gap = r.nextDouble();
        if (i == 4 && gap < 0.85) continue; // a thin item: absent most hours
        if (gap < 0.05) continue;           // nobody traded it this hour
        String hi = gap < 0.10 ? "null" : String.valueOf(Math.round(price[i] * 1.01)), lo = gap > 0.95 ? "null" : String.valueOf(Math.round(price[i] * 0.99));
        if (!first) sb.append(',');
        first = false;
        sb.append('"').append(554 + i).append("\":[").append(hi).append(',').append(r.nextInt(5000)).append(',').append(lo).append(',').append(r.nextInt(5000)).append(']');
      }
      out.add(HourBucket.parseLine(sb.append("}}").toString()));
    }
    return out;
  }

  /** Every hour's point for the item, as the Wiki's per-item series carries the same hours (an absent hour has no point). */
  static List<SellSupport.Point> wholeSeries(List<HourBucket> hours, int itemId, long upTo) {
    List<SellSupport.Point> out = new ArrayList<>();
    for (HourBucket b : hours) {
      int i = b.indexOf(itemId);
      if (i < 0 || b.ts > upTo) continue;
      out.add(new SellSupport.Point(b.ts, b.avgHigh(i) == HourBucket.NONE ? null : (double) b.avgHigh(i), (double) b.highVolume(i),
        b.avgLow(i) == HourBucket.NONE ? null : (double) b.avgLow(i), (double) b.lowVolume(i)));
    }
    return out;
  }

  static int indexEquivalence() throws IOException {
    long newest = 1791439200L;
    int items = 0;
    for (long seed = 1; seed <= 6; seed++) {
      List<HourBucket> hours = archive(newest, seed % 2 == 0 ? 337 : 30, seed);
      Collections.shuffle(hours, new Random(seed)); // the index orders the hours itself
      for (long cut : new long[]{newest, newest - H, newest - 5 * H}) {
        Forecasts.Index idx = Forecasts.Index.build(hours, cut);
        for (int item = 554; item <= 559; item++) {
          List<SellSupport.Point> kept = idx.series(item);
          ok(kept.size() <= Forecasts.SIX_HOUR_POINTS, "at most the newest 36 usable points are kept: " + kept.size());
          for (SellSupport.Point p : kept) ok(p.timestamp <= cut, "no hour after the cut");
          JsonObject a = Forecasts.forecastFromSeries(kept, "6h").json(), b = Forecasts.forecastFromSeries(wholeSeries(hours, item, cut), "6h").json();
          ok(EngineJson.diff(x(b), x(a)).isEmpty() && a.keySet().equals(b.keySet()), "seed " + seed + " cut " + cut + " item " + item + ": the kept points give the whole series' answer: "
            + EngineJson.diff(x(b), x(a)));
          items++;
        }
      }
    }
    ok(Forecasts.Index.build(null, newest).series(554).isEmpty() && "Uncertain".equals(Forecasts.forecastFromSeries(Collections.emptyList(), "6h").label),
      "no archive: an empty series, read as the JS reads an item with no usable hour (Uncertain)");
    return items;
  }

  // ------------------------------------------------------------------------------------------- 3. the hook

  static EngineFeed.Market market(long newest, long revision) {
    return new EngineFeed.Market(null, 0, null, null, null, null, newest, 0, 0, 0, 0, revision);
  }

  static void hook() throws Exception {
    long newest = 1791439200L;
    List<HourBucket> hours = archive(newest, 60, 9);
    AtomicInteger reads = new AtomicInteger();
    AtomicLong from = new AtomicLong();
    boolean[] fail = {false};
    EngineFeed.MarketSource src = new EngineFeed.MarketSource() {
      @Override public EngineFeed.Market market(long nowMs) {
        return null;
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        reads.incrementAndGet();
        from.set(fromTs);
        CompletableFuture<List<HourBucket>> f = new CompletableFuture<>();
        if (fail[0]) f.completeExceptionally(new IOException("planted"));
        else f.complete(new ArrayList<>(hours));
        return f;
      }
    };
    EngineFeed feed = new EngineFeed(src, 10_000);
    JsonObject notes = new JsonObject();
    Engine.ForecastHook h = feed.forecastHook(market(newest, 1), newest, notes);
    ok(reads.get() == 0, "making the hook reads nothing: the archive is read only when a forecast is asked for");
    ok(h.forecastFor(554, "1h") == null && h.forecastFor(554, "overnight") == null && h.forecastFor(554, null) == null && reads.get() == 0,
      "~1 hour and Overnight are retired: no forecast, and nothing read");
    PickChain.Forecast f = h.forecastFor(554, "6h");
    ok(f != null && reads.get() == 1 && from.get() == newest - EngineFeed.FORECAST_SPAN_S, "6h reads the archive once, over the Wiki series' 365-hour span: "
      + reads.get() + " reads from " + from.get());
    ok(EngineJson.diff(x(Forecasts.forecastFromSeries(wholeSeries(hours, 554, newest), "6h").json()), x((JsonElement) f.detail)).isEmpty(), "the item's archived series");
    ok(h.forecastFor(555, "6h") != null && feed.forecastHook(market(newest, 1), newest, notes).forecastFor(556, "6h") != null && reads.get() == 1,
      "other items, and the next poll on the same archive: no new read");
    feed.forecastHook(market(newest, 2), newest, notes).forecastFor(554, "6h");
    ok(reads.get() == 2, "the archive learned of another hour: read again");
    PickChain.Forecast cut = feed.forecastHook(market(newest, 2), newest - 2 * H, notes).forecastFor(554, "6h");
    ok(reads.get() == 3 && EngineJson.diff(x(Forecasts.forecastFromSeries(wholeSeries(hours, 554, newest - 2 * H), "6h").json()), x((JsonElement) cut.detail)).isEmpty(),
      "a different newest hour (the shadow's: the bridge's hour): read again, and cut there");
    ok(feed.forecastHook(market(Long.MIN_VALUE, 3), Long.MIN_VALUE, notes).forecastFor(554, "6h") == null && reads.get() == 3, "no stored hour: no forecast, nothing read");
    fail[0] = true;
    ok(feed.forecastHook(market(newest, 4), newest, notes).forecastFor(554, "6h") == null && notes.has("forecastFailed"),
      "a failed read: no forecast (as the bridge's failed fetch), noted: " + notes);
    ok(feed.forecastHook(market(newest, 4), newest, notes).forecastFor(555, "6h") == null && reads.get() == 4, "...and not tried again for that archive state");
  }

  // ------------------------------------------------------------------------------------------- 4. wired

  private static final long NOW = 1791446400000L + 2 * 60000L; // 09:02 UTC: hour H = 09:00, newest stored H-1 = 08:00
  private static final long HOUR_TS = 1791446400L - 3600;
  private static final String LATEST = "{\"data\":{\"561\":{\"high\":214,\"highTime\":" + (NOW / 1000 - 60) + ",\"low\":200,\"lowTime\":" + (NOW / 1000 - 75) + "}}}";
  private static final String HOUR = "{\"timestamp\":" + HOUR_TS + ",\"data\":{\"561\":{\"avgHighPrice\":214,\"highPriceVolume\":300000,\"avgLowPrice\":200,\"lowPriceVolume\":300000}}}";
  private static final String MAPPING = "[{\"examine\":\"x\",\"id\":561,\"members\":false,\"lowalch\":80,\"limit\":18000,\"value\":200,\"highalch\":120,\"icon\":\"n.png\",\"name\":\"Nature rune\"}]";

  /** Forty hours of Nature runes rising steadily up to {@code newest}: "Likely rising", whose outlook is the exit-risk sentence. */
  static List<HourBucket> rising(long newest) throws IOException {
    List<HourBucket> out = new ArrayList<>();
    for (int k = 39; k >= 0; k--) {
      long p = 230 + (39 - k);
      out.add(HourBucket.parseLine("{\"ts\":" + (newest - k * H) + ",\"d\":{\"561\":[" + (p + 2) + ",300000," + p + ",300000]}}"));
    }
    return out;
  }

  static JsonObject decide(String query, Engine.ForecastHook forecast) throws Exception {
    Engine.MarketData m = new Engine.MarketData();
    m.latest = WikiJson.latest(LATEST);
    m.latestHour = WikiJson.hour(HOUR);
    m.catalog = ItemCatalog.parse(MAPPING);
    m.typicalVolumes = Collections.emptyMap();
    m.rankPrices = Collections.emptyMap();
    m.recentHours = new ArrayList<>();
    Store store = new Store(r -> { });
    StoreState state = store.state(NOW);
    return ResponseJson.response(EngineFeed.decide(Engine.Query.parse(query), AccountView.of(state, store.offers(), null, NOW), state, store.offers(), m,
      Engine.Prefs.DEFAULT, null, forecast, null, NOW));
  }

  static void decideWiring() throws Exception {
    List<HourBucket> hours = rising(HOUR_TS);
    EngineFeed feed = new EngineFeed(new EngineFeed.MarketSource() {
      @Override public EngineFeed.Market market(long nowMs) {
        return null;
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        return CompletableFuture.completedFuture(hours);
      }
    }, 10_000);
    Engine.ForecastHook hook = feed.forecastHook(market(HOUR_TS, 1), HOUR_TS, new JsonObject());
    String base = "includeMarket=1&minProfit=1000&cash=1000000";
    JsonObject off = decide(base, hook), six = decide(base + "&forecast=6h", hook);
    JsonObject s = six.getAsJsonObject("suggestion");
    Forecasts.Reading expect = Forecasts.forecastFromSeries(wholeSeries(hours, 561, HOUR_TS), "6h");
    ok("Likely rising".equals(expect.label), "setup: the rising series reads as Likely rising: " + expect.label);
    ok(s != null && s.has("forecast") && EngineJson.diff(x(expect.json()), x(s.get("forecast"))).isEmpty() && s.has("fillOutlook")
      && s.getAsJsonObject("fillOutlook").get("worst").getAsString().equals("sell"), "6h: the suggestion carries the reading and its outlook: " + s);
    String sentence = Forecasts.fillOutlookSentence(Forecasts.fillOutlook(expect, "6h"));
    ok(sentence.startsWith("Exit risk:") && s.get("reasoning").getAsString().equals(off.getAsJsonObject("suggestion").get("reasoning").getAsString() + " " + sentence),
      "6h: the exit-risk sentence is appended to the reasoning, and nothing else changes: " + s.get("reasoning"));
    ok(!off.getAsJsonObject("suggestion").has("forecast") && !off.getAsJsonObject("suggestion").has("fillOutlook"), "no forecast asked for: none attached");
    for (String retired : new String[]{"1h", "overnight"}) {
      JsonObject r = decide(base + "&forecast=" + retired, hook);
      ok(r.equals(off), retired + " is retired in the plugin: the answer is the one with no forecast at all: " + r);
    }
    JsonObject skip = decide(base + "&forecast=6h&onForecast=skip", hook);
    ok(skip.getAsJsonObject("suggestion").get("itemId").getAsInt() == 561
      && skip.getAsJsonObject("suggestion").get("reasoning").getAsString().endsWith(PickChain.forecastPolicyNote("skip")),
      "skip is inactive, and says so (the bridge's note), the pick kept");
  }

  static JsonObject parse(String body) {
    return body == null ? null : new JsonParser().parse(body).getAsJsonObject();
  }

  static void inProcess() throws Exception {
    Filepath root = TestFiles.tempDir("evi-forecast-inprocess");
    PluginJournal journal = new PluginJournal(root, () -> NOW, s -> new JsonParser().parse(s), () -> false, m -> { });
    journal.start();
    List<String> logged = Collections.synchronizedList(new ArrayList<>());
    List<HourBucket> hours = rising(HOUR_TS);
    EngineFeed.Market m = new EngineFeed.Market(WikiJson.latest(LATEST), NOW - 30_000, WikiJson.hour(HOUR), ItemCatalog.parse(MAPPING), Collections.emptyMap(),
      Collections.emptyMap(), HOUR_TS, InProcessEngine.BASIS_HOURS, InProcessEngine.BASIS_HOURS + 1, 0, 0, 1, 0, true, InProcessEngine.BASIS_HOURS);
    InProcessEngine e = new InProcessEngine(root, EngineFeed.journalOf(() -> journal), new EngineFeed.MarketSource() {
      @Override public EngineFeed.Market market(long nowMs) {
        return m;
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        return CompletableFuture.completedFuture(hours);
      }
    }, s -> new JsonParser().parse(s), logged::add, () -> false, () -> null);
    try {
      String base = "includeMarket=1&minProfit=1000&cash=1000000";
      JsonObject six = parse(e.request(base + "&forecast=6h", NOW).get(30, TimeUnit.SECONDS).body);
      JsonObject one = parse(e.request(base + "&forecast=1h", NOW).get(30, TimeUnit.SECONDS).body);
      JsonObject over = parse(e.request(base + "&forecast=overnight", NOW).get(30, TimeUnit.SECONDS).body);
      ok(six != null && six.getAsJsonObject("suggestion").has("forecast")
        && EngineJson.diff(x(Forecasts.forecastFromSeries(wholeSeries(hours, 561, HOUR_TS), "6h").json()), x(six.getAsJsonObject("suggestion").get("forecast"))).isEmpty()
        && six.getAsJsonObject("suggestion").get("reasoning").getAsString().contains("Exit risk:"),
        "the in-process engine: 6h forecast from the archive up to its newest stored hour: " + six + " " + logged);
      ok(one != null && !one.getAsJsonObject("suggestion").has("forecast") && over != null && !over.getAsJsonObject("suggestion").has("forecast")
        && !one.getAsJsonObject("suggestion").get("reasoning").getAsString().contains("Exit risk"), "the in-process engine: 1h and overnight have no forecast");
    } finally {
      e.shutdown();
      journal.shutdown();
      journal.awaitIdle(30_000);
      root.deleteRecursively();
    }
  }
}
