package com.evi.live.journal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.util.Filepath;

/**
 * The plugin's journal files, one per account, in the bridge's events.jsonl line format, read and written
 * ONLY through RuneLite's {@link Filepath} (the 6 Oct 2026 forbidden-API list bans java.io file APIs).
 *
 * <p>ONE FILE PER ACCOUNT, because two RuneLite clients are two processes sharing one data folder. The
 * game never lets one account be logged in twice, so each file has exactly one writer at a time. The
 * writer still checks, before and after every append, that the file is the length it last saw: a change
 * it did not make means another process wrote, and the caller re-reads before trusting its own state.
 *
 * <p>Appends are one complete line per write call, in APPEND mode, then forced to disk (store.mjs does the
 * same: one writeFileSync of the line, then fsync). A reader only ever consumes up to the last newline, so
 * a line still being written is never half-read.
 *
 * <p>AN ACCOUNT'S EVENTS FILE IS NEVER REWRITTEN, only appended to (and, after a crash, cut back to its last
 * complete line, which no reader ever consumed past). That invariant is what lets another client follow a
 * file by remembering how many bytes it has read. The one-time import therefore does NOT merge into the
 * events file: it writes a SEPARATE, immutable {@code imported-<account>.jsonl}, created under a name that
 * does not exist yet ({@link #writeNew}). Replacing an existing file is avoided on purpose: on Windows a
 * move over a file fails with AccessDenied whenever any other process has that file open, which is exactly
 * what a second client's read does.
 */
final class JournalFile {
  private JournalFile() {}

  static final String PREFIX = "events-";
  static final String SUFFIX = ".jsonl";
  static final String IMPORTED_PREFIX = "imported-";
  /** store.mjs refuses to append past 100 MB; so does this. */
  static final long MAX_BYTES = 100L * 1024 * 1024;
  static final String TOO_LARGE = "Journal reached 100 MB. Back up data and contact support before continuing.";
  private static final Pattern NAME = Pattern.compile("events-([a-zA-Z0-9-]{1,80})\\.jsonl");
  private static final Pattern IMPORTED_NAME = Pattern.compile("imported-([a-zA-Z0-9-]{1,80})\\.jsonl");

  /**
   * TEST SEAM: told (file, "before") just before append() reads the length it checks, and (file, "after")
   * between its write and the length check that follows, so a test can stand in for another writer at
   * exactly those moments. Null in production.
   */
  static volatile BiConsumer<Filepath, String> appendSeam;
  /** TEST SEAM: told the target just before writeNew() moves its finished temporary file into place. Null in production. */
  static volatile Consumer<Filepath> moveSeam;

  /** Another process changed the file between this process's reads and its write. */
  static final class ConcurrentWrite extends RuntimeException {
    ConcurrentWrite(String message) {
      super(message);
    }
  }

  /** The file an account's records live in. The account must be a store.mjs id (letters, digits, hyphens). */
  static String fileName(String account) {
    if (!Packet.id(account)) throw new JournalException("Invalid account");
    return PREFIX + account + SUFFIX;
  }

  /** The one-time import's file for an account: the bridge's records for it, written once, never changed. */
  static String importedFileName(String account) {
    if (!Packet.id(account)) throw new JournalException("Invalid account");
    return IMPORTED_PREFIX + account + SUFFIX;
  }

  /** The account a journal file belongs to, or null when the name is not a journal file's. */
  static String accountOf(String fileName) {
    return match(NAME, fileName);
  }

  /** The account an imported file belongs to, or null when the name is not an imported file's. */
  static String importedAccountOf(String fileName) {
    return match(IMPORTED_NAME, fileName);
  }

  private static String match(Pattern p, String fileName) {
    if (fileName == null) return null;
    Matcher m = p.matcher(fileName);
    return m.matches() ? m.group(1) : null;
  }

  /** Every events file in {@code dir}, keyed by account, in ascending name order. */
  static List<String> accounts(Filepath dir) throws IOException {
    return list(dir, NAME);
  }

  /** Every imported file in {@code dir}, keyed by account, in ascending name order. */
  static List<String> importedAccounts(Filepath dir) throws IOException {
    return list(dir, IMPORTED_NAME);
  }

  private static List<String> list(Filepath dir, Pattern p) throws IOException {
    List<String> out = new ArrayList<>();
    if (!dir.isDirectory()) return out;
    try (java.util.stream.Stream<Filepath> s = dir.walk(1)) {
      s.forEach(f -> {
        String a = f.isFile() ? match(p, f.getFileName()) : null;
        if (a != null) out.add(a);
      });
    }
    out.sort(null);
    return out;
  }

  static long size(Filepath f) throws IOException {
    return f.exists() ? f.size() : 0;
  }

  /**
   * The bytes of {@code f} from {@code from} up to and including the LAST newline -- never a partial
   * trailing line. Empty when there is nothing complete past {@code from}.
   */
  static byte[] readComplete(Filepath f, long from) throws IOException {
    if (!f.exists()) return new byte[0];
    try (FileChannel ch = f.openFileChannel(StandardOpenOption.READ)) {
      long size = ch.size();
      if (size <= from) return new byte[0];
      long len = size - from;
      if (len > Integer.MAX_VALUE - 16) throw new IOException("journal file too large to read");
      ByteBuffer buf = ByteBuffer.allocate((int) len);
      ch.position(from);
      while (buf.hasRemaining() && ch.read(buf) >= 0) {
        // read until full or end of file
      }
      byte[] b = buf.array();
      int end = lastNewline(b, buf.position());
      return Arrays.copyOf(b, end);
    }
  }

  /** Index just past the last '\n' in the first {@code length} bytes, or 0. */
  static int lastNewline(byte[] b, int length) {
    for (int i = length - 1; i >= 0; i--) if (b[i] == '\n') return i + 1;
    return 0;
  }

  /** The last complete line of the first {@code length} bytes (its '\n' included), or an empty array. */
  static byte[] lastLine(byte[] b, int length) {
    int end = lastNewline(b, length);
    if (end == 0) return new byte[0];
    int start = lastNewline(b, end - 1);
    return Arrays.copyOfRange(b, start, end);
  }

  /** store.mjs's {@code split('\n').filter(Boolean)} over UTF-8 text. */
  static List<String> lines(byte[] bytes) {
    List<String> out = new ArrayList<>();
    for (String l : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) if (!l.isEmpty()) out.add(l);
    return out;
  }

  /**
   * Appends one line (which must end in '\n') when the file is exactly {@code expectedSize} long, and
   * returns the new size. Throws {@link ConcurrentWrite} WITHOUT writing when the length differs, and
   * AFTER writing when the length afterwards is not the expected one (another writer appended too: the
   * line is intact, because each write is one whole line in append mode, but the caller must re-read).
   */
  static long append(Filepath f, String line, long expectedSize) throws IOException {
    if (!line.endsWith("\n")) throw new IllegalArgumentException("a journal line must end in a newline");
    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
    try (FileChannel ch = f.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      BiConsumer<Filepath, String> seam = appendSeam;
      if (seam != null) seam.accept(f, "before");
      long before = ch.size();
      if (before != expectedSize) throw new ConcurrentWrite("journal changed on disk: " + before + " bytes, expected " + expectedSize);
      if (before > MAX_BYTES) throw new JournalException(TOO_LARGE);
      ByteBuffer buf = ByteBuffer.wrap(bytes);
      while (buf.hasRemaining()) ch.write(buf);
      ch.force(true);
      if (seam != null) seam.accept(f, "after");
      long after = ch.size();
      if (after != before + bytes.length) throw new ConcurrentWrite("journal grew by " + (after - before) + " bytes, expected " + bytes.length);
      return after;
    }
  }

  /**
   * Creates {@code target} holding {@code content}, complete or not at all: written in full to a temporary
   * file in the same folder (a name no reader lists), forced to disk, then renamed into place.
   * NEVER over an existing file: when {@code target} exists this throws {@link AlreadyExists} and changes
   * nothing (see the class comment for why a replace is avoided) -- and that holds when another process
   * creates {@code target} between the check and the rename, because the rename is asked NOT to replace: a
   * rename that fails while {@code target} is there is reported as {@link AlreadyExists} too. (ATOMIC_MOVE is
   * deliberately not used: on Windows it is a move that REPLACES an existing file.) A failure leaves no
   * temporary file behind.
   */
  static void writeNew(Filepath target, byte[] content) throws IOException {
    if (target.exists()) throw new AlreadyExists(target.getFileName(), null);
    Filepath tmp = target.getParent().joinSegment(target.getFileName() + ".tmp");
    tmp.deleteIfExists();
    try {
      try (FileChannel ch = tmp.openFileChannel(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        ByteBuffer buf = ByteBuffer.wrap(content);
        while (buf.hasRemaining()) ch.write(buf);
        ch.force(true);
      }
      Consumer<Filepath> seam = moveSeam;
      if (seam != null) seam.accept(target);
      try {
        tmp.moveTo(target); // no REPLACE_EXISTING, no ATOMIC_MOVE: an existing target fails the move
      } catch (IOException e) {
        if (target.exists()) throw new AlreadyExists(target.getFileName(), e); // created by another writer meanwhile
        throw e;
      }
    } finally {
      tmp.deleteIfExists();
    }
  }

  /**
   * {@link #writeNew}'s target was already there (before the write, or created by another writer before its rename):
   * nothing was written. The plugin's own exception, so no java.nio.file exception class is named.
   */
  static final class AlreadyExists extends IOException {
    AlreadyExists(String fileName, IOException cause) {
      super(fileName + " already exists", cause);
    }
  }

  /**
   * store.mjs's interrupted-tail recovery, for a file this process is about to start writing: whatever
   * follows the last newline (a line cut off by a crash) is saved beside it and cut from the journal.
   * Returns the saved file's name, or null when there was nothing to recover.
   */
  static String recoverTail(Filepath f, long now) throws IOException {
    if (!f.exists()) return null;
    try (FileChannel ch = f.openFileChannel(StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      long size = ch.size();
      long end = 0;
      // Scan back from the end for the last newline, in small blocks.
      ByteBuffer buf = ByteBuffer.allocate(8192);
      long pos = size;
      search:
      while (pos > 0) {
        int n = (int) Math.min(buf.capacity(), pos);
        pos -= n;
        buf.clear().limit(n);
        ch.position(pos);
        while (buf.hasRemaining() && ch.read(buf) >= 0) {
          // fill
        }
        for (int i = n - 1; i >= 0; i--) {
          if (buf.get(i) == '\n') {
            end = pos + i + 1;
            break search;
          }
        }
      }
      if (end == size) return null;
      ByteBuffer tail = ByteBuffer.allocate((int) Math.min(size - end, 16L * 1024 * 1024));
      ch.position(end);
      while (tail.hasRemaining() && ch.read(tail) >= 0) {
        // fill
      }
      String name = "interrupted-tail-" + f.getFileName() + "-" + now + ".txt";
      f.getParent().joinSegment(name).write(Arrays.copyOf(tail.array(), tail.position()));
      ch.truncate(end);
      ch.force(true);
      return name;
    }
  }

  /**
   * Bytes read from EACH end of a file for its {@link Fingerprint}. Tests lower it so a change between the two
   * windows is reachable with a small file.
   */
  static volatile int fingerprintWindow = 64 * 1024;

  /**
   * What a reader remembers about an immutable file (an account's imported file) to notice that it has been
   * changed by anything at all: its length, its last-modified time, and its first and last
   * {@link #fingerprintWindow} bytes. A file that GROWS or SHRINKS changes the length; one REPLACED with
   * content of the same length changes the time (a fresh write) or the bytes at either end. Reading both
   * ends costs at most two windows, so it is cheap enough to check before every use, unlike a hash of a file
   * that may be tens of megabytes. Its one blind spot -- same length, a restored time and identical ends --
   * is a deliberate forgery, not something a copy, an editor or another client does.
   */
  static final class Fingerprint {
    final long size;
    final FileTime modified;
    final byte[] head;
    final byte[] tail;

    Fingerprint(long size, FileTime modified, byte[] head, byte[] tail) {
      this.size = size;
      this.modified = modified;
      this.head = head;
      this.tail = tail;
    }

    @Override public boolean equals(Object o) {
      if (!(o instanceof Fingerprint)) return false;
      Fingerprint f = (Fingerprint) o;
      return size == f.size && Objects.equals(modified, f.modified) && Arrays.equals(head, f.head) && Arrays.equals(tail, f.tail);
    }

    @Override public int hashCode() {
      return Objects.hash(size, modified, Arrays.hashCode(head), Arrays.hashCode(tail));
    }
  }

  /** The {@link Fingerprint} of {@code f}; a missing file has size 0, no time and no bytes. */
  static Fingerprint fingerprint(Filepath f) throws IOException {
    if (!f.exists()) return new Fingerprint(0, null, new byte[0], new byte[0]);
    FileTime modified = f.getLastModifiedTime();
    try (FileChannel ch = f.openFileChannel(StandardOpenOption.READ)) {
      long size = ch.size();
      int window = (int) Math.min(fingerprintWindow, size);
      return new Fingerprint(size, modified, readAt(ch, 0, window), readAt(ch, size - window, window));
    }
  }

  private static byte[] readAt(FileChannel ch, long from, int len) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(len);
    ch.position(from);
    while (buf.hasRemaining() && ch.read(buf) >= 0) {
      // read until full or end of file
    }
    return Arrays.copyOf(buf.array(), buf.position());
  }

  /** The whole file as bytes (a small text file: an import marker, the import source). */
  static byte[] readAll(Filepath f) throws IOException {
    try (InputStream in = f.openInputStream()) {
      return in.readAllBytes();
    }
  }

  /** Appends text to a small local log file (the comparison log), creating it if needed. */
  static void appendText(Filepath f, String text) throws IOException {
    try (OutputStream out = f.openOutputStream(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }
}
