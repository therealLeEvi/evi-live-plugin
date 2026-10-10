package com.evi.live.engine;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Offer;
import com.evi.live.market.HourBucket;
import com.evi.live.market.LatestPrices;
import com.evi.live.market.WikiJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Phase 3b's traps as INTENT, beside the recorded vectors and transcripts, so a parity diff and a broken rule read
 * differently. Every expectation is a literal, chosen so the obvious wrong implementation gives a different one.
 * Synthetic data only.
 */
public final class TierIntentTest {
  private static int checks;

  public static void main(String[] args) throws Exception {
    theChainCannotForgetToWait();
    heldBackIsNeverShownDemotedIs();
    theBlocklistIsTheCallersSet();
    aLossAlwaysSpeaksATrivialGainStepsAside();
    holdingsAreScopedToTheAccount();
    idleStockIsNeverGpAndOnlyTheSurplus();
    theSupportWindowIsAlignedToTheHour();
    staleSupportCapsTheHeadlineOnly();
    theTaxBarAtSupportIsTheTiersOwn();
    autoIsAPreferenceNotAGate();
    numbersPrintAsTheBridgePrintsThem();
    theVerdictSaysWhatTheBridgeSays();
    theMoneyGatesHoldOnTheBoundary();
    theSeventhOctoberFixesHold();
    aBrokenSupportReadingFailsOpen();
    nothingIsWiredIntoThePluginYet();
    System.out.println("PASS: tier intent, " + checks + " checks");
  }

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  private static LatestPrices latest(String json) {
    try {
      return WikiJson.latest("{\"data\":" + json + "}");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Pick buy(int id, long low, long high) {
    Pick p = new Pick(id, "Item " + id, "buy", 100, low, high, "personal");
    p.reasoning = "Ranked.";
    return p;
  }

  // 5 Oct: an async rank was not awaited on three paths and four checks were skipped. Here rank RETURNS the candidate: the
  // interface's single method answers a Pick, not a future, so there is nothing to forget to wait for.
  static void theChainCannotForgetToWait() throws NoSuchMethodException {
    check(PickChain.Rank.class.getMethod("rank", Set.class).getReturnType() == Pick.class, "Rank must hand back the candidate itself");
    check(PickChain.SupportHook.class.getMethod("supportFor", Pick.class).getReturnType() == SellSupport.Result.class, "the support hook answers synchronously");
    // ...and the checks DO run on a ranked candidate: the first is demoted by sell support, the second is returned.
    List<Pick> ranked = Arrays.asList(buy(561, 200, 214), buy(565, 400, 420));
    PickChain.Options o = new PickChain.Options();
    o.blocklist = new LinkedHashSet<>();
    o.rank = bl -> { for (Pick p : ranked) if (!bl.contains(p.itemId)) return buy(p.itemId, p.buyPrice, p.sellPrice); return null; };
    o.supportFor = c -> c.itemId == 561 ? new SellSupport.Result(false, "Warning: weak.", null) : null;
    Pick got = PickChain.pick(o);
    check(got != null && got.itemId == 565 && got.demoted == null && "Ranked.".equals(got.reasoning), "the demoted first pick gives way to the second");
  }

  // 27 Sept: 172,266 blood runes reached the sidebar because a demotion only labels. HELD BACK is never shown, even alone.
  static void heldBackIsNeverShownDemotedIs() {
    PickChain.Options o = new PickChain.Options();
    o.blocklist = new LinkedHashSet<>();
    o.rank = bl -> bl.contains(565) ? null : buy(565, 400, 420);
    o.supportFor = c -> new SellSupport.Result(true, "Set aside: thin.", null);
    List<PickChain.Blocked> seen = new ArrayList<>();
    o.onBlocked = seen::addAll;
    check(PickChain.pick(o) == null, "a pick thinner than its own tax at the supported price is never returned");
    check(seen.size() == 1 && seen.get(0).pick != null && seen.get(0).pick.reasoning.equals("Set aside: thin. Ranked."), "it is held back, warning first");
    PickChain.Options d = new PickChain.Options();
    d.blocklist = new LinkedHashSet<>();
    d.rank = bl -> bl.contains(1755) ? null : buy(1755, 20, 30);
    d.supportFor = c -> new SellSupport.Result(false, "Warning: weak.", null);
    Pick shown = PickChain.pick(d);
    check(shown != null && Boolean.TRUE.equals(shown.demoted)
      && shown.reasoning.equals("Warning: weak. Ranked. No other candidate passed this check right now, which is why this one is shown."),
      "a merely demoted pick is shown, flagged, when nothing better passes");
  }

  // Several bridge call sites share one Set; the chain must grow the CALLER's, not a copy.
  static void theBlocklistIsTheCallersSet() {
    Set<Integer> shared = new LinkedHashSet<>(Collections.singletonList(2));
    PickChain.Options o = new PickChain.Options();
    o.blocklist = shared;
    o.rank = bl -> bl.contains(561) ? null : buy(561, 200, 214);
    o.correlationFor = c -> new PickChain.Check(true, "Moves with your stock.");
    PickChain.pick(o);
    check(shared.equals(new LinkedHashSet<>(Arrays.asList(2, 561))), "the correlated item went into the caller's own set: " + shared);
  }

  // 28 Sept / 30 Sept: a loss always speaks, an unknown worth always speaks, a trivial gain steps aside under the minimum.
  static void aLossAlwaysSpeaksATrivialGainStepsAside() {
    LatestPrices l = latest("{\"561\":{\"high\":214,\"low\":200}}");
    Pick loss = HoldingTiers.computeHoldingSuggestion(l, 561, 1000, "Nature rune", "a1-1", 220);
    check(loss.lossIfSoldNow == 10000 && loss.netIfSoldNow == -10000 && loss.breakEvenPrice == 225 - 1, "bought at 220: a 10,000 loss, break-even 224");
    check(HoldingTiers.holdingPreempts(loss, 1_000_000_000), "a loss speaks whatever the minimum");
    Pick unknown = HoldingTiers.computeHoldingSuggestion(l, 561, 1000, null, null, Double.NaN);
    check(unknown.netIfSoldNow == null && HoldingTiers.holdingPreempts(unknown, 1_000_000_000) && "item 561".equals(unknown.name), "an unknown cost speaks, named by id");
    Pick gain = HoldingTiers.computeHoldingSuggestion(l, 561, 1000, "Nature rune", null, 205);
    check(gain.netIfSoldNow == 5000 && !HoldingTiers.holdingPreempts(gain, 50000) && HoldingTiers.holdingPreempts(gain, 5000), "+5,000 steps aside under 50,000, not under 5,000");
    check(HoldingTiers.holdingPreempts(gain, 0) && HoldingTiers.holdingPreempts(gain, Double.NaN), "no minimum: it speaks");
    check(!HoldingTiers.comparableAsHistoryPick(gain, "both") && HoldingTiers.comparableAsHistoryPick(buy(561, 200, 214), "both"),
      "a holding is never weighed against a market buy (30 Sept)");
  }

  // 6 Oct: a poll naming no account speaks for nobody; another account's stock is never this one's.
  static void holdingsAreScopedToTheAccount() {
    List<FifoMatcher.OpenPosition> positions = Arrays.asList(
      new FifoMatcher.OpenPosition("acct-b", 565, "Blood rune", "b1-1", 2000, 2000, 1000L, 430, false),
      new FifoMatcher.OpenPosition("acct-a", 561, "Nature rune", "a1-2", 1000, 1000, 3000L, 220, false),
      new FifoMatcher.OpenPosition("acct-a", 4151, "Abyssal whip", "a1-1", 1, 1, 2000L, 1500000, false));
    check(HoldingTiers.pickPersistentOpenPosition(positions, null, null, null) == null && HoldingTiers.pickPersistentOpenPosition(positions, "", null, null) == null,
      "no account: nothing");
    check(idOf(HoldingTiers.pickPersistentOpenPosition(positions, "acct-a", null, null)) == 4151, "acct-a's OLDEST position, never acct-b's older one");
    check(idOf(HoldingTiers.pickPersistentOpenPosition(positions, "acct-a", null, id -> id == 4151)) == 561, "a listed position is walked past, not stopped at");
    Offer selling = new Offer(0, "SELLING", "a1-9", 561, "Nature rune", 230, 1000, 0, 0, true, null, "acct-b", "s", 1L, 1L, null, null);
    check(!HoldingTiers.hasLiveSellOffer(Collections.singletonList(selling), 561, "acct-a") && HoldingTiers.hasLiveSellOffer(Collections.singletonList(selling), 561, "acct-b")
      && !HoldingTiers.hasLiveSellOffer(Collections.singletonList(selling), 561, null), "a live sell is the asking account's only");
  }

  private static int idOf(FifoMatcher.OpenPosition p) {
    return p == null ? -1 : p.itemId;
  }

  // 30 Sept: tokens are spending power, never stock; marking an item kept-for-use protects only that many.
  static void idleStockIsNeverGpAndOnlyTheSurplus() {
    LatestPrices l = latest("{\"995\":{\"high\":1,\"low\":1},\"13204\":{\"high\":1000,\"low\":1000},\"4151\":{\"high\":1560000,\"low\":1500000}}");
    TreeMap<Integer, Long> bag = new TreeMap<>();
    bag.put(995, 900_000_000L);
    bag.put(13204, 5000L);
    check(HoldingTiers.computeInventorySuggestion(l, bag, Collections.emptyMap(), null) == null, "coins and platinum tokens are never offered");
    bag.put(4151, 3L);
    HoldingTiers.InventoryOptions o = new HoldingTiers.InventoryOptions();
    o.keptForUse = Collections.singletonMap(4151, 2L);
    Pick p = HoldingTiers.computeInventorySuggestion(l, bag, Collections.singletonMap(4151, "Abyssal whip"), o);
    check(p != null && p.quantity == 1 && "inventory".equals(p.source) && p.buyId == null, "3 held, 2 kept: the surplus 1 is offered");
    o.keptForUse = Collections.singletonMap(4151, 3L);
    check(HoldingTiers.computeInventorySuggestion(l, bag, Collections.emptyMap(), o) == null, "holding exactly what is kept leaves nothing");
  }

  // The window is the 12 FULL hours before now: start inclusive, the current hour excluded, seconds against milliseconds.
  static void theSupportWindowIsAlignedToTheHour() {
    long now = 1791374400000L + 1800000; // half past an hour
    double end = 1791374400; // the hour, in seconds
    List<SellSupport.Point> s = Arrays.asList(
      new SellSupport.Point(end, 999.0, 1000.0, null, null),             // the current hour: not yet complete, excluded
      new SellSupport.Point(end - 12 * 3600, 100.0, 1.0, null, null),    // the window's first hour: included
      new SellSupport.Point(end - 13 * 3600, 999.0, 1000.0, null, null), // before it: excluded
      new SellSupport.Point(end - 3600, 300.0, 3.0, null, null));
    SellSupport.Detail d = SellSupport.sellPriceSupport(s, 561, 200, now);
    check(d.units == 4 && d.averagePaid == 250 && d.latestPaid == 300 && d.netAtAverage == 250 - 5 - 200, "the window holds exactly the two hours inside it");
    check(SellSupport.sellPriceSupport(s, 561, 0, now) == null && SellSupport.sellPriceSupport(Collections.emptyList(), 561, 200, now) == null,
      "no buy price or no series: no reading, never a guess");
  }

  // The stale cap moves the NUMBER, never the pick: at 1.5x the headline price is what buyers pay now.
  static void staleSupportCapsTheHeadlineOnly() {
    SellSupport.Detail stale = new SellSupport.Detail(6010.0, 12, 399.6, 192.6, true, 210.0, 1.5);
    SellSupport.Detail almost = new SellSupport.Detail(6010.0, 12, 399.6, 192.6, true, 266.4, 1.4999);
    check(SellSupport.supportedPriceForHeadline(stale) == 210 && SellSupport.supportedPriceForHeadline(almost) == 399.6, "capped at 1.5x, not just below");
    Pick p = new Pick(561, "Nature rune", "buy", 0, 200, 214, "personal"); // a missing quantity counts as one unit
    p.sellSupport = stale;
    Verdict.enrich(p, null, null);
    check(p.quotedProfit == 10 && p.expectedProfit == 6 && Boolean.TRUE.equals(stale.stale), "headline = min(quoted 10, at 210: 6); the reading is marked stale");
    check("Buyers have moved on".equals(p.verdict.label), "and the card says why the number dropped");
  }

  // The bar at the supported price is the tier's own: 0.5x the tax with a track record, 1x without.
  static void theTaxBarAtSupportIsTheTiersOwn() {
    SellSupport.Detail d = new SellSupport.Detail(1000.0, 12, 207.0, 3.0, true, 207.0, 1.0); // a 3 gp edge against a 4 gp tax
    Pick personal = buy(561, 200, 214);
    check(!SellSupport.judge(d, personal, 0, false, true, LevelSettings.LOW).blocked, "0.75x clears the personal tier's 0.5x bar");
    Pick market = new Pick(561, "Nature rune", "buy", 100, 200, 214, "market");
    check(SellSupport.judge(d, market, 0, false, true, LevelSettings.LOW).blocked, "but not the 1x bar of a tier with no track record");
    check(!SellSupport.judge(d, market, 0, false, false, LevelSettings.LOW).blocked, "and \"no minimum at all\" switches the bar off");
    SellSupport.Result r = SellSupport.judge(d, personal, 1000, true, true, LevelSettings.LOW);
    check(!r.blocked && r.warning.startsWith("Warning: below your 1,000 gp minimum") && Boolean.TRUE.equals(r.detail.belowMinimumAtSupportedPrice)
      && d.belowMinimumAtSupportedPrice == null, "300 at support under a 1,000 minimum: a warning, on a COPY of the reading");
  }

  // 28 Sept: Auto's floor scales with the stack and is a preference; 500 is the floor of the floor.
  static void autoIsAPreferenceNotAGate() {
    check(Policy.autoMinProfit(89_000_000) == 89000 && Policy.autoMinProfit(Double.NaN) == 500 && Policy.autoMinProfit(50_000) == 500
      && Policy.autoMinProfit(500_500) == 501, "max(500, round(cash x 0.1%)), half-up");
    check(Policy.headlineProfit(10L, null) == 10 && Policy.headlineProfit(10L, 6L) == 6 && Policy.headlineProfit(10L, 40L) == 10 && Policy.headlineProfit(null, 6L) == null,
      "the headline is the cautious of the two, the quote alone when nothing was measured");
  }

  // toLocaleString('en-US') on ICU: shortest digits, three decimals, half away from zero, the sign kept on -0 -- on ANY host.
  static void numbersPrintAsTheBridgePrintsThem() {
    Locale before = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("nl", "NL")); // a Dutch host: 1.234,5 if the format ever followed the host
      check("1,234.568".equals(JsValues.localeUs(1234.5678)) && "1.001".equals(JsValues.localeUs(1.0005)) && "0.002".equals(JsValues.localeUs(0.0015)),
        "grouping and three decimals, rounded on the shortest form: " + JsValues.localeUs(1.0005));
      check("-0".equals(JsValues.localeUs(-0.0004)) && "-0".equals(JsValues.gp(-0.3)) && "-0".equals(JsValues.gp(-0.5)) && "-1".equals(JsValues.gp(-0.51)),
        "Math.round keeps minus zero, and toLocaleString prints it");
      check("3".equals(JsValues.gp(2.5)) && "-2".equals(JsValues.gp(-2.5)) && "1,152,921,504,606,847,000".equals(JsValues.localeUs(0x1p60)), "half-up, and 2^60 as ICU prints it");
      check("0".equals(JsValues.text(-0.0)) && "0.30000000000000004".equals(JsValues.text(0.1 + 0.2)), "a template prints -0 as 0");
      Pick fractional = HoldingTiers.computeHoldingSuggestion(latest("{\"561\":{\"high\":214,\"low\":200}}"), 561, 3, "Nature rune", null, 650.0 / 3);
      check(fractional.reasoning.contains("bought at 216.667 gp") && fractional.lossIfSoldNow == 20, "a fractional cost basis, as the bridge words it");
    } finally {
      Locale.setDefault(before);
    }
  }

  // The maintainer's 7 Oct fix (made in JS first): a net that rounds to zero is ZERO -- not minus zero, not a loss -- and the
  // card reads "+0", never "+-0". Pinned on the holding AND on a minus zero handed to the verdict from anywhere else.
  static void theVerdictSaysWhatTheBridgeSays() {
    Pick p = HoldingTiers.computeHoldingSuggestion(latest("{\"561\":{\"high\":214,\"low\":200}}"), 561, 1, "Nature rune", null, 210.2);
    check(p.netIfSoldNow == 0 && 1 / p.netIfSoldNow > 0 && p.lossIfSoldNow == null, "a 0.2 gp loss rounds to plain 0, and no loss is claimed");
    Verdict.Result v = Verdict.suggestionVerdict(p);
    check("Above break-even".equals(v.label) && "+0 gp over what you paid".equals(v.checks.get(0).text), "and the card reads +0: " + v.checks.get(0).text);
    Pick minusZero = new Pick(561, "Nature rune", "sell", 1, 200, 214, "holding");
    minusZero.netIfSoldNow = -0.0;
    check("+0 gp over what you paid".equals(Verdict.suggestionVerdict(minusZero).checks.get(0).text), "a minus zero from anywhere prints as 0");
    check("-0".equals(JsValues.gp(-0.3)) && "0".equals(Verdict.gp(-0.3)) && "-1".equals(Verdict.gp(-0.51)) && "NaN".equals(Verdict.gp(Double.NaN)),
      "only the verdict folds -0; JsValues.gp keeps the bridge's \"-0\" everywhere else, and NaN stays NaN");
    Pick idle = new Pick(4151, "Abyssal whip", "sell", 1, 1500000, 1560000, "inventory");
    Verdict.enrich(idle, null, null);
    check(idle.expectedProfit == null && idle.quotedProfit == 60000 - 31200 && Verdict.CAUTION.equals(idle.verdict.level), "idle stock: no profit claimed");
  }

  // The maintainer's 7 Oct fixes on the sell-support reading and the history tier's wording (made in JS first, matched here).
  static void theSeventhOctoberFixesHold() throws IOException {
    long now = 1791374400000L + 1800000;
    long end = 1791374400;
    List<HourBucket> eleven = new ArrayList<>(), twelve = new ArrayList<>();
    for (int k = 12; k >= 1; k--) {
      HourBucket b = HourBucket.parseLine("{\"ts\":" + (end - k * 3600) + ",\"d\":{\"561\":[205,1000,195,1000]}}");
      twelve.add(b);
      if (k <= 11) eleven.add(b);
    }
    // (7) an EMPTY series is as unusable as none: eleven archived hours are not a twelve-hour reading either way.
    check(SellSupport.reading(Collections.emptyList(), eleven, 561, 200, now) == null && SellSupport.reading(null, eleven, 561, 200, now) == null,
      "an empty series and no series alike: no reading from eleven archived hours");
    SellSupport.Detail full = SellSupport.reading(Collections.emptyList(), twelve, 561, 200, now);
    check(full != null && full.units == 12000 && full.averagePaid == 205, "twelve archived hours still read, series or none");
    List<SellSupport.Point> one = Collections.singletonList(new SellSupport.Point(end - 3600, 214.0, 7.0, null, null));
    check(SellSupport.reading(one, eleven, 561, 200, now) != null, "a series with a point in it is usable, and the archive merges over it");
    // (8) the below-minimum warning groups its buyer count as the plain support note always has.
    SellSupport.Detail d = new SellSupport.Detail(12000.0, 12, 214.0, 10.0, true, 214.0, 1.0);
    SellSupport.Result r = SellSupport.judge(d, new Pick(561, "Nature rune", "buy", 2000, 200, 240, "personal"), 50000, true, true, LevelSettings.forRequest("low"));
    check(r != null && !r.blocked && r.warning.contains("over the last 12 hours 12,000 buyers paid an average of 214 gp"), "grouped: " + (r == null ? null : r.warning));
    // (8) under "Bigger positions" the history tier names the WINDOW share that bound, not the per-hour ladder.
    double t = 1791374400000.0;
    HistoryTier.Options o = new HistoryTier.Options();
    o.volumes = HourBucket.parseLine("{\"ts\":" + (end - 3600) + ",\"d\":{\"561\":[214,1000,200,1000]}}");
    o.targetDurationMinutes = 720;
    o.volumeWindowShare = 0.1;
    List<History.Flip> five = Arrays.asList(flip(561, 50000, 5000, t - 5 * 3600000), flip(561, 50000, 5000, t - 6 * 3600000), flip(561, 50000, 5000, t - 7 * 3600000));
    Pick bigger = HistoryTier.computeSuggestion(five, latest("{\"561\":{\"high\":214,\"low\":200}}"), t, o);
    check(bigger.quantity == 1200 && bigger.reasoning.contains("reduced to 10% of what this item trades over your whole trade window, so the order isn't larger than the market absorbs")
      && !bigger.reasoning.contains("typical hour"), "the window share is named: " + bigger.reasoning);
    o.volumeWindowShare = Double.NaN;
    Pick ladder = HistoryTier.computeSuggestion(five, latest("{\"561\":{\"high\":214,\"low\":200}}"), t, o);
    check(ladder.quantity == 1000 && ladder.reasoning.contains("reduced to 100% of what this item trades in a typical hour"), "without it, the ladder: " + ladder.reasoning);
  }

  // 7 Oct: five comparisons on money gates survived an independent mutation run because nothing sat exactly ON a floor.
  // Each check below is the tie itself, with the neighbour that flips it, so `<` and `<=` read differently.
  static void theMoneyGatesHoldOnTheBoundary() {
    // Idle stock worth EXACTLY 100,000 is offered; one gp short is not.
    LatestPrices inv = latest("{\"4151\":{\"high\":100000,\"low\":95000},\"565\":{\"high\":99999,\"low\":90000}}");
    TreeMap<Integer, Long> one = new TreeMap<>();
    one.put(4151, 1L);
    Pick at = HoldingTiers.computeInventorySuggestion(inv, one, Collections.singletonMap(4151, "Abyssal whip"), null);
    check(at != null && at.itemId == 4151 && at.quantity == 1, "100,000 exactly is at the floor, not under it");
    TreeMap<Integer, Long> under = new TreeMap<>();
    under.put(565, 1L);
    check(HoldingTiers.computeInventorySuggestion(inv, under, Collections.emptyMap(), null) == null, "99,999 is under it");

    // A holding losing EXACTLY half a gp in total (the boundary of the maintainer's 7 Oct fix, made in JS first): Math.round
    // takes -0.5 to minus zero, so the rounded total is 0 and the holding takes the rounded branch -- "would net about +0 gp",
    // no loss claimed, never "a LOSS of about 0 gp". One more tenth and it rounds to a whole gp, and is the loss it is.
    LatestPrices nat = latest("{\"561\":{\"high\":214,\"low\":200}}");
    Pick subGp = HoldingTiers.computeHoldingSuggestion(nat, 561, 1, "Nature rune", null, 210.5);
    check(subGp.reasoning.startsWith("You're holding 1 Nature rune bought at 210.5 gp -- selling near 214 gp now would net about +0 gp.")
      && !subGp.reasoning.contains("LOSS") && subGp.lossIfSoldNow == null && subGp.netIfSoldNow == 0 && 1 / subGp.netIfSoldNow > 0,
      "a 0.5 gp loss rounds to the +0 branch: " + subGp.reasoning);
    check(!HoldingTiers.holdingPreempts(subGp, 500) && HoldingTiers.holdingPreempts(subGp, 0), "a trivial net steps aside under 500, as before");
    Pick wholeGp = HoldingTiers.computeHoldingSuggestion(nat, 561, 1, "Nature rune", null, 210.6);
    check(wholeGp.reasoning.startsWith("WARNING: you're holding 1 Nature rune bought at 210.6 gp") && wholeGp.reasoning.contains("LOSS of about 1 gp")
      && wholeGp.lossIfSoldNow == 1 && HoldingTiers.holdingPreempts(wholeGp, 500), "-0.6 rounds to a 1 gp loss, which always speaks");
    Pick evenHold = HoldingTiers.computeHoldingSuggestion(nat, 561, 3, "Nature rune", null, 210);
    check(evenHold.reasoning.startsWith("You're holding 3 Nature rune bought at 210 gp") && evenHold.lossIfSoldNow == null && evenHold.netIfSoldNow == 0,
      "exactly break-even is not a loss");

    // The offer prompt's "loss if sold now" (Prices.withCostBasis) is rounded FIRST, exactly like the holding above (the
    // maintainer's 7 Oct leftover fix, made in JS first): never a loss of "0 gp", and an exact .5 tie on a loss rounds the
    // way the sidebar rounds it (-2.5 -> 2), where the old rounding of the absolute value said 3. The two must agree.
    ItemPrice natPrice = new ItemPrice(561, 200, 214, null, null, null);
    check(Prices.withCostBasis(natPrice, 210.2, 1).lossIfSoldNow == null, "a 0.2 gp loss is no loss at the prompt (it used to read 0)");
    check(Prices.withCostBasis(natPrice, 210.1, 5).lossIfSoldNow == null, "-0.5 in total rounds to minus zero: no loss (it used to read 1)");
    check(Long.valueOf(2).equals(Prices.withCostBasis(natPrice, 212.5, 1).lossIfSoldNow), "-2.5 is a loss of 2, as the sidebar says (it used to read 3)");
    check(Long.valueOf(9).equals(Prices.withCostBasis(natPrice, 213, 3).lossIfSoldNow), "a whole-gp loss is still the loss it is");
    for (double[] c : new double[][]{{210.2, 1}, {210.1, 5}, {212.5, 1}, {210.6, 1}, {213, 3}, {209.5, 2}, {215.25, 4}}) {
      Long prompt = Prices.withCostBasis(natPrice, c[0], c[1]).lossIfSoldNow;
      Long sidebar = HoldingTiers.computeHoldingSuggestion(nat, 561, (long) c[1], "Nature rune", null, c[0]).lossIfSoldNow;
      check(java.util.Objects.equals(prompt, sidebar), "the prompt and the sidebar agree at " + c[0] + " x " + c[1] + ": " + prompt + " vs " + sidebar);
    }

    // The personal tier: a predicted total EQUAL to the minimum is offered; a zero score is not.
    LatestPrices hist = latest("{\"561\":{\"high\":214,\"low\":200}}");
    double t = 1791374400000.0;
    List<History.Flip> fifty = Arrays.asList(flip(561, 500, 50, t), flip(561, 500, 50, t), flip(561, 500, 50, t));
    HistoryTier.Options o = new HistoryTier.Options();
    o.minProfit = 500;
    Pick tie = HistoryTier.computeSuggestion(fifty, hist, t, o);
    check(tie != null && tie.quantity == 50, "50 x 10 gp = 500 reaches a 500 minimum");
    o.minProfit = 501;
    check(HistoryTier.computeSuggestion(fifty, hist, t, o) == null, "and not a 501 one");
    HistoryTier.Options z = new HistoryTier.Options();
    z.risk = "high";
    List<History.Flip> even = Arrays.asList(flip(561, 500, 10, t), flip(561, -500, 10, t));
    check(HistoryTier.computeSuggestion(even, hist, t, z) == null, "an average profit of exactly 0 scores 0 and is never suggested");

    // Sell support: a supported total EQUAL to the minimum draws no below-minimum warning; one gp more minimum does.
    SellSupport.Detail d = new SellSupport.Detail(1200.0, 12, 214.0, 10.0, true, 214.0, 1.0);
    Pick c = new Pick(561, "Nature rune", "buy", 50, 200, 214, "market");
    SellSupport.Result ok = SellSupport.judge(d, c, 500, true, true, LevelSettings.LOW);
    check(ok != null && !ok.blocked && ok.warning == null && ok.detail.belowMinimumAtSupportedPrice == null, "500 at support meets a 500 minimum");
    SellSupport.Result short1 = SellSupport.judge(d, c, 501, true, true, LevelSettings.LOW);
    check(short1 != null && short1.warning != null && short1.warning.startsWith("Warning: below your 501 gp minimum"), "and falls short of 501");
  }

  // The bridge's support hook fails OPEN (supportForSuggestion's `catch { return null; }`); the chain alone propagates, as
  // pickWithForecast does. failOpen is the catch the wiring must carry.
  static void aBrokenSupportReadingFailsOpen() {
    PickChain.SupportHook broken = c -> { throw new IllegalStateException("a series that would not parse"); };
    PickChain.Options raw = new PickChain.Options();
    raw.blocklist = new LinkedHashSet<>();
    raw.rank = bl -> bl.contains(561) ? null : buy(561, 200, 214);
    raw.supportFor = broken;
    boolean propagated = false;
    try {
      PickChain.pick(raw);
    } catch (IllegalStateException e) {
      propagated = true;
    }
    check(propagated, "the bare chain propagates a hook's exception, as pickWithForecast does");
    PickChain.Options wrapped = new PickChain.Options();
    wrapped.blocklist = new LinkedHashSet<>();
    wrapped.rank = raw.rank;
    wrapped.supportFor = PickChain.failOpen(broken);
    Pick got = PickChain.pick(wrapped);
    check(got != null && got.itemId == 561 && got.sellSupport == null && got.demoted == null && "Ranked.".equals(got.reasoning),
      "wrapped, a reading that throws is no reading: the candidate goes on unlabelled");
    check(PickChain.failOpen(null) == null, "no hook stays no hook");

    // P32: WHICH throws it absorbs, as a direct contract. supportForSuggestion's `catch { return null; }` takes anything the
    // reading throws, so every Exception is "no reading" -- the ones the Java reading can really raise (Js.round's
    // ArithmeticException past a long, a missing field's NullPointerException, a parse error) and a checked exception
    // that arrived undeclared. An Error is not a reading at all: it must reach the test runner, the same instance.
    Pick cand = buy(561, 200, 214);
    List<Exception> absorbed = Arrays.asList(new IllegalStateException("x"), new ArithmeticException("GP figure past a long"),
      new NullPointerException(), new ClassCastException(), new IndexOutOfBoundsException(), new NumberFormatException(),
      new IllegalArgumentException(), new UnsupportedOperationException(), new java.util.ConcurrentModificationException(),
      new java.io.UncheckedIOException(new IOException("x")), new com.google.gson.JsonParseException("x"), new IOException("undeclared checked"),
      new Exception("a plain Exception"));
    for (Exception e : absorbed) {
      int[] calls = {0};
      PickChain.SupportHook throwing = c -> {
        calls[0]++;
        check(c == cand, "the hook is handed the candidate itself");
        throw TierIntentTest.<RuntimeException>sneaky(e);
      };
      SellSupport.Result r = null;
      boolean escaped = false;
      try {
        r = PickChain.failOpen(throwing).supportFor(cand);
      } catch (Throwable t) {
        escaped = true;
      }
      check(!escaped && r == null && calls[0] == 1, "failOpen absorbs " + e.getClass().getName() + " as no reading");
    }
    List<Error> errors = Arrays.asList(new AssertionError("a test's own failure"), new StackOverflowError(), new OutOfMemoryError(),
      new Error("a plain Error"));
    for (Error e : errors) {
      Throwable thrown = null;
      try {
        PickChain.failOpen(c -> { throw e; }).supportFor(cand);
      } catch (Throwable t) {
        thrown = t;
      }
      check(thrown == e, "failOpen propagates " + e.getClass().getName() + ", the same instance");
    }
    // ...and when the hook answers, the answer passes through untouched: the same Result, or no reading.
    SellSupport.Result answer = new SellSupport.Result(true, "Set aside.", null);
    check(PickChain.failOpen(c -> answer).supportFor(cand) == answer, "a hook's answer passes through as the same object");
    check(PickChain.failOpen(c -> null).supportFor(cand) == null, "a hook's null passes through as no reading");
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> T sneaky(Throwable t) throws T {
    throw (T) t;
  }

  private static History.Flip flip(int itemId, double profit, double quantity, double lastSell) {
    return new History.Flip(false, itemId, profit, quantity, Double.NaN, lastSell, null, "Nature rune");
  }

  // No plugin source outside the engine package refers to it -- except the in-process engine (com.evi.live.inprocess), which
  // answers the sidebar (EviLiveInProcessWiringTest). 4.0.0: the developer shadow package is gone, so it has no exemption.
  static void nothingIsWiredIntoThePluginYet() throws IOException {
    String dir = System.getProperty("evi.mainSource");
    check(dir != null && !dir.isEmpty(), "evi.mainSource is not set");
    Path root = Paths.get(dir), engine = root.resolve(Paths.get("com", "evi", "live", "engine")),
      inprocess = root.resolve(Paths.get("com", "evi", "live", "inprocess"));
    List<String> users = new ArrayList<>();
    int scanned = 0;
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
        if (f.startsWith(engine) || f.startsWith(inprocess)) continue;
        scanned++;
        if (new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains("com.evi.live.engine")) users.add(root.relativize(f).toString());
      }
    }
    check(scanned >= 40, "too few plugin sources scanned (" + scanned + ")");
    check(users.isEmpty(), "the engine is referenced outside its package already: " + users);
  }
}
