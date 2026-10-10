package com.evi.live.engine;

import static com.evi.live.engine.EngineJson.check;

import com.evi.live.journal.Store;
import com.evi.live.journal.StoreState;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.WikiJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The intent of {@link Engine#decide}'s own rules that no golden transcript can carry, asserted as VALUES: the preferences
 * as readPreferences reads them (the spread clamped to 1-10), the request as URLSearchParams reads it (the FIRST value of a
 * repeated name, '+' as a space, a malformed escape kept), the 502s the bridge answers when a market input the request
 * needs is missing, the account view that must be the one the request names, and the body's shape (the bridge's key
 * order, {@code profit.since} sent as null). The behaviour itself is proven against the bridge by EngineTranscriptTest.
 */
public final class EngineDecideIntentTest {
  private static int checks;

  private static void ok(boolean cond, String message) {
    checks++;
    check(cond, message);
  }

  public static void main(String[] args) throws Exception {
    preferences();
    query();
    failures();
    shape();
    fillModelHour();
    sellSupportFromArchive();
    System.out.println("PASS: Engine.decide intent, " + checks + " checks -- the preferences as readPreferences reads them (spread clamped to 1-10, default 5),"
      + " the query as URLSearchParams reads it, the 502s for a missing /latest, mapping or hour, the account view tied to the request, the body's shape, and sell support read from the archive as the bridge reads its series (H-12..H-2)");
  }

  private static Engine.Prefs prefs(String json) {
    return Engine.Prefs.fromJson(new JsonParser().parse(json));
  }

  private static void preferences() {
    ok(prefs("{\"spread\":50}").spread == 10, "a spread past 10 is clamped to 10");
    ok(prefs("{\"spread\":0}").spread == 1 && prefs("{\"spread\":-4}").spread == 1, "a spread under 1 is clamped to 1");
    ok(prefs("{\"spread\":3}").spread == 3, "a spread in range is kept");
    ok(prefs("{\"spread\":2.5}").spread == 5 && prefs("{\"spread\":\"3\"}").spread == 5 && prefs("{}").spread == 5, "a spread that is not a safe whole number is the default 5");
    ok(new Engine.Prefs("any", null, 50, false).spread == 10 && new Engine.Prefs("any", null, Double.NaN, false).spread == 5,
      "the clamp holds whoever builds the preferences, not only the JSON reader");
    ok(Engine.Prefs.DEFAULT.spread == 5 && "any".equals(Engine.Prefs.DEFAULT.focus) && Engine.Prefs.DEFAULT.blocked.isEmpty() && !Engine.Prefs.DEFAULT.captureCap,
      "the defaults are readPreferences' own");
    ok("any".equals(prefs("{\"focus\":\"scrolls\"}").focus) && "gear".equals(prefs("{\"focus\":\"gear\"}").focus), "an unknown focus is any");
    ok(prefs("{\"blocked\":[561,-1,0,2.5,4294967857,\"4151\",565]}").blocked.equals(Arrays.asList(561, 565)),
      "blocked keeps positive safe whole ids in item range, in order: " + prefs("{\"blocked\":[561,-1,0,2.5,4294967857,\"4151\",565]}").blocked);
    ok(prefs("{\"captureCap\":true}").captureCap && !prefs("{\"captureCap\":\"true\"}").captureCap && !prefs("{\"captureCap\":1}").captureCap,
      "captureCap only when it is literally true");
    ok(prefs("null").spread == 5 && prefs("[1,2]").spread == 5 && prefs("7").spread == 5, "anything that is not an object reads as the defaults");
  }

  private static void query() {
    Engine.Query q = Engine.Query.parse("cash=50000000&minProfit=1&cash=1&holdName=Blood+rune&euro=%E2%82%AC&bad=%ZZ&half=%4&flag&=x&&a%20b=c%2Bd");
    ok("50000000".equals(q.get("cash")), "a repeated name answers its FIRST value: " + q.get("cash"));
    ok("Blood rune".equals(q.get("holdName")), "'+' is a space");
    ok("€".equals(q.get("euro")), "%XX sequences are UTF-8 decoded");
    ok("%ZZ".equals(q.get("bad")) && "%4".equals(q.get("half")), "a malformed escape is kept as written");
    ok(q.has("flag") && "".equals(q.get("flag")), "a bare name is present with an empty value");
    ok("x".equals(q.get("")), "an empty name is still a name");
    ok("c+d".equals(q.get("a b")), "names and values are both decoded");
    ok(!q.has("missing") && q.get("missing") == null, "an absent name is null, never empty");
    ok(Engine.Query.parse("?cash=5").get("cash").equals("5") && Engine.Query.parse(null).get("cash") == null, "a leading '?' and no query at all");
  }

  // ------------------------------------------------------------------------------------------- the 502s

  private static final long NOW = 1758456000000L; // 2025-09-21 12:00 UTC
  private static final String LATEST = "{\"data\":{\"561\":{\"high\":214,\"highTime\":" + (NOW / 1000 - 60) + ",\"low\":200,\"lowTime\":" + (NOW / 1000 - 75) + "}}}";
  private static final String HOUR = "{\"timestamp\":" + (NOW / 1000 / 3600 * 3600 - 3600) + ",\"data\":{\"561\":{\"avgHighPrice\":214,\"highPriceVolume\":300000,\"avgLowPrice\":200,\"lowPriceVolume\":300000}}}";
  private static final String MAPPING = "[{\"examine\":\"x\",\"id\":561,\"members\":false,\"lowalch\":80,\"limit\":18000,\"value\":200,\"highalch\":120,\"icon\":\"n.png\",\"name\":\"Nature rune\"}]";

  private static Engine.Response decide(String query, String account, Engine.MarketData market) {
    Store store = new Store(r -> { });
    StoreState state = store.state(NOW);
    AccountView view = AccountView.of(state, store.offers(), account, NOW);
    return Engine.decide(Engine.Query.parse(query), view, Engine.SharedJournal.of(state, null), store.offers(), market, Engine.Prefs.DEFAULT, new Engine.Hooks(), NOW);
  }

  private static Engine.MarketData market(boolean latest, boolean hour, boolean mapping) throws Exception {
    Engine.MarketData m = new Engine.MarketData();
    m.latest = latest ? WikiJson.latest(LATEST) : null;
    HourBucket h = hour ? WikiJson.hour(HOUR) : null;
    m.latestHour = h;
    m.catalog = mapping ? ItemCatalog.parse(MAPPING) : null;
    m.typicalVolumes = Collections.emptyMap();
    m.rankPrices = Collections.emptyMap();
    m.recentHours = new ArrayList<>();
    return m;
  }

  private static void failures() throws Exception {
    Engine.Response r = decide("cash=1000", null, market(false, true, true));
    ok(r.failed() && "no /latest prices".equals(r.error) && r.suggestion == null, "no /latest: the bridge's 502, nothing suggested: " + r.error);
    r = decide("account=acct-a&cash=1000", "acct-a", market(true, true, false));
    ok(r.failed() && "the item mapping is not available yet".equals(r.error), "a named account needs the mapping for its buy-limit gate: " + r.error);
    r = decide("members=0&cash=1000", null, market(true, true, false));
    ok(r.failed() && "the item mapping is not available yet".equals(r.error), "a free world needs the mapping for its members gate");
    r = decide("cash=1000", null, market(true, true, false));
    ok(!r.failed(), "no gate, no idle stock, no market: the mapping is not needed");
    r = decide("includeMarket=1&minProfit=1000&cash=1000000", null, market(true, false, true));
    ok(r.failed() && "the latest hour's volumes are not available".equals(r.error), "the market tier without the latest hour: the bridge's 502");
    r = decide("includeMarket=1&minProfit=1000&cash=1000000", null, market(true, true, true));
    ok(!r.failed() && r.suggestion != null && "market".equals(r.suggestion.source) && r.suggestion.itemId == 561, "with every input the market tier answers");
    boolean threw = false;
    try {
      Store store = new Store(x -> { });
      StoreState state = store.state(NOW);
      Engine.decide(Engine.Query.parse("account=acct-b&cash=1"), AccountView.of(state, store.offers(), "acct-a", NOW), Engine.SharedJournal.of(state, null),
        store.offers(), market(true, true, true), Engine.Prefs.DEFAULT, new Engine.Hooks(), NOW);
    } catch (IllegalArgumentException e) {
      threw = e.getMessage().contains("acct-a") && e.getMessage().contains("acct-b");
    }
    ok(threw, "an account view for another account than the request names is refused, never answered");
    threw = false;
    try {
      Store store = new Store(x -> { });
      StoreState state = store.state(NOW);
      Engine.decide(Engine.Query.parse("cash=1"), AccountView.of(state, store.offers(), "acct-a", NOW), Engine.SharedJournal.of(state, null),
        store.offers(), market(true, true, true), Engine.Prefs.DEFAULT, new Engine.Hooks(), NOW);
    } catch (IllegalArgumentException e) {
      threw = true;
    }
    ok(threw, "a poll that names no account is answered from NOBODY's view, never an account's");
  }

  /**
   * X61 (8 Oct verifier): the player fill model is kept for exactly ONE HOUR, as server.mjs playerFillModel keeps it
   * ({@code now - at < 3600000}): a model built at one poll answers every poll up to 59:59.999 later, and the next poll is a
   * rebuild. A failed or empty build is not kept (the bridge's {@code fillModelCache.model &&}): the next poll tries again.
   */
  private static void fillModelHour() {
    long t0 = 1790000000000L;
    int[] loads = {0};
    FillModelCache cache = new FillModelCache(from -> {
      loads[0]++;
      return new ArrayList<>();
    });
    List<com.evi.live.journal.Offer> offers = Collections.singletonList(new com.evi.live.journal.Offer(0, "BOUGHT", "o1", 561, "Nature rune", 200, 1000, 1000,
      200000, true, 10, "acct-a", "s1", t0 - 600000L, t0 - 300000L, t0 - 300000L, null));
    FillModel.Model first = cache.model(offers, t0);
    ok(first != null && loads[0] == 1, "setup: one watched offer builds a model, reading the archive once");
    ok(cache.model(offers, t0 + 60000L) == first && cache.model(offers, t0 + 1800000L) == first && cache.model(offers, t0 + 3599999L) == first && loads[0] == 1,
      "the fill model is kept for the WHOLE hour (a minute, half an hour, 59:59.999 later): " + loads[0] + " archive reads");
    FillModel.Model next = cache.model(offers, t0 + 3600000L);
    ok(next != null && next != first && loads[0] == 2, "an hour after it was built, the fill model is built again");
    ok(cache.model(offers, t0 + 3600000L + 3599999L) == next && loads[0] == 2, "...and that one is kept for its own hour");
    int[] failing = {0};
    FillModelCache broken = new FillModelCache(from -> {
      failing[0]++;
      throw new java.io.IOException("the archive could not be read");
    });
    ok(broken.model(offers, t0) == null && broken.model(offers, t0 + 1000L) == null && failing[0] == 2, "a failed build is not kept: the next poll tries again");
  }

  // ------------------------------------------------------------------------------------------- sell support from the archive

  /** One archived hour of Nature runes at {@code avgHigh} (300,000 units each side). */
  private static HourBucket natureHour(long ts, long avgHigh) throws Exception {
    return HourBucket.parseLine("{\"ts\":" + ts + ",\"d\":{\"561\":[" + avgHigh + ",300000,200,300000]}}");
  }

  /** The bridge's Wiki series for the same hours: its points ARE the archive's (measured identical, 19 Sept and 8 Oct). */
  private static List<SellSupport.Point> seriesOf(List<HourBucket> hours) {
    List<SellSupport.Point> out = new ArrayList<>();
    for (HourBucket b : hours) {
      int i = b.indexOf(561);
      out.add(new SellSupport.Point(b.ts, (double) b.avgHigh(i), (double) b.highVolume(i), (double) b.avgLow(i), (double) b.lowVolume(i)));
    }
    return out;
  }

  private static JsonObject body(String query, Engine.MarketData market, Engine.SellSeries series, long now) {
    Store store = new Store(r -> { });
    StoreState state = store.state(now);
    Engine.Hooks hooks = new Engine.Hooks();
    hooks.sellSeries = series;
    return ResponseJson.response(Engine.decide(Engine.Query.parse(query), AccountView.of(state, store.offers(), null, now),
      Engine.SharedJournal.of(state, null), store.offers(), market, Engine.Prefs.DEFAULT, hooks, now));
  }

  /**
   * 8 Oct 2026 (approved): with NO series hook (the plugin) the archive is the series, and the reading must be the bridge's.
   * The bridge's Wiki series ends at H-2 for the whole of hour H, so from hh:00 to about hh:05 -- before the archive has
   * stored H-1 -- the bridge reads the eleven hours H-12..H-2, where the old archive-only gate (twelve hours or nothing) had
   * NO reading. Each case compares the plugin's body with the body the SAME engine gives when handed the bridge's series
   * (the eleven settled hours, H-1 absent: what the Wiki serves then), so "the bridge's numbers" is not a re-derivation.
   */
  private static void sellSupportFromArchive() throws Exception {
    long h = NOW / 1000; // NOW is 12:00 UTC: hour H starts here
    List<HourBucket> settled = new ArrayList<>();
    for (int k = 12; k >= 2; k--) settled.add(natureHour(h - k * 3600L, 212));
    Engine.SellSeries bridgeSeries = itemId -> itemId == 561 ? seriesOf(settled) : null;
    String query = "includeMarket=1&minProfit=1000&cash=1000000";
    for (long at : new long[]{NOW + 2 * 60000L, NOW + 6 * 60000L, NOW + 20 * 60000L}) {
      Engine.MarketData m = market(true, true, true);
      m.recentHours = new ArrayList<>(settled);
      JsonObject plugin = body(query, m, null, at), bridge = body(query, m, bridgeSeries, at);
      JsonObject s = plugin.getAsJsonObject("suggestion");
      ok(s != null && s.has("sellSupport") && s.getAsJsonObject("sellSupport").get("units").getAsDouble() == 11 * 300000.0,
        "no series hook, archive H-12..H-2 at +" + (at - NOW) / 60000 + " min: a reading over the eleven settled hours (the old gate had none): " + s);
      ok(plugin.equals(bridge), "...and the whole body is the one the bridge's own series gives at +" + (at - NOW) / 60000 + " min:\n plugin " + plugin + "\n bridge " + bridge);
    }
    // H-1 stored (after hh:05): twelve hours, still the bridge's (series H-12..H-2, archive H-1 merged in).
    List<HourBucket> twelve = new ArrayList<>(settled);
    twelve.add(natureHour(h - 3600L, 210));
    Engine.MarketData m12 = market(true, true, true);
    m12.recentHours = twelve;
    JsonObject p12 = body(query, m12, null, NOW + 10 * 60000L);
    ok(p12.getAsJsonObject("suggestion").getAsJsonObject("sellSupport").get("units").getAsDouble() == 12 * 300000.0
      && p12.equals(body(query, m12, bridgeSeries, NOW + 10 * 60000L)), "with H-1 stored the reading covers twelve hours, as the bridge's merge does: " + p12);
    // A gap the series would have filled: no reading (never one over fewer hours than the bridge reads).
    List<HourBucket> gap = new ArrayList<>(settled);
    gap.remove(5);
    Engine.MarketData mg = market(true, true, true);
    mg.recentHours = gap;
    JsonObject pg = body(query, mg, null, NOW + 2 * 60000L);
    ok(pg.get("suggestion").isJsonObject() && !pg.getAsJsonObject("suggestion").has("sellSupport"), "an archived hour missing inside H-12..H-2: no reading: " + pg);
    ok(!SellSupport.archiveCoversSettledWindow(gap, NOW + 2 * 60000L) && SellSupport.archiveCoversSettledWindow(settled, NOW + 2 * 60000L)
      && !SellSupport.archiveCoversSettledWindow(settled.subList(1, settled.size()), NOW + 2 * 60000L)
      && !SellSupport.archiveCoversSettledWindow(settled, NOW + 3600000L + 2 * 60000L) && !SellSupport.archiveCoversSettledWindow(null, NOW),
      "the window is exactly H-12..H-2 of the hour now is in (H-12 missing, or an hour later with H-1 absent: not covered)");
    // A series hook that answers nothing (the bridge's failed fetch) keeps the bridge's own gate: eleven hours are not enough.
    Engine.MarketData mf = market(true, true, true);
    mf.recentHours = new ArrayList<>(settled);
    JsonObject failedFetch = body(query, mf, itemId -> null, NOW + 2 * 60000L);
    ok(!failedFetch.getAsJsonObject("suggestion").has("sellSupport"), "a hook whose fetch failed: the bridge's gate, no reading from eleven hours: " + failedFetch);
    // worthOf (the reachable probe's figure): the plugin's matches the bridge's in the same window.
    String high = "includeMarket=1&minProfit=900000000&cash=1000000";
    Engine.MarketData mr = market(true, true, true);
    mr.recentHours = new ArrayList<>(settled);
    JsonObject pr = body(high, mr, null, NOW + 2 * 60000L), br = body(high, mr, bridgeSeries, NOW + 2 * 60000L);
    JsonObject quotedOnly = body(high, mr, itemId -> null, NOW + 2 * 60000L);
    ok(pr.get("reachable").isJsonObject() && pr.equals(br) && !pr.get("reachable").equals(quotedOnly.get("reachable")),
      "the reachable figure is judged at the price buyers paid over the eleven settled hours, as the bridge's is (not the quoted spread):\n plugin "
        + pr.get("reachable") + "\n bridge " + br.get("reachable") + "\n quoted " + quotedOnly.get("reachable"));
  }

  private static void shape() throws Exception {
    Engine.Response r = decide("cash=1000", null, market(true, true, true));
    JsonObject j = ResponseJson.response(r);
    List<String> keys = new ArrayList<>();
    for (Map.Entry<String, com.google.gson.JsonElement> e : j.entrySet()) keys.add(e.getKey());
    ok(keys.equals(Arrays.asList("suggestion", "additional", "openItemPrice", "slotPrices", "slotFill", "relistAdvice", "slots", "profit", "reachable",
      "fellThroughFromHistory", "heldBack")), "the bridge's key order: " + keys);
    ok(j.get("suggestion").isJsonNull() && j.get("reachable").isJsonNull() && j.get("fellThroughFromHistory").isJsonNull(), "absent answers are JSON null");
    ok(j.getAsJsonObject("profit").has("since") && j.getAsJsonObject("profit").get("since").isJsonNull(), "profit.since is sent as null when never reset");
    ok(ResponseJson.response(decide("cash=1000", null, market(false, true, true))).toString().equals("{\"error\":\"no /latest prices\"}"), "a failed request is {error}");
  }
}
