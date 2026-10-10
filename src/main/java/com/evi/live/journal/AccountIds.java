package com.evi.live.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The account id EVI journals under: SHA-256 of {@code salt:profile} (plus {@code :economy:<scope>} on a separate-economy world),
 * as lowercase hex. ONE formula, used by the plugin at login and by the import, which recognises the records a former data
 * folder wrote by that folder's own salt ({@code import/identity-salt.txt}, see {@link PluginJournal}). Pure.
 */
public final class AccountIds {
  private AccountIds() {}

  public static String of(String salt, String profile, String economy) {
    String identity = salt + ":" + profile + (economy == null || economy.isEmpty() ? "" : ":economy:" + economy);
    byte[] hash;
    try {
      hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    StringBuilder h = new StringBuilder(64);
    for (byte b : hash) h.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
    return h.toString();
  }
}
