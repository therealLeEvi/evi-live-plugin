package com.evi.live.engine;

import com.evi.live.journal.BuyLimitUsage;
import com.evi.live.market.ItemCatalog;

/**
 * The three item gates one suggestion request builds (bridge/server.mjs GET /api/suggestion: {@code membersBlocked},
 * {@code focusBlocked} and {@code limitFor}), as the {@link Sizing.ItemGate} every buy tier -- personal, market -- clips its
 * candidates through. Pure: the catalogue, the account's view and the clock are handed in.
 *
 * <p>Each gate exists only when the request asks for it, exactly as the bridge builds them:
 * <ul>
 *   <li>members: only on a FREE world ({@code members=0}); an absent flag (before login) blocks nothing;</li>
 *   <li>focus: only when the player's focus is "bulk" or "gear", judged on the item's own buy limit ({@link Focus#focusAllows});</li>
 *   <li>the buy limit: only for a poll that NAMES an account, and only from THAT account's buys ({@link AccountView#buyLimitUsage}):
 *       what can fill NOW, one window's remainder ({@link Sizing#limitFor}). An unknown limit constrains nothing; a poll with
 *       no account -- the login screen, the five ticks after a login, when the cash may already be read -- has no limit
 *       reading at all, never another account's.</li>
 * </ul>
 */
public final class RequestGates {
  private RequestGates() {}

  /**
   * @param catalog the Wiki /mapping (the bridge's {@code itemIndex()}); needed whenever any gate is in force
   * @param freeToPlayWorld {@code members=0} on the request
   * @param focus the resolved focus ({@link Focus#resolveFocus}): "any", "bulk" or "gear"
   * @param view the asking account's view ({@link AccountView#nobody()} for a poll that names none)
   * @param targetDurationMinutes the normalised pace (null: none), for the limit's allowance across windows
   * @param now the request's clock, epoch ms
   */
  public static Sizing.ItemGate of(ItemCatalog catalog, boolean freeToPlayWorld, String focus, AccountView view, Double targetDurationMinutes, long now) {
    boolean focused = !"any".equals(focus);
    boolean limited = view != null && view.account != null;
    if (!freeToPlayWorld && !focused && !limited) return Sizing.ItemGate.NONE;
    if (catalog == null) throw new IllegalArgumentException("the item gates need the item mapping");
    double target = targetDurationMinutes == null ? Double.NaN : targetDurationMinutes;
    return new Sizing.ItemGate() {
      @Override public boolean membersBlocked(int itemId) {
        return freeToPlayWorld && catalog.membersOnly(itemId);
      }

      @Override public boolean focusBlocked(int itemId) {
        if (!focused) return false;
        Long limit = catalog.limitFor(itemId);
        return !Focus.focusAllows(focus, limit == null ? null : (double) limit);
      }

      @Override public Double limitRemaining(int itemId) {
        if (!limited) return null;
        Long limit = catalog.limitFor(itemId);
        if (limit == null) return null; // unknown limit: no constraint
        BuyLimitUsage u = view.buyLimitUsage(itemId);
        Sizing.Limit l = Sizing.limitFor(limit, u.used, u.windowEndsAt == null ? Double.NaN : u.windowEndsAt, target, now);
        return l == null ? null : l.remaining;
      }
    };
  }
}
