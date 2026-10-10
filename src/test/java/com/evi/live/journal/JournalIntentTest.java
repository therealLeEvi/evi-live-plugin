package com.evi.live.journal;

import static com.evi.live.journal.ParityJson.check;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The journal engine's traps, written as INTENT rather than recorded from JS, so a failure here reads as
 * "a rule broke" and a parity failure reads as "Java and JS disagree". Each was proven by sabotage.
 * Every assertion is a literal value, never a re-derivation of the code under test.
 */
public final class JournalIntentTest {
  private static final long T0 = 1789992000000L;

  public static void main(String[] args) {
    // Run under a locale that groups with dots and writes decimal commas: every figure the engine
    // writes must still be en-US (the bridge's 6 Oct F2 fix is the same rule).
    Locale.setDefault(new Locale("nl", "NL"));
    taxRules();
    halfUpRounding();
    probesAreInvisible();
    accountsNeverCross();
    restartMidOffer();
    absentIsNotZero();
    aboveTwoToThe31();
    localeAndLineFormat();
    keptCountsIterateAscending();
    pastTwoToThe63();
    closePositionPastTwoToThe63();
    bridgeWrittenHugePurchase();
    importRefusesUnsafeNet();
    profitSinceBoundary();
    System.out.println("PASS: journal intent -- tax (under-50 floor, the 5,000,000 cap from 250m, exempt chisel and bond, the 30 May 2025 era), "
      + "Math.round half-up on .5 ties (+0.5 -> 1, -1.5 -> -1, capital 2.5 -> 3), the 1-unit instant probe invisible while one at its asked price is a trade, "
      + "FIFO/limits/cost basis never crossing accounts (null account answers nothing), a client restart mid-offer linked to its original, "
      + "absent kept distinct from zero (no window, no tick reading, no completion), totals past 2^31, a recorded cost capped at 2^53 - 1, "
      + "JS-equal GP arithmetic past 2^53 and a loud refusal (never a wrapped sign) past 2^63, closing a position on such a journal refused with the journal's own message, an import whose net is not a safe integer refused, "
      + "the profit line counting a flip whose last sale is exactly at the reset, "
      + "en-US grouping plus JSON.stringify line text under a Dutch default locale, and kept counts listed by ascending item id as a JS object lists them");
  }

  // ------------------------------------------------------------------------------------------ helpers

  private static final class Client {
    final String account, session;
    long seq;
    final JsonObject[] slots = new JsonObject[8];
    int gen;

    Client(String account, String session) {
      this.account = account;
      this.session = session;
    }

    String place(int slot, int itemId, String name, long price, long total, boolean buy) {
      JsonObject o = new JsonObject();
      o.addProperty("itemId", itemId);
      o.addProperty("total", total);
      o.addProperty("filled", 0);
      o.addProperty("price", price);
      o.addProperty("spent", 0);
      String id = session + "-o" + (++gen);
      o.addProperty("offerId", id);
      o.addProperty("state", buy ? "BUYING" : "SELLING");
      o.addProperty("name", name);
      o.addProperty("knownStart", true);
      o.addProperty("ticksToFill", -1);
      slots[slot] = o;
      return id;
    }

    void fill(int slot, long filled, long spent, int ticks) {
      JsonObject o = slots[slot];
      o.addProperty("filled", filled);
      o.addProperty("spent", spent);
      if (ticks >= 0) o.addProperty("ticksToFill", ticks);
      if (filled == o.get("total").getAsLong()) o.addProperty("state", "BUYING".equals(o.get("state").getAsString()) ? "BOUGHT" : "SOLD");
    }

    void cancel(int slot) {
      JsonObject o = slots[slot];
      String st = o.get("state").getAsString();
      o.addProperty("state", "BUYING".equals(st) || "BOUGHT".equals(st) ? "CANCELLED_BUY" : "CANCELLED_SELL");
      o.addProperty("ticksToFill", -1);
    }

    Client restart(String newSession) {
      Client c = new Client(account, newSession);
      for (int i = 0; i < 8; i++) {
        if (slots[i] == null) continue;
        JsonObject o = slots[i].deepCopy();
        o.addProperty("offerId", newSession + "-o" + (++c.gen));
        o.addProperty("knownStart", false);
        o.addProperty("ticksToFill", -1);
        c.slots[i] = o;
      }
      return c;
    }

    JsonObject packet(long ts) {
      JsonObject p = new JsonObject();
      p.addProperty("version", 1);
      p.addProperty("session", session);
      p.addProperty("account", account);
      p.addProperty("seq", ++seq);
      p.addProperty("ts", ts);
      p.addProperty("loggedIn", true);
      JsonArray offers = new JsonArray();
      for (int i = 0; i < 8; i++) {
        JsonObject o;
        if (slots[i] != null) o = slots[i].deepCopy();
        else {
          o = new JsonObject();
          o.addProperty("itemId", 0);
          o.addProperty("total", 0);
          o.addProperty("filled", 0);
          o.addProperty("price", 0);
          o.addProperty("spent", 0);
          o.addProperty("offerId", session + "-e" + i);
          o.addProperty("state", "EMPTY");
          o.addProperty("name", "");
          o.addProperty("knownStart", false);
          o.addProperty("ticksToFill", -1);
        }
        o.addProperty("slot", i);
        offers.add(o);
      }
      p.add("offers", offers);
      return p;
    }
  }

  private static final List<String> journal = new ArrayList<>();

  private static Store fresh() {
    journal.clear();
    return new Store(r -> journal.add(JournalCodec.encodeLine(r)));
  }

  private static long t;

  private static void send(Store s, Client c) {
    t += 1000;
    s.ingest(c.packet(t - 300), t);
  }

  private static Offer probeBuy(long price, long spent, int ticks) {
    return new Offer(0, "BOUGHT", "p", 4151, "Abyssal whip", price, 1, 1, spent, true, ticks, "acct-a", "s", T0, T0 + 1000, T0 + 1000, null);
  }

  // -------------------------------------------------------------------------------------------- rules

  private static void taxRules() {
    check(Tax.estimateUnitTax(561, 49) == 0, "under 50 gp pays no tax (floor of 2%)");
    check(Tax.estimateUnitTax(561, 50) == 1, "50 gp pays 1");
    check(Tax.estimateUnitTax(561, 99.99) == 1, "the 2% is floored, never rounded");
    check(Tax.estimateUnitTax(22325, 249_999_999) == 4_999_999, "just under the cap: 4,999,999");
    check(Tax.estimateUnitTax(22325, 250_000_000) == 5_000_000, "the cap binds at 250m");
    check(Tax.estimateUnitTax(20997, 2_147_483_648.0) == 5_000_000, "above 2^31 the cap still binds");
    check(Tax.estimateUnitTax(1755, 1_000_000) == 0, "a chisel is tax-exempt");
    check(Tax.estimateUnitTax(13190, 10_000_000) == 0, "an Old school bond is tax-exempt");
    check(Tax.estimateUnitTax(561, Double.NaN) == 0 && Tax.estimateUnitTax(561, -5) == 0, "no price, no tax (never a guess)");
    Offer before = new Offer(0, "SOLD", "x", 561, "Nature rune", 214, 10, 10, 2140, true, null, "acct-a", "s", Tax.TAX_ERA_START_MS - 1, null, null, null);
    check(Tax.saleProceeds(before) == null, "a sale first seen before 30 May 2025 is never reinterpreted");
    Offer mixed = new Offer(0, "SOLD", "x", 561, "Nature rune", 214, 1000, 1000, 214_500, true, null, "acct-a", "s", T0, null, null, null);
    Tax.Proceeds p = Tax.saleProceeds(mixed);
    check(p.net == 210_500 && p.tax == 4_000 && !p.exact, "1,000 sold for 214,500: tax 4 a unit, net 210,500, NOT exact (mixed fill prices)");
    check(Tax.breakEvenSellPrice(561, 205) == 209L, "break-even of 205 is 209: 209 - floor(209/50) = 205, and 208 - 4 = 204 falls short");
    check(Tax.breakEvenSellPrice(1755, 20.5) == 21L, "exempt: break-even is just the cost rounded UP");
  }

  private static void halfUpRounding() {
    // Buy 2 for 5 gp (2.5 each), sell 1 for 3 gp, then say the rest is gone: the sold part is a flip with
    // capital 2.5 and profit 0.5. JS Math.round: 3 and 1. HALF_EVEN / rint would say 2 and 0.
    Offer buy = new Offer(0, "BOUGHT", "b1", 561, "Nature rune", 3, 2, 2, 5, true, 5, "acct-a", "s", T0, T0 + 2000, T0 + 2000, null);
    Offer sell = new Offer(1, "SOLD", "s1", 561, "Nature rune", 3, 1, 1, 3, true, null, "acct-a", "s", T0 + 3000, T0 + 4000, T0 + 4000, null);
    java.util.Map<String, FifoMatcher.Closed> closed = new java.util.HashMap<>();
    closed.put("b1", new FifoMatcher.Closed("used", T0 + 5000));
    FifoMatcher.Result r = FifoMatcher.computeAutoFlips(Arrays.asList(buy, sell), closed);
    check(r.flips.size() == 1, "one partial flip from the closed lot");
    FifoMatcher.AutoFlip f = r.flips.get(0);
    check(f.capital == 3 && f.netProceeds == 3 && f.profit == 1 && Boolean.TRUE.equals(f.partial), "capital 2.5 -> 3, profit 0.5 -> 1 (half-up), got " + f.capital + "/" + f.profit);
    // Fire runes: 2 for 7 (3.5 each), 1 sold for 2: profit -1.5 rounds to -1 (toward +infinity), not -2.
    Offer fb = new Offer(0, "BOUGHT", "b2", 554, "Fire rune", 4, 2, 2, 7, true, 6, "acct-a", "s", T0, T0 + 2000, T0 + 2000, null);
    Offer fs = new Offer(1, "SOLD", "s2", 554, "Fire rune", 2, 1, 1, 2, true, null, "acct-a", "s", T0 + 3000, T0 + 4000, T0 + 4000, null);
    closed.clear();
    closed.put("b2", new FifoMatcher.Closed("sold-untracked", T0 + 5000));
    FifoMatcher.AutoFlip g = FifoMatcher.computeAutoFlips(Arrays.asList(fb, fs), closed).flips.get(0);
    check(g.profit == -1 && g.capital == 4, "profit -1.5 -> -1 and capital 3.5 -> 4 (JS half-up), got " + g.profit + "/" + g.capital);
  }

  private static void probesAreInvisible() {
    check(FifoMatcher.isMarginCheck(probeBuy(1_600_000, 1_500_000, 1)), "1 unit, tick 1, filled BELOW its asking: a probe");
    check(!FifoMatcher.isMarginCheck(probeBuy(1_500_000, 1_500_000, 1)), "filled AT its asked price: a real trade (the reported chestplate lesson)");
    check(!FifoMatcher.isMarginCheck(probeBuy(1_600_000, 1_500_000, 3)), "tick 3 is too slow for a probe");
    check(!FifoMatcher.isMarginCheck(probeBuy(1_600_000, 1_500_000, -1)) && !FifoMatcher.isMarginCheck(
      new Offer(0, "BOUGHT", "p", 4151, "w", 1_600_000, 1, 1, 1_500_000, true, null, "acct-a", "s", T0, T0, T0, null)),
      "no tick reading is never a probe (only positive evidence excludes)");
    FifoMatcher.Result r = FifoMatcher.computeAutoFlips(Arrays.asList(probeBuy(1_600_000, 1_500_000, 1)), new java.util.HashMap<>());
    check(r.openPositions.isEmpty() && r.flips.isEmpty(), "a probe never becomes a position");
    FifoMatcher.Result r2 = FifoMatcher.computeAutoFlips(Arrays.asList(probeBuy(1_500_000, 1_500_000, 1)), new java.util.HashMap<>());
    check(r2.openPositions.size() == 1 && r2.openPositions.get(0).unitCost == 1_500_000, "the at-price buy IS held, at 1,500,000");
  }

  private static void accountsNeverCross() {
    Store s = fresh();
    t = T0;
    Client a = new Client("acct-a", "s-a1"), b = new Client("acct-b", "s-b1");
    send(s, a);
    send(s, b);
    a.place(0, 561, "Nature rune", 205, 100, true);
    b.place(0, 561, "Nature rune", 205, 100, true);
    send(s, a);
    send(s, b);
    a.fill(0, 100, 20_400, 3);
    b.fill(0, 100, 20_500, 3);
    send(s, a);
    send(s, b);
    a.slots[0] = null;
    a.place(1, 561, "Nature rune", 214, 150, false);
    send(s, a);
    a.fill(1, 150, 32_100, -1);
    send(s, a);
    StoreState st = s.state(t);
    check(st.autoFlips.size() == 1 && "acct-a".equals(st.autoFlips.get(0).account) && st.autoFlips.get(0).quantity == 100,
      "acct-a's 150 sold match only acct-a's 100 bought");
    check(st.autoUnmatchedSells.size() == 1 && st.autoUnmatchedSells.get(0).unmatchedQty == 50, "the other 50 stay unmatched even though acct-b holds 100");
    check(st.autoOpenPositions.size() == 1 && "acct-b".equals(st.autoOpenPositions.get(0).account), "acct-b keeps its own position");
    check(s.buyLimitUsage("acct-a", 561, t).used == 100 && s.buyLimitUsage("acct-b", 561, t).used == 100, "limits per account");
    check(s.buyLimitUsage(null, 561, t).used == 0 && s.buyLimitUsage(null, 561, t).windowEndsAt == null, "no account counts NOTHING, never everything");
    CostBasis cb = CostBasis.held(st.autoOpenPositions, st.active, 561, "acct-b");
    check(cb != null && cb.unitCost == 205 && cb.quantity == 100, "acct-b's cost basis is its own 205");
    check(CostBasis.held(st.autoOpenPositions, st.active, 561, "acct-a") == null, "acct-a holds none: no cost basis, not acct-b's");
    check(CostBasis.held(st.autoOpenPositions, st.active, 561, null) == null, "no account: no cost basis (the PORT WARNING)");
    JsonObject evil = a.packet(t + 500);
    evil.addProperty("account", "acct-b");
    try {
      s.ingest(evil, t + 1000);
      throw new AssertionError("a session changing account must be refused");
    } catch (JournalException e) {
      check("Session account changed".equals(e.getMessage()), "message: " + e.getMessage());
    }
  }

  private static void restartMidOffer() {
    Store s = fresh();
    t = T0;
    Client c = new Client("acct-a", "s-a1");
    send(s, c);
    String original = c.place(2, 565, "Blood rune", 400, 1000, true);
    send(s, c);
    c.fill(2, 300, 120_000, 7);
    send(s, c);
    Client c2 = c.restart("s-a2");
    c2.fill(2, 600, 240_000, -1);
    send(s, c2);
    // Partial fill then cancel: the cancel packet carries no tick reading either.
    c2.cancel(2);
    send(s, c2);
    StoreState st = s.state(t);
    check(st.linkedLoginOffers == 1, "the login-time offerId is linked to the original record");
    check(st.autoOpenPositions.size() == 1 && original.equals(st.autoOpenPositions.get(0).buyId) && st.autoOpenPositions.get(0).remaining == 600,
      "ONE position of the 600 filled, under the original offerId, not a separate baseline record");
    Offer kept = null;
    for (Offer o : s.offers()) if (o.offerId.equals(original)) kept = o;
    check(kept != null && kept.ticksToFill == 7 && "CANCELLED_BUY".equals(kept.state) && kept.firstSeen == T0 + 1700,
      "ticksToFill 7 and the first-seen time survive the restart and the cancel's -1");
    // ... and the link survives a bridge restart, rebuilt purely from the journal lines.
    List<JournalRecord> records = new ArrayList<>();
    for (String l : journal) records.add(JournalCodec.decode(ParityJson.parse(l)));
    Store again = Store.replay(records, r -> { });
    check(again.state(t).linkedLoginOffers == 1 && again.state(t).autoOpenPositions.get(0).remaining == 600, "alias rebuilt from the journal");
  }

  private static void absentIsNotZero() {
    Store s = fresh();
    t = T0;
    Client c = new Client("acct-a", "s-a1");
    send(s, c);
    String id = c.place(0, 561, "Nature rune", 205, 100, true);
    send(s, c);
    Offer o = null;
    for (Offer x : s.offers()) if (x.offerId.equals(id)) o = x;
    check(o.completedAt == null, "an unfinished offer has NO completion time, not 0");
    check(o.ticksToFill == null, "no fill yet: NO tick reading (null), never the packet's -1");
    BuyLimitUsage u = s.buyLimitUsage("acct-a", 561, t);
    check(u.used == 0 && u.windowEndsAt == null, "nothing bought: no window at all, not a window ending at 0");
  }

  private static void aboveTwoToThe31() {
    Store s = fresh();
    t = T0;
    JsonObject rec = new JsonObject();
    rec.addProperty("itemId", 20997);
    rec.addProperty("name", "Twisted bow");
    rec.addProperty("quantity", 3);
    rec.addProperty("unitPrice", 2_000_000_000L);
    rec.addProperty("at", T0 - 3_600_000);
    rec.addProperty("account", "acct-a");
    check(s.recordPurchase(rec, T0).cost == 6_000_000_000L, "3 x 2,000,000,000 costs 6,000,000,000 (past 2^31)");
    StoreState st = s.state(T0);
    check(st.dataHealth.openCost == 6_000_000_000L, "open cost 6e9");
    // The largest total cost this port records is 2^53 - 1 exactly (441,650,591 x 20,394,401); one unit
    // more is refused, where the bridge would accept it and round. No GE trade comes near either (max cash
    // plus tokens is about 2.1e12).
    JsonObject edge = rec.deepCopy();
    edge.addProperty("quantity", 20_394_401L);
    edge.addProperty("unitPrice", 441_650_591L);
    edge.addProperty("at", T0 - 2);
    check(s.recordPurchase(edge, T0).cost == 9_007_199_254_740_991L, "a cost of exactly 2^53 - 1 is recorded");
    JsonObject over = edge.deepCopy();
    over.addProperty("quantity", 20_394_402L);
    over.addProperty("at", T0 - 1);
    int lines = journal.size();
    try {
      s.recordPurchase(over, T0);
      throw new AssertionError("a total cost past 2^53 - 1 must be refused");
    } catch (JournalException e) {
      check(e.getMessage().equals("That purchase is too large to record: its total cost is past 9,007,199,254,740,991 GP, the most EVI can count exactly"),
        "message: " + e.getMessage());
    }
    check(journal.size() == lines, "a refused purchase writes nothing");
    JsonObject huge = rec.deepCopy();
    huge.addProperty("quantity", 2_147_483_647L);
    huge.addProperty("unitPrice", 2_147_483_647L);
    huge.addProperty("at", T0 - 3);
    try {
      s.recordPurchase(huge, T0);
      throw new AssertionError("2,147,483,647 x 2,147,483,647 must be refused (the verifier's overflow case)");
    } catch (JournalException e) {
      check(e.getMessage().startsWith("That purchase is too large to record"), e.getMessage());
    }
    // A packet unit price above 2^31-1 is refused, exactly as the bridge does today (an open decision for the maintainer).
    Client c = new Client("acct-a", "s-a1");
    c.place(0, 20997, "Twisted bow", 2_147_483_648L, 1, false);
    try {
      s.ingest(c.packet(T0 - 300), T0);
      throw new AssertionError("a unit price past 2^31-1 is refused today, like the bridge");
    } catch (JournalException e) {
      check("Invalid offer".equals(e.getMessage()), e.getMessage());
    }
  }

  // A JS object with integer keys iterates them ASCENDING whatever the insertion order; the bridge's
  // personalUseKept is Object.fromEntries of a Map. Marked whip (4151) first, then nature rune (561).
  private static void keptCountsIterateAscending() {
    Store s = fresh();
    s.markPersonalUseItem(4151, true, 2L);
    s.markPersonalUseItem(561, true, 1L);
    s.markPersonalUseItem(13190, true, null);
    StoreState st = s.state(T0);
    check(new ArrayList<>(st.personalUseKept.keySet()).equals(Arrays.asList(561, 4151)), "kept counts by ascending id: " + st.personalUseKept);
    check(st.personalUseKept.get(4151) == 2L && st.personalUseKept.get(561) == 1L, "the counts themselves");
    check(st.personalUseItems.equals(Arrays.asList(4151, 561, 13190)), "the item list keeps MARK order (a JS Set): " + st.personalUseItems);
    check(st.personalUseItemIds.equals(Arrays.asList(13190)), "only the count-less mark is a blanket exclusion");
    check(s.markPersonalUseItem(4151, true, 1L).kept == 2L, "re-marking with fewer never lowers the count");
  }

  // The verifier's overflow, 6 Oct: past 2^63 the old port wrapped a -1.38e19 loss to +4.6e18 and
  // saturated the open cost at Long.MAX_VALUE. A journal the BRIDGE wrote can still hold purchases this port
  // would refuse (JS accepts 2,147,483,647 x 2,147,483,647), so replay those lines: one and two such
  // purchases give JS's own open cost to the last digit; a third takes it past 2^63, and state() must
  // refuse loudly rather than return a number with the wrong sign or a saturated one.
  private static void pastTwoToThe63() {
    check(Js.multiply(2_147_483_647L, 2_147_483_647L) == 4_611_686_014_132_420_608L, "the product JS computes (its double), not the exact ...609");
    check(Js.add(1000L, 9_007_199_254_740_991L) == 9_007_199_254_741_992L, "past 2^53 the sum rounds as JS's does: ...992, not ...991");
    check(Js.add(-9_007_199_254_740_991L, 9_007_199_254_740_991L) == 0 && Js.subtract(5, 7) == -2, "ordinary integers stay exact");
    check(Js.round(2.5) == 3 && Js.round(-1.5) == -1 && Js.round(-0.5) == 0, "half-up");
    for (Runnable r : new Runnable[]{() -> Js.add(9_223_372_036_854_774_784L, 9_223_372_036_854_774_784L),
      () -> Js.subtract(-9_223_372_036_854_774_784L, 9_223_372_036_854_774_784L), () -> Js.multiply(3_000_000_000_000_000_000L, 4),
      () -> Js.round(1e19), () -> Js.round(-1e19), () -> Js.round(Double.NaN), () -> Js.round(Double.POSITIVE_INFINITY), () -> Js.round(0x1p63)}) {
      try {
        r.run();
        throw new AssertionError("a GP figure past what a long can hold must be refused, not wrapped or saturated");
      } catch (ArithmeticException expected) {
        // loud, as intended
      }
    }
    check(Js.round(-0x1p63) == Long.MIN_VALUE, "-2^63 itself is a long");
    check(JournalCodec.encodeLine(new JournalRecord.FlipsImported(Arrays.asList(new ImportedFlip("fp", "csv", 561, "Nature rune", 1, 0,
      4_611_686_014_132_420_608L, 4_611_686_014_132_420_608L, 1, 2, 0, null)))).contains("\"netProceeds\":4611686014132420600,\"profit\":4611686014132420600,"),
      "a number past 2^53 is written as JSON.stringify writes it (shortest digits)");
    List<JournalRecord> lines = new ArrayList<>();
    for (int k = 1; k <= 3; k++) {
      lines.add(new JournalRecord.PurchaseRecorded("recorded:314:" + (T0 - k) + ":2147483647:2147483647", 314, "Feather", 2_147_483_647L, 2_147_483_647L,
        T0 - k, "acct-a"));
      Store s = Store.replay(lines, r -> { });
      if (k < 3) {
        long cost = s.state(T0).dataHealth.openCost;
        check(cost == (k == 1 ? 4_611_686_014_132_420_608L : 9_223_372_028_264_841_216L), k + " such purchase(s): open cost " + cost + ", JS's value expected");
        continue;
      }
      try {
        long cost = s.state(T0).dataHealth.openCost;
        throw new AssertionError("three such purchases cost about 1.38e19, past a long; got " + cost);
      } catch (ArithmeticException expected) {
        check(expected.getMessage().contains("past what a long can hold"), expected.getMessage());
      }
    }
  }

  // A journal whose totals are past 2^63 (three bridge-written purchases of 2,147,483,647 x 2,147,483,647)
  // cannot say which positions are open: closePosition must refuse with the journal's own exception and a
  // message saying why, never let the ArithmeticException from state() reach the caller, and write nothing.
  private static void closePositionPastTwoToThe63() {
    List<JournalRecord> lines = new ArrayList<>();
    for (int k = 1; k <= 3; k++) {
      lines.add(new JournalRecord.PurchaseRecorded("recorded:314:" + (T0 - k) + ":2147483647:2147483647", 314, "Feather", 2_147_483_647L,
        2_147_483_647L, T0 - k, "acct-a"));
    }
    journal.clear();
    Store s = Store.replay(lines, r -> journal.add(JournalCodec.encodeLine(r)));
    // An ordinary observed buy, still held: the position the player asks to close.
    t = T0;
    Client c = new Client("acct-a", "s-a1");
    String buy = c.place(0, 561, "Nature rune", 100, 10, true);
    send(s, c);
    c.fill(0, 10, 1000, 5);
    send(s, c);
    int lines0 = journal.size();
    check(lines0 == 2, "setup: the buy was not journalled: " + journal);
    try {
      s.state(t);
      throw new AssertionError("setup: three such purchases must put state() past a long");
    } catch (ArithmeticException expected) {
      // the premise
    }
    try {
      s.closePosition(buy, "used", t);
      throw new AssertionError("closing a position on a journal past 2^63 must be refused");
    } catch (JournalException e) {
      check(("This journal's GP totals are past what EVI can count exactly, so it cannot tell which positions are open. "
        + "Treat the journal as corrupted").equals(e.getMessage()), "wrong refusal: " + e.getMessage());
    }
    check(journal.size() == lines0, "a refused close wrote " + journal.subList(lines0, journal.size()));
  }

  // A bridge-written journal can hold a recorded purchase this port would refuse. Reviewing it by hand
  // against an observed sale must give JS's profit, 2,147,483,647 - 4,611,686,014,132,420,608 rounded as a
  // double: -4,611,686,011,984,936,960 (node prints -4611686011984937000), not the exact ...961.
  private static void bridgeWrittenHugePurchase() {
    List<JournalRecord> lines = new ArrayList<>();
    String buyId = "recorded:314:" + (T0 - 3_600_000) + ":2147483647:2147483647";
    lines.add(new JournalRecord.PurchaseRecorded(buyId, 314, "Feather", 2_147_483_647L, 2_147_483_647L, T0 - 3_600_000, "acct-a"));
    journal.clear();
    Store s = Store.replay(lines, r -> journal.add(JournalCodec.encodeLine(r)));
    t = T0;
    Client c = new Client("acct-a", "s-a1");
    send(s, c);
    String sell = c.place(0, 314, "Feather", 1, 2_147_483_647L, false);
    send(s, c);
    c.fill(0, 2_147_483_647L, 2_147_483_647L, -1);
    send(s, c);
    JsonObject a = new JsonObject();
    a.addProperty("buyId", buyId);
    JsonArray ids = new JsonArray();
    ids.add(sell);
    a.add("sellIds", ids);
    ManualFlip f = s.confirm(a, t, () -> "flip-1");
    check(f.capital == 4_611_686_014_132_420_608L && f.netProceeds == 2_147_483_647L && f.profit == -4_611_686_011_984_936_960L,
      "JS's capital and profit: " + f.capital + " / " + f.profit);
    check(journal.get(journal.size() - 1).contains("\"capital\":4611686014132420600,\"netProceeds\":2147483647,\"profit\":-4611686011984937000,"),
      "and the flip line written as the bridge writes it: " + journal.get(journal.size() - 1));
    check(s.state(t).netProfit == -4_611_686_011_984_936_960L, "net profit the same");
  }

  // JS accepts an imported trade whose netProceeds (capital + profit) is past 2^53 and journals the
  // ROUNDED sum (1000 + 9,007,199,254,740,991 is written ...992); this port refuses the row instead, so no
  // line it writes can differ from the bridge's. Exactly 2^53 - 1 is accepted.
  private static void importRefusesUnsafeNet() {
    Store s = fresh();
    JsonObject flip = new JsonObject();
    flip.addProperty("fp", "x1");
    flip.addProperty("itemId", 561);
    flip.addProperty("item", "Nature rune");
    flip.addProperty("quantity", 1);
    flip.addProperty("capital", 1000);
    flip.addProperty("profit", 9_007_199_254_740_991L);
    flip.addProperty("firstBuy", T0 - 7_200_000);
    flip.addProperty("lastSell", T0 - 3_600_000);
    JsonObject req = new JsonObject();
    req.addProperty("source", "csv");
    JsonArray rows = new JsonArray();
    rows.add(flip);
    req.add("flips", rows);
    try {
      s.importFlips(req);
      throw new AssertionError("an import whose net is past 2^53 - 1 must be refused");
    } catch (JournalException e) {
      check("Invalid quantity, capital or profit".equals(e.getMessage()), e.getMessage());
    }
    check(journal.isEmpty(), "nothing journalled");
    flip.addProperty("profit", 9_007_199_254_739_991L);
    check(s.importFlips(req).accepted == 1, "a net of exactly 2^53 - 1 is accepted");
    check(journal.size() == 1 && journal.get(0).contains("\"netProceeds\":9007199254740991,"), "and journalled exactly: " + journal);
  }

  // server.mjs profitSince counts a flip when lastSell >= since: a flip whose last sale is AT the reset
  // time counts, one millisecond later it does not.
  private static void profitSinceBoundary() {
    Store s = fresh();
    t = T0;
    Client c = new Client("acct-a", "s-a1");
    send(s, c);
    c.place(0, 561, "Nature rune", 205, 100, true);
    send(s, c);
    c.fill(0, 100, 20_500, 3);
    send(s, c);
    c.slots[0] = null;
    c.place(1, 561, "Nature rune", 214, 100, false);
    send(s, c);
    c.fill(1, 100, 21_400, -1);
    send(s, c);
    StoreState st = s.state(t);
    check(st.autoFlips.size() == 1 && st.autoFlips.get(0).profit == 500 && st.autoFlips.get(0).lastSell == T0 + 4_700,
      "one flip: 21,400 - 400 tax - 20,500 = 500, last sale at T0 + 4,700");
    long last = T0 + 4_700;
    Profit at = Profit.sinceAllAccounts(st, last), after = Profit.sinceAllAccounts(st, last + 1), all = Profit.sinceAllAccounts(st, null);
    check(at.trades == 1 && at.gp == 500 && at.winners == 1, "a reset at the exact millisecond of the last sale still counts it");
    check(after.trades == 0 && after.gp == 0, "one millisecond later it does not");
    check(all.trades == 1 && all.gp == 500 && all.since == null, "no reset: everything, and since stays absent");
  }

  private static void localeAndLineFormat() {
    check("1,000".equals(Js.groupedUs(1000)) && "300,000,000".equals(Js.groupedUs(300_000_000)), "en-US grouping under nl-NL: " + Js.groupedUs(1000));
    check("0.5".equals(Js.numberToString(0.5)) && "1e+21".equals(Js.numberToString(1e21)) && "3".equals(Js.numberToString(3.0)),
      "String(x): 0.5, 1e+21, 3 -- not 0,5 / 1.0E21 / 3.0");
    check("\"Tumeken's shadow\"".equals(Js.quote("Tumeken's shadow")), "an apostrophe is written raw, as JSON.stringify does (Gson would write \\u0027)");
    // The confirm() message the player reads, under the Dutch locale.
    Store s = fresh();
    t = T0;
    Client c = new Client("acct-a", "s-a1");
    send(s, c);
    String buy = c.place(0, 561, "Nature rune", 205, 1000, true);
    send(s, c);
    c.fill(0, 1000, 205_000, 4);
    send(s, c);
    c.slots[0] = null;
    String sell = c.place(1, 561, "Nature rune", 214, 600, false);
    send(s, c);
    c.fill(1, 600, 128_400, -1);
    send(s, c);
    JsonObject a = new JsonObject();
    a.addProperty("buyId", buy);
    JsonArray ids = new JsonArray();
    ids.add(sell);
    a.add("sellIds", ids);
    try {
      s.confirm(a, t, () -> "x");
      throw new AssertionError("600 of 1,000 must be refused");
    } catch (JournalException e) {
      check(e.getMessage().startsWith("The selected sales total 600 items but the purchase was 1,000."), "message: " + e.getMessage());
    }
  }
}
