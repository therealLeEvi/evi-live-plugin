package com.evi.live;

import java.util.Locale;

/** Validates only local bridge tokens, never Jagex credentials. */
final class PairingKey {
  private PairingKey() {}

  static String normalize(String value) {
    String key = value == null ? "" : value.replace("﻿", "").trim().toLowerCase(Locale.ROOT);
    if (!key.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Paste only the 64-character RuneLite plugin key from EVI's bridge window.");
    }
    return key;
  }
}
