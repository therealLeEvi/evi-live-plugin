package com.evi.live.journal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import net.runelite.client.util.Filepath;

/**
 * The plugin's OWN trade journal: every Grand Exchange packet the plugin observes, recorded into a Java {@link Store} (ported
 * with parity from the old companion app's store.mjs), persisted in the same events.jsonl line format, and reloaded at
 * startup. The engine reads everything it knows about the player's trades from here.
 *
 * <h3>Threads</h3>
 * Every file operation and every touch of the Store happens on ONE plugin-owned thread,
 * {@value #THREAD_NAME}, and is checked there (a file operation anywhere else throws). The client thread
 * only hands over the packet text it built. {@link #shutdown()} uses {@code shutdown()}, never {@code shutdownNow()}: queued
 * writes finish, and no thread is ever interrupted (Thread.interrupt is on the Hub's forbidden list).
 *
 * <p>HANDOVER: when the plugin is switched off and on again, the old journal may still be finishing its
 * queue. The new one is started with the old one as its predecessor ({@link #start(PluginJournal)}), and its
 * own thread -- never the caller's -- waits (bounded) for the old one to finish before it reads anything, so
 * the two never write one file at the same time and every queued packet lands in its original order.
 *
 * <h3>Files</h3> (in the plugin's data folder, {@link PluginFolder#PATH}, through {@link Filepath} only)
 * <ul>
 *   <li>{@code journal/events-<account>.jsonl}: one per account, so two clients never share a file.
 *       APPEND-ONLY: never rewritten, so another client can follow it by the bytes it has read.
 *       The Store is MACHINE-WIDE like the bridge's (every account's file is replayed into it, which is
 *       what the profit line sums), and it re-reads files another client has appended to before it acts.</li>
 *   <li>{@code journal/imported-<account>.jsonl}: the one-time import's result -- the bridge's records for
 *       that account -- created once under a new name and never changed. Replayed BEFORE the account's
 *       events file, skipping any event the import already holds ({@link BridgeImport#merge}).</li>
 *   <li>{@code import/events.jsonl}: a copy of the bridge's data/events.jsonl, placed there BY THE PLAYER.
 *       Read only while the "Import old trade history" setting is on, once per account (the imported
 *       file records that it happened; {@code journal/import-done-<account>.json} holds its counts).</li>
 *   <li>{@code import/identity-salt.txt} (optional, 9 Oct 2026): the FORMER data folder's salt, placed there by the player. The
 *       bridge journalled each account under the id that salt gives ({@link AccountIds}); a new data folder has a new salt, so
 *       its ids match none of the bridge's records. When present, the import selects this account's records by the id the old
 *       salt gives and files them under the account's current id. Read only, never changed; absent, the import is as before.</li>
 * </ul>
 *
 * <h3>The sidebar's marks (SELF-CONTAINED decision 12)</h3>
 * Personal use (a buy, or an item: "Yours, not stock") and "I don't have this anymore" are the SAME records the bridge
 * journals (store.mjs markPersonalUse / markPersonalUseItem / closePosition, through the same {@link Store}), written to an
 * events file like every other record, so they survive a restart exactly as the bridge's do and the machine-wide Store
 * replays them as the bridge's does. A record names no account of its own: it goes to the file of the account that OWNS the
 * offer it names (as {@link BridgeImport} attributes it), else -- an item mark names no offer -- to the file of the account
 * that pressed the button. Nothing reaches a file without an account.
 */
public final class PluginJournal {
  public static final String THREAD_NAME = "evi-journal";
  public static final String JOURNAL_DIR = "journal";
  public static final String IMPORT_DIR = "import";
  /** The bridge's preferences copied beside the import file; this journal reads only its {@code oldNames} ({@link OldNames}). */
  static final String PREFERENCES_FILE = "preferences.json";
  /** A preferences file is a few hundred bytes; anything past this is not one. */
  static final long PREFERENCES_MAX_BYTES = 1024 * 1024;
  public static final String IMPORT_FILE = "events.jsonl";
  /** The former data folder's identity salt, copied beside the import file by the player (optional; see the class note). */
  static final String SALT_FILE = "identity-salt.txt";
  /** A salt is a 36-character UUID; anything past this is not one. */
  static final long SALT_MAX_BYTES = 4096;
  /** The log hint when no record matched and no salt file was given (the README names the old folder). */
  static final String SALT_HINT = "copy " + SALT_FILE + " from the older EVI version's plugin-data folder";
  /**
   * The sidebar line (the maintainer's decision, 6 Oct 2026) shown ONLY while the import setting is on, the account
   * has not been imported, and there is no import file. Plain and neutral: it says what is missing and where it
   * goes, nothing more.
   */
  public static final String IMPORT_MISSING_NOTICE = "Import is on, but no file was found at " + PluginFolder.PATH + "/"
    + IMPORT_DIR + "/" + IMPORT_FILE + ".";
  private static final int MAX_PENDING = 4096;
  /** How long a new journal's thread waits for its predecessor to finish its queue before going on anyway. */
  static final long HANDOVER_WAIT_MS = 30_000;
  /** An import that failed on file I/O (a locked or blocked file) is retried at later packets, this many times a session. */
  static final int IMPORT_TRIES = 3;
  /**
   * The largest import file read (it is read whole). The bridge refuses to append past 100 MB, so its
   * journal is at most that plus one line; anything larger is not a bridge journal. Tests lower it.
   */
  static volatile long importMaxBytes = JournalFile.MAX_BYTES + 1024 * 1024;

  /**
   * What a look at the import file found. UNKNOWN: it could not be told -- isFile() said no, but the file system did not say
   * the file is ABSENT either (an I/O error reading its attributes, such as a sharing violation while a virus scan holds it).
   * Found 8 Oct 2026: isFile() (Files.isRegularFile, which answers false on ANY I/O error) twice read a 10 MB import file that
   * was there as missing, and the sidebar said "no file was found". Only proven absence is "missing" now; UNKNOWN shows no line,
   * logs nothing about a missing file, counts no import attempt, and is looked at again at the next packet.
   */
  enum ImportFileState { PRESENT, ABSENT, UNKNOWN }

  /** The three looks {@link #importFileState} takes at a file. A seam: a test drives UNKNOWN with no real lock. */
  interface FileLook {
    boolean isFile();

    /** Reads the file's attributes: throws NoSuchFileException when it is not there, another IOException when it cannot tell. */
    void attributes() throws IOException;

    boolean isDirectory();
  }

  /** PRESENT when it is a file; ABSENT only on proof (no such file, or a folder in its place); UNKNOWN otherwise. */
  static ImportFileState importFileState(FileLook f) {
    if (f.isFile()) return ImportFileState.PRESENT;
    try {
      f.attributes();
    } catch (NoSuchFileException e) {
      return ImportFileState.ABSENT;
    } catch (IOException | RuntimeException e) {
      return ImportFileState.UNKNOWN;
    }
    return f.isDirectory() ? ImportFileState.ABSENT : ImportFileState.UNKNOWN;
  }

  static FileLook look(Filepath f) {
    return new FileLook() {
      @Override
      public boolean isFile() {
        return f.isFile();
      }

      @Override
      public void attributes() throws IOException {
        f.getLastModifiedTime();
      }

      @Override
      public boolean isDirectory() {
        return f.isDirectory();
      }
    };
  }

  /** TEST SEAM: how the import file is looked at. Production: {@link #look}. */
  volatile Function<Filepath, FileLook> importFileLook = PluginJournal::look;

  /** TEST SEAM: told the current thread's name at every file operation. Null in production. */
  static volatile Consumer<String> ioThreadSeam;

  private final Filepath root;
  private final Filepath dir;
  private final LongSupplier clock;
  private final Function<String, JsonElement> parser;
  private final BooleanSupplier importEnabled;
  private final Consumer<String> log;
  private final ThreadPoolExecutor executor;
  private volatile Thread thread;
  private volatile boolean closed;
  /**
   * Whether the import also takes the bridge's item-level marks ("Yours, not stock") for every importing account: the built-in
   * engine only (8 Oct 2026); off, the import is exactly what it was. Read when an import runs.
   */
  private volatile BooleanSupplier importItemMarks = () -> false;
  /**
   * Each account's character DISPLAY NAME, as the plugin learned it at login (any thread may tell it). The import takes the
   * bridge's flip histories from another tracker -- kept by display name -- for the character of that name only.
   */
  private final Map<String, String> characterNames = new java.util.concurrent.ConcurrentHashMap<>();
  /** Whether the plugin tells the character names (it does); an import that needs one then waits for it. */
  private volatile boolean namesExpected;
  /** Each account's RS profile and economy scope, as the plugin combined them with its salt (any thread may tell it). */
  private final Map<String, String[]> identities = new java.util.concurrent.ConcurrentHashMap<>();
  /** Told the import line to show (the text) or hide (null), on the journal thread, only when it changes. */
  private volatile Consumer<String> importNotice;

  // ---- owned by the journal thread
  private Store store;
  /** Bytes of each account's events file already applied to the store (complete lines only). */
  private final Map<String, Long> consumed = new TreeMap<>();
  /**
   * The last line applied from each account's events file, as bytes. Before reading on from
   * {@link #consumed}, sync checks those bytes are still where they were: a file that was REPLACED rather
   * than appended to (by anything outside EVI) is then re-read whole instead of being read from the middle.
   */
  private final Map<String, byte[]> tails = new TreeMap<>();
  /**
   * Each account's imported file as it was when the store was built ({@link JournalFile.Fingerprint}: length,
   * time and both ends). Any change -- grown, SHRUNK or replaced -- rebuilds the store from disk.
   */
  private final Map<String, JournalFile.Fingerprint> importedPrints = new TreeMap<>();
  /** Per account, the events its imported file already holds (skipped in the events file, as merge skips them). */
  private final Map<String, BridgeImport.Dedup> imported = new TreeMap<>();
  /** Accounts whose file has a line that does not decode: never written, never applied. */
  private final Set<String> broken = new TreeSet<>();
  /** Accounts whose imported file does not decode: logged once, left out. */
  private final Set<String> brokenImports = new TreeSet<>();
  /** Accounts this process has started writing (an interrupted tail is recovered first). */
  private final Set<String> claimed = new TreeSet<>();
  /** Accounts known to be imported already (their imported file or marker exists). */
  private final Set<String> importDone = new TreeSet<>();
  /** Import attempts this session per account; at {@link #IMPORT_TRIES} the account is not tried again. */
  private final Map<String, Integer> importTries = new TreeMap<>();
  /** Accounts already told there is no import file, so the log line is not repeated at every packet. */
  private final Set<String> importMissingLogged = new TreeSet<>();
  /** Accounts whose import file could not be checked (UNKNOWN), logged once a session. */
  private final Set<String> importUnknownLogged = new TreeSet<>();
  /** Accounts whose import waits for the character's name (the import file holds flips kept by name). */
  private final Set<String> waitingForName = new TreeSet<>();
  /** Accounts whose import waits for the account's identity (an import/identity-salt.txt is present), logged once. */
  private final Set<String> waitingForIdentity = new TreeSet<>();
  /** The account of the last packet: the one the import line speaks about between packets. */
  private String lastAccount;
  /** What the import line was last told, and whether it has been told anything yet. */
  private String noticeShown;
  private boolean noticeSent;
  private boolean rebuildNeeded = true;
  private String failure;
  /** The account that pressed a sidebar mark being written right now (journal thread only), or null between marks. */
  private String markAccount;

  /**
   * @param root           the plugin's data folder ({@code Plugin.getPluginDirectory()})
   * @param clock          epoch milliseconds; read where a packet is handed over, as the bridge reads its clock on receipt
   * @param parser         JSON text to a tree: the plugin passes the client's injected Gson
   * @param importEnabled  the opt-in import setting, read each time it could apply
   * @param log            one line of local diagnostics (the client log)
   */
  public PluginJournal(Filepath root, LongSupplier clock, Function<String, JsonElement> parser, BooleanSupplier importEnabled,
                       Consumer<String> log) {
    this.root = root;
    this.dir = root.joinSegment(JOURNAL_DIR);
    this.clock = clock;
    this.parser = parser;
    this.importEnabled = importEnabled;
    this.log = log;
    this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(MAX_PENDING), r -> {
      Thread t = new Thread(r, THREAD_NAME);
      t.setDaemon(true); // never holds the client open
      thread = t;
      return t;
    });
  }

  // ------------------------------------------------------------------------------------- any thread

  /** Loads every account's journal, on the journal thread. */
  public void start() {
    start(null);
  }

  /**
   * Loads every account's journal, on the journal thread, after {@code predecessor} -- the journal this one
   * replaces, when the plugin was switched off and on -- has finished every packet it had queued. The wait
   * runs on THIS journal's thread (packets handed over meanwhile queue behind it), never on the caller's,
   * and is bounded by {@value #HANDOVER_WAIT_MS} ms; past that this journal goes on, and whatever the old one
   * still writes is read as any other writer's appends are.
   */
  public void start(PluginJournal predecessor) {
    final PluginJournal before = predecessor == this ? null : predecessor;
    if (before != null) before.shutdown(); // a predecessor takes no new work; it only finishes what it has
    submit(() -> {
      if (before != null && !before.executor.awaitTermination(HANDOVER_WAIT_MS, TimeUnit.MILLISECONDS)) {
        log.accept("EVI journal: the previous journal was still writing after " + HANDOVER_WAIT_MS / 1000
          + " s; going on, and its remaining lines are read as another writer's");
      }
      ioCheck();
      dir.createDirectories();
      rebuild();
    });
  }

  /**
   * One packet, exactly the text the plugin built from the slots. Called on the client thread: only the hand-over
   * happens here; parsing, the store and the file are all on the journal thread.
   */
  public void offerPacket(String json) {
    if (json == null) return;
    final long now = clock.getAsLong();
    submit(() -> ingest(json, now));
  }

  /**
   * Where the import line goes: {@code listener} is told {@link #IMPORT_MISSING_NOTICE} to show it and null to
   * hide it, on the journal thread, and only when that changes. Set it before {@link #start}.
   */
  public void importItemMarks(BooleanSupplier enabled) {
    importItemMarks = enabled == null ? () -> false : enabled;
  }

  /**
   * The character's display name for {@code account} (the plugin tells it at login, before that account's first packet). The
   * one-time import takes the bridge's flip histories from another tracker made under this name only (see BridgeImport). Any
   * thread; a null or blank name is ignored.
   */
  public void characterName(String account, String name) {
    if (account == null || CharacterNames.key(name) == null) return;
    characterNames.put(account, name);
  }

  /**
   * What {@code account}'s id was made from besides the salt ({@link AccountIds#of}): its RS profile and economy scope. The import
   * needs it only when the player placed a former folder's {@code import/identity-salt.txt}, to name this account's bridge records
   * by the id that salt gives. Any thread; only stored. A null account or empty profile is ignored.
   */
  public void accountIdentity(String account, String profile, String economy) {
    if (account == null || profile == null || profile.isEmpty()) return;
    identities.put(account, new String[]{profile, economy == null ? "" : economy});
  }

  /**
   * Whether names will be told ({@link #characterName}). The plugin says yes: an import whose file holds flips kept by name then
   * WAITS for the character's name rather than run without it -- the import happens once per account, so running without the
   * name would lose that history for good. Off (the default, and every caller that never names a character): the import runs at
   * once and takes no such flip.
   */
  public void expectCharacterNames(boolean expected) {
    namesExpected = expected;
  }

  public void onImportNotice(Consumer<String> listener) {
    importNotice = listener;
  }

  /**
   * The import setting changed. Switched on (again), accounts not yet imported are tried at their next packet.
   * Either way the import line is brought up to date at once for the last account seen, not left until a packet.
   */
  public void importRequested() {
    submit(() -> {
      importDone.clear();
      importTries.clear();
      importMissingLogged.clear();
      importUnknownLogged.clear();
      waitingForName.clear();
      waitingForIdentity.clear();
      String a = lastAccount;
      ImportFileState w = a == null ? null : waitingForImportFile(a);
      if (w != ImportFileState.UNKNOWN) notice(w == ImportFileState.ABSENT ? IMPORT_MISSING_NOTICE : null);
    });
  }

  /**
   * The sidebar's "Personal use" on a held buy (store.mjs markPersonalUse with personal:true). Returns at once; the future
   * completes, on the journal thread, with null when the mark is in the journal (or already was), or with the refusal the
   * bridge would have answered 400 with (an unknown buy, an invalid account...). Never throws to the caller.
   */
  public java.util.concurrent.CompletableFuture<String> markPersonalUse(String account, String buyId) {
    return mark(account, s -> s.markPersonalUse(buyId, true));
  }

  /**
   * "Yours, not stock" on idle stock (store.mjs markPersonalUseItem with personal:true): {@code kept} is the count the bridge's
   * route reads from the last bag (null: none, which is the blanket mark the plugin's own request has always made). See
   * {@link #markPersonalUse} for the future.
   */
  public java.util.concurrent.CompletableFuture<String> markPersonalUseItem(String account, int itemId, Double kept) {
    return mark(account, s -> {
      JsonObject a = new JsonObject();
      a.addProperty("itemId", itemId);
      a.addProperty("personal", true);
      if (kept != null) a.addProperty("kept", kept);
      s.markPersonalUseItem(a);
    });
  }

  /** "I don't have this anymore" (store.mjs closePosition, reason "sold-untracked" as the plugin sends it). See {@link #markPersonalUse}. */
  public java.util.concurrent.CompletableFuture<String> closePosition(String account, String buyId) {
    final long now = clock.getAsLong();
    return mark(account, s -> s.closePosition(buyId, "sold-untracked", now));
  }

  private interface Mark {
    void apply(Store s);
  }

  private java.util.concurrent.CompletableFuture<String> mark(String account, Mark m) {
    java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
    if (closed) {
      f.complete("the journal is shut down");
      return f;
    }
    try {
      executor.execute(() -> {
        try {
          ioCheck();
          if (failure != null) {
            f.complete("the journal stopped: " + failure);
            return;
          }
          sync();
          if (store == null) {
            f.complete("the journal is not loaded");
            return;
          }
          markAccount = account;
          try {
            for (int attempt = 0; ; attempt++) {
              try {
                m.apply(store);
                break;
              } catch (JournalFile.ConcurrentWrite e) {
                if (attempt >= 2) throw e;
                sync(); // another client wrote: re-read, then try again on the current state
              }
            }
          } finally {
            markAccount = null;
          }
          f.complete(null);
        } catch (JournalException e) {
          log.accept("EVI journal: a sidebar mark was refused: " + e.getMessage());
          f.complete(e.getMessage());
        } catch (Throwable e) { // never reaches the game or the Swing thread
          log.accept("EVI journal: a sidebar mark could not be saved: " + e);
          f.complete(String.valueOf(e));
        }
      });
    } catch (RejectedExecutionException e) {
      f.complete("the journal's queue is full");
    }
    return f;
  }

  /**
   * What the plugin's engine reads from the journal at one moment: one {@code store.state(now)}
   * and a copy of the list of every offer (each offer is immutable), taken together on the journal thread.
   */
  public static final class EngineView {
    public final StoreState state;
    /** Every account's offers, in the store's order ({@code store.offers()}). */
    public final List<Offer> offers;
    /** How many accounts the store was built from. */
    public final int accounts;

    EngineView(StoreState state, List<Offer> offers, int accounts) {
      this.state = state;
      this.offers = java.util.Collections.unmodifiableList(offers);
      this.accounts = accounts;
    }
  }

  /**
   * DEVELOPER SHADOW MODE ONLY: the journal as the engine reads it at {@code now}, built on the journal thread after catching
   * up with every file (as a packet would). Returns at once. The future completes with null when the journal has stopped (it
   * did not replay), and exceptionally when it is shut down or its queue is full. Reads; never writes.
   */
  public java.util.concurrent.CompletableFuture<EngineView> engineView(long now) {
    java.util.concurrent.CompletableFuture<EngineView> f = new java.util.concurrent.CompletableFuture<>();
    if (closed) {
      f.completeExceptionally(new IllegalStateException("the journal is shut down"));
      return f;
    }
    try {
      executor.execute(() -> {
        try {
          ioCheck();
          sync();
          f.complete(store == null ? null : new EngineView(store.state(now), new ArrayList<>(store.offers()), knownAccounts()));
        } catch (Throwable t) { // reaches the caller's future, never the game
          f.completeExceptionally(t);
        }
      });
    } catch (RejectedExecutionException e) {
      f.completeExceptionally(e);
    }
    return f;
  }

  /** What {@link #exportTradeLog} saved: the file and how many offers and flips it holds. */
  public static final class SavedLog {
    public final Filepath file;
    public final int offers;
    public final int flips;

    SavedLog(Filepath file, int offers, int flips) {
      this.file = file;
      this.offers = offers;
      this.flips = flips;
    }
  }

  /**
   * The sidebar's "Save my trade log..." (9 Oct 2026, voluntary sharing): every account's journal as {@link TradeLogExport}
   * writes it, saved to {@code share/<fileName>} in the plugin's data folder, built and written on the journal thread after
   * catching up with every file (as a packet would). A file of the same name is REPLACED (the same day's export, refreshed),
   * whole or not at all: it is written to a temporary file first and renamed over it. Returns at once; the future completes
   * with what was saved, or exceptionally (the file could not be written, the journal stopped, or it never replayed).
   * Reads the journal; writes only that one file. Nothing is sent anywhere.
   *
   * <p>{@code linker} (null: none) names, by offer id, the offers linked to a suggestion EVI showed (the suggestion-outcome join,
   * which the caller owns); it runs here, on the journal thread, with the same state and offers the file is built from. A linker
   * that throws is logged and the file is saved without links, never with a guessed one.
   */
  public java.util.concurrent.CompletableFuture<SavedLog> exportTradeLog(String fileName, long now,
      java.util.function.BiFunction<StoreState, java.util.Collection<Offer>, Map<String, TradeLogExport.Link>> linker) {
    java.util.concurrent.CompletableFuture<SavedLog> f = new java.util.concurrent.CompletableFuture<>();
    if (closed) {
      f.completeExceptionally(new IllegalStateException("the journal is shut down"));
      return f;
    }
    try {
      executor.execute(() -> {
        try {
          ioCheck();
          sync();
          if (store == null || failure != null) throw new IllegalStateException(failure != null ? failure : "EVI's trade record is not loaded");
          StoreState state = store.state(now);
          List<Offer> offers = new ArrayList<>(store.offers());
          Map<String, TradeLogExport.Link> links = null;
          if (linker != null) {
            try {
              links = linker.apply(state, java.util.Collections.unmodifiableList(offers));
            } catch (RuntimeException e) {
              log.accept("EVI trade log: saved without EVI's suggested prices, which could not be read: " + e);
            }
          }
          TradeLogExport.Csv csv = TradeLogExport.of(state, offers, links);
          Filepath folder = root.joinSegment(TradeLogExport.SHARE_DIR);
          folder.createDirectories();
          Filepath target = folder.joinSegment(fileName);
          Filepath tmp = folder.joinSegment(fileName + ".tmp");
          tmp.deleteIfExists();
          try {
            tmp.write(csv.bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
          } finally {
            tmp.deleteIfExists();
          }
          f.complete(new SavedLog(target, csv.offers, csv.flips));
        } catch (Throwable t) { // reaches the caller's future, never the game
          f.completeExceptionally(t);
        }
      });
    } catch (RejectedExecutionException e) {
      f.completeExceptionally(e);
    }
    return f;
  }

  /** Stops accepting work; queued writes still finish. Never interrupts. */
  public void shutdown() {
    closed = true;
    executor.shutdown();
  }

  public boolean isShutdown() {
    return executor.isShutdown();
  }

  /** Tests and diagnostics only (it blocks): waits for every task queued so far. Never call on the client thread. */
  public boolean awaitIdle(long timeoutMs) throws Exception {
    if (Thread.currentThread() == thread) throw new IllegalStateException("awaitIdle on the journal thread");
    if (executor.isShutdown()) return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    executor.submit(() -> { }).get(timeoutMs, TimeUnit.MILLISECONDS);
    return true;
  }

  /** Tests only: run {@code task} on the journal thread with the store current, and return its answer. */
  <T> T call(Function<Store, T> task) throws Exception {
    Callable<T> c = () -> {
      ioCheck();
      sync();
      return store == null ? null : task.apply(store);
    };
    Future<T> f = executor.submit(c);
    return f.get(30, TimeUnit.SECONDS);
  }

  /** Tests only: why journalling stopped, or null. */
  String failure() throws Exception {
    return executor.submit(() -> failure).get(30, TimeUnit.SECONDS);
  }

  /** A unit of journal-thread work; may throw, and whatever it throws is logged, never propagated. */
  private interface Task {
    void run() throws Exception;
  }

  private void submit(Task task) {
    if (closed) return;
    try {
      executor.execute(() -> {
        try {
          task.run();
        } catch (Throwable t) { // a failure here must never reach the game or the bridge path
          log.accept("EVI journal: " + t);
        }
      });
    } catch (RejectedExecutionException e) {
      if (!closed) log.accept("EVI journal: work queue full; a packet was not journalled");
    }
  }

  // -------------------------------------------------------------------------------- journal thread

  private void ioCheck() {
    Thread t = Thread.currentThread();
    Consumer<String> seam = ioThreadSeam;
    if (seam != null) seam.accept(t.getName());
    if (t != thread) throw new IllegalStateException("EVI journal file work attempted on " + t.getName() + ", not " + THREAD_NAME);
  }

  private Filepath file(String account) {
    return dir.joinSegment(JournalFile.fileName(account));
  }

  private List<JournalRecord> decode(byte[] bytes) {
    List<JournalRecord> out = new ArrayList<>();
    for (String line : JournalFile.lines(bytes)) out.add(JournalCodec.decode(parser.apply(line)));
    return out;
  }

  private static byte[] encode(List<JournalRecord> records) {
    StringBuilder sb = new StringBuilder();
    for (JournalRecord r : records) sb.append(JournalCodec.encodeLine(r)).append('\n');
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private Filepath importedFile(String account) {
    return dir.joinSegment(JournalFile.importedFileName(account));
  }

  /** Every account the store was built from: an events file, an imported file, or both. */
  private int knownAccounts() {
    Set<String> all = new TreeSet<>(consumed.keySet());
    all.addAll(importedPrints.keySet());
    return all.size();
  }

  /**
   * Replays every account into a fresh store: its imported file first, then its events file minus what the
   * import already holds. An events file with an unreadable line is left out (with its imported file) and
   * never written; an imported file with an unreadable line is left out on its own.
   */
  void rebuild() throws IOException { // package-private only so a test can call it off the journal thread and see it refused
    ioCheck();
    List<String> eventAccounts = JournalFile.accounts(dir);
    List<String> importedAccounts = JournalFile.importedAccounts(dir);
    Set<String> accounts = new TreeSet<>(eventAccounts);
    accounts.addAll(importedAccounts);
    List<JournalRecord> all = new ArrayList<>();
    Map<String, Long> sizes = new TreeMap<>();
    Map<String, byte[]> lastLines = new TreeMap<>();
    Map<String, JournalFile.Fingerprint> impPrints = new TreeMap<>();
    Map<String, BridgeImport.Dedup> dedups = new TreeMap<>();
    Set<String> bad = new TreeSet<>();
    Set<String> badImports = new TreeSet<>();
    for (String a : accounts) {
      List<JournalRecord> imp = new ArrayList<>();
      if (importedAccounts.contains(a)) {
        Filepath g = importedFile(a);
        impPrints.put(a, JournalFile.fingerprint(g)); // taken BEFORE the read: a change during it shows at the next sync
        try {
          imp = decode(JournalFile.readComplete(g, 0));
        } catch (RuntimeException e) {
          imp = new ArrayList<>();
          badImports.add(a);
          if (!brokenImports.contains(a)) log.accept("EVI journal: " + JournalFile.importedFileName(a) + " has an unreadable line and is left out: " + e.getMessage());
        }
      }
      List<JournalRecord> ev = new ArrayList<>();
      if (eventAccounts.contains(a)) {
        byte[] b = JournalFile.readComplete(file(a), 0);
        try {
          ev = decode(b);
        } catch (RuntimeException e) {
          bad.add(a);
          if (!broken.contains(a)) log.accept("EVI journal: " + JournalFile.fileName(a) + " has an unreadable line and is left untouched: " + e.getMessage());
          continue;
        }
        sizes.put(a, (long) b.length);
        lastLines.put(a, JournalFile.lastLine(b, b.length));
      }
      dedups.put(a, BridgeImport.Dedup.of(imp));
      all.addAll(BridgeImport.merge(imp, ev));
    }
    try {
      store = Store.replay(all, this::write);
    } catch (RuntimeException e) {
      store = null;
      failure = "the journal does not replay: " + e;
      log.accept("EVI journal: stopped -- " + failure);
      throw e;
    }
    consumed.clear();
    consumed.putAll(sizes);
    tails.clear();
    tails.putAll(lastLines);
    importedPrints.clear();
    importedPrints.putAll(impPrints);
    imported.clear();
    imported.putAll(dedups);
    broken.clear();
    broken.addAll(bad);
    brokenImports.clear();
    brokenImports.addAll(badImports);
    claimed.clear();
    rebuildNeeded = false;
  }

  /**
   * Brings the store up to date with every file: new lines another client appended are applied in order.
   * Anything that is not a plain append -- an imported file appearing or changing, an events file whose
   * last-read line is no longer where it was (shrunk or replaced), or new lines that do not decode --
   * rebuilds the whole store from disk instead, so a read never starts in the middle of a line.
   */
  void sync() throws IOException { // package-private only so a test can call it off the journal thread and see it refused
    ioCheck();
    if (failure != null) return;
    if (rebuildNeeded || store == null) {
      rebuild();
      return;
    }
    List<String> imps = JournalFile.importedAccounts(dir);
    if (!imps.equals(new ArrayList<>(importedPrints.keySet()))) {
      rebuild(); // another client ran its import (or an imported file was removed)
      return;
    }
    for (String a : imps) {
      if (!JournalFile.fingerprint(importedFile(a)).equals(importedPrints.get(a))) { // grown, shrunk or replaced
        rebuild();
        return;
      }
    }
    List<String> accounts = JournalFile.accounts(dir);
    for (String a : consumed.keySet()) {
      if (!accounts.contains(a)) {
        rebuild();
        return;
      }
    }
    for (String a : accounts) {
      if (broken.contains(a)) continue;
      Filepath f = file(a);
      long size = JournalFile.size(f);
      long have = consumed.getOrDefault(a, 0L);
      if (size == have) continue; // EVI never rewrites an events file, so an unchanged length is an unchanged file
      // Re-read the last line already applied together with what follows it. If those bytes are no longer
      // where they were -- the file shrank, or was replaced by different content of any length -- it was
      // not appended to, and the store is rebuilt from disk rather than read on from the middle of a line.
      byte[] tail = tails.getOrDefault(a, new byte[0]);
      byte[] b = JournalFile.readComplete(f, have - tail.length);
      if (b.length < tail.length || !Arrays.equals(b, 0, tail.length, tail, 0, tail.length)) {
        rebuild();
        return;
      }
      if (b.length == tail.length) continue; // only part of a line so far
      byte[] fresh = Arrays.copyOfRange(b, tail.length, b.length);
      try {
        BridgeImport.Dedup d = imported.get(a);
        for (JournalRecord r : decode(fresh)) if (d == null || !d.contains(r)) store.apply(r);
      } catch (RuntimeException e) {
        rebuild(); // re-read everything (an unreadable line marks only its own file) rather than drop the work in hand
        return;
      }
      consumed.put(a, have + fresh.length);
      tails.put(a, JournalFile.lastLine(fresh, fresh.length));
    }
  }

  /** The Store's journal: each record goes to its account's file, or nowhere. Never a shared file. */
  private void write(JournalRecord r) {
    ioCheck();
    String account;
    if (r instanceof JournalRecord.PacketRecord) account = ((JournalRecord.PacketRecord) r).packet.account;
    else if (r instanceof JournalRecord.PurchaseRecorded) account = ((JournalRecord.PurchaseRecorded) r).account;
    else if (markAccount != null && (r instanceof JournalRecord.PersonalUse || r instanceof JournalRecord.PositionClosed))
      account = ownerOr(r instanceof JournalRecord.PersonalUse ? ((JournalRecord.PersonalUse) r).buyId : ((JournalRecord.PositionClosed) r).buyId, markAccount);
    else if (markAccount != null && r instanceof JournalRecord.PersonalUseItem) account = markAccount;
    else throw new JournalException("A " + r.type() + " record names no account; the plugin journal does not write it yet");
    if (!Packet.id(account)) throw new JournalException("Invalid account");
    if (broken.contains(account)) throw new JournalException("This account's journal file has an unreadable line, so EVI will not write to it");
    Filepath f = file(account);
    try {
      if (claimed.add(account)) {
        String saved = JournalFile.recoverTail(f, clock.getAsLong());
        if (saved != null) log.accept("EVI journal: an interrupted last line was saved to " + saved);
      }
      String line = JournalCodec.encodeLine(r) + "\n";
      long size = JournalFile.append(f, line, consumed.getOrDefault(account, 0L));
      consumed.put(account, size);
      tails.put(account, line.getBytes(StandardCharsets.UTF_8));
    } catch (JournalFile.ConcurrentWrite e) {
      rebuildNeeded = true;
      claimed.remove(account);
      throw e;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The account whose offer {@code buyId} names (resolved through the login-time aliases), else {@code fallback}. */
  private String ownerOr(String buyId, String fallback) {
    if (store != null && buyId != null) {
      String id = store.resolve(buyId);
      for (Offer o : store.offers()) if (o.offerId.equals(id) && Packet.id(o.account)) return o.account;
    }
    return fallback;
  }

  private void ingest(String json, long now) {
    ioCheck();
    if (failure != null) return;
    JsonElement el;
    Packet p;
    try {
      el = parser.apply(json);
      p = Packet.validate(el);
    } catch (RuntimeException e) {
      log.accept("EVI journal: refused a packet: " + e.getMessage());
      return;
    }
    // The import never costs the packet that triggered it: whatever it throws -- an I/O failure, a bug, or
    // an Error such as OutOfMemoryError on a huge file -- the packet is journalled next. An Error is not
    // retried this session (the same file would most likely do it again); switching the setting on resets it.
    lastAccount = p.account;
    try {
      maybeImport(p.account);
    } catch (Throwable e) {
      boolean retry = !(e instanceof Error);
      int attempt = importTries.getOrDefault(p.account, 0);
      if (!retry) importTries.put(p.account, IMPORT_TRIES);
      log.accept("EVI journal: the import did not complete (attempt " + attempt + " of " + IMPORT_TRIES
        + " this session; " + (retry ? "tried again at a later packet" : "not tried again this session") + "); this packet is journalled anyway: " + e);
    }
    try {
      sync();
      if (store == null) return;
      for (int attempt = 0; ; attempt++) {
        try {
          store.ingest(el, now);
          return;
        } catch (JournalFile.ConcurrentWrite e) {
          if (attempt >= 2) throw e;
          sync(); // another process wrote: re-read, then try again on the current state
        }
      }
    } catch (JournalException e) {
      log.accept("EVI journal: refused a packet: " + e.getMessage());
    } catch (IOException | RuntimeException e) {
      log.accept("EVI journal: could not journal a packet: " + e);
    }
  }

  static String markerName(String account) {
    return "import-done-" + account + ".json";
  }

  /**
   * The one-time, opt-in import for one account. Never runs while the setting is off. Writes ONLY a new
   * {@code imported-<account>.jsonl} (and the counts marker): the account's events file is not touched, so
   * a client following it is never disturbed. A missing import file is looked for again at every packet
   * (copying it in is enough); a file that cannot be used is not retried this session; a failed WRITE is
   * retried at later packets, {@value #IMPORT_TRIES} attempts a session. Switching the setting on again
   * resets all of that.
   */
  private void maybeImport(String account) throws IOException {
    ioCheck();
    ImportFileState file = waitingForImportFile(account);
    // UNKNOWN leaves the line as it is: no false "no file was found", and no line hidden on a guess either.
    if (file != ImportFileState.UNKNOWN) notice(file == ImportFileState.ABSENT ? IMPORT_MISSING_NOTICE : null);
    if (!importEnabled.getAsBoolean() || importDone.contains(account)) return;
    if (file == ImportFileState.UNKNOWN) {
      if (importUnknownLogged.add(account))
        log.accept("EVI journal: the import file could not be checked just now (it may be in use by another program); it is looked"
          + " at again at the next packet");
      return; // not an attempt: nothing was read
    }
    if (file == ImportFileState.ABSENT) {
      if (importMissingLogged.add(account)) {
        log.accept("EVI journal: import is on, but there is no " + IMPORT_DIR + "/" + IMPORT_FILE + " in the plugin's data folder"
          + " (" + PluginFolder.PATH + "/); nothing imported yet, and it is looked for again at every packet");
      }
      return;
    }
    int tries = importTries.getOrDefault(account, 0);
    if (tries >= IMPORT_TRIES) return;
    String name = characterNames.get(account);
    if (name == null && namesExpected && waitingForName.contains(account)) return; // still no name: nothing read again
    Filepath marker = dir.joinSegment(markerName(account));
    Filepath target = importedFile(account);
    Filepath src = root.joinSegment(IMPORT_DIR).joinSegment(IMPORT_FILE);
    importTries.put(account, tries + 1);
    long srcSize = JournalFile.size(src);
    if (srcSize > importMaxBytes) {
      importTries.put(account, IMPORT_TRIES);
      log.accept("EVI journal: " + IMPORT_DIR + "/" + IMPORT_FILE + " is " + srcSize + " bytes, larger than any EVI journal ("
        + importMaxBytes + " at most), so nothing was imported");
      return;
    }
    List<JournalRecord> all;
    BridgeImport.Selection sel;
    boolean marks;
    try {
      marks = importItemMarks.getAsBoolean();
    } catch (RuntimeException e) {
      marks = false;
    }
    try {
      all = decode(JournalFile.readComplete(src, 0));
      if (name == null && namesExpected && namesFlips(all)) {
        importTries.put(account, tries); // not an attempt: the import waits for the character's name, read at the next packet after it
        if (waitingForName.add(account))
          log.accept("EVI journal: the import waits for this character's name (its trade history from another tracker is kept by name)");
        return;
      }
    } catch (RuntimeException e) {
      importTries.put(account, IMPORT_TRIES);
      log.accept("EVI journal: the import file could not be read or replayed, so nothing was imported: " + e.getMessage());
      return;
    }
    // The player's stated former names (import/preferences.json "oldNames"), read only when the file holds flips kept by name.
    OldNames oldNames = OldNames.NONE;
    if (name != null && namesFlips(all)) {
      Filepath prefs = root.joinSegment(IMPORT_DIR).joinSegment(PREFERENCES_FILE);
      ImportFileState ps = importFileState(importFileLook.apply(prefs));
      if (ps == ImportFileState.UNKNOWN) {
        importTries.put(account, tries); // not an attempt: looked at again at the next packet, as the import file is
        if (importUnknownLogged.add(account))
          log.accept("EVI journal: " + IMPORT_DIR + "/" + PREFERENCES_FILE + " could not be checked just now (it may be in use by"
            + " another program); the import waits and looks again at the next packet");
        return;
      }
      if (ps == ImportFileState.PRESENT) {
        try {
          long n = JournalFile.size(prefs);
          if (n > PREFERENCES_MAX_BYTES) throw new IllegalArgumentException(n + " bytes, larger than any preferences file");
          oldNames = OldNames.parse(new String(JournalFile.readAll(prefs), StandardCharsets.UTF_8)); // whole: no trailing newline needed
        } catch (IllegalArgumentException e) {
          // The import runs once per account: never run it without former names the player may have meant to give.
          importTries.put(account, IMPORT_TRIES);
          log.accept("EVI journal: " + IMPORT_DIR + "/" + PREFERENCES_FILE + " could not be read (" + e.getMessage() + "), so nothing"
            + " was imported; correct it, then switch the import setting off and on");
          return;
        }
        if (oldNames.refusedCount() > 0)
          log.accept("EVI journal: " + oldNames.refusedCount() + " former name(s) in " + IMPORT_DIR + "/" + PREFERENCES_FILE
            + " oldNames were refused (mapped to two different current names, to no name, or to a name that is itself listed as a"
            + " former name); trades kept under them are left out");
      }
    }
    // The former data folder's salt (import/identity-salt.txt), when the player copied it: the bridge journalled this account under
    // the id THAT salt gives, so the records are selected by it and filed under this account's own id. Absent: as before.
    String from = account;
    Filepath saltFile = root.joinSegment(IMPORT_DIR).joinSegment(SALT_FILE);
    ImportFileState saltState = importFileState(importFileLook.apply(saltFile));
    if (saltState == ImportFileState.UNKNOWN) {
      importTries.put(account, tries); // not an attempt: looked at again at the next packet, as the import file is
      if (importUnknownLogged.add(account))
        log.accept("EVI journal: " + IMPORT_DIR + "/" + SALT_FILE + " could not be checked just now (it may be in use by another"
          + " program); the import waits and looks again at the next packet");
      return;
    }
    if (saltState == ImportFileState.PRESENT) {
      String[] identity = identities.get(account);
      if (identity == null) {
        importTries.put(account, tries); // not an attempt: the plugin tells the identity at login
        if (waitingForIdentity.add(account))
          log.accept("EVI journal: the import waits for this account's identity (" + IMPORT_DIR + "/" + SALT_FILE + " names its records by it)");
        return;
      }
      String salt;
      try {
        long n = JournalFile.size(saltFile);
        if (n > SALT_MAX_BYTES) throw new IllegalArgumentException(n + " bytes, larger than any identity salt");
        salt = new String(JournalFile.readAll(saltFile), StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        if (salt.isEmpty()) throw new IllegalArgumentException("it is empty");
      } catch (IllegalArgumentException e) {
        // The import runs once per account: never run it under an id the player did not mean (the preferences.json rule).
        importTries.put(account, IMPORT_TRIES);
        log.accept("EVI journal: " + IMPORT_DIR + "/" + SALT_FILE + " could not be read (" + e.getMessage() + "), so nothing was"
          + " imported; correct it, then switch the import setting off and on");
        return;
      }
      from = AccountIds.of(salt, identity[0], identity[1]);
    }
    try {
      sel = BridgeImport.select(all, from, marks, name, oldNames);
    } catch (RuntimeException e) {
      importTries.put(account, IMPORT_TRIES);
      log.accept("EVI journal: the import file could not be read or replayed, so nothing was imported: " + e.getMessage());
      return;
    }
    if (broken.contains(account)) {
      importTries.put(account, IMPORT_TRIES);
      log.accept("EVI journal: this account's journal has an unreadable line; import refused");
      return;
    }
    List<JournalRecord> existing;
    try {
      existing = decode(JournalFile.readComplete(file(account), 0));
    } catch (RuntimeException e) {
      importTries.put(account, IMPORT_TRIES);
      log.accept("EVI journal: this account's journal has an unreadable line; import refused");
      return;
    }
    List<JournalRecord> mine = from.equals(account) ? sel.records : rekey(sel.records, from, account);
    List<JournalRecord> merged = BridgeImport.merge(mine, existing);
    try {
      Store.replay(merged, r -> {
        throw new IllegalStateException("a validation replay must not write");
      }).state(clock.getAsLong());
    } catch (RuntimeException e) {
      importTries.put(account, IMPORT_TRIES);
      log.accept("EVI journal: the imported history does not replay, so nothing was imported: " + e);
      return;
    }
    // An I/O failure here (a locked or blocked file) propagates to ingest, which keeps the packet; this
    // attempt is already counted, so the import is tried again at a later packet, IMPORT_TRIES at most.
    dir.createDirectories();
    try {
      JournalFile.writeNew(target, encode(mine));
    } catch (JournalFile.AlreadyExists e) {
      // Another client imported this account between the check above and the write: its file IS the import
      // (written once, never changed), so this is success by another writer, not a failed attempt.
      importDone.add(account);
      rebuildNeeded = true;
      log.accept("EVI journal: another client imported this account's old history at the same moment; its import is used and nothing was written here");
      return;
    }
    importDone.add(account);
    rebuildNeeded = true;
    JsonObject m = new JsonObject();
    m.addProperty("at", clock.getAsLong());
    m.addProperty("imported", sel.records.size());
    m.addProperty("keptFromPlugin", merged.size() - sel.records.size());
    m.addProperty("bridgeRecords", sel.total);
    m.addProperty("otherAccounts", sel.otherAccounts);
    m.addProperty("notTiedToAnAccount", sel.notTiedToAnAccount);
    m.addProperty("unresolved", sel.unresolved);
    if (marks) m.addProperty("itemMarks", sel.itemMarks);
    m.addProperty("flips", sel.flips);
    m.addProperty("flipsOtherCharacters", sel.flipsOtherCharacters);
    if (oldNames.present) {
      m.addProperty("flipsViaOldNames", sel.flipsViaOldNames);
      m.addProperty("flipsOldNamesRefused", sel.flipsOldNamesRefused);
    }
    if (!from.equals(account)) m.addProperty("viaIdentitySalt", true);
    try {
      JournalFile.writeNew(marker, (m.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (JournalFile.AlreadyExists e) {
      log.accept("EVI journal: the import is done; its counts were already saved by another client");
    } catch (IOException e) {
      log.accept("EVI journal: the import is done, but its counts could not be saved: " + e);
    }
    log.accept("EVI journal: imported " + sel.records.size() + " of the import file's " + sel.total + " records for this account ("
      + sel.otherAccounts + " belong to other accounts, " + (marks ? sel.itemMarks + " are item marks taken for every account, " : "")
      + sel.notTiedToAnAccount + " name no account); " + sel.flips + " trades from another tracker under this character's name, "
      + sel.flipsOtherCharacters + " under other names left out"
      + (oldNames.present ? " (" + sel.flipsViaOldNames + " taken under this character's former names, " + sel.flipsOldNamesRefused
        + " under refused former names)" : "")
      + (from.equals(account) ? "" : "; this account's records were recognised by " + IMPORT_DIR + "/" + SALT_FILE));
    if (saltState == ImportFileState.ABSENT && sel.otherAccounts > 0 && ownedCount(sel.records) == 0)
      log.accept("EVI journal: none of the import file's records carry this account's id. If they were kept by the old companion"
        + " app, its ids came from another identity salt: " + SALT_HINT + " into " + IMPORT_DIR + "/ (the README says where it is)"
        + " before your other accounts import");
  }

  /** The records an account owns (packets, flips, purchases, marks naming its offers): every kind but the machine-wide ones. */
  static int ownedCount(List<JournalRecord> records) {
    int n = 0;
    for (JournalRecord r : records)
      if (!(r instanceof JournalRecord.PersonalUseItem || r instanceof JournalRecord.PersonalUseItemUndo || r instanceof JournalRecord.FlipsImported)) n++;
    return n;
  }

  /**
   * The same records with every value equal to {@code from} (an account id: 64 hex digits, so it is never part of anything else)
   * replaced by {@code to}, through the journal's own line format. Records that do not name {@code from} are kept as they are.
   */
  private List<JournalRecord> rekey(List<JournalRecord> records, String from, String to) {
    String q = '"' + from + '"', r = '"' + to + '"';
    List<JournalRecord> out = new ArrayList<>(records.size());
    for (JournalRecord x : records) {
      String line = JournalCodec.encodeLine(x);
      out.add(line.contains(q) ? JournalCodec.decode(parser.apply(line.replace(q, r))) : x);
    }
    return out;
  }

  /** Whether a bridge journal holds flip histories from another tracker that name a character. */
  static boolean namesFlips(List<JournalRecord> all) {
    for (JournalRecord r : all)
      if (r instanceof JournalRecord.FlipsImported) for (ImportedFlip f : ((JournalRecord.FlipsImported) r).flips) if (f.account != null) return true;
    return false;
  }

  /**
   * Whether {@code account} is still waiting for an import file: null when it is not (the setting off, or the account
   * imported), else the state of {@code import/events.jsonl} -- ABSENT is the only "waiting for a file" ({@link
   * #importFileState}). An import already on disk (its imported file, or the counts marker on its own) is recorded as done
   * here, so the line never asks for a file an account no longer needs.
   */
  private ImportFileState waitingForImportFile(String account) throws IOException {
    ioCheck();
    if (!importEnabled.getAsBoolean() || importDone.contains(account)) return null;
    if (importedFile(account).exists() || dir.joinSegment(markerName(account)).exists()) {
      importDone.add(account);
      return null;
    }
    return importFileState(importFileLook.apply(root.joinSegment(IMPORT_DIR).joinSegment(IMPORT_FILE)));
  }

  /** Tells the import line {@code text} (null hides it), once per change. Journal thread. */
  private void notice(String text) {
    if (noticeSent && java.util.Objects.equals(text, noticeShown)) return;
    noticeSent = true;
    noticeShown = text;
    Consumer<String> l = importNotice;
    if (l != null) l.accept(text);
  }
}
