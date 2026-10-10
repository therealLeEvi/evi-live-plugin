package com.evi.live.inprocess;

import com.evi.live.engine.Pick;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.runelite.client.util.Filepath;

/**
 * "I took this one" (SELF-CONTAINED decision 12): the suggestion's id and the record of which ones the player
 * took -- bridge/suggestionLog.mjs and bridge/acceptances.mjs, kept by the plugin in its own data folder.
 *
 * <p>THE ID is the bridge's scheme exactly: {@code now.toString(36) + "-" + itemId + "-" + (seq++ % 1296).toString(36)} padded to
 * two characters, with {@code seq} counting from 0 for each plugin start as the bridge's counts from 0 for each bridge start. A
 * REPEAT of the same suggestion for the same account (item, action, source, quantity, both prices and the demoted picks, as
 * suggestionLog.mjs keys it) inside {@link #REPEAT_MS} keeps its first id, so a pick that has been on screen a while still names
 * a logged entry. The repeat memory is in memory, as the bridge's is: after a restart the same pick is a new entry with a new id.
 * Only the first suggestion of a poll gets an id (the bridge logs no further position).
 *
 * <p>THE FILES, in {@value #DIR}/ of the plugin's data folder, through {@link Filepath} only: {@value #LOG} (one line per id
 * issued: the fields the outcome join needs -- {@link SuggestionOutcomes}) and {@value #ACCEPTED} (one line per press:
 * {@code {id, account, itemId, accepted, at}}). Both append-only; the newest record for an id wins, so taking an acceptance
 * back is just another line. Each moves to {@code .1} past its cap (50 MB / 8 MB, the bridge's), and, as in the bridge, only
 * the current acceptance file is read back at start. The acceptance file is read lazily, at the first use, on whichever
 * background thread asks first -- never at construction (that is the client thread). Local only; nothing is ever sent.
 *
 * <p>Deliberate differences from the bridge, none of which changes an answer: the log line carries only the suggestion's own
 * fields (the bridge also logs the poll's settings and its checks, which no join reads); and the acceptance line carries the
 * account that pressed the button (the plugin never sends one to the bridge, which writes null).
 *
 * <p>Thread-safe: the engine's thread issues ids, a background thread records presses.
 */
public final class SuggestionRecords {
  public static final String DIR = "suggestions";
  public static final String LOG = "suggestion-log.jsonl";
  public static final String ACCEPTED = "suggestion-accepted.jsonl";
  /** suggestionLog.mjs REPEAT_MS. */
  public static final long REPEAT_MS = 30L * 60 * 1000;
  static final long LOG_MAX_BYTES = 50L * 1024 * 1024;
  static final long ACCEPTED_MAX_BYTES = 8L * 1024 * 1024;

  private static final class Last {
    final String key;
    final long at;
    final String id;

    Last(String key, long at, String id) {
      this.key = key;
      this.at = at;
      this.id = id;
    }
  }

  private final Filepath dir;
  private final Function<String, JsonElement> parser;
  private final Consumer<String> log;
  private final Map<String, Last> last = new HashMap<>();
  private int seq;
  /** id -> the newest acceptance record's {accepted, at}; null until first read. */
  private Map<String, JsonObject> byId;

  /** @param pluginDir the plugin's data folder; nothing is read or written here until the first use */
  public SuggestionRecords(Filepath pluginDir, Function<String, JsonElement> parser, Consumer<String> log) {
    this.dir = pluginDir.joinSegment(DIR);
    this.parser = parser;
    this.log = log;
  }

  /** suggestionLog.mjs makeId: the clock in base 36, the item, and a two-character base-36 counter. */
  static String makeId(long now, int itemId, int seq) {
    String c = Integer.toString(seq % 1296, 36);
    return Long.toString(now, 36) + "-" + itemId + "-" + (c.length() < 2 ? "0" + c : c);
  }

  /** suggestionLog.mjs's repeat key: {@code [itemId, action, source, quantity, buyPrice, sellPrice, demotedIds].join('|')}. */
  static String key(Pick s, List<Pick> demoted) {
    StringBuilder d = new StringBuilder();
    if (demoted != null)
      for (int i = 0; i < demoted.size(); i++) {
        if (i > 0) d.append(',');
        d.append(demoted.get(i).itemId);
      }
    return s.itemId + "|" + nz(s.action) + "|" + nz(s.source) + "|" + s.quantity + "|" + s.buyPrice + "|" + s.sellPrice + "|" + d;
  }

  private static String nz(String s) {
    return s == null ? "" : s; // Array.join writes null and undefined as nothing
  }

  /**
   * The id for the suggestion a poll is answered with, logging it when it is new (suggestionLog.record). {@code account} is the
   * query's (null when it names none). Null only when a NEW entry could not be written -- the plugin then shows no button, as
   * with the bridge; as in the bridge, the repeat memory was already moved on, so the next poll of the same pick returns this id.
   */
  public synchronized String record(String account, Pick s, List<Pick> demoted, long now) {
    if (s == null) return null;
    String k = key(s, demoted);
    String who = account == null ? "" : account;
    Last prev = last.get(who);
    if (prev != null && prev.key.equals(k) && now - prev.at < REPEAT_MS) return prev.id;
    String id = makeId(now, s.itemId, seq++);
    last.put(who, new Last(k, now, id));
    JsonObject e = new JsonObject();
    e.addProperty("id", id);
    e.addProperty("ts", now);
    if (account == null) e.add("account", JsonNull.INSTANCE);
    else e.addProperty("account", account);
    e.addProperty("itemId", s.itemId);
    if (s.name != null) e.addProperty("name", s.name);
    if (s.action != null) e.addProperty("action", s.action);
    if (s.source != null) e.addProperty("source", s.source);
    e.addProperty("quantity", s.quantity);
    e.addProperty("buyPrice", s.buyPrice);
    e.addProperty("sellPrice", s.sellPrice);
    if (s.breakEvenPrice == null) e.add("breakEvenPrice", JsonNull.INSTANCE);
    else e.addProperty("breakEvenPrice", s.breakEvenPrice);
    if (s.lossIfSoldNow == null) e.add("lossIfSoldNow", JsonNull.INSTANCE);
    else e.addProperty("lossIfSoldNow", s.lossIfSoldNow);
    e.addProperty("persisted", Boolean.TRUE.equals(s.persisted));
    try {
      append(LOG, LOG_MAX_BYTES, e);
    } catch (IOException | RuntimeException ex) {
      log.accept("EVI: a suggestion could not be logged, so it has no \"I took this one\" button: " + ex);
      return null;
    }
    return id;
  }

  /**
   * One press of "I took this one" (acceptances.accept): an id is required; the record is appended, then remembered. Returns
   * false (and remembers nothing) when there is no id or the line could not be written.
   */
  public synchronized boolean accept(String id, String account, Integer itemId, boolean accepted, long now) {
    if (id == null || id.isEmpty()) return false;
    loadAcceptances();
    JsonObject r = new JsonObject();
    r.addProperty("id", id);
    if (account == null) r.add("account", JsonNull.INSTANCE);
    else r.addProperty("account", account);
    if (itemId == null) r.add("itemId", JsonNull.INSTANCE);
    else r.addProperty("itemId", itemId);
    r.addProperty("accepted", accepted);
    r.addProperty("at", now);
    try {
      append(ACCEPTED, ACCEPTED_MAX_BYTES, r);
    } catch (IOException | RuntimeException ex) {
      log.accept("EVI: \"I took this one\" could not be saved: " + ex);
      return false;
    }
    byId.put(id, r);
    return true;
  }

  /** acceptances.wasAccepted: the newest record for the id says accepted. */
  public synchronized boolean wasAccepted(String id) {
    if (id == null || id.isEmpty()) return false;
    loadAcceptances();
    JsonObject r = byId.get(id);
    return r != null && r.has("accepted") && r.get("accepted").isJsonPrimitive() && r.get("accepted").getAsJsonPrimitive().isBoolean()
      && r.get("accepted").getAsBoolean();
  }

  /** Every line of the suggestion log still in its current file, oldest first (unreadable lines skipped, as the bridge's recent()). */
  public synchronized List<JsonObject> logged() {
    List<JsonObject> out = new ArrayList<>();
    for (String line : lines(dir.joinSegment(LOG))) {
      try {
        JsonElement e = parser.apply(line);
        if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject());
      } catch (RuntimeException ignored) {
        // a partial or damaged line is discarded rather than guessed at
      }
    }
    return out;
  }

  private void loadAcceptances() {
    if (byId != null) return;
    byId = new HashMap<>();
    for (String line : lines(dir.joinSegment(ACCEPTED))) {
      try {
        JsonElement e = parser.apply(line);
        if (e == null || !e.isJsonObject()) continue;
        JsonObject r = e.getAsJsonObject();
        JsonElement id = r.get("id");
        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString() && !id.getAsString().isEmpty()) byId.put(id.getAsString(), r);
      } catch (RuntimeException ignored) {
        // acceptances.mjs skips a line that does not parse
      }
    }
  }

  private List<String> lines(Filepath f) {
    List<String> out = new ArrayList<>();
    if (!f.isFile()) return out;
    try (InputStream in = f.openInputStream()) {
      for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) if (!l.isEmpty()) out.add(l);
    } catch (IOException e) {
      log.accept("EVI: " + f.getFileName() + " could not be read: " + e);
    }
    return out;
  }

  private void append(String name, long maxBytes, JsonObject line) throws IOException {
    dir.createDirectories();
    Filepath f = dir.joinSegment(name);
    if (f.isFile() && f.size() > maxBytes) f.moveTo(dir.joinSegment(name + ".1"), StandardCopyOption.REPLACE_EXISTING);
    try (OutputStream out = f.openOutputStream(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      out.write((line.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    }
  }
}
