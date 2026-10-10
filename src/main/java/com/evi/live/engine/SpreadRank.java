package com.evi.live.engine;

/**
 * spreadRankFor (bridge/suggestions.mjs): which candidate from the top of the ranking this account is handed, so
 * that every user with the same settings is not pointed at the same item in the same hour (crowding, 3 Oct). Pure.
 *
 * <p>STABLE PER ACCOUNT: FNV-1a over the account id, no randomness and no state, so the same account gets the same
 * offset on every poll.
 *
 * <p>THE HASH IS AN UNSIGNED 32-BIT VALUE, and that is the trap. The JS does {@code Math.imul(h, prime) >>> 0} and
 * then {@code h % n} on a NON-NEGATIVE number. Java's {@code int} arithmetic gives the same bits, but a plain
 * {@code h % n} on a Java int is a SIGNED remainder: for about half of all accounts it would be a different (even
 * negative) bucket, and each of those players would quietly be handed a different pick from today. So the bucket is
 * {@link Integer#remainderUnsigned}. The account id is trimmed as {@code String.prototype.trim} trims
 * ({@link JsValues#trim}), not as {@link String#trim} does.
 */
public final class SpreadRank {
  private SpreadRank() {}

  private static final int FNV_OFFSET = 0x811c9dc5;
  private static final int FNV_PRIME = 0x01000193;

  /** The FNV-1a hash of the id's UTF-16 code units, as JS computes it (the bits of the unsigned value). */
  public static int fnv1a(String id) {
    int h = FNV_OFFSET;
    for (int i = 0; i < id.length(); i++) {
      h ^= id.charAt(i);
      h *= FNV_PRIME; // Math.imul: the low 32 bits of the product
    }
    return h;
  }

  /**
   * The rank (0 = the top pick) for this account under a spread of {@code spread} candidates. 0 for a spread of 1 or
   * less, a non-finite spread, or no account id.
   */
  public static long spreadRankFor(String accountId, double spread) {
    double n = JsValues.isFinite(spread) ? Math.floor(spread) : 1;
    if (n <= 1) return 0;
    String id = JsValues.trim(accountId == null ? "" : accountId);
    if (id.isEmpty()) return 0;
    int h = fnv1a(id);
    // n is a whole number of at least 2. Past 2^32 - 1 it exceeds every hash, so the remainder is the hash itself.
    if (n > 4294967295.0) return Integer.toUnsignedLong(h);
    return Integer.toUnsignedLong(Integer.remainderUnsigned(h, (int) (long) n));
  }
}
