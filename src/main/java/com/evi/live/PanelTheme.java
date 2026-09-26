package com.evi.live;

/**
 * Which colours EVI's own sidebar panel uses, as a plugin config dropdown. RUNELITE is the default
 * and is what the panel has always looked like: the client's own grays with EVI's teal as the single
 * accent, so the panel reads as part of RuneLite rather than a bolted-on box. OLD_SCHOOL is the
 * parchment-and-leather scheme the browser scanner also offers, for a player who would rather both
 * halves of EVI looked like the game than like the client.
 *
 * Only colours change. Nothing about what is suggested, warned about or sent depends on this.
 */
public enum PanelTheme {
  RUNELITE, OLD_SCHOOL;

  /** RuneLite renders enum dropdowns with toString(), so this is the visible label. */
  @Override public String toString() {
    return this == OLD_SCHOOL ? "Old School (parchment)" : "RuneLite (default)";
  }
}
