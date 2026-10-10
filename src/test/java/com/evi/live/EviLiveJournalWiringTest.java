package com.evi.live;

import com.evi.live.journal.JournalCodec;
import com.evi.live.journal.PluginJournal;
import com.evi.live.journal.Store;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.runelite.client.util.Filepath;

/**
 * The plugin's wiring of its own journal (self-contained stage 2), driven through the REAL plugin methods:
 * enqueue() on a thread named like RuneLite's client thread, startJournal() and shutDown(). Each check asserts what reached
 * the journal or the disk, not what the code says it does.
 *
 * <p>Synthetic data only. Files go to a temporary folder, deleted after.
 * (Filepath.Unchecked.getRooted is used HERE ONLY, to aim a Filepath at that folder.)
 */
public final class EviLiveJournalWiringTest {
  static final String ACCOUNT = "c3".repeat(32);
  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  static void set(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  static Object get(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  static String read(Filepath f) throws IOException {
    try (InputStream in = f.openInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static List<String> lines(Filepath f) throws IOException {
    List<String> out = new ArrayList<>();
    if (!f.exists()) return out;
    for (String l : read(f).split("\n")) if (!l.isEmpty()) out.add(l);
    return out;
  }

  static EviLiveConfig config() {
    return new EviLiveConfig() {
      public MaxTradeShare maxTradeShare() {
        return MaxTradeShare.OFF;
      }
    };
  }

  /** The packet the plugin would build now (its slots as they stand), through the same class enqueue() serialises. */
  static EviLivePlugin.Packet packetOf(EviLivePlugin p) throws Exception {
    EviLivePlugin.Packet pk = new EviLivePlugin.Packet();
    pk.offers.addAll(java.util.Arrays.asList((EviLivePlugin.Offer[]) get(p, "slots")));
    return pk;
  }

  public static void main(String[] args) throws Exception {
    // 1. The import is OFF for every player who has not chosen otherwise.
    EviLiveConfig defaults = new EviLiveConfig() { };
    check(!defaults.importBridgeHistory(), "the old-history import must default to OFF");

    List<String> threadNames = Collections.synchronizedList(new ArrayList<>());
    Field seam = PluginJournal.class.getDeclaredField("ioThreadSeam");
    seam.setAccessible(true);
    seam.set(null, (Consumer<String>) threadNames::add);
    Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-wiring-test"));
    try {
      // A bridge journal for this very account sits in the import folder: if the import ran without the
      // setting, its line would appear in the plugin's journal.
      List<String> bridgeLines = new ArrayList<>();
      Store bridge = new Store(r -> bridgeLines.add(JournalCodec.encodeLine(r)));
      JsonObject other = new JsonObject();
      other.addProperty("version", 1);
      other.addProperty("session", "bridge-only");
      other.addProperty("account", ACCOUNT);
      other.addProperty("seq", 1);
      other.addProperty("ts", 1789992000000L);
      other.addProperty("loggedIn", false);
      other.add("offers", new JsonArray());
      bridge.ingest(other, 1789992000100L);
      check(bridgeLines.size() == 1, "setup: the bridge line was not produced");
      Filepath imp = root.joinSegment(PluginJournal.IMPORT_DIR);
      imp.createDirectories();
      imp.joinSegment(PluginJournal.IMPORT_FILE).write((bridgeLines.get(0) + "\n").getBytes(StandardCharsets.UTF_8));

      // 2. startJournal() with the player's default settings, then a packet from the "client thread".
      EviLivePlugin p = new EviLivePlugin();
      set(p, "gson", new Gson());
      set(p, "config", config());
      Method start = EviLivePlugin.class.getDeclaredMethod("startJournal", Filepath.class);
      start.setAccessible(true);
      start.invoke(p, root);
      PluginJournal j = (PluginJournal) get(p, "journal");
      check(j != null, "startJournal() left no journal");
      set(p, "account", ACCOUNT);
      set(p, "session", "wiring-1");
      EviLivePlugin.Offer[] slots = (EviLivePlugin.Offer[]) get(p, "slots");
      for (int i = 0; i < 8; i++) {
        EviLivePlugin.Offer o = new EviLivePlugin.Offer();
        o.slot = i;
        o.state = "EMPTY";
        o.offerId = UUID.randomUUID().toString();
        slots[i] = o;
      }
      slots[2].state = "BUYING";
      slots[2].itemId = 4151;
      slots[2].name = "Abyssal whip";
      slots[2].price = 1_500_000;
      slots[2].total = 3;
      slots[2].knownStart = true;
      Method enqueue = EviLivePlugin.class.getDeclaredMethod("enqueue", boolean.class);
      enqueue.setAccessible(true);
      Throwable[] failed = {null};
      Thread client = new Thread(() -> {
        try {
          enqueue.invoke(p, true);
        } catch (Throwable t) {
          failed[0] = t;
        }
      }, "Client");
      client.start();
      client.join();
      check(failed[0] == null, "enqueue on the client thread threw: " + failed[0]);
      check(j.awaitIdle(30000), "journal did not drain");
      Filepath file = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment("events-" + ACCOUNT + ".jsonl");
      List<String> written = lines(file);
      check(written.size() == 1, "expected exactly the plugin's own packet in its journal (import OFF), found " + written.size() + " lines");
      JsonObject rec = new JsonParser().parse(written.get(0)).getAsJsonObject();
      check("packet".equals(rec.get("type").getAsString()), "the journal line is not a packet record");
      JsonObject packet = rec.getAsJsonObject("packet");
      JsonElement built = new JsonParser().parse(new Gson().toJson(packetOf(p)));
      check(packet.get("session").getAsString().equals("wiring-1") && packet.get("account").getAsString().equals(ACCOUNT) && packet.get("loggedIn").getAsBoolean()
        && packet.getAsJsonArray("offers").size() == 8 && packet.getAsJsonArray("offers").get(2).getAsJsonObject().get("itemId").getAsInt() == 4151,
        "the journal's packet is not the one the plugin built from its slots: " + packet);
      check(packet.getAsJsonArray("offers").equals(built.getAsJsonObject().getAsJsonArray("offers")), "the journal holds the slots exactly as the plugin serialised them");
      check(!root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment("import-done-" + ACCOUNT + ".json").exists(), "an import ran with the setting OFF");
      check(!root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment("imported-" + ACCOUNT + ".jsonl").exists(), "an imported file was written with the setting OFF");

      // 3. (4.0.0) There is no comparison with a companion app any more: nothing writes a comparison log.
      set(p, "running", true);
      set(p, "lifecycle", 1L);
      set(p, "suggestionCache", new SuggestionCache());
      set(p, "openItemPriceCache", new OpenItemPriceCache());
      set(p, "panel", new EviLivePanel(() -> { }, () -> { }, () -> { }, () -> { }, () -> { }));
      check(!root.joinSegment("journal-compare.jsonl").exists(), "no comparison log is ever written");
      javax.swing.SwingUtilities.invokeAndWait(() -> { }); // let the panel's EDT writes settle

      // 4. Every file operation ran on the journal's thread, while enqueue ran on "Client".
      check(threadNames.size() >= 5, "the seam saw only " + threadNames.size() + " file operations");
      for (String n : new ArrayList<>(threadNames)) check(PluginJournal.THREAD_NAME.equals(n), "journal file work ran on '" + n + "'");

      // 5. shutDown() stops the journal's executor -- first, so a later teardown failure cannot skip it -- and
      // stops the sender in order: never shutdownNow() (it interrupts, and Thread.interrupt is on the Hub's
      // forbidden list), and without waiting on the calling (client) thread for a request in flight.
      ScheduledExecutorService fakeSender = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread th = new Thread(r, "evi-local-sender");
        th.setDaemon(true);
        return th;
      });
      CountDownLatch inFlight = new CountDownLatch(1), release = new CountDownLatch(1);
      boolean[] interrupted = {false}, finished = {false};
      fakeSender.execute(() -> {
        inFlight.countDown();
        try {
          if (!release.await(30, TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) {
          interrupted[0] = true;
        }
        if (Thread.currentThread().isInterrupted()) interrupted[0] = true;
        finished[0] = true;
      });
      check(inFlight.await(10, TimeUnit.SECONDS), "setup: the in-flight request did not start");
      set(p, "sender", fakeSender);
      Method shutDown = EviLivePlugin.class.getDeclaredMethod("shutDown");
      shutDown.setAccessible(true);
      long began = System.nanoTime();
      try {
        shutDown.invoke(p);
      } catch (InvocationTargetException expected) {
        // this harness injects no keybind handler or overlay manager, so later teardown steps throw
      }
      long tookMs = (System.nanoTime() - began) / 1_000_000;
      check(fakeSender.isShutdown(), "shutDown() did not stop the sender");
      check(tookMs < EviLivePlugin.SENDER_STOP_WAIT_MS / 2, "shutDown() waited " + tookMs + " ms for the sender on the calling thread");
      check(!finished[0] && !interrupted[0], "the request in flight was cut short by shutDown()");
      release.countDown();
      check(fakeSender.awaitTermination(10, TimeUnit.SECONDS), "the sender did not finish once its request did");
      check(finished[0] && !interrupted[0], "shutDown() interrupted the sender's thread");
      check(j.isShutdown(), "shutDown() did not shut the journal's executor");
      check(get(p, "journal") == null, "shutDown() left the journal reachable");
      check(get(p, "retiredJournal") == j, "shutDown() did not keep the stopped journal for the next start to hand over to");
      check(j.awaitIdle(30000), "the journal's thread did not finish after shutDown()");
      Thread after = new Thread(() -> {
        try {
          enqueue.invoke(p, true);
        } catch (Throwable t) {
          failed[0] = t;
        }
      }, "Client");
      after.start();
      after.join();
      check(lines(file).size() == 1, "a packet reached the journal after shutDown()");

      // 6. The off-thread wait itself: it reports a sender still busy after its bound, and is silent otherwise.
      List<String> warned = Collections.synchronizedList(new ArrayList<>());
      ExecutorService busy = Executors.newSingleThreadExecutor();
      CountDownLatch busyRelease = new CountDownLatch(1);
      busy.execute(() -> {
        try {
          busyRelease.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          warned.add("INTERRUPTED");
        }
      });
      Thread waiter = EviLivePlugin.stopOffThread(busy, 100, warned::add);
      check(!"evi-sender-stop".equals(Thread.currentThread().getName()) && "evi-sender-stop".equals(waiter.getName()) && waiter.isDaemon(),
        "the wait must run on its own daemon thread");
      waiter.join(10_000);
      check(warned.size() == 1 && warned.get(0).contains("still busy"), "a sender still busy past the bound must be reported once: " + warned);
      busyRelease.countDown();
      check(busy.awaitTermination(10, TimeUnit.SECONDS) && warned.size() == 1, "the busy task was interrupted or did not finish: " + warned);
      ExecutorService idle = Executors.newSingleThreadExecutor();
      Thread quiet = EviLivePlugin.stopOffThread(idle, 10_000, warned::add);
      quiet.join(10_000);
      check(!quiet.isAlive() && warned.size() == 1, "an idle sender's stop must say nothing: " + warned);

      // 7. Off and on again while the old journal still has a packet queued: the new journal is handed the
      // old one and waits for it on its own thread, so the old packet lands first and nothing is lost.
      Method stop = EviLivePlugin.class.getDeclaredMethod("stopJournal");
      stop.setAccessible(true);
      start.invoke(p, root);
      PluginJournal j3 = (PluginJournal) get(p, "journal");
      check(j3 != null && j3 != j && get(p, "retiredJournal") == null, "startJournal() did not take over the retired journal");
      check(j3.awaitIdle(30000), "drain");
      int before = lines(file).size();
      set(p, "account", ACCOUNT);
      set(p, "session", "wiring-old");
      set(p, "seq", 0L);
      slots = (EviLivePlugin.Offer[]) get(p, "slots");
      for (int i = 0; i < 8; i++) {
        EviLivePlugin.Offer o = new EviLivePlugin.Offer();
        o.slot = i;
        o.state = "EMPTY";
        o.offerId = UUID.randomUUID().toString();
        slots[i] = o;
      }
      failed[0] = null;
      CountDownLatch gate = new CountDownLatch(1), held = new CountDownLatch(1);
      AtomicBoolean armed = new AtomicBoolean(true);
      seam.set(null, (Consumer<String>) name -> {
        threadNames.add(name);
        if (!armed.compareAndSet(true, false)) return;
        held.countDown();
        try {
          gate.await(30, TimeUnit.SECONDS); // holds the OLD journal's thread at its next file operation
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
      });
      Thread oldPacket = new Thread(() -> {
        try {
          enqueue.invoke(p, true);
        } catch (Throwable t) {
          failed[0] = t;
        }
      }, "Client");
      oldPacket.start();
      oldPacket.join();
      check(held.await(10, TimeUnit.SECONDS), "setup: the old journal never reached its file work");
      stop.invoke(p);           // switched off with that packet still queued...
      start.invoke(p, root);    // ...and on again
      PluginJournal j4 = (PluginJournal) get(p, "journal");
      set(p, "session", "wiring-new");
      set(p, "seq", 0L);
      Thread newPacket = new Thread(() -> {
        try {
          enqueue.invoke(p, true);
        } catch (Throwable t) {
          failed[0] = t;
        }
      }, "Client");
      newPacket.start();
      newPacket.join();
      // While the old journal still holds its packet, the new one must not get ahead of it: given two
      // seconds, it is still waiting (without the handover it would have written the new packet by now).
      boolean ranAhead;
      try {
        ranAhead = j4.awaitIdle(2000);
      } catch (java.util.concurrent.TimeoutException waiting) {
        ranAhead = false;
      }
      check(!ranAhead, "the new journal ran ahead of the old one's queued packet");
      gate.countDown();
      check(failed[0] == null, "enqueue threw: " + failed[0]);
      check(j4 != null && j4 != j3 && j4.awaitIdle(30000) && j3.awaitIdle(1000), "the journals did not drain");
      List<String> after7 = lines(file);
      check(after7.size() == before + 2, "an off/on lost a packet: " + (after7.size() - before) + " new lines, expected 2");
      String first = new JsonParser().parse(after7.get(before)).getAsJsonObject().getAsJsonObject("packet").get("session").getAsString();
      String second = new JsonParser().parse(after7.get(before + 1)).getAsJsonObject().getAsJsonObject("packet").get("session").getAsString();
      check("wiring-old".equals(first) && "wiring-new".equals(second), "after an off/on the packets landed out of order: " + first + ", then " + second);
      shutDown.setAccessible(true);
      try {
        shutDown.invoke(p);
      } catch (InvocationTargetException expected) {
        // as above
      }
      check(j4.awaitIdle(30000), "drain");
    } finally {
      seam.set(null, null);
      root.deleteRecursively();
    }
    System.out.println("PASS: journal wiring (" + checks + " checks)");
  }
}
