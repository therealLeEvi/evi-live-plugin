package com.evi.live.engine;

import com.evi.live.journal.Tax;
import com.evi.live.market.MarketAggregates;
import java.util.Map;

/**
 * A holding below break-even: quote a PATIENT listing price, not only the last print (bridge/patience.mjs
 * {@code patiencePrice}, {@code applyPatience}, {@code patienceLine}, and server.mjs's enrich). Pure.
 *
 * <p>Asked for on 23 Oct 2026: the holding card quoted 14 Antler guards at the last print, under their break-even, at a
 * 12-hour pace; listed at the day's typical high instead they sold that day for a gain. Measured first
 * (PATIENCE-VERDICT-2026-10-23, private): over every item and hour of two months, listing at
 * P = max(last print, median of the previous 24 hourly highs) for a 12-hour pace beat the last print -- median +1.6 to
 * +1.8%, sold at P 80-85% of the time (median wait about 50 minutes) -- in both halves, without the top 5 items, under the
 * cautious variant, and at 24 and 48 hours.
 *
 * <p>What it changes, and only this: on a HOLDING whose quick sale is a loss (a known break-even, not a listing that
 * ended unseen), at a pace of at least {@link #PATIENCE_MIN_PACE} minutes, when the typical high sits more than half a
 * percent above the last print, the pick's sell price becomes that typical high (so the GE price key fills it), it carries
 * a {@link Reading}, its headline is what listing there would net over what was paid, and the reasoning says so. The loss
 * at today's price and the break-even stay. Fails open: no basis (thin data, before the first build), no pace or a short
 * one -- the card is exactly as before.
 */
public final class Patience {
  private Patience() {}

  public static final double PATIENCE_MIN_PACE = 720;
  /** "More than 0.5% above", compared in whole numbers: typicalHigh x 1000 > quick x 1005. */
  public static final long PATIENCE_MIN_GAP_PER_MILLE = 1005;

  /** patiencePrice's answer: the patient price (the typical high), the quick price it was judged against, and the basis' hours. */
  public static final class Price {
    public final long price;
    public final double quickPrice;
    public final long typicalHigh;
    public final int hours;

    Price(long price, double quickPrice, long typicalHigh, int hours) {
      this.price = price;
      this.quickPrice = quickPrice;
      this.typicalHigh = typicalHigh;
      this.hours = hours;
    }
  }

  /** {@code patience {price, quickPrice, typicalHigh, net}} on a pick: net is what listing at price would net over what was paid. */
  public static final class Reading {
    public final long price;
    public final long quickPrice;
    public final long typicalHigh;
    public final long net;

    Reading(long price, long quickPrice, long typicalHigh, long net) {
      this.price = price;
      this.quickPrice = quickPrice;
      this.typicalHigh = typicalHigh;
      this.net = net;
    }
  }

  /** patiencePrice: null unless there is a basis, a pace of at least {@link #PATIENCE_MIN_PACE}, and the typical high is more than 0.5% above. */
  public static Price price(MarketAggregates.PatienceBasis basis, double quickPrice, Double paceMinutes) {
    if (basis == null || !(basis.typicalHigh > 0) || !(quickPrice > 0)) return null;
    if (paceMinutes == null || !(paceMinutes >= PATIENCE_MIN_PACE)) return null;
    if (!((double) basis.typicalHigh * 1000 > quickPrice * PATIENCE_MIN_GAP_PER_MILLE)) return null;
    return new Price(basis.typicalHigh, quickPrice, basis.typicalHigh, basis.hours);
  }

  /** patienceLine: the card line, short enough for the 225px panel. */
  static String line(Reading r) {
    return "Patient price: " + JsValues.localeUs(r.price) + " (24h typical)";
  }

  /**
   * applyPatience, for one pick, IN PLACE: anything that is not a holding sold at a loss with a known break-even (and a
   * finite net), or a listing that ended unseen, is left untouched. A null basis map is no reading.
   */
  static void apply(Pick s, Map<Integer, MarketAggregates.PatienceBasis> basis, Double paceMinutes) {
    if (s == null || !"holding".equals(s.source) || !"sell".equals(s.action) || s.endedUnseen != null) return;
    if (s.lossIfSoldNow == null || !(s.lossIfSoldNow > 0) || s.breakEvenPrice == null || s.netIfSoldNow == null
      || !JsValues.isFinite(s.netIfSoldNow)) return;
    long quick = s.sellPrice;
    Price r = price(basis == null ? null : basis.get(s.itemId), quick, paceMinutes);
    if (r == null) return;
    long qty = s.quantity > 0 ? s.quantity : 1;
    double unitAtPatience = r.price - Tax.estimateUnitTax(s.itemId, r.price), unitAtQuick = quick - Tax.estimateUnitTax(s.itemId, quick);
    long net = (long) JsValues.jsRound(s.netIfSoldNow + qty * (unitAtPatience - unitAtQuick)); // (long) folds JS's -0 into 0, as `|| 0`
    s.patience = new Reading(r.price, quick, r.typicalHigh, net);
    s.sellPrice = r.price;
    String versus = r.price >= s.breakEvenPrice
      ? "which clears your break-even"
      : "still under your break-even, a loss of about " + JsValues.localeUs(Math.abs(net)) + " gp instead of " + JsValues.localeUs(s.lossIfSoldNow);
    s.reasoning = ((s.reasoning == null ? "" : s.reasoning) + " Patient price: " + JsValues.localeUs(r.price)
      + " gp, the typical high of the last 24 hours (" + versus + "). "
      + "In EVI's market test, holdings under break-even listed at the day's typical high usually sold there within the hour "
      + "and did better than selling at the last price. Not a promise: if it has not sold by the end of your pace, the market "
      + "price is the one to judge by.").trim();
  }
}
