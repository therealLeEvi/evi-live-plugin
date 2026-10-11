package com.evi.live.market;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.runelite.client.util.Filepath;

/**
 * The plugin's own hourly price archive: one gzip file per hour in {@code prices/1h/} inside the plugin's data folder ({@code PluginFolder.PATH}),
 * read and written ONLY through RuneLite's {@link Filepath} (java.io file APIs are on the Hub's forbidden list).
 *
 * <p>A CACHE OF A PUBLIC DATASET, not a recording: every hour can be fetched again from the Wiki for at least 90 days,
 * so a gap (the game was closed) is repaired at the next login and nothing is ever lost for good. Retention is a
 * rolling window ({@link #prune}); the archive does not grow.
 *
 * <p>ONE FILE PER HOUR, created complete or not at all and never replaced ({@link ArchiveFiles#writeNew}), so two
 * clients sharing the folder cannot corrupt it: both fetching the same hour write the same content, the first
 * rename stands, the second finds the file there and changes nothing. Each file holds exactly one gzip member with
 * one line, byte for byte the bridge's archive line ({@link HourBucket#line}).
 *
 * <p>NOT READABLE BY THE PRIVATE TOOLS AS IS: the bridge's {@code readArchive} accepts only
 * {@code 1h-YYYY-MM.jsonl.gz} and silently skips any other name. These files are deliberately named differently
 * ({@code hour-<ts>.json.gz}) so that nothing mistakes one layout for the other; point a tool at them only through
 * a converter.
 *
 * <p>An hour the Wiki answered with nothing usable (no rows, or a different hour) is remembered by an empty marker
 * file ({@code hour-<ts>.none}), so it is not asked for again -- priceArchive.mjs records the asked-for ts in its
 * index for the same reason. Not thread-safe: one thread (the price-data thread) owns an instance.
 */
public final class HourlyArchive {
  static final String DIR = "1h";
  private static final Pattern DATA = Pattern.compile("hour-(\\d{1,12})\\.json\\.gz");
  private static final Pattern NONE = Pattern.compile("hour-(\\d{1,12})\\.none");
  private static final Pattern TMP = Pattern.compile("hour-\\d{1,12}\\.(json\\.gz|none)\\.[0-9a-f-]{36}\\.tmp");
  /** A temporary file this old belongs to no live write (one takes milliseconds); it was left by a crash. */
  static final long STALE_TMP_MS = 60L * 60 * 1000;

  private final Filepath dir;
  private final Consumer<String> log;

  public HourlyArchive(Filepath pricesDir, Consumer<String> log) {
    this.dir = pricesDir.joinSegment(DIR);
    this.log = log;
  }

  Filepath dir() {
    return dir;
  }

  static String dataName(long ts) {
    return "hour-" + ts + ".json.gz";
  }

  static String noneName(long ts) {
    return "hour-" + ts + ".none";
  }

  /** Every hour with a data file, ascending. Re-listed from the folder each time, so another client's writes count. */
  public NavigableSet<Long> storedHours() throws IOException {
    return list(DATA);
  }

  /** Every hour the Wiki answered with nothing usable. */
  NavigableSet<Long> answeredEmpty() throws IOException {
    return list(NONE);
  }

  /** TEST SEAM: told the hour folder just before it is listed; a test throws from it to stand for a folder that cannot be read. */
  interface ListSeam {
    void listing(Filepath dir) throws IOException;
  }

  /** The {@link ListSeam}. Null in production. */
  static volatile ListSeam listSeam;

  private NavigableSet<Long> list(Pattern p) throws IOException {
    TreeSet<Long> out = new TreeSet<>();
    if (!dir.isDirectory()) return out;
    ListSeam seam = listSeam;
    if (seam != null) seam.listing(dir);
    try (Stream<Filepath> s = dir.walk(1)) {
      s.forEach(f -> {
        Matcher m = p.matcher(f.getFileName());
        if (m.matches() && f.isFile()) out.add(Long.parseLong(m.group(1)));
      });
    }
    return out;
  }

  /** Whether this hour needs no fetch: stored, or answered empty (by this client or another). */
  boolean done(long ts) {
    return dir.joinSegment(dataName(ts)).exists() || dir.joinSegment(noneName(ts)).exists();
  }

  /**
   * Stores one hour. False when the file was already there (another client got it first, which is fine: the
   * content is the same public data). The bucket's own ts names the file.
   */
  boolean store(HourBucket b) throws IOException {
    dir.createDirectories();
    return ArchiveFiles.writeNew(dir.joinSegment(dataName(b.ts)), gzip(b.line()));
  }

  /** Remembers that the Wiki answered this hour with nothing usable. */
  void markEmpty(long ts) throws IOException {
    dir.createDirectories();
    ArchiveFiles.writeNew(dir.joinSegment(noneName(ts)), new byte[0]);
  }

  static byte[] gzip(String text) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() / 8 + 64);
    try (GZIPOutputStream z = new GZIPOutputStream(out)) {
      z.write(text.getBytes(StandardCharsets.UTF_8));
    }
    return out.toByteArray();
  }

  /**
   * One stored hour, or null when it is absent or cannot be read. A file whose CONTENT is bad (not gzip, not one
   * bucket, or a bucket of a different hour than its name) is deleted, so the hour reads as missing and is fetched
   * again; a file that cannot be OPENED (another program holding it, say) is left alone and skipped this time.
   */
  public HourBucket read(long ts) {
    Filepath f = dir.joinSegment(dataName(ts));
    InputStream raw;
    try {
      if (!f.exists()) return null;
      raw = f.openInputStream();
    } catch (IOException e) {
      log.accept("EVI prices: could not open " + f.getFileName() + " (" + e.getClass().getSimpleName() + "); skipped this time");
      return null;
    }
    HourBucket b;
    // raw is closed on every path (the gzip header check can throw before the reader exists), so a damaged file
    // is never still open when it is deleted -- on Windows an open file cannot be removed.
    try (InputStream in = raw; Reader r = new InputStreamReader(new GZIPInputStream(in, 65536), StandardCharsets.UTF_8)) {
      b = HourBucket.parseLine(r);
    } catch (IOException | RuntimeException e) {
      b = null;
    }
    if (b != null && b.ts == ts) return b;
    log.accept("EVI prices: " + f.getFileName() + " is damaged; removed so the hour is fetched again");
    try {
      f.deleteIfExists();
    } catch (IOException e) {
      log.accept("EVI prices: could not remove " + f.getFileName() + ": " + e.getMessage());
    }
    return null;
  }

  /** Every readable stored hour in [fromTs, toTs], oldest first. For tests and small windows; the engine uses {@link MarketAggregates#build}. */
  public List<HourBucket> read(long fromTs, long toTs) throws IOException {
    List<HourBucket> out = new ArrayList<>();
    for (long ts : storedHours().subSet(fromTs, true, toTs, true)) {
      HourBucket b = read(ts);
      if (b != null) out.add(b);
    }
    return out;
  }

  /**
   * The rolling window: removes hour files and empty-hour markers older than {@code oldestKept}, and temporary
   * files a crash left behind (older than {@link #STALE_TMP_MS}; a live write's own temporary file is never that
   * old). Returns how many files went. Two clients pruning at once is harmless: a file already gone is skipped.
   */
  int prune(long oldestKept, long nowMs) throws IOException {
    if (!dir.isDirectory()) return 0;
    List<Filepath> doomed = new ArrayList<>();
    try (Stream<Filepath> s = dir.walk(1)) {
      s.forEach(f -> {
        String name = f.getFileName();
        Matcher d = DATA.matcher(name), n = NONE.matcher(name);
        if (d.matches() && Long.parseLong(d.group(1)) < oldestKept) doomed.add(f);
        else if (n.matches() && Long.parseLong(n.group(1)) < oldestKept) doomed.add(f);
        else if (TMP.matcher(name).matches()) {
          try {
            if (nowMs - ArchiveFiles.modifiedMs(f) > STALE_TMP_MS) doomed.add(f);
          } catch (IOException ignored) {
            // gone already
          }
        }
      });
    }
    int removed = 0;
    for (Filepath f : doomed) {
      try {
        f.deleteIfExists();
        removed++;
      } catch (IOException e) {
        log.accept("EVI prices: could not remove " + f.getFileName() + ": " + e.getMessage());
      }
    }
    return removed;
  }
}
