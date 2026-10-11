package com.evi.live.journal;

import com.evi.live.TestFiles;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.client.util.Filepath;

/**
 * The voluntary trade-log export (9 Oct 2026): {@link TradeLogExport}'s CSV, and {@link PluginJournal#exportTradeLog} writing
 * it from every account's journal. Every check asserts a VALUE -- a byte, a line, a field, a count. SYNTHETIC data only: the
 * offers below and PluginJournalTest's synthetic clients. The point the file must never miss: no account id, no character name,
 * no session and no offer id appear anywhere in it.
 */
public final class TradeLogExportTest {
  static int checks;
  static final String ACCOUNT = "f00d".repeat(16);
  static final String CHARACTER = "Synthetic Trader";
  static final long T = 1789992000000L; // 2026-09-21T12:00:00Z

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    headerAndShape();
    rowsAndQuoting();
    nothingIdentifying();
    journalWritesEveryAccount();
    System.out.println("PASS: trade-log export (" + checks + " checks): BOM, sep=, line, the header, RFC 4180 quoting, an offer row, a"
      + " cancelled offer, an ended-unseen offer, a recorded purchase left out, a reviewed flip at a loss with its refs, EVI's suggested price and link type (observed / matched / inferred from the plugin's own join, empty when unlinked, a linker failure costing only the links), ISO UTC times"
      + " to the millisecond, rows sorted by time not account, no account id / character name / session / offer id anywhere; and the"
      + " journal writing every account's offers and flips to share/, replacing the same day's file, off no thread but its own");
  }

  static Offer offer(String id, String state, int itemId, String name, long price, long total, long filled, Long firstSeen, Long updated,
                     Long completedAt, Integer ticks, boolean knownStart, Boolean recorded) {
    return new Offer(3, state, id, itemId, name, price, total, filled, price * filled, knownStart, ticks, ACCOUNT, "session-x", firstSeen,
      updated, completedAt, recorded);
  }

  static List<Offer> sample() {
    return Arrays.asList(
      // listed out of time order on purpose: the file sorts by first seen
      offer("o-sell", "CANCELLED_SELL", 1515, "Yew logs", 320, 500, 120, T + 60_000, T + 90_000, T + 90_000, null, true, null),
      offer("o-buy", "BOUGHT", 4151, "Abyssal whip", 1_500_000, 2, 2, T, T + 30_123, T + 30_123, 40, true, null),
      offer("o-odd", "BUYING", 12345, "Odd, \"quoted\" item", 7, 100, 0, T + 120_000, T + 120_000, null, null, false, null),
      offer("o-told", "BOUGHT", 4151, "Abyssal whip", 1_400_000, 1, 1, T - 5_000, T - 5_000, T - 5_000, null, true, Boolean.TRUE),
      offer("o-sold", "SOLD", 4151, "Abyssal whip", 1_450_000, 2, 2, T + 40_000, T + 50_000, T + 50_000, 12, true, null));
  }

  /** A suggestion-log line as SuggestionRecords writes it, plus fields the file must never carry (an account and a minimum profit). */
  static com.google.gson.JsonObject suggestion(String id, long ts, int itemId, String action, long buy, long sell, long quantity) {
    com.google.gson.JsonObject e = new com.google.gson.JsonObject();
    e.addProperty("id", id);
    e.addProperty("ts", ts);
    e.addProperty("account", "acct-secret");
    e.addProperty("itemId", itemId);
    e.addProperty("action", action);
    e.addProperty("quantity", quantity);
    e.addProperty("buyPrice", buy);
    e.addProperty("sellPrice", sell);
    e.addProperty("minProfit", 424242);
    return e;
  }

  static List<TradeLogExport.Flip> sampleFlips() {
    ManualFlip loss = new ManualFlip("m1", "o-buy", null, Arrays.asList("o-sold", "o-gone"), ACCOUNT, 4151, "Abyssal whip", 2, 3_000_000,
      2_842_000, -158_000, T, T + 50_000, 0.01, T + 60_000, "manual", "exact");
    return new ArrayList<>(Arrays.asList(TradeLogExport.Flip.of(loss)));
  }

  static TradeLogExport.Csv sampleCsv() {
    // EVI's suggested price beside two offers: the buy linked as OBSERVED at a price that differs from the offer's own, the sale
    // MATCHED at its own price; the cancelled sell and the odd buy have no link
    java.util.Map<String, TradeLogExport.Link> links = new java.util.HashMap<>();
    links.put("o-buy", new TradeLogExport.Link("observed", 1_490_000L));
    links.put("o-sold", new TradeLogExport.Link("matched", 1_450_000L));
    links.put("o-told", new TradeLogExport.Link("inferred", 1L)); // an offer left out of the file carries nothing in
    return TradeLogExport.build(sample(), new HashSet<>(Arrays.asList("o-odd")), sampleFlips(), links);
  }

  /** Parses the file the way a spreadsheet reads RFC 4180: quoted fields, doubled quotes, CRLF records. */
  static List<List<String>> parse(String text) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder f = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; }
        else if (c == '"') quoted = false;
        else f.append(c);
      } else if (c == '"') quoted = true;
      else if (c == ',') { row.add(f.toString()); f.setLength(0); }
      else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
        row.add(f.toString()); f.setLength(0); rows.add(row); row = new ArrayList<>(); i++;
      } else f.append(c);
    }
    check(f.length() == 0 && row.isEmpty(), "the file ends with a complete record");
    return rows;
  }

  static String text(byte[] bytes) {
    check(bytes.length > 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF, "UTF-8 BOM first");
    return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
  }

  static void headerAndShape() {
    String t = text(sampleCsv().bytes);
    check(t.startsWith("sep=,\r\n" + TradeLogExport.HEADER + "\r\n"), "sep=, then the header, CRLF: " + t.substring(0, Math.min(80, t.length())));
    check(TradeLogExport.HEADER.equals("record,ref,item_id,item_name,side,state,price,evi_price,evi_link,quantity,filled,cancelled,ended_unseen,known_start,"
      + "ticks_to_fill,first_seen_utc,last_seen_utc,completed_utc,matched,buy_ref,sell_refs,cost,proceeds,profit,first_buy_utc,last_sell_utc"),
      "the header, word for word");
    check(!t.contains(";"), "never a semicolon");
    List<List<String>> rows = parse(t);
    check(rows.get(0).equals(List.of("sep=", "")), "line 1 is sep=, (Excel reads it as the separator, not data): " + rows.get(0));
    for (List<String> r : rows.subList(1, rows.size()))
      check(r.size() == TradeLogExport.COLUMNS, "every record has " + TradeLogExport.COLUMNS + " fields: " + r);
    check(TradeLogExport.fileName(LocalDate.of(2026, 10, 9)).equals("evi-trade-log-2026-10-09.csv"), "file name per local day");
    check(TradeLogExport.field(null).isEmpty() && TradeLogExport.field("a,b").equals("\"a,b\"") && TradeLogExport.field("say \"hi\"")
      .equals("\"say \"\"hi\"\"\"") && TradeLogExport.field("two\nlines").equals("\"two\nlines\"") && TradeLogExport.field("plain").equals("plain"),
      "RFC 4180 quoting");
    check("2026-09-21T12:00:30.123Z".equals(TradeLogExport.time(T + 30_123)) && "2026-09-21T12:00:00.000Z".equals(TradeLogExport.time(T)),
      "ISO-8601 UTC, exact to the millisecond: " + TradeLogExport.time(T + 30_123));
  }

  static void rowsAndQuoting() {
    TradeLogExport.Csv csv = sampleCsv();
    check(csv.offers == 4 && csv.flips == 1, "four offers (the told purchase left out) and one flip: " + csv.offers + "/" + csv.flips);
    List<List<String>> rows = parse(text(csv.bytes));
    List<List<String>> data = rows.subList(2, rows.size());
    check(data.size() == 5, "five records: " + data.size());
    // sorted by first seen: the buy (T), the sale (T+40s), the cancelled sell (T+60s), the odd buy (T+120s); then the flip
    check(data.get(0).equals(List.of("offer", "1", "4151", "Abyssal whip", "buy", "BOUGHT", "1500000", "1490000", "observed", "2", "2", "no", "no", "yes", "40",
      "2026-09-21T12:00:00.000Z", "2026-09-21T12:00:30.123Z", "2026-09-21T12:00:30.123Z", "", "", "", "", "", "", "", "")),
      "a filled buy linked to EVI's suggestion at a DIFFERENT price, field for field: " + data.get(0));
    check(data.get(1).get(1).equals("2") && data.get(1).get(5).equals("SOLD") && data.get(1).get(4).equals("sell")
      && data.get(1).get(6).equals("1450000") && data.get(1).get(7).equals("1450000") && data.get(1).get(8).equals("matched"),
      "the sale second, MATCHED at its own price: " + data.get(1));
    check(data.get(2).equals(List.of("offer", "3", "1515", "Yew logs", "sell", "CANCELLED_SELL", "320", "", "", "500", "120", "yes", "no", "yes", "",
      "2026-09-21T12:01:00.000Z", "2026-09-21T12:01:30.000Z", "2026-09-21T12:01:30.000Z", "", "", "", "", "", "", "", "")),
      "a cancelled, part-filled sell with no link (both suggestion cells empty): cancelled=yes, no tick reading: " + data.get(2));
    check(data.get(3).get(3).equals("Odd, \"quoted\" item") && data.get(3).get(12).equals("yes") && data.get(3).get(13).equals("no")
      && data.get(3).get(17).isEmpty() && data.get(3).get(7).isEmpty() && data.get(3).get(8).isEmpty(), "a name with a comma and quotes survives quoting; ended-unseen; not seen from its start; not completed: " + data.get(3));
    check(data.get(4).equals(List.of("flip", "5", "4151", "Abyssal whip", "", "", "", "", "", "2", "", "", "", "", "", "", "", "", "reviewed", "1", "2",
      "3000000", "2842000", "-158000", "2026-09-21T12:00:00.000Z", "2026-09-21T12:00:50.000Z")),
      "a reviewed flip at a LOSS: negative profit, its buy and sale named by ref, an offer not in the file dropped from sell_refs: " + data.get(4));
  }

  static void nothingIdentifying() {
    String t = text(sampleCsv().bytes);
    for (String s : List.of(ACCOUNT, ACCOUNT.substring(0, 8), "session-x", "o-buy", "o-sell", "o-odd", "o-told", "o-sold", "o-gone", "m1"))
      check(!t.contains(s), "the file must not contain \"" + s + "\"");
  }

  /**
   * End to end: two clients (two accounts) write their own journals into one folder; the export from ONE journal holds every
   * account's offers and the FIFO flips, is exactly TradeLogExport over a store that saw every packet, lands in share/, and a
   * second export the same day replaces the file. No account id, character name, session or offer id is in it.
   */
  static void journalWritesEveryAccount() throws Exception {
    Filepath root = PluginJournalTest.tempRoot();
    AtomicLong c1 = new AtomicLong(), c2 = new AtomicLong();
    PluginJournal j1 = PluginJournalTest.journal(root, c1, () -> false);
    PluginJournal j2 = PluginJournalTest.journal(root, c2, () -> false);
    j1.characterName(PluginJournalTest.A, CHARACTER);
    j2.characterName(PluginJournalTest.B, CHARACTER + " Two");
    PluginJournalTest.Client a = PluginJournalTest.streamA(), b = PluginJournalTest.streamB();
    Store ref = new Store(r -> { });
    PluginJournalTest.feed(a, 0, a.packets.size(), j1, c1, ref);
    PluginJournalTest.feed(b, 0, b.packets.size(), j2, c2, ref);
    check(j1.awaitIdle(30000) && j2.awaitIdle(30000), "drain");
    long t = PluginJournalTest.T0 + 900_000;
    String name = TradeLogExport.fileName(LocalDate.of(2026, 10, 9));
    // EVI's suggestions, through the plugin's own join (SuggestionOutcomes): a whip buy suggested at a price the player did not
    // use (INFERRED), a nature-rune buy at the offer's exact price and quantity (MATCHED), and a chestplate sale the player said
    // they took (OBSERVED). Each record also holds an id, an account and a minimum profit that must never reach the file.
    List<com.google.gson.JsonObject> suggestions = new ArrayList<>();
    suggestions.add(suggestion("sugg-secret-1", PluginJournalTest.T0 + 30_000, 4151, "buy", 1_490_000, 1_600_000, 10));
    suggestions.add(suggestion("sugg-secret-3", PluginJournalTest.T0 + 180_000, 11832, "sell", 20_000_000, 21_000_000, 2));
    suggestions.add(suggestion("sugg-secret-2", PluginJournalTest.T0 + 290_000, 561, "buy", 100, 110, 1000));
    java.util.function.BiFunction<StoreState, java.util.Collection<Offer>, java.util.Map<String, TradeLogExport.Link>> linker =
      (state, offers) -> com.evi.live.inprocess.SuggestionOutcomes.offerLinks(com.evi.live.inprocess.SuggestionOutcomes.join(suggestions, offers,
        com.evi.live.inprocess.SuggestionOutcomes.Flip.of(state.flips, state.autoFlips), com.evi.live.inprocess.SuggestionOutcomes.TAKEN_WINDOW_MS,
        "sugg-secret-3"::equals));
    PluginJournal.SavedLog saved = j1.exportTradeLog(name, t, linker).get(30, TimeUnit.SECONDS);
    Filepath file = saved.file;
    check(file.equals(root.joinSegment(TradeLogExport.SHARE_DIR).joinSegment(name)) && file.toString().startsWith(root.toString()),
      "saved as share/" + name + " under the data folder, an absolute path: " + file);
    byte[] bytes = TestFiles.read(file);
    StoreState st = ref.state(t);
    TradeLogExport.Csv expected = TradeLogExport.of(st, ref.offers(), linker.apply(st, ref.offers()));
    check(Arrays.equals(bytes, expected.bytes), "the file is exactly the export of a store that saw both accounts' packets");
    check(saved.offers == ref.offers().size() && saved.offers == 6 && saved.flips == st.autoFlips.size() + st.flips.size() && saved.flips == 2,
      "every account's offers (6) and both FIFO flips: " + saved.offers + " offers, " + saved.flips + " flips");
    String t1 = text(bytes);
    for (String item : List.of("Abyssal whip", "Nature rune", "Bandos chestplate", "Dragon bones")) check(t1.contains(item), "has " + item);
    for (String s : List.of(PluginJournalTest.A, PluginJournalTest.B, "a1a1a1a1", "b2b2b2b2", CHARACTER, "sa1", "sb1", "-b1", "-s1",
      "sugg-secret", "acct-secret", "424242", "minProfit"))
      check(!t1.contains(s), "the saved file must not contain \"" + s + "\"");
    List<List<String>> rows = parse(t1);
    java.util.Map<String, String> linked = new java.util.TreeMap<>();
    for (List<String> r : rows.subList(2, rows.size()))
      if (r.get(0).equals("offer")) linked.put(r.get(3) + " " + r.get(4), r.get(6) + "|" + r.get(7) + "|" + r.get(8));
    check(linked.equals(new java.util.TreeMap<>(java.util.Map.of(
      "Abyssal whip buy", "1500000|1490000|inferred",
      "Abyssal whip sell", "1600000||",
      "Nature rune buy", "100|100|matched",
      "Bandos chestplate buy", "20000000||",
      "Bandos chestplate sell", "21000000|21000000|observed",
      "Dragon bones buy", "3000||"))),
      "each offer's price | EVI's suggested price | link type, from the plugin's own join (unlinked offers empty): " + linked);
    int flips = 0;
    for (List<String> r : rows.subList(2, rows.size())) {
      if (!r.get(0).equals("flip")) continue;
      flips++;
      check(r.get(18).equals("auto") && !r.get(19).isEmpty() && !r.get(20).isEmpty(), "a FIFO flip names its buy and sale: " + r);
      check(Long.parseLong(r.get(23)) == Long.parseLong(r.get(22)) - Long.parseLong(r.get(21)), "profit = proceeds - cost: " + r);
    }
    check(flips == 2, "two flip rows");
    // the same day again: replaced, not duplicated, and no temporary file left behind
    PluginJournalTest.Client more = new PluginJournalTest.Client("sa3", PluginJournalTest.A);
    more.set(PluginJournalTest.offer(5, "sa3-b5", "BUYING", 1515, "Yew logs", 300, 50, 0, 0, true, -1)).send(PluginJournalTest.T0 + 800_000, true);
    PluginJournalTest.feed(more, 0, more.packets.size(), j1, c1, ref);
    check(j1.awaitIdle(30000), "drain");
    // a linker that fails (an unreadable suggestion log) costs the links, never the file, and never a guessed link
    PluginJournal.SavedLog again = j1.exportTradeLog(name, t, (state, offers) -> { throw new IllegalStateException("synthetic"); }).get(30, TimeUnit.SECONDS);
    check(again.offers == 7 && Arrays.equals(TestFiles.read(file), TradeLogExport.of(ref.state(t), ref.offers(), null).bytes),
      "a second export the same day replaces the file with the newer journal: " + again.offers);
    List<String> names = TestFiles.names(file.getParent());
    check(names.equals(List.of(name)), "only the one file in share/: " + names);
    PluginJournalTest.stop(j1);
    PluginJournalTest.stop(j2);
    try {
      j1.exportTradeLog(name, t, null).get(30, TimeUnit.SECONDS);
      check(false, "a stopped journal must refuse");
    } catch (java.util.concurrent.ExecutionException e) {
      check(e.getCause() instanceof IllegalStateException, "a stopped journal refuses with a reason: " + e.getCause());
    }
    root.deleteRecursively();
  }
}
