package com.evi.live.engine;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.market.HourBucket;
import com.evi.live.market.LatestPrices;
import com.evi.live.market.MarketAggregates;
import com.evi.live.market.WikiJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The patient price on a holding below break-even (Patience, MarketAggregates.patienceBasis, the relist guard) as INTENT,
 * beside the transcripts: every expectation is a literal the JS gave on the same input (tests/patience.test.mjs). It
 * re-prices a HOLDING sold at a loss at a long pace, and touches nothing else. Synthetic data only.
 */
public final class PatienceIntentTest {
  private static int checks;
  private static final long H = 3600;
  private static final long END = 2_000_000L * H;

  public static void main(String[] args) throws Exception {
    theConstantsAreTheMeasuredOnes();
    theBasisIsTheTrueMedianFloored();
    theBasisReadsOnlyTheNewest24Hours();
    theBasisFailsOpen();
    thePriceNeedsAPaceAndAGap();
    applyMatchesTheJsExactly();
    applyStillUnderBreakEvenSaysTheSmallerLoss();
    applyLeavesEverythingElseAlone();
    theVerdictAddsAThirdNeutralLine();
    theHeadlineIsTheNetAtThePatientPrice();
    relistStaysQuietForAPatientAsk();
    // the plugin's OWN build (streamed from its per-hour archive files) gives what the list port gives
    checks += com.evi.live.market.PatienceBuildCheck.run();
    System.out.println("PASS: patience intent, " + checks + " checks");
  }

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  /** One item's hourly rows ending at {@code end}: highs cycled from {@code highs}, {@code vol} units each side (the JS rows()). */
  static List<HourBucket> rows(int id, long[] highs, int hours, long vol, long end) throws Exception {
    List<HourBucket> out = new ArrayList<>();
    for (int k = hours - 1; k >= 0; k--) {
      long hi = highs[k % highs.length];
      out.add(hour(end - k * H, id, hi, vol, Math.round(hi * 0.97), vol));
    }
    return out;
  }

  static HourBucket hour(long ts, int id, long high, long hv, long low, long lv) throws Exception {
    return WikiJson.hour("{\"timestamp\":" + ts + ",\"data\":{\"" + id + "\":{\"avgHighPrice\":" + high + ",\"highPriceVolume\":" + hv
      + ",\"avgLowPrice\":" + low + ",\"lowPriceVolume\":" + lv + "}}}");
  }

  static void theConstantsAreTheMeasuredOnes() {
    check(MarketAggregates.PATIENCE_HOURS == 24 && MarketAggregates.MIN_PATIENCE_HOURS == 12 && MarketAggregates.PATIENCE_MIN_UNITS == 120
      && MarketAggregates.PATIENCE_PRICE_FLOOR == 1000, "24 hours, 12 priced, 120 units, 1,000 gp");
    check(Patience.PATIENCE_MIN_PACE == 720 && Patience.PATIENCE_MIN_GAP_PER_MILLE == 1005, "720 minutes, 1005 per mille");
  }

  static void theBasisIsTheTrueMedianFloored() throws Exception {
    long[] highs = new long[24];
    for (int k = 0; k < 24; k++) highs[k] = 1000 + ((k * 7) % 24);
    MarketAggregates.PatienceBasis b = MarketAggregates.patienceBasis(rows(1333, highs, 24, 10, END), 24).get(1333);
    check(b != null && b.typicalHigh == 1011 && b.hours == 24, "even count: (1011 + 1012) / 2 floored = 1011 (the upper middle would be 1012): " + b);
    List<HourBucket> odd = rows(1333, new long[]{5000, 5100, 5200, 5300, 5400, 5500, 5600, 5700, 5800, 5900, 6000, 6100, 6200}, 13, 20, END);
    for (int k = 0; k < 11; k++) odd.add(hour(END - (13 + k) * H, 1333, 9999, 0, 5000, 20));
    odd.sort((x, y) -> Long.compare(x.ts, y.ts));
    b = MarketAggregates.patienceBasis(odd, 24).get(1333);
    check(b != null && b.typicalHigh == 5600 && b.hours == 13, "odd count: the middle value, hours with no buyers left out: " + b);
  }

  static void theBasisReadsOnlyTheNewest24Hours() throws Exception {
    List<HourBucket> all = new ArrayList<>(rows(1333, new long[]{90000}, 48, 10, END - 30 * H));
    all.addAll(rows(1333, new long[]{20000}, 24, 10, END));
    MarketAggregates.PatienceBasis b = MarketAggregates.patienceBasis(all, 24).get(1333);
    check(b != null && b.typicalHigh == 20000 && b.hours == 24, "the older, dearer hours never reach it: " + b);
    // patienceBasisAt reads 48 hours back from now, then keeps the newest 24 STORED hours.
    Map<Integer, MarketAggregates.PatienceBasis> at = MarketAggregates.patienceBasisAt(all, (END + 2 * H) * 1000);
    check(at.get(1333) != null && at.get(1333).typicalHigh == 20000, "patienceBasisAt: the same reading two hours after the newest");
    check(MarketAggregates.patienceBasisAt(all, (END + 49 * H) * 1000).isEmpty(), "nothing stored in the last 48 hours: no reading");
  }

  static void theBasisFailsOpen() throws Exception {
    check(MarketAggregates.patienceBasis(Collections.emptyList(), 24).isEmpty(), "nothing");
    check(MarketAggregates.patienceBasis(null, 24).isEmpty(), "null");
    check(MarketAggregates.patienceBasis(rows(1333, new long[]{20000}, 11, 100, END), 24).get(1333) == null, "11 priced hours");
    check(MarketAggregates.patienceBasis(rows(1333, new long[]{20000}, 12, 100, END), 24).get(1333) != null, "exactly 12 speak");
    check(MarketAggregates.patienceBasis(rows(1333, new long[]{20000}, 24, 2, END), 24).get(1333) == null, "96 units < 120");
    check(MarketAggregates.patienceBasis(rows(1333, new long[]{999}, 24, 10, END), 24).get(1333) == null, "under the price floor");
  }

  static void thePriceNeedsAPaceAndAGap() {
    MarketAggregates.PatienceBasis basis = new MarketAggregates.PatienceBasis(289858, 24);
    Patience.Price p = Patience.price(basis, 280147, 720.0);
    check(p != null && p.price == 289858 && p.typicalHigh == 289858 && p.quickPrice == 280147 && p.hours == 24, "the Antler guard case");
    check(Patience.price(basis, 280147, 2880.0) != null, "Slow");
    check(Patience.price(basis, 280147, 719.0) == null, "under 12 hours");
    check(Patience.price(basis, 280147, null) == null, "no pace chosen");
    check(Patience.price(basis, 289858, 720.0) == null, "already at the typical high");
    check(Patience.price(new MarketAggregates.PatienceBasis(10050, 24), 10000, 720.0) == null, "exactly 0.5% is not more than 0.5%");
    check(Patience.price(new MarketAggregates.PatienceBasis(10051, 24), 10000, 720.0) != null, "just over");
    check(Patience.price(null, 280147, 720.0) == null && Patience.price(basis, 0, 720.0) == null, "no basis, no quote");
    check(Patience.price(basis, Double.NaN, 720.0) == null, "an unpriced item (NaN, as the JS undefined)");
  }

  static LatestPrices antler() throws Exception {
    return WikiJson.latest("{\"data\":{\"31081\":{\"high\":280147,\"highTime\":1,\"low\":261343,\"lowTime\":1}}}");
  }

  static Pick holding(double paid) throws Exception {
    return HoldingTiers.computeHoldingSuggestion(antler(), 31081, 14, "Antler guard", "b1", paid);
  }

  static Map<Integer, MarketAggregates.PatienceBasis> basis(long typicalHigh) {
    Map<Integer, MarketAggregates.PatienceBasis> m = new LinkedHashMap<>();
    m.put(31081, new MarketAggregates.PatienceBasis(typicalHigh, 24));
    return m;
  }

  static final String TAIL = " In EVI's market test, holdings under break-even listed at the day's typical high usually sold there within the hour "
    + "and did better than selling at the last price. Not a promise: if it has not sold by the end of your pace, the market price is the one to judge by.";

  static void applyMatchesTheJsExactly() throws Exception {
    Pick s = holding(280000);
    check(s.sellPrice == 280147 && s.breakEvenPrice == 285714 && s.lossIfSoldNow == 76370 && s.netIfSoldNow == -76370, "the JS holding exactly");
    String before = s.reasoning;
    Patience.apply(s, basis(289858), 720.0);
    check(s.patience != null && s.patience.price == 289858 && s.patience.quickPrice == 280147 && s.patience.typicalHigh == 289858
      && s.patience.net == 56854, "patience {289858, 280147, 289858, 56854}");
    check(s.sellPrice == 289858, "the GE price key fills sellPrice");
    check(s.lossIfSoldNow == 76370 && s.breakEvenPrice == 285714 && s.netIfSoldNow == -76370, "the quick-sale figures stay");
    check(s.reasoning.equals(before + " Patient price: 289,858 gp, the typical high of the last 24 hours (which clears your break-even)." + TAIL),
      "the JS sentence exactly: " + s.reasoning);
  }

  static void applyStillUnderBreakEvenSaysTheSmallerLoss() throws Exception {
    Pick s = holding(280000);
    Patience.apply(s, basis(284000), 720.0);
    check(s.patience.net == -23520 && s.sellPrice == 284000, "net -23,520 at 284,000");
    check(s.reasoning.endsWith(" Patient price: 284,000 gp, the typical high of the last 24 hours (still under your break-even, a loss of about 23,520 gp instead of 76,370)." + TAIL),
      "the smaller loss, not 'clears': " + s.reasoning);
  }

  static String shape(Pick s) {
    return s.sellPrice + "|" + s.reasoning + "|" + (s.patience == null);
  }

  static void applyLeavesEverythingElseAlone() throws Exception {
    String plain = shape(holding(280000));
    Pick s;
    s = holding(280000); Patience.apply(s, basis(289858), 719.0); check(shape(s).equals(plain), "pace too short");
    s = holding(280000); Patience.apply(s, basis(289858), null); check(shape(s).equals(plain), "no pace");
    s = holding(280000); Patience.apply(s, new LinkedHashMap<>(), 720.0); check(shape(s).equals(plain), "no basis for the item");
    s = holding(280000); Patience.apply(s, null, 720.0); check(shape(s).equals(plain), "no basis built");
    s = holding(250000); String gain = shape(s); Patience.apply(s, basis(289858), 720.0); check(shape(s).equals(gain), "a gain now");
    s = holding(Double.NaN); String unknown = shape(s); Patience.apply(s, basis(289858), 720.0); check(shape(s).equals(unknown), "no cost basis");
    s = HoldingTiers.computeHoldingSuggestion(antler(), 31081, 14, "Antler guard", "b1", 280000, new FifoMatcher.EndedUnseenMark("s", 300000, 1, 14));
    String gone = shape(s); Patience.apply(s, basis(289858), 720.0); check(shape(s).equals(gone), "a listing that ended unseen");
    Pick buy = new Pick(31081, "Antler guard", "buy", 1, 260000, 280147, "market");
    buy.lossIfSoldNow = 5L; buy.breakEvenPrice = 1L; buy.netIfSoldNow = -5.0;
    Patience.apply(buy, basis(289858), 720.0);
    check(buy.sellPrice == 280147 && buy.patience == null, "a buy is never re-priced");
    Patience.apply(null, basis(289858), 720.0);
    check(true, "null is fine");
  }

  static void theVerdictAddsAThirdNeutralLine() throws Exception {
    Verdict.Result plain = Verdict.suggestionVerdict(holding(280000));
    check(plain.level.equals(Verdict.WARN) && plain.checks.size() == 2, "the loss card has two lines");
    Pick s = holding(280000);
    Patience.apply(s, basis(289858), 720.0);
    Verdict.Result v = Verdict.suggestionVerdict(s);
    check(v.level.equals(Verdict.WARN) && v.label.equals("Selling now is a loss") && v.checks.size() == 3, "still WARN, three lines");
    check(v.checks.get(0).text.equals(plain.checks.get(0).text) && v.checks.get(1).text.equals(plain.checks.get(1).text), "the first two unchanged");
    check(v.checks.get(2).ok == null && v.checks.get(2).text.equals("Patient price: 289,858 (24h typical)"), "the third: " + v.checks.get(2).text);
    check(Patience.line(new Patience.Reading(1_234_567_890L, 1, 1, 1)).length() <= 46, "fits the 225px panel");
  }

  static void theHeadlineIsTheNetAtThePatientPrice() throws Exception {
    Pick s = holding(280000);
    Patience.apply(s, basis(289858), 720.0);
    Verdict.enrich(s, null, null);
    check(s.expectedProfit != null && s.expectedProfit == 56854, "the headline is the net at P over what was paid: " + s.expectedProfit);
    Pick t = holding(280000);
    Verdict.enrich(t, null, null);
    check(t.expectedProfit != null && t.expectedProfit != 56854, "without the reading the old headline: " + t.expectedProfit);
  }

  static void relistStaysQuietForAPatientAsk() {
    double now = 1_792_756_800_000.0;
    Map<Integer, AdviceNote.Price> prices = new LinkedHashMap<>();
    prices.put(31081, AdviceNote.Price.of(new ItemPrice(31081, 261343, 280147, null, null, null)));
    Map<Integer, Double> cost = new LinkedHashMap<>();
    cost.put(31081, 280000.0);
    Map<Integer, Long> patience = new LinkedHashMap<>();
    patience.put(31081, 289858L);
    java.util.function.BiFunction<Double, Double, Relist.SellOffer> offer = (price, minutes) ->
      new Relist.SellOffer(31081, "Antler guard", price, 14, now - minutes * 60000);
    check(Relist.relistAdvice(List.of(offer.apply(289858.0, 20.0)), prices, cost, 720, now).size() == 1, "without it the early path speaks (unchanged)");
    check(Relist.relistAdvice(List.of(offer.apply(289858.0, 20.0)), prices, cost, 720, now, patience).isEmpty(), "at the patient price: quiet early");
    check(Relist.relistAdvice(List.of(offer.apply(285000.0, 20.0)), prices, cost, 720, now, patience).isEmpty(), "under it: quiet early");
    check(Relist.relistAdvice(List.of(offer.apply(295000.0, 20.0)), prices, cost, 720, now, patience).size() == 1, "above it: speaks");
    List<AdviceNote> late = Relist.relistAdvice(List.of(offer.apply(289858.0, 181.0)), prices, cost, 720, now, patience);
    check(late.size() == 1 && "Not selling".equals(late.get(0).label), "past a quarter of the pace the ordinary clock speaks");
    Map<Integer, AdviceNote.Price> above = new LinkedHashMap<>();
    above.put(31081, AdviceNote.Price.of(new ItemPrice(31081, 261343, 290000, null, null, null)));
    Map<Integer, Long> higher = new LinkedHashMap<>();
    higher.put(31081, 300000L);
    check(Relist.relistAdvice(List.of(offer.apply(296000.0, 20.0)), above, cost, 720, now, higher).size() == 1,
      "above break-even the patient price plays no part");
  }
}
