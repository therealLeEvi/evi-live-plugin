package com.evi.live.engine;

import static com.evi.live.engine.EngineJson.check;
import static com.evi.live.engine.EngineJson.isNullish;

import com.evi.live.journal.Store;
import com.evi.live.journal.StoreState;
import com.evi.live.journal.TranscriptDriver;
import com.evi.live.market.HourBucket;
import com.evi.live.market.LatestPrices;
import com.evi.live.market.WikiJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * GOLDEN TRANSCRIPTS of the real bridge, every one of them, replayed through the Java journal and {@link AdviceNotes}: at
 * each GET /api/suggestion the advice channel the plugin draws under "Active offers" ({@code relistAdvice} -- crash notes,
 * sell-below-break-even, margin-gone buys, relist advice, buy progress, holdings) is composed from the Java store's state
 * and compared, entry by entry and character by character, with what the bridge sent.
 *
 * <p>The inputs the advice reads but does not decide are taken from the poll itself: the account and the pace from the
 * query, /latest as the bridge's cache held it (the last fetch recorded at or before the poll), and from the answer the
 * suggestion's item and slotFill's "may not fill in time" items -- both checked elsewhere (EngineTranscriptTest), and the
 * market tier that can produce them is not ported yet. The crash watch is the JAVA watch, fed the same five-minute
 * buckets at the same moments the recorder fed the bridge's ({@code feed5m} steps), reading the transcript's archive.
 */
public final class AdviceTranscriptTest {
  private static final String API = "https://prices.runescape.wiki/api/v1/osrs/";
  private static final List<String> diffs = new ArrayList<>();
  private static int polls, withAdvice, entries;
  private static final Map<String, Integer> labels = new TreeMap<>();
  private static final Set<String> replayed = new TreeSet<>();
  /**
   * EVERY advice scenario (tools/golden-advice-scenarios.mjs), each pinning one rule or killing named mutants: if one stops
   * being replayed -- dropped from index.txt, renamed, its resource deleted -- its rule is unguarded and this test fails.
   * The reverse holds too: an advice-* transcript that is replayed but not listed here fails, so a new pin cannot be added
   * without being required.
   */
  static final List<String> REQUIRED = List.of("advice-relist-thresholds", "advice-relist-below-break-even", "advice-sell-below-break-even",
    "advice-buy-margin-gone", "advice-buy-progress", "advice-holdings", "advice-crash-notes", "advice-account-scoping", "advice-logged-out",
    "advice-lots-and-cancelled-sell", "advice-alt-listing-and-order",
    // 7 Oct, second pass (V06 V12 V13 V15 V16 V19 V22 V23)
    "advice-two-accounts-cost-basis", "advice-two-accounts-crash", "advice-hour-clock-and-no-pace",
    // 7 Oct, third pass (M06 M12 M14)
    "advice-two-accounts-same-item", "advice-two-accounts-holds-nothing", "advice-crash-ended-unseen");

  public static void main(String[] args) throws Exception {
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
      StringBuilder sb = new StringBuilder(diffs.size() + " relistAdvice divergence(s) from the JS bridge:");
      for (String d : diffs.subList(0, Math.min(40, diffs.size()))) sb.append("\n  ").append(d);
      throw new AssertionError(sb.toString());
    }
    for (String r : REQUIRED) check(replayed.contains(r), "the advice transcript " + r + " is no longer replayed");
    for (String r : replayed) check(!r.startsWith("advice-") || REQUIRED.contains(r), "the advice transcript " + r + " is replayed but not REQUIRED");
    check(locks.size() == 1, "transcripts recorded under different engines: " + locks);
    // Inert-test guards: the transcripts must still reach every kind of card the channel carries.
    for (String l : new String[]{"Below break-even", "Margin gone", "Not selling", "Market moved away", "No fills yet", "Part filled",
      "Holding", "Holding, under water", "Crashing"})
      check(labels.getOrDefault(l, 0) >= 1, "no transcript reaches a '" + l + "' card: " + labels);
    check(polls >= 150 && withAdvice >= 30 && entries >= 60, "too few polls with advice (" + polls + " polls, " + withAdvice + " with advice, " + entries + " entries)");
    System.out.println("PASS: relistAdvice vs the JS bridge over " + fixtures + " golden transcripts (" + polls + " polls, " + withAdvice + " with advice, "
      + entries + " entries " + labels + "), engine lock " + locks.iterator().next().substring(0, 16));
  }

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

  private static String cached(List<Wiki> wiki, String url, long at) {
    String body = null;
    for (Wiki w : wiki) if (w.at <= at && w.url.equals(url)) body = w.body;
    return body;
  }

  private static void replay(String name, JsonObject t) throws IOException {
    JsonObject boot = t.getAsJsonObject("input").getAsJsonObject("boot");
    List<HourBucket> archive = EngineJson.buckets(boot.get("archive1h"));
    List<Wiki> wiki = new ArrayList<>();
    for (JsonElement w : t.getAsJsonArray("wiki")) {
      JsonObject o = w.getAsJsonObject();
      wiki.add(new Wiki(o.get("at").getAsLong(), o.get("url").getAsString(), o.get("body").getAsString()));
    }
    JsonArray steps = t.getAsJsonArray("steps");
    // The bridge's crash watch reads the 1h archive (readArchive: ts >= from) and starts with an empty five-minute window.
    CrashWatch.Watch watch = new CrashWatch.Watch(from -> {
      List<HourBucket> out = new ArrayList<>();
      if (archive != null) for (HourBucket b : archive) if (b.ts >= from) out.add(b);
      return out;
    }, from -> new ArrayList<>(), null);
    int[] fed = {0};
    TranscriptDriver d = new TranscriptDriver(name);
    d.boot(null, boot.get("preferences"));
    d.observe((i, at, query, response, store) -> {
      // Feed every five-minute bucket the recorder handed the bridge before this poll, at its own moment.
      for (; fed[0] < steps.size(); fed[0]++) {
        JsonObject s = steps.get(fed[0]).getAsJsonObject();
        if (s.get("i").getAsInt() >= i) break;
        try {
          // hours stored into the bridge's archive mid-run (store1h): the crash watch reads them from this step on
          if (s.has("store1h")) {
            check(archive != null, name + ": hours stored with no boot archive");
            archive.addAll(EngineJson.buckets(s.get("store1h")));
          }
          if (s.has("feed5m")) watch.addFiveMinute(EngineJson.bucket(s.get("feed5m")), s.get("at").getAsLong());
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      poll(name + " step " + i, at, query, response, store, wiki, watch);
    });
    d.run(steps);
    for (String x : d.diffs) diffs.add("[journal] " + x);
  }

  private static Map<String, String> params(String q) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String part : q.split("&")) {
      if (part.isEmpty()) continue;
      int eq = part.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
      out.putIfAbsent(k, v);
    }
    return out;
  }

  private static void poll(String where, long at, String query, JsonObject response, Store store, List<Wiki> wiki, CrashWatch.Watch watch) {
    if (response.get("status").getAsInt() != 200) return;
    JsonObject body = response.getAsJsonObject("body");
    if (!body.has("relistAdvice")) return;
    polls++;
    Map<String, String> q = params(query);
    AdviceNotes.Inputs in = new AdviceNotes.Inputs();
    String account = q.get("account");
    // The poll's account view, built ONCE from one state(at), exactly as the plugin will: everything the advice knows
    // about offers, lots and cost basis is in it, and nothing of any other account.
    StoreState state = store.state(at);
    in.view = AccountView.of(state, store.offers(), account, at);
    String latestBody = cached(wiki, API + "latest", at);
    try {
      in.marketLatest = latestBody == null ? null : WikiJson.latest(latestBody);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String raw = q.get("duration");
    in.targetDurationMinutes = Sizing.duration(raw == null ? 0 : JsValues.toNumber(raw));
    Set<Integer> slow = new LinkedHashSet<>();
    if (body.has("slotFill") && body.get("slotFill").isJsonArray())
      for (JsonElement f : body.getAsJsonArray("slotFill")) {
        JsonObject o = f.getAsJsonObject();
        if (o.has("likelyToFillInTime") && !o.get("likelyToFillInTime").isJsonNull() && !o.get("likelyToFillInTime").getAsBoolean())
          slow.add(o.get("itemId").getAsInt());
      }
    in.slowFillItemIds = slow;
    JsonElement s = body.get("suggestion");
    in.suggestedItemId = isNullish(s) ? null : s.getAsJsonObject().get("itemId").getAsInt();
    in.crashWatch = watch;
    in.now = at;
    List<AdviceNote> notes = AdviceNotes.compose(in);
    JsonArray expected = body.getAsJsonArray("relistAdvice");
    for (String x : EngineJson.diff(expected, AdviceVectorsTest.notes(notes))) diffs.add(where + " relistAdvice" + x.substring(1));
    if (expected.size() > 0) withAdvice++;
    entries += expected.size();
    for (JsonElement e : expected) {
      JsonObject o = e.getAsJsonObject();
      String label = o.has("label") ? o.get("label").getAsString() : o.get("belowBreakEven").getAsBoolean() ? "Below break-even" : isNullish(o.get("name")) ? "?" : "Margin gone|Nobody selling";
      if ("Margin gone|Nobody selling".equals(label)) label = o.get("message").getAsString().contains("nobody is selling") ? "Nobody selling" : "Margin gone";
      labels.merge(label, 1, Integer::sum);
    }
  }
}
