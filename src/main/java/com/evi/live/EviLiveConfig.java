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
    keyName = "minProfitTier",
    name = "Min predicted profit",
    description = "Suggestions below this predicted profit (current buy/sell margin x suggested quantity) are skipped. Auto applies no floor at all -- useful if you want EVI to also point out thin-margin, high-volume flips, which is exactly what a low-capital account often needs. Replaces the old free-form gp field; if you had that set, pick the closest tier here.",
    position = 3
  )
  default MinProfitTier minProfitThreshold() {
    return MinProfitTier.AUTO;
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
    keyName = "tradingProfile",
    name = "Trading profile",
    description = "Starter restricts market-wide suggestions to items the Grand Exchange charges no tax on -- cheap, heavily traded things that sell quickly. Backtested over 90 days on a 2m stack it completed 325 trades instead of 220, won 98% instead of 85%, and left 5% of capital stuck instead of 29%, with a far smaller worst case. Each trade earns less, so this is a way to learn the mechanics and grow steadily rather than to make a fortune quickly. Standard searches the whole catalogue. Either way, suggestions from your own flip history are unaffected.",
    position = 6
  )
  default TradingProfile tradingProfile() {
    return TradingProfile.STARTER;
  }

  @ConfigItem(
    keyName = "maxTradeShare",
    name = "Max share of cash per trade",
    description = "Limits how much of your cash stack one market-wide suggestion may commit, so a single slow-selling item can't tie up everything you have. Replaying 90 days of real prices through EVI's own ranking, uncapped market-wide suggestions lost around 93m gp -- almost entirely from expensive items bought with nearly the whole stack and still unsold a day later -- while capping each trade at a quarter of the stack turned the same 90 days positive, with no fewer suggestions. It never withholds a suggestion; it only makes it smaller, and says so. Applies to market-wide picks only: suggestions from your own flip history keep the size your own trading history implies. Set to No limit for the old behaviour.",
    position = 7
  )
  default MaxTradeShare maxTradeShare() {
    return MaxTradeShare.QUARTER;
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

  @ConfigItem(
    keyName = "suggestIdleInventory",
    name = "Suggest selling idle inventory",
    description = "When nothing else has a suggestion (no active hold, no profitable flip history), scan your current inventory for anything worth roughly 100k gp or more with no active GE offer, and suggest selling it -- even if EVI never saw you buy it (a drop, a quest reward, or anything acquired before this bridge started watching). Off by default: EVI's suggestions stay limited to things it actually observed you trade, as before.",
    position = 8
  )
  default boolean suggestIdleInventory() {
    return false;
  }

  @ConfigItem(
    keyName = "forecastHorizon",
    name = "Price forecast for suggestions",
    description = "Loads the OSRS Wiki's recent price history for EVI's suggested buy and runs the same momentum/volume forecast the scanner's own Predict button uses, over roughly this horizon, before showing the suggestion -- so a swing against you has a chance to be caught before you commit capital, not just after. Off by default: suggestions carry no forecast and cost no extra Wiki API call, exactly as before this setting existed. Never checked for a \"sell what you're already holding\" reminder -- there's no buy decision left to forecast there.",
    position = 9
  )
  default ForecastHorizon forecastHorizon() {
    return ForecastHorizon.OFF;
  }

  @ConfigItem(
    keyName = "forecastPolicy",
    name = "On an unfavorable forecast",
    description = "Only checked when \"Price forecast for suggestions\" above isn't Off. Warn: keep the suggestion, with the forecast folded into its reasoning text so you can weigh it yourself. Skip: drop that candidate entirely and rank the next-best one instead, retrying a few times before falling back exactly as if forecasting were off.",
    position = 10
  )
  default ForecastPolicy forecastPolicy() {
    return ForecastPolicy.WARN;
  }

  // Off by default, under a NEW keyName. It originally shipped on by default under keyName
  // "marginSafetyCushion", and RuneLite writes every default into the stored profile the first time
  // the plugin loads -- so just flipping the default under the old key would have left anyone who'd
  // already run that build stuck with a stored "true". Measured against live Wiki data, that check
  // blocked every candidate (0 of 40 market-wide picks, 0 of 5 personal-history picks passed,
  // including a real, high-volume thin-margin flip) and the bridge then returned no suggestion at
  // all: its volatility estimate counts the ordinary bounce between an item's buy and sell prices
  // (the very margin being flipped) as price movement, and scales it over a fixed 2-hour window.
  // Stays opt-in until that measurement is redesigned -- see the 2026-09-17 README entry.
  @ConfigItem(
    keyName = "requireMarginAboveNoise",
    name = "Require margin above price noise",
    description = "EXPERIMENTAL -- currently far too strict: in testing against live prices it skipped almost every candidate, including stable high-volume flips, which can leave you with no suggestion at all. Before suggesting a buy, checks the item's own recent price history and skips it (trying the next-best pick instead) if the predicted margin is thinner than that specific item's measured short-term price wobble. Off by default until its measurement is recalibrated.",
    position = 11
  )
  default boolean marginSafetyCushion() {
    return false;
  }

}
