package com.evi.live.engine;

import com.evi.live.market.HourBucket;
import com.evi.live.market.MarketAggregates;
import com.evi.live.market.WikiJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The stretched-price warning (Stretch, MarketAggregates.stretchBasis) as INTENT, beside the transcripts: every expectation
 * is a literal the JS gave on the same input (tests/stretch.test.mjs). A warning, never a block: it attaches a reading, a
 * sentence and a first verdict line to a BUY, and touches nothing else. Synthetic data only.
 */
public final class StretchIntentTest {
  private static int checks;
  private static final long H = 3600;

  public static void main(String[] args) throws Exception {
    theConstantsAreTheMeasuredOnes();
    theBasisIsTheUpperMedianAndAPositiveMove();
    theBasisMatchesTheJsExactly();
    theBasisFailsOpen();
    theBasisReadsOnlyBehindTheNewestHour();
    onlyAStretchAboveTheLevelSpeaks();
    theWordsAreTheBridgesOwn();
    theVerdictLineComesFirstAndTheLabelOnlyFillsAnEmptyOne();
    applyTouchesABuyOnly();
    // the plugin's OWN build (streamed from its per-hour archive files) gives what the list port gives
    checks += com.evi.live.market.StretchBuildCheck.run();
    System.out.println("PASS: stretch intent, " + checks + " checks");
  }

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  /** One item's hourly lows: a level with a 37-hour wobble (the JS test's series()). */
  static List<HourBucket> series(int id, double level, int hours, double amp, long endTs) {
    List<HourBucket> out = new ArrayList<>();
    for (int k = hours - 1; k >= 0; k--) {
      long ts = endTs - k * H;
      long low = Math.round(level * (1 + amp * Math.sin(2 * Math.PI * ((double) ts / H) / 37)));
      long high = Math.round(low * 1.1);
      try {
        out.add(WikiJson.hour("{\"timestamp\":" + ts + ",\"data\":{\"" + id + "\":{\"avgHighPrice\":" + high
          + ",\"highPriceVolume\":100,\"avgLowPrice\":" + low + ",\"lowPriceVolume\":100}}}"));
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return out;
  }

  static final long END = 2_000_000L * H;

  static void theConstantsAreTheMeasuredOnes() {
    check(Stretch.STRETCH_Z == 2 && Stretch.STRETCH_PRICE_FLOOR == 1000, "z 2, floor 1,000");
    check(MarketAggregates.STRETCH_HOURS == 336 && MarketAggregates.MIN_STRETCH_HOURS == 48 && MarketAggregates.MIN_STRETCH_PAIRS == 48,
      "336 hours, 48 lows, 48 pairs");
  }

  static void theBasisIsTheUpperMedianAndAPositiveMove() {
    MarketAggregates.StretchBasis b = MarketAggregates.stretchBasis(series(1333, 15000, 336, 0.01, END), 336).get(1333);
    check(b != null, "a full two weeks gives a basis");
    check(Math.abs(b.level - 15000) <= 150, "level near 15,000: " + b.level);
    check(b.move > 0 && b.move < 0.03, "move " + b.move);
  }

  /**
   * The exact values the JS gave for an irregular 80-hour series (low = 15,000 + ((k * 7919) % 97) * 3, k hours before the
   * newest): 80 lows and 56 day-apart moves, both even, and both pairs of middle values differ -- so an off-by-one median
   * (the lower middle: level 15,144, move 0.006556097785152541) cannot pass. The move is compared EXACTLY: StrictMath.log is
   * fdlibm, as V8's Math.log is.
   */
  static void theBasisMatchesTheJsExactly() throws Exception {
    List<HourBucket> b = new ArrayList<>();
    for (int k = 79; k >= 0; k--) {
      long ts = END - k * H, low = 15000 + ((k * 7919L) % 97) * 3;
      b.add(WikiJson.hour("{\"timestamp\":" + ts + ",\"data\":{\"1333\":{\"avgHighPrice\":" + Math.round(low * 1.1)
        + ",\"highPriceVolume\":100,\"avgLowPrice\":" + low + ",\"lowPriceVolume\":100}}}"));
    }
    MarketAggregates.StretchBasis s = MarketAggregates.stretchBasis(b, 336).get(1333);
    check(s != null && s.level == 15147, "the upper median of the lows (JS 15147): " + s);
    check(s.move == 0.0065587038250132165, "the upper median of the moves (JS 0.0065587038250132165): " + s.move);
  }

  static void theBasisFailsOpen() {
    check(MarketAggregates.stretchBasis(Collections.emptyList(), 336).isEmpty(), "nothing: empty");
    check(MarketAggregates.stretchBasis(null, 336).isEmpty(), "null: empty, never null");
    check(!MarketAggregates.stretchBasis(series(1333, 15000, 47, 0.01, END), 336).containsKey(1333), "47 lows: none");
    check(!MarketAggregates.stretchBasis(series(1333, 15000, 60, 0.01, END), 336).containsKey(1333), "36 pairs: none");
    check(!MarketAggregates.stretchBasis(series(1333, 15000, 336, 0, END), 336).containsKey(1333), "a move of 0: none");
  }

  static void theBasisReadsOnlyBehindTheNewestHour() {
    List<HourBucket> all = new ArrayList<>(series(1333, 50000, 200, 0.01, 1_000_000L * H));
    all.addAll(series(1333, 15000, 336, 0.01, END));
    MarketAggregates.StretchBasis b = MarketAggregates.stretchBasis(all, 336).get(1333);
    check(Math.abs(b.level - 15000) <= 150, "old prices must not reach the level: " + b.level);
  }

  static void onlyAStretchAboveTheLevelSpeaks() {
    MarketAggregates.StretchBasis basis = new MarketAggregates.StretchBasis(10000, 0.02);
    check(Stretch.of(basis, 10000) == null, "at the level: silent");
    check(Stretch.of(basis, 8000) == null, "a dip: silent");
    check(Stretch.of(basis, (long) Math.floor(10000 * Math.exp(0.0399))) == null, "just under z 2: silent");
    Stretch.Reading r = Stretch.of(basis, (long) Math.ceil(10000 * Math.exp(0.04)));
    check(r != null && r.level == 10000 && r.abovePct == 4, "at z 2: 4%");
    r = Stretch.of(basis, 15500);
    check(r != null && r.abovePct == 55, "55%");
    check(Stretch.of(null, 15500) == null, "no basis");
    check(Stretch.of(new MarketAggregates.StretchBasis(999, 0.02), 5000) == null, "under the price floor");
    check(Stretch.of(new MarketAggregates.StretchBasis(10000, 0), 15500) == null, "a move of 0");
    check(Stretch.of(basis, 0) == null, "no price");
    r = Stretch.of(new MarketAggregates.StretchBasis(100000, 0.001), 100300);
    check(r != null && r.abovePct == 1, "whole and at least 1: " + (r == null ? null : r.abovePct));
  }

  static void theWordsAreTheBridgesOwn() {
    Stretch.Reading r = new Stretch.Reading(1101581, 56);
    check(Stretch.line(r).equals("56% above its usual price -- often falls back. Consider waiting."), Stretch.line(r));
    check(Stretch.sentence(r).equals("This price is 56% above where the item usually traded over the last two weeks (about 1,101,581 gp). "
      + "Across the market, buys made this far above an item's usual price have lost money more often than buys near it."), Stretch.sentence(r));
  }

  static Pick buy() {
    Pick p = new Pick(1333, "Rune scimitar", "buy", 10, 15500, 17000, "market");
    p.reasoning = "Market-wide pick.";
    return p;
  }

  static void theVerdictLineComesFirstAndTheLabelOnlyFillsAnEmptyOne() {
    Pick p = buy();
    check(Verdict.suggestionVerdict(p) == null, "nothing measured: no opinion");
    p.stretch = new Stretch.Reading(10000, 55);
    p.fillHistoryHoursTraded = 300;
    p.fillHistoryHours = 336;
    Verdict.Result v = Verdict.suggestionVerdict(p);
    check(Verdict.CAUTION.equals(v.level) && "Price well above usual".equals(v.label), v.level + " / " + v.label);
    check(Boolean.FALSE.equals(v.checks.get(0).ok) && v.checks.get(0).text.equals("55% above its usual price -- often falls back. Consider waiting."), v.checks.get(0).text);
    check(v.checks.size() == 2, "the stretch line and the fill line: " + v.checks.size());
    Pick sell = new Pick(1333, "Rune scimitar", "sell", 10, 15500, 17000, "holding");
    sell.stretch = new Stretch.Reading(10000, 55);
    Verdict.Result vs = Verdict.suggestionVerdict(sell);
    check(vs == null || vs.checks.stream().noneMatch(l -> l.text.contains("above its usual price")), "never on a sell");
  }

  static void applyTouchesABuyOnly() {
    Map<Integer, MarketAggregates.StretchBasis> basis = Collections.singletonMap(1333, new MarketAggregates.StretchBasis(10000, 0.02));
    Pick p = buy();
    Stretch.apply(p, basis);
    check(p.stretch != null && p.stretch.abovePct == 55, "attached");
    check(p.reasoning.startsWith("Market-wide pick. This price is 55% above"), p.reasoning);
    check(p.itemId == 1333 && p.quantity == 10 && p.buyPrice == 15500 && p.sellPrice == 17000, "the pick is unchanged");
    Pick none = buy();
    Stretch.apply(none, null);
    check(none.stretch == null && none.reasoning.equals("Market-wide pick."), "no basis map: no warning");
    Pick sell = new Pick(1333, "Rune scimitar", "sell", 10, 15500, 17000, "holding");
    sell.reasoning = "Sell.";
    Stretch.apply(sell, basis);
    check(sell.stretch == null && sell.reasoning.equals("Sell."), "a sell is never warned");
  }
}
