package com.evi.live.market;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import net.runelite.client.util.Filepath;

/**
 * Creating a price-data file complete or not at all, safely with TWO RuneLite clients (two processes) sharing one
 * plugin-data folder. The rule is the journal's ({@code JournalFile.writeNew}, Phase 1), with one difference.
 *
 * <p>THE RULE: write the whole content to a temporary file in the same folder, force it to disk, then RENAME it into
 * place, and NEVER over an existing file: when the target exists this throws {@link FileAlreadyExistsException} and
 * changes nothing. A replace is avoided on purpose -- on Windows a move over a file fails with AccessDenied whenever
 * any other process has that file open, which is exactly what the other client's read does (the Phase 1 lesson). So
 * no {@code REPLACE_EXISTING}, and no {@code ATOMIC_MOVE} (on Windows that one REPLACES). A plain rename in one folder
 * is atomic: a reader sees no file or the whole file, never part of one.
 *
 * <p>THE DIFFERENCE: the temporary name is UNIQUE PER WRITE ({@code <target>.<uuid>.tmp}). The journal's shared
 * {@code <target>.tmp} is safe there because one account's file has one writer; here both clients routinely fetch
 * the SAME hour at the same moment, and with a shared temporary name one client's clean-up could delete -- or its
 * rename could publish -- the other's half-written file. With unique names each writer only ever touches its own.
 */
final class ArchiveFiles {
  private ArchiveFiles() {}

  static final String TMP_SUFFIX = ".tmp";

  /** TEST SEAM: told the target just before writeNew() renames its finished temporary file into place. Null in production. */
  static volatile Consumer<Filepath> moveSeam;

  /**
   * TEST SEAM: answers a file's modified time (epoch ms) in place of the file system, so a test can show an old temporary
   * file without setting a real file's time (which Filepath, the only file API the tests use, cannot do). Null in production.
   */
  static volatile ToLongFunction<Filepath> modifiedSeam;

  /** {@code f}'s modified time in epoch ms: the file system's, or the test seam's when one is set. */
  static long modifiedMs(Filepath f) throws IOException {
    ToLongFunction<Filepath> seam = modifiedSeam;
    return seam != null ? seam.applyAsLong(f) : f.getLastModifiedTime().toMillis();
  }

  /** True when the content landed; false when the target already existed (another writer won, or it was there before). */
  static boolean writeNew(Filepath target, byte[] content) throws IOException {
    if (target.exists()) return false;
    Filepath tmp = target.getParent().joinSegment(target.getFileName() + "." + UUID.randomUUID() + TMP_SUFFIX);
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
        return true;
      } catch (FileAlreadyExistsException e) {
        return false; // created by someone else meanwhile: theirs stands, untouched
      }
    } finally {
      tmp.deleteIfExists(); // only ever this write's own temporary file
    }
  }
}
