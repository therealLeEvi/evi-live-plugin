package com.evi.live;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.ModifierlessKeybind;

// Every description below is the hover tooltip in RuneLite's settings panel, so each is kept to one
// short plain sentence -- the user reported the old ones, some over 700 characters, as unreadable.
// The reasoning and backtest numbers behind each setting live in README.md (the settings reference
// and the changelog), which is where they can be read properly.
//
// Changing `name` or `description` is safe. NEVER change a `keyName`: RuneLite stores settings by
// keyName, so a renamed key silently resets that setting for every existing user. Moving an item
// into a @ConfigSection or renumbering its position is safe for the same reason -- neither is part
// of the stored key, so every setting below keeps whatever the player already chose.
//
// The four sections were added on 28 Sept 2026 because the panel had grown to seventeen settings in
// one flat list with no grouping at all and -- worse -- with DUPLICATE position numbers: 6 was used
// three times (market-wide, trading profile, suggest from) and 7 three times (max share, trade
// duration, trade pace). RuneLite sorts by position, so those six appeared in an arbitrary order.
// Every position below is unique, numbered in tens by section so a new setting can be slotted
// between two existing ones without renumbering anything.
//
// The ordering principle: the first section holds what a player actually adjusts, the second decides
// where picks come from, and the last two are collapsed because they are either experimental or
// set-once.
@ConfigGroup("evilive")
public interface EviLiveConfig extends Config {

  @ConfigSection(
    name = "What to trade",
    description = "The size, pace and caution of the trades EVI will offer you.",
    position = 10
  )
  String tradeSection = "tradeSection";

  @ConfigSection(
    name = "Where suggestions come from",
    description = "Whether EVI ranks from your own history, the whole market, or both.",
    position = 20
  )
  String sourceSection = "sourceSection";

  @ConfigSection(
    name = "Safety checks",
    description = "Extra warnings. Off by default; each one is opt-in for a measured reason.",
    position = 30,
    closedByDefault = true
  )
  String checksSection = "checksSection";

  @ConfigSection(
    name = "Display and controls",
    description = "The hotkey, the GE overlay and the sidebar colours. Nothing here changes what is suggested.",
    position = 40,
    closedByDefault = true
  )
  String displaySection = "displaySection";

  // ------------------------------------------------------------------ What to trade

  @ConfigItem(
    keyName = "minProfitTier",
    name = "Min predicted profit",
    description = "Skips suggestions predicted to make less than this. Auto scales with your cash stack.",
    section = tradeSection,
    position = 11
  )
  default MinProfitTier minProfitThreshold() {
    return MinProfitTier.AUTO;
  }

  @ConfigItem(
    keyName = "tradePace",
    name = "Trade pace",
    description = "How long you will wait for one trade. Under two hours, round trips rarely finish.",
    section = tradeSection,
    position = 12
  )
  default TradePace tradePace() {
    return TradePace.NONE;
  }

  @ConfigItem(
    keyName = "riskLevel",
    name = "Risk level",
    description = "How much winning history EVI wants before trusting an item. Low is the strictest.",
    section = tradeSection,
    position = 13
  )
  // Low by default: measured over 90 days, the old Medium default did no better than picking an
  // eligible item at random, because one lucky flip could carry an item. See the bridge's own default.
  default RiskLevel riskLevel() {
    return RiskLevel.LOW;
  }

  @ConfigItem(
    keyName = "maxTradeShare",
    name = "Max share of cash per trade",
    description = "The most of your cash a single market-wide suggestion may use.",
    section = tradeSection,
    position = 14
  )
  default MaxTradeShare maxTradeShare() {
    return MaxTradeShare.QUARTER;
  }

  // Default follows the scanner's own Focus switch, so an existing player sees no change.
  @ConfigItem(
    keyName = "suggestionFocus",
    name = "Suggestion focus",
    description = "Which items EVI suggests buying: gear, bulk consumables and ammo, or all. Sales are unaffected.",
    section = tradeSection,
    position = 15
  )
  default SuggestionFocus suggestionFocus() {
    return SuggestionFocus.SAME_AS_SCANNER;
  }

  // New keyName, default ONE, so nothing changes for anyone who does not go looking for it. A large
  // cash stack is the only reason this exists: at 89m the market tier's median suggestion commits
  // about 3.6m and no ranking change moves that, so the rest can only be deployed through further
  // positions. Each one is ranked on the cash left after the ones before it, never on a split stack.
  @ConfigItem(
    keyName = "maxPositions",
    name = "Suggestions at once",
    description = "For a large cash stack. Each extra pick uses only the cash the earlier ones left.",
    section = tradeSection,
    position = 16
  )
  default MaxPositions maxPositions() {
    return MaxPositions.ONE;
  }

  // New keyName, default CAREFUL, so nothing changes for anyone who does not choose it. Measured
  // before it was offered: see PositionSizing and the 28 Sept 2026 README entry.
  @ConfigItem(
    keyName = "positionSizing",
    name = "Position sizing",
    description = "Bigger buys more of thinly traded items. More profit per trade, slightly more risk.",
    section = tradeSection,
    position = 17
  )
  default PositionSizing positionSizing() {
    return PositionSizing.CAREFUL;
  }

  @ConfigItem(
    keyName = "tradeDuration",
    name = "Target trade duration",
    description = "Replaced by Trade pace. Kept so nothing you chose is lost; it no longer has any effect.",
    section = tradeSection,
    position = 19,
    hidden = true
  )
  default TradeDuration tradeDuration() {
    return TradeDuration.NONE;
  }

  // --------------------------------------------------- Where suggestions come from

  @ConfigItem(
    keyName = "suggestionSource",
    name = "Suggest from",
    description = "Your own history first, the best of both, or the whole market ignoring your history.",
    section = sourceSection,
    position = 21
  )
  default SuggestionSource suggestionSource() {
    return SuggestionSource.HISTORY_FIRST;
  }

  @ConfigItem(
    keyName = "includeMarketSuggestions",
    name = "Include market-wide suggestions",
    description = "When your own trade history has nothing, suggest from the whole GE catalogue.",
    section = sourceSection,
    position = 22
  )
  default boolean includeMarketSuggestions() {
    return false;
  }

  @ConfigItem(
    keyName = "tradingProfile",
    name = "Trading profile",
    description = "Starter sticks to cheap, untaxed, fast-selling items. Standard uses the whole catalogue.",
    section = sourceSection,
    position = 23
  )
  default TradingProfile tradingProfile() {
    return TradingProfile.STARTER;
  }

  @ConfigItem(
    keyName = "suggestIdleInventory",
    name = "Suggest selling idle inventory",
    description = "Also suggests selling valuable inventory items EVI never saw you buy.",
    section = sourceSection,
    position = 24
  )
  default boolean suggestIdleInventory() {
    return false;
  }

  @ConfigItem(
    keyName = "itemBlocklist",
    name = "Item blocklist (item IDs)",
    description = "Item IDs never to suggest, e.g. 4151,995. Easier: the sidebar's Block icon.",
    section = sourceSection,
    position = 25
  )
  default String itemBlocklist() {
    return "";
  }

  // ------------------------------------------------------------------ Safety checks

  // Renamed from "Price forecast for suggestions". Calibration over 90 days found the forecast's
  // direction calls wrong more often than chance, so it no longer predicts price at all; what it
  // measurably predicts is whether a trade's sell side fills, and that is all it is now used for
  // (see bridge/fillOutlook.mjs). Same keyName, so existing choices are kept.
  @ConfigItem(
    keyName = "forecastHorizon",
    name = "Exit-risk check",
    description = "Warns when items with a similar recent price pattern often failed to sell in time.",
    section = checksSection,
    position = 31
  )
  default ForecastHorizon forecastHorizon() {
    return ForecastHorizon.OFF;
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
    section = checksSection,
    position = 32
  )
  default boolean marginSafetyCushion() {
    return false;
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
    section = checksSection,
    position = 33,
    hidden = true
  )
  default ForecastPolicy forecastPolicy() {
    return ForecastPolicy.WARN;
  }

  // ----------------------------------------------------------- Display and controls

  @ConfigItem(
    keyName = "showSuggestionHint",
    name = "Show GE hints",
    description = "Shows EVI's suggestion in the GE offer prompt and highlights the suggested item in search.",
    section = displaySection,
    position = 41
  )
  default boolean showSuggestionHint() {
    return true;
  }

  @ConfigItem(
    keyName = "suggestionKeybind",
    name = "Fill suggestion hotkey",
    description = "Fills in EVI's suggested quantity or price while a GE prompt is open. Never confirms an offer.",
    section = displaySection,
    position = 42
  )
  default Keybind suggestionKeybind() {
    return new ModifierlessKeybind(KeyEvent.VK_F8, 0);
  }

  // Colours only: nothing about what is suggested, warned about or sent depends on this. Default is
  // the look the panel has always had, so an existing player sees no change unless they pick one.
  @ConfigItem(
    keyName = "panelTheme",
    name = "Panel colours",
    description = "Colours for EVI's sidebar: RuneLite's own, or the dashboard's Old School parchment.",
    section = displaySection,
    position = 43
  )
  default PanelTheme panelTheme() {
    return PanelTheme.RUNELITE;
  }
}
