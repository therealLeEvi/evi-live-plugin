package com.evi.live;
import java.util.EnumSet;
import net.runelite.api.WorldType;

public final class EconomyScopeTest {
  public static void main(String[] args) {
    if(!EconomyScope.of(EnumSet.noneOf(WorldType.class)).isEmpty())throw new AssertionError("Free worlds share ordinary economy");
    if(!EconomyScope.of(EnumSet.of(WorldType.MEMBERS,WorldType.PVP,WorldType.HIGH_RISK)).isEmpty())throw new AssertionError("Ordinary world flags must not split account history");
    for(WorldType type:new WorldType[]{WorldType.DEADMAN,WorldType.SEASONAL,WorldType.FRESH_START_WORLD,WorldType.BETA_WORLD,WorldType.NOSAVE_MODE,WorldType.TOURNAMENT_WORLD}) {
      if(EconomyScope.of(EnumSet.of(type,WorldType.MEMBERS)).isEmpty())throw new AssertionError("Special economy merged into main: "+type);
    }
    if(EconomyScope.of(EnumSet.of(WorldType.DEADMAN)).equals(EconomyScope.of(EnumSet.of(WorldType.DEADMAN,WorldType.SEASONAL))))throw new AssertionError("Seasonal and permanent modes must remain separate");
    if(EconomyScope.of(null).isEmpty())throw new AssertionError("Unknown world is not ordinary economy");
    System.out.println("PASS: ordinary-world identity continuity and special-economy separation");
  }
}
