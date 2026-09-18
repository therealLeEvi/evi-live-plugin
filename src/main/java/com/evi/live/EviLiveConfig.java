package com.evi.live;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.ModifierlessKeybind;

// Every description below is the hover tooltip in RuneLite's settings panel, so each is kept to one
// short plain sentence -- the user reported the old ones, some over 700 characters, as unreadable.
// The reasoning and backtest numbers behind each setting live in README.md (the settings reference
// and the changelog), which is where they can be read properly.
//
// Changing `name` or `description` is safe. NEVER change a `keyName`: RuneLite stores settings by
// keyName, so a renamed key silently resets that setting for every existing user.
@ConfigGroup("evilive")
public interface EviLiveConfig extends Config {
  @ConfigItem(
    keyName = "suggestionKeybind",
    name = "Fill suggestion hotkey",
    description = "Fills in EVI's suggested quantity or price while a GE prompt is open. Never confirms an offer.",
    position = 1
  )
  default Keybind suggestionKeybind() {
    return new ModifierlessKeybind(KeyEvent.VK_F8, 0);
  }

  @ConfigItem(
    keyName = "showSuggestionHint",
    name = "Show GE hints",
    description = "Shows EVI's suggestion in the GE offer prompt and highlights the suggested item in search.",
    position = 2
  )
  default boolean showSuggestionHint() {
    return true;
  }

  @ConfigItem(
    keyName = "minProfitTier",
    name = "Min predicted profit",
    description = "Skips suggestions predicted to make less than this. Auto has no minimum.",
    position = 3
  )
  default MinProfitTier minProfitThreshold() {
    return MinProfitTier.AUTO;
  }

  @ConfigItem(
    keyName = "itemBlocklist",
    name = "Item blocklist (item IDs)",
    description = "Item IDs EVI should never suggest, separated by commas, e.g. 4151,995.",
    position = 4
  )
  default String itemBlocklist() {
    return "";
  }

  @ConfigItem(
    keyName = "riskLevel",
    name = "Risk level",
    description = "How much winning history EVI wants before trusting an item. Low is the strictest.",
    position = 5
  )
  // Low by default: measured over 90 days, the old Medium default did no better than picking an
  // eligible item at random, because one lucky flip could carry an item. See the bridge's own default.
  default RiskLevel riskLevel() {
    return RiskLevel.LOW;
  }

  @ConfigItem(
    keyName = "includeMarketSuggestions",
    name = "Include market-wide suggestions",
    description = "When your own trade history has nothing, suggest from the whole GE catalogue.",
    position = 6
  )
  default boolean includeMarketSuggestions() {
    return false;
  }

  @ConfigItem(
    keyName = "tradingProfile",
    name = "Trading profile",
    description = "Starter sticks to cheap, untaxed, fast-selling items. Standard uses the whole catalogue.",
    position = 6
  )
  default TradingProfile tradingProfile() {
    return TradingProfile.STARTER;
  }

  @ConfigItem(
    keyName = "maxTradeShare",
    name = "Max share of cash per trade",
    description = "The most of your cash a single market-wide suggestion may use.",
    position = 7
  )
  default MaxTradeShare maxTradeShare() {
    return MaxTradeShare.QUARTER;
  }

  @ConfigItem(
    keyName = "tradeDuration",
    name = "Target trade duration",
    description = "Prefers trades that can finish within about this long. A rough estimate, not a guarantee.",
    position = 7
  )
  default TradeDuration tradeDuration() {
    return TradeDuration.NONE;
  }

  @ConfigItem(
    keyName = "suggestIdleInventory",
    name = "Suggest selling idle inventory",
    description = "Also suggests selling valuable inventory items EVI never saw you buy.",
    position = 8
  )
  default boolean suggestIdleInventory() {
    return false;
  }

  // Renamed from "Price forecast for suggestions". Calibration over 90 days found the forecast's
  // direction calls wrong more often than chance, so it no longer predicts price at all; what it
  // measurably predicts is whether a trade's sell side fills, and that is all it is now used for
  // (see bridge/fillOutlook.mjs). Same keyName, so existing choices are kept.
  @ConfigItem(
    keyName = "forecastHorizon",
    name = "Exit-risk check",
    description = "Warns when items with a similar recent price pattern often failed to sell in time.",
    position = 9
  )
  default ForecastHorizon forecastHorizon() {
    return ForecastHorizon.OFF;
  }

  // Hidden, not removed: it currently has no effect. "Skip" used to drop a candidate on an
  // unfavourable forecast, but the forecast was measured to be wrong more often than right, so
  // skipping is switched off in the bridge (FORECAST_MAY_DROP_CANDIDATES in suggestions.mjs). A
  // setting that does nothing is the least clear thing a settings panel can show. Kept under its
  // keyName so a stored choice survives, and so it can be unhidden if a recalibrated forecast earns
  // the right to rule trades out again.
  @ConfigItem(
    keyName = "forecastPolicy",
    name = "On an unfavorable forecast",
    description = "Inactive: the exit-risk check only warns and never skips a trade.",
    position = 10,
    hidden = true
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
    description = "Experimental and far too strict: skips almost every trade. Best left off.",
    position = 11
  )
  default boolean marginSafetyCushion() {
    return false;
  }

}
