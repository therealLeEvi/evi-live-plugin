package com.evi.live;

/**
 * Where a buy suggestion is allowed to come from.
 *
 * EVI has always tried the player's own trade history first and only fallen through to the wider
 * market when that history had nothing eligible. The reason is sound -- an item you have traded
 * profitably before is evidence about what you can actually trade -- but it has a cost the player
 * cannot see: a better market-wide trade can sit behind a worse one from your own history, and
 * EVI never mentions it.
 *
 * The cost is not hypothetical. Replaying 90 days in September found the personal ranking at
 * Medium risk did no better than picking an eligible item at random, because a single lucky flip
 * could carry an item into first place for weeks (the project notes, 18 Sept 2026). On
 * 26 Sept a one-flip history put an Uncharged toxic trident (e) ahead of everything else at a
 * quoted 1,300,613 gp, while buyers were in fact paying a price that made it worth about 64,559.
 *
 * So the player gets to choose, and the default changes nothing for anyone who does not:
 *
 * <ul>
 *   <li>{@code HISTORY_FIRST} -- what EVI has always done, and still the default.</li>
 *   <li>{@code BEST_OF_BOTH} -- rank your history and the wider market together, and take whichever
 *       is genuinely worth more. Your history still counts; it just stops being a veto.</li>
 *   <li>{@code MARKET_ONLY} -- ignore your history entirely and pick from the whole market.</li>
 * </ul>
 *
 * Sent as {@code ?source=}; HISTORY_FIRST is left off the query string entirely, so an untouched
 * config produces exactly the request it produced before this setting existed.
 */
public enum SuggestionSource {
  HISTORY_FIRST, BEST_OF_BOTH, MARKET_ONLY;

  /** RuneLite's config UI renders enum dropdowns using toString(), so this is the visible label. */
  @Override public String toString() {
    switch (this) {
      case BEST_OF_BOTH: return "Best of both";
      case MARKET_ONLY: return "Market only";
      default: return "Your history first";
    }
  }

  /** The value to send as ?source=, or null to leave the parameter off entirely. */
  String param() {
    switch (this) {
      case BEST_OF_BOTH: return "both";
      case MARKET_ONLY: return "market";
      default: return null;
    }
  }
}
