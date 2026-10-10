package com.evi.live.market;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * THE PLUGIN'S ONLY NETWORK CLASS. Every request EVI makes is made here, and only to the OSRS Wiki's public real-time
 * prices API, {@code https://prices.runescape.wiki/api/v2/osrs/}.
 *
 * <p>What it can ask for is fixed by the method list, not by a caller: {@link #latest()}, {@link #mapping()} and
 * {@link #hour(long)}. All three are ALL-ITEMS endpoints, the same requests for every player, so the Wiki learns
 * nothing about which items EVI is looking at. Nothing about the player is ever sent: the only variable part of any
 * URL is the hour's own timestamp, and the User-Agent names the plugin, its version and its public source page --
 * no account, name, cash, offer or trade history, in the URL, the headers or the User-Agent.
 *
 * <p>How it asks:
 * <ul>
 *   <li>through RuneLite's own injected {@link OkHttpClient}, derived with {@code newBuilder()} (never a new client),
 *       with the shared response cache turned OFF so hundreds of archive responses do not churn the 20 MB cache
 *       every other plugin uses, redirects refused, and a 20 s limit on the whole call;</li>
 *   <li>NEVER on the client thread or the Swing EDT: it refuses before sending (RuneLite's client would also throw);</li>
 *   <li>ONE request in flight at a time, across EVERY instance of this class (the guard is static, so a plugin turned
 *       off and on while a request is out cannot add a second one through a new instance): a concurrent call is
 *       refused, never queued. Spacing between requests and retries with back-off belong to the caller's scheduler
 *       ({@link PriceDataService}, which also hands its pacing to the next generation), because a wait here would
 *       need {@code Thread.sleep}, which the Hub forbids;</li>
 *   <li>a descriptive User-Agent, which is the one thing the Wiki asks of API users (generic Java agents are blocked
 *       pre-emptively). RuneLite prefixes its own {@code RuneLite/<version>} to it;</li>
 *   <li>answers above 10 MB are refused (the largest real one, {@code /mapping}, is under 1 MB).</li>
 * </ul>
 */
public final class WikiPriceClient {
  public static final String HOST = "prices.runescape.wiki";
  static final String BASE_PATH = "/api/v2/osrs/";
  static final long MAX_BYTES = 10_000_000L;
  static final long CALL_TIMEOUT_SECONDS = 20;
  /**
   * The plugin's version as it identifies itself. Must equal {@code version} in build.gradle and
   * runelite-plugin.properties; {@code MarketIntentTest.oneNetworkClass} fails the build when it does not, so it cannot drift
   * the way an older User-Agent version once did.
   */
  public static final String VERSION = "4.0.0";
  /** Where the source lives: the contact point the Wiki asks for, and nothing that is not already public. */
  static final String HOME = "https://github.com/therealLeEvi/evi-live-plugin";

  /** The User-Agent for one purpose: {@code EVI-Live/<version> (<what>; <purpose>; +<home>)}. */
  static String userAgent(String purpose) {
    return "EVI-Live/" + VERSION + " (RuneLite plugin; " + purpose + "; +" + HOME + ")";
  }

  /** The derived client (package-private so a test can read its settings: no cache, no redirects, the call limit). */
  final OkHttpClient http;
  private final BooleanSupplier forbiddenThread;
  /** One request in flight for the whole plugin, not per instance: see the class comment. */
  private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();

  /**
   * @param injected        RuneLite's own OkHttpClient (injected into the plugin)
   * @param forbiddenThread true while the caller is on a thread that must never block on the network: the client
   *                        thread (the plugin passes {@code client::isClientThread}); the Swing EDT is checked here too
   */
  public WikiPriceClient(OkHttpClient injected, BooleanSupplier forbiddenThread) {
    this.http = injected.newBuilder()
      .cache(null)
      .followRedirects(false)
      .followSslRedirects(false)
      .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .build();
    this.forbiddenThread = forbiddenThread;
  }

  /** {@code /latest}: every item's last instant-buy and instant-sell price. About 72 KB on the wire. */
  public String latest() throws IOException {
    return get("latest", null, "live prices");
  }

  /** {@code /mapping}: every item's name, buy limit and members flag. About 140 KB on the wire. */
  public String mapping() throws IOException {
    return get("mapping", null, "item list");
  }

  /** {@code /1h?timestamp=<ts>}: one hour of every item's averages. {@code ts} must be the start of an hour (unix seconds). */
  public String hour(long ts) throws IOException {
    if (ts <= 0 || ts % 3600 != 0) throw new IllegalArgumentException("not the start of an hour: " + ts);
    return get("1h", Long.toString(ts), "hourly price history");
  }

  /** The URL each method asks for. Package-private so a test can pin every byte of it. */
  static HttpUrl url(String endpoint, String timestamp) {
    HttpUrl.Builder b = new HttpUrl.Builder().scheme("https").host(HOST).encodedPath(BASE_PATH + endpoint);
    if (timestamp != null) b.addQueryParameter("timestamp", timestamp);
    return b.build();
  }

  private String get(String endpoint, String timestamp, String purpose) throws IOException {
    if (forbiddenThread.getAsBoolean() || javax.swing.SwingUtilities.isEventDispatchThread())
      throw new IllegalStateException("EVI prices: a network request on the client thread or the Swing EDT was refused");
    if (!IN_FLIGHT.compareAndSet(false, true)) throw new IllegalStateException("EVI prices: one Wiki request at a time");
    try {
      Request request = new Request.Builder().url(url(endpoint, timestamp)).header("User-Agent", userAgent(purpose)).get().build();
      try (Response response = http.newCall(request).execute()) {
        if (response.code() != 200) throw new IOException("the Wiki answered HTTP " + response.code());
        ResponseBody body = response.body();
        if (body == null) throw new IOException("the Wiki sent no body");
        BufferedSource source = body.source();
        if (source.request(MAX_BYTES + 1)) throw new IOException("the Wiki's answer is larger than " + MAX_BYTES + " bytes");
        return source.getBuffer().readUtf8();
      }
    } finally {
      IN_FLIGHT.set(false);
    }
  }
}
