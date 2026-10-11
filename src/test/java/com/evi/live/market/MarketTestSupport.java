package com.evi.live.market;

import com.evi.live.TestFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import net.runelite.client.util.Filepath;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Test-side helpers for the price-data tests. Nothing here ships (tests are not in the plugin jar).
 *
 * <p>{@link FakeWiki} is the RECORDED-RESPONSE network: an OkHttp application interceptor that answers every call
 * from a function of the URL and never opens a socket, while recording exactly what was asked (URL, headers, the
 * thread, when, and how many calls were in flight at once). It is installed on the "injected" client, so it reaches
 * {@link WikiPriceClient} through the same {@code newBuilder()} derivation the real RuneLite client goes through.
 */
final class MarketTestSupport {
  private MarketTestSupport() {}

  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  /** A temp folder as a Filepath (TestFiles.tempDir); the plugin uses getPluginDirectory(). */
  static Filepath tempRoot(String prefix) throws IOException {
    return TestFiles.tempDir(prefix);
  }

  static void deleteTree(Filepath f) {
    try {
      f.deleteRecursively();
    } catch (IOException ignored) {
      // a temp folder; the OS cleans it
    }
  }

  /** A classpath resource, gunzipped when its name ends in .gz; null when absent. */
  static InputStream open(String resource) throws IOException {
    InputStream in = MarketTestSupport.class.getResourceAsStream(resource);
    if (in == null) return null;
    return resource.endsWith(".gz") ? new GZIPInputStream(in) : in;
  }

  static JsonElement readJson(String resource) throws IOException {
    try (InputStream in = open(resource)) {
      if (in == null) return null;
      try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        return new JsonParser().parse(r);
      }
    }
  }

  static String sha256Hex(String text) throws Exception {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    for (byte b : d) sb.append(String.format("%02x", b & 0xff));
    return sb.toString();
  }

  /** One recorded request. */
  static final class Seen {
    final String url;
    final Request request;
    final String thread;
    final long atNanos;

    Seen(Request request, String thread, long atNanos) {
      this.url = request.url().toString();
      this.request = request;
      this.thread = thread;
      this.atNanos = atNanos;
    }
  }

  /** What the fake answers: a status and a body, or an IOException to throw. */
  static final class Answer {
    final int code;
    final String body;
    final IOException error;

    Answer(int code, String body, IOException error) {
      this.code = code;
      this.body = body;
      this.error = error;
    }

    static Answer ok(String body) {
      return new Answer(200, body, null);
    }

    static Answer status(int code) {
      return new Answer(code, "{}", null);
    }

    static Answer fail(String message) {
      return new Answer(0, null, new IOException(message));
    }
  }

  static final class FakeWiki implements Interceptor {
    final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    volatile Function<String, Answer> answer;
    /** Called while a request is "in flight" (after it is counted, before it is answered): tests block here. */
    volatile Runnable during;

    FakeWiki(Function<String, Answer> answer) {
      this.answer = answer;
    }

    @Override public Response intercept(Chain chain) throws IOException {
      Request req = chain.request();
      int n = inFlight.incrementAndGet();
      maxInFlight.accumulateAndGet(n, Math::max);
      try {
        seen.add(new Seen(req, Thread.currentThread().getName(), System.nanoTime()));
        Runnable d = during;
        if (d != null) d.run();
        Answer a = answer.apply(req.url().toString());
        if (a.error != null) throw a.error;
        return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(a.code).message("x")
          .body(ResponseBody.create(MediaType.parse("application/json"), a.body)).build();
      } finally {
        inFlight.decrementAndGet();
      }
    }

    /** RuneLite's injected client, stood in for: the fake sits where the network would be. */
    OkHttpClient injected() {
      return new OkHttpClient.Builder().addInterceptor(this).build();
    }

    List<String> urls() {
      List<String> out = new ArrayList<>();
      synchronized (seen) {
        for (Seen s : seen) out.add(s.url);
      }
      return out;
    }
  }

  /** A /1h body for an hour: n items with integer or fractional prices. */
  static String hourBody(long ts, int items, boolean fractional) {
    StringBuilder sb = new StringBuilder("{\"data\":{");
    for (int i = 0; i < items; i++) {
      if (i > 0) sb.append(',');
      long base = 100 + i * 7L;
      sb.append('"').append(2 + i).append("\":{\"avgHighPrice\":").append(fractional ? base + ".5" : Long.toString(base))
        .append(",\"highPriceVolume\":").append(10 + i).append(",\"avgLowPrice\":").append(base - 3).append(",\"lowPriceVolume\":").append(5 + i).append('}');
    }
    return sb.append("},\"timestamp\":").append(ts).append('}').toString();
  }

  static final String MAPPING_BODY = "[{\"id\":2,\"name\":\"Cannonball\",\"limit\":11000,\"members\":true},{\"id\":561,\"name\":\"Nature rune\",\"limit\":18000,\"members\":false}]";
  static final String LATEST_BODY = "{\"data\":{\"2\":{\"high\":180,\"highTime\":1790000000,\"low\":176,\"lowTime\":1790000010},\"561\":{\"high\":214,\"highTime\":1790000020,\"low\":null,\"lowTime\":null}}}";
}
