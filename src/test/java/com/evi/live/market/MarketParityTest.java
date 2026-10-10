package com.evi.live.market;

import static com.evi.live.market.MarketTestSupport.check;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.client.util.Filepath;

/**
 * PUBLIC parity for the price-data readers: every answer recorded from the REAL bridge JavaScript
 * (tools/parity-market-vectors.mjs: robustPrices, volumeReadingFor, compactBucket and readArchive imported; the
 * server's robustPricesCached, typicalVolumesCached and itemIndex cut out of server.mjs's own text and run with a
 * pinned clock), against the Java port on the SAME synthetic archive slices. Zero divergences required.
 *
 * <p>Every slice is checked TWICE on the Java side: through the list-based ports, and through the PRODUCTION path --
 * the buckets written into a real per-hour {@link HourlyArchive} with the plugin's own writer, then read back by
 * {@link MarketAggregates#build}, one file at a time. Replicating the production call is the point: three checks in
 * this project gave wrong answers because they were measured on an engine the bridge did not run.
 */
public final class MarketParityTest {
  static final List<String> diffs = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    JsonObject v = MarketTestSupport.readJson("/parity/market-vectors.json.gz").getAsJsonObject();
    check("evi-market-vectors/1".equals(v.get("format").getAsString()) && "public".equals(v.get("tier").getAsString()), "not the public market vectors");
    JsonObject k = v.getAsJsonObject("constants");
    check(k.get("ROBUST_PRICE_HOURS").getAsInt() == MarketAggregates.ROBUST_PRICE_HOURS, "ROBUST_PRICE_HOURS differs from the JS engine's");
    check(k.get("MIN_ROBUST_HOURS").getAsInt() == MarketAggregates.MIN_ROBUST_HOURS, "MIN_ROBUST_HOURS differs from the JS engine's");

    int compact = compact(v.getAsJsonArray("compact"));
    Map<String, List<HourBucket>> slices = new java.util.HashMap<>();
    for (JsonElement e : v.getAsJsonArray("slices")) slices.put(e.getAsJsonObject().get("name").getAsString(), buckets(e.getAsJsonObject().getAsJsonArray("buckets")));
    int robust = robust(v.getAsJsonArray("robust"), slices);
    int[] sl = slices(v.getAsJsonArray("slices"), slices);
    int vol = volumeReadings(v.getAsJsonArray("volumeReading"));
    int map = mapping(v.getAsJsonArray("mapping"));
    if (!diffs.isEmpty()) {
      System.out.println("MARKET PARITY: " + diffs.size() + " DIVERGENCES");
      for (String d : diffs.subList(0, Math.min(60, diffs.size()))) System.out.println("  " + d);
      throw new AssertionError(diffs.size() + " market parity divergence(s); first: " + diffs.get(0));
    }
    System.out.println("MARKET PARITY PASS: " + compact + " v2 bodies -> archive lines byte for byte, " + robust + " robustPrices cases, "
      + sl[0] + " slices x " + sl[1] + " clocks (robustPricesCached + typicalVolumesCached, list port AND the per-hour archive through build()), "
      + sl[2] + " readArchive ranges (both paths), " + vol + " latest-hour volume readings, " + map + " mapping queries; 0 divergences");
  }

  static List<HourBucket> buckets(JsonArray a) throws Exception {
    List<HourBucket> out = new ArrayList<>();
    for (JsonElement e : a) out.add(e.isJsonNull() ? null : HourBucket.parseLine(e.toString()));
    return out;
  }

  static int compact(JsonArray cases) throws Exception {
    int n = 0;
    for (JsonElement e : cases) {
      JsonObject c = e.getAsJsonObject();
      HourBucket b = WikiJson.hour(c.get("body").getAsString());
      boolean stored = b != null && b.size() > 0;
      if (c.get("line").isJsonNull()) {
        if (stored) diffs.add("compact #" + n + ": JS does not store this body, Java would: " + b.line());
      } else if (!stored) {
        diffs.add("compact #" + n + ": JS stores " + c.get("line").getAsString() + " but Java has nothing");
      } else if (!c.get("line").getAsString().equals(b.line())) {
        diffs.add("compact #" + n + ": line differs\n    JS:   " + c.get("line").getAsString() + "    Java: " + b.line());
      }
      n++;
    }
    return n;
  }

  static int robust(JsonArray cases, Map<String, List<HourBucket>> slices) throws Exception {
    int n = 0;
    for (JsonElement e : cases) {
      JsonObject c = e.getAsJsonObject();
      List<HourBucket> list = c.has("slice") ? slices.get(c.get("slice").getAsString()) : buckets(c.getAsJsonArray("buckets"));
      compareRobust("robust " + c.get("name").getAsString(), c.get("result"), MarketAggregates.robustPrices(list, c.get("hours").getAsInt()));
      n++;
    }
    return n;
  }

  static int[] slices(JsonArray cases, Map<String, List<HourBucket>> parsed) throws Exception {
    int s = 0, clocks = 0, reads = 0;
    for (JsonElement e : cases) {
      JsonObject c = e.getAsJsonObject();
      String name = c.get("name").getAsString();
      List<HourBucket> list = parsed.get(name);
      // The production path: the plugin's own per-hour archive. JS readArchive keeps the LAST write of a repeated ts;
      // the plugin's files can never hold two (a file is never replaced), so the archive gets the deduplicated list.
      Filepath root = MarketTestSupport.tempRoot("evi-market-parity");
      try {
        HourlyArchive archive = new HourlyArchive(root, m -> { });
        for (HourBucket b : MarketAggregates.readArchive(list, Long.MIN_VALUE, Long.MAX_VALUE))
          check(archive.store(b), name + ": the archive refused a fresh hour " + b.ts);
        for (JsonElement a : c.getAsJsonArray("at")) {
          JsonObject at = a.getAsJsonObject();
          long now = at.get("nowMs").getAsLong();
          String where = name + " @" + now;
          compareRobust(where + " robustPricesAt (list)", at.get("robust"), MarketAggregates.robustPricesAt(list, now));
          compareTypical(where + " typicalVolumesAt (list)", at.get("typical"), MarketAggregates.typicalVolumesAt(list, now));
          MarketAggregates.Readings r = MarketAggregates.build(archive, now);
          compareRobust(where + " build() robust (archive)", at.get("robust"), r.robust);
          compareTypical(where + " build() typical (archive)", at.get("typical"), r.typical);
          clocks++;
        }
        for (JsonElement a : c.getAsJsonArray("reads")) {
          JsonObject rd = a.getAsJsonObject();
          long from = rd.get("from").getAsLong(), to = rd.get("to").isJsonNull() ? Long.MAX_VALUE : rd.get("to").getAsLong();
          List<Long> wantTs = new ArrayList<>();
          for (JsonElement t : rd.getAsJsonArray("ts")) wantTs.add(t.getAsLong());
          String wantSha = rd.get("linesSha256").getAsString();
          compareRead(name + " readArchive(" + from + "," + to + ") list", wantTs, wantSha, MarketAggregates.readArchive(list, from, to));
          compareRead(name + " readArchive(" + from + "," + to + ") archive", wantTs, wantSha, archive.read(from, to));
          reads++;
        }
      } finally {
        MarketTestSupport.deleteTree(root);
      }
      s++;
    }
    return new int[]{s, clocks, reads};
  }

  static void compareRead(String where, List<Long> wantTs, String wantSha, List<HourBucket> got) throws Exception {
    List<Long> ts = new ArrayList<>();
    StringBuilder lines = new StringBuilder();
    for (HourBucket b : got) {
      ts.add(b.ts);
      lines.append(b.line());
    }
    if (!wantTs.equals(ts)) diffs.add(where + ": hours " + ts + " expected " + wantTs);
    else if (!MarketTestSupport.sha256Hex(lines.toString()).startsWith(wantSha)) diffs.add(where + ": the archive lines differ from JS's bytes");
  }

  static void compareRobust(String where, JsonElement exp, Map<Integer, MarketAggregates.RobustPrice> act) {
    if (exp == null || exp.isJsonNull()) {
      if (act != null) diffs.add(where + ": JS null, Java " + act);
      return;
    }
    if (act == null) {
      diffs.add(where + ": JS " + exp + ", Java null");
      return;
    }
    JsonObject o = exp.getAsJsonObject();
    Set<Integer> keys = new TreeSet<>(act.keySet());
    for (String key : o.keySet()) keys.add(Integer.parseInt(key));
    for (Integer id : keys) {
      JsonElement x = o.get(String.valueOf(id));
      MarketAggregates.RobustPrice p = act.get(id);
      if (x == null || p == null) diffs.add(where + ": item " + id + " JS " + x + " Java " + p);
      else if (x.getAsJsonObject().get("high").getAsLong() != p.high || x.getAsJsonObject().get("low").getAsLong() != p.low)
        diffs.add(where + ": item " + id + " JS " + x + " Java " + p);
    }
  }

  static void compareTypical(String where, JsonElement exp, Map<Integer, Long> act) {
    JsonObject o = exp.getAsJsonObject();
    Set<Integer> keys = new TreeSet<>(act.keySet());
    for (String key : o.keySet()) keys.add(Integer.parseInt(key));
    for (Integer id : keys) {
      JsonElement x = o.get(String.valueOf(id));
      Long y = act.get(id);
      if (x == null || y == null || x.getAsLong() != y) diffs.add(where + ": item " + id + " JS " + x + " Java " + y);
    }
  }

  static int volumeReadings(JsonArray cases) throws Exception {
    int n = 0;
    for (JsonElement e : cases) {
      JsonObject c = e.getAsJsonObject();
      HourBucket b = c.get("body").isJsonNull() ? null : WikiJson.hour(c.get("body").getAsString());
      Long got = MarketAggregates.volumeReadingFor(b, c.get("itemId").getAsInt());
      JsonElement want = c.get("result");
      boolean same = want.isJsonNull() ? got == null : got != null && got == want.getAsLong();
      if (!same) diffs.add("volumeReadingFor item " + c.get("itemId") + ": JS " + want + " Java " + got);
      n++;
    }
    return n;
  }

  static int mapping(JsonArray cases) throws Exception {
    int n = 0;
    for (JsonElement e : cases) {
      JsonObject c = e.getAsJsonObject();
      ItemCatalog cat = ItemCatalog.parse(c.get("body").getAsString());
      if (cat.size() != c.get("size").getAsInt()) diffs.add("mapping size: JS " + c.get("size") + " Java " + cat.size());
      // JS's Map keeps each id at its FIRST position with its LAST value
      LinkedHashSet<Integer> order = new LinkedHashSet<>();
      for (ItemCatalog.Item i : cat.items()) order.add(i.id);
      List<Integer> want = new ArrayList<>();
      for (JsonElement x : c.getAsJsonArray("order")) want.add(x.getAsInt());
      if (!want.equals(new ArrayList<>(order))) diffs.add("mapping order: JS " + want + " Java " + order);
      for (JsonElement q : c.getAsJsonArray("queries")) {
        JsonObject o = q.getAsJsonObject();
        int id = o.get("id").getAsInt();
        ItemCatalog.Item item = cat.get(id);
        Long limit = cat.limitFor(id);
        String name = item == null ? null : item.name;
        boolean ok = o.get("present").getAsBoolean() == (item != null)
          && (o.get("name").isJsonNull() ? name == null : o.get("name").getAsString().equals(name))
          && (o.get("limit").isJsonNull() ? limit == null : limit != null && limit == o.get("limit").getAsLong())
          && o.get("members").getAsBoolean() == cat.membersOnly(id);
        if (!ok) diffs.add("mapping item " + id + ": JS " + o + " Java present=" + (item != null) + " name=" + name + " limit=" + limit + " members=" + cat.membersOnly(id));
        n++;
      }
    }
    return n;
  }
}
