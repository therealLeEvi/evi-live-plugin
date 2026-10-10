package com.evi.live.market;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Wiki's {@code /mapping}: every tradeable item's id, name, Grand Exchange buy limit and members flag.
 * Byte-identical between API versions.
 *
 * <p>PORTED FROM server.mjs {@code itemIndex()} and the first lines of its {@code limitFor}, with their rules kept:
 * <ul>
 *   <li>an entry counts only when it is an object whose {@code id} is a NUMBER ({@code Number.isFinite(i.id)}: a
 *       string id is not one); a repeated id -- the LAST one wins (a {@code Map} built in list order);</li>
 *   <li>the buy limit is known only when it is a finite number above zero; anything else -- absent, null, zero --
 *       is UNKNOWN, which constrains nothing (some items genuinely have no buy limit, and missing is not zero);</li>
 *   <li>members is {@code === true}: absent or anything but the literal true is not members-only.</li>
 * </ul>
 * The list keeps the response's own ORDER, because the market tier walks the mapping in that order and ties keep
 * it (CLAUDE.md: order and ties decide which item spreadRank hands out).
 *
 * <p>Two documented edges the Wiki never sends: an id or limit with a fraction is not an item id or limit here
 * (JavaScript would carry 2.5 through), and an id beyond 2^31-1 is skipped.
 */
public final class ItemCatalog {
  public static final class Item {
    public final int id;
    public final String name;
    /** The raw limit as sent, or null when it was absent, null or not a whole number. */
    private final Long limit;
    private final boolean members;

    Item(int id, String name, Long limit, boolean members) {
      this.id = id;
      this.name = name;
      this.limit = limit;
      this.members = members;
    }

    /** The buy limit when known (above zero), else null: unknown constrains nothing. */
    public Long limit() {
      return limit != null && limit > 0 ? limit : null;
    }

    public boolean members() {
      return members;
    }
  }

  private final List<Item> items;
  private final Map<Integer, Item> byId;

  private ItemCatalog(List<Item> items) {
    this.items = Collections.unmodifiableList(items);
    Map<Integer, Item> m = new HashMap<>(items.size() * 2);
    for (Item i : items) m.put(i.id, i);
    this.byId = m;
  }

  /** The entries in the mapping's own order. */
  public List<Item> items() {
    return items;
  }

  /** The item, or null when the mapping has no entry for it. */
  public Item get(int itemId) {
    return byId.get(itemId);
  }

  /** limitFor's first rule: the item's buy limit when known, null when unknown or the item is not listed. */
  public Long limitFor(int itemId) {
    Item i = byId.get(itemId);
    return i == null ? null : i.limit();
  }

  /** {@code index.get(itemId)?.members === true}. */
  public boolean membersOnly(int itemId) {
    Item i = byId.get(itemId);
    return i != null && i.members;
  }

  public int size() {
    return byId.size();
  }

  public static ItemCatalog parse(String body) throws IOException {
    JsonReader r = new JsonReader(new StringReader(body));
    List<Item> items = new ArrayList<>();
    r.beginArray();
    while (r.hasNext()) {
      if (r.peek() != JsonToken.BEGIN_OBJECT) {
        r.skipValue();
        continue;
      }
      Long id = null;
      String name = null;
      Long limit = null;
      boolean members = false, idIsNumber = false;
      r.beginObject();
      while (r.hasNext()) {
        String field = r.nextName();
        JsonToken t = r.peek();
        if ("id".equals(field) && t == JsonToken.NUMBER) {
          idIsNumber = true;
          id = WikiJson.integral(r.nextString());
        } else if ("name".equals(field) && t == JsonToken.STRING) {
          name = r.nextString();
        } else if ("limit".equals(field) && t == JsonToken.NUMBER) {
          limit = WikiJson.integral(r.nextString());
        } else if ("members".equals(field) && t == JsonToken.BOOLEAN) {
          members = r.nextBoolean();
        } else {
          // a repeated field overwrites, as JSON.parse does: a later non-number id makes the entry not an item
          if ("id".equals(field)) {
            idIsNumber = false;
            id = null;
          } else if ("limit".equals(field)) {
            limit = null;
          } else if ("members".equals(field)) {
            members = false;
          } else if ("name".equals(field)) {
            name = null;
          }
          r.skipValue();
        }
      }
      r.endObject();
      if (idIsNumber && id != null && id >= Integer.MIN_VALUE && id <= Integer.MAX_VALUE) items.add(new Item((int) (long) id, name, limit, members));
    }
    r.endArray();
    return new ItemCatalog(items);
  }
}
