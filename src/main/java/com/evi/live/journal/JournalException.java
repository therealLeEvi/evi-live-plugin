package com.evi.live.journal;

/**
 * A refusal from the journal engine. The message is the exact sentence store.mjs throws, because the
 * bridge sends it to the player as {@code {error: message}} and the wording is a considered thing.
 */
public final class JournalException extends RuntimeException {
  public JournalException(String message) {
    super(message);
  }
}
