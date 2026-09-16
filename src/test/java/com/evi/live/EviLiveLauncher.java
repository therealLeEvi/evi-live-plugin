package com.evi.live;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
public class EviLiveLauncher {
  public static void main(String[] args) throws Exception {
    ExternalPluginManager.loadBuiltin(EviLivePlugin.class);
    RuneLite.main(args);
  }
}
