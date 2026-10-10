package com.evi.live.engine;

import com.evi.live.journal.FifoMatcher;
import com.evi.live.journal.Offer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The eight Grand Exchange slots (bridge/suggestions.mjs slotCapacity, slotExposure, slotNote). Pure.
 *
 * <p>THE PORT WARNING, honoured: the JS {@code slotExposure} scopes with {@code !account || x.account === account}, so
 * called with no account it counts EVERY account's positions and offers, and it is safe only because server.mjs
 * filters both lists with {@code ownedBy} first (6 Oct: a pure account's restock showed up as the main's holding).
 * This port scopes ITSELF with the server's rule: no account (null or empty) owns nothing. EngineVectorsTest compares
 * it with the JS answer on the lists server.mjs would actually pass.
 */
public final class GeSlots {
  private GeSlots() {}

  /** GE_SLOTS. */
  public static final int GE_SLOTS = 8;

  /** slotCapacity's answer. {@code free}/{@code collectable} are null when the plugin sent no usable count. */
  public static final class Capacity {
    public final Integer free;
    public final Integer collectable;
    /** Every slot occupied and none holds a finished offer: nothing can be placed. */
    public final boolean full;
    /** Every slot occupied, but collecting one is a click away: ranking continues with a note. */
    public final boolean tight;

    public Capacity(Integer free, Integer collectable, boolean full, boolean tight) {
      this.free = free;
      this.collectable = collectable;
      this.full = full;
      this.tight = tight;
    }
  }

  /** A raw query value as a slot count: a whole number from 0 to 8 as {@code Number()} reads it, else null. */
  static Integer count(String raw) {
    if (raw == null || raw.isEmpty()) return null;
    double n = JsValues.toNumber(raw);
    return JsValues.isInteger(n) && n >= 0 && n <= GE_SLOTS ? Integer.valueOf((int) n) : null;
  }

  /** slotCapacity, from the two raw counts the plugin sends (null when absent). Unknown never invents a constraint. */
  public static Capacity slotCapacity(String freeRaw, String collectableRaw) {
    Integer free = count(freeRaw), collectable = count(collectableRaw);
    boolean freeZero = free != null && free == 0;
    boolean full = freeZero && collectable != null && collectable == 0;
    return new Capacity(free, collectable, full, freeZero && !full);
  }

  /** One sentence for a tight GE, or null when there is nothing to say. */
  public static String slotNote(Capacity capacity) {
    if (capacity == null || !capacity.tight) return null;
    Integer n = capacity.collectable;
    return n != null && n > 0
      ? "All " + GE_SLOTS + " GE slots are in use, but " + n + " finished offer" + (n == 1 ? "" : "s") + " can be collected to free one."
      : "All " + GE_SLOTS + " GE slots are in use -- you will need to collect or cancel an offer before placing this.";
  }

  /** slotExposure's answer. */
  public static final class Exposure {
    /** Every item the account is committed to, held or being bought, in the JS Set's insertion order. */
    public final Set<Integer> exposure;
    /** Held stock with no slot of its own, in first-seen order. */
    public final List<Integer> owedItems;
    public final int sellSlotsOwed;

    Exposure(Set<Integer> exposure, List<Integer> owedItems) {
      this.exposure = Collections.unmodifiableSet(exposure);
      this.owedItems = Collections.unmodifiableList(owedItems);
      this.sellSlotsOwed = owedItems.size();
    }
  }

  /** server.mjs's {@code ownedBy}: a real account, and this record is that account's. */
  static boolean ownedBy(String account, String recordAccount) {
    return account != null && !account.isEmpty() && account.equals(recordAccount);
  }

  /**
   * slotExposure for ONE account: what it is committed to (open positions and offers buying), and how many sells
   * need a slot they do not already have -- stock held with no offer in any slot. A buy vacates its own slot when
   * collected and the sell goes straight into it, so anything in a slot brings its own slot with it.
   *
   * <p>PACKAGE-PRIVATE: the kernel the JS vectors call with the lists the bridge passes. Production reads it through
   * {@link AccountView#slotExposure}, which can only hand it one account's own lots and slots.
   */
  static Exposure slotExposure(List<FifoMatcher.OpenPosition> openPositions, List<Offer> occupiedOffers, String account) {
    List<Integer> held = new ArrayList<>();
    if (openPositions != null) for (FifoMatcher.OpenPosition p : openPositions) if (p != null && ownedBy(account, p.account)) held.add(p.itemId);
    Set<Integer> inASlot = new HashSet<>();
    List<Integer> buying = new ArrayList<>();
    if (occupiedOffers != null) for (Offer o : occupiedOffers) {
      if (o == null || !ownedBy(account, o.account)) continue;
      inASlot.add(o.itemId);
      if ("BUYING".equals(o.state) || "BOUGHT".equals(o.state)) buying.add(o.itemId);
    }
    List<Integer> owed = new ArrayList<>();
    for (Integer id : new LinkedHashSet<>(held)) if (!inASlot.contains(id)) owed.add(id);
    Set<Integer> exposure = new LinkedHashSet<>(held);
    exposure.addAll(buying);
    return new Exposure(exposure, owed);
  }
}
