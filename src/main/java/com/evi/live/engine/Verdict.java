package com.evi.live.engine;

import com.evi.live.journal.Js;
import com.evi.live.journal.Tax;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What EVI thinks of its own suggestion, as something the sidebar can draw (bridge/verdict.mjs
 * {@code suggestionVerdict}), and the headline enrichment server.mjs applies to every pick before it is sent (the
 * {@code enrich} closure: the verdict, the quoted profit, and the CAUTIOUS headline figure). Pure.
 *
 * <p>The verdict invents nothing: every line restates a figure a check already produced, and no verdict at all (null)
 * means "no opinion", never "fine". Lines are short on purpose (the 225px panel).
 *
 * <p>MINUS ZERO PRINTS AS "0" (the maintainer, 7 Oct 2026, fixed in JS first): a holding whose net rounded to minus zero
 * used to read "+-0 gp over what you paid", because {@code Math.round(-0).toLocaleString()} is "-0". The verdict's own
 * {@link #gp} folds only that one value; every other figure prints exactly as {@link JsValues#gp} does.
 */
public final class Verdict {
  private Verdict() {}

  public static final String CLEAR = "clear";
  public static final String CAUTION = "caution";
  public static final String WARN = "warn";

  /** One line of a verdict: passed (true), failed (false) or a plain fact (null). */
  public static final class Line {
    public final Boolean ok;
    public final String text;

    Line(Boolean ok, String text) {
      this.ok = ok;
      this.text = text;
    }
  }

  public static final class Result {
    public final String level;
    public final String label;
    public final List<Line> checks;

    Result(String level, String label, List<Line> checks) {
      this.level = level;
      this.label = label;
      this.checks = Collections.unmodifiableList(checks);
    }
  }

  /**
   * verdict.mjs's own {@code gp}: {@code Math.round(n).toLocaleString('en-US')} with MINUS ZERO folded into 0, so a net within
   * half a gp of break-even reads "+0", never "+-0". NaN and every other value print exactly as {@link JsValues#gp}.
   */
  static String gp(double n) {
    double r = JsValues.jsRound(n);
    return JsValues.localeUs(r == 0 ? 0.0 : r);
  }

  private static boolean finite(Double d) {
    return d != null && JsValues.isFinite(d);
  }

  /** suggestionVerdict: null for no suggestion, an item id of 0, or nothing measured. */
  public static Result suggestionVerdict(Pick s) {
    if (s == null || s.itemId == 0) return null;
    List<Line> checks = new ArrayList<>();
    String level = CLEAR;
    String label = null;
    double qty = s.quantity > 0 ? s.quantity : 1;
    double quotedNet = (double) (s.sellPrice - s.buyPrice - Tax.estimateUnitTax(s.itemId, s.sellPrice)) * qty;

    // A sell that would realise a loss outranks everything else. Warn, never block.
    if (s.lossIfSoldNow != null && s.lossIfSoldNow > 0) {
      List<Line> l = new ArrayList<>();
      l.add(new Line(false, "Down " + gp(s.lossIfSoldNow) + " gp at today's price"));
      if (s.breakEvenPrice != null) l.add(new Line(null, "Break-even after tax: " + gp(s.breakEvenPrice)));
      return new Result(WARN, "Selling now is a loss", l);
    }
    // A holding whose sell listing ENDED while EVI was not watching (8 Oct, a real case): it may have sold. Never a
    // profit and never green -- only what EVI saw, at CAUTION, like idle stock.
    if ("holding".equals(s.source) && s.endedUnseen != null) {
      checks.add(new Line(null, "Last seen listed at " + gp(s.endedUnseen.price)));
      checks.add(new Line(null, "Gone at your next login"));
      checks.add(new Line(null, "No sale seen, so no profit is claimed"));
      level = CAUTION;
      label = "Ended while EVI was away";
    }
    // A holding that is NOT a loss draws a card too (30 Sept: only the bad news used to).
    if ("holding".equals(s.source) && finite(s.netIfSoldNow) && s.netIfSoldNow >= 0) {
      checks.add(new Line(true, "+" + gp(s.netIfSoldNow) + " gp over what you paid"));
      if (s.breakEvenPrice != null) checks.add(new Line(null, "Break-even after tax: " + gp(s.breakEvenPrice)));
      label = label != null ? label : "Above break-even"; // JS: label || 'Above break-even'
    }
    // Stock EVI never watched being bought: there is no profit to state, only what the stack is worth today.
    if ("inventory".equals(s.source)) {
      double worth = (double) s.sellPrice * qty;
      if (worth > 0) checks.add(new Line(null, "Worth ~" + gp(worth) + " at today's price"));
      checks.add(new Line(null, "No buy on record, so no profit is claimed"));
      level = CLEAR.equals(level) ? CAUTION : level;
      label = label != null ? label : "Not bought through EVI";
    }
    // The margin at the price buyers have actually been paying, against the quoted spread.
    SellSupport.Detail support = s.sellSupport;
    if (support != null && finite(support.netAtAverage)) {
      double supportedTotal = JsValues.jsRound(support.netAtAverage * qty);
      if (supportedTotal < quotedNet * 0.7) {
        level = CAUTION;
        label = "Worth less than it looks";
        checks.add(new Line(false, gp(supportedTotal) + " at what buyers really pay"));
        if (finite(support.averagePaid) && finite(support.units))
          checks.add(new Line(null, gp(support.units) + " paid ~" + gp(support.averagePaid) + " in " + (support.hours == null ? "null" : support.hours) + "h"));
      } else if (supportedTotal >= quotedNet) {
        checks.add(new Line(true, "Buyers paying more than quoted"));
      } else {
        checks.add(new Line(true, "Holds up at ~" + gp(SellSupport.nullAsZero(support.averagePaid))));
      }
    }
    // The support is mostly HISTORY: the headline has already been capped on it, so say why.
    if (support != null && finite(support.staleness) && finite(support.latestPaid)) {
      if (support.staleness >= SellSupport.STALE_SUPPORT_RATIO) {
        level = CLEAR.equals(level) ? CAUTION : level;
        label = label != null ? label : "Buyers have moved on";
        checks.add(new Line(false, "Now paying ~" + gp(support.latestPaid) + ", not " + gp(SellSupport.nullAsZero(support.averagePaid))));
      } else if (support.staleness >= SellSupport.SOFT_STALE_SUPPORT_RATIO) {
        checks.add(new Line(null, "Buyers now paying ~" + gp(support.latestPaid)));
      }
    }
    // Can this price be bought again at all: the fill-history reading.
    if (s.fillHistoryHoursTraded != null && s.fillHistoryHours != null && s.fillHistoryHours > 0) {
      double share = (double) s.fillHistoryHoursTraded / s.fillHistoryHours;
      if (share < 0.25) {
        level = CLEAR.equals(level) ? CAUTION : level;
        label = label != null ? label : "Rarely traded";
        checks.add(new Line(false, "Traded only " + s.fillHistoryHoursTraded + " of " + s.fillHistoryHours + " hours"));
      } else {
        checks.add(new Line(true, "Traded " + s.fillHistoryHoursTraded + " of " + s.fillHistoryHours + " hours"));
      }
    }
    if ("personal".equals(s.source) && s.trades != null && s.trades > 0)
      checks.add(new Line(true, "Your " + s.trades + " flip" + (s.trades == 1 ? "" : "s") + " here"));
    // Demoted for a reason none of the above named: say so rather than show a clean card.
    if (Boolean.TRUE.equals(s.demoted) && CLEAR.equals(level)) {
      level = CAUTION;
      label = "Shown because nothing passed";
    }
    if (checks.isEmpty() && CLEAR.equals(level)) return null;
    return new Result(level, label != null ? label : "Every check passed", checks.size() > 4 ? checks.subList(0, 4) : checks);
  }

  /**
   * The server's {@code enrich}: the fill-history reading (when there is one), the verdict, the quoted profit, the
   * headline figure -- the LOWER of the quoted spread and the margin at the price buyers pay (capped at what they pay NOW
   * when the support is stale) -- and the stale flag on the reading. Idle stock gets no headline: with no cost basis
   * the spread is not a profit (30 Sept). Mutates {@code s}, as the JS does.
   *
   * @param hoursTraded the thin-market reading's hours traded (null: none)
   */
  public static void enrich(Pick s, Integer hoursTraded, Integer hours) {
    if (s == null) return;
    if (hoursTraded != null && hours != null) {
      s.fillHistoryHoursTraded = hoursTraded;
      s.fillHistoryHours = hours;
    }
    Result v = suggestionVerdict(s);
    if (v != null) s.verdict = v;
    long q = s.quantity > 0 ? s.quantity : 1;
    long quoted = Js.multiply(s.sellPrice - s.buyPrice - Tax.estimateUnitTax(s.itemId, s.sellPrice), q);
    SellSupport.Detail support = s.sellSupport;
    Double honest = SellSupport.supportedPriceForHeadline(support);
    Long supported = support != null && finite(support.netAtAverage) && finite(honest)
      ? Js.round((honest - Tax.estimateUnitTax(s.itemId, honest) - s.buyPrice) * q) : null;
    if (support != null && SellSupport.supportIsStale(support)) support.stale = true;
    s.quotedProfit = quoted;
    // Idle stock, and a holding whose listing ended unseen: neither has a profit EVI can state.
    s.expectedProfit = "inventory".equals(s.source) || s.endedUnseen != null ? null : Policy.headlineProfit(quoted, supported);
  }
}
