package com.evi.live;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/**
 * File access for the tests, through RuneLite's {@link Filepath} like the plugin's own code: the Plugin Hub's review
 * (11 Oct 2026) asks that ALL file I/O go through Filepath, tests included. {@code Filepath.Unchecked} is called HERE
 * ONLY, to aim a Filepath at a folder that is no plugin's data folder -- a new temporary folder, or one of this project's
 * own folders named by build.gradle. The plugin never calls it. {@code HubRulesScanTest} holds every other test file to
 * the same file rules as the main source.
 */
public final class TestFiles {
  private TestFiles() {
  }

  /** An absolute folder named by the build (a system property such as evi.projectDir, or a task argument). */
  public static Filepath rooted(String dir) {
    if (dir == null || dir.isEmpty()) throw new IllegalStateException("a folder the build should have named is not set");
    return Filepath.Unchecked.getRooted(java.nio.file.Paths.get(dir).toAbsolutePath());
  }

  /** The project folder (build.gradle's evi.projectDir). */
  public static Filepath project() {
    return rooted(System.getProperty("evi.projectDir"));
  }

  /** The system's temporary folder. */
  public static Filepath systemTemp() {
    return rooted(System.getProperty("java.io.tmpdir"));
  }

  /** A new, empty folder under the system's temporary folder. */
  public static Filepath tempDir(String prefix) throws IOException {
    return systemTemp().createTempDir(prefix);
  }

  /**
   * {@code f} as a {@code java.io.File}, for the two library constructors the tests call that take nothing else: OkHttp's
   * {@code Cache} (a stand-in of RuneLite's injected client; the plugin itself never makes a cache) and RuneLite's
   * {@code ConfigData} (a test ConfigManager whose file is never written). Nothing here reads or writes through it.
   */
  public static java.io.File asFile(Filepath f) {
    return Filepath.Unchecked.getFile(f);
  }

  /** A new temporary folder as the {@code java.io.File} OkHttp's {@code Cache} takes ({@link #asFile}). */
  public static java.io.File okHttpCacheDir(String prefix) throws IOException {
    return asFile(tempDir(prefix));
  }

  public static byte[] read(Filepath f) throws IOException {
    try (InputStream in = f.openInputStream()) {
      return in.readAllBytes();
    }
  }

  public static String text(Filepath f) throws IOException {
    return new String(read(f), StandardCharsets.UTF_8);
  }

  /** The file's lines, split as BufferedReader.readLine splits them (\n, \r\n or \r). */
  public static List<String> lines(Filepath f) throws IOException {
    List<String> out = new ArrayList<>();
    try (BufferedReader r = f.openBufferedReader()) {
      for (String line = r.readLine(); line != null; line = r.readLine()) out.add(line);
    }
    return out;
  }

  /** The names of what is directly inside {@code dir}, sorted. */
  public static List<String> names(Filepath dir) throws IOException {
    try (Stream<Filepath> s = dir.walk(1)) {
      return s.filter(f -> !f.equals(dir)).map(Filepath::getFileName).sorted().collect(Collectors.toList());
    }
  }

  /** Every file under {@code root} (any depth) whose name ends with {@code suffix}, sorted by path. */
  public static List<Filepath> files(Filepath root, String suffix) throws IOException {
    try (Stream<Filepath> s = root.walk()) {
      return s.filter(f -> f.isFile() && f.getFileName().endsWith(suffix)).sorted().collect(Collectors.toList());
    }
  }

  /** {@code f}'s path below {@code root}, with forward slashes. */
  public static String relative(Filepath root, Filepath f) {
    String r = root.toString(), p = f.toString();
    if (!p.startsWith(r)) throw new IllegalArgumentException(p + " is not under " + r);
    String rest = p.substring(r.length()).replace('\\', '/');
    return rest.startsWith("/") ? rest.substring(1) : rest;
  }

  /** {@code root} joined with each of {@code segments}, one folder or file name at a time. */
  public static Filepath at(Filepath root, String... segments) {
    Filepath f = root;
    for (String s : segments) f = f.joinSegment(s);
    return f;
  }
}
