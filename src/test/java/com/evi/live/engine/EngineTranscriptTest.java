package com.evi.live.engine;

import static com.evi.live.engine.EngineJson.check;
import static com.evi.live.engine.EngineJson.isNullish;

import com.evi.live.journal.Store;
import com.evi.live.journal.StoreState;
import com.evi.live.journal.TranscriptDriver;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.MarketAggregates;
import com.evi.live.market.WikiJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * EVERY GOLDEN TRANSCRIPT of the real bridge (tools/golden-record.mjs: the tier, advice, journal and market scenarios),
 * replayed through the PRODUCTION {@link Engine#decide}: at each GET /api/suggestion the whole answer -- the suggestion,
 * the further positions, heldBack, slots, the open offer's price, the running offers' prices and fill estimates, the advice
 * channel, the profit line, the reachable probe and the "fell through from history" note -- is written by the production
 * serialiser ({@link ResponseJson}) and compared field for field, character for character, with the body the bridge sent.
 * Only the suggestion log's handle ({@code id}, and {@code accepted} read back from that log) is left out: a wall-clock id.
 *
 * <p>THE INPUTS ARE WHAT THE BRIDGE'S CACHES HELD, nothing more: the journal is the Java {@link Store} replayed from the
 * same packets ({@link TranscriptDriver}, which also proves the journal's own answers), split into the asking account's
 * {@link AccountView}, the deliberately shared parts ({@link Engine.SharedJournal}) and every account's offers for the fill
 * model; the Wiki bodies are the last ones recorded at or before the poll for each URL (the transcripts record every
 * fetch); the 168-hour typical volumes, the 336-hour steady prices and the thin-market index are read from the
 * transcript's archive at the poll's clock -- the boot archive plus every hour a {@code store1h} step stored before the poll; the crash watch is the Java watch fed the same five-minute buckets at
 * the same moments ({@code feed5m} steps); and the sell-support series is the injected function, parsed as server.mjs
 * parses it ({@link #series}).
 *
 * <p>This REPLACES the test-only composer that stood here until 7 Oct: the orchestration it restated is now
 * {@link Engine#decide}, so a zero here is the plugin's own engine matching the bridge, not a test's copy of it.
 */
public final class EngineTranscriptTest {
  private static final List<String> diffs = new ArrayList<>();
  private static int compared, polls, withSuggestion, withHeldBack, withAdditional, withReachable, withFellThrough, steppedDown, withAdvice;
  private static int crashDemoted, thinDemoted, fillSentences, fillHistory;
  private static final Map<String, Integer> sources = new TreeMap<>();
  /**
   * The transcripts that pin one step of the ORDER each (the 7 Oct verifier gaps and the maintainer's 7 Oct fixes): if one
   * stops being replayed, the reordering it guards passes again. See the names: the Block button after the sell-side tiers,
   * a listed sell silencing the live reminder, the SAME sizing on extra positions as on the main pick (fix 3), the
   * step-down's held-back picks and its judgement at the 500 it ranks at (leftover fix 2), a holding SELL leaving the
   * extra-buy budget alone (fix 4), every request id past the int range, the step-down running on past held-back picks
   * (fix 1), extra positions ranked and judged at the step-down's 500 (fix 5) -- in the history AND the market chains
   * (market-stepdown-judged-at-floor) -- and the Phase 3b, Phase 4 and Engine.decide survivors' pins.
   */
  static final List<String> REQUIRED = List.of("tier-blocked-item-still-sold", "tier-holding-silenced-by-listed-sell",
    "tier-extra-typical-volume", "tier-extra-capture-cap", "tier-extra-window-share", "tier-stepdown-held-back",
    "tier-stepdown-judged-at-floor", "tier-stepdown-below-floor-at-support", "tier-extra-after-holding-sell", "tier-query-ids-past-int-range",
    "tier-persisted-listed-not-excluded", "tier-inventory-other-account-position", "tier-ge-full-every-tier", "tier-stepdown-not-at-floor",
    "tier-stepdown-sized", "tier-extra-slots-owed", "tier-extra-budget-minimum-share", "tier-extra-held-back-discarded",
    "tier-history-both-under-auto", "tier-history-members-unknown", "tier-history-focus-from-preferences", "tier-open-item-cost-sources",
    "tier-query-zero-quantities", "tier-history-equal-score", "tier-inventory-equal-value", "tier-support-zero-margin",
    "tier-support-series-unusable",
    "tier-stepdown-shared-blocklist", "tier-extra-no-minimum-tax-bar", "tier-extra-request-risk", "tier-history-one-sided-quote",
    "tier-history-stale-print-half-hour", "tier-support-headline-tax-at-paid",
    "tier-safety-thin-demoted", "tier-safety-absent-from-archive", "tier-safety-crash-demoted", "tier-safety-fill-sentence",
    "tier-stepdown-continues-past-held-back", "tier-extra-after-stepdown-minimum", "tier-extra-after-stepdown-judged-at-floor",
    "market-stepdown-judged-at-floor",
    // Engine.decide's own pins (tools/golden-decide-scenarios.mjs), each killing a named mutation of the 7 Oct sabotage pass,
    // the REQUIRED Phase 3b survivors among them (N04 decide-no-minimum-support-thin, N08 decide-limit-without-account, N15
    // decide-idle-stock-skipped, N103 decide-source-market-skips-history; N02 is tier-extra-after-stepdown-minimum and
    // market-stepdown-judged-at-floor, N104 tier-history-source-both-and-market, N107 -- slotFill on the REMAINING quantity --
    // advice-buy-progress).
    "decide-fell-through-from-history", "decide-fell-through-same-item", "decide-reachable-headline-under-rung",
    "decide-reachable-best-of-both-tiers", "decide-reachable-market-source-only", "decide-reachable-judged-at-request-minimum", "decide-reachable-history-at-rung",
    "decide-best-of-both-tie", "decide-best-of-both-market-wins", "decide-market-held-back-and-demoted", "decide-market-stepdown-held-back",
    "decide-source-market-skips-history", "decide-market-held-for-exits", "decide-market-steady-price", "decide-market-bigger-positions",
    "decide-idle-stock-skipped", "decide-limit-without-account", "decide-no-minimum-support-thin",
    "decide-slot-reserve-uncollected-sell", "decide-fill-model-built-lazily", "decide-reachable-after-three-held-back", "decide-query-repeated-name",
    // the shadow mode's pin (Stage 4 verifier): a NULL thin index is rebuilt on the next request, after hours were stored mid-run
    "decide-thin-index-retried",
    // the 8 Oct mutation verifier's survivors: the step-downs and the probe sized by the cash (X15, X16, X20), the market
    // step-down / further position asked only when the history has none (Y06, Y08), a reachable tie to the first found (X21),
    // and no tax bar on the market tier under "no minimum at all" (X26)
    "decide-stepdown-cash-capped", "decide-extras-history-before-market", "decide-reachable-cash-capped-tie", "decide-market-no-minimum-tax-bar",
    "advice-buy-progress");
  private static final Set<String> replayed = new TreeSet<>();

  public static void main(String[] args) throws Exception {
    seriesSelfCheck();
    String index = new String(EngineJson.class.getResourceAsStream("/parity/transcripts/index.txt").readAllBytes(), StandardCharsets.UTF_8);
    int fixtures = 0;
    Set<String> locks = new LinkedHashSet<>();
    for (String name : index.split("\n")) {
      if (name.isBlank()) continue;
      JsonObject t = EngineJson.read("/parity/transcripts/" + name.trim() + ".json.gz").getAsJsonObject();
      fixtures++;
      replayed.add(name.trim());
      locks.add(t.getAsJsonObject("engineLock").get("combined").getAsString());
      replay(name.trim(), t);
    }
    if (!diffs.isEmpty()) {
      StringBuilder sb = new StringBuilder(diffs.size() + " Engine.decide divergence(s) from the JS bridge:");
      for (String d : diffs.subList(0, Math.min(40, diffs.size()))) sb.append("\n  ").append(d);
      throw new AssertionError(sb.toString());
    }
    // Inert-test guards: the transcripts must still reach every tier and every outcome the engine produces. Counted on the
    // BRIDGE's answers, so a silent Java engine cannot satisfy them.
    check(fixtures >= 162 && polls >= 242, "too few transcripts (" + fixtures + " fixtures, " + polls + " polls)");
    for (String r : REQUIRED) check(replayed.contains(r), "the order-pinning transcript " + r + " is no longer replayed");
    check(locks.size() == 1, "transcripts recorded under different engines: " + locks);
    for (String src : new String[]{"holding", "inventory", "personal", "market"}) check(sources.getOrDefault(src, 0) >= 15, "too few '" + src + "' answers: " + sources);
    check(withSuggestion >= 158 && polls - withSuggestion >= 80 && withHeldBack >= 12 && withAdditional >= 21 && steppedDown >= 9 && withAdvice >= 50,
      "the transcripts no longer reach every outcome (" + withSuggestion + " answered, " + withHeldBack + " with held-back picks, " + withAdditional
        + " with extra positions, " + steppedDown + " stepped down, " + withAdvice + " with advice)");
    check(withReachable >= 8 && withFellThrough >= 1, "the reachable probe or the fell-through note is never reached (" + withReachable + ", " + withFellThrough + ")");
    check(crashDemoted >= 1 && thinDemoted >= 3 && fillSentences >= 4 && fillHistory >= 4,
      "the Phase 4 readings no longer fire (" + crashDemoted + " crash and " + thinDemoted + " thin demotions, " + fillSentences + " fill sentences, "
        + fillHistory + " fill-history statements)");
    System.out.println("PASS: Engine.decide vs the JS bridge over " + fixtures + " golden transcripts (" + polls + " polls, " + withSuggestion + " answered "
      + sources + ", " + withHeldBack + " with picks held back, " + withAdditional + " with extra positions, " + steppedDown + " stepped down, " + withReachable
      + " with a reachable figure, " + withFellThrough + " fell through from history, " + withAdvice + " with advice; " + crashDemoted + " crash and "
      + thinDemoted + " thin demotions, " + fillSentences + " fill sentences, " + fillHistory + " fill-history statements; " + compared
      + " compared bodies), engine lock " + locks.iterator().next().substring(0, 16));
    throwingSeriesIsNoSeries();
  }

  // ------------------------------------------------------------------------------------------- a series hook that THROWS

  /** When set, every poll's sell-series hook (instead of the recorded Wiki body), and every body decide wrote is kept. */
  private static Engine.SellSeries seriesOverride;
  private static final List<JsonObject> overrideBodies = new ArrayList<>();

  /**
   * X37 (8 Oct verifier): a sell-series hook that THROWS is NO series (null), as the bridge's
   * {@code try { series = JSON.parse(await get(...)).data || [] } catch {}} leaves it when the Wiki fetch fails -- never an
   * EMPTY one, which worthOf reads with the archive merged in. decide-best-of-both-tie tells the two apart: six archived hours
   * and an EMPTY recorded series value both picks at 108,000 (what buyers paid); with NO series, under twelve archived hours,
   * worthOf falls back to the quoted 180,000. (That a null series is the bridge's "no series" is pinned against the bridge by
   * tier-support-series-unusable.) Run after the main replay so its counts are untouched.
   */
  private static void throwingSeriesIsNoSeries() throws IOException {
    String name = "decide-best-of-both-tie";
    JsonObject t = EngineJson.read("/parity/transcripts/" + name + ".json.gz").getAsJsonObject();
    Engine.SellSeries[] hooks = {
      itemId -> { throw new IOException("the Wiki's per-item series could not be fetched"); },
      itemId -> null,
      itemId -> new ArrayList<>(),
    };
    List<String> held = new ArrayList<>();
    List<JsonObject> bodies = new ArrayList<>();
    int before = diffs.size();
    try {
      for (Engine.SellSeries hook : hooks) {
        seriesOverride = hook;
        overrideBodies.clear();
        replay(name, t);
        check(overrideBodies.size() == 1, name + ": one poll expected, got " + overrideBodies.size());
        JsonObject b = overrideBodies.get(0);
        bodies.add(b);
        held.add(b.getAsJsonArray("heldBack").get(0).getAsJsonObject().get("reason").getAsString());
      }
    } finally {
      seriesOverride = null;
      overrideBodies.clear();
    }
    boolean emptyAgreed = diffs.size() == before + 2; // the throwing and null runs differ from the recorded (empty-series) body; the empty run agrees
    diffs.subList(before, diffs.size()).clear();
    check(emptyAgreed, name + ": only the no-series runs may differ from the bridge's recorded empty-series body");
    check(held.get(2).equals("A market-wide pick worth about 108,000 gp, set aside for your own Nature rune at about 108,000 gp."),
      "setup: an EMPTY series is read with the archive merged in: " + held.get(2));
    check(held.get(1).equals("A market-wide pick worth about 180,000 gp, set aside for your own Nature rune at about 180,000 gp."),
      "setup: NO series under twelve archived hours is the quoted spread: " + held.get(1));
    check(EngineJson.diff(bodies.get(1), bodies.get(0)).isEmpty() && held.get(0).equals(held.get(1)),
      "a series hook that THROWS must be read as NO series (null), not as an empty one: " + held.get(0));
    System.out.println("PASS: a sell-series hook that throws is no series (" + held.get(0) + "), never an empty one");
  }

  // ------------------------------------------------------------------------------------------- one transcript

  private static final class Wiki {
    final long at;
    final String url;
    final String body;

    Wiki(long at, String url, String body) {
      this.at = at;
      this.url = url;
      this.body = body;
    }
  }

  /** The body the bridge's market cache held for this URL at {@code at}: the last fetch recorded at or before it. */
  private static String cached(List<Wiki> wiki, String url, long at) {
    String body = null;
    for (Wiki w : wiki) if (w.at <= at && w.url.equals(url)) body = w.body;
    return body;
  }

  private static final String API = "https://prices.runescape.wiki/api/v1/osrs/";

  private static void replay(String name, JsonObject t) throws IOException {
    JsonObject boot = t.getAsJsonObject("input").getAsJsonObject("boot");
    List<HourBucket> archive = EngineJson.buckets(boot.get("archive1h"));
    List<HourBucket> archiveOrEmpty = archive == null ? new ArrayList<>() : archive;
    JsonArray steps = t.getAsJsonArray("steps");
    List<Wiki> wiki = new ArrayList<>();
    for (JsonElement w : t.getAsJsonArray("wiki")) {
      JsonObject o = w.getAsJsonObject();
      check(!o.get("url").getAsString().contains("/5m"), name + ": a five-minute bucket was fetched");
      wiki.add(new Wiki(o.get("at").getAsLong(), o.get("url").getAsString(), o.get("body").getAsString()));
    }
    Engine.Prefs prefs = Engine.Prefs.fromJson(boot.get("preferences"));
    // The bridge's per-process market state, per transcript: the crash watch (reading the 1h archive, its five-minute
    // window starting EMPTY) and the player fill model's hourly cache.
    CrashWatch.Watch watch = new CrashWatch.Watch(from -> MarketAggregates.readArchive(archiveOrEmpty, (long) from, Long.MAX_VALUE), from -> new ArrayList<>(), null);
    FillModelCache fill = new FillModelCache(from -> MarketAggregates.readArchive(archiveOrEmpty, from, Long.MAX_VALUE));
    int[] fed = {0};
    TranscriptDriver d = new TranscriptDriver(name);
    d.boot(null, boot.get("preferences"));
    d.observe((i, at, query, response, store) -> {
      for (; fed[0] < steps.size(); fed[0]++) {
        JsonObject s = steps.get(fed[0]).getAsJsonObject();
        if (s.get("i").getAsInt() >= i) break;
        try {
          // hours stored into the bridge's archive mid-run: in the archive from this step on (the caches above read it live)
          if (s.has("store1h")) {
            check(archive != null, name + ": hours stored with no boot archive");
            archive.addAll(EngineJson.buckets(s.get("store1h")));
          }
          if (s.has("feed5m")) watch.addFiveMinute(EngineJson.bucket(s.get("feed5m")), s.get("at").getAsLong());
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      try {
        poll(name + " step " + i, at, query, response, store, d.profitSince(), archive, wiki, prefsAt(prefs, d, query, name + " step " + i), watch, fill);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    d.run(steps);
    for (String x : d.diffs) diffs.add("[journal] " + x);
  }

  /**
   * The preferences the bridge reads at this step: the boot file's, with the blocked list the Block button has written since
   * (server.mjs setBlocked rewrites preferences.json; every other field it keeps as readPreferences normalised it, which
   * {@link Engine.Prefs} already holds).
   */
  static Engine.Prefs prefsAt(Engine.Prefs boot, TranscriptDriver d) {
    java.util.List<Integer> blocked = d.blockedNow();
    return blocked == null ? boot : new Engine.Prefs(boot.focus, blocked, boot.spread, boot.captureCap);
  }

  /**
   * What the PLUGIN's engine reads for this poll (8 Oct 2026: Block is per character in the plugin): the boot list plus the Block
   * presses of the account this query names -- the bridge's list is one for the machine. The engine is the same either way, so
   * the bridge's answer is the expected one only where both lists agree; a transcript in which another account's press would
   * change what this account sees FAILS here, so the difference must be asserted explicitly (as InProcessButtonsTest does for
   * its one intended difference), never hidden.
   */
  static Engine.Prefs prefsAt(Engine.Prefs boot, TranscriptDriver d, String query, String where) {
    String account = "";
    for (String part : query.split("&")) if (part.startsWith("account=")) account = part.substring(8);
    java.util.List<Integer> mine = d.blockedFor(account), bridge = d.blockedNow();
    if (!java.util.Objects.equals(mine == null ? null : new java.util.TreeSet<>(mine), bridge == null ? null : new java.util.TreeSet<>(bridge)))
      throw new AssertionError(where + ": the plugin's per-character Block list " + mine + " is not the bridge's machine-wide " + bridge
        + " -- assert this per-account difference explicitly");
    return mine == null ? boot : new Engine.Prefs(boot.focus, mine, boot.spread, boot.captureCap);
  }

  /** The market snapshot as the bridge's caches held it at {@code at}. */
  static Engine.MarketData market(long at, List<HourBucket> archive, List<String[]> wikiAt, CrashWatch.Watch watch, Engine.FillModelSource fill) throws IOException {
    Engine.MarketData m = new Engine.MarketData();
    String latest = null, hour = null, mapping = null;
    for (String[] w : wikiAt) {
      if (w[0].equals(API + "latest")) latest = w[1];
      if (w[0].equals(API + "1h")) hour = w[1];
      if (w[0].equals(API + "mapping")) mapping = w[1];
    }
    m.latest = latest == null ? null : WikiJson.latest(latest);
    m.latestHour = hour == null ? null : WikiJson.hour(hour);
    m.catalog = mapping == null ? null : ItemCatalog.parse(mapping);
    m.typicalVolumes = archive == null ? Collections.emptyMap() : MarketAggregates.typicalVolumesAt(archive, at);
    m.rankPrices = archive == null ? Collections.emptyMap() : MarketAggregates.robustPricesAt(archive, at);
    m.recentHours = archive == null ? new ArrayList<>() : archive;
    m.thin = archive == null ? null : SafetyChecks.thinIndex(MarketAggregates.readArchive(archive, Math.floorDiv(at, 1000) - 14 * 86400L, Long.MAX_VALUE));
    m.crashWatch = watch;
    m.fillModel = fill;
    return m;
  }

  private static void poll(String where, long at, String query, JsonObject response, Store store, Long profitSince, List<HourBucket> archive, List<Wiki> wiki,
                           Engine.Prefs prefs, CrashWatch.Watch watch, FillModelCache fill) throws IOException {
    polls++;
    check(response.get("status").getAsInt() == 200, where + ": the bridge answered " + response.get("status"));
    JsonObject body = response.getAsJsonObject("body");
    Engine.Query q = Engine.Query.parse(query);
    List<String[]> wikiAt = new ArrayList<>();
    for (Wiki w : wiki) if (w.at <= at) wikiAt.add(new String[]{w.url, w.body});
    Engine.MarketData market = market(at, archive, wikiAt, watch, fill);
    Engine.Hooks hooks = new Engine.Hooks();
    hooks.sellSeries = seriesOverride != null ? seriesOverride : itemId -> series(cached(wiki, API + "timeseries?id=" + itemId + "&timestep=1h", at));
    StoreState state = store.state(at);
    AccountView view = AccountView.of(state, store.offers(), q.get("account"), at);
    Engine.Response r = Engine.decide(q, view, Engine.SharedJournal.of(state, profitSince), store.offers(), market, prefs, hooks, at);
    if (r.failed()) {
      diffs.add(where + ": Engine.decide failed: " + r.error);
      if (r.cause != null) r.cause.printStackTrace();
      return;
    }
    JsonObject expected = body.deepCopy();
    JsonElement s = expected.get("suggestion");
    if (!isNullish(s)) {
      s.getAsJsonObject().remove("id");       // the suggestion log's handle: wall clock and sequence
      s.getAsJsonObject().remove("accepted"); // read back from that log
      withSuggestion++;
      JsonObject so = s.getAsJsonObject();
      sources.merge(so.get("source").getAsString(), 1, Integer::sum);
      if (so.has("belowUsualBar")) steppedDown++;
      JsonElement support = so.get("sellSupport");
      if (!isNullish(support) && support.getAsJsonObject().has("crashing")) crashDemoted++;
      if (!isNullish(support) && support.getAsJsonObject().has("thinMarket")) thinDemoted++;
      String reasoning = so.has("reasoning") ? so.get("reasoning").getAsString() : "";
      if (reasoning.contains("of your own past offers") || reasoning.contains("past offers this size")) fillSentences++;
      if (reasoning.contains("Fill history:")) fillHistory++;
    }
    if (body.getAsJsonArray("additional").size() > 0) withAdditional++;
    if (body.getAsJsonArray("heldBack").size() > 0) withHeldBack++;
    if (body.getAsJsonArray("relistAdvice").size() > 0) withAdvice++;
    if (!isNullish(body.get("reachable"))) withReachable++;
    if (!isNullish(body.get("fellThroughFromHistory"))) withFellThrough++;
    compared++;
    JsonObject actual = ResponseJson.response(r);
    if (seriesOverride != null) overrideBodies.add(actual);
    List<String> d = EngineJson.diff(expected, actual);
    if (!d.isEmpty()) diffs.add(where + ": " + String.join("; ", d.subList(0, Math.min(3, d.size()))) + (d.size() > 3 ? " (+" + (d.size() - 3) + " more)" : ""));
  }

  // ------------------------------------------------------------------------------------------- the injected series

  /**
   * The Wiki's per-item series body as server.mjs reads it: {@code try { series = JSON.parse(body).data || []; } catch {}},
   * in its OWN try inside the sell-support hook. So:
   * <ul>
   *   <li>no body (the fetch failed), a body JSON.parse refuses (an HTML error page, trailing text, a lenient-only token)
   *       or a JSON {@code null} (whose {@code .data} throws): NO series (null);</li>
   *   <li>a falsy {@code data} (absent, null, false, 0, ""), or any top-level value without one: an EMPTY series;</li>
   *   <li>a truthy {@code data} that is not an array: also empty, as mergeArchiveHours reads any non-array as [];</li>
   *   <li>an array: its points. A JSON null point stays null (JS throws on it: no reading). A point that is not an object
   *       has no timestamp in JS and never falls inside the window, so it is a point with a NaN timestamp here.</li>
   * </ul>
   * The tier-support-series-unusable transcript pins the first two families against the real bridge.
   */
  static List<SellSupport.Point> series(String body) {
    if (body == null) return null;
    JsonElement root;
    try {
      com.google.gson.stream.JsonReader r = new com.google.gson.stream.JsonReader(new java.io.StringReader(body));
      r.setLenient(false); // JSON.parse: no comments, single quotes, unquoted names or NaN
      root = com.google.gson.internal.Streams.parse(r);
      if (r.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) return null; // trailing text: JSON.parse throws
    } catch (RuntimeException | IOException e) {
      return null;
    }
    if (root == null || root.isJsonNull()) return null; // JSON.parse('null').data throws; so does an empty body
    JsonElement data = root.isJsonObject() ? root.getAsJsonObject().get("data") : null;
    List<SellSupport.Point> out = new ArrayList<>();
    if (data == null || !data.isJsonArray()) return out; // falsy -> [], and a truthy non-array merges as []
    for (JsonElement p : data.getAsJsonArray()) {
      if (p.isJsonNull()) out.add(null);
      else if (p.isJsonObject()) out.add(EngineJson.point(p));
      else out.add(new SellSupport.Point(Double.NaN, null, null, null, null));
    }
    return out;
  }

  /** What series() must answer for the bodies JS reads differently from a lenient parser (run once, at start). */
  private static void seriesSelfCheck() {
    check(series(null) == null && series("<html>502</html>") == null && series("null") == null && series("") == null
      && series("{\"data\":[]} x") == null && series("{'data':[]}") == null && series("{\"data\":NaN}") == null, "series(): a body JSON.parse refuses is no series");
    for (String empty : new String[]{"5", "\"x\"", "[1]", "true", "{}", "{\"data\":null}", "{\"data\":0}", "{\"data\":false}", "{\"data\":\"\"}",
      "{\"data\":{\"a\":1}}", "{\"data\":7}"}) {
      List<SellSupport.Point> s = series(empty);
      check(s != null && s.isEmpty(), "series(" + empty + "): JS reads an empty series");
    }
    List<SellSupport.Point> mixed = series("{\"data\":[null,5,{\"timestamp\":3600,\"avgHighPrice\":10,\"highPriceVolume\":2}]}");
    check(mixed.size() == 3 && mixed.get(0) == null && Double.isNaN(mixed.get(1).timestamp) && mixed.get(2).timestamp == 3600, "series(): points kept in order");
  }
}
