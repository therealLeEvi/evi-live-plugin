package com.evi.live.market;

import com.evi.live.inprocess.EngineFeed;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.client.util.Filepath;

/**
 * {@link PriceDataService#readHours}, the one door the in-process engine has into the price layer's archive (run from
 * InProcessEngineTest): the hours from a time on, oldest first, read on the price-data thread; refused before the archive is
 * loaded and after shutdown; and never a request to the Wiki. Synthetic hours only.
 */
public final class ReadHoursCheck {
  private ReadHoursCheck() {}

  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  static List<Long> tsOf(List<HourBucket> hours) {
    List<Long> out = new ArrayList<>();
    for (HourBucket b : hours) out.add(b.ts);
    return out;
  }

  static boolean refused(CompletableFuture<List<HourBucket>> f, String why) throws Exception {
    try {
      f.get(30, TimeUnit.SECONDS);
      return false;
    } catch (ExecutionException e) {
      return e.getCause() instanceof IllegalStateException && e.getCause().getMessage().contains(why);
    }
  }

  public static int run() throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-readhours");
    try {
      long hour = 1790852400L;
      final int hole = 3; // a gap in the stored hours: backfilled below, after the newest
      AtomicLong clock = new AtomicLong((hour + 3600 + 600) * 1000);
      MarketTestSupport.FakeWiki fake = new MarketTestSupport.FakeWiki(url -> MarketTestSupport.Answer.fail("offline"));
      PriceDataService s = new PriceDataService(root, new WikiPriceClient(fake.injected(), () -> false), clock::get, clock::get, m -> { },
        new PriceDataService.Settings(2500, 60_000, 48, false));
      for (int h = 0; h < 30; h++) { // enough for the typical volumes (24 hours) and the steady prices (6)
        if (h == hole) continue;
        TreeMap<Integer, long[]> rows = new TreeMap<>();
        rows.put(561, new long[]{230 + h, 1000, 220 + h, 900});
        check(s.archive().store(HourBucket.of(hour - h * 3600L, rows)), "setup: store");
      }
      check(refused(s.readHours(0), "not loaded"), "before the archive is loaded, readHours must refuse rather than read");
      s.start();
      s.step(); // loads the archive (and asks for the item list, which the fake refuses)
      int requests = fake.seen.size();
      List<HourBucket> two = s.readHours(hour - 7200).get(30, TimeUnit.SECONDS);
      check(tsOf(two).equals(List.of(hour - 7200, hour - 3600, hour)), "the hours from a time on, oldest first: " + tsOf(two));
      check(two.get(2).indexOf(561) >= 0 && two.get(2).highVolume(two.get(2).indexOf(561)) == 1000, "the stored hour itself");
      check(tsOf(s.readHours(0).get(30, TimeUnit.SECONDS)).size() == 29, "every stored hour from 0");
      check(s.readHours(hour + 1).get(30, TimeUnit.SECONDS).isEmpty(), "nothing from after the newest hour");
      check(fake.seen.size() == requests, "readHours made a request: " + (fake.seen.size() - requests));
      // the engine's adapters over this very service: the snapshot field for field, and the archive through readHours
      long now = clock.get();
      MarketSnapshot snap = s.snapshot();
      check(snap.readingsBuiltAtMs != 0 && !snap.typical.isEmpty() && !snap.robust.isEmpty(), "setup: the readings were built from the stored hours");
      EngineFeed.Market m = EngineFeed.marketOf(() -> s).market(now);
      check(m.typical == snap.typical && m.robust == snap.robust && m.latest == snap.latest && m.catalog == snap.catalog
        && m.latestHour == snap.latestHour(now) && m.latestHour != null && m.newestStoredTs == hour && m.hoursStored == snap.hoursStored
        && m.windowHours == snap.windowHours && m.robustHours == snap.robustHours && m.typicalHours == snap.typicalHours
        && m.latestFetchedAtMs == snap.latestFetchedAtMs && m.archiveRevision == snap.archiveRevision, "Market.of maps the snapshot field for field");
      check(snap.archiveRevision == 29, "the archive revision counts the hours learned at load: " + snap.archiveRevision);
      EngineFeed.Market empty = EngineFeed.Market.of(MarketSnapshot.EMPTY, now);
      check(empty.typical == null && empty.robust.isEmpty() && empty.latest == null && empty.catalog == null && empty.latestHour == null,
        "before the first build the typical volumes are ABSENT (null), as the bridge's cache is before it is warm");
      check(tsOf(EngineFeed.marketOf(() -> s).hoursFrom(hour - 3600).get(30, TimeUnit.SECONDS)).equals(List.of(hour - 3600, hour)), "marketOf reads the archive through readHours");
      check(EngineFeed.marketOf(() -> null).market(now) == null && refused(EngineFeed.marketOf(() -> null).hoursFrom(0), "not running"),
        "no price layer: no market, and the archive refused");
      // THE GAP IS FILLED NEWEST FIRST: its older hour arrives after the newest, so the newest stored hour does not move --
      // the archive revision does, and that is what the engine reads its last 26 hours again on.
      long gap = hour - hole * 3600L;
      fake.answer = url -> url.endsWith("/1h?timestamp=" + gap) ? MarketTestSupport.Answer.ok(MarketTestSupport.hourBody(gap, 3, false))
        : MarketTestSupport.Answer.fail("offline");
      clock.addAndGet(3_000); // past the 2.5 s spacing; the item list is still backing off (10 s)
      check(s.step() == PriceDataService.Work.HOUR, "setup: the next request is the gap");
      check(fake.urls().get(fake.urls().size() - 1).endsWith("/1h?timestamp=" + gap), "setup: the gap was asked for: " + fake.urls());
      MarketSnapshot after = s.snapshot();
      check(after.newestStoredTs == snap.newestStoredTs && after.archiveRevision == snap.archiveRevision + 1,
        "a backfilled OLDER hour: the newest stored hour unchanged (" + after.newestStoredTs + "), the revision moved by one ("
          + snap.archiveRevision + " -> " + after.archiveRevision + ")");
      check(tsOf(s.readHours(gap).get(30, TimeUnit.SECONDS)).get(0) == gap, "the backfilled hour is in the archive");
      EngineFeed.Market m2 = EngineFeed.marketOf(() -> s).market(clock.get());
      check(m2.archiveRevision == after.archiveRevision && m2.newestStoredTs == m.newestStoredTs, "the engine sees the revision move and the newest stay");
      clock.addAndGet(3_000);
      s.step(); // the next hour is refused: nothing learned, the revision stays
      check(s.snapshot().archiveRevision == after.archiveRevision, "a refused request learns nothing: " + s.snapshot().archiveRevision);
      // AN HOUR ANOTHER CLIENT WROTE (a second archive on the same folder) is learned when the backfill walk reaches it
      // (nextMissingHour), and it moves the revision too -- or the engine would not read its last 26 hours again (S2, 8 Oct).
      long theirs = hour - 30 * 3600L; // inside the 48-hour window, older than every stored hour, never fetched here
      check(!s.archive().storedHours().contains(theirs), "setup: this client has not stored that hour");
      HourlyArchive other = new HourlyArchive(root.joinSegment(PriceDataService.DIR), msg -> { });
      TreeMap<Integer, long[]> rows = new TreeMap<>();
      rows.put(561, new long[]{200, 1000, 190, 900});
      check(other.store(HourBucket.of(theirs, rows)), "setup: the other client stores the hour");
      int asked = fake.seen.size();
      clock.addAndGet(3_000);
      s.step();
      MarketSnapshot learned = s.snapshot();
      check(learned.archiveRevision == after.archiveRevision + 1 && learned.newestStoredTs == after.newestStoredTs,
        "an hour another client wrote moves the revision by one (" + after.archiveRevision + " -> " + learned.archiveRevision + "), the newest stays");
      check(fake.urls().subList(asked, fake.urls().size()).stream().noneMatch(u -> u.endsWith("/1h?timestamp=" + theirs)), "the other client's hour is never fetched");
      s.shutdown();
      CompletableFuture<List<HourBucket>> late = s.readHours(0);
      check(late.isDone() && refused(late, "shut down"), "after shutdown readHours must refuse at once");
      check(s.awaitTermination(30_000), "the service did not stop");
      return checks;
    } finally {
      root.deleteRecursively();
    }
  }
}
