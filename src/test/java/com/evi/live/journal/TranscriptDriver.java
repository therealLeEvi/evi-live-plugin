package com.evi.live.journal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays one golden transcript (format evi-golden-transcript/1, recorded from the real bridge) through the
 * Java {@link Store} and compares every journal-owned answer with what the JS bridge recorded:
 *
 * <ul>
 *   <li>POST /api/events -- {duplicate} or the exact 400 message</li>
 *   <li>GET /api/state -- the whole body: sessions, active/occupied, completed with proceeds, manual and
 *       automatic flips, open positions (cost basis), unmatched sells, data health, personal use, imports,
 *       closed positions, net profit, trade count</li>
 *   <li>store.buyLimitUsage calls -- {used, windowEndsAt}</li>
 *   <li>POST /api/suggestion/not-held and /personal-use -- the reply or the exact message</li>
 *   <li>POST /api/profit/reset, and the {@code profit} field of every GET /api/suggestion reply</li>
 *   <li>POST /api/suggestion/block -- the reply; the list it leaves in preferences.json is {@link #blockedNow()}, which the
 *       engine replays read (server.mjs keeps it in the preferences file, not the journal)</li>
 *   <li>POST /api/flips/import (the scanner's import, with its cookie, origin and x-evi-ui header) -- the reply</li>
 *   <li>a bridge restart -- the Store rebuilt by decoding the lines the JAVA engine journalled, so every
 *       restart also proves the line codec round-trips</li>
 * </ul>
 *
 * <p>The rest of a suggestion reply is the engine (Phase 3) and is not compared here. The server glue the
 * journal routes depend on is reproduced in a few lines and named as such: the plugin-key check, the
 * last-inventory memory that gives an item mark its kept count, the inventory-check memory behind
 * {@code dataHealth.inventoryCheck}, and the profit reset time. Each mirrors server.mjs; the last-inventory
 * one is an approximation (the bridge only refreshes it when the idle-inventory tier is reached) that is
 * exact for every recorded transcript and would show up as a diff the day it is not.
 */
public final class TranscriptDriver {
  /**
   * Called for every GET /api/suggestion step, after the journal glue has seen its query and before the next step runs, so
   * the Store it is handed holds exactly what the bridge's held when it answered (Phase 3b: the engine's tiers are composed
   * against it by EngineTranscriptTest).
   */
  public interface SuggestionObserver {
    void onSuggestion(int step, long at, String query, JsonObject response, Store store);
  }

  private SuggestionObserver observer;

  public void observe(SuggestionObserver o) {
    observer = o;
  }

  private final String name;
  public final List<String> diffs = new ArrayList<>();
  public int compared;
  /** The journal lines the JAVA store wrote (plus any seed lines), in order. */
  final List<String> lines = new ArrayList<>();
  private Store store;
  private Long profitSince;
  /** preferences.json's blocked list as the bridge holds it (readPreferences' filter), or null while no Block was pressed. */
  private List<Integer> blocked;
  private List<Integer> bootBlocked = new ArrayList<>();
  /**
   * The plugin's PER-CHARACTER lists (8 Oct 2026): each Block press goes to the account of the poll whose pick it was pressed on
   * (the request names none; the plugin presses for the account it polls for), "" when that poll named none.
   */
  private final Map<String, LinkedHashSet<Integer>> pressedBy = new HashMap<>();
  private String lastPolledAccount = "";
  private final Map<String, Object[]> inventoryChecks = new LinkedHashMap<>(); // key -> {account|null, at, List<Integer>}
  private final Map<String, Object[]> lastInventory = new HashMap<>();         // key -> {Map<Integer,Long>, at}
  private final Store.Journal sink = r -> lines.add(JournalCodec.encodeLine(r));

  public TranscriptDriver(String name) {
    this.name = name;
  }

  private void rebuild() {
    List<JournalRecord> records = new ArrayList<>();
    for (String l : lines) records.add(JournalCodec.decode(ParityJson.parse(l)));
    store = Store.replay(records, sink);
  }

  /** Boot as the bridge does: replay the seed journal (if any), and read preferences.json's profitSince. */
  public void boot(List<String> seedLines, JsonElement preferences) {
    Double since = preferences != null && preferences.isJsonObject() ? Js.number(preferences.getAsJsonObject().get("profitSince")) : null;
    profitSince = since == null || since.isNaN() || since.isInfinite() ? null : (long) (double) since;
    bootBlocked = new ArrayList<>();
    JsonElement b = preferences != null && preferences.isJsonObject() ? preferences.getAsJsonObject().get("blocked") : null;
    if (b != null && b.isJsonArray())
      for (JsonElement x : b.getAsJsonArray()) {
        Double v = Js.number(x);
        if (v != null && Js.isSafeInteger(v) && v > 0) bootBlocked.add((int) (double) v); // Number.isSafeInteger(id) && id > 0
      }
    blocked = null;
    pressedBy.clear();
    lastPolledAccount = "";
    lines.clear();
    if (seedLines != null) lines.addAll(seedLines);
    rebuild();
    // The seed is the bridge's file; only what the Java engine appends afterwards is "its" output.
  }

  public Store store() {
    return store;
  }

  /**
   * preferences.json's blocked list as the bridge holds it at this step, in the stored order, or null when no Block was pressed
   * in this transcript (the boot preferences then stand as they are).
   */
  public List<Integer> blockedNow() {
    return blocked == null ? null : new ArrayList<>(blocked);
  }

  /**
   * What the PLUGIN's engine reads at this step for a query naming {@code account}: the boot file's blocked list (the plugin's own
   * preferences.json, read as the bridge reads its file) plus that character's own presses, ascending -- or null when no Block was
   * pressed and the boot list stands as it is. Equal to {@link #blockedNow()} whenever only one account pressed and polled.
   */
  public List<Integer> blockedFor(String account) {
    if (blocked == null) return null;
    java.util.TreeSet<Integer> all = new java.util.TreeSet<>(bootBlocked);
    LinkedHashSet<Integer> mine = pressedBy.get(account == null ? "" : account);
    if (mine != null) all.addAll(mine);
    return new ArrayList<>(all);
  }

  /** The profit line's reset time as the bridge holds it at this step (null: never reset), for the engine's shared journal. */
  public Long profitSince() {
    return profitSince;
  }

  private void expect(int i, String what, JsonElement exp, JsonElement act) {
    compared++;
    for (String d : ParityJson.diff(exp, act)) diffs.add(name + " step " + i + " " + what + ": " + d);
  }

  private static JsonObject error(int status, String message) {
    JsonObject o = new JsonObject();
    o.addProperty("status", status);
    JsonObject b = new JsonObject();
    b.addProperty("error", message);
    o.add("body", b);
    return o;
  }

  private static JsonObject ok(JsonElement body) {
    JsonObject o = new JsonObject();
    o.addProperty("status", 200);
    o.add("body", body);
    return o;
  }

  public void run(JsonArray steps) {
    for (JsonElement se : steps) {
      JsonObject step = se.getAsJsonObject();
      int i = step.get("i").getAsInt();
      long at = step.get("at").getAsLong();
      JsonObject response = step.getAsJsonObject("response");
      if (step.has("restart")) {
        rebuild();
        inventoryChecks.clear();
        lastInventory.clear();
        continue;
      }
      // Phase 4: a five-minute bucket handed straight to the bridge's crash watch (tools/golden-advice-scenarios.mjs). Not a
      // journal event; AdviceTranscriptTest feeds the Java watch from it.
      if (step.has("feed5m")) continue;
      // Stage 4: hours stored into the bridge's archive mid-run (store1h). Not a journal event; the engine and shadow replays
      // add them to their archive from that step on.
      if (step.has("store1h")) continue;
      if (step.has("call")) {
        JsonArray args = step.getAsJsonObject("call").getAsJsonArray("args");
        String account = args.get(0).isJsonNull() ? null : args.get(0).getAsString();
        BuyLimitUsage u = store.buyLimitUsage(account, args.get(1).getAsInt(), at);
        JsonObject b = new JsonObject();
        b.addProperty("used", u.used);
        ParityJson.put(b, "windowEndsAt", u.windowEndsAt);
        expect(i, "buyLimitUsage", response.get("body"), b);
        continue;
      }
      JsonObject req = step.getAsJsonObject("request");
      String method = req.get("method").getAsString(), path = req.get("path").getAsString();
      String auth = req.has("auth") ? req.get("auth").getAsString() : "";
      int repeat = step.has("repeat") ? step.get("repeat").getAsInt() : 1;
      JsonObject body = req.has("body") && req.get("body").isJsonObject() ? req.getAsJsonObject("body") : new JsonObject();
      if ("POST".equals(method) && "ui".equals(auth) && "/api/flips/import".equals(path)) {
        // The scanner's flip import (server.mjs: send(200, store.importFlips(body))): ranking history, never profit.
        JsonObject got;
        try {
          Store.ImportResult r = store.importFlips(body);
          JsonObject b = new JsonObject();
          ParityJson.put(b, "removed", r.removed);
          ParityJson.put(b, "accepted", r.accepted);
          ParityJson.put(b, "duplicates", r.duplicates);
          b.addProperty("total", r.total);
          got = ok(b);
        } catch (JournalException e) {
          got = error(400, e.getMessage());
        }
        expect(i, path, response, got);
        continue;
      }
      if ("POST".equals(method) && !"plugin".equals(auth)) {
        // server.mjs refuses a wrong plugin key before the store sees anything.
        expect(i, path + " (wrong key)", response, error(401, "Plugin key required"));
        continue;
      }
      switch (method + " " + path) {
        case "POST /api/events": {
          JsonObject got = null;
          for (int k = 0; k < repeat; k++) {
            try {
              JsonObject b = new JsonObject();
              b.addProperty("duplicate", store.ingest(req.get("body"), at));
              got = ok(b);
            } catch (JournalException e) {
              got = error(400, e.getMessage());
            }
          }
          expect(i, "POST /api/events", response, got);
          break;
        }
        case "GET /api/state": {
          JsonObject st = ParityJson.state(store.state(at));
          addInventoryCheck(st, at);
          expect(i, "GET /api/state", response.get("body"), st);
          JsonElement kept = response.get("body").getAsJsonObject().get("personalUseKept");
          if (kept != null && kept.isJsonObject()) {
            compared++;
            if (!ParityJson.keyOrder(kept.getAsJsonObject()).equals(ParityJson.keyOrder(st.getAsJsonObject("personalUseKept"))))
              diffs.add(name + " step " + i + ": personalUseKept key ORDER " + st.get("personalUseKept") + " vs JS " + kept);
          }
          break;
        }
        case "POST /api/suggestion/not-held": {
          JsonObject got;
          try {
            JsonObject a = new JsonObject();
            if (body.has("buyId")) a.add("buyId", body.get("buyId"));
            a.addProperty("reason", "used".equals(Js.string(body.get("reason"))) ? "used" : "sold-untracked");
            store.closePosition(a, at);
            JsonObject b = new JsonObject();
            b.addProperty("ok", true);
            got = ok(b);
          } catch (JournalException e) {
            got = error(400, e.getMessage());
          }
          expect(i, path, response, got);
          break;
        }
        case "POST /api/suggestion/personal-use": {
          JsonObject got;
          try {
            JsonElement personal = body.has("personal") ? body.get("personal") : new com.google.gson.JsonPrimitive(true);
            if (ParityJson.isNull(body.get("buyId"))) {
              JsonObject a = new JsonObject();
              if (body.has("itemId")) a.add("itemId", body.get("itemId"));
              a.add("personal", personal);
              Long kept = keptFor(Js.string(body.get("account")), body.get("itemId"), at);
              if (kept != null) a.addProperty("kept", kept);
              Store.PersonalUseItemReply r = store.markPersonalUseItem(a);
              JsonObject b = new JsonObject();
              b.addProperty("ok", true);
              b.addProperty("itemId", r.itemId);
              b.addProperty("personal", r.personal);
              ParityJson.put(b, "kept", r.kept);
              got = ok(b);
            } else {
              JsonObject a = new JsonObject();
              a.add("buyId", body.get("buyId"));
              a.add("personal", personal);
              store.markPersonalUse(a);
              JsonObject b = new JsonObject();
              b.addProperty("ok", true);
              got = ok(b);
            }
          } catch (JournalException e) {
            got = error(400, e.getMessage());
          }
          expect(i, path, response, got);
          break;
        }
        case "POST /api/suggestion/block": {
          // server.mjs setBlocked: Number(itemId) must be a safe integer above 0 (else 400 'Invalid itemId'); the set is the stored
          // list deduplicated in order, the id added (or removed for blocked:false); the FILE gets it sorted, the reply the set's order.
          JsonObject got;
          JsonElement idEl = body.get("itemId");
          Double id = idEl == null || idEl.isJsonNull() ? null : Js.number(idEl);
          if (id == null || !Js.isSafeInteger(id) || id <= 0) {
            got = error(400, "Invalid itemId");
          } else {
            LinkedHashSet<Integer> set = new LinkedHashSet<>(blocked == null ? bootBlocked : blocked);
            JsonElement flag = body.get("blocked");
            if (flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean() && !flag.getAsBoolean()) set.remove((int) (double) id);
            else set.add((int) (double) id);
            List<Integer> sorted = new ArrayList<>(set);
            java.util.Collections.sort(sorted);
            blocked = sorted;
            LinkedHashSet<Integer> mine = pressedBy.computeIfAbsent(lastPolledAccount, k -> new LinkedHashSet<>());
            if (flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean() && !flag.getAsBoolean()) mine.remove((int) (double) id);
            else mine.add((int) (double) id);
            JsonObject b = new JsonObject();
            b.addProperty("ok", true);
            JsonArray reply = new JsonArray();
            for (Integer x : set) reply.add(x);
            b.add("blocked", reply);
            got = ok(b);
          }
          expect(i, path, response, got);
          break;
        }
        case "POST /api/profit/reset": {
          profitSince = at;
          JsonObject b = new JsonObject();
          b.addProperty("ok", true);
          b.add("profit", ParityJson.profit(Profit.sinceAllAccounts(store.state(at), profitSince)));
          expect(i, path, response, ok(b));
          break;
        }
        case "GET /api/suggestion": {
          if (step.has("warmupQuery")) observeQuery(step.get("warmupQuery").getAsString(), at);
          observeQuery(req.get("query").getAsString(), at);
          JsonElement rb = response.get("body");
          if (response.get("status").getAsInt() == 200 && rb != null && rb.isJsonObject() && rb.getAsJsonObject().has("profit"))
            expect(i, "GET /api/suggestion .profit", rb.getAsJsonObject().get("profit"),
              ParityJson.profit(Profit.sinceAllAccounts(store.state(at), profitSince)));
          if (observer != null) observer.onSuggestion(i, at, req.get("query").getAsString(), response, store);
          break;
        }
        default:
          break; // /api/version and anything else: not the journal's
      }
    }
  }

  // ------------------------------------------------------------------------------------- server glue

  private static Map<String, String> query(String q) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String part : q.split("&")) {
      if (part.isEmpty()) continue;
      int eq = part.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
      out.putIfAbsent(k, v); // URLSearchParams.get returns the first
    }
    return out;
  }

  /** JS parseInt(s, 10): leading whitespace, a sign, then digits; anything else is NaN (null here). */
  private static Long parseIntJs(String s) {
    String t = s.trim();
    int i = 0;
    if (i < t.length() && (t.charAt(i) == '+' || t.charAt(i) == '-')) i++;
    int start = i;
    while (i < t.length() && Character.isDigit(t.charAt(i)) && t.charAt(i) < 128) i++;
    if (i == start) return null;
    return Long.parseLong(t.substring(0, i));
  }

  // server.mjs: every /api/suggestion with heldPositions= (even empty) records an inventory check for that
  // account; with includeInventory=1 and a non-empty inventory= it records the bag (lastInventory).
  private void observeQuery(String q, long at) {
    Map<String, String> p = query(q);
    String account = p.get("account");
    if (account != null && account.isEmpty()) account = null;
    String key = account == null ? "" : account;
    lastPolledAccount = key;
    if (p.containsKey("heldPositions")) {
      Set<Integer> confirmed = new LinkedHashSet<>();
      for (String s : p.get("heldPositions").split(",", -1)) {
        Long v = parseIntJs(s);
        if (v != null) confirmed.add((int) (long) v);
      }
      inventoryChecks.put(key, new Object[]{account, at, new ArrayList<>(confirmed)});
    }
    if ("1".equals(p.get("includeInventory"))) {
      Map<Integer, Long> inv = new LinkedHashMap<>();
      for (String pair : p.getOrDefault("inventory", "").split(",", -1)) {
        String[] parts = pair.split(":", -1);
        Long id = parseIntJs(parts[0]);
        Long qty = parts.length > 1 ? parseIntJs(parts[1]) : null;
        if (id != null && id > 0 && qty != null && qty > 0) inv.put((int) (long) id, qty);
      }
      if (!inv.isEmpty()) lastInventory.put(key, new Object[]{inv, at});
    }
  }

  // server.mjs /personal-use: the kept count is what the last poll of THAT account (or of "") held, if
  // that poll is under a minute old. The plugin sends no account, so its marks look up "" -- and, since 8 Oct 2026, when
  // that reading is absent or stale, the ONE account whose bag was read in the last minute (two or more: no count).
  @SuppressWarnings("unchecked")
  private Long keptFor(String account, JsonElement itemId, long at) {
    boolean named = account != null && !account.isEmpty();
    Object[] seen = lastInventory.get(named ? account : "");
    if (seen == null) seen = lastInventory.get("");
    Map<Integer, Long> fresh = seen != null && at - (long) seen[1] < 60000 ? (Map<Integer, Long>) seen[0] : null;
    if (fresh == null && !named) {
      List<Object[]> recent = new ArrayList<>();
      for (Object[] r : lastInventory.values()) if (at - (long) r[1] < 60000) recent.add(r);
      if (recent.size() == 1) fresh = (Map<Integer, Long>) recent.get(0)[0];
    }
    if (fresh == null) return null;
    Double id = Js.number(itemId);
    if (id == null) return null;
    return fresh.get((int) (double) id);
  }

  // server.mjs /api/state: the newest inventory check under ten minutes old, counted against that
  // account's positions -- or, when the poll named no account, against every position (the scanner's
  // machine-wide line; the bridge does this, the glue reproduces it explicitly rather than by predicate).
  @SuppressWarnings("unchecked")
  private void addInventoryCheck(JsonObject st, long now) {
    Object[] newest = null;
    for (Object[] c : inventoryChecks.values()) if (newest == null || (long) c[1] > (long) newest[1]) newest = c;
    if (newest == null || now - (long) newest[1] >= 600000) return;
    String account = (String) newest[0];
    Set<Integer> items = new LinkedHashSet<>();
    for (FifoMatcher.OpenPosition p : store.state(now).autoOpenPositions)
      if (account == null || account.equals(p.account)) items.add(p.itemId);
    int seen = 0;
    for (Integer id : (List<Integer>) newest[2]) if (items.contains(id)) seen++;
    JsonObject ic = new JsonObject();
    ic.addProperty("at", (long) newest[1]);
    ic.addProperty("positions", items.size());
    ic.addProperty("seenInInventory", seen);
    st.getAsJsonObject("dataHealth").add("inventoryCheck", ic);
  }
}
