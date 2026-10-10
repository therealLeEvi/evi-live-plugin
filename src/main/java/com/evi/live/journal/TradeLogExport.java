package com.evi.live.journal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The trade log a player can save and send by hand (9 Oct 2026, the maintainer's decision: VOLUNTARY sharing, option 2 of the
 * SELF-CONTAINED-PLAN bullet of that day). PURE: it turns what the journal already holds into CSV bytes and touches no file, no
 * thread and no network; {@link PluginJournal#exportTradeLog} writes them, on the journal thread.
 *
 * <p>WHAT IT CARRIES, and nothing else: every Grand Exchange offer EVI saw, from EVERY account's journal on this machine (item,
 * side, price, quantity, filled, its last state, cancelled, ended-unseen, when EVI first saw it and whether that was its start,
 * the tick reading, last seen, completed) -- with EVI's suggested price for that item and side when the offer can be linked to a
 * suggestion EVI showed, and how it was linked ({@link Link}; 9 Oct 2026, approved by the maintainer) -- plus every finished flip the profit line counts ({@code state.flips} -- reviewed,
 * removed ones left out -- and {@code state.autoFlips}, the FIFO matches; the same set as {@code tradeCount}) with its cost,
 * proceeds after tax and profit or loss. Imported flips are NOT included: they came from another tracker, EVI never saw them,
 * and the profit line leaves them out for the same reason. A purchase the player only TOLD EVI about ({@code recorded}) is not
 * an offer EVI saw and is left out too; a flip that used one keeps an empty {@code buy_ref}.
 *
 * <p>WHAT IT NEVER CARRIES: the account id, a character name, the session, the GE slot, cash, bank, inventory or any path, and
 * nothing from a suggestion record but its price and the link type (no suggestion id, account, cash, minimum profit or setting). Offer
 * ids are replaced by {@code ref}, a number that exists only in this file (so a flip can name the offers it matched); rows are
 * sorted by time, not by account, so the order does not group one account's trades either.
 *
 * <p>The 3 Oct 2026 CSV rules (the scanner's trade-log export): a UTF-8 BOM, {@code sep=,} as the first line (Excel on a
 * locale whose list separator is ';' would otherwise put every line in column A), COMMAS always -- never semicolons, which repair
 * one locale and break every other -- and RFC 4180 quoting for a field holding a comma, a quote or a line break. Times are
 * ISO-8601 UTC to the millisecond, exactly as recorded: the fill model needs them, and they must line up with the Wiki's series.
 */
public final class TradeLogExport {
  private TradeLogExport() {}

  /** The folder, inside the plugin's data folder, the file is saved in. */
  public static final String SHARE_DIR = "share";
  /** One table: an {@code offer} row leaves the flip columns empty and a {@code flip} row the offer columns. */
  public static final String HEADER = "record,ref,item_id,item_name,side,state,price,evi_price,evi_link,quantity,filled,cancelled,ended_unseen,known_start,"
    + "ticks_to_fill,first_seen_utc,last_seen_utc,completed_utc,matched,buy_ref,sell_refs,cost,proceeds,profit,first_buy_utc,last_sell_utc";
  static final int COLUMNS = HEADER.split(",", -1).length;
  static final String EOL = "\r\n";
  private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC);

  /** The file's name for a (local) day: a second save on the same day replaces that day's file. */
  public static String fileName(LocalDate day) {
    return "evi-trade-log-" + day + ".csv";
  }

  /** The file's bytes and what it counted. */
  public static final class Csv {
    public final byte[] bytes;
    public final int offers;
    public final int flips;

    Csv(byte[] bytes, int offers, int flips) {
      this.bytes = bytes;
      this.offers = offers;
      this.flips = flips;
    }
  }

  /**
   * EVI's own suggestion behind one offer, by offer id: the suggested price for that side and how the offer was linked to it --
   * {@code observed}, {@code matched} or {@code inferred}, the suggestion-outcome join's three attributions, never pooled
   * (inprocess.SuggestionOutcomes, the port of bridge/suggestionOutcomes.mjs, decides them; nothing here infers a link). An offer
   * with no link leaves both columns empty. Nothing else from the suggestion record reaches the file.
   */
  public static final class Link {
    /** {@code observed}, {@code matched} or {@code inferred}. */
    final String type;
    /** The suggested price as recorded, or null when the record holds none. */
    final String price;

    public Link(String type, Long price) {
      if (!"observed".equals(type) && !"matched".equals(type) && !"inferred".equals(type))
        throw new IllegalArgumentException("not a link type: " + type);
      this.type = type;
      this.price = price == null ? null : String.valueOf(price);
    }
  }

  /** One finished flip as the file shows it: either kind of match, reduced to the fields both carry. */
  static final class Flip {
    final String matched;
    final int itemId;
    final String item;
    final long quantity;
    final long cost;
    final long proceeds;
    final long profit;
    final Long firstBuy;
    final Long lastSell;
    final String buyId;
    final List<String> sellIds;

    Flip(String matched, int itemId, String item, long quantity, long cost, long proceeds, long profit, Long firstBuy, Long lastSell,
         String buyId, List<String> sellIds) {
      this.matched = matched;
      this.itemId = itemId;
      this.item = item;
      this.quantity = quantity;
      this.cost = cost;
      this.proceeds = proceeds;
      this.profit = profit;
      this.firstBuy = firstBuy;
      this.lastSell = lastSell;
      this.buyId = buyId;
      this.sellIds = sellIds == null ? Collections.emptyList() : sellIds;
    }

    static Flip of(ManualFlip f) {
      return new Flip("reviewed", f.itemId, f.item, f.quantity, f.capital, f.netProceeds, f.profit, f.firstBuy, f.lastSell, f.buyId,
        f.sellIdsOrSingle());
    }

    static Flip of(FifoMatcher.AutoFlip f) {
      return new Flip("auto", f.itemId, f.item, f.quantity, f.capital, f.netProceeds, f.profit, f.firstBuy, f.lastSell, f.buyId, f.sellIds);
    }
  }

  /** The file for one journal moment: {@code offers} is the store's every offer ({@code Store.offers()}), {@code state} its state. */
  public static Csv of(StoreState state, Collection<Offer> offers, Map<String, Link> links) {
    Set<String> unseen = new HashSet<>();
    for (StoreState.EndedUnseenOffer u : state.endedUnseen) unseen.add(u.offerId);
    List<Flip> flips = new ArrayList<>();
    for (ManualFlip f : state.flips) flips.add(Flip.of(f));
    for (FifoMatcher.AutoFlip f : state.autoFlips) flips.add(Flip.of(f));
    return build(offers, unseen, flips, links);
  }

  static Csv build(Collection<Offer> all, Set<String> endedUnseen, List<Flip> allFlips, Map<String, Link> links) {
    List<Offer> offers = new ArrayList<>();
    for (Offer o : all) if (seen(o)) offers.add(o);
    offers.sort(Comparator.comparing((Offer o) -> o.firstSeen, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(o -> o.updated, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparingInt(o -> o.itemId));
    List<Flip> flips = new ArrayList<>(allFlips);
    flips.sort(Comparator.comparing((Flip f) -> f.lastSell, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(f -> f.firstBuy, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparingInt(f -> f.itemId));

    StringBuilder sb = new StringBuilder(256 + 160 * (offers.size() + flips.size()));
    sb.append("sep=,").append(EOL).append(HEADER).append(EOL);
    Map<String, Integer> refs = new HashMap<>();
    int ref = 0;
    for (Offer o : offers) {
      refs.put(o.offerId, ++ref);
      String[] row = new String[COLUMNS];
      row[0] = "offer";
      row[1] = String.valueOf(ref);
      row[2] = String.valueOf(o.itemId);
      row[3] = o.name;
      row[4] = o.side();
      row[5] = o.state;
      row[6] = String.valueOf(o.price);
      Link link = links == null ? null : links.get(o.offerId);
      if (link != null) {
        row[7] = link.price;
        row[8] = link.type;
      }
      row[9] = String.valueOf(o.total);
      row[10] = String.valueOf(o.filled);
      row[11] = yesNo(o.state.startsWith("CANCELLED"));
      row[12] = yesNo(endedUnseen.contains(o.offerId));
      row[13] = yesNo(o.knownStart);
      row[14] = o.ticksToFill == null ? null : String.valueOf(o.ticksToFill);
      row[15] = time(o.firstSeen);
      row[16] = time(o.updated);
      row[17] = time(o.completedAt);
      line(sb, row);
    }
    for (Flip f : flips) {
      String[] row = new String[COLUMNS];
      row[0] = "flip";
      row[1] = String.valueOf(++ref);
      row[2] = String.valueOf(f.itemId);
      row[3] = f.item;
      row[9] = String.valueOf(f.quantity);
      row[18] = f.matched;
      Integer buy = f.buyId == null ? null : refs.get(f.buyId);
      row[19] = buy == null ? null : String.valueOf(buy);
      StringBuilder sells = new StringBuilder();
      for (String id : f.sellIds) {
        Integer s = id == null ? null : refs.get(id);
        if (s == null) continue;
        if (sells.length() > 0) sells.append(' ');
        sells.append(s);
      }
      row[20] = sells.toString();
      row[21] = String.valueOf(f.cost);
      row[22] = String.valueOf(f.proceeds);
      row[23] = String.valueOf(f.profit);
      row[24] = time(f.firstBuy);
      row[25] = time(f.lastSell);
      line(sb, row);
    }
    byte[] text = sb.toString().getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[BOM.length + text.length];
    System.arraycopy(BOM, 0, out, 0, BOM.length);
    System.arraycopy(text, 0, out, BOM.length, text.length);
    return new Csv(out, offers.size(), flips.size());
  }

  /** An offer EVI watched: not a purchase the player only told it about, and not an empty slot. */
  /** Yes/no rather than true/false: the file is read by players in a spreadsheet, not parsed by EVI. */
  static String yesNo(boolean b) {
    return b ? "yes" : "no";
  }

  static boolean seen(Offer o) {
    return o != null && !Boolean.TRUE.equals(o.recorded) && o.itemId > 0 && o.state != null && !"EMPTY".equals(o.state);
  }

  /** ISO-8601 UTC to the millisecond, or empty when the journal holds no time. */
  static String time(Long epochMs) {
    return epochMs == null ? null : UTC.format(Instant.ofEpochMilli(epochMs));
  }

  private static void line(StringBuilder sb, String[] row) {
    for (int i = 0; i < row.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(field(row[i]));
    }
    sb.append(EOL);
  }

  /** RFC 4180: a field holding a comma, a double quote or a line break is quoted, its quotes doubled. Null is empty. */
  static String field(String value) {
    if (value == null) return "";
    boolean quote = false;
    for (int i = 0; i < value.length() && !quote; i++) {
      char c = value.charAt(i);
      quote = c == ',' || c == '"' || c == '\n' || c == '\r';
    }
    return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
  }
}
