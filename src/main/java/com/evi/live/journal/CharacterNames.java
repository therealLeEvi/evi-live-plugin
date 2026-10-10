package com.evi.live.journal;

import java.util.Locale;
import net.runelite.client.util.Text;

/**
 * Whether two Old School display names are the SAME name, as the game treats them: letter case does not matter, and a space,
 * an underscore, a hyphen and a non-breaking space are one character (RuneLite's own {@link Text#toJagexName}: the client hands
 * the local player's name with non-breaking spaces, and an export may spell it with underscores). Nothing looser: no other
 * character is dropped or merged, so a name that is not the same name never matches -- an imported flip history must never
 * land on another character.
 */
public final class CharacterNames {
  private CharacterNames() {}

  /** The name as compared: RuneLite's Jagex form, lower case. Null or blank: null (matches nothing). */
  public static String key(String name) {
    if (name == null) return null;
    String k = Text.toJagexName(name).toLowerCase(Locale.ROOT);
    return k.isEmpty() ? null : k;
  }

  public static boolean same(String a, String b) {
    String x = key(a), y = key(b);
    return x != null && x.equals(y);
  }
}
