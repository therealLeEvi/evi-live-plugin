package com.evi.live.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.client.util.Filepath;

/**
 * The stretched-price basis as the PLUGIN builds it (MarketAggregates.build, streamed newest-first from its own per-hour
 * archive files) against the list port that mirrors stretch.mjs (stretchBasisAt): the same answer at several clocks. The
 * transcript suites hand the engine a ready-made basis and never run build(), so without this the streamed path -- the one
 * every player's warning comes from -- had no check of its own (the gap recorded on 23 Oct 2026, closed 12 Nov). The clocks
 * cover: the newest hour just stored, a few hours on (the window is set by the newest STORED hour, not the clock), a clock
 * that leaves an item under its minimum hours, a clock one hour inside and one hour outside the 336-hour read, and an
 * archive with holes. Run from engine.StretchIntentTest; it lives here because the archive helpers are package-private.
 * Synthetic data only.
 */
public final class StretchBuildCheck {
  private StretchBuildCheck() {}

  private static final long H = 3600;

  /** Returns the number of checks made; throws AssertionError on the first difference. */
  public static int run() throws Exception {
    int checks = 0;
    long end = 2_000_000L * H;
    int span = 400; // longer than the 336-hour window, so its far edge is exercised
    List<HourBucket> list = new ArrayList<>();
    for (int k = span - 1; k >= 0; k--) {
      if (k == 5 || k == 6 || k == 30 || (k >= 200 && k < 212)) continue; // holes: recent ones, one a day back, and half a day mid-window
      long ts = end - k * H;
      StringBuilder data = new StringBuilder();
      // 4151: a level with a 37-hour wobble, so the day-over-day move is above 0 (a reading)
      long wobble = Math.round(100000 * (1 + 0.04 * Math.sin(2 * Math.PI * ((double) ts / H) / 37)));
      data.append("\"4151\":{\"avgHighPrice\":").append(Math.round(wobble * 1.1)).append(",\"highPriceVolume\":40,\"avgLowPrice\":").append(wobble)
        .append(",\"lowPriceVolume\":40},");
      // 561: perfectly flat, so its typical move is 0 (no reading: the warning fails open)
      data.append("\"561\":{\"avgHighPrice\":214,\"highPriceVolume\":900,\"avgLowPrice\":200,\"lowPriceVolume\":900},");
      // 11335: priced on the low side only every eighth hour, and only in the newest 300 hours (under the minimum hours)
      if (k < 300 && k % 8 == 0)
        data.append("\"11335\":{\"avgHighPrice\":2200000,\"highPriceVolume\":1,\"avgLowPrice\":").append(2000000 + k * 1000).append(",\"lowPriceVolume\":1},");
      // 20997: trades only in the OLDEST 160 hours of the span, so it leaves the window as the clock moves on
      if (k >= span - 160)
        data.append("\"20997\":{\"avgHighPrice\":").append(900000 + (k * 7919) % 50000).append(",\"highPriceVolume\":3,\"avgLowPrice\":")
          .append(800000 + (k * 104729) % 60000).append(",\"lowPriceVolume\":3},");
      // 2: a high side but never a low (no low, no basis)
      data.append("\"2\":{\"avgHighPrice\":190,\"highPriceVolume\":5000,\"avgLowPrice\":null,\"lowPriceVolume\":0}");
      list.add(WikiJson.hour("{\"timestamp\":" + ts + ",\"data\":{" + data + "}}"));
    }
    Filepath root = MarketTestSupport.tempRoot("evi-stretch-build");
    try {
      HourlyArchive archive = new HourlyArchive(root, m -> { });
      for (HourBucket b : list) if (!archive.store(b)) throw new AssertionError("the archive refused an hour " + b.ts);
      boolean sawOldItem = false, sawOldItemGone = false;
      for (long after : new long[]{0, 1, 3, 30, 100, 250, 335, 336, 337, 400}) {
        long nowMs = (end + after * H + 600) * 1000;
        Map<Integer, MarketAggregates.StretchBasis> want = MarketAggregates.stretchBasisAt(list, nowMs);
        Map<Integer, MarketAggregates.StretchBasis> got = MarketAggregates.build(archive, nowMs).stretch;
        if (!want.toString().equals(got.toString()))
          throw new AssertionError("build() stretch " + got + " differs from stretchBasisAt " + want + " at newest + " + after + "h");
        checks++;
        if (after <= 3) {
          // the fixture must MEAN something, or the comparison above is two empty maps agreeing
          MarketAggregates.StretchBasis whip = want.get(4151);
          if (whip == null || !(whip.move > 0) || whip.level < 96000 || whip.level > 104000)
            throw new AssertionError("the wobbling item must have a reading near its level: " + want);
          if (want.get(561) != null) throw new AssertionError("a flat item has a move of 0 and no reading: " + want);
          if (want.get(11335) != null) throw new AssertionError("an item under the minimum hours has no reading: " + want);
          if (want.get(2) != null) throw new AssertionError("an item with no low side has no reading: " + want);
          checks++;
        }
        sawOldItem |= want.get(20997) != null;
        sawOldItemGone |= after <= 3 && want.get(20997) == null;
        if (after == 100 && want.get(20997) != null) throw new AssertionError("an item whose hours fell out of the read has no reading: " + want);
        if (after >= 337 && !want.isEmpty()) throw new AssertionError("nothing stored inside the 336-hour read: no reading, got " + want);
      }
      // 20997 traded only in hours 240-399 back: inside the newest hour's 336-hour window for 96 of them (a reading, since
      // the window hangs off the newest STORED hour), so the far edge of the window is really exercised
      if (!sawOldItem || sawOldItemGone) throw new AssertionError("the item at the window's far edge must have a reading at the first clocks");
      checks++;
    } finally {
      MarketTestSupport.deleteTree(root);
    }
    return checks;
  }
}
