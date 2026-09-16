package com.evi.live;

import java.util.Collection;
import java.util.EnumSet;
import java.util.stream.Collectors;
import net.runelite.api.WorldType;

/** Keep separate game economies from sharing a trade-history identity. */
final class EconomyScope {
  private static final EnumSet<WorldType> SHARED = EnumSet.of(
    WorldType.MEMBERS, WorldType.PVP, WorldType.BOUNTY,
    WorldType.HIGH_RISK, WorldType.SKILL_TOTAL);

  static String of(Collection<WorldType> types) {
    if(types==null)return "unknown";
    // Unrecognized/new flags remain in the namespace rather than silently
    // merging their observations into ordinary-world history.
    return types.stream().filter(t->!SHARED.contains(t)).map(Enum::name)
      .sorted().collect(Collectors.joining(","));
  }
}
