package com.evi.live;

import net.runelite.client.util.Filepath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The Plugin Hub's forbidden-API list ("Rejected or Rolled Back Features", revised 6 Oct 2026), checked over
 * every file of the plugin's MAIN source on every build, so a banned call cannot come back unnoticed. The
 * list is read off the code with comments and string literals removed: a comment that says "never
 * Thread.interrupt" is not a call, and a call hidden in a comment is not code.
 *
 * <p>What is forbidden, as the page words it: reflection; JNI and JNA; running external programs; downloading
 * or loading code at runtime; Desktop.open / Desktop.browse / LinkBrowser.open; Thread.interrupt and
 * Thread.sleep; Client.menuAction; java.io file APIs (use Filepath); java.lang.Runtime; KeyboardFocusManager;
 * any network client but OkHttp; creating your own Gson or OkHttp instance; net.runelite as the package. And
 * Filepath.Unchecked, which is restricted (manual review). shutdownNow() is added because it interrupts.
 *
 * <p>7 Oct 2026, the blind spots a verifier's mutation run found, each now a rule and a self-test line: a sleep
 * reached any other way ({@code TimeUnit.X.sleep}, a static import of {@code Thread.sleep}, a call split across
 * lines), an interrupt reached any other way ({@code Future.cancel(true)} and {@code invokeAll}/{@code invokeAny}
 * with a timeout, which cancel with interrupt), {@code java.io.*} imported whole and its {@code File} used bare,
 * RuneLite's own {@code java.io.File} folders ({@code RuneLite.RUNELITE_DIR} and the rest), and network IO outside
 * OkHttp through {@code java.net.URL} (e.g. {@code ImageIO.read(new URL(...))}). The scan now reads each file as
 * ONE text with whitespace collapsed and removed around a dot, so a call split over lines is still one call; a
 * hit still reports its real line.
 *
 * <p>7 Oct 2026, second pass, after a planted file stayed green end to end:
 * <ul>
 *   <li><b>Unicode escapes are decoded first, exactly as javac does</b> (JLS 3.3: any number of u's, and only a
 *       backslash preceded by an even run of backslashes starts one). Without it {@code Thread.\u005cu0073leep(1)}
 *       was invisible, and a {@code \u005cu0022} or {@code \u005cu000a} could move a string's or comment's edge so
 *       real code read as a literal. Text blocks are stripped whole, and a carriage return ends a line comment.</li>
 *   <li><b>Method references are read as calls</b>: {@code X::m} as {@code X.m(} and {@code X::new} as
 *       {@code new X(}, so {@code Thread::sleep}, {@code ExecutorService::shutdownNow}, {@code Class::forName},
 *       {@code Client::menuAction} and {@code Gson::new} meet the same rules as the calls they stand for. A
 *       reference to {@code cancel} is flagged: which overload it binds is the compiler's choice, not the reader's.</li>
 *   <li><b>Allow-lists instead of block-lists for {@code java.io}, {@code java.net}, {@code java.nio.file} and
 *       {@code java.nio.channels}</b>, which a JDK class can only be used from by naming it in an import or in
 *       full (only {@code java.lang} is implicit). So {@code PrintWriter}, {@code PrintStream}, {@code InetAddress},
 *       {@code SocketChannel} and {@code FileSystems} are caught without listing them, and any class added later
 *       must be put on the list deliberately. <b>Wildcard imports are banned for the same reason</b>: they would
 *       let a class be used without its package ever being written.</li>
 *   <li>Packages with nothing a flipping plugin needs: {@code java.beans} (reflection by another name),
 *       {@code javax.net} and {@code .createSocket(} (raw sockets, also reachable from OkHttp's socket factories),
 *       {@code java.rmi}, {@code javax.naming}, JMX, {@code java.lang.instrument}, and the JDK's internal
 *       {@code sun.*}, {@code jdk.*}, {@code com.sun.*}. Plus {@code FocusManager} (a
 *       {@code KeyboardFocusManager}), {@code ZipFile}/{@code JarFile}/{@code FileHandler}/{@code java.util.Formatter}
 *       (they open files by name), {@code ProcessHandle}, {@code LockSupport} (a sleep under another name), and a
 *       static import of a member of {@code Thread}, {@code Class}, {@code Runtime}, {@code ClassLoader},
 *       {@code System.load*} or {@code LinkBrowser.open}, which would let the call appear without its class.</li>
 * </ul>
 *
 * <p>The limit by construction: the scan reads NAMES, not types. An API reached only through a value whose type
 * is never written (a method chain, {@code var}) is seen only when its method's name is on a list -- which is why
 * the dangerous method names ({@code sleep}, {@code interrupt}, {@code createSocket}, {@code toFile}, {@code
 * openConnection} ...) are matched on their own, whatever they are called on.
 *
 * <p>11 Oct 2026, the Hub's review of 4.0.0: "all file i/o should be performed with Filepath rather than direct java
 * APIs" -- the tests included. So the TEST source is scanned too, by the two file rules ({@link #TEST_RULES}): every
 * test reads and writes through {@link TestFiles}, which is the one test file allowed to aim a Filepath at a folder
 * (Filepath.Unchecked: a temporary folder, or the project's own files) and to hand OkHttp's cache its java.io.File.
 *
 * <p>Test-only: tests do not ship in the plugin jar. The scanner proves itself first, on known-bad and
 * known-good snippets, so a pattern that silently matches nothing fails here rather than passing.
 */
public final class HubRulesScanTest {
  static final class Rule {
    final String name;
    final Pattern pattern;

    Rule(String name, String regex) {
      this.name = name;
      this.pattern = Pattern.compile(regex);
    }
  }

  /** java.io classes a plugin may use: streams, readers and exceptions. Nothing here opens a file by name. */
  static final String JAVA_IO_ALLOWED = "IOException|UncheckedIOException|EOFException|InputStream|OutputStream|Reader|Writer|BufferedReader"
    + "|BufferedWriter|InputStreamReader|OutputStreamWriter|ByteArrayInputStream|ByteArrayOutputStream|StringReader|StringWriter|Closeable"
    + "|Flushable|Serializable";
  /** java.net: names and encoders, OkHttp's Proxy.NO_PROXY, and the exceptions an OkHttp call can throw. Nothing that connects. */
  static final String JAVA_NET_ALLOWED = "URI|URISyntaxException|URLEncoder|URLDecoder|Proxy|MalformedURLException|SocketTimeoutException"
    + "|ConnectException|UnknownHostException|NoRouteToHostException";
  /** java.nio.file: options, exceptions and FileTime -- what Filepath's own methods take and throw. Not Files, Paths, Path or FileSystems. */
  static final String JAVA_NIO_FILE_ALLOWED = "StandardOpenOption|StandardCopyOption|OpenOption|CopyOption"
    + "|NoSuchFileException|AccessDeniedException|AtomicMoveNotSupportedException|DirectoryNotEmptyException|NotDirectoryException"
    + "|attribute\\.FileTime";
  /** java.nio.channels: the FileChannel that Filepath.openFileChannel hands out, and what goes with it. No socket or async channel. */
  static final String JAVA_NIO_CHANNELS_ALLOWED = "FileChannel|FileLock|Channels|ReadableByteChannel|WritableByteChannel|SeekableByteChannel"
    + "|ByteChannel|ClosedChannelException|OverlappingFileLockException";

  static String outside(String pkg, String allowed) {
    return "\\b" + pkg.replace(".", "\\.") + "\\.(?!(?:" + allowed + ")\\b)\\w[\\w.]*";
  }

  static final List<Rule> RULES = Arrays.asList(
    new Rule("reflection", "\\bjava\\.lang\\.reflect\\b|\\bjava\\.lang\\.invoke\\b|\\.setAccessible\\s*\\(|\\.getDeclared(Field|Method|Constructor)s?\\s*\\("
      + "|\\.get(Field|Method|Constructor)s?\\s*\\(|\\bClass\\.forName\\s*\\(|\\.newInstance\\s*\\(|\\bProxy\\.newProxyInstance\\b|\\bMethodHandles\\b|\\bsun\\.misc\\b"
      // reflection under other names: bean introspection, JMX, instrumentation
      + "|\\bjava\\.beans\\b|\\bIntrospector\\b|\\bjavax\\.management\\b|\\bjava\\.lang\\.management\\b|\\bjava\\.lang\\.instrument\\b"),
    new Rule("JNI / JNA", "\\bnative\\s+[\\w<>\\[\\], ]+\\s+\\w+\\s*\\(|\\bSystem\\.load(Library)?\\s*\\(|\\bcom\\.sun\\.jna\\b"),
    new Rule("external programs", "\\bProcessBuilder\\b|\\bRuntime\\b|\\bProcess\\b|\\bProcessHandle\\b"),
    new Rule("code loaded at runtime", "\\bURLClassLoader\\b|\\bdefineClass\\b|\\bjavax\\.script\\b|\\bScriptEngine\\w*\\b|\\bjavax\\.tools\\b|\\bClassLoader\\b"),
    new Rule("JDK internals", "(?<![\\w.])(sun|jdk|com\\.sun)\\.\\w"),
    new Rule("Desktop / LinkBrowser.open", "\\bDesktop\\b|\\bLinkBrowser\\.open\\b"),
    new Rule("Thread.sleep / interrupt", "\\bThread\\.sleep\\s*\\(|\\.interrupt\\s*\\(|\\bshutdownNow\\s*\\("
      // any sleep(...) call: TimeUnit.X.sleep, unit.sleep, a static import of Thread.sleep; LockSupport.park* is a sleep by another name
      + "|\\bsleep\\s*\\(|\\bimport\\s+static\\s+java\\.lang\\.Thread\\b|\\bLockSupport\\b"
      // Future.cancel with anything but a literal false interrupts the running task; invokeAll / invokeAny cancel with interrupt
      // (the space after the bracket is taken possessively, so "cancel( )" cannot backtrack into a match)
      + "|\\.cancel\\s*\\(\\s*+(?!false\\s*\\)|\\))|\\.invoke(All|Any)\\s*\\("),
    new Rule("Client.menuAction", "\\.menuAction\\s*\\("),
    new Rule("java.io file APIs", outside("java.io", JAVA_IO_ALLOWED)
      + "|\\b(FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile|FileDescriptor)\\b|\\bnew\\s+File\\s*\\(|\\bFile\\.separator\\b"
      + "|" + outside("java.nio.file", JAVA_NIO_FILE_ALLOWED) + "|\\bFiles\\s*\\.|\\bPaths\\s*\\.|\\bFileSystems?\\b|\\bPath\\.of\\s*\\(|\\bFileChannel\\.open\\s*\\(|\\.toFile\\s*\\("
      // java.io.File used under its bare name (File.createTempFile, File[], new File)
      + "|\\bFile\\b"
      // other JDK classes that open a file by name
      + "|\\bjava\\.util\\.(zip\\.ZipFile|jar\\.JarFile|logging\\.FileHandler|Formatter)\\b|\\b(ZipFile|JarFile|FileHandler)\\b|\\bnew\\s+Formatter\\s*\\("
      // RuneLite's own java.io.File / Path folders, qualified or statically imported
      + "|\\b(RUNELITE_DIR|CACHE_DIR|PLUGINS_DIR|SCREENSHOT_DIR|LOGS_DIR|DEFAULT_SESSION_FILE|NOTIFICATIONS_DIR|FONTS_DIR|PLUGIN_DATA)\\b"),
    new Rule("KeyboardFocusManager", "\\b(Keyboard|Default)?FocusManager\\b"),
    new Rule("a network client other than OkHttp", "\\bHttpURLConnection\\b|\\bURLConnection\\b|\\bjava\\.net\\.http\\b|\\bHttpClient\\b|\\bSocket\\b|\\bServerSocket\\b"
      + "|\\bDatagramSocket\\b|\\.openConnection\\s*\\(|\\.openStream\\s*\\("
      // java.net.URL: a request made by ImageIO.read(url), url.getContent(), and the like
      + "|\\bnew\\s+URL\\s*\\(|\\.toURL\\s*\\("
      // everything in java.net and java.nio.channels that is not on the allow-list (InetAddress, InetSocketAddress, URL, SocketChannel ...)
      + "|" + outside("java.net", JAVA_NET_ALLOWED) + "|" + outside("java.nio.channels", JAVA_NIO_CHANNELS_ALLOWED)
      + "|\\bInet[46]?(Socket)?Address\\b|\\b(Server)?SocketChannel\\b|\\bDatagramChannel\\b|\\bAsynchronous\\w*Channel\\b"
      // raw sockets through a socket factory (javax.net, or OkHttp's own socketFactory()/sslSocketFactory()), RMI, JNDI
      + "|\\bjavax\\.net\\b|\\b(SSL)?SocketFactory\\b|\\.createSocket\\s*\\(|\\bjava\\.rmi\\b|\\bjavax\\.naming\\b"),
    new Rule("own Gson instance", "\\bnew\\s+(com\\.google\\.gson\\.)?Gson(Builder)?\\s*\\("),
    new Rule("own OkHttp instance", "\\bnew\\s+(okhttp3\\.)?OkHttpClient(\\.Builder)?\\s*\\("),
    new Rule("Filepath.Unchecked", "\\bFilepath\\.Unchecked\\b|\\bUnchecked\\.get"),
    new Rule("wildcard import (hides which classes a file uses)", "\\bimport\\s+(static\\s+)?[\\w.]+\\.\\*"),
    new Rule("static import of a banned member", "\\bimport\\s+static\\s+java\\.lang\\.(Thread|Class|Runtime|ClassLoader|ProcessBuilder|Process|System\\.load\\w*)\\b"
      + "|\\bimport\\s+static\\s+[\\w.]*\\bLinkBrowser\\.open\\b"),
    new Rule("net.runelite package", "^\\s*package\\s+net\\.runelite\\b"));

  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  /**
   * The source as javac reads it (JLS 3.3): every Unicode escape decoded -- a backslash, one or more u's, four hex
   * digits -- but only where the backslash is preceded by an EVEN number of contiguous raw backslashes, so the
   * text {@code \\u0041} (an escaped backslash, then "u0041") stays as it is. A backslash produced by an escape
   * never starts another. {@code lineOf[i]} is the SOURCE line of decoded character i, so an escaped newline
   * cannot shift a reported line.
   */
  static String unescape(String src, int[] lineOf) {
    StringBuilder out = new StringBuilder(src.length());
    int line = 1, run = 0, i = 0, n = src.length();
    while (i < n) {
      char c = src.charAt(i);
      if (c == '\\' && run % 2 == 0 && i + 1 < n && src.charAt(i + 1) == 'u') {
        int j = i + 1;
        while (j < n && src.charAt(j) == 'u') j++;
        if (j + 4 <= n && isHex(src, j, j + 4)) {
          lineOf[out.length()] = line;
          out.append((char) Integer.parseInt(src.substring(j, j + 4), 16));
          i = j + 4;
          run = 0;
          continue;
        }
      }
      run = c == '\\' ? run + 1 : 0;
      lineOf[out.length()] = line;
      out.append(c);
      if (c == '\n') line++;
      i++;
    }
    lineOf[out.length()] = line;
    return out.toString();
  }

  static boolean isHex(String s, int from, int to) {
    for (int k = from; k < to; k++) if (Character.digit(s.charAt(k), 16) < 0) return false;
    return true;
  }

  /**
   * The (decoded) source with every comment and every string, text-block or char literal replaced by spaces.
   * Same length as its input, line breaks kept, so position i still maps to the same source line.
   */
  static String code(String src) {
    StringBuilder out = new StringBuilder(src.length());
    int i = 0, n = src.length();
    while (i < n) {
      char c = src.charAt(i);
      if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
        while (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') {
          out.append(' ');
          i++;
        }
      } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
        int end = src.indexOf("*/", i + 2);
        int stop = end < 0 ? n : end + 2;
        for (; i < stop; i++) out.append(blank(src.charAt(i)));
      } else if (src.startsWith("\"\"\"", i)) {
        // a text block: everything up to the closing """ that is not escaped
        int j = i + 3;
        while (j < n && !src.startsWith("\"\"\"", j)) j += src.charAt(j) == '\\' ? 2 : 1;
        int stop = Math.min(n, j + 3);
        for (; i < stop; i++) out.append(blank(src.charAt(i)));
      } else if (c == '"' || c == '\'') {
        out.append(' ');
        i++;
        while (i < n && src.charAt(i) != c && src.charAt(i) != '\n' && src.charAt(i) != '\r') {
          if (src.charAt(i) == '\\' && i + 1 < n) {
            out.append(' ');
            i++;
          }
          out.append(blank(src.charAt(i)));
          i++;
        }
        if (i < n) {
          out.append(blank(src.charAt(i)));
          i++;
        }
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  static char blank(char c) {
    return c == '\n' || c == '\r' ? c : ' ';
  }

  /**
   * The code (comments and literals already blanked) as ONE line: every run of whitespace, newlines included,
   * becomes a single space, and none is kept next to a dot -- so "Thread\n    .sleep(1)" reads "Thread.sleep(1)".
   * {@code lineOf[i]} is the source line of the i-th character kept, read from {@code srcLine}.
   */
  static String flatten(String code, int[] srcLine, int[] lineOf) {
    StringBuilder out = new StringBuilder(code.length());
    int i = 0, n = code.length();
    while (i < n) {
      char c = code.charAt(i);
      if (!Character.isWhitespace(c)) {
        lineOf[out.length()] = srcLine[i];
        out.append(c);
        i++;
        continue;
      }
      int startLine = srcLine[i];
      while (i < n && Character.isWhitespace(code.charAt(i))) i++;
      char before = out.length() == 0 ? ' ' : out.charAt(out.length() - 1);
      char after = i < n ? code.charAt(i) : ' ';
      if (out.length() == 0 || before == '.' || after == '.') continue;
      lineOf[out.length()] = startLine;
      out.append(' ');
    }
    return out.toString();
  }

  /** {@code X::new} (X may be qualified or an array type) -- a constructor reference. */
  static final Pattern CONSTRUCTOR_REF = Pattern.compile("([\\w.$]+(?:\\[\\])*) ?:: ?new\\b");
  /** {@code ::m} or {@code ::<T>m} -- any other method reference. */
  static final Pattern METHOD_REF = Pattern.compile(" ?:: ?(?:<[^<>]*> ?)?(\\w+)");

  /**
   * Method references read as the calls they stand for: {@code X::new} becomes {@code new X(#} and {@code X::m}
   * becomes {@code X.m(#}. The {@code #} is not Java, so a rule that inspects a call's argument (cancel) sees an
   * argument it cannot vouch for. {@code lines} is rewritten alongside; the replacement takes the line of the reference.
   */
  static String references(String flat, int[][] lines) {
    flat = replace(flat, lines, CONSTRUCTOR_REF, m -> "new " + m.group(1) + "(#");
    return replace(flat, lines, METHOD_REF, m -> "." + m.group(1) + "(#");
  }

  static String replace(String s, int[][] lines, Pattern p, Function<Matcher, String> with) {
    int[] in = lines[0];
    int[] out = new int[s.length() * 2 + 8];
    StringBuilder b = new StringBuilder(s.length());
    Matcher m = p.matcher(s);
    int last = 0;
    while (m.find()) {
      for (int k = last; k < m.start(); k++) {
        out[b.length()] = in[k];
        b.append(s.charAt(k));
      }
      String r = with.apply(m);
      for (int k = 0; k < r.length(); k++) out[b.length() + k] = in[m.start()];
      b.append(r);
      last = m.end();
    }
    for (int k = last; k < s.length(); k++) {
      out[b.length()] = in[k];
      b.append(s.charAt(k));
    }
    lines[0] = out;
    return b.toString();
  }

  /** Every hit in one file, as "file:line: rule: the code on that line" -- once per rule and line. */
  /** The rules the TEST source is held to as well: file I/O through Filepath only, and no Filepath.Unchecked. */
  static final List<Rule> TEST_RULES = RULES.stream().filter(r -> r.name.equals("java.io file APIs") || r.name.equals("Filepath.Unchecked"))
    .collect(Collectors.toList());
  /** The one test file exempt from {@link #TEST_RULES}, by its path under src/test/java. */
  static final String TEST_FILES_HELPER = "com/evi/live/TestFiles.java";

  static List<String> scan(String file, String src) {
    return scan(file, src, RULES);
  }

  static List<String> scan(String file, String src, List<Rule> rules) {
    List<String> hits = new ArrayList<>();
    String[] lines = src.split("\n", -1);
    int[] srcLine = new int[src.length() + 1];
    String decoded = unescape(src, srcLine);
    String code = code(decoded);
    int[] flatLine = new int[code.length() + 1];
    String flat = flatten(code, srcLine, flatLine);
    int[][] box = {flatLine};
    flat = references(flat, box);
    int[] lineOf = box[0];
    for (Rule r : rules) {
      Matcher m = r.pattern.matcher(flat);
      java.util.Set<Integer> seen = new java.util.TreeSet<>();
      while (m.find()) {
        int k = lineOf[m.start()];
        if (seen.add(k)) hits.add(file + ":" + k + ": " + r.name + ": " + lines[Math.min(k, lines.length) - 1].trim());
      }
    }
    return hits;
  }

  /** The scanner on snippets whose answer is known: each banned form is found, each allowed form is not. */
  static void selfTest() {
    String[] bad = {
      "Field f = x.getClass().getDeclaredField(\"a\");", "f.setAccessible(true);", "import java.lang.reflect.Method;",
      "Object o = Class.forName(name).newInstance();", "public native int peek(long a);", "System.loadLibrary(\"x\");",
      "new ProcessBuilder(\"cmd\").start();", "Runtime.getRuntime().exec(\"x\");", "Desktop.getDesktop().browse(uri);",
      "LinkBrowser.open(url);", "Thread.sleep(100);", "worker.interrupt();", "executor.shutdownNow();", "client.menuAction(1, 2);",
      "new java.io.File(\"x\");", "new File(dir, name)", "try (FileInputStream in = open()) {", "Files.readAllBytes(p);",
      "java.nio.file.Paths.get(\"x\")", "Path.of(\"x\")", "FileChannel.open(p)", "dir.toFile()", "KeyboardFocusManager.getCurrentKeyboardFocusManager();",
      "HttpURLConnection c = (HttpURLConnection) u.openConnection();", "java.net.http.HttpClient.newHttpClient();", "new Socket(\"h\", 1);",
      "Gson g = new Gson();", "new GsonBuilder().create()", "new com.google.gson.Gson()", "OkHttpClient c = new OkHttpClient();",
      "new OkHttpClient.Builder().build()", "Filepath.Unchecked.getRooted(p)", "package net.runelite.client.plugins.evi;",
      "new URLClassLoader(urls)", "sun.misc.Unsafe u;",
      // 7 Oct 2026, the verifier's blind spots: each was a main-source mutation the scan let through.
      "java.util.concurrent.TimeUnit.MILLISECONDS.sleep(1);", "unit.sleep(5);", "import static java.lang.Thread.sleep;", "sleep(1);",
      "import static java.lang.Thread.*;", "Thread\n      .sleep(1);", "Thread . sleep ( 1 );", "f.cancel(true);", "future.cancel(mayInterrupt);",
      "pool.invokeAll(tasks, 1, TimeUnit.SECONDS);", "pool.invokeAny(tasks);", "Thread.currentThread()\n  .interrupt();",
      "import java.io.*;", "Object t = File.createTempFile(\"a\", \"b\");", "File[] roots = File.listRoots();",
      "net.runelite.client.RuneLite.RUNELITE_DIR.listFiles();", "import static net.runelite.client.RuneLite.RUNELITE_DIR;", "RuneLite.CACHE_DIR",
      "Path p = RuneLite.PLUGIN_DATA;", "javax.imageio.ImageIO.read(new java.net.URL(\"http://example.invalid/x.png\"));", "import java.net.URL;",
      "URL u = new URL(s);", "URI.create(s).toURL()",
      // 7 Oct 2026, second pass: the planted file that stayed green, line by line.
      "t.forEach(Thread::interrupt);", "Optional.of(e).ifPresent(ExecutorService::shutdownNow);", "new java.io.PrintWriter(\"x.txt\");",
      "java.nio.channels.SocketChannel.open(new java.net.InetSocketAddress(\"h\", 1));",
      "java.beans.Introspector.getBeanInfo(c).getPropertyDescriptors()[0].getReadMethod().invoke(o);", "java.nio.file.FileSystems.getDefault();", "import java.nio.file.FileAlreadyExistsException;", "throw new java.nio.file.FileSystemException(name);",
      "java.net.InetAddress.getByName(h);",
      // ...method references to every banned call...
      "LongConsumer s = Thread::sleep;", "Consumer<Long> s = TimeUnit.SECONDS::sleep;", "Consumer<Long> s = TimeUnit.SECONDS :: sleep;",
      "Function<String, Class<?>> f = Class::forName;", "BiConsumer<Client, Integer> m = Client::menuAction;", "Supplier<Gson> g = Gson::new;",
      "Supplier<GsonBuilder> g = GsonBuilder::new;", "Supplier<OkHttpClient> c = OkHttpClient::new;", "BiConsumer<Future<?>, Boolean> c = Future::cancel;",
      "Function<Object, Object> f = Desktop::getDesktop;", "Consumer<Thread> i = Thread\n  ::interrupt;", "Function<File, Object> f = File::new;",
      // ...sockets, other file openers, FocusManager...
      "javax.net.ssl.SSLSocketFactory.getDefault().createSocket(h, 443);", "client.sslSocketFactory().createSocket(s, h, 443, true);",
      "client.socketFactory().createSocket();", "new java.io.PrintStream(\"f\");", "import java.io.PrintStream;", "new java.util.zip.ZipFile(\"f\");",
      "import java.util.zip.ZipFile;", "new ZipFile(name);", "new java.util.jar.JarFile(name);", "new java.util.Formatter(\"out.txt\");",
      "javax.swing.FocusManager.getCurrentManager();", "FocusManager.getCurrentManager();", "import java.net.InetSocketAddress;", "Inet4Address a;",
      "import java.nio.channels.SocketChannel;", "java.nio.channels.AsynchronousFileChannel.open(p);", "import java.nio.file.Path;",
      "import static java.nio.file.Files.readAllBytes;", "jdk.internal.misc.Unsafe u;", "com.sun.net.httpserver.HttpServer.create();",
      "java.lang.management.ManagementFactory.getPlatformMBeanServer();", "ProcessHandle.current();", "java.util.concurrent.locks.LockSupport.parkNanos(1);",
      "import java.util.zip.*;", "import static java.lang.Class.forName;", "import static java.lang.System.loadLibrary;",
      "import static net.runelite.client.util.LinkBrowser.open;", "java.rmi.Naming.lookup(u);", "new javax.naming.InitialContext();",
      // ...and Unicode-escape evasion, decoded exactly as javac decodes it (these are the raw source characters).
      "Thread.\\u0073leep(1);", "Thread.\\uuuu0073leep(1);", "\\u0054hread.sleep(1);",
      "String a = \"x\\u0022; Thread.sleep(1); String b = \\u0022y\";", "int a; // \\u000a Thread.sleep(1);", "int a; // \\u000d Thread.sleep(1);",
      "/* \\u002a/ Thread.sleep(1); /* */", "String s = \"\"\"\n  x\n  \"\"\"; Thread.sleep(1);"};
    for (String s : bad) check(!scan("bad", s).isEmpty(), "the scanner missed a banned form: " + s);
    String[] good = {
      "throw new RuntimeException(e);", "} catch (IOException | RuntimeException e) {", "import java.io.IOException;", "import java.io.UncheckedIOException;",
      "try (InputStream in = f.openInputStream()) {", "BufferedReader r = fp.openBufferedReader();", "this.client = shared.newBuilder().build();",
      "gson.fromJson(s, JsonElement.class)", "executor.shutdown();", "executor.awaitTermination(5, TimeUnit.SECONDS);", "Thread t = Thread.currentThread();",
      "// never Thread.sleep(1) or shutdownNow() here", "/* Filepath.Unchecked is restricted */ int x;", "String s = \"new Gson() Thread.sleep(1)\";",
      "char q = '\"'; int y = 1;", "String esc = \"a \\\" Runtime.getRuntime() \\\" b\";", "LinkBrowser.browse(url);", "f.moveTo(other, StandardCopyOption.REPLACE_EXISTING);",
      "dir.joinSegment(\"x\").openFileChannel(StandardOpenOption.READ)", "Filepath dir = getPluginDirectory();", "MyFiles.load()", "nativeName = 1;",
      "package com.evi.live;",
      // ...and the allowed forms next to the new rules.
      "future.cancel(false);", "call.cancel();", "timer.cancel( );", "TimeUnit.MILLISECONDS.toMillis(1);", "boolean asleep = isAsleep(x);",
      "String name = f.getFileName();", "JournalFile.size(f)", "Filepath fp;", "root.joinSegment(IMPORT_DIR)", "static final String JOURNAL_DIR = x;",
      "Profile f = files.get(0);", "executor.invokeLater(r);", "a.b\n  .c(1);",
      // second pass: the imports and references the plugin really uses, and look-alikes of the new rules.
      "import java.io.BufferedReader;", "import java.io.InputStream;", "import java.io.OutputStream;", "import java.net.Proxy;", "import java.net.URLEncoder;",
      "import java.net.URI;", ".proxy(Proxy.NO_PROXY)", "import java.nio.channels.FileChannel;",
      "import java.nio.file.StandardCopyOption;", "import java.nio.file.StandardOpenOption;", "import java.nio.file.attribute.FileTime;",
      "import java.nio.charset.StandardCharsets;", "import java.util.List;", "import static java.util.Objects.requireNonNull;",
      "java.util.List<String> xs = new java.util.ArrayList<>();", "map.merge(k, 1, Integer::sum);", "Store.replay(all, this::write);",
      "types.stream().map(Enum::name)", "clientThread.invokeLater(Handler.this::fill);", "new Panel(this::pair, this::skip);", "Supplier<Thread> t = Thread::new;",
      "x.sort(Store::jsDefaultCompare);", "LongSupplier c = System::currentTimeMillis;", "boolean b = x ? y : z;", "String s = \"\\u00b7 yours\";",
      "String p = \"a\\\\u0073leep(1)\";", "int sunny = 1; int x = sunny.length;", "Object o = this.jdkVersion;", "String a = \"x\\\\\"; int y = 1;"};
    for (String s : good) check(scan("good", s).isEmpty(), "the scanner flagged an allowed form: " + s + " -> " + scan("good", s));
    // Line numbers survive a block comment spanning lines, and an escaped newline.
    List<String> h = scan("f", "/* one\n two */\nint a;\nThread.sleep(1);\n");
    check(h.size() == 1 && h.get(0).startsWith("f:4: Thread.sleep"), "a hit must carry its real line number: " + h);
    h = scan("f", "int a;\n// \\u000a\\u000a\\u000a x\nint b;\nThread.sleep(1);\n");
    check(h.size() == 1 && h.get(0).startsWith("f:4: Thread.sleep"), "an escaped newline must not move a reported line: " + h);
    h = scan("f", "int a;\nRunnable r =\n  Thread::interrupt;\n");
    check(h.size() == 1 && h.get(0).startsWith("f:3: Thread.sleep / interrupt"), "a method reference reports the line of its :: : " + h);
    // The decoder itself, against JLS 3.3 by value: an escaped backslash is not an escape, three backslashes are.
    int[] l = new int[64];
    check("A".equals(unescape("\\u0041", l)), "\\u0041 decodes to A");
    check("A".equals(unescape("\\uuu0041", l)), "several u's are one escape");
    check("\\\\u0041".equals(unescape("\\\\u0041", l)), "an escaped backslash does not start an escape: " + unescape("\\\\u0041", l));
    check("\\\\A".equals(unescape("\\\\\\u0041", l)), "an even run of backslashes before it does: " + unescape("\\\\\\u0041", l));
    check("\\u0041".equals(unescape("\\u005c\\u0041".replace("\\u0041", "u0041"), l)), "a backslash made by an escape does not start another");
    check("\\uZZZZ".equals(unescape("\\uZZZZ", l)), "not hex: left alone");
  }

  public static void main(String[] args) throws Exception {
    selfTest();
    String root = System.getProperty("evi.mainSource");
    check(root != null && !root.isEmpty(), "evi.mainSource is not set");
    Filepath dir = TestFiles.rooted(root);
    List<Filepath> files = TestFiles.files(dir, ".java");
    check(files.size() >= 40, "only " + files.size() + " source files found under " + dir + ": the scan would prove nothing");
    List<String> hits = new ArrayList<>();
    for (Filepath p : files) hits.addAll(scan(TestFiles.relative(dir, p), TestFiles.text(p)));
    check(hits.isEmpty(), "the plugin's main source uses APIs the Plugin Hub forbids:\n  " + String.join("\n  ", hits));

    // The tests: file I/O through Filepath only, every file but the TestFiles helper.
    check(TEST_RULES.size() == 2, "the test scan must carry the two file rules (a renamed rule would drop out silently): " + TEST_RULES.size());
    String testRoot = System.getProperty("evi.testSource");
    check(testRoot != null && !testRoot.isEmpty(), "evi.testSource is not set");
    Filepath tdir = TestFiles.rooted(testRoot);
    List<Filepath> tests = TestFiles.files(tdir, ".java");
    check(tests.size() >= 30, "only " + tests.size() + " test files found under " + tdir + ": the scan would prove nothing");
    List<String> testHits = new ArrayList<>();
    boolean helper = false;
    for (Filepath p : tests) {
      String rel = TestFiles.relative(tdir, p);
      if (rel.equals(TEST_FILES_HELPER)) {
        helper = true;
        continue;
      }
      testHits.addAll(scan(rel, TestFiles.text(p), TEST_RULES));
    }
    check(helper, "the scan must find " + TEST_FILES_HELPER + " (the one exempt file) where it expects it");
    check(testHits.isEmpty(), "the tests do file I/O other than through Filepath (only TestFiles may aim one at a folder):\n  " + String.join("\n  ", testHits));
    System.out.println("PASS: Hub forbidden-API scan (" + files.size() + " main source files, " + RULES.size() + " rules; " + tests.size()
      + " test files, " + TEST_RULES.size() + " file rules; " + checks + " checks, 0 hits)");
  }
}
