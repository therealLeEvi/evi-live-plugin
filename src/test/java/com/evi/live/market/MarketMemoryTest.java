package com.evi.live.market;

import static com.evi.live.market.MarketTestSupport.check;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/**
 * MEMORY, measured rather than assumed, under the RuneLite launcher's own {@code -Xmx768m} (the Gradle task sets it).
 * The bridge measured about 167 MB of retained heap for one 336-hour archive read in Node; inside the game client the
 * plugin must keep only the aggregates.
 *
 * <p>A synthetic archive at the REAL archive's size (337 hours of 3,138 items an hour drawn from 4,453 ids -- the
 * figures measured on the bridge's archive, SELF-CONTAINED in-client-data section 3), written by the plugin's own
 * writer, then {@link MarketAggregates#build} over it. Reported: heap RETAINED by the answers, the PEAK heap in use
 * during the build (an upper bound: it counts garbage not yet collected), bytes the build allocated, time, and for
 * comparison the heap a whole-window read held as buckets would retain.
 */
public final class MarketMemoryTest {
  static final int HOURS = 337, PER_HOUR = 3138, IDS = 4453;

  public static void main(String[] args) throws Exception {
    Filepath root = MarketTestSupport.tempRoot("evi-prices-memory");
    try {
      HourlyArchive archive = new HourlyArchive(root, m -> { });
      Random r = new Random(42);
      int[] pool = new int[IDS];
      for (int i = 0; i < IDS; i++) pool[i] = 2 + i * 6 + r.nextInt(5);
      long[] base = new long[IDS];
      for (int i = 0; i < IDS; i++) base[i] = (long) Math.pow(10, 1 + r.nextDouble() * 8);
      long end = 1790852400L;
      for (int h = 0; h < HOURS; h++) {
        TreeMap<Integer, long[]> rows = new TreeMap<>();
        while (rows.size() < PER_HOUR) {
          int k = r.nextInt(IDS);
          long b = base[k];
          rows.put(pool[k], new long[]{b + r.nextInt((int) Math.max(1, b / 50)), r.nextInt(5000), b - r.nextInt((int) Math.max(1, b / 50)), r.nextInt(5000)});
        }
        check(archive.store(HourBucket.of(end - h * 3600L, rows)), "store");
      }
      long disk;
      try (Stream<Path> s = Files.list(MarketTestSupport.path(archive.dir()))) {
        disk = s.mapToLong(p -> p.toFile().length()).sum();
      }
      long nowMs = (end + 3600 + 600) * 1000;
      MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
      MarketAggregates.build(archive, nowMs); // warm up class loading and the JIT, then measure a fresh build
      long before = settledUsed(mem);
      AtomicBoolean sampling = new AtomicBoolean(true);
      AtomicLong peak = new AtomicLong(before);
      Thread sampler = new Thread(() -> {
        while (sampling.get()) peak.accumulateAndGet(mem.getHeapMemoryUsage().getUsed(), Math::max);
      });
      sampler.setDaemon(true);
      sampler.start();
      com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      long allocBefore = threads.getCurrentThreadAllocatedBytes();
      long t0 = System.nanoTime();
      MarketAggregates.Readings kept = MarketAggregates.build(archive, nowMs);
      long ms = (System.nanoTime() - t0) / 1_000_000;
      long allocated = threads.getCurrentThreadAllocatedBytes() - allocBefore;
      sampling.set(false);
      sampler.join();
      long retained = settledUsed(mem) - before;

      // For comparison: the whole window held as buckets (what a bridge-style readArchive keeps alive while it works).
      long beforeList = settledUsed(mem);
      List<HourBucket> list = archive.read(0, Long.MAX_VALUE);
      long listRetained = settledUsed(mem) - beforeList;

      // The windows are measured back from NOW, as server.mjs does (readArchive(now - 336h)): at ten past the hour the newest
      // closed hour is over an hour old, so the 336-hour window holds 335 closed hours and the 168-hour one 167.
      check(kept.robustHours == 335 && kept.typicalHours == 167, "the build read the windows: " + kept.robustHours + "/" + kept.typicalHours);
      check(kept.robust.size() > 4000 && kept.typical.size() > 4000, "a full catalogue's answers: " + kept.robust.size() + " robust, " + kept.typical.size() + " typical");
      check(list.size() == HOURS, "the comparison read every hour");
      // THE ASSERTION: only the answers stay. A build that kept its buckets (or its per-item lists) would retain tens of MB.
      check(retained < 8L * 1024 * 1024, "the aggregates must retain under 8 MB; retained " + mb(retained));
      check(listRetained > 10 * Math.max(retained, 1), "setup: the whole-window list should dwarf the aggregates");
      check(peak.get() - before < 400L * 1024 * 1024, "the build's peak must stay well inside a 768 MB heap: " + mb(peak.get() - before));
      System.out.println("MARKET MEMORY PASS (-Xmx" + mb(Runtime.getRuntime().maxMemory()) + "): " + HOURS + " hours x " + PER_HOUR + " items, "
        + mb(disk) + " on disk (" + disk / HOURS / 1024 + " KB an hour, synthetic: random values compress worse than real ones). build(): "
        + mb(retained) + " retained (" + kept.robust.size() + " robust prices, " + kept.typical.size() + " typical volumes), peak heap in use "
        + mb(peak.get() - before) + " above the baseline (an upper bound, garbage included), " + mb(allocated) + " allocated, " + ms
        + " ms. For comparison, the same window held as buckets retains " + mb(listRetained) + ".");
      check(kept.robust.size() + list.size() > 0, "keep both alive until measured");
    } finally {
      MarketTestSupport.deleteTree(root);
    }
  }

  static long settledUsed(MemoryMXBean mem) throws InterruptedException {
    long used = Long.MAX_VALUE;
    for (int i = 0; i < 6; i++) {
      System.gc();
      Thread.sleep(40);
      used = Math.min(used, mem.getHeapMemoryUsage().getUsed());
    }
    return used;
  }

  static String mb(long bytes) {
    return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
  }
}
