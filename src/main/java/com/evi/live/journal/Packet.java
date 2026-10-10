package com.evi.live.journal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One snapshot of the eight GE slots from the plugin, after store.mjs's {@code validatePacket}.
 *
 * <p>{@link #validate} takes the JSON TREE, not a typed object, on purpose: its checks are about JSON
 * types and JS number semantics ({@code 1.0} is the integer 1, a string "5" is not a number, an absent
 * {@code ticksToFill} is fine but {@code null} is not), and the same tree is what a journal line holds.
 * A plugin building packets in-process converts to the tree with the injected Gson and goes through the
 * same single validation path.
 *
 * <p>THE INT32 CAP IS KEPT AS THE JS ENGINE HAS IT, and it is an open decision rather than an
 * endorsement: price, total, filled and spent are refused above 2,147,483,647, so since Beyond Max Cash a
 * unit priced over max cash (or a spend past it) makes the WHOLE packet invalid. Recorded by the golden
 * fixtures max-cash-unit-price-over-int32 and max-cash-spent-overflow. Raising it is the maintainer's call and must
 * happen in JS first.
 */
public final class Packet {
  public final int version;
  public final String session;
  public final String account;
  public final long seq;
  public final long ts;
  public final boolean loggedIn;
  public final List<SlotOffer> offers;

  public Packet(int version, String session, String account, long seq, long ts, boolean loggedIn, List<SlotOffer> offers) {
    this.version = version;
    this.session = session;
    this.account = account;
    this.seq = seq;
    this.ts = ts;
    this.loggedIn = loggedIn;
    this.offers = Collections.unmodifiableList(new ArrayList<>(offers));
  }

  /** One slot of a packet, exactly the eleven fields validatePacket keeps. */
  public static final class SlotOffer {
    public final int slot;
    public final String state;
    public final String offerId;
    public final int itemId;
    public final String name;
    public final long price;
    public final long total;
    public final long filled;
    public final long spent;
    public final boolean knownStart;
    /** -1 for "no reading" from a validated packet; null only in a journal line written before the field existed. */
    public final Integer ticksToFill;

    public SlotOffer(int slot, String state, String offerId, int itemId, String name, long price, long total, long filled,
                     long spent, boolean knownStart, Integer ticksToFill) {
      this.slot = slot;
      this.state = state;
      this.offerId = offerId;
      this.itemId = itemId;
      this.name = name;
      this.price = price;
      this.total = total;
      this.filled = filled;
      this.spent = spent;
      this.knownStart = knownStart;
      this.ticksToFill = ticksToFill;
    }

    public boolean empty() {
      return "EMPTY".equals(state);
    }
  }

  public static final Set<String> STATES = Set.of("EMPTY", "BUYING", "SELLING", "BOUGHT", "SOLD", "CANCELLED_BUY", "CANCELLED_SELL");
  private static final Pattern ID = Pattern.compile("[a-zA-Z0-9-]{1,80}");

  /** store.mjs id(): a string of 1-80 letters, digits or hyphens. */
  public static boolean id(JsonElement e) {
    return Js.isString(e) && ID.matcher(e.getAsString()).matches();
  }

  /** store.mjs id() for a value already known to be a string (or null). */
  public static boolean id(String s) {
    return s != null && ID.matcher(s).matches();
  }

  /** store.mjs validatePacket: the same checks, in the same order, with the same messages. */
  public static Packet validate(JsonElement input) {
    if (input == null || !input.isJsonObject()) throw new JournalException("Invalid packet");
    JsonObject p = input.getAsJsonObject();
    Double version = Js.number(p.get("version"));
    if (version == null || version != 1 || !id(p.get("session")) || !id(p.get("account"))
        || !Js.integer(p.get("seq"), 1, Js.MAX_SAFE) || !Js.integer(p.get("ts"), 1, Js.MAX_SAFE)
        || !Js.isBoolean(p.get("loggedIn")) || p.get("offers") == null || !p.get("offers").isJsonArray()
        || p.getAsJsonArray("offers").size() > 8) throw new JournalException("Invalid packet");
    JsonArray raw = p.getAsJsonArray("offers");
    Set<Integer> slots = new HashSet<>();
    List<SlotOffer> offers = new ArrayList<>();
    for (JsonElement e : raw) {
      if (e == null || !e.isJsonObject()) throw new JournalException("Invalid offer");
      JsonObject o = e.getAsJsonObject();
      if (!Js.integer(o.get("slot"), 0, 7) || slots.contains(slot(o)) || !Js.isString(o.get("state"))
          || !STATES.contains(o.get("state").getAsString()) || !id(o.get("offerId"))
          || !Js.integer(o.get("itemId")) || !Js.integer(o.get("price")) || !Js.integer(o.get("total"))
          || !Js.integer(o.get("filled")) || Js.number(o.get("filled")) > Js.number(o.get("total"))
          || !Js.integer(o.get("spent")) || !Js.isBoolean(o.get("knownStart")) || !Js.isString(o.get("name"))
          || o.get("name").getAsString().length() > 150) throw new JournalException("Invalid offer");
      String state = o.get("state").getAsString();
      if (!"EMPTY".equals(state) && (Js.number(o.get("itemId")) == 0 || Js.number(o.get("total")) == 0 || Js.number(o.get("price")) == 0))
        throw new JournalException("Invalid nonempty offer");
      // Optional: older plugin builds do not send it. Absent is fine; null is not (null !== undefined).
      if (o.has("ticksToFill") && !Js.integer(o.get("ticksToFill"), -1, 1000000)) throw new JournalException("Invalid ticksToFill");
      slots.add(slot(o));
      offers.add(new SlotOffer(slot(o), state, o.get("offerId").getAsString(), (int) (double) Js.number(o.get("itemId")),
        o.get("name").getAsString(), (long) (double) Js.number(o.get("price")), (long) (double) Js.number(o.get("total")),
        (long) (double) Js.number(o.get("filled")), (long) (double) Js.number(o.get("spent")), o.get("knownStart").getAsBoolean(),
        o.has("ticksToFill") ? (int) (double) Js.number(o.get("ticksToFill")) : -1));
    }
    boolean loggedIn = p.get("loggedIn").getAsBoolean();
    if (loggedIn && offers.size() != 8) throw new JournalException("Expected all eight slots");
    return new Packet(1, p.get("session").getAsString(), p.get("account").getAsString(), (long) (double) Js.number(p.get("seq")),
      (long) (double) Js.number(p.get("ts")), loggedIn, offers);
  }

  // Only ever called once the slot is known to be a safe integer in 0..7 (or for the duplicate check,
  // where a non-number simply cannot collide: Js.integer above has already failed for it).
  private static Integer slot(JsonObject o) {
    Double d = Js.number(o.get("slot"));
    return d == null ? null : (int) (double) d;
  }
}
