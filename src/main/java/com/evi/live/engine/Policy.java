package com.evi.live.engine;

/**
 * Two small server.mjs rules every tier's answer passes through, kept out of the orchestration so they can be proven on
 * their own (bridge/server.mjs {@code autoMinProfit}, {@code headlineProfit}). Pure.
 */
public final class Policy {
  private Policy() {}

  /** AUTO_MIN_PROFIT: the floor against absurdity when the player chose no minimum. */
  public static final double AUTO_MIN_PROFIT = 500;
  /** AUTO_STACK_SHARE: Auto's floor is also 0.1% of the cash stack (28 Sept: 89m offered 500 gp trades). */
  public static final double AUTO_STACK_SHARE = 0.001;
  /**
   * BIGGER_POSITIONS (server.mjs), "Position sizing = Bigger positions" ({@code sizing=bigger}): the market tier's hourly
   * floor becomes a per-WINDOW floor of 24 units, and every tier's per-hour volume cap becomes 10% of what trades over the
   * whole window. The SIZING half only: the constant carries no ranking since 4 Oct ({@link LevelSettings#marketRankBy}).
   */
  public static final double BIGGER_POSITIONS_MIN_VOLUME_IN_WINDOW = 24;
  public static final double BIGGER_POSITIONS_WINDOW_SHARE = 0.10;

  /**
   * autoMinProfit: {@code max(500, Math.round(cash x 0.1%))}, or 500 for an unknown or empty stack. Under Auto this is a
   * PREFERENCE, not a gate (the step-down answers below it); an explicit minimum is never raised.
   */
  public static double autoMinProfit(double cash) {
    if (!JsValues.isFinite(cash) || cash <= 0) return AUTO_MIN_PROFIT;
    return Math.max(AUTO_MIN_PROFIT, JsValues.jsRound(cash * AUTO_STACK_SHARE));
  }

  /**
   * headlineProfit: the more cautious of the quoted spread and what buyers have actually been paying, or the quoted
   * spread alone when nothing was measured (no measurement must never invent a constraint). Null without a quote.
   */
  public static Long headlineProfit(Long quoted, Long supported) {
    if (quoted == null) return null;
    if (supported == null) return quoted;
    return Math.min(quoted, supported);
  }

  /** The same over arbitrary JS numbers (NaN for none), as the bridge function takes them. */
  public static Double headlineProfitOf(double quoted, double supported) {
    if (!JsValues.isFinite(quoted)) return null;
    if (!JsValues.isFinite(supported)) return quoted;
    return Math.min(quoted, supported);
  }
}
