package com.evi.live;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.ModifierlessKeybind;

@ConfigGroup("evilive")
public interface EviLiveConfig extends Config {
  @ConfigItem(
    keyName = "suggestionKeybind",
    name = "Fill suggestion hotkey",
    description = "While a GE quantity or price prompt is open, fills in EVI's suggested value for that one field. Never opens, selects an item for, or confirms an offer by itself.",
    position = 1
  )
  default Keybind suggestionKeybind() {
    return new ModifierlessKeybind(KeyEvent.VK_F8, 0);
  }

  @ConfigItem(
    keyName = "showSuggestionHint",
    name = "Show suggestion hint in chatbox",
    description = "While a GE quantity or price prompt is open, shows a text line naming EVI's suggested value and the hotkey that fills it (now works for ANY item you're buying or selling, not only EVI's own top pick -- see LOCAL-API.md). Also highlights EVI's suggested item's own row in the GE item-search results list while you're searching, and adds a clickable 'EVI item: <name>' row there (same technique, same search widget, Flipping Copilot's own published Plugin Hub listing uses for its equivalent row) that jumps straight to the suggested item on click or Enter, same as Copilot's does. Turning this off disables all of the above.",
    position = 2
  )
  default boolean showSuggestionHint() {
    return true;
  }

  @ConfigItem(
    keyName = "minProfitThreshold",
    name = "Min predicted profit (gp)",
    description = "Suggestions below this predicted profit (current buy/sell margin x suggested quantity) are skipped. 0 disables the filter.",
    position = 3
  )
  default int minProfitThreshold() {
    return 0;
  }

  @ConfigItem(
    keyName = "itemBlocklist",
    name = "Item blocklist (item IDs)",
    description = "Comma-separated item IDs EVI should never suggest, e.g. 4151,995. Leave blank for no blocklist. Find an item's ID with the scanner's Item lookup.",
    position = 4
  )
  default String itemBlocklist() {
    return "";
  }

  @ConfigItem(
    keyName = "riskLevel",
    name = "Risk level",
    description = "Tunes ranking using your own win-rate history (not real market volatility, which this free tier doesn't track). Low: a longer, more consistent winning history required. Medium: the original balance. High: accepts a thinner track record and leans more on raw average profit.",
    position = 5
  )
  default RiskLevel riskLevel() {
    return RiskLevel.MEDIUM;
  }

  @ConfigItem(
    keyName = "includeMarketSuggestions",
    name = "Include market-wide suggestions",
    description = "When you have no reviewed flip yet that's currently profitable, fall back to a market-wide pick ranked purely by current margin and trading volume across the whole item catalogue -- not just items you've flipped before. Off by default: suggestions stay limited to your own reviewed flip history, as before.",
    position = 6
  )
  default boolean includeMarketSuggestions() {
    return false;
  }

  @ConfigItem(
    keyName = "tradeDuration",
    name = "Target trade duration",
    description = "Prefer trades that can realistically complete within about this long, estimated from the OSRS Wiki price API's own recent trading-volume data for each item -- not a guarantee, just a rough sanity check. A candidate too slow-moving even for one unit within this window is skipped; one that's only realistic at a smaller quantity gets sized down instead. No preference (the default) leaves suggestions exactly as before this setting existed.",
    position = 7
  )
  default TradeDuration tradeDuration() {
    return TradeDuration.NONE;
  }

}
