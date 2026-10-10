package com.evi.live.journal;

/**
 * How much of an item ONE account has bought in the GE's rolling four-hour window, from EVI's own journal
 * (store.mjs {@code buyLimitUsage}).
 *
 * <p>Deliberately an UNDER-estimate: only buys EVI observed count, so callers may use it to REDUCE a
 * suggested quantity, never to justify a larger one. Each buy is attributed to when it completed (or was
 * last seen changing), the closest time the journal has. A buy exactly four hours old still counts.
 *
 * <p>SCOPED BY CONSTRUCTION: the account is compared for equality and a null account matches nothing
 * (never "every account"). That is the 6 Oct pure/main bug's lesson; the JS engine gives the same answer
 * here (no offer has an undefined account), and the vectors assert it.
 */
public final class BuyLimitUsage {
  /** The GE's window, 4 hours. */
  public static final long WINDOW_MS = 4L * 3600 * 1000;

  public final long used;
  /** When the oldest counted buy leaves the window; null when nothing is counted (absent, not zero). */
  public final Long windowEndsAt;

  BuyLimitUsage(long used, Long windowEndsAt) {
    this.used = used;
    this.windowEndsAt = windowEndsAt;
  }

  public static BuyLimitUsage of(Iterable<Offer> offers, String account, int itemId, long now) {
    if (account == null) return new BuyLimitUsage(0, null);
    long windowStart = now - WINDOW_MS;
    long used = 0;
    Long oldest = null;
    for (Offer o : offers) {
      if (!account.equals(o.account) || o.itemId != itemId || !"buy".equals(o.side()) || !(o.filled > 0)) continue;
      Long at = o.completedAt != null ? o.completedAt : o.updated != null ? o.updated : o.firstSeen;
      if (at == null || at < windowStart) continue;
      used = Js.add(used, o.filled);
      if (oldest == null || at < oldest) oldest = at;
    }
    return new BuyLimitUsage(used, oldest == null ? null : oldest + WINDOW_MS);
  }
}
