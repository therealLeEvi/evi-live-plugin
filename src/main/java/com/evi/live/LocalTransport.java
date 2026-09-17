package com.evi.live;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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

  /** Fixed destinations, no listener, redirects, system proxy or game commands. */
  final class Http implements LocalTransport {
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
      HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection(Proxy.NO_PROXY);
      try {
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(2000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
        return connection.getResponseCode();
      } finally { connection.disconnect(); }
    }
    public String get(String key, String query) throws IOException {
      String url = "http://127.0.0.1:51743/api/suggestion" + (query == null || query.isEmpty() ? "" : "?" + query);
      HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection(Proxy.NO_PROXY);
      try {
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(2000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        if (connection.getResponseCode() != 200) return null;
        try(InputStream in = connection.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
      } finally { connection.disconnect(); }
    }
  }
}
