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
        if (response.code() != 200) return null;
        ResponseBody body = response.body();
        return body == null ? null : body.string();
      }
    }
  }
}
