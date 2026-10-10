package com.evi.live.engine;

/**
 * One suggestion as the tiers build it and the checks after them annotate it: the object {@code computeSuggestion},
 * {@code computeHoldingSuggestion} and {@code computeInventorySuggestion} return in bridge/suggestions.mjs, which
 * {@code pickWithForecast} and the server's enrichment then MUTATE (reasoning grows, a sell-support reading, a demotion,
 * a verdict, the headline profit are attached). It is mutable for exactly that reason: the JS order of those mutations is
 * part of the behaviour (a warning goes FIRST in the reasoning, a demotion note LAST), and a copy-on-write object would
 * hide which step wrote what.
 *
 * <p>Every field the JS leaves undefined is null here, and a null field is simply absent from the answer.
 *
 * <p>GP is a {@code long} everywhere a price or a price times a quantity is held, with ONE deliberate exception:
 * {@link #netIfSoldNow} is the double JS {@code Math.round} left. Since the maintainer's 7 Oct fix the holding tier folds minus
 * zero into zero, and the verdict prints any -0 handed to it as "0" (see {@link Verdict}); the field stays a double so a
 * vector carrying -0 still reaches it as the JS sees it. It is always a whole number.
 */
public final class Pick {
  public final int itemId;
  public final String name;
  /** "buy" or "sell". */
  public final String action;
  public final long quantity;
  public final long buyPrice;
  public final long sellPrice;
  /** "personal", "holding" or "inventory" in the tiers ported so far ("market" and "scanner" later). */
  public final String source;

  /** The personal tier's flip count on the item (so the verdict can say "your N flips here"). */
  public Integer trades;
  public String reasoning;
  /** The buy offer a holding came from, for the personal-use button; null when there is none. */
  public String buyId;
  /** Holding tier: never estimated, null when the cost basis is unknown. */
  public Long breakEvenPrice;
  public Long lossIfSoldNow;
  /** Holding tier: what selling now nets after tax, a whole number (never -0 from the holding tier, see the class note); null when unknown. */
  public Double netIfSoldNow;
  /** Holding tier: the position's sell listing ended while EVI was not watching (null for every other holding). */
  public com.evi.live.journal.FifoMatcher.EndedUnseenMark endedUnseen;
  /** Set by the caller when a holding was reconstructed from the journal rather than watched this session. */
  public Boolean persisted;

  /** The sell-support reading attached by the pick chain, whether or not anything was wrong with it. */
  public SellSupport.Detail sellSupport;
  /** Set when this pick is shown only because nothing better passed the sell-support check. */
  public Boolean demoted;
  /** An opaque forecast handed in by the forecast hook (off by default; plan decision 13). */
  public Object forecast;
  public Object fillOutlook;

  // ------------------------------------------------------------------------------------- the enrichment (Verdict.enrich)
  public Verdict.Result verdict;
  public Long quotedProfit;
  /** The headline figure; null for idle stock, which has no cost basis to state a profit against. */
  public Long expectedProfit;
  /** The fill-history reading the thin-market index carried, when there was one. */
  public Integer fillHistoryHoursTraded;
  public Integer fillHistoryHours;
  /** Set by the Auto step-down: the minimum this pick fell below. */
  public Double belowUsualBar;

  public Pick(int itemId, String name, String action, long quantity, long buyPrice, long sellPrice, String source) {
    this.itemId = itemId;
    this.name = name;
    this.action = action;
    this.quantity = quantity;
    this.buyPrice = buyPrice;
    this.sellPrice = sellPrice;
    this.source = source;
  }

  public boolean isBuy() {
    return "buy".equals(action);
  }
}
