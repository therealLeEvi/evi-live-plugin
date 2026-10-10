package com.evi.live.engine;

/**
 * Reading a suggestion request's values the way server.mjs reads its query string ({@code cashFromQuery},
 * {@code maxSpendFromQuery}). Pure. The in-process transport receives the same query text the bridge did, so the
 * same text must mean the same thing.
 *
 * <p>ABSENT IS NOT ZERO (Phase 0's F1 fix, 6 Oct): no cash reading from an identified player means "unknown, no spend
 * cap"; {@code cash=0} means "carrying nothing, afford nothing"; and a poll naming neither an account nor a cash
 * figure describes no player at all and stays at 0, exactly as the bridge keeps it.
 */
public final class QueryValues {
  private QueryValues() {}

  /** cashFromQuery: NaN when the parameter is absent or blank, otherwise {@code Number(raw)}. */
  public static double cashFromQuery(String raw) {
    return raw == null || JsValues.trim(raw).isEmpty() ? Double.NaN : JsValues.toNumber(raw);
  }

  /**
   * The {@code stackShare} parameter (EviLiveConfig.maxTradeShare, a percentage) as server.mjs reads it:
   * {@code Number(searchParams.get('stackShare'))} -- so an ABSENT parameter is Number(null), 0 -- kept only when it is
   * finite and in (0, 100], as a fraction; NaN otherwise (no share cap). Only meaningful beside a known cash stack.
   */
  public static double maxStackShare(String raw) {
    double share = raw == null ? 0 : JsValues.toNumber(raw);
    return JsValues.isFinite(share) && share > 0 && share <= 100 ? share / 100 : Double.NaN;
  }

  /**
   * maxSpendFromQuery: the cash reading when it is a finite value of at least 0; else null (no limit) when the poll
   * names an account; else 0. {@code accountRaw} is the raw parameter (null when absent; empty names no account).
   */
  public static Double maxSpendFromQuery(String cashRaw, String accountRaw) {
    double cash = cashFromQuery(cashRaw);
    if (JsValues.isFinite(cash) && cash >= 0) return cash;
    if (accountRaw == null || accountRaw.isEmpty()) return 0.0;
    return null;
  }
}
