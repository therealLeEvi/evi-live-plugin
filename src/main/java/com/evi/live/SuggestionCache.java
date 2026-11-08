package com.evi.live;

import java.util.Collections;
import java.util.List;
import javax.inject.Singleton;

/** Holds the most recently fetched suggestion in memory. Written by the poller, read by the hotkey handler.
 *
 *  Also holds the "Also worth buying" positions (2 Nov 2026): without them the GE prompt for a second or
 *  third position fell through to the plain open-item price, which carries no quantity, so the price filled
 *  and the quantity did not. */
@Singleton
class SuggestionCache {
  private volatile Suggestion current;
  private volatile List<Suggestion> extras = Collections.emptyList();
  Suggestion get() { return current; }
  void set(Suggestion s) { current = s; }
  List<Suggestion> extras() { return extras; }
  void setExtras(List<Suggestion> list) { extras = list == null ? Collections.emptyList() : Collections.unmodifiableList(new java.util.ArrayList<>(list)); }
}
