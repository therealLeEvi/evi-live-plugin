package com.evi.live;

/**
 * The player's one trading choice since 6 Oct 2026: how many losing trades they accept. Sent to the
 * bridge as ?risk=low|medium|high on GET /api/suggestion. LOW (the default) is left off the query
 * string, because the bridge's own default when no risk parameter arrives is also low
 * (server.mjs: anything else falls back to 'low'); MEDIUM and HIGH are always sent.
 *
 * Read from TWO config items. The live one is "riskLevelV2" (EviLiveConfig.riskLevelV2), new on
 * 6 Oct 2026 so that every existing player starts fresh at Low and chooses knowingly: under the old
 * key a High chosen for the history tier would have silently become High on the market tier too. The
 * old "riskLevel" item is kept hidden with this same type, so a stored value still loads, and nothing
 * reads it.
 *
 * HIDDEN since 7 Oct 2026 (the maintainer's decision, after three offline replays could not validate the levels:
 * a replay cannot measure whether a sell actually fills). See {@link #SHOWN}.
 *
 * NO ODDS ANYWHERE, permanently (same decision): no label, tooltip or line says how many trades lose. How
 * the levels behave is explained in a FAQ, never in the interface -- and that holds even once they return.
 */
public enum RiskLevel {
  LOW, MEDIUM, HIGH;

  /** THE ONE SWITCH for the risk levels, false since 7 Oct 2026: until the levels are calibrated on LIVE sells,
   *  nothing of them is visible and nothing of them is sent. While false: the sidebar's Risk level section
   *  (rule, heading, the three buttons and the line under them) is built but never shown; the settings item
   *  riskLevelV2 is hidden (it keeps its keyName and type, so a stored value still loads safely); and the
   *  request NEVER carries risk=, whatever is stored -- every player gets Low, which is the bridge's own
   *  default when no risk= arrives, so a default player's request is byte-identical to before. All of the
   *  code and its tests stay in place behind this constant: setting it true brings the whole section back.
   *  A compile-time constant on purpose, because the settings item's {@code hidden} reads it. */
  static final boolean SHOWN = false;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label: the bare
   *  name, with no odds (7 Oct 2026). Stored values are the constant NAMES, so changing these labels never
   *  touches what a player saved. */
  @Override public String toString() {
    return label();
  }

  /** The bare name, for the sidebar's three buttons. */
  String label() {
    switch (this) {
      case LOW: return "Low";
      case HIGH: return "High";
      default: return "Medium";
    }
  }

  /** The one quiet line under the sidebar's buttons: what kind of item to expect at the level. It names
   *  no odds (7 Oct 2026): how many trades lose is a FAQ answer, never an interface line. */
  String aim() {
    switch (this) {
      case LOW: return "Mostly busy, high-volume items.";
      case HIGH: return "Adds gear whose price can drop suddenly.";
      default: return "Adds slower, mid-priced items with thinner edges.";
    }
  }

  /** Added to High's line when "Max share of cash per trade" is "No limit" (the maintainer's decision 8b, 6 Oct 2026):
   *  High follows the player's own cap and no hidden second one, so with no cap set the line says so plainly. */
  static final String NO_LIMIT_AT_HIGH = "No trade-size limit (your setting): one High trade can use most of your stack.";

  /** The line under the buttons, given the player's "Max share of cash per trade" (null: not known yet). Only the
   *  one combination that removes every size limit -- High with "No limit" -- adds anything, and only words. */
  String aim(MaxTradeShare share) {
    return this == HIGH && share == MaxTradeShare.OFF ? aim() + " " + NO_LIMIT_AT_HIGH : aim();
  }

  /** The exact ?risk= value the bridge expects. */
  String param() { return name().toLowerCase(); }
}
