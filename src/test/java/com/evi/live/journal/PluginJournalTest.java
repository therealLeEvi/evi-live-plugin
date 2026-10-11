package com.evi.live.journal;

import com.evi.live.TestFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.runelite.client.util.Filepath;

/**
 * The plugin's own journal (PluginJournal / JournalFile / BridgeImport): persistence, restart, two clients,
 * the opt-in import, the developer comparison, and the thread rule. Every check asserts a VALUE -- a line
 * byte for byte, a state field for field, a count -- and each section was proven by breaking the code it
 * covers and watching it fail (see the sabotage list in the stage report).
 *
 * <p>Uses only SYNTHETIC data: the public golden transcripts and packets written below. Temporary folders
 * are created under the system temp directory and deleted afterwards. (They come from
 * TestFiles.tempDir, and every file is read and written through Filepath; the plugin itself only ever uses getPluginDirectory().)
 */
public final class PluginJournalTest {
  static final String A = "a1".repeat(32);
  static final String B = "b2".repeat(32);
  static final long T0 = 1789992000000L;
  static final List<String> threadNames = Collections.synchronizedList(new ArrayList<>());
  static final List<String> logs = Collections.synchronizedList(new ArrayList<>());
  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    PluginJournal.ioThreadSeam = threadNames::add;
    try {
      roundTripOverPublicTranscripts();
      interruptedTailAndRestart();
      twoClientsInParallel();
      engineViewSeesOtherWriter();
      accountHandover();
      importOffDoesNothing();
      importOnOncePerAccount();
      importWhileAnotherClientFollows();
      externalRewritesRebuild();
      importWriteFailureKeepsThePacket();
      importFileArrivesLater();
      importFileStateFromLooks();
      importFileIndeterminateIsNotMissing();
      importTooLargeRefused();
      importWhileEventsFileHeldOpen();
      importErrorKeepsThePacket();
      replayOrderImportedFirst();
      importedFileChangedRebuilds();
      importedFileShrunkOrReplacedRebuilds();
      fingerprintSeesEveryKindOfChange();
      importThatDoesNotReplayRefused();
      markerAloneCountsAsImported();
      pluginMarkSurvivesIdenticalImportedLine();
      importTakesItemMarksForEveryAccount();
      importFlipsByCharacterName();
      importFlipsUnderFormerNames();
      importRecognisedByOldSalt();
      importSaltSameAsOwnIsUnchanged();
      importSaltUnreadableOrWaiting();
      writeNewNeverOverwrites();
      importRaceIsAnotherWritersSuccess();
      concurrentWriteRetry();
      toggleHandsOver();
    } finally {
      PluginJournal.ioThreadSeam = null;
    }
    // Every file operation above ran on the journal's own thread, and nowhere else.
    List<String> names = new ArrayList<>(threadNames);
    check(names.size() > 100, "the thread seam saw only " + names.size() + " file operations");
    for (String n : names) check(PluginJournal.THREAD_NAME.equals(n), "a journal file operation ran on thread '" + n + "'");
    wrongThreadRefused();
    System.out.println("PASS: plugin journal (" + checks + " checks; " + names.size() + " file operations, all on " + PluginJournal.THREAD_NAME + ")");
  }

  // ------------------------------------------------------------------------------------------- helpers

  static Filepath tempRoot() throws Exception {
    return TestFiles.tempDir("evi-journal-test");
  }

  static PluginJournal journal(Filepath root, AtomicLong clock, BooleanSupplier importEnabled) {
    PluginJournal j = new PluginJournal(root, clock::get, ParityJson::parse, importEnabled, logs::add);
    j.start();
    return j;
  }

  static void stop(PluginJournal j) throws Exception {
    j.shutdown();
    check(j.awaitIdle(30000), "the journal thread did not finish its queued work");
  }

  static List<String> fileLines(Filepath root, String account) throws Exception {
    Filepath f = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(JournalFile.fileName(account));
    return f.exists() ? JournalFile.lines(JournalFile.readComplete(f, 0)) : new ArrayList<>();
  }

  static List<String> importedLines(Filepath root, String account) throws Exception {
    Filepath f = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(JournalFile.importedFileName(account));
    return f.exists() ? JournalFile.lines(JournalFile.readComplete(f, 0)) : new ArrayList<>();
  }

  /** Every offer a store holds, with its times: what a replay from the wrong offset gets wrong first. */
  static List<String> offers(Store s) {
    List<String> out = new ArrayList<>();
    for (Offer o : s.offers()) {
      out.add(o.offerId + "|" + o.account + "|" + o.session + "|" + o.state + "|" + o.filled + "|" + o.spent + "|" + o.firstSeen + "|"
        + o.updated + "|" + o.completedAt);
    }
    out.sort(null);
    return out;
  }

  /**
   * What is on disk, replayed the way the plugin defines it: per account in ascending order, the imported
   * file first, then the events file minus what the import already holds.
   */
  static Store diskReplay(Filepath root) throws Exception {
    Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
    java.util.Set<String> accounts = new java.util.TreeSet<>(JournalFile.accounts(dir));
    accounts.addAll(JournalFile.importedAccounts(dir));
    List<JournalRecord> all = new ArrayList<>();
    for (String a : accounts) all.addAll(BridgeImport.merge(decode(importedLines(root, a)), decode(fileLines(root, a))));
    return Store.replay(all, r -> {
      throw new IllegalStateException("a reference replay must not write");
    });
  }

  static int logsContaining(int from, String text) {
    int n = 0;
    synchronized (logs) {
      for (int i = from; i < logs.size(); i++) if (logs.get(i).contains(text)) n++;
    }
    return n;
  }

  static List<JournalRecord> decode(List<String> lines) {
    List<JournalRecord> out = new ArrayList<>();
    for (String l : lines) out.add(JournalCodec.decode(ParityJson.parse(l)));
    return out;
  }

  static Store replay(List<String> lines) {
    return Store.replay(decode(lines), r -> {
      throw new IllegalStateException("a reference replay must not write");
    });
  }

  /** Arrays sorted, object keys sorted: equal when two states hold the same things in any order. */
  static JsonElement canon(JsonElement e) {
    if (e == null || e.isJsonNull() || e.isJsonPrimitive()) return e;
    if (e.isJsonArray()) {
      List<JsonElement> items = new ArrayList<>();
      for (JsonElement x : e.getAsJsonArray()) items.add(canon(x));
      items.sort((x, y) -> String.valueOf(x).compareTo(String.valueOf(y)));
      JsonArray out = new JsonArray();
      for (JsonElement x : items) out.add(x);
      return out;
    }
    TreeMap<String, JsonElement> sorted = new TreeMap<>();
    for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) sorted.put(en.getKey(), canon(en.getValue()));
    JsonObject out = new JsonObject();
    for (Map.Entry<String, JsonElement> en : sorted.entrySet()) out.add(en.getKey(), en.getValue());
    return out;
  }

  /** The trading content of a state: everything but the live-connection view, which differs by design. */
  static JsonElement trading(StoreState s) {
    JsonObject o = ParityJson.state(s);
    for (String k : new String[]{"sessions", "active", "occupied", "serverTime"}) o.remove(k);
    return canon(o);
  }

  static String account(String line) {
    JournalRecord r = JournalCodec.decode(ParityJson.parse(line));
    return ((JournalRecord.PacketRecord) r).packet.account;
  }

  // ---- a synthetic client: eight slots, packets exactly as EviLivePlugin.enqueue writes them

  static JsonObject offer(int slot, String id, String state, int itemId, String name, long price, long total, long filled, long spent,
                          boolean knownStart, int ticks) {
    JsonObject o = new JsonObject();
    o.addProperty("slot", slot);
    o.addProperty("itemId", itemId);
    o.addProperty("total", total);
    o.addProperty("filled", filled);
    o.addProperty("price", price);
    o.addProperty("spent", spent);
    o.addProperty("offerId", id);
    o.addProperty("state", state);
    o.addProperty("name", name);
    o.addProperty("knownStart", knownStart);
    o.addProperty("ticksToFill", ticks);
    return o;
  }

  static final class Client {
    final String session, account;
    final JsonObject[] slots = new JsonObject[8];
    long seq;
    final List<long[]> times = new ArrayList<>(); // {ts, now} per packet
    final List<String> packets = new ArrayList<>();

    Client(String session, String account) {
      this.session = session;
      this.account = account;
      for (int i = 0; i < 8; i++) empty(i, session + "-e" + i);
    }

    Client empty(int slot, String id) {
      slots[slot] = offer(slot, id, "EMPTY", 0, "", 0, 0, 0, 0, false, -1);
      return this;
    }

    Client set(JsonObject o) {
      slots[o.get("slot").getAsInt()] = o;
      return this;
    }

    /** n packets of a buy in slot 7 filling one unit at a time: each one changes something, so each is journalled. */
    Client sendAll(long ts, int n) {
      for (int i = 0; i < n; i++) {
        set(offer(7, session + "-b7", "BUYING", 1515, "Yew logs", 300, 100, i, 300L * i, true, i == 0 ? -1 : 3));
        send(ts + i * 1000L, true);
      }
      return this;
    }

    void send(long ts, boolean loggedIn) {
      JsonObject p = new JsonObject();
      p.addProperty("version", 1);
      p.addProperty("session", session);
      p.addProperty("account", account);
      p.addProperty("seq", ++seq);
      p.addProperty("ts", ts);
      p.addProperty("loggedIn", loggedIn);
      JsonArray offers = new JsonArray();
      if (loggedIn) for (JsonObject o : slots) offers.add(o.deepCopy());
      p.add("offers", offers);
      packets.add(p.toString());
      times.add(new long[]{ts, ts + 200});
    }
  }

  /** Account A, first session: a whip flip (all 10 bought and sold), then a nature-rune buy left part-filled, then logout. */
  static Client streamA() {
    Client c = new Client("sa1", A);
    c.send(T0, true);
    c.set(offer(0, "sa1-b1", "BUYING", 4151, "Abyssal whip", 1_500_000, 10, 0, 0, true, -1)).send(T0 + 60_000, true);
    c.set(offer(0, "sa1-b1", "BOUGHT", 4151, "Abyssal whip", 1_500_000, 10, 10, 15_000_000, true, 40)).send(T0 + 120_000, true);
    c.empty(0, "sa1-e0b").set(offer(1, "sa1-s1", "SELLING", 4151, "Abyssal whip", 1_600_000, 10, 0, 0, true, -1)).send(T0 + 180_000, true);
    c.set(offer(1, "sa1-s1", "SOLD", 4151, "Abyssal whip", 1_600_000, 10, 10, 16_000_000, true, 30)).send(T0 + 240_000, true);
    c.set(offer(2, "sa1-b2", "BUYING", 561, "Nature rune", 100, 1000, 300, 30_000, true, 5)).send(T0 + 300_000, true);
    c.send(T0 + 310_000, false);
    return c;
  }

  /** Account A again after a relog: the plugin gives every offer a NEW id, so both carry-overs must be linked. */
  static Client streamA2() {
    Client c = new Client("sa2", A);
    c.set(offer(1, "sa2-s1", "SOLD", 4151, "Abyssal whip", 1_600_000, 10, 10, 16_000_000, false, -1));
    c.set(offer(2, "sa2-b2", "BUYING", 561, "Nature rune", 100, 1000, 500, 50_000, false, -1)).send(T0 + 600_000, true);
    c.set(offer(2, "sa2-b2", "BOUGHT", 561, "Nature rune", 100, 1000, 1000, 100_000, false, -1)).send(T0 + 660_000, true);
    return c;
  }

  /** Account B: two chestplates bought and sold, then dragon bones bought and held. */
  static Client streamB() {
    Client c = new Client("sb1", B);
    c.send(T0 + 5_000, true);
    c.set(offer(0, "sb1-b1", "BUYING", 11832, "Bandos chestplate", 20_000_000, 2, 0, 0, true, -1)).send(T0 + 65_000, true);
    c.set(offer(0, "sb1-b1", "BOUGHT", 11832, "Bandos chestplate", 20_000_000, 2, 2, 40_000_000, true, 100)).send(T0 + 125_000, true);
    c.empty(0, "sb1-e0b").set(offer(3, "sb1-s1", "SELLING", 11832, "Bandos chestplate", 21_000_000, 2, 0, 0, true, -1)).send(T0 + 185_000, true);
    c.set(offer(3, "sb1-s1", "SOLD", 11832, "Bandos chestplate", 21_000_000, 2, 2, 42_000_000, true, 20)).send(T0 + 245_000, true);
    c.set(offer(4, "sb1-b4", "BUYING", 536, "Dragon bones", 3000, 100, 0, 0, true, -1)).send(T0 + 305_000, true);
    c.set(offer(4, "sb1-b4", "BOUGHT", 536, "Dragon bones", 3000, 100, 100, 300_000, true, 10)).send(T0 + 365_000, true);
    return c;
  }

  /** Feeds a client's packets to a journal and to a reference store, with the same clock. */
  static void feed(Client c, int from, int to, PluginJournal j, AtomicLong clock, Store ref) {
    for (int i = from; i < to; i++) {
      long now = c.times.get(i)[1];
      clock.set(now);
      if (j != null) j.offerPacket(c.packets.get(i));
      if (ref != null) ref.ingest(ParityJson.parse(c.packets.get(i)), now);
    }
  }

  // --------------------------------------------------------------------------------------------- tests

  /**
   * Every public golden transcript's packets, through the file-backed journal and through an in-memory
   * Store: the same lines byte for byte, each in its own account's file; the same live state; and after a
   * restart, the same state again.
   */
  static void roundTripOverPublicTranscripts() throws Exception {
    String index = ParityJson.readText("/parity/transcripts/index.txt");
    check(index != null, "parity/transcripts/index.txt missing");
    int transcripts = 0, packets = 0, lines = 0, multiAccount = 0;
    for (String name : index.split("\n")) {
      if (name.isBlank()) continue;
      JsonObject t = ParityJson.read("/parity/transcripts/" + name.trim() + ".json.gz").getAsJsonObject();
      Filepath root = tempRoot();
      AtomicLong clock = new AtomicLong();
      PluginJournal j = journal(root, clock, () -> false);
      List<String> refLines = new ArrayList<>();
      Store ref = new Store(r -> refLines.add(JournalCodec.encodeLine(r)));
      long last = 0;
      for (JsonElement s : t.getAsJsonArray("steps")) {
        JsonObject step = s.getAsJsonObject();
        if (!step.has("request")) continue;
        JsonObject req = step.getAsJsonObject("request");
        if (!"POST".equals(req.get("method").getAsString()) || !"/api/events".equals(req.get("path").getAsString())) continue;
        if (!req.has("auth") || !"plugin".equals(req.get("auth").getAsString())) continue;
        long at = step.get("at").getAsLong();
        int repeat = step.has("repeat") ? step.get("repeat").getAsInt() : 1;
        for (int k = 0; k < repeat; k++) {
          clock.set(at);
          j.offerPacket(req.get("body").toString());
          try {
            ref.ingest(req.get("body"), at);
          } catch (JournalException ignored) {
            // refused by both
          }
          packets++;
        }
        last = Math.max(last, at);
      }
      check(j.awaitIdle(30000), name + ": journal did not drain");
      final long when = last;
      // The same live state as the in-memory store, field for field and in the same order.
      JsonObject live = j.call(st -> ParityJson.state(st.state(when)));
      check(live.equals(ParityJson.state(ref.state(when))), name + ": the file-backed journal's live state differs from the in-memory store's");
      // Each account's file holds exactly that account's lines, in order, byte for byte.
      Map<String, List<String>> byAccount = new TreeMap<>();
      for (String l : refLines) byAccount.computeIfAbsent(account(l), k -> new ArrayList<>()).add(l);
      List<String> files = new ArrayList<>();
      for (String a : byAccount.keySet()) files.add(a);
      Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
      check(JournalFile.accounts(dir).equals(files), name + ": journal files " + JournalFile.accounts(dir) + ", expected " + files);
      for (Map.Entry<String, List<String>> e : byAccount.entrySet()) {
        check(fileLines(root, e.getKey()).equals(e.getValue()), name + ": " + JournalFile.fileName(e.getKey()) + " differs from the store's own lines");
        lines += e.getValue().size();
      }
      if (byAccount.size() > 1) multiAccount++;
      stop(j);
      // Restart: a new journal over the same folder replays to the same state.
      PluginJournal k = journal(root, clock, () -> false);
      JsonObject reloaded = k.call(st -> ParityJson.state(st.state(when)));
      check(canon(reloaded).equals(canon(ParityJson.state(replay(refLines).state(when)))), name + ": the reloaded journal differs from a replay of the same lines");
      check(k.failure() == null, name + ": reload failed");
      stop(k);
      root.deleteRecursively();
      transcripts++;
    }
    check(transcripts >= 30 && lines > 50 && multiAccount >= 1, "too little coverage: " + transcripts + " transcripts, " + lines + " lines, " + multiAccount + " multi-account");
    System.out.println("  round trip: " + transcripts + " public transcripts, " + packets + " packets, " + lines + " journal lines byte-identical; reload equal");
  }

  /** A crash mid-line: the reload ignores the cut line; the next write saves it aside and cuts it, like store.mjs. */
  static void interruptedTailAndRestart() throws Exception {
    Filepath root = tempRoot();
    AtomicLong clock = new AtomicLong();
    Client a = streamA();
    List<String> refLines = new ArrayList<>();
    Store ref = new Store(r -> refLines.add(JournalCodec.encodeLine(r)));
    PluginJournal j = journal(root, clock, () -> false);
    feed(a, 0, 4, j, clock, ref);
    stop(j);
    List<String> firstHalf = new ArrayList<>(refLines);
    check(fileLines(root, A).equals(firstHalf) && firstHalf.size() == 4, "first half not persisted: " + fileLines(root, A).size() + " lines");
    String cut = "{\"type\":\"packet\",\"received\":17899";
    Filepath f = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(JournalFile.fileName(A));
    JournalFile.appendText(f, cut);
    PluginJournal k = journal(root, clock, () -> false);
    long t = a.times.get(3)[1];
    JsonObject reloaded = k.call(st -> ParityJson.state(st.state(t)));
    check(canon(reloaded).equals(canon(ParityJson.state(replay(firstHalf).state(t)))), "reload with a cut last line differs from the complete lines");
    feed(a, 4, a.packets.size(), k, clock, ref);
    check(k.awaitIdle(30000), "drain");
    check(fileLines(root, A).equals(refLines), "after recovery the file is not exactly the store's lines");
    byte[] raw = JournalFile.readAll(f);
    check(raw.length > 0 && raw[raw.length - 1] == '\n', "the journal does not end in a complete line");
    List<String> tails = new ArrayList<>();
    try (java.util.stream.Stream<Filepath> s = root.joinSegment(PluginJournal.JOURNAL_DIR).walk(1)) {
      s.forEach(p -> {
        if (p.getFileName().startsWith("interrupted-tail-")) tails.add(p.getFileName());
      });
    }
    check(tails.size() == 1, "expected one saved tail, found " + tails);
    String saved = new String(JournalFile.readAll(root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(tails.get(0))), StandardCharsets.UTF_8);
    check(saved.equals(cut), "the saved tail is not the cut line: " + saved);
    stop(k);
    PluginJournal m = journal(root, clock, () -> false);
    long end = a.times.get(a.times.size() - 1)[1];
    JsonObject again = m.call(st -> ParityJson.state(st.state(end)));
    check(canon(again).equals(canon(ParityJson.state(replay(refLines).state(end)))), "second restart differs");
    StoreState st = m.call(s -> s.state(end));
    check(st.autoFlips.size() == 1 && st.autoOpenPositions.isEmpty(), "restart lost the whip flip or a position: " + st.autoFlips.size() + " flips, " + st.autoOpenPositions.size() + " open");
    stop(m);
    root.deleteRecursively();
  }

  /** Two clients (two journals, one folder), two accounts, writing at the same time. */
  static void twoClientsInParallel() throws Exception {
    for (int round = 0; round < 5; round++) {
      Filepath root = tempRoot();
      AtomicLong c1 = new AtomicLong(), c2 = new AtomicLong();
      PluginJournal j1 = journal(root, c1, () -> false);
      PluginJournal j2 = journal(root, c2, () -> false);
      Client a = streamA(), b = streamB();
      List<String> linesA = new ArrayList<>(), linesB = new ArrayList<>();
      Store refA = new Store(r -> linesA.add(JournalCodec.encodeLine(r)));
      Store refB = new Store(r -> linesB.add(JournalCodec.encodeLine(r)));
      Thread ta = new Thread(() -> feed(a, 0, a.packets.size(), j1, c1, refA), "Client");
      Thread tb = new Thread(() -> feed(b, 0, b.packets.size(), j2, c2, refB), "Client");
      ta.start();
      tb.start();
      ta.join();
      tb.join();
      check(j1.awaitIdle(30000) && j2.awaitIdle(30000), "drain");
      check(fileLines(root, A).equals(linesA), "round " + round + ": account A's file is not exactly A's lines");
      check(fileLines(root, B).equals(linesB), "round " + round + ": account B's file is not exactly B's lines");
      // Each client also sees what the OTHER wrote, without writing anything itself first.
      List<String> both = new ArrayList<>(linesA);
      both.addAll(linesB);
      long t = T0 + 400_000;
      JsonElement expected = trading(replay(both).state(t));
      check(trading(j1.call(s -> s.state(t))).equals(expected), "round " + round + ": client 1 does not see client 2's trades");
      check(trading(j2.call(s -> s.state(t))).equals(expected), "round " + round + ": client 2 does not see client 1's trades");
      StoreState st = j1.call(s -> s.state(t));
      check(Profit.sinceAllAccounts(st, null).trades == 2, "machine-wide profit line should count both accounts' flips");
      stop(j1);
      stop(j2);
      root.deleteRecursively();
    }
  }

  /**
   * DEVELOPER SHADOW MODE's door (S3, 8 Oct verifier): {@link PluginJournal#engineView} catches up with EVERY file before it
   * answers, as a packet would. Client 2 writes account B's trades to the shared folder AFTER client 1 started and while client 1
   * receives nothing: client 1's view must hold them -- read from B's file at the moment of the view, not the state it loaded at
   * start.
   */
  static void engineViewSeesOtherWriter() throws Exception {
    Filepath root = tempRoot();
    AtomicLong c1 = new AtomicLong(), c2 = new AtomicLong();
    PluginJournal j1 = journal(root, c1, () -> false);
    long t = T0 + 400_000;
    PluginJournal.EngineView before = j1.engineView(t).get(30, TimeUnit.SECONDS);
    check(before != null && before.offers.isEmpty() && before.accounts == 0, "setup: client 1 starts on an empty folder");
    PluginJournal j2 = journal(root, c2, () -> false);
    Client b = streamB();
    List<String> linesB = new ArrayList<>();
    feed(b, 0, b.packets.size(), j2, c2, new Store(r -> linesB.add(JournalCodec.encodeLine(r))));
    check(j2.awaitIdle(30000), "drain");
    check(fileLines(root, B).equals(linesB) && !linesB.isEmpty(), "setup: client 2 wrote account B's file");
    PluginJournal.EngineView view = j1.engineView(t).get(30, TimeUnit.SECONDS);
    JsonElement expected = trading(replay(linesB).state(t));
    check(view != null && trading(view.state).equals(expected) && view.offers.size() == replay(linesB).offers().size() && !view.offers.isEmpty() && view.accounts == 1,
      "the engine's view must include what ANOTHER writer appended since (it syncs first): " + (view == null ? "null" : view.offers.size() + " offers, " + view.accounts + " accounts"));
    stop(j1);
    stop(j2);
    root.deleteRecursively();
  }

  /**
   * One account moves between clients: client 1 trades on A and logs out, client 2 (started before any of
   * that was written) logs in to A. Client 2 must read client 1's lines before it writes, or the relog's
   * new offer ids could not be linked to the offers they continue.
   */
  static void accountHandover() throws Exception {
    Filepath root = tempRoot();
    AtomicLong c1 = new AtomicLong(), c2 = new AtomicLong();
    PluginJournal j2 = journal(root, c2, () -> false);
    check(j2.awaitIdle(30000), "drain");
    PluginJournal j1 = journal(root, c1, () -> false);
    Client a = streamA(), a2 = streamA2();
    List<String> refLines = new ArrayList<>();
    Store ref = new Store(r -> refLines.add(JournalCodec.encodeLine(r)));
    feed(a, 0, a.packets.size(), j1, c1, ref);
    check(j1.awaitIdle(30000), "drain");
    feed(a2, 0, a2.packets.size(), j2, c2, ref);
    check(j2.awaitIdle(30000), "drain");
    long t = T0 + 700_000;
    StoreState st = j2.call(s -> s.state(t));
    check(st.linkedLoginOffers == 2, "client 2 linked " + st.linkedLoginOffers + " login offers, expected 2 (the sale and the rune buy)");
    check(trading(st).equals(trading(ref.state(t))), "client 2's state differs from one store that saw every packet");
    check(fileLines(root, A).equals(refLines), "the handed-over account's file is not exactly the store's lines");
    stop(j1);
    stop(j2);
    root.deleteRecursively();
  }

  /** A synthetic bridge journal: both accounts' packets interleaved, plus records of every other kind. */
  static final class BridgeJournal {
    final List<String> lines = new ArrayList<>();
    final List<String> owner = new ArrayList<>(); // A, B or "" (names no account), per line
    final Store store = new Store(r -> lines.add(JournalCodec.encodeLine(r)));

    void tag(String who) {
      while (owner.size() < lines.size()) owner.add(who);
    }

    List<String> of(String who) {
      List<String> out = new ArrayList<>();
      for (int i = 0; i < lines.size(); i++) if (owner.get(i).equals(who)) out.add(lines.get(i));
      return out;
    }

    static BridgeJournal build() {
      BridgeJournal bj = new BridgeJournal();
      Client a = streamA(), a2 = streamA2(), b = streamB();
      for (int i = 0; i < Math.max(a.packets.size(), b.packets.size()); i++) {
        if (i < b.packets.size()) {
          feed(b, i, i + 1, null, new AtomicLong(), bj.store);
          bj.tag(B);
        }
        if (i < a.packets.size()) {
          feed(a, i, i + 1, null, new AtomicLong(), bj.store);
          bj.tag(A);
        }
      }
      feed(a2, 0, a2.packets.size(), null, new AtomicLong(), bj.store);
      bj.tag(A);
      long now = T0 + 700_000;
      JsonObject rec = new JsonObject();
      rec.addProperty("itemId", 2);
      rec.addProperty("name", "Steel cannonball");
      rec.addProperty("quantity", 100);
      rec.addProperty("unitPrice", 5);
      rec.addProperty("at", T0 + 50_000);
      rec.addProperty("account", A);
      bj.store.recordPurchase(rec, now);
      bj.tag(A);
      bj.store.closePosition("sb1-b4", "used", now);
      bj.tag(B);
      bj.store.markPersonalUseItem(1038, true, null);
      bj.tag("");
      bj.store.markPersonalUse("sa2-b2", true); // named by its relog id: the import must resolve it to A
      bj.tag(A);
      return bj;
    }
  }

  static void writeImport(Filepath root, List<String> lines) throws Exception {
    Filepath d = root.joinSegment(PluginJournal.IMPORT_DIR);
    d.createDirectories();
    StringBuilder sb = new StringBuilder();
    for (String l : lines) sb.append(l).append('\n');
    d.joinSegment(PluginJournal.IMPORT_FILE).write(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** The import file is present, the setting is off: nothing is read, nothing is written but the plugin's own packets. */
  static void importOffDoesNothing() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    check(bj.of(A).size() == 11 && bj.of(B).size() == 8 && bj.of("").size() == 1, "fixture shape: " + bj.of(A).size() + "/" + bj.of(B).size() + "/" + bj.of("").size());
    Filepath root = tempRoot();
    writeImport(root, bj.lines);
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> false);
    Client p = new Client("p1", A);
    List<String> own = new ArrayList<>();
    feed(p.sendAll(T0 + 900_000, 2), 0, 2, j, clock, new Store(r -> own.add(JournalCodec.encodeLine(r))));
    check(j.awaitIdle(30000), "drain");
    check(fileLines(root, A).equals(own), "with the import OFF the account file must hold only the plugin's own lines");
    check(!root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(PluginJournal.markerName(A)).exists(), "an import marker was written with the import OFF");
    check(fileLines(root, B).isEmpty(), "B's records appeared with the import OFF");
    check(importedLines(root, A).isEmpty() && importedLines(root, B).isEmpty(), "an imported file was written with the import OFF");
    stop(j);
    root.deleteRecursively();
  }

  /** The setting on: exactly this account's records are copied, merged with the plugin's own, once. */
  static void importOnOncePerAccount() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    Filepath root = tempRoot();
    writeImport(root, bj.lines);
    AtomicLong clock = new AtomicLong();
    // Before the import: the plugin already journalled A's first packet (the bridge has it too, received a
    // moment apart) and one packet of its own session.
    PluginJournal j = journal(root, clock, () -> false);
    Client a = streamA();
    clock.set(a.times.get(0)[1] + 7);
    j.offerPacket(a.packets.get(0));
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 2);
    List<String> own = new ArrayList<>();
    Store ownStore = new Store(r -> own.add(JournalCodec.encodeLine(r)));
    feed(p, 0, 1, j, clock, ownStore);
    stop(j);
    List<String> pre = fileLines(root, A);
    check(pre.size() == 2 && pre.get(1).equals(own.get(0)), "setup: expected 2 plugin lines before the import");
    boolean[] enabled = {true};
    PluginJournal k = journal(root, clock, () -> enabled[0]);
    feed(p, 1, 2, k, clock, ownStore); // the first packet after the setting is on triggers the import
    check(k.awaitIdle(30000), "drain");
    // The import is a NEW file holding exactly A's bridge records; the events file is only ever appended to.
    check(importedLines(root, A).equals(bj.of(A)), "the imported file is not exactly A's bridge records: got "
      + importedLines(root, A).size() + " lines, expected " + bj.of(A).size());
    List<String> events = new ArrayList<>(pre);
    events.add(own.get(1));
    check(fileLines(root, A).equals(events), "the events file was rewritten by the import, or lost the plugin's own lines");
    check(fileLines(root, B).isEmpty() && importedLines(root, B).isEmpty(), "B's records were imported into the plugin journal");
    // What the store holds: A's bridge records, then the plugin's own (A's first packet is the bridge's copy).
    List<String> expected = new ArrayList<>(bj.of(A));
    expected.addAll(own);
    long t0 = T0 + 1_000_000;
    check(trading(k.call(s -> s.state(t0))).equals(trading(replay(expected).state(t0))), "the store is not the bridge's records followed by the plugin's own");
    check(k.call(PluginJournalTest::offers).equals(offers(replay(expected))), "the store's offers differ from the bridge's records followed by the plugin's own");
    PluginJournal fresh = journal(root, clock, () -> false);
    check(fresh.call(PluginJournalTest::offers).equals(offers(replay(expected))), "after a restart the store differs from the bridge's records followed by the plugin's own");
    stop(fresh);
    Filepath marker = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(PluginJournal.markerName(A));
    check(marker.exists(), "no import marker");
    JsonObject mk = ParityJson.parse(new String(JournalFile.readAll(marker), StandardCharsets.UTF_8)).getAsJsonObject();
    check(mk.get("imported").getAsInt() == 11 && mk.get("otherAccounts").getAsInt() == 8 && mk.get("notTiedToAnAccount").getAsInt() == 1
      && mk.get("keptFromPlugin").getAsInt() == 1 && mk.get("unresolved").getAsInt() == 0, "marker counts: " + mk);
    long t = T0 + 1_000_000;
    StoreState st = k.call(s -> s.state(t));
    check(st.personalUseBuyIds.equals(Collections.singletonList("sa1-b2")), "the personal-use mark did not come across resolved: " + st.personalUseBuyIds);
    check(st.linkedLoginOffers == 2, "the relog continuations were not rebuilt from the imported lines");
    // ONCE: the setting re-applied and the import file grown, the account is not imported again.
    List<String> grown = new ArrayList<>(bj.lines);
    grown.add(bj.lines.get(1));
    writeImport(root, grown);
    k.importRequested();
    Client q = new Client("p2", A);
    q.sendAll(T0 + 950_000, 1);
    List<String> more = new ArrayList<>();
    Store qs = new Store(r -> more.add(JournalCodec.encodeLine(r)));
    int from = logs.size();
    feed(q, 0, 1, k, clock, qs);
    check(k.awaitIdle(30000), "drain");
    check(logsContaining(from, "import") == 0, "an already-imported account was tried again: " + logs.subList(from, logs.size()));
    List<String> after = new ArrayList<>(events);
    after.addAll(more);
    check(fileLines(root, A).equals(after) && importedLines(root, A).equals(bj.of(A)), "a second import ran for an account already imported");
    // An import file with an unreadable line imports nothing.
    Filepath root2 = tempRoot();
    List<String> bad = new ArrayList<>(bj.lines);
    bad.add(3, "{not json");
    writeImport(root2, bad);
    PluginJournal m = journal(root2, clock, () -> true);
    Client r = new Client("p3", A);
    r.sendAll(T0 + 990_000, 1);
    List<String> rl = new ArrayList<>();
    feed(r, 0, 1, m, clock, new Store(x -> rl.add(JournalCodec.encodeLine(x))));
    check(m.awaitIdle(30000), "drain");
    check(fileLines(root2, A).equals(rl) && importedLines(root2, A).isEmpty(), "an unreadable import file must import nothing");
    check(!root2.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(PluginJournal.markerName(A)).exists(), "marker written for a refused import");
    stop(k);
    stop(m);
    root.deleteRecursively();
    root2.deleteRecursively();
  }

  /**
   * The case a review found (6 Oct): client 1 runs the import for account A while client 2, logged in to B,
   * has already read A's events file. Client 2 must refuse or lose none of B's packets, and both clients
   * must end up holding exactly what is on disk, A's imported history included.
   */
  static void importWhileAnotherClientFollows() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    for (int split = 1; split <= 3; split++) {
      Filepath root = tempRoot();
      AtomicLong c1 = new AtomicLong(), c2 = new AtomicLong();
      boolean[] on = {false};
      PluginJournal j1 = journal(root, c1, () -> on[0]);
      PluginJournal j2 = journal(root, c2, () -> false);
      // Client 1's own lines for A before the import: a session the bridge copy does not hold.
      Client p0 = new Client("p0", A);
      p0.sendAll(T0 + 800_000, split);
      feed(p0, 0, split, j1, c1, null);
      check(j1.awaitIdle(30000), "drain");
      Client b = streamB();
      List<String> linesB = new ArrayList<>();
      Store refB = new Store(r -> linesB.add(JournalCodec.encodeLine(r)));
      feed(b, 0, 1, j2, c2, refB); // client 2 reads A's events file here, before the import
      check(j2.awaitIdle(30000), "drain");
      List<String> eventsA = fileLines(root, A);
      check(eventsA.size() == split, "setup: " + eventsA.size() + " lines for A");
      writeImport(root, bj.lines);
      on[0] = true;
      j1.importRequested();
      Client p1 = new Client("p1", A);
      p1.sendAll(T0 + 900_000, 1);
      feed(p1, 0, 1, j1, c1, null);
      check(j1.awaitIdle(30000), "drain");
      check(importedLines(root, A).equals(bj.of(A)), "split " + split + ": the import did not happen");
      int from = logs.size();
      feed(b, 1, b.packets.size(), j2, c2, refB);
      check(j2.awaitIdle(30000), "drain");
      check(logsContaining(from, "could not journal") == 0 && logsContaining(from, "refused") == 0,
        "split " + split + ": client 2 refused or dropped a packet: " + logs.subList(from, logs.size()));
      check(fileLines(root, B).equals(linesB), "split " + split + ": client 2 lost one of B's packets during client 1's import");
      check(fileLines(root, A).subList(0, split).equals(eventsA), "split " + split + ": the import rewrote A's events file");
      Store disk = diskReplay(root);
      long t = T0 + 1_000_000;
      check(offers(disk).size() > offers(replay(fileLines(root, B))).size() + split, "split " + split + ": the disk holds no imported history");
      check(j2.call(PluginJournalTest::offers).equals(offers(disk)), "split " + split + ": client 2's offers differ from what is on disk");
      check(trading(j2.call(s -> s.state(t))).equals(trading(disk.state(t))), "split " + split + ": client 2's state differs from what is on disk");
      check(trading(j1.call(s -> s.state(t))).equals(trading(disk.state(t))), "split " + split + ": client 1's state differs from what is on disk");
      check(disk.state(t).linkedLoginOffers == 2 && j2.call(s -> s.state(t)).linkedLoginOffers == 2,
        "split " + split + ": client 2 does not hold A's imported relog links");
      if (split == 1) {
        // Another writer appends, to A's events file, an OLD packet the import already holds (the whip buy at
        // 0 of 10). A rebuild skips it (merge); a client following the file must skip it too, or the offer
        // goes back to BUYING in that client only.
        String old = bj.of(A).get(1);
        check(old.contains("\"BUYING\"") && old.contains("Abyssal whip"), "setup: the old line is the whip buy");
        JournalFile.appendText(root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(JournalFile.fileName(A)), old + "\n");
        Store disk2 = diskReplay(root);
        check(offers(disk2).equals(offers(disk)), "setup: the duplicate changes what a rebuild holds");
        check(j2.call(PluginJournalTest::offers).equals(offers(disk2)), "a client following A's file applied a packet the import already holds");
      }
      stop(j1);
      stop(j2);
      root.deleteRecursively();
    }
  }

  /** The events file of a test account, written whole (as something outside EVI might). */
  static void writeLines(Filepath f, List<String> lines) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (String l : lines) sb.append(l).append('\n');
    f.write(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * An events file changed by anything but an append is re-read whole, never read on from the middle: one
   * that shrank, one replaced by different content of greater length, and one that gained a line that does
   * not decode (that file is then left out, and the packet in hand -- another account's -- is still kept).
   */
  static void externalRewritesRebuild() throws Exception {
    Client a = streamA();
    List<String> ref = new ArrayList<>();
    feed(a, 0, 4, null, new AtomicLong(), new Store(r -> ref.add(JournalCodec.encodeLine(r))));
    check(ref.size() == 4, "setup: " + ref.size() + " reference lines");
    Filepath root = tempRoot();
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> false);
    feed(a, 0, 3, j, clock, null);
    check(j.awaitIdle(30000) && fileLines(root, A).equals(ref.subList(0, 3)), "setup: the first three lines");
    check(j.call(PluginJournalTest::offers).equals(offers(replay(ref.subList(0, 3)))), "setup: the store");
    Filepath f = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(JournalFile.fileName(A));
    // 1. Shrunk to two lines.
    writeLines(f, ref.subList(0, 2));
    check(j.call(PluginJournalTest::offers).equals(offers(replay(ref.subList(0, 2)))), "a file that shrank was not re-read whole");
    // 2. Replaced: the second line (the store's last-read line) now says the whip buy started a second later
    //    -- same length -- and a further line follows. Read on from the old length, the store would apply only
    //    the new line and keep the old start time.
    String ts = "\"ts\":" + a.times.get(1)[0] + ",";
    String moved = "\"ts\":" + (a.times.get(1)[0] + 1000) + ",";
    check(ref.get(1).split(java.util.regex.Pattern.quote(ts), -1).length == 2 && ts.length() == moved.length(), "setup: the second line's ts");
    List<String> replaced = new ArrayList<>(ref.subList(0, 3));
    replaced.set(1, ref.get(1).replace(ts, moved));
    writeLines(f, replaced);
    List<String> expect = j.call(PluginJournalTest::offers);
    check(expect.equals(offers(replay(replaced))) && !expect.equals(offers(replay(ref.subList(0, 3)))),
      "a file replaced by different, longer content was read on from the middle instead of re-read whole");
    // 3. A line that does not decode, appended by another writer: A's file is left out and logged; B's packets are kept.
    JournalFile.appendText(f, "{not json\n");
    Client b = streamB();
    List<String> lb = new ArrayList<>();
    int from = logs.size();
    feed(b, 0, 2, j, clock, new Store(r -> lb.add(JournalCodec.encodeLine(r))));
    check(j.awaitIdle(30000), "drain");
    check(fileLines(root, B).equals(lb) && lb.size() == 2, "a packet for B was dropped because A's file gained an unreadable line");
    check(logsContaining(from, JournalFile.fileName(A) + " has an unreadable line") == 1, "the unreadable line was not logged once: " + logs.subList(from, logs.size()));
    check(j.call(PluginJournalTest::offers).equals(offers(replay(lb))), "A's unreadable file must be left out and B's packets kept");
    stop(j);
    root.deleteRecursively();
  }

  static Filepath blockImport(Filepath root, String account) throws Exception {
    // A non-empty folder where the import writes its temporary file: every write attempt fails on I/O.
    Filepath blocker = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(JournalFile.importedFileName(account) + ".tmp");
    blocker.createDirectories();
    blocker.joinSegment("x").write(new byte[]{1});
    return blocker;
  }

  /** An import that cannot write never costs the packet that triggered it, is retried, and stops after IMPORT_TRIES. */
  static void importWriteFailureKeepsThePacket() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    // Round 1: blocked once, then unblocked: imported at the very next packet, without the setting re-applied.
    Filepath root = tempRoot();
    writeImport(root, bj.lines);
    Filepath blocker = blockImport(root, A);
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> true);
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 2);
    List<String> own = new ArrayList<>();
    Store os = new Store(r -> own.add(JournalCodec.encodeLine(r)));
    int from = logs.size();
    feed(p, 0, 1, j, clock, os);
    check(j.awaitIdle(30000), "drain");
    check(own.size() == 1 && fileLines(root, A).equals(own), "the packet that triggered a failed import was not journalled");
    check(importedLines(root, A).isEmpty() && logsContaining(from, "the import did not complete") == 1, "the failed import write was not reported once");
    blocker.deleteRecursively();
    feed(p, 1, 2, j, clock, os);
    check(j.awaitIdle(30000), "drain");
    check(importedLines(root, A).equals(bj.of(A)), "a failed import write was not retried at the next packet");
    check(own.size() == 2 && fileLines(root, A).equals(own), "the retry's packet was not journalled");
    List<String> expected = new ArrayList<>(bj.of(A));
    expected.addAll(own);
    check(j.call(PluginJournalTest::offers).equals(offers(replay(expected))), "after the retried import the store is not the bridge's records then the plugin's");
    stop(j);
    root.deleteRecursively();
    // Round 2: blocked for good: tried IMPORT_TRIES times, every packet still journalled; re-applying the setting tries again.
    root = tempRoot();
    writeImport(root, bj.lines);
    blocker = blockImport(root, A);
    j = journal(root, clock, () -> true);
    Client q = new Client("p2", A);
    int n = PluginJournal.IMPORT_TRIES + 1;
    q.sendAll(T0 + 950_000, n + 2);
    List<String> own2 = new ArrayList<>();
    Store os2 = new Store(r -> own2.add(JournalCodec.encodeLine(r)));
    from = logs.size();
    feed(q, 0, n, j, clock, os2);
    check(j.awaitIdle(30000), "drain");
    check(own2.size() == n && fileLines(root, A).equals(own2), "packets were lost while the import kept failing");
    check(logsContaining(from, "the import did not complete") == PluginJournal.IMPORT_TRIES,
      "expected exactly " + PluginJournal.IMPORT_TRIES + " import attempts, saw " + logsContaining(from, "the import did not complete"));
    blocker.deleteRecursively();
    feed(q, n, n + 1, j, clock, os2);
    check(j.awaitIdle(30000) && importedLines(root, A).isEmpty(), "an import past its tries ran again without the setting being re-applied");
    j.importRequested();
    feed(q, n + 1, n + 2, j, clock, os2);
    check(j.awaitIdle(30000) && importedLines(root, A).equals(bj.of(A)), "re-applying the setting did not try the import again");
    check(fileLines(root, A).equals(own2) && own2.size() == n + 2, "packets were lost around the import");
    stop(j);
    root.deleteRecursively();
  }

  /** The setting on before the file is there: said once, then imported as soon as the file appears. */
  static void importFileArrivesLater() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    Filepath root = tempRoot();
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> true);
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 3);
    List<String> own = new ArrayList<>();
    Store os = new Store(r -> own.add(JournalCodec.encodeLine(r)));
    int from = logs.size();
    feed(p, 0, 2, j, clock, os);
    check(j.awaitIdle(30000), "drain");
    check(logsContaining(from, "there is no " + PluginJournal.IMPORT_DIR + "/" + PluginJournal.IMPORT_FILE) == 1, "a missing import file must be said once, not at every packet");
    check(logsContaining(from, PluginFolder.PATH + "/") == 1, "the missing-file line does not say where the file goes");
    check(importedLines(root, A).isEmpty(), "imported from nothing");
    writeImport(root, bj.lines);
    feed(p, 2, 3, j, clock, os);
    check(j.awaitIdle(30000) && importedLines(root, A).equals(bj.of(A)), "the import file appeared and was not picked up at the next packet");
    check(fileLines(root, A).equals(own) && own.size() == 3, "packets were lost around the import");
    stop(j);
    root.deleteRecursively();
  }

  /** A look the test controls: isFile, then what reading the attributes does (null = succeeds), then isDirectory. */
  static PluginJournal.FileLook fakeLook(boolean isFile, IOException attributes, boolean isDirectory) {
    return new PluginJournal.FileLook() {
      @Override
      public boolean isFile() {
        return isFile;
      }

      @Override
      public void attributes() throws IOException {
        if (attributes != null) throw attributes;
      }

      @Override
      public boolean isDirectory() {
        return isDirectory;
      }
    };
  }

  /**
   * "Missing" needs PROOF of absence (8 Oct 2026): isFile() answers false on any I/O error, and twice read a 10 MB import file
   * that was there as missing. Each way a look can come out, then the real file system for the three provable answers.
   */
  static void importFileStateFromLooks() throws Exception {
    final PluginJournal.ImportFileState P = PluginJournal.ImportFileState.PRESENT, M = PluginJournal.ImportFileState.ABSENT,
      U = PluginJournal.ImportFileState.UNKNOWN;
    IOException sharing = new IOException("events.jsonl: The process cannot access the file because it is being used by another process");
    check(PluginJournal.importFileState(fakeLook(true, sharing, false)) == P, "a file is present, whatever else is said");
    check(PluginJournal.importFileState(fakeLook(false, new java.nio.file.NoSuchFileException("events.jsonl"), false)) == M, "no such file: absent");
    check(PluginJournal.importFileState(fakeLook(false, sharing, false)) == U, "a sharing violation is NOT absence");
    check(PluginJournal.importFileState(fakeLook(false, new java.nio.file.AccessDeniedException("events.jsonl"), false)) == U, "access denied is NOT absence");
    check(PluginJournal.importFileState(fakeLook(false, null, true)) == M, "a folder where the file goes: no file");
    check(PluginJournal.importFileState(fakeLook(false, null, false)) == U, "there, readable, yet not a file a moment ago: unknown, never missing");
    Filepath root = tempRoot();
    Filepath f = root.joinSegment(PluginJournal.IMPORT_DIR).joinSegment(PluginJournal.IMPORT_FILE);
    check(PluginJournal.importFileState(PluginJournal.look(f)) == M, "really absent (no import folder either): absent");
    writeImport(root, Collections.singletonList("{}"));
    check(PluginJournal.importFileState(PluginJournal.look(f)) == P, "really there: present");
    f.delete();
    f.createDirectory();
    check(PluginJournal.importFileState(PluginJournal.look(f)) == M, "really a folder: no file");
    root.deleteRecursively();
  }

  /**
   * An import file that cannot be checked right now is not missing: no "no file was found" line or log, no import attempt
   * spent -- more packets than IMPORT_TRIES go by -- and once it can be read, the next packet imports it.
   */
  static void importFileIndeterminateIsNotMissing() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    Filepath root = tempRoot();
    writeImport(root, bj.lines);
    AtomicLong clock = new AtomicLong();
    PluginJournal j = new PluginJournal(root, clock::get, ParityJson::parse, () -> true, logs::add);
    List<String> notices = Collections.synchronizedList(new ArrayList<>());
    j.onImportNotice(notices::add);
    IOException sharing = new IOException("events.jsonl: The process cannot access the file because it is being used by another process");
    j.importFileLook = f -> fakeLook(false, sharing, false);
    j.start();
    Client p = new Client("p1", A);
    int packets = PluginJournal.IMPORT_TRIES + 2;
    p.sendAll(T0 + 900_000, packets + 1);
    List<String> own = new ArrayList<>();
    Store os = new Store(r -> own.add(JournalCodec.encodeLine(r)));
    int from = logs.size();
    feed(p, 0, packets, j, clock, os);
    j.importRequested();
    check(j.awaitIdle(30000), "drain");
    check(!notices.contains(PluginJournal.IMPORT_MISSING_NOTICE), "an import file that could not be checked was called missing: " + notices);
    check(logsContaining(from, "there is no " + PluginJournal.IMPORT_DIR + "/" + PluginJournal.IMPORT_FILE) == 0, "a missing-file log for a file that is there");
    check(logsContaining(from, "could not be checked just now") == 1, "the unknown state is logged once a session, not at every packet");
    check(importedLines(root, A).isEmpty(), "nothing can have been imported while the file could not be checked");
    j.importFileLook = PluginJournal::look;
    feed(p, packets, packets + 1, j, clock, os);
    check(j.awaitIdle(30000) && importedLines(root, A).equals(bj.of(A)), "the file could be read again, and the next packet did not import it (were attempts spent?)");
    check(!notices.contains(PluginJournal.IMPORT_MISSING_NOTICE), "the line was shown at some point: " + notices);
    check(fileLines(root, A).equals(own) && own.size() == packets + 1, "packets were lost");
    stop(j);
    root.deleteRecursively();
  }

  /** An import file larger than any EVI journal is refused before it is read. */
  static void importTooLargeRefused() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    long saved = PluginJournal.importMaxBytes;
    Filepath root = tempRoot();
    try {
      writeImport(root, bj.lines);
      long size = root.joinSegment(PluginJournal.IMPORT_DIR).joinSegment(PluginJournal.IMPORT_FILE).size();
      PluginJournal.importMaxBytes = size - 1;
      AtomicLong clock = new AtomicLong();
      PluginJournal j = journal(root, clock, () -> true);
      Client p = new Client("p1", A);
      p.sendAll(T0 + 900_000, 2);
      List<String> own = new ArrayList<>();
      Store os = new Store(r -> own.add(JournalCodec.encodeLine(r)));
      int from = logs.size();
      feed(p, 0, 1, j, clock, os);
      check(j.awaitIdle(30000), "drain");
      check(importedLines(root, A).isEmpty() && logsContaining(from, "larger than any EVI journal") == 1, "an oversized import file was not refused");
      check(fileLines(root, A).equals(own) && own.size() == 1, "the packet was lost when the import was refused");
      PluginJournal.importMaxBytes = size;
      j.importRequested();
      feed(p, 1, 2, j, clock, os);
      check(j.awaitIdle(30000) && importedLines(root, A).equals(bj.of(A)), "an import file exactly at the limit was refused");
      stop(j);
    } finally {
      PluginJournal.importMaxBytes = saved;
      root.deleteRecursively();
    }
  }

  /**
   * Windows: while another process holds a file open, a move OVER that file fails with AccessDenied (a
   * review proved it). The import must not depend on that: it succeeds while the account's events file is
   * held open for reading, as a second client's read holds it.
   */
  static void importWhileEventsFileHeldOpen() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    Filepath root = tempRoot();
    AtomicLong clock = new AtomicLong();
    PluginJournal j0 = journal(root, clock, () -> false);
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 3);
    List<String> own = new ArrayList<>();
    Store os = new Store(r -> own.add(JournalCodec.encodeLine(r)));
    feed(p, 0, 2, j0, clock, os);
    stop(j0);
    writeImport(root, bj.lines);
    Filepath f = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(JournalFile.fileName(A));
    PluginJournal j = journal(root, clock, () -> true);
    check(j.awaitIdle(30000), "drain");
    int from = logs.size();
    try (java.nio.channels.FileChannel held = f.openFileChannel(java.nio.file.StandardOpenOption.READ)) {
      check(held.size() > 0, "setup: the held file is empty");
      feed(p, 2, 3, j, clock, os);
      check(j.awaitIdle(30000), "drain");
    }
    check(importedLines(root, A).equals(bj.of(A)), "the import failed while the events file was held open: " + logs.subList(from, logs.size()));
    check(fileLines(root, A).equals(own) && own.size() == 3, "a packet was lost while the events file was held open");
    stop(j);
    root.deleteRecursively();
  }

  /**
   * An Error thrown inside the import (an OutOfMemoryError reading a huge file, here thrown by the parser on
   * a marked line) never costs the packet that triggered it: the packet is journalled, the failure is said
   * once, the import is not retried at the next packet (it would most likely fail the same way), and
   * switching the setting on again does retry it.
   */
  static void importErrorKeepsThePacket() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    Filepath root = tempRoot();
    List<String> poisoned = new ArrayList<>(bj.lines);
    poisoned.add("{\"type\":\"synthetic-oom\"}");
    writeImport(root, poisoned);
    AtomicLong clock = new AtomicLong();
    Function<String, JsonElement> parser = s -> {
      if (s.contains("synthetic-oom")) throw new OutOfMemoryError("synthetic, thrown by the test");
      return ParityJson.parse(s);
    };
    PluginJournal j = new PluginJournal(root, clock::get, parser, () -> true, logs::add);
    j.start();
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 3);
    List<String> own = new ArrayList<>();
    Store os = new Store(r -> own.add(JournalCodec.encodeLine(r)));
    int from = logs.size();
    feed(p, 0, 2, j, clock, os);
    check(j.awaitIdle(30000), "drain");
    check(own.size() == 2 && fileLines(root, A).equals(own), "a packet was lost when the import threw an Error: " + fileLines(root, A).size() + " lines, expected 2");
    check(logsContaining(from, "OutOfMemoryError") == 1 && logsContaining(from, "not tried again this session") == 1,
      "the Error must be said exactly once and not retried at the next packet: " + logs.subList(from, logs.size()));
    check(importedLines(root, A).isEmpty(), "an import that threw an Error wrote a file");
    writeImport(root, bj.lines);
    j.importRequested();
    feed(p, 2, 3, j, clock, os);
    check(j.awaitIdle(30000), "drain");
    check(importedLines(root, A).equals(bj.of(A)), "switching the setting on again did not retry the import");
    check(own.size() == 3 && fileLines(root, A).equals(own), "packets were lost around the retried import");
    stop(j);
    root.deleteRecursively();
  }

  /**
   * The replay order is the account's imported file FIRST, then its events file: a relog session in the
   * events file (new offer ids, knownStart false) links to the offers its imported history holds only in
   * that order. Pinned at startup and when the imported file appears under a running journal.
   */
  static void replayOrderImportedFirst() throws Exception {
    Client a = streamA(), a2 = streamA2();
    List<String> all = new ArrayList<>();
    Store ref = new Store(r -> all.add(JournalCodec.encodeLine(r)));
    feed(a, 0, a.packets.size(), null, new AtomicLong(), ref);
    int nA = all.size();
    feed(a2, 0, a2.packets.size(), null, new AtomicLong(), ref);
    List<String> bridgePart = new ArrayList<>(all.subList(0, nA)), pluginPart = new ArrayList<>(all.subList(nA, all.size()));
    long t = T0 + 700_000;
    check(nA == 7 && pluginPart.size() == 2 && ref.state(t).linkedLoginOffers == 2, "setup: " + nA + "/" + pluginPart.size() + " lines");
    List<String> eventsFirst = new ArrayList<>(pluginPart);
    eventsFirst.addAll(bridgePart);
    check(replay(eventsFirst).state(t).linkedLoginOffers == 0 && !offers(replay(eventsFirst)).equals(offers(ref)),
      "setup: replayed events-first the relog must link nothing, or this test cannot see the order");
    Filepath root = tempRoot();
    Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
    dir.createDirectories();
    writeLines(dir.joinSegment(JournalFile.importedFileName(A)), bridgePart);
    writeLines(dir.joinSegment(JournalFile.fileName(A)), pluginPart);
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> false);
    StoreState st = j.call(s -> s.state(t));
    check(st.linkedLoginOffers == 2, "at startup the relog linked " + st.linkedLoginOffers + " offers, expected 2 (imported file replayed first)");
    check(j.call(PluginJournalTest::offers).equals(offers(ref)) && trading(st).equals(trading(ref.state(t))),
      "at startup the store is not the imported history followed by the events file");
    stop(j);
    root.deleteRecursively();
    // The imported file appears while a journal runs (another client's import): the rebuild keeps the order.
    Filepath root2 = tempRoot();
    Filepath dir2 = root2.joinSegment(PluginJournal.JOURNAL_DIR);
    dir2.createDirectories();
    writeLines(dir2.joinSegment(JournalFile.fileName(A)), pluginPart);
    PluginJournal k = journal(root2, clock, () -> false);
    check(k.call(s -> s.state(t)).linkedLoginOffers == 0, "setup: the events file alone links nothing");
    writeLines(dir2.joinSegment(JournalFile.importedFileName(A)), bridgePart);
    StoreState st2 = k.call(s -> s.state(t));
    check(st2.linkedLoginOffers == 2 && k.call(PluginJournalTest::offers).equals(offers(ref)),
      "after the imported file appeared the relog linked " + st2.linkedLoginOffers + ", expected 2 (imported first)");
    stop(k);
    root2.deleteRecursively();
  }

  /**
   * An imported file that CHANGES SIZE while a journal runs (the list of files staying the same) is re-read:
   * the store is rebuilt from disk. Here the imported file first holds only the first four of A's bridge
   * lines, then all seven; the store must end up holding all seven, imported first.
   */
  static void importedFileChangedRebuilds() throws Exception {
    Client a = streamA(), a2 = streamA2();
    List<String> all = new ArrayList<>();
    Store ref = new Store(r -> all.add(JournalCodec.encodeLine(r)));
    feed(a, 0, a.packets.size(), null, new AtomicLong(), ref);
    int nA = all.size();
    feed(a2, 0, a2.packets.size(), null, new AtomicLong(), ref);
    List<String> bridgePart = new ArrayList<>(all.subList(0, nA)), pluginPart = new ArrayList<>(all.subList(nA, all.size()));
    List<String> partial = new ArrayList<>(bridgePart.subList(0, 4));
    List<String> partialThenPlugin = new ArrayList<>(partial);
    partialThenPlugin.addAll(pluginPart);
    check(nA == 7 && !offers(replay(partialThenPlugin)).equals(offers(ref)), "setup: four imported lines must hold less than seven");
    Filepath root = tempRoot();
    Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
    dir.createDirectories();
    writeLines(dir.joinSegment(JournalFile.importedFileName(A)), partial);
    writeLines(dir.joinSegment(JournalFile.fileName(A)), pluginPart);
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> false);
    check(j.call(PluginJournalTest::offers).equals(offers(replay(partialThenPlugin))), "setup: at startup the store holds the four imported lines, then the events file");
    writeLines(dir.joinSegment(JournalFile.importedFileName(A)), bridgePart); // same file name, new size
    long t = T0 + 700_000;
    check(j.call(PluginJournalTest::offers).equals(offers(ref)), "an imported file that grew was not re-read: the store still holds the old four lines");
    check(j.call(s -> s.state(t)).linkedLoginOffers == 2, "after the imported file grew the relog must link both carry-overs");
    stop(j);
    root.deleteRecursively();
  }

  /**
   * The imported file is written once and never changed by EVI -- but anything outside it can change it. Growth is
   * pinned above; here the file SHRINKS to its first four lines, and then is REPLACED by content of exactly the
   * same length (one packet's ts moved by a second). Both times the store must be rebuilt from what is on disk,
   * and both expectations are checked to differ from what the store held before, so neither passes by accident.
   */
  static void importedFileShrunkOrReplacedRebuilds() throws Exception {
    Client a = streamA(), a2 = streamA2();
    List<String> all = new ArrayList<>();
    Store ref = new Store(r -> all.add(JournalCodec.encodeLine(r)));
    feed(a, 0, a.packets.size(), null, new AtomicLong(), ref);
    int nA = all.size();
    feed(a2, 0, a2.packets.size(), null, new AtomicLong(), ref);
    List<String> bridgePart = new ArrayList<>(all.subList(0, nA)), pluginPart = new ArrayList<>(all.subList(nA, all.size()));
    Filepath root = tempRoot();
    Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
    dir.createDirectories();
    Filepath imp = dir.joinSegment(JournalFile.importedFileName(A));
    writeLines(imp, bridgePart);
    writeLines(dir.joinSegment(JournalFile.fileName(A)), pluginPart);
    PluginJournal j = journal(root, new AtomicLong(), () -> false);
    check(j.call(PluginJournalTest::offers).equals(offers(ref)), "setup: at startup the store holds all seven imported lines, then the events file");
    // 1. SHRUNK to four lines.
    List<String> shrunk = new ArrayList<>(bridgePart.subList(0, 4));
    List<String> shrunkThenPlugin = new ArrayList<>(shrunk);
    shrunkThenPlugin.addAll(pluginPart);
    check(!offers(replay(shrunkThenPlugin)).equals(offers(ref)), "setup: four imported lines must hold less than seven");
    writeLines(imp, shrunk);
    check(j.call(PluginJournalTest::offers).equals(offers(replay(shrunkThenPlugin))), "an imported file that SHRANK was not re-read: the store still holds the old seven lines");
    // 2. REPLACED, same length: the second line says the whip buy started a second later.
    String ts = "\"ts\":" + a.times.get(1)[0] + ",", moved = "\"ts\":" + (a.times.get(1)[0] + 1000) + ",";
    List<String> replaced = new ArrayList<>(shrunk);
    replaced.set(1, shrunk.get(1).replace(ts, moved));
    check(shrunk.get(1).split(java.util.regex.Pattern.quote(ts), -1).length == 2 && !replaced.get(1).equals(shrunk.get(1))
      && String.join("\n", replaced).length() == String.join("\n", shrunk).length(), "setup: the replacement must change one ts and keep the file's length");
    List<String> replacedThenPlugin = new ArrayList<>(replaced);
    replacedThenPlugin.addAll(pluginPart);
    check(!offers(replay(replacedThenPlugin)).equals(offers(replay(shrunkThenPlugin))), "setup: the replaced file must replay differently");
    writeLines(imp, replaced);
    check(j.call(PluginJournalTest::offers).equals(offers(replay(replacedThenPlugin))),
      "an imported file REPLACED by content of the same length was not re-read: the store still holds the old start time");
    stop(j);
    root.deleteRecursively();
  }

  /**
   * The fingerprint the imported file is checked by, one component at a time, on a 1,000-byte file read through
   * 64-byte windows. Each change below is visible to exactly ONE component: the length (a middle line removed),
   * the head window (byte 10 changed), the tail window (the last line changed), and the modified time (a middle
   * byte changed, read with a later time). Dropping any component from the comparison fails the line that names it.
   * Unchanged, it is equal; a missing file is not. The size and both windows are read from the real file; the time
   * each is compared at is pinned in the fingerprint itself ({@link #atTime}), because Filepath -- the only file API
   * these tests use, as the plugin does -- cannot set a file's modified time.
   */
  static void fingerprintSeesEveryKindOfChange() throws Exception {
    int saved = JournalFile.fingerprintWindow;
    Filepath root = TestFiles.tempDir("evi-journal-test");
    try {
      JournalFile.fingerprintWindow = 64;
      Filepath f = root.joinSegment("imported-x.jsonl");
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 10; i++) sb.append(String.format(java.util.Locale.US, "line-%03d-", i)).append("x".repeat(90)).append('\n');
      byte[] orig = sb.toString().getBytes(StandardCharsets.UTF_8);
      check(orig.length == 1000, "setup: " + orig.length + " bytes");
      f.write(orig);
      java.nio.file.attribute.FileTime t0 = java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L);
      JournalFile.Fingerprint read0 = JournalFile.fingerprint(f);
      check(read0.modified != null && read0.modified.equals(f.getLastModifiedTime()), "the fingerprint carries the file's own modified time: " + read0.modified);
      JournalFile.Fingerprint fp0 = atTime(read0, t0);
      check(fp0.size == 1000 && fp0.head.length == 64 && fp0.tail.length == 64, "the fingerprint's parts: " + fp0.size + "/" + fp0.head.length + "/" + fp0.tail.length);
      check(Arrays.equals(fp0.head, Arrays.copyOfRange(orig, 0, 64)) && Arrays.equals(fp0.tail, Arrays.copyOfRange(orig, 936, 1000)),
        "the windows are the file's first and last 64 bytes");
      check(fp0.equals(atTime(JournalFile.fingerprint(f), t0)), "an unchanged file must give an equal fingerprint");
      // Length only: line 5 removed; head, tail and time all as before.
      byte[] shorter = new byte[900];
      System.arraycopy(orig, 0, shorter, 0, 500);
      System.arraycopy(orig, 600, shorter, 500, 400);
      f.write(shorter);
      JournalFile.Fingerprint fp1 = atTime(JournalFile.fingerprint(f), t0);
      check(fp1.size == 900 && Arrays.equals(fp1.head, fp0.head) && Arrays.equals(fp1.tail, fp0.tail), "setup: only the length differs");
      check(!fp1.equals(fp0), "a file that SHRANK (same ends, same time) must not match: the LENGTH is not compared");
      // Head only.
      byte[] head = orig.clone();
      head[10] = 'Z';
      f.write(head);
      JournalFile.Fingerprint fp2 = atTime(JournalFile.fingerprint(f), t0);
      check(fp2.size == 1000 && !Arrays.equals(fp2.head, fp0.head) && Arrays.equals(fp2.tail, fp0.tail), "setup: only the head differs");
      check(!fp2.equals(fp0), "a change in the first bytes (same length, same time) must not match: the HEAD is not compared");
      // Tail only.
      byte[] tail = orig.clone();
      tail[990] = 'Z';
      f.write(tail);
      JournalFile.Fingerprint fp3 = atTime(JournalFile.fingerprint(f), t0);
      check(fp3.size == 1000 && Arrays.equals(fp3.head, fp0.head) && !Arrays.equals(fp3.tail, fp0.tail), "setup: only the tail differs");
      check(!fp3.equals(fp0), "a change in the last bytes (same length, same time) must not match: the TAIL is not compared");
      // Time only (a middle byte, outside both windows, read with a later time).
      byte[] middle = orig.clone();
      middle[500] = 'Z';
      f.write(middle);
      JournalFile.Fingerprint fp4 = atTime(JournalFile.fingerprint(f), java.nio.file.attribute.FileTime.fromMillis(1_700_000_002_000L));
      check(fp4.size == 1000 && Arrays.equals(fp4.head, fp0.head) && Arrays.equals(fp4.tail, fp0.tail), "setup: only the time differs");
      check(!fp4.equals(fp0), "a middle change written afresh (same length, same ends) must not match: the TIME is not compared");
      // Missing.
      f.delete();
      check(!JournalFile.fingerprint(f).equals(fp0) && JournalFile.fingerprint(f).size == 0, "a missing file must not match an existing one");
    } finally {
      JournalFile.fingerprintWindow = saved;
      root.deleteRecursively();
    }
  }

  /** {@code fp} as read from the file, with its modified time replaced by {@code time}. */
  static JournalFile.Fingerprint atTime(JournalFile.Fingerprint fp, java.nio.file.attribute.FileTime time) {
    return new JournalFile.Fingerprint(fp.size, time, fp.head, fp.tail);
  }

  /**
   * An import file that selects cleanly but whose merged history does not replay is refused before anything is
   * written. The case: three recorded purchases of 2,147,483,647 x 2,147,483,647 -- a bridge journal can hold
   * them, since JS accepts that product -- put the journal's totals past what a long holds, so state() throws.
   * Written, the imported file is never changed again, and the MACHINE-WIDE store (every account's profit line
   * and holdings) would throw at every state() from then on. Refused: no imported file, no marker, one log
   * line (not retried at the next packet), the packet in hand journalled, and the store still answering.
   */
  static void importThatDoesNotReplayRefused() throws Exception {
    List<String> lines = new ArrayList<>();
    for (int k = 1; k <= 3; k++) {
      lines.add(JournalCodec.encodeLine(new JournalRecord.PurchaseRecorded("recorded:314:" + (T0 - k) + ":2147483647:2147483647", 314, "Feather",
        2_147_483_647L, 2_147_483_647L, T0 - k, A)));
    }
    check(BridgeImport.select(decode(lines), A).records.size() == 3, "setup: the import file must select cleanly, all three lines A's");
    boolean threw = false;
    try {
      replay(lines).state(T0);
    } catch (ArithmeticException expected) {
      threw = true;
    }
    check(threw, "setup: three such purchases must put state() past a long");
    Filepath root = tempRoot();
    writeImport(root, lines);
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> true);
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 2);
    List<String> own = new ArrayList<>();
    Store os = new Store(r -> own.add(JournalCodec.encodeLine(r)));
    int from = logs.size();
    feed(p, 0, 2, j, clock, os);
    check(j.awaitIdle(30000), "drain");
    check(importedLines(root, A).isEmpty(), "an import whose history does not replay was written: " + importedLines(root, A).size() + " lines");
    check(!root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(PluginJournal.markerName(A)).exists(), "a marker was written for an import that does not replay");
    check(logsContaining(from, "the imported history does not replay, so nothing was imported") == 1,
      "the refusal must be logged exactly once (not retried at the next packet): " + logs.subList(from, logs.size()));
    check(own.size() == 2 && fileLines(root, A).equals(own), "the packets were not journalled when the import was refused");
    long t = T0 + 1_000_000;
    check(trading(j.call(s -> s.state(t))).equals(trading(os.state(t))), "after the refusal the store must hold the plugin's own packets and still answer");
    stop(j);
    root.deleteRecursively();
  }

  /**
   * The counts marker on its own means "already imported": an imported file removed by hand is not imported
   * a second time. B, which has no marker, is imported from the same file in the same session (the control).
   */
  /**
   * 8 Oct 2026: a mark pressed IN the plugin is never dropped as a "duplicate" of an imported line. The import holds the
   * bridge's personal-use mark on A's buy AND its undo; the player then marks the same buy again in the plugin. Marks carry no
   * time, so the plugin's line is byte-identical to the imported mark -- and until the fix the rebuild's merge dropped it, so
   * the buy came back as "not personal use" after the next restart. Packets the bridge also holds are still not doubled.
   */
  static void pluginMarkSurvivesIdenticalImportedLine() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    List<String> imp = new ArrayList<>(bj.of(A));
    Store bridge = Store.replay(decode(imp), r -> imp.add(JournalCodec.encodeLine(r)));
    String markLine = imp.get(imp.size() - 1);
    check(markLine.contains("\"type\":\"personal-use\""), "setup: the bridge's last A record is its personal-use mark: " + markLine);
    bridge.markPersonalUse("sa2-b2", false); // undone later, in the scanner
    check(imp.get(imp.size() - 1).contains("\"type\":\"personal-use-undo\""), "setup: the undo is imported too");
    Filepath root = tempRoot();
    Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
    dir.createDirectories();
    writeLines(dir.joinSegment(JournalFile.importedFileName(A)), imp);
    AtomicLong clock = new AtomicLong(T0 + 1_000_000);
    long t = T0 + 1_000_000;
    PluginJournal j = journal(root, clock, () -> false);
    check(j.call(s -> s.state(t)).personalUseBuyIds.isEmpty(), "setup: imported mark then undo leaves the buy unmarked");
    // the player marks it again, in the plugin
    check(j.markPersonalUse(A, "sa2-b2").get(30, java.util.concurrent.TimeUnit.SECONDS) == null, "the plugin's mark is taken");
    check(j.awaitIdle(30000), "drain");
    check(fileLines(root, A).equals(Collections.singletonList(markLine)), "the plugin's line is byte-identical to the imported mark: " + fileLines(root, A));
    check(j.call(s -> s.state(t)).personalUseBuyIds.equals(Collections.singletonList("sa1-b2")), "marked at once");
    stop(j);
    PluginJournal k = journal(root, clock, () -> false); // a restart: the store rebuilt from disk, imported file first
    check(k.call(s -> s.state(t)).personalUseBuyIds.equals(Collections.singletonList("sa1-b2")),
      "after a rebuild the plugin's own mark must still hold: " + k.call(s -> s.state(t)).personalUseBuyIds);
    check(diskReplay(root).state(t).personalUseBuyIds.equals(Collections.singletonList("sa1-b2")), "the merge itself keeps it");
    // a packet the bridge also holds is still the same packet, once
    List<JournalRecord> packetTwice = BridgeImport.merge(decode(bj.of(A).subList(0, 1)), decode(bj.of(A).subList(0, 1)));
    check(packetTwice.size() == 1, "a packet held by the import and the events file is replayed once: " + packetTwice.size());
    stop(k);
    root.deleteRecursively();
  }

  /**
   * 8 Oct 2026, the built-in engine: the bridge's item-level marks ("Yours, not stock", machine-wide in the bridge) come across
   * with EVERY importing account's import, in journal order, and are counted as item marks rather than "names no account".
   * Without the switch (a bridge install) the import is exactly what it was: importOnOncePerAccount pins that.
   */
  static void importTakesItemMarksForEveryAccount() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    List<String> marks = bj.of("");
    check(marks.size() == 1 && marks.get(0).contains("\"type\":\"personal-use-item\""), "setup: the bridge's one item mark");
    Filepath root = tempRoot();
    writeImport(root, bj.lines);
    AtomicLong clock = new AtomicLong();
    PluginJournal j = new PluginJournal(root, clock::get, ParityJson::parse, () -> true, logs::add);
    j.importItemMarks(() -> true);
    j.start();
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 1);
    feed(p, 0, 1, j, clock, null);
    Client q = new Client("q1", B);
    q.sendAll(T0 + 950_000, 1);
    feed(q, 0, 1, j, clock, null);
    check(j.awaitIdle(30000), "drain");
    for (String who : new String[]{A, B}) {
      List<String> expected = new ArrayList<>();
      for (int i = 0; i < bj.lines.size(); i++) if (bj.owner.get(i).equals(who) || bj.owner.get(i).isEmpty()) expected.add(bj.lines.get(i));
      check(importedLines(root, who).equals(expected), who.substring(0, 6) + ": its records and the item mark, in journal order: " + importedLines(root, who).size());
      JsonObject mk = ParityJson.parse(new String(JournalFile.readAll(root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(PluginJournal.markerName(who))),
        StandardCharsets.UTF_8)).getAsJsonObject();
      check(mk.get("itemMarks").getAsInt() == 1 && mk.get("notTiedToAnAccount").getAsInt() == 0, "marker counts: " + mk);
    }
    long t = T0 + 1_000_000;
    check(j.call(s -> s.state(t)).personalUseItems.contains(1038), "the mark applies (machine-wide, as in the bridge)");
    stop(j);
    root.deleteRecursively();
  }

  // ------------------------------------------------- the former folder's identity salt (import/identity-salt.txt), 9 Oct 2026

  static final String SALT = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
  static final String PROFILE = "rsprofile--1";

  /** The bridge's lines with every account id {@code from} renamed {@code to} (quoted, so only whole ids). */
  static List<String> renamed(List<String> lines, String from, String to) {
    String q = '"' + from + '"', r = '"' + to + '"';
    List<String> out = new ArrayList<>();
    for (String l : lines) out.add(l.replace(q, r));
    return out;
  }

  static void writeSalt(Filepath root, byte[] bytes) throws Exception {
    Filepath d = root.joinSegment(PluginJournal.IMPORT_DIR);
    d.createDirectories();
    d.joinSegment(PluginJournal.SALT_FILE).write(bytes);
  }

  /**
   * The bug found in-client on 9 Oct 2026: a new data folder has a new salt, so the account's id is new and the bridge's records,
   * kept under the id the OLD salt gave, matched none. With the old salt in import/, they are selected by that id and filed under
   * the current one: the imported file is exactly what an import under a matching id writes, and the store sees them as this
   * account's.
   */
  static void importRecognisedByOldSalt() throws Exception {
    // One formula: the plugin's old inline derivation (String.format %02x over SHA-256) and AccountIds agree.
    for (String[] c : new String[][]{{SALT, PROFILE, ""}, {SALT, PROFILE, "leagues"}, {"x", "", ""}}) {
      String identity = c[0] + ":" + c[1] + (c[2].isEmpty() ? "" : ":economy:" + c[2]);
      StringBuilder h = new StringBuilder();
      for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)))
        h.append(String.format("%02x", b & 255));
      check(AccountIds.of(c[0], c[1], c[2]).equals(h.toString()), "AccountIds differs from the plugin's formula for " + Arrays.toString(c));
    }
    BridgeJournal bj = BridgeJournal.build();
    String old = AccountIds.of(SALT, PROFILE, "");
    check(!old.equals(A), "setup: the old id is not A");
    List<String> bridge = renamed(bj.lines, A, old);
    check(!String.join("", bridge).contains(A) && renamed(bridge, old, A).equals(bj.lines), "setup: A appears only as a whole id");
    Filepath root = tempRoot();
    writeImport(root, bridge);
    byte[] s = ((char) 0xFEFF + SALT + "  \r\n").getBytes(StandardCharsets.UTF_8); // as Notepad might save it
    writeSalt(root, s);
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> true);
    j.accountIdentity(A, PROFILE, "");
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 2);
    List<String> own = new ArrayList<>();
    int from = logs.size();
    feed(p, 0, 2, j, clock, new Store(r -> own.add(JournalCodec.encodeLine(r))));
    check(j.awaitIdle(30000), "drain");
    check(importedLines(root, A).equals(bj.of(A)), "the imported file is not exactly A's bridge records under A's id: "
      + importedLines(root, A).size() + " lines, expected " + bj.of(A).size());
    check(importedLines(root, old).isEmpty() && fileLines(root, old).isEmpty(), "something was filed under the OLD id");
    JsonObject mk = marker(root, A);
    check(mk != null && mk.get("imported").getAsInt() == 11 && mk.get("otherAccounts").getAsInt() == 8
      && mk.has("viaIdentitySalt") && mk.get("viaIdentitySalt").getAsBoolean(), "marker counts: " + mk);
    check(logsContaining(from, "recognised by " + PluginJournal.IMPORT_DIR + "/" + PluginJournal.SALT_FILE) == 1, "the import log does not say the salt was used");
    check(logsContaining(from, "carry this account's id") == 0, "the missing-salt hint was logged although the salt matched");
    List<String> expected = new ArrayList<>(bj.of(A));
    expected.addAll(own);
    long t = T0 + 1_000_000;
    check(j.call(PluginJournalTest::offers).equals(offers(replay(expected))), "the store's offers are not A's bridge records then the plugin's own");
    StoreState st = j.call(x -> x.state(t)), ref = replay(expected).state(t);
    check(trading(st).equals(trading(ref)), "the store's trading state differs from a replay of A's records");
    Profit pr = Profit.sinceAllAccounts(st, null), pref = Profit.sinceAllAccounts(ref, null);
    check(pr.gp == pref.gp && pr.trades == pref.trades && pr.openPositions == pref.openPositions, "the profit line differs");
    check(st.personalUseBuyIds.equals(Collections.singletonList("sa1-b2")) && st.linkedLoginOffers == 2, "marks or relogs not carried");
    boolean anyOld = false, anyA = false;
    for (Offer o : j.call(Store::offers)) {
      anyOld |= old.equals(o.account);
      anyA |= A.equals(o.account);
    }
    check(anyA && !anyOld, "the store's offers are not this account's (old id seen: " + anyOld + ")");
    PluginJournal fresh = journal(root, clock, () -> false);
    check(fresh.call(PluginJournalTest::offers).equals(offers(replay(expected))), "after a restart the store differs");
    stop(fresh);
    stop(j);
    // The salt file is only read: still exactly what was placed there.
    check(Arrays.equals(JournalFile.readAll(root.joinSegment(PluginJournal.IMPORT_DIR).joinSegment(PluginJournal.SALT_FILE)), s), "the salt file was changed");

    // WITHOUT the salt file: the old behaviour (nothing of A's is matched), plus one hint naming the file to copy.
    Filepath root2 = tempRoot();
    writeImport(root2, bridge);
    PluginJournal k = journal(root2, clock, () -> true);
    k.accountIdentity(A, PROFILE, "");
    Client q = new Client("p2", A);
    q.sendAll(T0 + 900_000, 1);
    from = logs.size();
    feed(q, 0, 1, k, clock, null);
    check(k.awaitIdle(30000), "drain");
    check(importedLines(root2, A).isEmpty(), "without the salt file A's old-id records must not be taken");
    JsonObject mk2 = marker(root2, A);
    check(mk2 != null && mk2.get("imported").getAsInt() == 0 && mk2.get("otherAccounts").getAsInt() == 19 && !mk2.has("viaIdentitySalt"), "marker: " + mk2);
    check(logsContaining(from, PluginJournal.SALT_HINT) == 1, "no hint to copy the old salt: " + logs.subList(from, logs.size()));
    stop(k);
    root.deleteRecursively();
    root2.deleteRecursively();
  }

  /**
   * The dev tree's case: the salt in import/ is the folder's OWN (same id). Everything written -- imported file and counts -- is
   * exactly what an import without the salt file writes.
   */
  static void importSaltSameAsOwnIsUnchanged() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    String x = AccountIds.of(SALT, PROFILE, "");
    List<String> bridge = renamed(bj.lines, A, x);
    List<List<String>> imported = new ArrayList<>();
    List<JsonObject> markers = new ArrayList<>();
    for (boolean withSalt : new boolean[]{false, true}) {
      Filepath root = tempRoot();
      writeImport(root, bridge);
      if (withSalt) writeSalt(root, SALT.getBytes(StandardCharsets.UTF_8));
      AtomicLong clock = new AtomicLong();
      PluginJournal j = journal(root, clock, () -> true);
      j.accountIdentity(x, PROFILE, "");
      Client p = new Client("p1", x);
      p.sendAll(T0 + 900_000, 2);
      int from = logs.size();
      feed(p, 0, 2, j, clock, null);
      check(j.awaitIdle(30000), "drain");
      check(logsContaining(from, PluginJournal.SALT_FILE) == 0, "the salt was named in the log although it changed nothing: " + withSalt);
      imported.add(importedLines(root, x));
      JsonObject mk = marker(root, x);
      mk.remove("at");
      markers.add(mk);
      stop(j);
      root.deleteRecursively();
    }
    check(imported.get(0).equals(imported.get(1)) && imported.get(0).size() == 11, "the own salt changed the imported file");
    check(markers.get(0).equals(markers.get(1)), "the own salt changed the counts: " + markers);
  }

  /**
   * A salt file that is empty or too large imports NOTHING and records nothing (the preferences.json rule: the import runs once
   * per account, so never under an id the player did not mean); corrected and the setting toggled, it imports. A salt file with
   * no identity told yet waits, as for a name, and is no attempt.
   */
  static void importSaltUnreadableOrWaiting() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    String old = AccountIds.of(SALT, PROFILE, "");
    List<String> bridge = renamed(bj.lines, A, old);
    byte[] big = new byte[(int) PluginJournal.SALT_MAX_BYTES + 1];
    Arrays.fill(big, (byte) 'a');
    for (byte[] bad : new byte[][]{" \r\n".getBytes(StandardCharsets.UTF_8), big}) {
      Filepath root = tempRoot();
      writeImport(root, bridge);
      writeSalt(root, bad);
      AtomicLong clock = new AtomicLong();
      PluginJournal j = journal(root, clock, () -> true);
      j.accountIdentity(A, PROFILE, "");
      Client p = new Client("p1", A);
      p.sendAll(T0 + 900_000, 3);
      int from = logs.size();
      feed(p, 0, 2, j, clock, null);
      check(j.awaitIdle(30000), "drain");
      check(logsContaining(from, PluginJournal.SALT_FILE + " could not be read") == 1, "an unreadable salt was not logged once: " + logs.subList(from, logs.size()));
      check(importedLines(root, A).isEmpty() && marker(root, A) == null, "an unreadable salt must import and record nothing");
      writeSalt(root, SALT.getBytes(StandardCharsets.UTF_8));
      j.importRequested();
      feed(p, 2, 3, j, clock, null);
      check(j.awaitIdle(30000), "drain");
      check(importedLines(root, A).equals(bj.of(A)), "the corrected salt did not import");
      stop(j);
      root.deleteRecursively();
    }
    // No identity told yet: waits (no attempt, nothing written), then imports once it is told.
    Filepath root = tempRoot();
    writeImport(root, bridge);
    writeSalt(root, SALT.getBytes(StandardCharsets.UTF_8));
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> true);
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 6);
    int from = logs.size();
    feed(p, 0, 5, j, clock, null); // more packets than IMPORT_TRIES: waiting must not use up the attempts
    check(j.awaitIdle(30000), "drain");
    check(logsContaining(from, "waits for this account's identity") == 1, "the wait was not logged once");
    check(importedLines(root, A).isEmpty() && marker(root, A) == null, "imported before the identity was known");
    j.accountIdentity(A, PROFILE, "");
    feed(p, 5, 6, j, clock, null);
    check(j.awaitIdle(30000), "drain");
    check(importedLines(root, A).equals(bj.of(A)), "the import did not run once the identity was told");
    stop(j);
    root.deleteRecursively();
  }

  /** One bridge flips-imported line; each flip {fp, source, itemId, account (null: none)}. */
  static String flipsLine(Object[]... flips) {
    JsonObject r = new JsonObject();
    r.addProperty("type", "flips-imported");
    com.google.gson.JsonArray a = new com.google.gson.JsonArray();
    for (Object[] f : flips) {
      JsonObject o = new JsonObject();
      o.addProperty("fp", (String) f[0]);
      o.addProperty("source", (String) f[1]);
      o.addProperty("itemId", (Integer) f[2]);
      o.addProperty("item", "Item " + f[2]);
      o.addProperty("quantity", 10);
      o.addProperty("capital", 1000);
      o.addProperty("netProceeds", 1100);
      o.addProperty("profit", 100);
      o.addProperty("firstBuy", T0 - 7_200_000L);
      o.addProperty("lastSell", T0 - 3_600_000L);
      o.addProperty("hold", 1);
      if (f[3] == null) o.add("account", com.google.gson.JsonNull.INSTANCE);
      else o.addProperty("account", (String) f[3]);
      o.addProperty("imported", true);
      a.add(o);
    }
    r.add("flips", a);
    return r.toString();
  }

  static List<String> fps(List<ImportedFlip> flips) {
    List<String> out = new ArrayList<>();
    for (ImportedFlip f : flips) out.add(f.fp);
    return out;
  }

  /**
   * 8 Oct 2026 (approved): the bridge's flip histories from another tracker (a Copilot export, a flips.csv) name the CHARACTER,
   * not EVI's account. The import takes, for each account, only the flips made under that account's own display name -- as the
   * game compares names (case; space, underscore, hyphen and non-breaking space alike) -- and only those the bridge still holds
   * (a removed import is not taken; a repeated fingerprint is not taken twice). Never another character's, never one naming
   * none. Once per account. While the plugin has not told the name yet, an import that needs it WAITS (the import is once, so
   * running without the name would lose that history for good). The plugin's store, machine-wide as the bridge's, then ranks
   * every account from every imported character's flips.
   */
  static void importFlipsByCharacterName() throws Exception {
    check(CharacterNames.same("Ash Fen", "ash_fen") && CharacterNames.same("Ash Fen", "ASH-FEN") && CharacterNames.same("Ash\u00a0Fen", "ash fen")
      && CharacterNames.same(" ash fen ", "Ash Fen"), "the game's name rule: case, and space / underscore / hyphen / non-breaking space are one");
    check(!CharacterNames.same("ash fen", "ashfen") && !CharacterNames.same("ash fen", "ash fen2") && !CharacterNames.same(null, "ash fen")
      && !CharacterNames.same("", "") && !CharacterNames.same(" ", "_") && !CharacterNames.same("ash fen", null), "nothing looser than that");
    BridgeJournal bj = BridgeJournal.build();
    List<String> lines = new ArrayList<>(bj.lines);
    lines.add(flipsLine(new Object[]{"fp1", "copilot", 561, "Ash Fen"}, new Object[]{"fp2", "copilot", 562, "Alt One"}));
    lines.add(flipsLine(new Object[]{"fp3", "copilot", 563, null}));
    lines.add(flipsLine(new Object[]{"fp4", "csv flips.csv", 564, "ash_fen"}, new Object[]{"fp5", "csv flips.csv", 565, "ASH-FEN"},
      new Object[]{"fp6", "csv flips.csv", 566, "ash\u00a0fen"}, new Object[]{"fp7", "csv flips.csv", 567, "ashfen"},
      new Object[]{"fp8", "csv flips.csv", 568, "ash fen2"}));
    lines.add(flipsLine(new Object[]{"fp9", "tmp", 569, "Ash Fen"}));
    lines.add("{\"type\":\"flips-import-removed\",\"source\":\"tmp\"}");
    lines.add(flipsLine(new Object[]{"fp1", "copilot", 561, "Ash Fen"})); // a repeated fingerprint: the bridge skipped it
    lines.add(flipsLine(new Object[]{"fp10", "tmp", 570, "ash fen"}));    // imported again after the removal: held
    List<JournalRecord> all = decode(lines);
    // The selection, pure.
    BridgeImport.Selection none = BridgeImport.select(all, A, true, null);
    check(none.flips == 0 && none.records.stream().noneMatch(r -> r instanceof JournalRecord.FlipsImported), "no name known: no imported flip taken");
    BridgeImport.Selection sa = BridgeImport.select(all, A, true, "Ash\u00a0Fen");
    List<ImportedFlip> taken = new ArrayList<>();
    for (JournalRecord r : sa.records) if (r instanceof JournalRecord.FlipsImported) taken.addAll(((JournalRecord.FlipsImported) r).flips);
    check(fps(taken).equals(Arrays.asList("fp1", "fp4", "fp5", "fp6", "fp10")) && sa.flips == 5 && sa.flipsOtherCharacters == 3,
      "A (\"Ash Fen\" as the client spells it): exactly its own name's flips the bridge still holds, in journal order: " + fps(taken) + " other " + sa.flipsOtherCharacters);
    BridgeImport.Selection sb = BridgeImport.select(all, B, true, "alt_one");
    List<ImportedFlip> takenB = new ArrayList<>();
    for (JournalRecord r : sb.records) if (r instanceof JournalRecord.FlipsImported) takenB.addAll(((JournalRecord.FlipsImported) r).flips);
    check(fps(takenB).equals(Collections.singletonList("fp2")), "B (\"Alt One\"): only its own: " + fps(takenB));
    check(BridgeImport.select(all, A, true, "Somebody Else").flips == 0, "a name with no flips: none taken");
    // Through the journal: the import waits for the name, then takes them, once.
    Filepath root = tempRoot();
    writeImport(root, lines);
    AtomicLong clock = new AtomicLong();
    PluginJournal j = new PluginJournal(root, clock::get, ParityJson::parse, () -> true, logs::add);
    j.importItemMarks(() -> true);
    j.expectCharacterNames(true);
    j.start();
    int from = logs.size();
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 4);
    feed(p, 0, 1, j, clock, null);
    feed(p, 1, 2, j, clock, null);
    check(j.awaitIdle(30000), "drain");
    Filepath marker = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(PluginJournal.markerName(A));
    check(importedLines(root, A).isEmpty() && !marker.exists(), "no name told yet: the import waits (nothing written)");
    check(logsContaining(from, "the import waits for this character's name") == 1, "said once, not at every packet: " + logs.subList(from, logs.size()));
    check(fileLines(root, A).size() == 2, "the packets are journalled meanwhile");
    j.characterName(A, "Ash\u00a0Fen");
    feed(p, 2, 3, j, clock, null);
    check(j.awaitIdle(30000), "drain");
    List<String> expectA = new ArrayList<>();
    for (JournalRecord r : sa.records) expectA.add(JournalCodec.encodeLine(r));
    check(importedLines(root, A).equals(expectA), "named: A's import is its records with its own flips: " + importedLines(root, A).size() + " lines");
    JsonObject mk = ParityJson.parse(new String(JournalFile.readAll(marker), StandardCharsets.UTF_8)).getAsJsonObject();
    check(mk.get("flips").getAsInt() == 5 && mk.get("flipsOtherCharacters").getAsInt() == 3, "the marker counts them: " + mk);
    long t = T0 + 1_000_000;
    check(fps(j.call(s -> s.state(t)).importedFlips).equals(Arrays.asList("fp1", "fp4", "fp5", "fp6", "fp10")), "the store holds A's character's flips");
    j.characterName(B, "alt_one");
    Client q = new Client("q1", B);
    q.sendAll(T0 + 950_000, 1);
    feed(q, 0, 1, j, clock, null);
    feed(p, 3, 4, j, clock, null);
    check(j.awaitIdle(30000), "drain");
    check(importedLines(root, A).equals(expectA), "once per account: A is not imported again");
    List<String> machine = fps(j.call(s -> s.state(t)).importedFlips);
    check(machine.equals(Arrays.asList("fp1", "fp4", "fp5", "fp6", "fp10", "fp2")),
      "machine-wide, as the bridge ranks them: every imported character's flips (A's file, then B's): " + machine);
    List<String> bridgeNamed = new ArrayList<>();
    for (ImportedFlip f : replay(lines).state(t).importedFlips)
      if (CharacterNames.same(f.account, "ash fen") || CharacterNames.same(f.account, "alt one")) bridgeNamed.add(f.fp);
    List<String> sortedMine = new ArrayList<>(machine), sortedBridge = new ArrayList<>(bridgeNamed);
    Collections.sort(sortedMine);
    Collections.sort(sortedBridge);
    check(sortedMine.equals(sortedBridge), "the same flips the bridge holds under those two names: " + bridgeNamed);
    stop(j);
    // Without names expected (a caller that never names a character), the import runs at once and takes no named flip.
    Filepath root2 = tempRoot();
    writeImport(root2, lines);
    PluginJournal k = new PluginJournal(root2, clock::get, ParityJson::parse, () -> true, logs::add);
    k.start();
    Client p2 = new Client("p2", A);
    p2.sendAll(T0 + 900_000, 1);
    feed(p2, 0, 1, k, clock, null);
    check(k.awaitIdle(30000), "drain");
    JsonObject mk2 = ParityJson.parse(new String(JournalFile.readAll(root2.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(PluginJournal.markerName(A))),
      StandardCharsets.UTF_8)).getAsJsonObject();
    check(mk2.get("flips").getAsInt() == 0 && k.call(s -> s.state(t)).importedFlips.isEmpty(), "names not expected: imported at once, no named flip: " + mk2);
    stop(k);
    root.deleteRecursively();
    root2.deleteRecursively();
  }

  static List<String> takenFps(BridgeImport.Selection s) {
    List<ImportedFlip> taken = new ArrayList<>();
    for (JournalRecord r : s.records) if (r instanceof JournalRecord.FlipsImported) taken.addAll(((JournalRecord.FlipsImported) r).flips);
    return fps(taken);
  }

  static void writePrefs(Filepath root, String text) throws Exception {
    root.joinSegment(PluginJournal.IMPORT_DIR).joinSegment(PluginJournal.PREFERENCES_FILE).write(text.getBytes(StandardCharsets.UTF_8));
  }

  static JsonObject marker(Filepath root, String account) throws Exception {
    Filepath m = root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(PluginJournal.markerName(account));
    return m.exists() ? ParityJson.parse(new String(JournalFile.readAll(m), StandardCharsets.UTF_8)).getAsJsonObject() : null;
  }

  /**
   * 8 Oct 2026 (approved): a character renamed since keeps its trades under its FORMER name too, when the player says so in
   * import/preferences.json ({@code "oldNames": {"Old Name": "Current Name"}}). Explicit only; names compared as the game compares
   * them; a former name is taken ONLY as its current name (never by a character holding the old name itself, never by another);
   * a former name mapped to two current names, to no name, or to a name that is itself a former name (a chain) is refused and its
   * trades go to no one; an unreadable preferences file refuses the import (it runs once) rather than run without the names.
   */
  static void importFlipsUnderFormerNames() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    List<String> lines = new ArrayList<>(bj.lines);
    lines.add(flipsLine(new Object[]{"o1", "copilot", 561, "Old Name"}, new Object[]{"o2", "copilot", 562, "New Name"},
      new Object[]{"o3", "copilot", 563, "Other"}, new Object[]{"o4", "copilot", 564, "Gone"}, new Object[]{"o5", "copilot", 565, "Alt Old"},
      new Object[]{"o6", "copilot", 566, "Chain A"}, new Object[]{"o7", "copilot", 567, "Bad"}, new Object[]{"o8", "copilot", 568, "Mid"}));
    List<JournalRecord> all = decode(lines);
    String prefs = "{\"blocked\":[4151],\"spread\":5,\"oldNames\":{\"old_name\":\"New Name\",\"Gone\":\"New Name\",\"GONE\":\"Alt Now\","
      + "\"Alt Old\":\"alt-now\",\"Chain A\":\"Mid\",\"Mid\":\"new name\",\"Bad\":5,\"Same\":\"same\",\"  \":\"New Name\"},\"profitSince\":1}";
    OldNames on = OldNames.parse(prefs);
    check(on.present && on.mapped() == 3 && on.refusedCount() == 3, "mapped old_name, Alt Old, Mid; refused Gone (two current names), Chain A (a chain), Bad"
      + " (no name); Same->same and a blank name dropped: " + on.mapped() + "/" + on.refusedCount());
    // Unmapped: exactly as before -- the old name's trades are left out.
    BridgeImport.Selection plain = BridgeImport.select(all, A, true, "New Name");
    check(takenFps(plain).equals(Collections.singletonList("o2")) && plain.flipsViaOldNames == 0, "no mapping: only the current name's: " + takenFps(plain));
    BridgeImport.Selection plain2 = BridgeImport.select(all, A, true, "New Name", OldNames.NONE);
    check(takenFps(plain2).equals(takenFps(plain)) && plain2.flipsOtherCharacters == plain.flipsOtherCharacters, "NONE is exactly the old selection");
    // Mapped: the character now named "New Name" takes its old names' trades as its own, in journal order.
    BridgeImport.Selection s = BridgeImport.select(all, A, true, "new_name", on);
    check(takenFps(s).equals(Arrays.asList("o1", "o2", "o8")) && s.flips == 3 && s.flipsViaOldNames == 2,
      "mapped: o1 (old_name) and o8 (Mid) as New Name's, o2 its own: " + takenFps(s) + " via " + s.flipsViaOldNames);
    check(s.flipsOldNamesRefused == 3 && s.flipsOtherCharacters == 5, "refused o4 (conflict), o6 (chain), o7 (no name); o3, o5 left for others: "
      + s.flipsOldNamesRefused + "/" + s.flipsOtherCharacters);
    // A mapping to a DIFFERENT character is not taken by this one, and is taken by that one.
    check(takenFps(BridgeImport.select(all, A, true, "Alt Now", on)).equals(Collections.singletonList("o5")), "Alt Now takes Alt Old's only");
    check(takenFps(BridgeImport.select(all, A, true, "Old Name", on)).isEmpty(), "a character holding the former name itself takes none of it");
    check(takenFps(BridgeImport.select(all, A, true, "Gone", on)).isEmpty() && takenFps(BridgeImport.select(all, A, true, "Chain A", on)).isEmpty()
      && takenFps(BridgeImport.select(all, A, true, "Bad", on)).isEmpty(), "a refused former name's trades go to no one, not even a character of that name");
    check(takenFps(BridgeImport.select(all, A, true, "Mid", on)).isEmpty(), "Mid is a former name: a character named Mid takes none");
    // Exact duplicate keys are seen (a parsed tree would keep the last one silently); an agreeing duplicate is fine.
    OldNames dup = OldNames.parse("{\"oldNames\":{\"X\":\"A\",\"X\":\"B\"}}");
    check(dup.mapped() == 0 && dup.refusedCount() == 1, "a duplicate key with two current names is refused");
    check(OldNames.parse("\uFEFF{\"oldNames\":{\"X\":\"A\"}}").mapped() == 1, "a byte-order mark (Notepad) is not an error");
    OldNames agree = OldNames.parse("{\"oldNames\":{\"X\":\"A\",\"x\":\"a\"}}");
    check(agree.mapped() == 1 && agree.refusedCount() == 0, "two spellings of one former name to one current name: mapped");
    check(!OldNames.parse("{\"blocked\":[]}").present && !OldNames.parse("{\"oldNames\":null}").present && OldNames.parse("{\"oldNames\":{}}").present,
      "absent or null: no names; an empty object: present, no names");
    for (String bad : new String[]{"[]", "{\"oldNames\":[]}", "{\"oldNames\":\"x\"}", "{\"oldNames\":{}} {}", "{\"oldNames\":{\"a\":\"b\"", "", "nul"}) {
      boolean threw = false;
      try {
        OldNames.parse(bad);
      } catch (IllegalArgumentException e) {
        threw = true;
      }
      check(threw, "not a usable preferences file, refused: " + bad);
    }
    // Through the journal: mapped, the marker and the log line say how many came via former names.
    AtomicLong clock = new AtomicLong();
    long t = T0 + 1_000_000;
    Filepath root = tempRoot();
    writeImport(root, lines);
    writePrefs(root, prefs);
    PluginJournal j = new PluginJournal(root, clock::get, ParityJson::parse, () -> true, logs::add);
    j.expectCharacterNames(true);
    j.characterName(A, "New Name");
    j.start();
    int from = logs.size();
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 1);
    feed(p, 0, 1, j, clock, null);
    check(j.awaitIdle(30000), "drain");
    JsonObject mk = marker(root, A);
    check(mk != null && mk.get("flips").getAsInt() == 3 && mk.get("flipsViaOldNames").getAsInt() == 2 && mk.get("flipsOldNamesRefused").getAsInt() == 3,
      "the marker counts the former names' trades: " + mk + " logs " + logs.subList(from, logs.size()));
    check(logsContaining(from, "2 taken under this character's former names, 3 under refused former names") == 1, "the import line says so: " + logs.subList(from, logs.size()));
    check(logsContaining(from, "3 former name(s)") == 1, "the refusals are logged once: " + logs.subList(from, logs.size()));
    check(fps(j.call(st -> st.state(t)).importedFlips).equals(Arrays.asList("o1", "o2", "o8")), "the store holds them");
    stop(j);
    root.deleteRecursively();
    // No preferences file: exactly as before (and the marker has no old-name fields).
    Filepath root2 = tempRoot();
    writeImport(root2, lines);
    PluginJournal k = new PluginJournal(root2, clock::get, ParityJson::parse, () -> true, logs::add);
    k.expectCharacterNames(true);
    k.characterName(A, "New Name");
    k.start();
    Client p2 = new Client("p2", A);
    p2.sendAll(T0 + 900_000, 1);
    feed(p2, 0, 1, k, clock, null);
    check(k.awaitIdle(30000), "drain");
    JsonObject mk2 = marker(root2, A);
    check(mk2 != null && mk2.get("flips").getAsInt() == 1 && !mk2.has("flipsViaOldNames"), "no preferences file: as before: " + mk2);
    stop(k);
    root2.deleteRecursively();
    // An unreadable preferences file refuses the import (once per account: never run without the names meant).
    Filepath root3 = tempRoot();
    writeImport(root3, lines);
    writePrefs(root3, "{\"oldNames\":{\"old_name\":\"New Name\",}");
    PluginJournal m = new PluginJournal(root3, clock::get, ParityJson::parse, () -> true, logs::add);
    m.expectCharacterNames(true);
    m.characterName(A, "New Name");
    m.start();
    from = logs.size();
    Client p3 = new Client("p3", A);
    p3.sendAll(T0 + 900_000, 2);
    feed(p3, 0, 1, m, clock, null);
    check(m.awaitIdle(30000), "drain");
    check(importedLines(root3, A).isEmpty() && marker(root3, A) == null, "an unreadable preferences file: nothing imported");
    check(logsContaining(from, PluginJournal.PREFERENCES_FILE + " could not be read") == 1, "and said: " + logs.subList(from, logs.size()));
    // Corrected, then the setting switched off and on: imported.
    writePrefs(root3, "{\"oldNames\":{\"old_name\":\"New Name\"}}");
    m.importRequested();
    feed(p3, 1, 2, m, clock, null);
    check(m.awaitIdle(30000), "drain");
    JsonObject mk3 = marker(root3, A);
    check(mk3 != null && mk3.get("flipsViaOldNames").getAsInt() == 1, "corrected and requested again: imported: " + mk3);
    stop(m);
    root3.deleteRecursively();
    // A preferences file that cannot be checked (held by another program): the import waits, spends no attempt.
    Filepath root4 = tempRoot();
    writeImport(root4, lines);
    writePrefs(root4, prefs);
    PluginJournal w = new PluginJournal(root4, clock::get, ParityJson::parse, () -> true, logs::add);
    w.expectCharacterNames(true);
    w.characterName(A, "New Name");
    IOException sharing = new IOException("preferences.json: The process cannot access the file because it is being used by another process");
    w.importFileLook = f -> PluginJournal.PREFERENCES_FILE.equals(f.getFileName()) ? fakeLook(false, sharing, false) : PluginJournal.look(f);
    w.start();
    Client p4 = new Client("p4", A);
    int packets = PluginJournal.IMPORT_TRIES + 2;
    p4.sendAll(T0 + 900_000, packets + 1);
    feed(p4, 0, packets, w, clock, null);
    check(w.awaitIdle(30000), "drain");
    check(importedLines(root4, A).isEmpty() && marker(root4, A) == null, "the preferences file could not be checked: nothing imported yet");
    w.importFileLook = PluginJournal::look;
    feed(p4, packets, packets + 1, w, clock, null);
    check(w.awaitIdle(30000), "drain");
    JsonObject mk4 = marker(root4, A);
    check(mk4 != null && mk4.get("flipsViaOldNames").getAsInt() == 2, "checked again at the next packet, no attempt spent meanwhile: " + mk4);
    stop(w);
    root4.deleteRecursively();
    // An import file with no trades kept by name never needs the names: a broken preferences file does not stop it.
    Filepath root5 = tempRoot();
    writeImport(root5, bj.lines);
    writePrefs(root5, "{broken");
    PluginJournal u = new PluginJournal(root5, clock::get, ParityJson::parse, () -> true, logs::add);
    u.expectCharacterNames(true);
    u.characterName(A, "New Name");
    u.start();
    Client p5 = new Client("p5", A);
    p5.sendAll(T0 + 900_000, 1);
    feed(p5, 0, 1, u, clock, null);
    check(u.awaitIdle(30000), "drain");
    check(importedLines(root5, A).equals(bj.of(A)), "no named trades: the preferences file is not read for names, and the import runs");
    stop(u);
    root5.deleteRecursively();
  }

  static void markerAloneCountsAsImported() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    Filepath root = tempRoot();
    writeImport(root, bj.lines);
    Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
    dir.createDirectories();
    dir.joinSegment(PluginJournal.markerName(A)).write("{\"at\":1}\n".getBytes(StandardCharsets.UTF_8));
    AtomicLong clock = new AtomicLong();
    PluginJournal j = journal(root, clock, () -> true);
    Client p = new Client("p1", A);
    p.sendAll(T0 + 900_000, 1);
    List<String> own = new ArrayList<>();
    int from = logs.size();
    feed(p, 0, 1, j, clock, new Store(r -> own.add(JournalCodec.encodeLine(r))));
    check(j.awaitIdle(30000), "drain");
    check(importedLines(root, A).isEmpty() && logsContaining(from, "EVI journal: imported") == 0,
      "an account with a marker but no imported file was imported again: " + importedLines(root, A).size() + " lines");
    check(fileLines(root, A).equals(own) && own.size() == 1, "the packet was not journalled");
    Client q = new Client("q1", B);
    q.sendAll(T0 + 950_000, 1);
    feed(q, 0, 1, j, clock, null);
    check(j.awaitIdle(30000), "drain");
    check(importedLines(root, B).equals(bj.of(B)), "control: B has no marker and must be imported from the same file");
    stop(j);
    root.deleteRecursively();
  }

  /**
   * writeNew never replaces a file: not one that exists when it is called, and not one another writer
   * creates between its check and its rename (the race a second client's import could hit). Either way
   * it throws JournalFile.AlreadyExists, the other writer's bytes are untouched, and no .tmp is left.
   */
  static void writeNewNeverOverwrites() throws Exception {
    Filepath root = tempRoot();
    byte[] theirs = "{\"from\":\"another client\"}\n".getBytes(StandardCharsets.UTF_8);
    byte[] mine = "{\"from\":\"this client\"}\n".getBytes(StandardCharsets.UTF_8);
    Filepath existing = root.joinSegment("imported-x.jsonl");
    existing.write(theirs);
    try {
      JournalFile.writeNew(existing, mine);
      check(false, "writeNew replaced an existing file");
    } catch (JournalFile.AlreadyExists expected) {
      check(Arrays.equals(JournalFile.readAll(existing), theirs), "an existing file's bytes changed");
    }
    check(!root.joinSegment("imported-x.jsonl.tmp").exists(), "a refused writeNew left its temporary file");
    Filepath raced = root.joinSegment("imported-y.jsonl");
    JournalFile.moveSeam = f -> {
      if (!f.getFileName().equals("imported-y.jsonl")) return;
      try {
        f.write(theirs);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
    try {
      JournalFile.writeNew(raced, mine);
      check(false, "writeNew's rename replaced a file another writer created after the check");
    } catch (JournalFile.AlreadyExists expected) {
      check(Arrays.equals(JournalFile.readAll(raced), theirs), "the other writer's file was replaced: " + new String(JournalFile.readAll(raced), StandardCharsets.UTF_8));
    } finally {
      JournalFile.moveSeam = null;
    }
    check(!root.joinSegment("imported-y.jsonl.tmp").exists(), "a raced writeNew left its temporary file");
    Filepath fresh = root.joinSegment("imported-z.jsonl");
    JournalFile.writeNew(fresh, mine);
    check(Arrays.equals(JournalFile.readAll(fresh), mine) && !root.joinSegment("imported-z.jsonl.tmp").exists(), "an ordinary writeNew did not write exactly its bytes");
    root.deleteRecursively();
  }

  /**
   * Two clients run the import for one account at the same moment and the OTHER one's file lands first:
   * that is the import done by another writer -- said as such, never as a failed attempt -- and its file is
   * what this client then holds. The packet in hand is journalled, and the account is not tried again.
   */
  static void importRaceIsAnotherWritersSuccess() throws Exception {
    BridgeJournal bj = BridgeJournal.build();
    Filepath root = tempRoot();
    writeImport(root, bj.lines);
    StringBuilder sb = new StringBuilder();
    for (String l : bj.of(A)) sb.append(l).append('\n');
    byte[] otherClients = sb.toString().getBytes(StandardCharsets.UTF_8);
    JournalFile.moveSeam = f -> {
      if (!f.getFileName().equals(JournalFile.importedFileName(A)) || f.exists()) return;
      try {
        f.write(otherClients);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
    try {
      AtomicLong clock = new AtomicLong();
      PluginJournal j = journal(root, clock, () -> true);
      Client p = new Client("p1", A);
      p.sendAll(T0 + 900_000, 2);
      List<String> own = new ArrayList<>();
      Store os = new Store(r -> own.add(JournalCodec.encodeLine(r)));
      int from = logs.size();
      feed(p, 0, 1, j, clock, os);
      check(j.awaitIdle(30000), "drain");
      check(logsContaining(from, "another client imported this account") == 1 && logsContaining(from, "the import did not complete") == 0,
        "the other client's import must be reported as done by another writer, not as a failed attempt: " + logs.subList(from, logs.size()));
      check(importedLines(root, A).equals(bj.of(A)), "the imported file is not the other client's");
      check(own.size() == 1 && fileLines(root, A).equals(own), "the packet was lost in the race");
      check(!root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment(PluginJournal.markerName(A)).exists(), "this client wrote counts for an import it did not write");
      List<String> expected = new ArrayList<>(bj.of(A));
      expected.addAll(own);
      check(j.call(PluginJournalTest::offers).equals(offers(replay(expected))), "the store does not hold the other client's import followed by this client's packet");
      feed(p, 1, 2, j, clock, os);
      check(j.awaitIdle(30000), "drain");
      check(logsContaining(from, "import") == 1 && fileLines(root, A).equals(own) && own.size() == 2, "the account was tried again, or a packet was lost: " + logs.subList(from, logs.size()));
      stop(j);
    } finally {
      JournalFile.moveSeam = null;
      root.deleteRecursively();
    }
  }

  /**
   * Another writer appends to this account's file at the moment this journal writes (before its length
   * check, or between its write and the check after it). The journal re-reads and tries again: up to two
   * such interruptions in a row cost nothing, in order; a third is reported, never silent.
   */
  static void concurrentWriteRetry() throws Exception {
    String[][] cases = {{"before", "1"}, {"before", "2"}, {"before", "3"}, {"after", "1"}};
    for (String[] c : cases) {
      String phase = c[0];
      int n = Integer.parseInt(c[1]);
      Filepath root = tempRoot();
      AtomicLong clock = new AtomicLong();
      PluginJournal j = journal(root, clock, () -> false);
      Client a = streamA();
      List<String> own = new ArrayList<>();
      Store ref = new Store(r -> own.add(JournalCodec.encodeLine(r)));
      feed(a, 0, 2, j, clock, ref);
      check(j.awaitIdle(30000), "drain");
      Client x = new Client("ext", A);
      x.sendAll(T0 + 30_000, n);
      List<String> ext = new ArrayList<>();
      Store xs = new Store(r -> ext.add(JournalCodec.encodeLine(r)));
      feed(x, 0, n, null, new AtomicLong(), xs);
      check(ext.size() == n, "setup: " + ext.size() + " external lines");
      AtomicInteger used = new AtomicInteger();
      JournalFile.appendSeam = (f, at) -> {
        if (!at.equals(phase) || !f.getFileName().equals(JournalFile.fileName(A)) || used.get() >= n) return;
        try {
          JournalFile.appendText(f, ext.get(used.getAndIncrement()) + "\n");
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      };
      int from = logs.size();
      try {
        feed(a, 2, 3, j, clock, ref);
        check(j.awaitIdle(30000), "drain");
      } finally {
        JournalFile.appendSeam = null;
      }
      String what = phase + " x" + n + ": ";
      check(used.get() == n, what + "the other writer appended " + used.get() + " times");
      List<String> expected = new ArrayList<>(own.subList(0, 2));
      if (phase.equals("after")) {
        expected.add(own.get(2));
        expected.addAll(ext);
      } else {
        expected.addAll(ext);
        if (n <= 2) expected.add(own.get(2));
      }
      check(fileLines(root, A).equals(expected), what + "the file is not the lines in the order they were written: " + fileLines(root, A).size() + " lines, expected " + expected.size());
      int lost = logsContaining(from, "could not journal a packet");
      check(lost == (n <= 2 ? 0 : 1), what + (n <= 2 ? "a retry was not absorbed" : "the third interruption was not reported") + ": " + logs.subList(from, logs.size()));
      check(j.call(PluginJournalTest::offers).equals(offers(diskReplay(root))), what + "the store differs from what is on disk");
      stop(j);
      root.deleteRecursively();
    }
  }

  /**
   * The plugin switched off and straight on again while the old journal still has packets queued (held
   * here by a parser that waits on a gate). The new journal is started with the old one as predecessor and
   * waits for it on its OWN thread: every queued packet is journalled, in the order it was handed over,
   * and the new session's relog links to it. Without the handover the new journal writes first.
   */
  static void toggleHandsOver() throws Exception {
    Filepath root = tempRoot();
    AtomicLong c1 = new AtomicLong(), c2 = new AtomicLong();
    CountDownLatch gate = new CountDownLatch(1);
    Function<String, JsonElement> gated = s -> {
      try {
        if (!gate.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("the test never opened the gate");
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      return ParityJson.parse(s);
    };
    PluginJournal j1 = new PluginJournal(root, c1::get, gated, () -> false, logs::add);
    j1.start();
    Client a = streamA(), a2 = streamA2();
    List<String> refLines = new ArrayList<>();
    Store ref = new Store(r -> refLines.add(JournalCodec.encodeLine(r)));
    feed(a, 0, a.packets.size(), j1, c1, ref); // queued in the old journal, held at its first packet
    j1.shutdown();                             // the plugin is switched off...
    PluginJournal j2 = new PluginJournal(root, c2::get, ParityJson::parse, () -> false, logs::add);
    j2.start(j1);                              // ...and on again, before the old queue has drained
    feed(a2, 0, a2.packets.size(), j2, c2, ref);
    int from = logs.size();
    // Given two seconds, the new journal is still waiting for the old one (it must not get ahead of it).
    boolean ranAhead;
    try {
      ranAhead = j2.awaitIdle(2000);
    } catch (java.util.concurrent.TimeoutException waiting) {
      ranAhead = false;
    }
    check(!ranAhead, "the new journal ran ahead of the old one's queued packets");
    gate.countDown();
    check(j2.awaitIdle(60000), "the new journal did not drain");
    check(j1.awaitIdle(1000), "the old journal had not finished when the new one did");
    check(fileLines(root, A).equals(refLines), "after an off/on the file is not every packet in hand-over order: "
      + fileLines(root, A).size() + " lines, expected " + refLines.size());
    long t = T0 + 700_000;
    check(j2.call(s -> s.state(t)).linkedLoginOffers == 2, "the new session's relog did not link to the old journal's offers");
    check(logsContaining(from, "could not journal") == 0 && logsContaining(from, "still writing") == 0, "the handover was not clean: " + logs.subList(from, logs.size()));
    stop(j2);
    root.deleteRecursively();
  }

  /** A journal file operation attempted off the journal thread is refused, not performed. */
  static void wrongThreadRefused() throws Exception {
    Filepath root = tempRoot();
    PluginJournal j = journal(root, new AtomicLong(), () -> false);
    check(j.awaitIdle(30000), "drain");
    for (String name : new String[]{"sync", "rebuild"}) {
      try {
        if ("sync".equals(name)) j.sync();
        else j.rebuild();
        check(false, name + "() ran on the caller's thread");
      } catch (IllegalStateException e) {
        check(e.getMessage().contains("not " + PluginJournal.THREAD_NAME), name + "() off-thread: wrong refusal " + e);
      }
    }
    stop(j);
    check(j.isShutdown(), "shutdown() did not shut the executor");
    root.deleteRecursively();
  }
}
