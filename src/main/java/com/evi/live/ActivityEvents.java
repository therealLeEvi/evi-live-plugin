package com.evi.live;

import com.evi.live.activity.ActivityLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * Turns what the plugin already sees into activity log records (schema 1; see {@link ActivityLog}). It keeps the little state
 * a record needs -- which suggestion is on screen, the last hotkey fill -- and nothing else. Every method is safe from any
 * thread, returns at once and never throws: activity log must not be able to cost a suggestion, a journal line or a frame.
 *
 * <p>FACTS ONLY. A field that was not observed is left out, never guessed; how a thing was learned is itself a field
 * ({@code observed}, {@code link}). Everything that identifies or sizes the player sits under {@code p}.
 */
final class ActivityEvents {
  /** A hotkey fill this recent, for the same item and side, is what links an offer to a suggestion as "hotkey". */
  static final long ASSIST_LINK_MS = 2 * 60 * 1000;

  private ActivityLog sink;
  private String account, sid;
  private int counter;
  /** The suggestion on screen: its id, its change key and what it said (for linking an offer to it). */
  private String shownId, shownKey;
  private Suggestion shown;
  private List<Suggestion> shownExtras = java.util.Collections.emptyList();
  private String emptyState;
  private String settingsShown;
  private int assistItem = -1;
  private String assistAction, assistId;
  private long assistAt;
  private final java.util.function.LongSupplier clock;

  ActivityEvents(java.util.function.LongSupplier clock) {
    this.clock = clock;
  }

  synchronized void start(ActivityLog t) {
    sink = t;
  }

  synchronized void stop() {
    sink = null;
  }

  private void put(String type, JsonObject fields) {
    ActivityLog t = sink;
    if (t != null) t.record(account, sid, type, fields);
  }

  /** A login, world hop or plugin start that made the plugin ready: a new session id from here on. */
  synchronized void session(String account, String sid, String reason, String pluginVersion, Boolean members, JsonObject settings) {
    try {
      this.account = account;
      this.sid = sid;
      counter = 0;
      shownId = shownKey = null;
      shown = null;
      emptyState = null;
      JsonObject o = new JsonObject();
      o.addProperty("reason", reason);
      if (pluginVersion != null) o.addProperty("plugin", pluginVersion);
      if (members != null) o.addProperty("members", members);
      if (settings != null) {
        o.add("settings", settings);
        settingsShown = settings.toString();
      }
      JsonObject p = new JsonObject();
      p.addProperty("account", account);
      o.add("p", p);
      put("session", o);
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  /** Every few minutes while logged in: bounds the watching window if the client dies without a logout. */
  synchronized void alive() {
    if (account != null) put("alive", null);
  }

  /** Logout, hop, connection loss or shutdown. The suggestion on screen, if any, ends with it. */
  synchronized void sessionEnd(String reason) {
    try {
      if (account == null) return;
      endShown("session-end");
      JsonObject o = new JsonObject();
      o.addProperty("reason", reason);
      put("session-end", o);
      account = null;
      sid = null;
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  /** The suggestion-relevant settings as they are now; written only when they differ from the last ones written. */
  synchronized void settings(JsonObject now) {
    try {
      if (account == null || now == null) return;
      String s = now.toString();
      if (s.equals(settingsShown)) return;
      settingsShown = s;
      JsonObject o = new JsonObject();
      o.add("settings", now);
      put("settings", o);
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  static String key(Suggestion s) {
    return s.itemId + "|" + s.action + "|" + s.quantity + "|" + s.buyPrice + "|" + s.sellPrice;
  }

  /**
   * What a poll left on screen. Writes a record only when the pick CHANGED (never once per poll): the old one ends
   * ("replaced"), the new one gets an id. {@code suggestion} is the pick as JSON (the caller's own serialisation, with the
   * long reasoning text left out); {@code mkt} the market as EVI read it for that item, or null.
   */
  synchronized void shown(Suggestion s, List<Suggestion> extras, JsonObject suggestion, JsonObject mkt, long cash, int tick) {
    try {
      if (account == null || s == null) return;
      String k = key(s);
      shownExtras = extras == null ? java.util.Collections.emptyList() : extras;
      if (k.equals(shownKey)) return;
      endShown("replaced");
      shownExtras = extras == null ? java.util.Collections.emptyList() : extras; // endShown cleared them with the old pick
      emptyState = null;
      shownId = sid + "-" + (++counter);
      shownKey = k;
      shown = s;
      JsonObject o = suggestion == null ? new JsonObject() : suggestion;
      o.addProperty("id", shownId);
      if (tick >= 0) o.addProperty("tick", tick);
      if (!shownExtras.isEmpty()) {
        JsonArray a = new JsonArray();
        for (Suggestion e : shownExtras) {
          JsonObject x = new JsonObject();
          x.addProperty("item", e.itemId);
          x.addProperty("qty", e.quantity);
          x.addProperty("buy", e.buyPrice);
          x.addProperty("sell", e.sellPrice);
          a.add(x);
        }
        o.add("extras", a);
      }
      if (mkt != null) o.add("mkt", mkt);
      if (cash >= 0) {
        JsonObject p = new JsonObject();
        p.addProperty("cash", cash);
        o.add("p", p);
      }
      put("suggestion", o);
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  /** The pick left the screen without a new one taking its place. {@code why}: skipped, blocked, mine, gone, cleared ... */
  synchronized void gone(String why) {
    try {
      endShown(why);
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  private void endShown(String why) {
    if (shownId == null) return;
    JsonObject o = new JsonObject();
    o.addProperty("id", shownId);
    o.addProperty("why", why);
    put("suggestion-end", o);
    shownId = shownKey = null;
    shown = null;
    shownExtras = java.util.Collections.emptyList();
  }

  /** The no-suggestion state, by a short code; written only when it changes. */
  synchronized void empty(String state) {
    try {
      if (account == null || state == null || state.equals(emptyState)) return;
      endShown("cleared");
      emptyState = state;
      JsonObject o = new JsonObject();
      o.addProperty("state", state);
      put("empty", o);
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  /** A sidebar press: skip, block, took-it, mine, gone, promote-extra, show-skipped. */
  synchronized void action(String kind, Suggestion on) {
    try {
      if (account == null) return;
      JsonObject o = new JsonObject();
      o.addProperty("kind", kind);
      if (on != null) {
        o.addProperty("item", on.itemId);
        if (shown != null && shownId != null && key(on).equals(shownKey)) o.addProperty("id", shownId);
      }
      put("action", o);
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  /** A sidebar press that names an item rather than the pick on screen (an "Also worth buying" line). */
  synchronized void action(String kind, int itemId) {
    try {
      if (account == null) return;
      JsonObject o = new JsonObject();
      o.addProperty("kind", kind);
      o.addProperty("item", itemId);
      put("action", o);
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  /** The hotkey filled a field from {@code s}: the strongest sign that the offer that follows came from it. */
  synchronized void assist(Suggestion s, String field, long value) {
    try {
      if (account == null || s == null) return;
      JsonObject o = new JsonObject();
      o.addProperty("field", field);
      o.addProperty("value", value);
      o.addProperty("item", s.itemId);
      String id = shownId != null && shown != null && shown.itemId == s.itemId ? shownId : null;
      if (id != null) o.addProperty("id", id);
      put("assist", o);
      assistItem = s.itemId;
      assistAction = s.action;
      assistId = id;
      assistAt = clock.getAsLong();
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  /** How an offer that has just been placed relates to what EVI was suggesting: hotkey, exact, item or none. */
  static String link(boolean buy, int itemId, long price, long total, Suggestion s, boolean assisted) {
    if (s == null || s.itemId != itemId || s.action == null || buy != "buy".equals(s.action)) return "none";
    if (assisted) return "hotkey";
    long want = buy ? s.buyPrice : s.sellPrice;
    return want == price && s.quantity == total ? "exact" : "item";
  }

  /**
   * One slot's change, old state to new. Writes placed / fill / cancelled / done / collected as they apply; nothing when
   * nothing happened. {@code live} is false for the first look at the slots after a login, where an offer's real moment is
   * not known -- that is said ({@code observed: "at-login"}), never guessed.
   */
  synchronized void offer(EviLivePlugin.Offer old, EviLivePlugin.Offer now, boolean live, JsonObject mkt, int tick) {
    try {
      if (account == null || now == null) return;
      boolean was = old != null && !"EMPTY".equals(old.state), is = !"EMPTY".equals(now.state);
      boolean same = was && is && old.offerId != null && old.offerId.equals(now.offerId);
      if (was && !same) write("collected", old, live, null, tick, 0, 0, null);
      if (!is) return;
      boolean buy = now.state.equals("BUYING") || now.state.equals("BOUGHT") || now.state.equals("CANCELLED_BUY");
      if (!same) {
        JsonObject extra = new JsonObject();
        if (live) {
          Suggestion best = match(buy, now.itemId);
          boolean assisted = assistItem == now.itemId && assistAction != null && buy == "buy".equals(assistAction)
            && clock.getAsLong() - assistAt <= ASSIST_LINK_MS;
          String l = link(buy, now.itemId, now.price, now.total, best, assisted);
          extra.addProperty("link", l);
          if (!"none".equals(l)) {
            String id = assisted && assistId != null ? assistId : (shown != null && best == shown ? shownId : null);
            if (id != null) extra.addProperty("sug", id);
            extra.addProperty("dPrice", now.price - (buy ? best.buyPrice : best.sellPrice));
            extra.addProperty("dQty", (long) now.total - best.quantity);
          }
          if (mkt != null) extra.add("mkt", mkt);
        }
        write("placed", now, live, extra, tick, now.filled, now.spent, null);
      } else if (now.filled > old.filled || now.spent > old.spent) {
        write("fill", now, live, null, tick, now.filled - old.filled, now.spent - old.spent, null);
      }
      boolean endedNow = terminal(now) && !(same && terminal(old));
      if (endedNow) write(now.state.startsWith("CANCELLED") ? "cancelled" : "done", now, live, null, tick, 0, 0, null);
    } catch (RuntimeException ignored) {
      // never the caller's problem
    }
  }

  private Suggestion match(boolean buy, int itemId) {
    if (shown != null && shown.itemId == itemId && shown.action != null && buy == "buy".equals(shown.action)) return shown;
    for (Suggestion e : shownExtras) if (e != null && e.itemId == itemId && buy) return e; // extras are buys
    return null;
  }

  private static boolean terminal(EviLivePlugin.Offer o) {
    return o.state.equals("BOUGHT") || o.state.equals("SOLD") || o.state.startsWith("CANCELLED");
  }

  private void write(String ev, EviLivePlugin.Offer o, boolean live, JsonObject extra, int tick, long dFilled, long dSpent, JsonElement unused) {
    JsonObject x = extra == null ? new JsonObject() : extra;
    x.addProperty("ev", ev);
    x.addProperty("oid", o.offerId);
    x.addProperty("oidSource", "session"); // the plugin's own id for this sighting; the journal joins sightings across sessions
    x.addProperty("slot", o.slot);
    x.addProperty("item", o.itemId);
    x.addProperty("side", o.state.equals("BUYING") || o.state.equals("BOUGHT") || o.state.equals("CANCELLED_BUY") ? "buy" : "sell");
    x.addProperty("state", o.state);
    x.addProperty("price", o.price);
    x.addProperty("total", o.total);
    x.addProperty("filled", o.filled);
    x.addProperty("spent", o.spent);
    if ("fill".equals(ev)) {
      x.addProperty("dFilled", dFilled);
      x.addProperty("dSpent", dSpent);
    }
    x.addProperty("observed", live ? "live" : "at-login");
    if (tick >= 0) x.addProperty("tick", tick);
    put("offer", x);
  }
}
