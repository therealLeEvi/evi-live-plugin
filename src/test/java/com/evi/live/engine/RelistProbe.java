package com.evi.live.engine;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The ported relist advice for ONE sell offer, for tests outside this package (the plugin's one-voice-per-offer test): the
 *  record-level core the bridge runs ({@link Relist#relistAdvice}), not a re-derivation of its rule. */
public final class RelistProbe {
  private RelistProbe() {}

  /**
   * @param price     the offer's own asking price
   * @param market    the live price buyers pay ({@code sellPrice})
   * @param paid      what this account paid per unit, or null when the journal does not know
   * @param minutesUp how long the offer has stood
   * @param target    the player's pace in minutes (the bridge passes 1440 when none is set)
   */
  public static List<AdviceNote> notes(int itemId, String name, double price, double remaining, double market, Double paid, double minutesUp,
                                       double target) {
    double now = 1_000_000_000_000.0;
    Relist.SellOffer offer = new Relist.SellOffer(itemId, name, price, remaining, now - minutesUp * 60_000);
    Map<Integer, AdviceNote.Price> prices = Collections.singletonMap(itemId, new AdviceNote.Price(market, market));
    Map<Integer, Double> basis = paid == null ? Collections.emptyMap() : Collections.singletonMap(itemId, paid);
    return Relist.relistAdvice(Collections.singletonList(offer), prices, basis, target, now);
  }
}
