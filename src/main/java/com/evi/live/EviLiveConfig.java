package com.evi.live;

import com.evi.live.journal.PluginFolder;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.ModifierlessKeybind;

// Every description below is the hover tooltip in RuneLite's settings panel, so each is kept to one
// short plain sentence -- a player reported the old ones, some over 700 characters, as unreadable.
// The reasoning and backtest numbers behind each setting live in the project notes (the settings
// reference and the changelog), which is where they can be read properly.
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
// (That list is the 28 Sept state. "Trading profile" has since been HIDDEN, 6 Oct 2026: it keeps its
// keyName and position so a stored value still loads, but no longer appears in the panel. The same
// day "riskLevel" and "includeMarketSuggestions" were retired the same way, each replaced by an item
// under a NEW keyName -- riskLevelV2 and includeMarketWide -- so that every player, not only fresh
// installs, starts at Low with market-wide suggestions on.)
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

  // NEW keyName on 6 Oct 2026 (the maintainer's decision), so EVERY player -- new or existing -- starts at Low
  // and chooses a level knowingly. The old "riskLevel" item below only ever tuned the history tier;
  // carrying a stored High over under the same key would have silently become High on the market
  // tier too. First in the section because it is now the one trading choice a player makes. The
  // sidebar's three buttons write this same key, so the panel and this setting cannot disagree.
  // HIDDEN since 7 Oct 2026, with the sidebar's buttons, until the levels are calibrated on live sells
  // (RiskLevel.SHOWN is the one switch). keyName and return type are unchanged, so a value stored while it was
  // visible still loads; while hidden the request never sends it. The description names no odds, permanently.
  @ConfigItem(
    keyName = "riskLevelV2",
    name = "Risk level",
    description = "How much risk you accept. Higher levels add slower or more volatile items.",
    section = tradeSection,
    position = 10,
    hidden = !RiskLevel.SHOWN
  )
  default RiskLevel riskLevelV2() {
    return RiskLevel.LOW;
  }

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

  // RETIRED 6 Oct 2026, replaced by riskLevelV2 above. Kept as a hidden item under the same keyName
  // and return type so a stored LOW/MEDIUM/HIGH is still a valid value for the config loader (the
  // project's rule for config changes). NOTHING reads it: suggestionQuery sends riskLevelV2 only.
  @ConfigItem(
    keyName = "riskLevel",
    name = "Risk level (old)",
    description = "Replaced by Risk level. Kept so nothing you chose is lost; it no longer has any effect.",
    section = tradeSection,
    position = 18,
    hidden = true
  )
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

  // 4.0.0: "Same as scanner" is gone (there is no scanner), and the default is All items -- an unset focus means any item
  // (the maintainer's decision, 8 Oct 2026). keyName and return type unchanged, so a stored Gear or Bulk is kept. A stored SAME_AS_SCANNER no longer
  // names a constant: RuneLite 1.13.1's ConfigManager.setDefaultConfiguration(config, false), run when the plugin is loaded,
  // reads each item typed, finds it unreadable and writes this default over it (one "Unable to unmarshal" line in the client
  // log, once). ALL_ITEMS sends no focus, which the engine reads as any item -- what "Same as scanner" meant with no scanner
  // switch set. Checked in the
  // 1.13.1 client bytecode on 8 Oct 2026; SuggestionFocusMigrationTest pins it against the real ConfigManager.
  @ConfigItem(
    keyName = "suggestionFocus",
    name = "Suggestion focus",
    description = "Which items EVI suggests buying: gear, bulk consumables and ammo, or all. Sales are unaffected.",
    section = tradeSection,
    position = 15
  )
  default SuggestionFocus suggestionFocus() {
    return SuggestionFocus.ALL_ITEMS;
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
  // before it was offered: see PositionSizing and the project notes of 28 Sept 2026.
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

  // ON by default, under a NEW keyName (6 Oct 2026). Off, a fresh install has no history of its own
  // and so got no buy suggestion at all, in every hour measured (the redesign's replay). Why a new
  // key: RuneLite 1.13.1's ConfigManager.setDefaultConfiguration(proxy, false) WRITES each default
  // into the profile the first time the plugin loads, so every install that had already run EVI
  // held a stored "false" under the old key -- and flipping that default reached new installs only.
  // A new key reaches everyone. The cost, accepted by the maintainer: a player who had DELIBERATELY switched
  // it off finds it on once, and switches it off again here.
  @ConfigItem(
    keyName = "includeMarketWide",
    name = "Include market-wide suggestions",
    description = "Suggests from the whole GE catalogue, not only items you have traded before.",
    section = sourceSection,
    position = 22
  )
  default boolean includeMarketWide() {
    return true;
  }

  // RETIRED 6 Oct 2026, replaced by includeMarketWide above. Hidden under the same keyName and type
  // so a stored value still loads. NOTHING reads it -- not the query, not the no-suggestion message.
  @ConfigItem(
    keyName = "includeMarketSuggestions",
    name = "Include market-wide suggestions (old)",
    description = "Replaced by the setting of the same name. Kept so a stored value still loads; no effect.",
    section = sourceSection,
    position = 28,
    hidden = true
  )
  default boolean includeMarketSuggestions() {
    return true;
  }

  // RETIRED 6 Oct 2026: the Starter profile is gone from the panel and is no longer sent, whatever
  // is stored (see suggestionQuery). It allowed untaxed items only, which on market-wide picks meant
  // sub-50 gp bulk and nothing else. Kept as a hidden item under the same keyName and return type
  // so a stored "STARTER" is still a valid value for the config loader -- the project's rule for
  // config changes -- and so nothing a player chose is lost. Nothing reads it.
  @ConfigItem(
    keyName = "tradingProfile",
    name = "Trading profile",
    description = "Removed. Kept so nothing you chose is lost; it no longer has any effect.",
    section = sourceSection,
    position = 23,
    hidden = true
  )
  default TradingProfile tradingProfile() {
    return TradingProfile.STANDARD;
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

  // Calibration over 90 days found the forecast's direction calls wrong more often than chance, so it no longer predicts
  // price at all; what it measurably predicts is whether a trade's sell side fills, and that is all it is used for.
  //
  // A NEW keyName in 4.0.0 (the maintainer's decision, 8 Oct 2026): the ~1 hour and Overnight options are gone -- the plugin's own archive is
  // hourly and the fill-outlook table was measured for ~6 hours only, so those two gave no forecast at all. The old key,
  // "forecastHorizon", is no longer declared: whatever it holds stays in the profile, unread. EVERY player starts this one
  // at Off, including anyone who had chosen ~6 hours before (they switch it on again here); nobody is left on a setting
  // that silently did nothing.
  @ConfigItem(
    keyName = "exitRiskCheck",
    name = "Exit-risk check",
    description = "Warns when items with a similar recent price pattern often failed to sell within about 6 hours.",
    section = checksSection,
    position = 31
  )
  default ForecastHorizon exitRiskCheck() {
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
  // Stays opt-in until that measurement is redesigned -- see the project notes of 17 Sept 2026.
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

  // Off by default, deliberately. EVI already computes BETTER abort advice than the alternatives --
  // the relist advice names the BREAK-EVEN, which the plugin's own offerDriftHint cannot -- and then only
  // whispers it into a sidebar nobody is watching during a 2-24 hour hold. This closes a DELIVERY
  // gap, not an analysis one. It fires from the engine's advice and never from offerDriftHint: on
  // 2 Oct 2026 that hint said "take the current price" about a position whose market had fallen
  // through its cost basis, and pushing that at somebody is worse than staying quiet. Uses
  // RuneLite's own Notifier, so there is no new traffic of any kind.
  @ConfigItem(
    keyName = "notifyAdvice",
    name = "Notify on offer warnings",
    description = "Uses RuneLite's notifier the first time EVI warns about an offer you placed.",
    section = checksSection,
    position = 33
  )
  default boolean notifyAdvice() {
    return false;
  }

  // Hidden, not removed: it currently has no effect. "Skip" used to drop a candidate on an
  // unfavourable forecast, but the forecast was measured to be wrong more often than right, so
  // skipping is switched off in the engine (FORECAST_MAY_DROP_CANDIDATES). A
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
    description = "Colours for EVI's sidebar: RuneLite's own, or an Old School parchment style.",
    section = displaySection,
    position = 43
  )
  default PanelTheme panelTheme() {
    return PanelTheme.RUNELITE;
  }

  // ------------------------------------------------------------------ Price data
  //
  // 7 Oct 2026, the self-contained move: the plugin fetches the OSRS Wiki's public prices itself
  // (com.evi.live.market.WikiPriceClient, the plugin's only network class to the internet). Every network use is
  // disclosed plainly before it ships. The settings text is deliberately SHORT (the maintainer's decision, 7 Oct: the
  // first version ran to about 760 characters, which a hover tooltip cannot carry); the full account -- what is fetched,
  // when, how much, how long the first run takes, and what is never sent -- is in the plugin's README.
  // MarketIntentTest pins this text word for word and still ties the README's figures to the constants that make them
  // true. It holds no setting: the disclosure is the section's own description (RuneLite shows it on the section
  // header). ASCII only, plain text.
  String PRICE_DATA_DISCLOSURE = "Downloads public GE prices from the OSRS Wiki. Nothing about you is sent. Details in the README.";

  @ConfigSection(
    name = "Price data",
    description = PRICE_DATA_DISCLOSURE,
    position = 45,
    closedByDefault = true
  )
  String priceDataSection = "priceDataSection";


  // ------------------------------------------------------------------ Trade history
  //
  // The plugin keeps its own trade journal (com.evi.live.journal.PluginJournal). The import below is the only way history
  // from the old companion app reaches it, and it is never automatic: OFF by default, it reads only a file the player has
  // put in the plugin's own data folder, once per account. keyName unchanged since it was added (4.0.0 renamed only the
  // label), so a stored choice is kept.
  @ConfigSection(
    name = "Trade history",
    description = "EVI's own record of your trades, kept on this computer.",
    position = 50,
    closedByDefault = true
  )
  String historySection = "historySection";

  @ConfigItem(
    keyName = "importBridgeHistory",
    name = "Import old trade history",
    description = "Copies once: " + PluginFolder.PATH + "/import/events.jsonl and preferences.json. Off: reads nothing.",
    section = historySection,
    position = 51
  )
  default boolean importBridgeHistory() {
    return false;
  }

  // Hidden (voluntary trade-log sharing, 9 Oct 2026): the plugin version at which the sidebar's "Not now" was pressed. The
  // invitation comes back when the version differs (ShareInvite.shown). Written by the sidebar only; empty until then.
  @ConfigItem(
    keyName = ShareInvite.CONFIG_KEY,
    name = "Share invitation dismissed at",
    description = "The EVI version at which the sidebar's trade-log invitation was hidden with Not now.",
    section = historySection,
    position = 55,
    hidden = true
  )
  default String shareInviteDismissedVersion() {
    return "";
  }
}
