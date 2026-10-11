package com.evi.live.engine;

import com.evi.live.TestFiles;
import com.evi.live.journal.Store;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.LatestPrices;
import com.evi.live.market.MarketAggregates;
import com.evi.live.market.WikiJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The market tier's traps as INTENT, beside the recorded vectors, so a parity diff and a broken rule read differently.
 * Every expectation is a literal the real JS gave on the same input (each was read off bridge/suggestions.mjs while this
 * test was written), chosen so the obvious wrong implementation gives a different one. Synthetic data only.
 */
public final class MarketTierIntentTest {
  private static int checks;
  private static final long T = 1791374400000L; // 7 Oct 2026 12:00 UTC
  private static final long S = T / 1000;

  public static void main(String[] args) throws Exception {
    rankOnTheSteadyPriceQuoteTheLiveOne();
    anItemMustClearBothViews();
    theMinimumIsJudgedOnTheMoreCautiousTotal();
    tiesKeepTheMappingsOrder();
    theSpreadRankIsClampedAndFloored();
    everyRankCarriesTheSpread();
    absentCashIsNotZeroCash();
    theSlowPaceNamesThePerWindowLimit();
    biggerPositionsNamesTheCapThatBound();
    theNoPaceSentenceIsTheBridgesOwn();
    theBondIsNeverBought();
    theRankingRuleIsTodaysAtEveryLevel();
    hugePricesStayExactAndPrintGrouped();
    aStaleSpreadIsDroppedOnlyAgainstAClock();
    biggerPositionsMovesTheFloorToTheWindow();
    theStackShareIsReadAsTheBridgeReadsIt();
    aDutchHostPrintsTheSameSentence();
    theLimitGateIsWhatFillsNowForThisAccountOnly();
    theSourceUsesTheJsArithmetic();
    System.out.println("PASS: market-tier intent, " + checks + " checks");
  }

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  // ------------------------------------------------------------------------------------------- builders

  private static List<ItemCatalog.Item> mapping(String json) throws IOException {
    return ItemCatalog.parse(json).items();
  }

  private static LatestPrices latest(String json) throws IOException {
    return WikiJson.latest("{\"data\":" + json + "}");
  }

  private static HourBucket hour(String json) throws IOException {
    return WikiJson.hour("{\"timestamp\":0,\"data\":" + json + "}");
  }

  private static String q(long high, long low) {
    return "{\"high\":" + high + ",\"low\":" + low + ",\"highTime\":" + S + ",\"lowTime\":" + S + "}";
  }

  private static MarketTier.Options opts() {
    MarketTier.Options o = new MarketTier.Options();
    o.now = T;
    return o;
  }

  private static String reasoning(Pick p) {
    return p == null ? null : p.reasoning;
  }

  private static final String TAIL = " Verify liquidity and news yourself before committing capital; this ranking has no track record behind it.";
  private static final String HEAD = "Market-wide pick -- you have no flip history for this item yet. Current margin after tax is ~";

  // ------------------------------------------------------------------------------------------- the rules

  // The Ape atoll tablet: one freak print (28,756 against a day of ~5,600) won the whole catalogue on 28 Sept.
  static void rankOnTheSteadyPriceQuoteTheLiveOne() throws IOException {
    List<ItemCatalog.Item> items = mapping("[{\"id\":19631,\"name\":\"Ape atoll teleport (tablet)\",\"limit\":10000},{\"id\":561,\"name\":\"Nature rune\",\"limit\":12000}]");
    LatestPrices l = latest("{\"19631\":" + q(28756, 5092) + ",\"561\":" + q(700, 600) + "}");
    HourBucket v = hour("{\"19631\":{\"highPriceVolume\":500,\"lowPriceVolume\":500},\"561\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000}}");
    MarketTier.Options o = opts();
    o.maxSpend = 50_000_000;
    o.targetDurationMinutes = 720;
    Pick live = MarketTier.computeMarketSuggestion(items, l, v, o);
    check(live != null && live.itemId == 19631 && live.quantity == 500, "without a steady reading the freak print wins (the pinned old behaviour)");
    Map<Integer, MarketAggregates.RobustPrice> steady = new HashMap<>();
    steady.put(19631, MarketAggregates.RobustPrice.of(5600, 5200));
    steady.put(561, MarketAggregates.RobustPrice.of(800, 600));
    o.rankPrices = steady;
    Pick p = MarketTier.computeMarketSuggestion(items, l, v, o);
    check(p != null && p.itemId == 561 && p.quantity == 5000, "ranked on the steady price the rune wins");
    check(p.buyPrice == 600 && p.sellPrice == 700, "and is QUOTED at the live price (600 -> 700), never the median's 800");
    check((HEAD + "430,000 gp at quantity 5000 (sized to this item's own GE buy limit of 12,000 per 4 hours, counted over the 3 four-hour windows your trade "
      + "spans (36,000 in all); capped to 100% of what this item trades in a typical hour, so the order isn't larger than the market absorbs over your 12-hour "
      + "trade window) (buy near 600 gp, sell near 700 gp)." + TAIL).equals(p.reasoning), "the reasoning quotes the LIVE margin: " + p.reasoning);
    Map<Integer, MarketAggregates.RobustPrice> partial = new HashMap<>();
    partial.put(561, MarketAggregates.RobustPrice.of(700, 600));
    o.rankPrices = partial;
    check(MarketTier.computeMarketSuggestion(items, l, v, o).itemId == 19631, "an item with no steady reading ranks on its live quote, as before the archive");
  }

  static void anItemMustClearBothViews() throws IOException {
    List<ItemCatalog.Item> items = mapping("[{\"id\":561,\"name\":\"Nature rune\",\"limit\":12000}]");
    HourBucket v = hour("{\"561\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000}}");
    MarketTier.Options o = opts();
    o.rankPrices = Collections.singletonMap(561, MarketAggregates.RobustPrice.of(700, 600));
    check(MarketTier.computeMarketSuggestion(items, latest("{\"561\":" + q(601, 600) + "}"), v, o) == null,
      "a closed live spread is not offered however good the steady price looks");
    o.rankPrices = Collections.singletonMap(561, MarketAggregates.RobustPrice.of(602, 600)); // steady net 2 - 12 tax < 0
    check(MarketTier.computeMarketSuggestion(items, latest("{\"561\":" + q(700, 600) + "}"), v, o) == null,
      "and a live spread the fortnight never supported is not either");
  }

  // The floor is the more cautious of the live total (26 x 18,000 = 468,000) and the steady one (6 x 18,000 = 108,000).
  static void theMinimumIsJudgedOnTheMoreCautiousTotal() throws IOException {
    List<ItemCatalog.Item> items = mapping("[{\"id\":561,\"name\":\"Nature rune\",\"limit\":18000}]");
    LatestPrices l = latest("{\"561\":" + q(230, 200) + "}");
    HourBucket v = hour("{\"561\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000}}");
    MarketTier.Options o = opts();
    o.maxVolumeShare = 0;
    o.rankPrices = Collections.singletonMap(561, MarketAggregates.RobustPrice.of(210, 200));
    o.minProfit = 200_000;
    check(MarketTier.computeMarketSuggestion(items, l, v, o) == null, "468,000 live but 108,000 steady does not reach 200,000");
    o.minProfit = 100_000;
    Pick p = MarketTier.computeMarketSuggestion(items, l, v, o);
    check(p != null && p.quantity == 18000 && (HEAD + "468,000 gp at quantity 18000 (sized to this item's own GE buy limit of 18,000 per 4 hours) (buy near 200 gp, "
      + "sell near 230 gp)." + TAIL).equals(p.reasoning), "108,000 reaches 100,000, and the sentence still quotes the live 468,000: " + reasoning(p));
  }

  private static final String RUNES = "[{\"id\":561,\"name\":\"Nature rune\",\"limit\":18000},{\"id\":565,\"name\":\"Blood rune\",\"limit\":18000},"
    + "{\"id\":554,\"name\":\"Fire rune\",\"limit\":18000}]";
  private static final String RUNES_REVERSED = "[{\"id\":554,\"name\":\"Fire rune\",\"limit\":18000},{\"id\":565,\"name\":\"Blood rune\",\"limit\":18000},"
    + "{\"id\":561,\"name\":\"Nature rune\",\"limit\":18000}]";

  private static Pick runes(String mapping, double spreadRank) throws IOException {
    MarketTier.Options o = opts();
    o.maxSpend = 20_000_000;
    o.targetDurationMinutes = 720;
    o.spreadRank = spreadRank;
    return MarketTier.computeMarketSuggestion(mapping(mapping), latest("{\"561\":" + q(230, 200) + ",\"565\":" + q(230, 200) + ",\"554\":" + q(230, 200) + "}"),
      hour("{\"561\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000},\"565\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000},"
        + "\"554\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000}}"), o);
  }

  // Three identical runes score identically; a stable sort keeps the MAPPING's order, which is what decides the spread.
  static void tiesKeepTheMappingsOrder() throws IOException {
    check(runes(RUNES, Double.NaN).itemId == 561, "a tie goes to the first in the mapping");
    check(runes(RUNES_REVERSED, Double.NaN).itemId == 554, "and follows the mapping when it is reversed");
    check(runes(RUNES, 1).itemId == 565 && runes(RUNES, 2).itemId == 554, "the spread rank walks the tie in mapping order");
  }

  static void theSpreadRankIsClampedAndFloored() throws IOException {
    check(runes(RUNES, 99).itemId == 554, "a rank past the end CLAMPS to the last candidate, never answers nothing");
    check(runes(RUNES, -1).itemId == 561 && runes(RUNES, -0.0).itemId == 561 && runes(RUNES, Double.NEGATIVE_INFINITY).itemId == 561,
      "below zero is the top pick");
    check(runes(RUNES, 1.9).itemId == 565, "a fraction is floored, not rounded");
    check(MarketTier.spreadIndex(Double.POSITIVE_INFINITY, 3) == 2 && MarketTier.spreadIndex(Double.NaN, 3) == 0 && MarketTier.spreadIndex(2, 1) == 0,
      "Infinity clamps, NaN is the top, one candidate is always the answer");
  }

  // The 5 Oct wiring gap, closed 8 Oct in the bridge first: marketRankAt carries the account's spread rank too.
  static void everyRankCarriesTheSpread() throws IOException {
    MarketTier.Options shared = opts();
    shared.targetDurationMinutes = 720;
    MarketTier.Ranking r = new MarketTier.Ranking(mapping(RUNES), latest("{\"561\":" + q(230, 200) + ",\"565\":" + q(230, 200) + ",\"554\":" + q(230, 200) + "}"),
      hour("{\"561\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000},\"565\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000},"
        + "\"554\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000}}"), shared, 20_000_000.0, 2);
    Set<Integer> none = new LinkedHashSet<>();
    check(r.rank(none, 500).itemId == 554, "the primary rank hands this account its spread rank (the third)");
    check(r.rankAt(none, 20_000_000.0, 500).itemId == 554, "marketRankAt carries the SAME spread rank: the step-down, the probe and the extras (8 Oct)");
    check(r.rankAt(new LinkedHashSet<>(Arrays.asList(554)), 20_000_000.0, 500).itemId == 565,
      "a further position (the main pick blocklisted) continues from the same offset, clamped to what is left");
    check(r.ranked(500).rank(new LinkedHashSet<>(Arrays.asList(554))).itemId == 565, "as a chain's tier the blocklist still applies before the spread");
    check(r.rankAt(none, 0.0, 500) == null && r.rankAt(none, null, 500).quantity == 5000, "its budget is its own: 0 affords nothing, absent caps nothing");
    check(r.rankAt(none, 20_000_000.0, 200_000) == null && r.rank(none, 200_000) == null, "and so is its minimum (130,000 does not reach 200,000)");
  }

  static void absentCashIsNotZeroCash() throws IOException {
    List<ItemCatalog.Item> items = mapping("[{\"id\":561,\"name\":\"Nature rune\",\"limit\":18000}]");
    LatestPrices l = latest("{\"561\":" + q(230, 200) + "}");
    HourBucket v = hour("{\"561\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000}}");
    MarketTier.Options o = opts();
    Pick unknown = MarketTier.computeMarketSuggestion(items, l, v, o);
    check(unknown != null && unknown.quantity == 500, "absent cash is UNKNOWN: no spend cap (500, the volume share's)");
    o.maxSpend = 0;
    check(MarketTier.computeMarketSuggestion(items, l, v, o) == null, "cash 0 is carrying nothing: afford nothing");
    o.maxSpend = 199;
    check(MarketTier.computeMarketSuggestion(items, l, v, o) == null, "199 gp cannot buy one 200 gp unit");
    o.maxSpend = 200;
    Pick one = MarketTier.computeMarketSuggestion(items, l, v, o);
    check(one != null && one.quantity == 1 && (HEAD + "26 gp at quantity 1 (sized to this item's own GE buy limit of 18,000 per 4 hours; capped to what your current "
      + "cash stack can afford) (buy near 200 gp, sell near 230 gp)." + TAIL).equals(one.reasoning), "200 gp buys exactly one: " + reasoning(one));
  }

  private static final String ETHER = "[{\"id\":21820,\"name\":\"Revenant ether\",\"limit\":300000}]";

  // 5 Oct: on Slow the sidebar told players Ancient essence's limit was 3,600,000 per 4 hours; it is 300,000.
  static void theSlowPaceNamesThePerWindowLimit() throws IOException {
    MarketTier.Options o = opts();
    o.targetDurationMinutes = 2880;
    Pick p = MarketTier.computeMarketSuggestion(mapping(ETHER), latest("{\"21820\":" + q(260, 230) + "}"),
      hour("{\"21820\":{\"highPriceVolume\":10000000,\"lowPriceVolume\":10000000}}"), o);
    check(p != null && p.quantity == 3_600_000 && (HEAD + "90,000,000 gp at quantity 3600000 (sized to this item's own GE buy limit of 300,000 per 4 hours, counted "
      + "over the 12 four-hour windows your trade spans (3,600,000 in all)) (buy near 230 gp, sell near 260 gp)." + TAIL).equals(p.reasoning),
      "the per-4h limit is the item's own, the 12 windows and their total beside it: " + reasoning(p));
  }

  // 7 Oct (fixed in the JS first): under "Bigger positions" the WINDOW share bound, so it is the one named.
  static void biggerPositionsNamesTheCapThatBound() throws IOException {
    HourBucket v = hour("{\"21820\":{\"highPriceVolume\":20000,\"lowPriceVolume\":9000}}");
    MarketTier.Options o = opts();
    o.targetDurationMinutes = 2880;
    o.volumeWindowShare = Policy.BIGGER_POSITIONS_WINDOW_SHARE;
    o.minVolumeInWindow = Policy.BIGGER_POSITIONS_MIN_VOLUME_IN_WINDOW;
    Pick bigger = MarketTier.computeMarketSuggestion(mapping(ETHER), latest("{\"21820\":" + q(260, 230) + "}"), v, o);
    check(bigger != null && bigger.quantity == 43_200 && bigger.reasoning.contains("; capped to fit an estimated ~2880-minute trade; capped to 10% of what this "
      + "item trades over your whole trade window, so the order isn't larger than the market absorbs) (buy near"), "Bigger positions names the window share: " + reasoning(bigger));
    o.volumeWindowShare = Double.NaN;
    o.minVolumeInWindow = Double.NaN;
    Pick hourly = MarketTier.computeMarketSuggestion(mapping(ETHER), latest("{\"21820\":" + q(260, 230) + "}"), v, o);
    check(hourly != null && hourly.quantity == 18_000 && hourly.reasoning.contains("capped to 200% of what this item trades in a typical hour, so the order isn't "
      + "larger than the market absorbs over your 48-hour trade window)"), "the ladder names the per-hour share and the window: " + reasoning(hourly));
  }

  // An explicit per-hour share above 10% with no pace prints "undefined-minute" in the bridge; kept until the JS changes it.
  static void theNoPaceSentenceIsTheBridgesOwn() throws IOException {
    MarketTier.Options o = opts();
    o.maxVolumeShare = 0.5;
    Pick p = MarketTier.computeMarketSuggestion(mapping(ETHER), latest("{\"21820\":" + q(260, 230) + "}"),
      hour("{\"21820\":{\"highPriceVolume\":20000,\"lowPriceVolume\":9000}}"), o);
    check(p != null && p.quantity == 4500 && p.reasoning.contains("capped to 50% of what this item trades in a typical hour, so the order isn't larger than the "
      + "market absorbs over your undefined-minute trade window"), "the no-pace wording is the bridge's: " + reasoning(p));
  }

  static void theBondIsNeverBought() throws IOException {
    check(MarketTier.computeMarketSuggestion(mapping("[{\"id\":13190,\"name\":\"Old school bond\",\"limit\":100}]"), latest("{\"13190\":" + q(12_000_000, 11_000_000) + "}"),
      hour("{\"13190\":{\"highPriceVolume\":500,\"lowPriceVolume\":500}}"), opts()) == null, "a tax-free 1m spread on the bond is still never offered");
  }

  // 4 Oct: BIGGER_POSITIONS' 'profit' ranking was a shipped defect; the seam stays empty at every level.
  static void theRankingRuleIsTodaysAtEveryLevel() throws IOException {
    for (LevelSettings level : new LevelSettings[]{LevelSettings.LOW, LevelSettings.MEDIUM, LevelSettings.HIGH})
      check(level.marketRankBy == null, "the market ranking must be today's at every level");
    List<ItemCatalog.Item> items = mapping("[{\"id\":561,\"name\":\"Nature rune\",\"limit\":18000},{\"id\":4151,\"name\":\"Abyssal whip\",\"limit\":70}]");
    LatestPrices l = latest("{\"561\":" + q(230, 200) + ",\"4151\":" + q(1_600_000, 1_500_000) + "}");
    HourBucket v = hour("{\"561\":{\"highPriceVolume\":100000,\"lowPriceVolume\":100000},\"4151\":{\"highPriceVolume\":40,\"lowPriceVolume\":40}}");
    MarketTier.Options o = opts();
    o.maxSpend = 100_000_000;
    o.targetDurationMinutes = 720;
    o.rankBy = LevelSettings.LOW.marketRankBy;
    Pick today = MarketTier.computeMarketSuggestion(items, l, v, o);
    check(today.itemId == 561 && today.quantity == 54_000, "today's score: the liquidity multiplier keeps the deep rune ahead");
    for (String rule : new String[]{"profit", "profit-per-hour", "soft-liquidity"}) {
      o.rankBy = rule;
      Pick other = MarketTier.computeMarketSuggestion(items, l, v, o);
      check(other.itemId == 4151 && other.quantity == 40, "'" + rule + "' would pick the whip -- which is why no level passes one");
    }
  }

  // Past 2^31: a 3bn item, its tax capped at 5m a unit, 95m net x 8 = 760m, printed grouped.
  static void hugePricesStayExactAndPrintGrouped() throws IOException {
    Pick p = MarketTier.computeMarketSuggestion(mapping("[{\"id\":22325,\"name\":\"Scythe of vitur\",\"limit\":8}]"),
      latest("{\"22325\":" + q(3_100_000_000L, 3_000_000_000L) + "}"), hour("{\"22325\":{\"highPriceVolume\":100,\"lowPriceVolume\":100}}"), opts());
    check(p != null && p.buyPrice == 3_000_000_000L && p.sellPrice == 3_100_000_000L && p.quantity == 8, "a price past 2^31 is carried exactly");
    check((HEAD + "760,000,000 gp at quantity 8 (sized to this item's own GE buy limit of 8 per 4 hours) (buy near 3,000,000,000 gp, sell near 3,100,000,000 gp)."
      + TAIL).equals(p.reasoning), "the tax cap and the grouping: " + p.reasoning);
  }

  static void aStaleSpreadIsDroppedOnlyAgainstAClock() throws IOException {
    List<ItemCatalog.Item> items = mapping("[{\"id\":561,\"name\":\"Nature rune\",\"limit\":18000}]");
    LatestPrices old = latest("{\"561\":{\"high\":230,\"low\":200,\"highTime\":" + (S - 7200) + ",\"lowTime\":" + (S - 7200) + "}}");
    HourBucket v = hour("{\"561\":{\"highPriceVolume\":5000,\"lowPriceVolume\":5000}}");
    check(MarketTier.computeMarketSuggestion(items, old, v, opts()) == null, "two hours since either side traded: no trustworthy spread");
    MarketTier.Options o = opts();
    o.maxPriceAgeMinutes = 180;
    check(MarketTier.computeMarketSuggestion(items, old, v, o) != null, "unless the caller allows that age");
    o = opts();
    o.now = Double.NaN;
    check(MarketTier.computeMarketSuggestion(items, old, v, o) != null, "an age against no clock is no age: fail open");
    check(MarketTier.computeMarketSuggestion(items, latest("{\"561\":{\"high\":230,\"low\":200,\"highTime\":" + (S - 7200) + "}}"), v, opts()) != null,
      "one side's time unknown: fail open");
  }

  // The market tier's own floor: 5 an hour, or under Bigger positions 24 over the WINDOW (3 an hour for 48 hours is 144).
  static void biggerPositionsMovesTheFloorToTheWindow() throws IOException {
    List<ItemCatalog.Item> items = mapping("[{\"id\":561,\"name\":\"Nature rune\",\"limit\":18000}]");
    LatestPrices l = latest("{\"561\":" + q(230, 200) + "}");
    HourBucket thin = hour("{\"561\":{\"highPriceVolume\":3,\"lowPriceVolume\":3}}");
    MarketTier.Options o = opts();
    o.targetDurationMinutes = 2880;
    check(MarketTier.computeMarketSuggestion(items, l, thin, o) == null, "3 an hour is under the hourly floor of 5");
    o.volumeWindowShare = 0.1;
    o.minVolumeInWindow = 24;
    Pick p = MarketTier.computeMarketSuggestion(items, l, thin, o);
    check(p != null && p.quantity == 14, "144 over the window clears 24, sized at 10% of the window: " + (p == null ? null : p.quantity));
    o.minHourlyVolume = 100;
    check(MarketTier.computeMarketSuggestion(items, l, thin, o) != null, "and the per-window floor REPLACES the hourly one, raised or not");
  }

  static void theStackShareIsReadAsTheBridgeReadsIt() {
    check(Double.isNaN(QueryValues.maxStackShare(null)) && Double.isNaN(QueryValues.maxStackShare("")) && Double.isNaN(QueryValues.maxStackShare("0")),
      "absent, empty and 0 are no share cap");
    check(QueryValues.maxStackShare("25") == 0.25 && QueryValues.maxStackShare("100") == 1 && Double.isNaN(QueryValues.maxStackShare("101")),
      "a percentage in (0, 100]");
    check(QueryValues.maxStackShare("0x19") == 0.25 && QueryValues.maxStackShare(" 1e1 ") == 0.1 && Double.isNaN(QueryValues.maxStackShare("abc"))
      && Double.isNaN(QueryValues.maxStackShare("-1")), "through Number(), as the bridge reads it");
  }

  static void aDutchHostPrintsTheSameSentence() throws IOException {
    Locale before = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("nl", "NL"));
      MarketTier.Options o = opts();
      o.targetDurationMinutes = 2880;
      Pick p = MarketTier.computeMarketSuggestion(mapping(ETHER), latest("{\"21820\":" + q(260, 230) + "}"),
        hour("{\"21820\":{\"highPriceVolume\":10000000,\"lowPriceVolume\":10000000}}"), o);
      check(p.reasoning.contains("~90,000,000 gp") && p.reasoning.contains("(3,600,000 in all)") && p.reasoning.contains("of 300,000 per 4 hours"),
        "en-US grouping on a Dutch host: " + p.reasoning);
    } finally {
      Locale.setDefault(before);
    }
  }

  // server.mjs's limitFor (27 Sept, 172,266 blood runes on a 2-day pace): the clip is what can FILL NOW, one window's
  // remainder for THIS account -- never the allowance across every window the pace spans, and never another account's buys.
  static void theLimitGateIsWhatFillsNowForThisAccountOnly() throws IOException {
    Store store = new Store(r -> { });
    long now = T, t0 = T - 3_600_000;
    store.ingest(packet("acct-a", "sa", 1, t0, "{\"slot\":0,\"itemId\":561,\"total\":10000,\"filled\":4000,\"price\":200,\"spent\":800000,"
      + "\"offerId\":\"a1\",\"state\":\"BUYING\",\"name\":\"Nature rune\",\"knownStart\":true,\"ticksToFill\":5}"), t0);
    ItemCatalog catalog = ItemCatalog.parse("[{\"id\":561,\"name\":\"Nature rune\",\"limit\":18000,\"members\":false},"
      + "{\"id\":4151,\"name\":\"Abyssal whip\",\"limit\":70,\"members\":true},{\"id\":2,\"name\":\"No limit\"}]");
    AccountView a = AccountView.of(store.state(now), store.offers(), "acct-a", now);
    Sizing.ItemGate gate = RequestGates.of(catalog, false, "any", a, 2880.0, now);
    check(gate.limitRemaining(561) == 14_000, "4,000 of 18,000 bought this window: 14,000 can fill now (not the 2-day allowance)");
    check(gate.limitRemaining(2) == null, "an unknown limit constrains nothing");
    check(RequestGates.of(catalog, false, "any", AccountView.of(store.state(now), store.offers(), "acct-b", now), 2880.0, now).limitRemaining(561) == 18_000,
      "another account's buys never count");
    check(RequestGates.of(catalog, false, "any", AccountView.nobody(), 2880.0, now) == Sizing.ItemGate.NONE, "no account, no focus, a members world: no gate at all");
    Sizing.ItemGate f2p = RequestGates.of(catalog, true, "any", AccountView.nobody(), null, now);
    check(f2p.membersBlocked(4151) && !f2p.membersBlocked(561) && f2p.limitRemaining(561) == null, "a free world blocks members items; no account, no limit");
    Sizing.ItemGate bulk = RequestGates.of(catalog, false, "bulk", AccountView.nobody(), null, now);
    check(!bulk.focusBlocked(561) && bulk.focusBlocked(4151) && bulk.focusBlocked(2), "bulk keeps limits of 1,000 and up; an unknown limit is neither");
    MarketTier.Options o = opts();
    o.targetDurationMinutes = 2880;
    o.maxVolumeShare = 0;
    o.gate = gate;
    Pick p = MarketTier.computeMarketSuggestion(catalog.items(), latest("{\"561\":" + q(230, 200) + "}"), hour("{\"561\":{\"highPriceVolume\":500000,"
      + "\"lowPriceVolume\":500000}}"), o);
    check(p != null && p.quantity == 14_000 && p.reasoning.contains("capped to what EVI has seen left of this item's GE buy limit, which is all that can fill "
      + "before the limit resets in 4 hours"), "the market pick is clipped to it and says so: " + reasoning(p));
  }

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
        : "{\"slot\":" + slot + ",\"itemId\":0,\"total\":0,\"filled\":0,\"price\":0,\"spent\":0,\"offerId\":\"" + session + "-e" + slot
          + "\",\"state\":\"EMPTY\",\"name\":\"\",\"knownStart\":false,\"ticksToFill\":-1}";
      a.add(new JsonParser().parse(o));
    }
    p.add("offers", a);
    return p;
  }

  // What the vectors cannot always see: Math.log and StrictMath.log agree on most inputs and differ in the last place on a
  // few, and a host-locale format agrees on an English machine.
  static void theSourceUsesTheJsArithmetic() throws IOException {
    String dir = System.getProperty("evi.mainSource");
    check(dir != null && !dir.isEmpty(), "evi.mainSource is not set");
    String src = TestFiles.text(TestFiles.at(TestFiles.rooted(dir), "com", "evi", "live", "engine", "MarketTier.java"));
    check(src.contains("StrictMath.log(liquidity + 1)") && !Pattern.compile("(?<!Strict)Math\\.log\\(").matcher(src).find(),
      "the score's log must be StrictMath.log (fdlibm, as V8's)");
    check(!src.contains("String.format") && !src.contains("Math.rint") && !src.contains("HashMap") && !src.contains("getDefault()"),
      "no host-locale formatting, no rint, no hash-ordered iteration in the market tier");
  }
}
