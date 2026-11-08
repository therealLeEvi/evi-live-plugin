package com.evi.live.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.client.util.Filepath;

/**
 * The patient-price basis as the PLUGIN builds it (MarketAggregates.build, streamed from its own per-hour archive files)
 * against the list port that mirrors patience.mjs (patienceBasisAt): the same answer at several clocks, including a clock
 * whose newest stored hour is more than 48 hours old (no reading), a clock 30 hours on (a partial read), and an archive with a hole in its last day. Run from
 * engine.PatienceIntentTest; it lives here because the archive helpers are package-private. Synthetic data only.
 */
public final class PatienceBuildCheck {
  private PatienceBuildCheck() {}

  private static final long H = 3600;

  /** Returns the number of checks made; throws AssertionError on the first difference. */
  public static int run() throws Exception {
    int checks = 0;
    long end = 2_000_000L * H;
    List<HourBucket> list = new ArrayList<>();
    for (int k = 71; k >= 0; k--) {
      if (k == 5 || k == 6) continue; // a hole in the last day, so "the newest 24 STORED hours" and "24 clock hours" differ
      long ts = end - k * H;
      StringBuilder data = new StringBuilder();
      // two items: one dearer over the last day, one thin (fails the units floor)
      long hi = k < 24 ? 2600 + (k * 37) % 11 : 2500 + (k * 13) % 7;
      data.append("\"536\":{\"avgHighPrice\":").append(hi).append(",\"highPriceVolume\":40,\"avgLowPrice\":2300,\"lowPriceVolume\":40},");
      data.append("\"11335\":{\"avgHighPrice\":2200000,\"highPriceVolume\":").append(k % 3 == 0 ? 1 : 0).append(",\"avgLowPrice\":2000000,\"lowPriceVolume\":1}");
      list.add(WikiJson.hour("{\"timestamp\":" + ts + ",\"data\":{" + data + "}}"));
    }
    Filepath root = MarketTestSupport.tempRoot("evi-patience-build");
    try {
      HourlyArchive archive = new HourlyArchive(root, m -> { });
      for (HourBucket b : list) if (!archive.store(b)) throw new AssertionError("the archive refused an hour " + b.ts);
      for (long after : new long[]{0, 1, 3, 30, 47, 49}) {
        long nowMs = (end + after * H + 600) * 1000;
        Map<Integer, MarketAggregates.PatienceBasis> want = MarketAggregates.patienceBasisAt(list, nowMs);
        Map<Integer, MarketAggregates.PatienceBasis> got = MarketAggregates.build(archive, nowMs).patience;
        if (!want.toString().equals(got.toString()))
          throw new AssertionError("build() patience " + got + " differs from patienceBasisAt " + want + " at newest + " + after + "h");
        checks++;
        // within a few hours of the newest stored hour the read holds the whole last day: a reading for the bones, none for the
        // thin helm. Later the 48-hour read holds fewer stored hours (the bridge reads the same window), down to none.
        if (after <= 3 && (want.get(536) == null || want.get(11335) != null))
          throw new AssertionError("the fixture must give Dragon bones a reading and the thin helm none: " + want);
        if (after == 49 && !want.isEmpty()) throw new AssertionError("nothing stored in the last 48 hours: no reading, got " + want);
        checks++;
      }
    } finally {
      MarketTestSupport.deleteTree(root);
    }
    return checks;
  }
}
