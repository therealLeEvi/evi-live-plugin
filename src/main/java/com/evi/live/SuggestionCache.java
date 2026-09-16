package com.evi.live;

import javax.inject.Singleton;

/** Holds the most recently fetched suggestion in memory. Written by the poller, read by the hotkey handler. */
@Singleton
class SuggestionCache {
  private volatile Suggestion current;
  Suggestion get() { return current; }
  void set(Suggestion s) { current = s; }
}
