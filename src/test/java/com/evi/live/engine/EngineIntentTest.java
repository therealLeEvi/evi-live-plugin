package com.evi.live.engine;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Offer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The engine core's traps as INTENT, beside the recorded vectors (EngineVectorsTest), so a parity diff and a broken rule
 * read differently. Every expectation is a literal value, chosen so that the obvious wrong implementation gives a
 * different one. Synthetic data only.
 */
public final class EngineIntentTest {
  private static int checks;

  public static void main(String[] args) {
    spreadBucketIsUnsigned();
    absentCashIsNotZero();
    measuredZeroBeatsTheMedian();
    halfUpTies();
    accountScoping();
    capOrderDecidesTheSentence();
    gatesFailOpen();
    jsNumberRules();
    System.out.println("PASS: engine intent, " + checks + " checks");
  }

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  // The FNV hash is an UNSIGNED 32-bit value. "acct-b" hashes to 0x8F0E... (negative as a Java int), so a signed % would
  // hand that account a negative or different bucket; the JS answer for a spread of 5 is 2 and for 7 is 1.
  static void spreadBucketIsUnsigned() {
    int h = SpreadRank.fnv1a("acct-b");
    check(h < 0, "the case needs a hash whose top bit is set (it is " + Integer.toHexString(h) + ")");
    long unsigned = Integer.toUnsignedLong(h);
    check(SpreadRank.spreadRankFor("acct-b", 5) == unsigned % 5 && SpreadRank.spreadRankFor("acct-b", 7) == unsigned % 7, "the bucket must be the UNSIGNED remainder");
    check(SpreadRank.spreadRankFor("acct-b", 5) != Math.floorMod(h, 5) || SpreadRank.spreadRankFor("acct-b", 7) != Math.floorMod(h, 7),
      "the signed bucket must differ somewhere, or this test proves nothing");
    check(SpreadRank.spreadRankFor("acct-b", 1) == 0 && SpreadRank.spreadRankFor(null, 5) == 0 && SpreadRank.spreadRankFor(" \u00A0", 5) == 0,
      "spread 1, no account and a white-space account are the top pick");
    check(SpreadRank.spreadRankFor("\u00A0acct-b\u00A0", 5) == SpreadRank.spreadRankFor("acct-b", 5), "JS trim removes the no-break space");
    check(SpreadRank.spreadRankFor("\u0001acct-b", 5) != SpreadRank.spreadRankFor("acct-b", 5) || SpreadRank.fnv1a("\u0001acct-b") != h,
      "JS trim keeps a control character, which Java's String.trim would strip");
  }

  // 29 Sept / F1: no cash reading from an identified player is no cap; cash=0 is afford nothing; no account and no cash is 0.
  static void absentCashIsNotZero() {
    check(QueryValues.maxSpendFromQuery(null, "acct-a") == null, "absent cash, known account: no spend cap");
    check(QueryValues.maxSpendFromQuery("0", "acct-a") == 0.0, "cash=0 is a real answer");
    check(QueryValues.maxSpendFromQuery(" ", "acct-a") == null, "blank cash is absent");
    check(QueryValues.maxSpendFromQuery(null, null) == 0.0 && QueryValues.maxSpendFromQuery(null, "") == 0.0, "no account, no cash: 0, as the bridge keeps it");
    check(Sizing.maxSpend(0) == 0.0 && Sizing.maxSpend(-1) == null && Sizing.maxSpend(Double.NaN) == null, "a tier keeps cash 0 as 0 and only junk as unknown");
    VolumeRow row = new VolumeRow(2000, 500, 1000, 500);
    Sizing.Sized unknown = Sizing.historyQuantity(561, 10, 1000, null, Double.NaN, row, Double.NaN, null, Sizing.SizingOptions.DEFAULT, Sizing.ItemGate.NONE);
    check(unknown != null && unknown.quantity == 10 && !unknown.cashLimited, "unknown cash sizes at the player's own median");
    check(Sizing.historyQuantity(561, 10, 1000, 0.0, Double.NaN, row, Double.NaN, null, Sizing.SizingOptions.DEFAULT, Sizing.ItemGate.NONE) == null,
      "carrying nothing buys nothing");
    Sizing.Sized four = Sizing.historyQuantity(561, 10, 1000, 4999.0, Double.NaN, row, Double.NaN, null, Sizing.SizingOptions.DEFAULT, Sizing.ItemGate.NONE);
    check(four != null && four.quantity == 4 && four.cashLimited, "4,999 gp buys four at 1,000");
  }

  // 2 Oct: a measured zero in the latest hour beats any median; a zero typical reading falls back.
  static void measuredZeroBeatsTheMedian() {
    check(Sizing.sizingLiquidityFor(0L, 403) == 0.0, "a measured zero now wins over a typical 403");
    check(Sizing.sizingLiquidityFor(null, 403) == 403.0 && Sizing.sizingLiquidityFor(969L, 92) == 92.0, "otherwise the typical hour");
    check(Sizing.sizingLiquidityFor(969L, 0) == 969.0 && Sizing.sizingLiquidityFor(null, Double.NaN) == null, "a zero typical falls back; nothing is no reading");
    check(Sizing.orderSizeCap(0, Double.NaN, Sizing.SizingOptions.DEFAULT) == 1L, "a measured zero sizes one unit, never 'no constraint'");
    check(Sizing.orderSizeCap(Double.NaN, Double.NaN, Sizing.SizingOptions.DEFAULT) == null, "no reading constrains nothing");
  }

  // Math.round is half-UP: a median quantity of 2.5 is 3 (never 2), and -2.5 is -2.
  static void halfUpTies() {
    check(Medians.average(Arrays.asList(2.0, 3.0)) == 2.5, "personalHistory's median averages the middle pair");
    check(Medians.upper(new long[]{2, 3}) == 3L, "the robust median takes the upper middle");
    Sizing.Sized s = Sizing.historyQuantity(561, 2.5, 1000, null, Double.NaN, null, Double.NaN, null, Sizing.SizingOptions.DEFAULT, Sizing.ItemGate.NONE);
    check(s != null && s.quantity == 3, "a 2.5 median is an order of 3");
    check(Sizing.estimateOfferFill(1, new VolumeRow(0, 60, 0, 60), 60).estimatedFillMinutes == 8, "7.5 minutes rounds up to 8");
  }

  // The PORT WARNING: no account owns nothing (JS's own predicate would count every account).
  static void accountScoping() {
    List<FifoMatcher.OpenPosition> pos = Arrays.asList(
      new FifoMatcher.OpenPosition("acct-a", 561, null, null, 10, 10, null, 200, false),
      new FifoMatcher.OpenPosition("acct-b", 565, null, null, 5, 5, null, 400, false),
      new FifoMatcher.OpenPosition("", 561, null, null, 3, 3, null, 999, false)); // a record with an empty account owns nothing either
    List<Offer> offers = Collections.singletonList(new Offer(0, "BUYING", null, 4151, null, 0, 0, 1, 2000000, false, null, "acct-b", null, null, null, null, null));
    for (String none : new String[]{null, ""}) {
      GeSlots.Exposure x = GeSlots.slotExposure(pos, offers, none);
      check(x.exposure.isEmpty() && x.sellSlotsOwed == 0, "no account must own nothing (" + none + ")");
      check(Prices.heldCostBasis(pos, offers, 561, none) == null, "no account, no cost basis (" + none + ")");
    }
    GeSlots.Exposure a = GeSlots.slotExposure(pos, offers, "acct-a");
    check(a.exposure.equals(Collections.singleton(561)) && a.owedItems.equals(Collections.singletonList(561)), "acct-a holds 561 only");
    GeSlots.Exposure b = GeSlots.slotExposure(pos, offers, "acct-b");
    check(new ArrayList<>(b.exposure).equals(Arrays.asList(565, 4151)) && b.sellSlotsOwed == 1, "acct-b: held 565 then buying 4151, one exit owed");
  }

  // The quantity is the smallest cap whatever the order; WHICH cap binds (the sentence) depends on the tier's order.
  static void capOrderDecidesTheSentence() {
    VolumeRow row = new VolumeRow(2000, 50, 1000, 50); // 50 an hour: 10% is 5 units
    Sizing.ItemGate limit5 = new Sizing.ItemGate() {
      @Override public boolean membersBlocked(int itemId) {
        return false;
      }

      @Override public boolean focusBlocked(int itemId) {
        return false;
      }

      @Override public Double limitRemaining(int itemId) {
        return 5.0;
      }
    };
    // Cash allows 5 as well. Market order: cash first (binds at 5), then the share (5 is not < 5), then the limit (not < 5).
    Sizing.Sized m = Sizing.marketQuantity(561, 11000, 1000, 5000.0, Double.NaN, row, Double.NaN, null, Sizing.SizingOptions.DEFAULT, limit5);
    check(m.quantity == 5 && m.cashLimited && !m.shareLimited && !m.limitLimited, "market: cash is checked first");
    // Pushed order: share (5), then the limit (5, not <), then cash (5, not <): the SHARE sentence.
    Sizing.Sized p = Sizing.pushedQuantity(561, 11000, Double.NaN, 1000, 5000.0, row, Double.NaN, null, Sizing.SizingOptions.DEFAULT, limit5);
    check(p.quantity == 5 && p.shareLimited && !p.cashLimited && !p.limitLimited, "pushed: the volume share is checked first and cash last");
    check(Sizing.gate(13190, 5, Sizing.ItemGate.NONE) == null, "the bond is never bought");
    // Market: cash (80), then the trade length (60 an hour over an hour is 60, so 40 fillable), then the stack share (40):
    // the length binds and the share, equal and later, does not. No volume share (0), so it cannot interfere.
    Sizing.SizingOptions noShare = new Sizing.SizingOptions(0, Double.NaN, false);
    Sizing.Sized md = Sizing.marketQuantity(561, 11000, 1000, 80000.0, 0.5, new VolumeRow(2000, 60, 1000, 60), Double.NaN, 60.0, noShare, Sizing.ItemGate.NONE);
    check(md.quantity == 40 && md.cashLimited && md.durationLimited && !md.stackLimited, "market: the trade length is checked before the stack share");
    // Pushed: the buy-limit clip (5) comes BEFORE cash (also 5), so the sentence is the limit's.
    Sizing.Sized pl = Sizing.pushedQuantity(561, 11000, Double.NaN, 1000, 5000.0, row, Double.NaN, null, noShare, limit5);
    check(pl.quantity == 5 && pl.limitLimited && !pl.cashLimited, "pushed: the buy-limit clip is checked before cash");
  }

  // Nothing unknown blocks a candidate.
  static void gatesFailOpen() {
    check(Gates.marginClearsTax(Double.NaN, 4, 1) && Gates.marginClearsTax(2, 0, 1) && !Gates.marginClearsTax(2, 4, 1), "margin over tax");
    check(!Gates.implausibleSpread(864, 10, 1, 5) && Gates.implausibleSpread(864, 10, Double.NaN, 5), "absent volume is 'did not trade' only here");
    check(!Gates.implausibleBuyPrint(299, null, 0.5) && Gates.implausibleBuyPrint(299, new VolumeRow(0, 0, 2984, 1898), 0.5), "a 299 print against 2,984");
    check(Gates.liquidityFloorMet(5, null, Double.NaN, Double.NaN, LevelSettings.LOW) && !Gates.liquidityFloorMet(4, null, Double.NaN, Double.NaN, LevelSettings.LOW),
      "the hourly floor is 5");
    check(Gates.liquidityFloorMet(1, 2880.0, 24, Double.NaN, LevelSettings.LOW), "the window floor: 1 an hour over 48 hours is 48 >= 24");
  }

  // Number(string) is not Double.parseDouble.
  static void jsNumberRules() {
    check(JsValues.toNumber(" ") == 0 && JsValues.toNumber("0x10") == 16 && JsValues.toNumber("0b11") == 3, "JS reads blank as 0 and hex/binary literals");
    check(Double.isNaN(JsValues.toNumber("1d")) && Double.isNaN(JsValues.toNumber("NaN")) && Double.isNaN(JsValues.toNumber("\u00013")), "Java-only forms are NaN");
    check(GeSlots.slotCapacity(" ", "0").full && GeSlots.slotCapacity("", "0").free == null, "a blank count is 0, an empty one is absent");
  }
}
