package com.evi.live.inprocess;

import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.LatestPrices;
import com.evi.live.market.MarketAggregates;
import com.evi.live.market.WikiJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

/**
 * THE TRANSCRIPT RIG the in-process tests replay the public golden transcripts through (moved, unchanged in behaviour, from the
 * retired developer shadow mode's test at the 4.0.0 switch-over): the transcripts and their index, which polls the plugin's
 * own composition can reproduce BY DESIGN, and the transcript's market as the plugin's price layer would hold it. The recorded
 * bodies are the old companion app's answers; replaying them needs no companion app. Synthetic data only.
 */
public final class EngineRig {
  private EngineRig() {}

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  static final Function<String, JsonElement> PARSER = s -> new JsonParser().parse(s);
  private static final String API = "https://prices.runescape.wiki/api/v1/osrs/";

  static JsonElement j(String s) {
    return new JsonParser().parse(s);
  }

  // ------------------------------------------------------------------------------------------- the transcripts

  static JsonObject transcript(String name) throws IOException {
    InputStream raw = EngineRig.class.getResourceAsStream("/parity/transcripts/" + name + ".json.gz");
    if (raw == null) return null;
    try (InputStream in = new GZIPInputStream(raw); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return new JsonParser().parse(r).getAsJsonObject();
    }
  }

  static List<String> index() throws IOException {
    String text = new String(EngineRig.class.getResourceAsStream("/parity/transcripts/index.txt").readAllBytes(), StandardCharsets.UTF_8);
    List<String> out = new ArrayList<>();
    for (String l : text.split("\\r?\\n")) if (!l.trim().isEmpty()) out.add(l.trim());
    return out;
  }

  /**
   * The bridge's first per-item Wiki series fetch (the sell-support reading the plugin takes from its archive instead), or
   * Long.MAX_VALUE: polls from then on are not the plugin's by design. A poll before it never read a series -- the bridge
   * fetches one AT the poll that first needs it -- so it is compared.
   */
  static long firstSeries(JsonObject t) {
    long first = Long.MAX_VALUE;
    for (JsonElement w : t.getAsJsonArray("wiki"))
      if (w.getAsJsonObject().get("url").getAsString().contains("timeseries")) first = Math.min(first, w.getAsJsonObject().get("at").getAsLong());
    return first;
  }

  /** Why the in-process composition cannot reproduce this transcript BY DESIGN (null: it can, up to {@link #firstSeries}). */
  static String outOfScope(JsonObject t) {
    JsonObject boot = t.getAsJsonObject("input").getAsJsonObject("boot");
    JsonElement seed = boot.get("journal");
    if (seed != null && seed.isJsonArray() && seed.getAsJsonArray().size() > 0) return "a seed journal";
    int gets = 0;
    for (JsonElement se : t.getAsJsonArray("steps")) {
      JsonObject s = se.getAsJsonObject();
      if (s.has("feed5m")) return "the five-minute stream";
      if (s.has("call") || s.has("restart")) return "a bridge-side call";
      if (!s.has("request")) continue;
      JsonObject r = s.getAsJsonObject("request");
      String m = r.get("method").getAsString(), p = r.get("path").getAsString();
      if (m.equals("POST") && !p.equals("/api/events")) return "a scanner or sidebar POST (flip import, personal use, not held, profit reset)";
      if (m.equals("GET") && !p.equals("/api/suggestion")) continue; // a read of another route changes nothing
      if (m.equals("GET")) {
        gets++;
        if (s.getAsJsonObject("response").get("status").getAsInt() != 200) return "a failed request";
      }
    }
    return gets == 0 ? "no suggestion poll" : null;
  }

  /**
   * The transcript's market as the plugin's price layer would hold it, poll by poll: the boot archive, plus the hours a
   * {@code store1h} step stored (from that step on), less any hour a test WITHHOLDS (not yet backfilled). Every hour added
   * moves {@link EngineFeed.Market#archiveRevision}, as PriceDataService's does.
   */
  static final class TestMarket implements EngineFeed.MarketSource {
    private final List<HourBucket> stored;
    final JsonArray wiki;
    volatile long at;
    final List<String> threads = Collections.synchronizedList(new ArrayList<>());
    private final java.util.Set<Long> withheld = Collections.synchronizedSet(new java.util.TreeSet<>());
    private final AtomicLong revision = new AtomicLong();
    final List<Long> reads = Collections.synchronizedList(new ArrayList<>());

    TestMarket(List<HourBucket> archive, JsonArray wiki) {
      this.stored = archive == null ? null : Collections.synchronizedList(new ArrayList<>(archive));
      this.wiki = wiki;
      if (archive != null) revision.set(archive.size());
    }

    /** What the price layer holds now (null: no archive at all). */
    List<HourBucket> visible() {
      if (stored == null) return null;
      List<HourBucket> out = new ArrayList<>();
      synchronized (stored) {
        for (HourBucket b : stored) if (!withheld.contains(b.ts)) out.add(b);
      }
      return out;
    }

    void store(List<HourBucket> hours) {
      check(stored != null, "hours stored with no boot archive");
      stored.addAll(hours);
      revision.addAndGet(hours.size());
    }

    /** The hour is not yet backfilled: the price layer does not have it. */
    void withhold(long ts) {
      withheld.add(ts);
    }

    /** The hour arrives (an older hour, backfilled after the newest): the revision moves, the newest stored hour does not. */
    void backfill(long ts) {
      check(withheld.remove(ts), "not withheld: " + ts);
      revision.incrementAndGet();
    }

    String body(String url) {
      String b = null;
      for (JsonElement w : wiki) {
        JsonObject o = w.getAsJsonObject();
        if (o.get("at").getAsLong() <= at && o.get("url").getAsString().equals(url)) b = o.get("body").getAsString();
      }
      return b;
    }

    long fetchedAt(String url) {
      long a = 0;
      for (JsonElement w : wiki) {
        JsonObject o = w.getAsJsonObject();
        if (o.get("at").getAsLong() <= at && o.get("url").getAsString().equals(url)) a = o.get("at").getAsLong();
      }
      return a;
    }

    @Override public EngineFeed.Market market(long nowMs) {
      threads.add(Thread.currentThread().getName());
      try {
        String latest = body(API + "latest"), hour = body(API + "1h"), mapping = body(API + "mapping");
        LatestPrices l = latest == null ? null : WikiJson.latest(latest);
        HourBucket h = hour == null ? null : WikiJson.hour(hour);
        ItemCatalog c = mapping == null ? null : ItemCatalog.parse(mapping);
        List<HourBucket> archive = visible();
        long newest = Long.MIN_VALUE;
        if (archive != null) for (HourBucket b : archive) newest = Math.max(newest, b.ts);
        return new EngineFeed.Market(l, fetchedAt(API + "latest"), h, c, archive == null ? Collections.emptyMap() : MarketAggregates.typicalVolumesAt(archive, nowMs),
          archive == null ? Collections.emptyMap() : MarketAggregates.robustPricesAt(archive, nowMs), newest, 0, 0, 0, 0, revision.get());
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
      threads.add(Thread.currentThread().getName());
      reads.add(fromTs);
      List<HourBucket> archive = visible();
      return CompletableFuture.completedFuture(MarketAggregates.readArchive(archive == null ? new ArrayList<>() : archive, fromTs, Long.MAX_VALUE));
    }
  }


  static int pollsBefore(JsonObject t, long at) {
    int n = 0;
    for (JsonElement se : t.getAsJsonArray("steps")) {
      JsonObject s = se.getAsJsonObject();
      if (s.get("at").getAsLong() < at && s.has("request") && s.getAsJsonObject("request").get("path").getAsString().equals("/api/suggestion")) n++;
    }
    return n;
  }

  /** The index of the first step at or after {@code at} (polls from there are not replayed), or Integer.MAX_VALUE. */
  static int stepBefore(JsonObject t, long at) {
    for (JsonElement se : t.getAsJsonArray("steps")) if (se.getAsJsonObject().get("at").getAsLong() >= at) return se.getAsJsonObject().get("i").getAsInt();
    return Integer.MAX_VALUE;
  }
}
