package com.evi.live.engine;

import net.runelite.client.util.Filepath;
import com.evi.live.TestFiles;
import com.evi.live.journal.Offer;
import com.evi.live.journal.Store;
import com.evi.live.journal.Tax;
import com.evi.live.market.HourBucket;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phase 4's traps, as INTENT -- each rule CLAUDE.md names for these checks, asserted on values, so a parity diff and a broken
 * rule read differently. Every expectation here is a statement of what the rule means, never a restatement of the code.
 */
public final class AdviceIntentTest {
  private static int checks;

  static void check(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  private static final double NOW = 1791374400000.0; // 7 Oct 2026 12:00 UTC
  private static final double MIN = 60000;

  public static void main(String[] args) throws Exception {
    belowBreakEvenSpeaksWhateverTheGap();
    driftThresholdsAndTheFloor();
    noCostBasisNeverGuesses();
    neverSuggestsAPriceBelowBreakEven();
    sellAdviceOnlyOnTheSellersOwnCost();
    buyProgressStatesFactsAndNeverPredicts();
    holdingsTakeNoMinimumProfit();
    probesTeachTheFillModelNothing();
    aThinBandSaysNothing();
    thinMarketFailsOpenAndAbsenceIsAWarning();
    aCrashDemotesBeforeAnythingElseAndNeverPredicts();
    adviceIsScopedToTheNamedAccount();
    aViewHoldsOneAccountOnly();
    scopingHoldsByConstruction();
    wordingOnADutchHost();
    jsToFixed();
    nothingUserVisibleIsWired();
    System.out.println("PASS: Phase 4 advice and safety intent, " + checks + " checks");
  }

  // ------------------------------------------------------------------------------------------- relist

  private static Relist.SellOffer sell(double price, double minutesOpen) {
    return new Relist.SellOffer(561, "Nature rune", price, 100, NOW - minutesOpen * MIN);
  }

  private static Map<Integer, AdviceNote.Price> market(double sell) {
    Map<Integer, AdviceNote.Price> m = new HashMap<>();
    m.put(561, new AdviceNote.Price(sell - 10, sell));
    return m;
  }

  private static Map<Integer, Double> paid(double unitCost) {
    Map<Integer, Double> m = new HashMap<>();
    m.put(561, unitCost);
    return m;
  }

  /** 2 Oct 2026: below break-even, the early path speaks whatever the gap -- the plugin's hint would say "take the current price". */
  private static void belowBreakEvenSpeaksWhateverTheGap() {
    // Bought at 1,000 (break-even 1,020), the market at 990, an ask 32% over it, 15 minutes old, on a 2-day pace.
    List<AdviceNote> under = Relist.relistAdvice(List.of(sell(1400, 15)), market(990), paid(1000), 2880, NOW);
    check(under.size() == 1, "below break-even must speak past 5%: " + under.size());
    AdviceNote n = under.get(0);
    check("warn".equals(n.level) && Boolean.TRUE.equals(n.belowBreakEven) && "Market moved away".equals(n.label), "a below-break-even ask is a WARNING");
    check(n.suggestedPrice == 1020 && n.breakEven == 1020, "it names the break-even, never the market, as the price: " + n.suggestedPrice);
    check(n.message.contains("BELOW your break-even (1,020 after tax), so relisting there would lock in a loss"), n.message);
    check(!n.message.toLowerCase(Locale.ROOT).contains("take the current price"), "it never tells the player to take the market");
    // The same 32% gap ABOVE break-even is the plugin's to say until the clock runs out.
    check(Relist.relistAdvice(List.of(sell(1400, 15)), market(990), paid(900), 2880, NOW).isEmpty(), "above break-even past 5% stays with the plugin's hint");
    check(Relist.relistAdvice(List.of(sell(1400, 720)), market(990), paid(900), 2880, NOW).size() == 1, "...and speaks once the clock has run");
    // A market EXACTLY at break-even is not under water.
    long be = Tax.breakEvenSellPrice(561, 1000);
    List<AdviceNote> at = Relist.relistAdvice(List.of(sell(1400, 720)), market(be), paid(1000), 2880, NOW);
    check(at.size() == 1 && !at.get(0).belowBreakEven && "caution".equals(at.get(0).level), "the market AT break-even still clears it");
  }

  /** DRIFT_SPEAKS_NOW 0.015 inclusive, PLUGIN_SPEAKS_ABOVE 0.05 exclusive, DRIFT_MIN_WAIT_MINUTES 15 inclusive. */
  private static void driftThresholdsAndTheFloor() {
    check(Relist.DRIFT_SPEAKS_NOW == 0.015 && Relist.PLUGIN_SPEAKS_ABOVE == 0.05 && Relist.DRIFT_MIN_WAIT_MINUTES == 15 && Relist.MIN_GAP == 0.005,
      "the measured thresholds");
    // 1,000 against 985 is exactly 1.5%; against 985.1 just under.
    check(Relist.relistAdvice(List.of(sell(1000, 15)), market(985), paid(900), 2880, NOW).size() == 1, "1.5% exactly speaks");
    check(Relist.relistAdvice(List.of(sell(1000, 15)), market(985.1), paid(900), 2880, NOW).isEmpty(), "just under 1.5% waits for the clock");
    // The floor: a fresh offer is never second-guessed on the spot.
    check(Relist.relistAdvice(List.of(sell(1000, 14.999)), market(985), paid(900), 2880, NOW).isEmpty(), "under 15 minutes is too soon");
    // 5% exactly is the plugin's (OFFER_DRIFT_THRESHOLD is also 0.05, so the hand-off is exact); just under is ours.
    check(Relist.relistAdvice(List.of(sell(1000, 20)), market(950), paid(900), 2880, NOW).isEmpty(), "5% exactly hands over to the plugin");
    check(Relist.relistAdvice(List.of(sell(1000, 20)), market(950.1), paid(900), 2880, NOW).size() == 1, "just under 5% is the early path's");
    // The clock: a quarter of the pace, never under 30 minutes.
    // (1% over: above MIN_GAP, under DRIFT_SPEAKS_NOW, so only the clock can make it speak.)
    check(Relist.relistAdvice(List.of(sell(1000, 29.9)), market(990), paid(900), 60, NOW).isEmpty(), "30 minutes at least");
    check(Relist.relistAdvice(List.of(sell(1000, 30)), market(990), paid(900), 60, NOW).size() == 1, "the 30-minute floor");
    check(Relist.relistAdvice(List.of(sell(1000, 10000)), market(996), paid(900), 60, NOW).isEmpty(), "under MIN_GAP never speaks, however long");
  }

  private static void noCostBasisNeverGuesses() {
    List<AdviceNote> n = Relist.relistAdvice(List.of(sell(1000, 20)), market(980), null, 2880, NOW);
    check(n.size() == 1 && n.get(0).breakEven == null && !n.get(0).belowBreakEven, "no cost basis: no break-even, never 'below' it");
    check(n.get(0).message.contains("EVI doesn't know what you paid"), "and it says so");
    check(SellAdvice.sellAdvice(List.of(new SellAdvice.SellOffer(561, "Nature rune", 1, 5)), null).isEmpty(), "sell advice without a cost basis says nothing");
    check(HoldingsAdvice.holdingsAdvice(List.of(new HoldingsAdvice.Position(561, "Nature rune", 10, Double.NaN)), Map.of(561, 200.0), null, null).isEmpty(),
      "a holding without a cost basis is not listed");
  }

  /** Property: whatever relist suggests is never under the break-even when one is known. */
  private static void neverSuggestsAPriceBelowBreakEven() {
    Random r = new Random(4);
    int spoke = 0;
    for (int k = 0; k < 4000; k++) {
      double cost = 1 + r.nextInt(2_000_000), market = Math.max(1, cost * (0.7 + r.nextDouble() * 0.6)), ask = Math.ceil(market * (1 + r.nextDouble() * 0.5));
      List<AdviceNote> n = Relist.relistAdvice(List.of(new Relist.SellOffer(561, "x", ask, 3, NOW - r.nextInt(3000) * MIN)), market(market), paid(cost), 1440, NOW);
      for (AdviceNote a : n) {
        spoke++;
        check(a.suggestedPrice >= a.breakEven, "relist suggested " + a.suggestedPrice + " under the break-even " + a.breakEven);
        check(a.belowBreakEven == (market < a.breakEven), "belowBreakEven must mean the market is under the break-even");
      }
    }
    check(spoke > 500, "the property must actually be exercised: " + spoke);
  }

  private static void sellAdviceOnlyOnTheSellersOwnCost() {
    // A 1,010 ask on a 1,000 cost: break-even 1,020, so it LOSES 10 a unit after tax although it is above the cost.
    List<AdviceNote> n = SellAdvice.sellAdvice(List.of(new SellAdvice.SellOffer(561, "Nature rune", 1010, 100)), paid(1000));
    check(n.size() == 1 && n.get(0).lossEach == 10 && n.get(0).lossTotal == 1000, "the tax is what loses here");
    // A loss under 0.1% of the cost is tax rounding, not a decision.
    Map<Integer, Double> whip = new HashMap<>();
    whip.put(4151, 1500000.0);
    check(SellAdvice.sellAdvice(List.of(new SellAdvice.SellOffer(4151, "Abyssal whip", 1530000, 1)), whip).isEmpty(), "rounding is not worth an interruption");
  }

  // ------------------------------------------------------------------------------------------- buy progress

  private static final Pattern PREDICTS = Pattern.compile("(?i)\\bwill (fill|not|never)\\b|\\bdead\\b|\\bcancel\\b|\\bexpect|\\blikely\\b|\\bshould\\b");

  /** buyProgress states facts and never predicts a fill (1 Oct: the "dead buy" figure was an artefact). */
  private static void buyProgressStatesFactsAndNeverPredicts() {
    List<BuyProgress.BuyOffer> offers = List.of(new BuyProgress.BuyOffer(561, "Nature rune", 10, 0, NOW - 300 * MIN),
      new BuyProgress.BuyOffer(565, "Blood rune", 10, 4, NOW - 200 * MIN));
    check(BuyProgress.buyProgressAdvice(offers, null, Collections.emptySet(), NOW).isEmpty(), "no pace set: no expectation is invented");
    List<AdviceNote> n = BuyProgress.buyProgressAdvice(offers, 60.0, Collections.emptySet(), NOW);
    check(n.size() == 2, "both are past the player's own pace");
    for (AdviceNote a : n) {
      // The disclaimer itself says "whether it will fill"; anything else predicting is a regression.
      String rest = a.message.replace("EVI is not predicting whether it will fill", "");
      check(!PREDICTS.matcher(rest).find() && !PREDICTS.matcher(a.figures).find(), "a prediction crept in: " + a.message);
      check(a.message.contains("EVI is not predicting whether it will fill"), "the disclaimer stays");
      check("caution".equals(a.level), "nothing has gone wrong: caution, never warn");
    }
    check(BuyProgress.buyProgressAdvice(offers, 60.0, Set.of(561), NOW).size() == 1, "what slotFill already flags is not said twice");
    check(BuyProgress.buyProgressAdvice(List.of(new BuyProgress.BuyOffer(561, "x", 10, 0, NOW - 60 * MIN)), 60.0, Collections.emptySet(), NOW).isEmpty(),
      "exactly the pace is not past it");
  }

  // ------------------------------------------------------------------------------------------- holdings

  /** holdingsAdvice CANNOT be passed a minimum profit: the 152 gp pie is listed, and no overload takes a profit bar. */
  private static void holdingsTakeNoMinimumProfit() {
    List<AdviceNote> pie = HoldingsAdvice.holdingsAdvice(List.of(new HoldingsAdvice.Position(7218, "Uncooked dragonfruit pie", 1, 100)), Map.of(7218, 152.0), null, null);
    check(pie.size() == 1 && pie.get(0).net == 49, "a trivial holding is still stated: " + (pie.isEmpty() ? "none" : pie.get(0).net));
    int found = 0;
    // Every entry point -- the public forAccount and the record-level cores behind it -- takes no minimum profit.
    for (Method m : HoldingsAdvice.class.getDeclaredMethods()) {
      if (!m.getName().equals("holdingsAdvice") && !m.getName().equals("forAccount")) continue;
      found++;
      List<Class<?>> p = Arrays.asList(m.getParameterTypes());
      check(p.equals(Arrays.asList(List.class, Map.class, Set.class, Integer.class)) || p.equals(Arrays.asList(List.class, Map.class, Set.class, Integer.class, double.class))
          || p.equals(Arrays.asList(AccountView.class, com.evi.live.market.LatestPrices.class, Integer.class)),
        "holdingsAdvice must take positions, prices, listed items, the suggestion and a line cap -- nothing else: " + m);
    }
    check(found == 3, "three entry points (forAccount and two record-level cores): " + found);
    // A loss is stated as plainly as a gain.
    List<AdviceNote> loss = HoldingsAdvice.holdingsAdvice(List.of(new HoldingsAdvice.Position(561, "Nature rune", 100, 230)), Map.of(561, 214.0), null, null);
    check("Holding, under water".equals(loss.get(0).label) && loss.get(0).figures.equals("100 · -2,000 after tax") && loss.get(0).net == -2000, "under water: " + loss.get(0).figures);
  }

  // ------------------------------------------------------------------------------------------- the fill model

  private static HourBucket hour(long ts, int itemId, long vol) throws IOException {
    return HourBucket.parseLine("{\"ts\":" + ts + ",\"d\":{\"" + itemId + "\":[210," + vol + ",200," + vol + "]}}");
  }

  private static Offer offer(String state, long total, long filled, long price, long spent, Integer ticks, long firstSeen, Long completedAt) {
    return new Offer(0, state, "o" + firstSeen + state, 561, "Nature rune", price, total, filled, spent, true, ticks, "acct-a", "s", firstSeen, completedAt, completedAt, null);
  }

  /** The 1-unit instant buy above market IS a margin check (30 Sept: invisible on purpose), and teaches nothing. */
  private static void probesTeachTheFillModelNothing() throws IOException {
    long t = (long) NOW, ts = (t / 3600000) * 3600;
    List<HourBucket> buckets = List.of(hour(ts, 561, 1000));
    Offer probe = offer("BOUGHT", 1, 1, 210, 200, 2, t, t + 1200);
    FillModel.Model m = FillModel.build(List.of(probe), buckets, NOW);
    check(m.skipped == 1 && m.bands.stream().allMatch(b -> b.count == 0), "a probe is skipped, never a sample");
    Offer slow = offer("BOUGHT", 1, 1, 210, 200, 3, t, t + 1200); // three ticks: not a probe
    check(FillModel.build(List.of(slow), buckets, NOW).bands.get(0).count == 1, "one tick past the probe window is a real order");
    // Cancelled and still-open offers are counted, never dropped (the survivorship trap).
    FillModel.Model mixed = FillModel.build(List.of(offer("CANCELLED_BUY", 5, 0, 200, 0, null, t, null), offer("BUYING", 5, 0, 200, 0, null, t + 1, null)), buckets, NOW + 60 * MIN);
    FillModel.BandStats b = mixed.bands.get(0);
    check(b.count == 2 && b.filledCount == 0 && b.samples.get(0).gaveUp && b.samples.get(1).open, "a cancel is 'gave up', an open order is censored");
  }

  private static void aThinBandSaysNothing() throws IOException {
    long t = (long) NOW, ts = (t / 3600000) * 3600;
    List<HourBucket> buckets = List.of(hour(ts, 561, 1000));
    List<Offer> fourteen = new ArrayList<>();
    for (int k = 0; k < 14; k++) fourteen.add(offer("BOUGHT", 5, 5, 200, 1000, 30, t + k, t + k + 60000));
    VolumeRow row = new VolumeRow(210, 1000, 200, 1000);
    check(FillModel.fillChance(FillModel.build(fourteen, buckets, NOW), 5, row, 60) == null, "fourteen offers is too few to say anything");
    fourteen.add(offer("BOUGHT", 5, 5, 200, 1000, 30, t + 99, t + 99 + 60000));
    FillModel.Chance c = FillModel.fillChance(FillModel.build(fourteen, buckets, NOW), 5, row, 60);
    check(c != null && c.samples == 15 && c.probability == 1, "fifteen is enough");
    String s = FillModel.fillChanceSentence(c, 60);
    check(s.endsWith("That is your own record, not a forecast.") && s.contains("(15 offers"), "the sentence names its evidence and is no forecast: " + s);
    // The maintainer's 7 Oct fix (made in JS first): when NONE of the offers filled there is no typical time to state, so the
    // sentence says "(15 offers)" and never "typically 0 minutes" beside "0% finished".
    List<Offer> gaveUp = new ArrayList<>();
    for (int k = 0; k < 15; k++) gaveUp.add(offer("CANCELLED_BUY", 5, 0, 200, 0, null, t + k, null));
    FillModel.Chance none = FillModel.fillChance(FillModel.build(gaveUp, buckets, NOW), 5, row, 60);
    check(none != null && none.probability == 0 && none.medianMinutes == null, "fifteen offers that all gave up: a chance of 0 with no median");
    check("Of your own past offers this size relative to the item's trading, 0% finished within 1 hour (15 offers). That is your own record, not a forecast."
      .equals(FillModel.fillChanceSentence(none, 60)), "no typical time when nothing filled: " + FillModel.fillChanceSentence(none, 60));
  }

  // ------------------------------------------------------------------------------------------- the thin market and the crash gate

  private static Pick pick(int itemId, String name) {
    return new Pick(itemId, name, "buy", 2, 1000, 1100, "personal");
  }

  private static void thinMarketFailsOpenAndAbsenceIsAWarning() throws IOException {
    check(SafetyChecks.thinIndex(new ArrayList<>()) == null, "no archive: no index");
    List<HourBucket> seventyOne = new ArrayList<>(), seventyTwo = new ArrayList<>();
    for (int h = 0; h < 72; h++) {
      HourBucket b = hour(1791000000L + h * 3600L, 561, 50);
      if (h < 71) seventyOne.add(b);
      seventyTwo.add(b);
    }
    check(SafetyChecks.thinIndex(seventyOne) == null, "71 hours is too little to judge anything");
    ThinMarket.Index idx = SafetyChecks.thinIndex(seventyTwo);
    check(idx != null && idx.hours == 72, "72 hours arms it");
    SellSupport.Result absent = SafetyChecks.precheck(pick(4151, "Abyssal whip"), null, idx, null);
    check(absent != null && !absent.blocked && Boolean.TRUE.equals(absent.detail.thinMarket)
      && absent.warning.startsWith("Warning: Abyssal whip did not trade at all in the last 72 hours"), "absent from a long archive is the strongest warning");
    check(SafetyChecks.precheck(pick(561, "Nature rune"), null, idx, null) == null, "an item trading every hour is left alone");
    check(SafetyChecks.precheck(pick(4151, "Abyssal whip"), null, null, null) == null, "no index: no reading, never a quiet pass and never a block");
    check(ThinMarket.windowFor(600.0) == 12 && ThinMarket.windowFor(null) == 12 && ThinMarket.windowFor(60.0) == 1, "the window follows the player's pace");
  }

  private static final Pattern FORECAST = Pattern.compile("(?i)will (recover|rebound|bounce)|buy now|cheap");

  private static void aCrashDemotesBeforeAnythingElseAndNeverPredicts() throws IOException {
    CrashWatch.Crash c = new CrashWatch.Crash(561, 140, 130, 214, 200, 1 - 140 / 214.0, 1000000, 600000);
    List<HourBucket> seventyTwo = new ArrayList<>();
    for (int h = 0; h < 72; h++) seventyTwo.add(hour(1791000000L + h * 3600L, 4151, 50));
    SellSupport.Result r = SafetyChecks.precheck(pick(561, "Nature rune"), c, SafetyChecks.thinIndex(seventyTwo), null);
    check(r != null && !r.blocked && Boolean.TRUE.equals(r.detail.crashing) && r.detail.thinMarket == null, "a crash is read FIRST, and demotes rather than blocks");
    check(r.warning.startsWith("Warning: Nature rune is crashing: buyers are paying about 140 gp, 35% under its 24-hour average of 214 gp"), r.warning);
    check(r.warning.endsWith("That is history, not a forecast for this one.") && !FORECAST.matcher(r.warning).find(), "no rebound is promised");
    check(CrashWatch.crashMessage(new CrashWatch.Crash(1, 2e6, 1.9e6, 3e6, 2.9e6, 0.33, 40, 3), "X", null).contains("(a small sample)"),
      "the dear band rests on 18 crashes and says so");
    // The sidebar's note (8 Oct 2026): a short line and the tooltip's detail, the same facts as the one sentence.
    CrashWatch.Crash seaweed = new CrashWatch.Crash(21504, 44, 42, 69, 66, 1 - 44 / 69.0, 52181, 11154);
    String mine = "Your buy for 20,945 is still running (11,000 bought).";
    CrashWatch.Note n = CrashWatch.crashNote(seaweed, "Giant seaweed", mine);
    check(n.message.equals("Giant seaweed is crashing: buyers pay about 44 gp, 36% under its 24-hour average of 69 gp. " + mine), n.message);
    check(n.detail.equals("52,181 traded in the last 30 minutes (normally about 11,154 an hour). Cause unknown. Of 432 similar crashes in items under 10k "
      + "(90 days), 57% had made back most of the drop a day later and 6% were lower still. History, not a forecast."), n.detail);
    check(n.message.length() <= 160 && n.detail.length() <= 260, "a line read at a glance and a few short sentences");
    String whole = CrashWatch.crashMessage(seaweed, "Giant seaweed", null);
    java.util.regex.Matcher num = java.util.regex.Pattern.compile("\\d[\\d,]*%?").matcher(whole);
    while (num.find()) check((n.message + " " + n.detail).contains(num.group()), "no figure lost: " + num.group());
    check(CrashWatch.crashNote(new CrashWatch.Crash(1, 2e6, 1.9e6, 3e6, 2.9e6, 0.33, 40, 3), "X", null).detail.contains("(90 days, a small sample)"), "the small sample, said");
    check(CrashWatch.crashNote(new CrashWatch.Crash(561, 150, 140, Double.NaN, 9000, 0.5, 2, 0.25), null, "").detail
      .equals("2 traded in the last 30 minutes (normally about 0.3 an hour). Cause unknown."), "no band, no history invented");
    // One side alone is not a crash, and a misclick on thin volume is not either (the measurement's own rules).
    List<HourBucket> week = new ArrayList<>();
    long end = 1791374400L;
    for (int k = 192; k >= 1; k--) {
      double w = 1 + 0.01 * Math.sin(k);
      week.add(HourBucket.parseLine("{\"ts\":" + (end - k * 3600L) + ",\"d\":{\"11037\":[" + Math.round(465000 * w) + ",8," + Math.round(452000 * w) + ",8]}}"));
    }
    Map<Integer, CrashWatch.Baseline> base = CrashWatch.buildBaselines(week, end);
    check(base.containsKey(11037), "a week of steady trading has a baseline");
    check(CrashWatch.detectCrashes(base, List.of(HourBucket.parseLine("{\"ts\":" + (end + 600) + ",\"d\":{\"11037\":[465000,2,300000,80]}}"))).isEmpty(), "one side alone");
    check(CrashWatch.detectCrashes(base, List.of(HourBucket.parseLine("{\"ts\":" + (end + 600) + ",\"d\":{\"11037\":[300000,1,46464,1]}}"))).isEmpty(), "a misclick");
    check(CrashWatch.detectCrashes(base, List.of(HourBucket.parseLine("{\"ts\":" + (end + 600) + ",\"d\":{\"11037\":[300000,64,323881,49]}}"))).size() == 1, "Brine sabre, 19 Sep");
  }

  // ------------------------------------------------------------------------------------------- account scoping

  private static JsonObject packet(String account, String session, long seq, long ts, String... offers) {
    JsonObject p = new JsonObject();
    p.addProperty("version", 1);
    p.addProperty("session", session);
    p.addProperty("account", account);
    p.addProperty("seq", seq);
    p.addProperty("ts", ts);
    p.addProperty("loggedIn", true);
    JsonArray a = new JsonArray();
    for (int slot = 0; slot < 8; slot++) {
      String o = slot < offers.length ? offers[slot]
        : "{\"slot\":" + slot + ",\"itemId\":0,\"total\":0,\"filled\":0,\"price\":0,\"spent\":0,\"offerId\":\"" + session + "-e" + slot + "\",\"state\":\"EMPTY\",\"name\":\"\",\"knownStart\":false,\"ticksToFill\":-1}";
      a.add(new JsonParser().parse(o));
    }
    p.add("offers", a);
    return p;
  }

  private static String sellOffer(int slot, String id, int itemId, String name, long price, long total) {
    return "{\"slot\":" + slot + ",\"itemId\":" + itemId + ",\"total\":" + total + ",\"filled\":0,\"price\":" + price + ",\"spent\":0,\"offerId\":\"" + id
      + "\",\"state\":\"SELLING\",\"name\":\"" + name + "\",\"knownStart\":true,\"ticksToFill\":-1}";
  }

  private static String offer(int slot, String id, String state, int itemId, String name, long price, long total, long filled) {
    return "{\"slot\":" + slot + ",\"itemId\":" + itemId + ",\"total\":" + total + ",\"filled\":" + filled + ",\"price\":" + price + ",\"spent\":" + (price * filled)
      + ",\"offerId\":\"" + id + "\",\"state\":\"" + state + "\",\"name\":\"" + name + "\",\"knownStart\":true,\"ticksToFill\":" + (filled > 0 ? 5 : -1) + "}";
  }

  /** The 6 Oct pure/main bug: a poll that names no account must see nobody's offers, and an account only its own. */
  private static void adviceIsScopedToTheNamedAccount() {
    Store store = new Store(r -> { });
    long t0 = (long) NOW - 60 * 60000;
    store.ingest(packet("acct-a", "sa", 1, t0, sellOffer(0, "a1", 561, "Nature rune", 1010, 10)), t0);
    store.ingest(packet("acct-b", "sb", 1, t0, sellOffer(0, "b1", 565, "Blood rune", 1010, 10)), t0);
    long now = (long) NOW;
    store.ingest(packet("acct-a", "sa", 2, now - 1000, sellOffer(0, "a1", 561, "Nature rune", 1010, 10)), now - 1000);
    store.ingest(packet("acct-b", "sb", 2, now - 1000, sellOffer(0, "b1", 565, "Blood rune", 1010, 10)), now - 1000);
    AdviceNotes.Inputs in = new AdviceNotes.Inputs();
    in.marketLatest = latest();
    in.now = now;
    in.view = AccountView.of(store.state(now), store.offers(), "acct-a", now);
    List<AdviceNote> a = AdviceNotes.compose(in);
    check(a.size() == 1 && a.get(0).itemId == 561, "acct-a hears about its own ask only: " + a.size());
    in.view = AccountView.of(store.state(now), store.offers(), "acct-b", now);
    List<AdviceNote> b = AdviceNotes.compose(in);
    check(b.size() == 1 && b.get(0).itemId == 565, "acct-b hears about its own ask only");
    in.view = AccountView.of(store.state(now), store.offers(), null, now);
    check(AdviceNotes.compose(in).isEmpty(), "a poll naming no account speaks of nobody's offers");
    in.view = AccountView.of(store.state(now), store.offers(), "", now);
    check(AdviceNotes.compose(in).isEmpty(), "an empty account names nobody either");
    in.view = null;
    check(AdviceNotes.compose(in).isEmpty(), "no view at all is nobody's view");
  }

  /**
   * Two accounts on the SAME items (the shape every 7 Oct widening needed): a view holds its own account's offers, lots,
   * cost basis, listings and buy-limit usage, and none of the other's -- including for an item this account has no lot of.
   */
  private static void aViewHoldsOneAccountOnly() {
    Store store = new Store(r -> { });
    long now = (long) NOW, t0 = now - 3 * 3600000L;
    // b buys 50 Nature runes at 1,100 and keeps them; both accounts then run a Nature rune buy; a also lists Nature runes it
    // never bought through EVI.
    store.ingest(packet("acct-b", "sb", 1, t0 - 600000, offer(0, "b1", "BUYING", 561, "Nature rune", 1100, 50, 0)), t0 - 600000);
    store.ingest(packet("acct-b", "sb", 2, t0 - 300000, offer(0, "b1", "BOUGHT", 561, "Nature rune", 1100, 50, 50)), t0 - 300000);
    store.ingest(packet("acct-b", "sb", 3, now - 2000, offer(0, "b2", "BUYING", 561, "Nature rune", 1005, 500, 250)), now - 2000);
    store.ingest(packet("acct-a", "sa", 1, now - 1000, sellOffer(0, "a1", 561, "Nature rune", 1010, 40), offer(1, "a2", "BUYING", 561, "Nature rune", 1000, 100, 10)), now - 1000);
    AccountView a = AccountView.of(store.state(now), store.offers(), "acct-a", now), b = AccountView.of(store.state(now), store.offers(), "acct-b", now);
    for (Offer o : a.active) check("acct-a".equals(o.account), "a's view holds an offer of " + o.account);
    for (com.evi.live.journal.FifoMatcher.OpenPosition p : a.positions) check("acct-a".equals(p.account), "a's view holds a lot of " + p.account);
    check(a.liveBuys.size() == 1 && a.liveBuys.get(0).price == 1000, "a's live buys are its own: " + a.liveBuys.size());
    check(a.liveSells.size() == 1 && a.listedItemIds.contains(561), "a's listing is its own");
    Double aCost = a.costBasis.get(561), bCost = b.costBasis.get(561);
    check(aCost == null || aCost == 1000, "a's Nature rune cost is never b's 1,100 lot: " + aCost);
    check(bCost != null, "b's lot is in b's view");
    check(!b.listedItemIds.contains(561), "a's listing is not b's");
    check(a.buyLimitUsage(561).used == 10 && b.buyLimitUsage(561).used == 300, "buy-limit usage is per account: " + a.buyLimitUsage(561).used + " / " + b.buyLimitUsage(561).used);
    check(store.buyLimitUsage("acct-a", 561, now).used == a.buyLimitUsage(561).used, "the view's usage is the journal's for that account");
    AccountView nobody = AccountView.of(store.state(now), store.offers(), null, now);
    check(nobody.isEmpty() && nobody.costBasis.isEmpty() && nobody.buyLimitUsage(561).used == 0, "no account: an empty view");
    boolean immutable = false;
    try {
      a.positions.add(b.positions.get(0));
    } catch (UnsupportedOperationException e) {
      immutable = true;
    }
    check(immutable, "a view cannot be widened after it is built");
  }

  /**
   * THE STRUCTURE, asserted: the advice channel cannot reach the journal. Its inputs carry no journal state, and outside the
   * engine package the only way into each advice module is a {@code forAccount} taking an {@link AccountView} -- the
   * record-level cores that take offer lists or a cost map are package-private (the parity harness drives them).
   */
  private static void scopingHoldsByConstruction() {
    List<Class<?>> journalTypes = List.of(com.evi.live.journal.StoreState.class, Store.class, Offer.class, com.evi.live.journal.FifoMatcher.OpenPosition.class);
    for (java.lang.reflect.Field f : AdviceNotes.Inputs.class.getFields()) {
      check(!journalTypes.contains(f.getType()) && !List.class.isAssignableFrom(f.getType()) && !Map.class.isAssignableFrom(f.getType())
        && !java.util.Collection.class.isAssignableFrom(f.getType()) || f.getName().equals("slowFillItemIds"), "AdviceNotes.Inputs." + f.getName() + " reaches journal state");
    }
    for (java.lang.reflect.Field f : AccountView.class.getDeclaredFields())
      check(f.getType() != com.evi.live.journal.StoreState.class && f.getType() != Store.class, "AccountView keeps a path back to the whole journal: " + f.getName());
    for (Method m : AdviceNotes.class.getDeclaredMethods())
      for (Class<?> p : m.getParameterTypes()) check(p != com.evi.live.journal.StoreState.class && p != Store.class, "AdviceNotes." + m.getName() + " takes journal state");
    int entries = 0;
    for (Class<?> c : List.of(SellAdvice.class, BuyAdvice.class, Relist.class, BuyProgress.class, HoldingsAdvice.class)) {
      for (Method m : c.getDeclaredMethods()) {
        if (!Modifier.isPublic(m.getModifiers())) continue;
        Class<?>[] ps = m.getParameterTypes();
        for (Class<?> p : ps) check(p != List.class && p != Map.class && !journalTypes.contains(p), c.getSimpleName() + "." + m.getName() + " is public and takes " + p.getSimpleName());
        if (m.getName().equals("forAccount")) {
          entries++;
          check(ps.length > 0 && ps[0] == AccountView.class, c.getSimpleName() + ".forAccount must take the AccountView first");
        }
      }
    }
    check(entries == 5, "every advice module has exactly one forAccount entry: " + entries);
  }

  private static com.evi.live.market.LatestPrices latest() {
    try {
      return com.evi.live.market.WikiJson.latest("{\"data\":{\"561\":{\"high\":990,\"highTime\":1791374340,\"low\":950,\"lowTime\":1791374330},"
        + "\"565\":{\"high\":990,\"highTime\":1791374340,\"low\":950,\"lowTime\":1791374330}}}");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ------------------------------------------------------------------------------------------- formatting

  /** A Dutch host (the maintainer's) must still read "1,000" and "1.5%": en-US always, never the host's "1.000" and "1,5%". */
  private static void wordingOnADutchHost() {
    Locale before = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("nl", "NL"));
      AdviceNote n = Relist.relistAdvice(List.of(new Relist.SellOffer(561, "Nature rune", 1000, 1234, NOW - 20 * MIN)), market(985), paid(900), 2880, NOW).get(0);
      check(n.message.startsWith("1.5% over the going rate, 1,234 still unsold."), n.message);
      check(n.figures.equals("1,000 asked · 985 market"), n.figures);
      AdviceNote h = BuyProgress.buyProgressAdvice(List.of(new BuyProgress.BuyOffer(561, "x", 10, 0, NOW - 390 * MIN)), 60.0, Collections.emptySet(), NOW).get(0);
      check(h.figures.endsWith("open 6.5h"), h.figures);
    } finally {
      Locale.setDefault(before);
    }
  }

  /** Number.prototype.toFixed: the exact binary value, ties to the larger magnitude, the sign kept on a negative zero. */
  private static void jsToFixed() {
    check(JsValues.toFixed(0.15, 1).equals("0.1") && JsValues.toFixed(0.05, 1).equals("0.1") && JsValues.toFixed(1.45, 1).equals("1.4"), "exact binary values");
    check(JsValues.toFixed(2.5, 0).equals("3") && JsValues.toFixed(-0.04, 1).equals("-0.0") && JsValues.toFixed(-0.0, 1).equals("0.0"), "ties and signs");
    check(JsValues.toFixed(1e21, 1).equals("1e+21") && JsValues.toFixed(Double.NaN, 1).equals("NaN"), "the spec's edges");
  }

  // ------------------------------------------------------------------------------------------- nothing user-visible yet

  /** The Phase 4 classes are engine-only: nothing in the plugin outside com.evi.live.engine reaches them -- except the in-process
   *  engine (com.evi.live.inprocess), which composes them; see EviLiveInProcessWiringTest. (4.0.0: the shadow package is gone.) */
  private static void nothingUserVisibleIsWired() throws IOException {
    String dir = System.getProperty("evi.mainSource");
    check(dir != null && !dir.isEmpty(), "evi.mainSource is not set");
    Filepath root = TestFiles.rooted(dir), engine = TestFiles.at(root, "com", "evi", "live", "engine"),
      inprocess = TestFiles.at(root, "com", "evi", "live", "inprocess");
    check(!TestFiles.at(root, "com", "evi", "live", "shadow").exists(), "the shadow package must be gone (4.0.0)");
    String[] phase4 = {"ThinMarket", "CrashWatch", "Relist", "SellAdvice", "BuyAdvice", "BuyProgress", "HoldingsAdvice", "FillModel", "AdviceNote",
      "AdviceNotes", "SafetyChecks", "AccountView"};
    for (String c : phase4) check(engine.joinSegment(c + ".java").isFile(), "missing " + c);
    List<String> users = new ArrayList<>();
    int scanned = 0;
    for (Filepath f : TestFiles.files(root, ".java")) {
      if (f.startsWith(engine) || f.startsWith(inprocess)) continue;
      scanned++;
      String text = TestFiles.text(f);
      for (String c : phase4) if (Pattern.compile("\\b" + c + "\\b").matcher(text).find() && text.contains("com.evi.live.engine")) users.add(TestFiles.relative(root, f) + ": " + c);
    }
    check(scanned >= 40, "too few plugin sources scanned (" + scanned + ")");
    check(users.isEmpty(), "Phase 4 is wired into the plugin already: " + users);
  }
}
