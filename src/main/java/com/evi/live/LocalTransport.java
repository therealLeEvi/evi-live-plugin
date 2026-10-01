package com.evi.live;

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.CacheControl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

interface LocalTransport {
  int send(String key, String json) throws IOException;
  /**
   * Returns the response body, or null on any non-200 status. Never throws on a plain rejection.
   * query is a URL query string without a leading '?' (e.g. "minProfit=500000&risk=high"), or ""
   * for none -- built entirely from this plugin's own local config, never from observed content.
   */
  String get(String key, String query) throws IOException;
  /** The HTTP status of the most recent {@link #get}, or 0 if none has run.
   *
   * A rejected key and an unreachable bridge both surface as a null body, and telling the player to
   * check something that is not wrong is the worst kind of error message: EVI's second user was shown
   * "Bridge unreachable" and "Bridge rejected the key" in the same panel, while the bridge was
   * running and answering. This is what lets the suggestion path say which one actually happened. */
  default int lastGetStatus() { return 0; }

  /**
   * Asks the bridge which API it speaks: GET /api/version, which answers {"api":N,"packet":N}.
   * Returns N, or -1 when it could not be read at all.
   *
   * <p>A bridge from BEFORE that route existed answers 401 here, because the request falls through
   * to the scanner gate -- so a 401 is not necessarily a bad key, and the caller distinguishes the
   * two by whether ordinary suggestion calls are working (see EviLivePlugin.refreshBridgeApi).
   *
   * <p>Default returns -1 so the anonymous test doubles keep compiling, the same reason
   * lastGetStatus above is a default. Only Http actually talks to the bridge.
   */
  default int version(String key) throws IOException { return -1; }

  /**
   * Flags (or unflags) a specific, already-observed buy offer as personal use with the bridge --
   * see Store.markPersonalUse in bridge/store.mjs and EviLivePlugin.flagPersonalUse. json is the
   * full request body (e.g. {"buyId":"...","personal":true}), built by the caller the same way
   * send()'s json is. Returns the response status; a caller that only cares "did this succeed"
   * treats anything other than 200 as a no-op, since the session-local exclusion already applied
   * regardless of whether this call lands. Default implementation returns 0 ("not attempted") so
   * LocalTransport implementations written before this method existed (test fixtures) don't need
   * updating -- only Http actually talks to the bridge.
   */
  default int markPersonalUse(String key, String json) throws IOException { return 0; }

  /**
   * Tells the bridge the player no longer holds what a "you're holding this" suggestion refers to --
   * used in-game, or sold while EVI wasn't watching (see Store.closePosition in bridge/store.mjs and
   * EviLivePlugin.flagNotHeld). Same request/response shape and same "0 = not attempted" default as
   * markPersonalUse above; whatever part of that purchase EVI did see sold still counts as profit.
   */
  default int markNotHeld(String key, String json) throws IOException { return 0; }

  /**
   * The sidebar's Block button: tells the bridge never to suggest buying this item again (see
   * setBlocked in bridge/server.mjs). Same shape and same "0 = not attempted" default as the two
   * above. Undone from the scanner, which lists blocked items by name.
   */
  default int markBlocked(String key, String json) throws IOException { return 0; }
  // "I took this one": the only way EVI learns whether following it actually worked. Defaulted so a
  // test double need not implement it, exactly like the buttons above.
  default int markAccepted(String key, String json) throws IOException { return 0; }

  /**
   * The sidebar's "Reset" beside its profit line: start counting realised profit from now (see
   * resetProfit in bridge/server.mjs). Same shape and same "0 = not attempted" default as the others.
   */
  default int resetProfit(String key, String json) throws IOException { return 0; }

  /**
   * Fixed destinations, no listener, redirects, system proxy or game commands.
   *
   * Built on RuneLite's own injected {@link OkHttpClient} rather than a client of its own, so every
   * request this plugin makes goes through the client the user's RuneLite installation already
   * governs -- its connection pool, dispatcher and interceptors. The only things adjusted here are
   * the ones specific to talking to a bridge on this same machine: short timeouts (a loopback
   * service either answers immediately or isn't running), no system proxy, and no redirects, so a
   * request to 127.0.0.1 can never be talked into going anywhere else.
   */
  final class Http implements LocalTransport {
    private static final MediaType JSON = MediaType.parse("application/json");
    private final OkHttpClient client;

    Http(OkHttpClient shared) {
      this.client = shared.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .writeTimeout(2000, TimeUnit.MILLISECONDS)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .build();
    }

    public int send(String key, String json) throws IOException {
      return post("http://127.0.0.1:51743/api/events", key, json);
    }
    public int markPersonalUse(String key, String json) throws IOException {
      return post("http://127.0.0.1:51743/api/suggestion/personal-use", key, json);
    }
    public int markNotHeld(String key, String json) throws IOException {
      return post("http://127.0.0.1:51743/api/suggestion/not-held", key, json);
    }
    public int markBlocked(String key, String json) throws IOException {
      return post("http://127.0.0.1:51743/api/suggestion/block", key, json);
    }
    public int markAccepted(String key, String json) throws IOException {
      return post("http://127.0.0.1:51743/api/suggestion/accept", key, json);
    }
    public int resetProfit(String key, String json) throws IOException {
      return post("http://127.0.0.1:51743/api/profit/reset", key, json);
    }
    private int post(String url, String key, String json) throws IOException {
      // Deliberately the byte[] overload, not the String one. RequestBody.create(MediaType, String)
      // rewrites the type to "application/json; charset=utf-8", which a bridge that checks the
      // header for an exact "application/json" rejects with HTTP 400 -- observed against a real
      // client the first time this class used OkHttp. Encoding the UTF-8 bytes here sends the bare
      // type, so a plugin update can never break against a bridge the player hasn't updated yet.
      Request request = new Request.Builder()
        .url(url)
        .header("Authorization", "Bearer " + key)
        .post(RequestBody.create(JSON, json.getBytes(StandardCharsets.UTF_8)))
        .build();
      try(Response response = client.newCall(request).execute()) { return response.code(); }
    }
    private volatile int lastGet;
    @Override public int lastGetStatus() { return lastGet; }
    @Override
    public int version(String key) throws IOException {
      Request request = new Request.Builder()
        .url("http://127.0.0.1:51743/api/version")
        .header("Authorization", "Bearer " + key)
        .cacheControl(CacheControl.FORCE_NETWORK)
        .get()
        .build();
      try(Response response = client.newCall(request).execute()) {
        // 401 from a bridge too old to have this route at all. Reported as 0 rather than -1, which
        // means "could not tell": an old bridge IS an answer, and the whole point of this call.
        if (response.code() == 401) return 0;
        if (response.code() != 200) return -1;
        ResponseBody body = response.body();
        if (body == null) return -1;
        // Deliberately not parsed with Gson: this is one integer from a local process, and a
        // malformed answer must leave the player alone rather than throw inside the poll thread.
        java.util.regex.Matcher m = java.util.regex.Pattern
          .compile("\"api\"\\s*:\\s*(\\d+)").matcher(body.string());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
      } catch (RuntimeException ex) {
        return -1;
      }
    }

    public String get(String key, String query) throws IOException {
      String url = "http://127.0.0.1:51743/api/suggestion" + (query == null || query.isEmpty() ? "" : "?" + query);
      Request request = new Request.Builder()
        .url(url)
        .header("Authorization", "Bearer " + key)
        // A suggestion is a live reading of the player's own journal and today's prices; serving a
        // cached one from a shared client's cache would quietly show a stale trade.
        .cacheControl(CacheControl.FORCE_NETWORK)
        .get()
        .build();
      try(Response response = client.newCall(request).execute()) {
        lastGet = response.code();
        if (response.code() != 200) return null;
        ResponseBody body = response.body();
        return body == null ? null : body.string();
      }
    }
  }
}
