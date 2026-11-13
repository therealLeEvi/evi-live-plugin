package com.evi.live.activity;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import net.runelite.client.util.Filepath;

/**
 * EVI's local activity log file (12 Nov 2026, schema 1): what happened and what EVI knew when it happened -- which suggestion
 * was on screen, what the player did with it, each offer event, the market as EVI read it -- so that it can be learned
 * from later. Design: the maintainer's notes of 12 Nov 2026.
 *
 * <p><b>LOCAL ONLY.</b> One file per account per calendar month (UTC), {@code activity/activity-<account>-<YYYY-MM>.jsonl}
 * in the plugin's data folder, one JSON object per line, append-only. Nothing here is ever sent anywhere; this class has
 * no network code and must never get any.
 *
 * <p><b>The engine never reads it</b>, so it cannot change a suggestion, and it is outside parity. The journal is a
 * separate file with a frozen format and is not touched.
 *
 * <p><b>It can only fail quietly.</b> One daemon thread and a bounded queue. {@link #record} returns at once on any thread
 * and never throws; a full queue drops the record, and the next line written carries {@code dropped: n}. Every file
 * operation is caught and logged once per kind. An activity log fault must never cost a suggestion, a journal line or a frame.
 *
 * <p>A closed month is gzipped when the first line of a later month is written. Nothing is deleted. A month's file stops
 * taking lines at {@value #MAX_BYTES} bytes, said once.
 */
public final class ActivityLog {
  public static final int SCHEMA = 1;
  public static final String DIR = "activity";
  static final String THREAD_NAME = "evi-activity";
  static final int MAX_PENDING = 2048;
  static final long MAX_BYTES = 50L * 1024 * 1024;
  private static final Pattern ACCOUNT = Pattern.compile("[a-zA-Z0-9-]{1,80}");
  private static final Pattern PLAIN = Pattern.compile("activity-([a-zA-Z0-9-]{1,80})-(\\d{4}-\\d{2})\\.jsonl");

  private final Filepath dir;
  private final LongSupplier clock;
  private final Consumer<String> log;
  private final ThreadPoolExecutor executor;
  private final AtomicInteger dropped = new AtomicInteger();
  /** Writer thread only. */
  private final Map<String, String> monthOf = new HashMap<>();
  private final Set<String> saidOnce = new HashSet<>();
  private volatile boolean closed;

  /** @param pluginDir the plugin's data folder; nothing is read or written until the first record */
  public ActivityLog(Filepath pluginDir, LongSupplier clock, Consumer<String> log) {
    this.dir = pluginDir.joinSegment(DIR);
    this.clock = clock;
    this.log = log;
    this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(MAX_PENDING), r -> {
      Thread t = new Thread(r, THREAD_NAME);
      t.setDaemon(true); // never holds the client open
      return t;
    });
  }

  /** {@code activity-<account>-<YYYY-MM>.jsonl} for the UTC month of {@code t}. */
  static String fileName(String account, long t) {
    return "activity-" + account + "-" + month(t) + ".jsonl";
  }

  static String month(long t) {
    ZonedDateTime z = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC);
    return String.format(Locale.US, "%04d-%02d", z.getYear(), z.getMonthValue());
  }

  /**
   * Queues one line: {@code {"v":1,"type":type,"t":now,"sid":sid, ...fields}}. Any thread; returns at once; never throws.
   * A record with no usable account is not written (there is no file to put it in before a login).
   *
   * @param fields the record's own fields; this method takes ownership (do not change it afterwards). May be null.
   */
  public void record(String account, String sid, String type, JsonObject fields) {
    try {
      if (closed || type == null || account == null || !ACCOUNT.matcher(account).matches()) return;
      final long t = clock.getAsLong();
      final JsonObject line = new JsonObject();
      line.addProperty("v", SCHEMA);
      line.addProperty("type", type);
      line.addProperty("t", t);
      if (sid != null) line.addProperty("sid", sid);
      if (fields != null) for (Map.Entry<String, com.google.gson.JsonElement> e : fields.entrySet()) {
        if (!line.has(e.getKey())) line.add(e.getKey(), e.getValue()); // the envelope's own names are never overwritten
      }
      try {
        executor.execute(() -> write(account, t, line));
      } catch (RejectedExecutionException e) {
        dropped.incrementAndGet();
      }
    } catch (RuntimeException e) {
      once("record", "EVI activity log: a record could not be queued: " + e);
    }
  }

  private void write(String account, long t, JsonObject line) {
    try {
      dir.createDirectories();
      String name = fileName(account, t);
      String m = month(t);
      if (!m.equals(monthOf.put(account, m))) closeOlderMonths(account, name);
      Filepath f = dir.joinSegment(name);
      if (f.isFile() && f.size() > MAX_BYTES) {
        once("full:" + name, "EVI activity log: " + name + " reached 50 MB; nothing more is written to it this month");
        return;
      }
      int d = dropped.getAndSet(0);
      if (d > 0) line.addProperty("dropped", d);
      try (OutputStream out = f.openOutputStream(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
        out.write((line.toString() + "\n").getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException | RuntimeException e) {
      once("write", "EVI activity log: a line could not be written (EVI itself is unaffected): " + e);
    }
  }

  /** Gzips this account's plain files of any month other than {@code current}; a failure leaves the plain file as it is. */
  private void closeOlderMonths(String account, String current) {
    try {
      if (!dir.isDirectory()) return;
      List<Filepath> older = new ArrayList<>();
      try (java.util.stream.Stream<Filepath> s = dir.walk(1)) {
        s.forEach(f -> {
          Matcher k = f.isFile() ? PLAIN.matcher(f.getFileName()) : null;
          if (k != null && k.matches() && k.group(1).equals(account) && !f.getFileName().equals(current)) older.add(f);
        });
      }
      for (Filepath f : older) {
        Filepath gz = dir.joinSegment(f.getFileName() + ".gz");
        if (gz.exists()) continue; // never overwrite an archive; the plain file stays beside it
        byte[] all;
        try (InputStream in = f.openInputStream()) {
          all = in.readAllBytes();
        }
        try (OutputStream out = gz.openOutputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             GZIPOutputStream z = new GZIPOutputStream(out)) {
          z.write(all);
        }
        f.deleteIfExists();
      }
    } catch (IOException | RuntimeException e) {
      once("gzip", "EVI activity log: an older month could not be compressed (left as it is): " + e);
    }
  }

  private void once(String key, String message) {
    boolean first;
    synchronized (saidOnce) {
      first = saidOnce.add(key);
    }
    if (first) {
      try {
        log.accept(message);
      } catch (RuntimeException ignored) {
        // a log that throws must not become an activity log failure
      }
    }
  }

  /** No more records are taken; what is queued is still written. Never blocks. */
  public void shutdown() {
    closed = true;
    executor.shutdown();
  }

  /** Tests only (it blocks): waits for everything queued so far. Never call on the client thread. */
  public boolean awaitIdle(long timeoutMs) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    CountDownLatch done = new CountDownLatch(1);
    while (true) {
      long left = deadline - System.nanoTime();
      if (left <= 0) return false;
      try {
        executor.execute(done::countDown);
        return done.await(left, TimeUnit.NANOSECONDS);
      } catch (RejectedExecutionException e) {
        if (executor.isShutdown()) return executor.awaitTermination(left, TimeUnit.NANOSECONDS);
        executor.awaitTermination(20, TimeUnit.MILLISECONDS); // the queue is full: give the writer a moment, then ask again
      }
    }
  }

  /** Tests only: runs {@code r} on the writer thread (to hold it while the queue fills). */
  public void onWriter(Runnable r) {
    executor.execute(r);
  }
}
