package com.evi.live;

import com.evi.live.inprocess.EngineFeed;
import com.evi.live.inprocess.InProcessEngine;
import com.evi.live.inprocess.SuggestionRecords;
import com.evi.live.journal.AccountIds;
import com.evi.live.journal.PluginFolder;
import com.evi.live.journal.PluginJournal;
import com.evi.live.market.PriceDataService;
import com.evi.live.market.WikiPriceClient;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.inject.Provides;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Observes GE state; shows your own EVI suggestion as a text hint in the open quantity/price prompt and, on an optional hotkey, fills that one field with it. No menus, clicks, item selection, offer confirmation or other automated actions.
 *  Self-contained since 4.0.0: the engine, the trade journal and the price archive all run inside the plugin, and its only
 *  network traffic is the OSRS Wiki's public prices (com.evi.live.market.WikiPriceClient). */
@PluginDescriptor(name="EVI Live",internalName="evi-flipping",description="Flip suggestions for the Grand Exchange: what to buy, how many and at what price, plus a trade journal with exact GE tax. Uses only public OSRS Wiki prices; nothing about you is sent.",tags={"grand exchange","ge","flip","flipping","trading","money making","profit","suggestions","journal","evi"})
public class EviLivePlugin extends Plugin {
  private static final Logger log=LoggerFactory.getLogger(EviLivePlugin.class);
  @Inject private Notifier notifier;
  @Inject private Client client;
  @Inject private ConfigManager configManager;
  @Inject private ClientToolbar toolbar;
  @Inject private ClientThread clientThread;
  @Inject private GEOffer geOffer;
  @Inject private SuggestionCache suggestionCache;
  @Inject private OpenItemPriceCache openItemPriceCache;
  @Inject private SuggestionKeybindHandler suggestionKeybindHandler;
  @Inject private SuggestionHintWidget suggestionHintWidget;
  @Inject private SuggestionItemSelectWidget itemSelectWidget;
  @Inject private SuggestionSearchHighlightOverlay searchHighlightOverlay;
  @Inject private OverlayManager overlayManager;
  @Inject private EviLiveConfig config;
  private EviLivePanel panel;
  private NavigationButton navigation;
  private volatile boolean running;
  private volatile long lifecycle;
  @Inject private Gson gson;
  // RuneLite's own shared HTTP client, injected rather than built here, so every request this plugin makes -- only ever to the
  // OSRS Wiki's public prices, through WikiPriceClient -- goes through the client the player's installation already governs.
  @Inject private okhttp3.OkHttpClient okHttpClient;
  private ScheduledExecutorService sender;
  // The plugin's OWN trade journal: every packet the plugin builds from the GE slots, recorded and persisted in its data folder.
  // It owns its own thread (evi-journal) for every file and store operation; this class only hands text over. Volatile: set
  // in startUp/shutDown, read on the client thread (enqueue) and the poll thread.
  private volatile PluginJournal journal;
  // The journal shutDown() stopped, which may still be finishing its queue: the next startUp() hands it to
  // the new journal, whose OWN thread waits for it (bounded) before reading anything, so a quick off/on
  // never has two journals writing one file at once and no queued packet is lost or reordered.
  private volatile PluginJournal retiredJournal;
  // The price-data layer: the OSRS Wiki's public prices and a rolling hourly archive in the plugin's own data folder, on its
  // own thread (evi-prices). The engine reads it. Volatile: set in startUp/shutDown, read on the client thread.
  private volatile PriceDataService prices;
  // The price layer shutDown() stopped, which may still have a Wiki request out (shutdown never interrupts it). The next
  // startUp() hands it to the new layer, which reads and fetches NOTHING until it has finished and then keeps its
  // spacing, so turning the plugin off and on never puts two Wiki requests in flight or two closer than 2.5 s.
  private volatile PriceDataService retiredPrices;
  // The plugin's own engine (its own thread, evi-engine) and the source the poll asks it through (an InProcessTransport over
  // that engine; a test may install a recorded one). Null until startInProcess built them, or when the engine did not start.
  private volatile InProcessEngine inProcess;
  private volatile AnswerSource answers;
  // The profit line's reset time: epoch ms, null for never. Stored under its own keyName in the plugin's config group, read
  // at startUp; the engine's thread reads this field.
  private volatile Long inProcessProfitSince;
  // "I took this one" -- the suggestion ids the engine issues and the presses (see SuggestionRecords), in the plugin's data
  // folder. Built in startInProcess.
  private volatile SuggestionRecords suggestionRecords;
  // The Block button's lists, PER CHARACTER (the maintainer's decision, 8 Oct 2026): each kept on its own RuneScape profile
  // under the plugin's group (see BlockedItems), addressed by the profile NAMED in each call, never by whichever profile is
  // current. Not final so a test can supply its own store.
  private BlockedItems blockedItems=new BlockedItems(new BlockedItems.Store(){
    public String read(String profile){ConfigManager cm=configManager;return cm==null?null:cm.getConfiguration(CONFIG_GROUP,profile,BlockedItems.CONFIG_KEY);}
    public void write(String profile,String value){
      ConfigManager cm=configManager;
      if(cm==null)throw new IllegalStateException("no config manager");
      if(value.isEmpty())cm.unsetConfiguration(CONFIG_GROUP,profile,BlockedItems.CONFIG_KEY);
      else cm.setConfiguration(CONFIG_GROUP,profile,BlockedItems.CONFIG_KEY,value);
    }
  });
  // Which RuneScape profile each account pseudonym this plugin lifetime has seen belongs to (account -> profile key). The engine
  // is asked about an ACCOUNT; a Block list lives on a PROFILE; this joins the two without reading the current profile on the
  // engine's thread. Filled where the account is worked out (onGameTick); never cleared, so a poll answered just after a
  // character switch still reads the list of the account it was asked for.
  private final Map<String,String> accountProfiles=new ConcurrentHashMap<>();
  // The RuneScape profile of the LAST character worked out at a login (the maintainer's decision, 8 Oct 2026), so a query that
  // names no account (the login screen, the first ticks of a login, after a logout) still reads that character's Block list
  // instead of none. Kept in the config under LAST_PROFILE_KEY, so it survives a client restart; read at startInProcess,
  // written at login. Null: no character ever logged in (no list applies).
  private volatile String lastProfile;
  // The plugin's data folder, kept by startInProcess for the one-time import of the old companion app's preferences.
  private volatile Filepath inProcessDir;
  // RuneScape profiles whose import/preferences.json could not be read, so the log says so once per session, not every poll.
  private final Set<String> preferencesImportLogged=ConcurrentHashMap.newKeySet();
  /** The config key the in-process profit reset is stored under (a new keyName, no @ConfigItem: nothing to show in settings). */
  static final String PROFIT_SINCE_KEY="inProcessProfitSince";
  /** The config key holding the last logged-in character's RuneScape profile key (a new keyName, no @ConfigItem). */
  static final String LAST_PROFILE_KEY="lastCharacterProfile";
  /** The status line before the engine's first answer (the name is from the build that still had a bridge mode). */
  static final String IN_PROCESS_WAITING="Waiting for prices from the OSRS Wiki.";
  /** The suggestion card while buys wait for the price history and nothing else applies. ASCII. */
  static final String BUYS_PAUSED="Buy suggestions are paused while EVI loads price history (progress below).";
  /** How long a helper thread (never the client thread) waits for the sender after shutDown before logging that it is still busy. */
  static final long SENDER_STOP_WAIT_MS=10_000;
  private String salt,profile,account,session,economy;
  private long seq;
  private int warmTicks,ticks;
  private boolean ready;
  private final Offer[] slots=new Offer[8];
  // Item IDs the suggestion poll should skip over: never a permanent preference (that's the
  // config blocklist) -- both cleared on reset() and meant to last only this login session.
  // skippedItemIds: the player manually dismissed this item via the sidebar's "Skip this
  // suggestion" button, e.g. because they don't want to do that flip right now.
  // activeSlotItemIds: a snapshot (client-thread writes, read from the poll's own background
  // thread, hence volatile) of item IDs currently occupying ANY of the 8 GE slots in a non-empty
  // state -- buying, selling, or bought/sold/cancelled but not yet collected. Recomputed
  // automatically alongside every slots[] mutation, so it self-clears the moment a slot empties
  // (collected) with no separate bookkeeping. This is what stops the same item being suggested
  // again right after you've already acted on it -- the sidebar previously had no way to know an
  // offer had been placed.
  //
  // 6 Oct 2026: the Skip BUTTON no longer lands here. It goes to skipMemory below, which keeps it for
  // four hours per RuneScape account and survives reset(). This set now holds only the session-long
  // exclusions the other buttons add for immediacy (Block, Personal use, Gone -- each also recorded
  // by the plugin for good), plus a Skip pressed while no account is known, so that press still applies.
  private final Set<Integer> skippedItemIds=ConcurrentHashMap.newKeySet();
  /** The config group every EviLiveConfig item lives in. */
  static final String CONFIG_GROUP="evilive";
  /** The keyName the sidebar's risk buttons write -- the SAME key as the settings panel's Risk level
   *  (EviLiveConfig.riskLevelV2), so the two can never disagree. */
  static final String RISK_KEY="riskLevelV2";
  /** "Max share of cash per trade" (EviLiveConfig.maxTradeShare): with High it decides the line under the risk buttons. */
  static final String SHARE_KEY="maxTradeShare";
  /** Whether the risk levels reach the request at all. RiskLevel.SHOWN, the one switch, and false since 7 Oct 2026:
   *  while false NO risk= is ever sent, whatever riskLevelV2 holds, so every player is asked at Low -- the bridge's own
   *  default -- exactly as a default player always was. A field rather than the bare constant only so a test can
   *  still drive the dormant path; nothing in the plugin writes it. */
  private boolean riskLevelsShown=RiskLevel.SHOWN;
  // Skips, per account, for four hours (see SkipMemory). Backed by RuneLite's per-account RS-profile
  // configuration; read lazily, so a plugin built without injection (the tests) simply has no profile
  // and falls back to the session set above. Not final so a test can supply its own store and clock.
  private SkipMemory skipMemory=new SkipMemory(new SkipMemory.Store(){
    public String profileKey(){ConfigManager cm=configManager;return cm==null?null:cm.getRSProfileKey();}
    // Both guard on the profile STILL being current: RuneLite's RS-profile calls always address the
    // current account, so a profile that changed between lookup and use must read and write nothing.
    public String read(String profile){
      ConfigManager cm=configManager;
      if(cm==null || !profile.equals(cm.getRSProfileKey()))return null;
      return cm.getRSProfileConfiguration(CONFIG_GROUP,SkipMemory.CONFIG_KEY);
    }
    public void write(String profile,String value){
      ConfigManager cm=configManager;
      if(cm==null || !profile.equals(cm.getRSProfileKey()))return;
      if(value.isEmpty())cm.unsetRSProfileConfiguration(CONFIG_GROUP,SkipMemory.CONFIG_KEY);
      else cm.setRSProfileConfiguration(CONFIG_GROUP,SkipMemory.CONFIG_KEY,value);
    }
  },System::currentTimeMillis);
  // How a config value is written: (keyName, stored value). Null means ConfigManager, which is the
  // only production path; a test sets this to observe exactly which key and value a press writes.
  private java.util.function.BiConsumer<String,String> configWriter;
  // unverifiableItemIds: a reconstructed holding the bridge offered that was NOT in the inventory
  // when we looked. Held back only until the inventory next changes, never for the session.
  //
  // It used to go into skippedItemIds, which is the list the player fills by pressing "Skip", and
  // that was wrong in a way that reached every user: a buy that has FILLED but not yet been
  // COLLECTED is not in the inventory, so a poll landing in that window excluded the item until
  // RuneLite was restarted. Collecting it changed nothing, because EVI never asked again. A player hit
  // this twice, on 29 and 30 Sept, while the bridge was answering "sell 1 ..." with a profit the
  // whole time and the plugin was dropping it on the floor. A transient condition must not cause a permanent exclusion,
  // and this one was invisible as well as permanent: the early return below skipped the line that
  // updates the skipped count, so "Show skipped items again" never even appeared.
  private final Set<Integer> unverifiableItemIds=ConcurrentHashMap.newKeySet();
  private volatile Set<Integer> activeSlotItemIds=Collections.emptySet();
  // A lighter, immutable snapshot of only the still-in-progress (non-terminal: BUYING or SELLING,
  // not yet fully filled/cancelled) offers among the same slots[] activeSlotItemIds is built from --
  // same client-thread-writes/background-thread-reads shape (hence volatile) as activeSlotItemIds
  // just above, kept alongside it in refreshActiveSlotItemIds() rather than recomputed separately.
  // This is what suggestionQuery()'s slots= param and pollSuggestion()'s offer-drift hint (see
  // offerDriftHint) are built from -- unlike exclude=, a terminal-but-uncollected offer has nothing
  // left to cancel or relist, so it's deliberately left out of this one.
  private volatile List<ActiveOffer> activeOffers=Collections.emptyList();
  // Every non-EMPTY GE slot, in slot order -- including bought/sold/cancelled offers still waiting to
  // be collected, unlike activeOffers above -- purely for the sidebar's "Active offers" list, so the
  // player can see everything sitting in the GE at a glance. Rebuilt alongside activeOffers in
  // refreshActiveSlotItemIds() and pushed to the panel from there, so the list follows GE changes
  // immediately rather than waiting for the next 2-second suggestion poll.
  private volatile List<OfferRow> geOfferRows=Collections.emptyList();
  // How many of the 8 Grand Exchange slots are actually usable right now, recomputed alongside
  // activeSlotItemIds (client-thread writes, poll-thread reads, hence volatile) and sent to the
  // bridge as freeSlots=/collectable= so it never suggests a trade the player has nowhere to put.
  // OSRS caps everyone at 8 simultaneous offers, and EVI previously had no idea: with all 8 in use
  // it would happily keep suggesting a ninth, which is advice that cannot be followed.
  //   freeSlots        -- slots that are genuinely EMPTY, i.e. a new offer can be placed at once.
  //   collectableSlots -- slots holding a finished (bought/sold/cancelled) offer that has not been
  //                       collected. These are full right now, but one click frees them, so they
  //                       are counted separately rather than lumped in with "no room": the bridge
  //                       still ranks normally when any exist and simply says to collect first.
  // Both start at -1 ("not known yet", before the first slot snapshot) which sends nothing at all
  // and filters nothing -- the same fail-open rule as cashStack and membersWorld above.
  // Whether the player is standing in an instance. Client-thread write, poll-thread read, hence
  // volatile -- see refreshInInstance for why this is not read inline.
  // Warnings already announced, so one standing offer does not notify every two seconds.
  private final java.util.Set<String> announcedAdvice=new java.util.HashSet<>();
  private volatile boolean inInstance;
  // Acceptances the player has pressed but the engine's answer does not show yet. See acceptSuggestion.
  private final java.util.Map<String,Boolean> pendingAccept=new java.util.concurrent.ConcurrentHashMap<>();
  private volatile int freeSlots=-1;
  private volatile int collectableSlots=-1;
  // The items the bridge's journal believes are still held (positionItems in its last response). The
  // journal only sees the Grand Exchange, so stock sold, used or dropped outside it stays "held"
  // forever -- two such stale positions once reserved the player's last two slots. This plugin
  // confirms against its own inventory snapshot and sends back only the ones really there
  // (heldPositions=, see suggestionQuery), exactly as verifyPersistedHolding already does for
  // suggestions. Only ever echoes IDs the bridge named itself, so no other inventory contents leave
  // the client. Written by the poll thread, read by the next poll, hence volatile.
  private volatile Set<Integer> bridgePositionItems=Collections.emptySet();
  // The player's actual current spending power, read on the client thread every tick and cached
  // here for the background poll thread (hence volatile) -- never fabricated or assumed. Since
  // Jagex's "Beyond Max Cash" update it is their coins (995) PLUS their platinum tokens (13204) at
  // 1,000 gp each, because the Grand Exchange settles an offer from both together; see
  // refreshCashStack for the full reasoning and for why this is a long rather than an int.
  // -1 means "not yet known" (e.g. before the inventory has loaded this session), which
  // suggestionQuery() treats as "send no cash figure at all" so a missing reading never falsely
  // constrains suggestions. Note that 0 is a REAL answer -- carrying nothing -- and the bridge
  // distinguishes the two. Sent to the bridge so it can cap/re-rank suggestions to trades actually
  // affordable right now, rather than a huge-margin item at a quantity costing more than they hold.
  private volatile long cashStack=-1;
  // The item ID currently selected in an open GE offer (buy or sell), read from GEOffer on the
  // client thread every tick and cached here (volatile) for the background poll thread, same
  // pattern as cashStack above. -1 means "no GE slot open right now" (GEOffer.currentItemId()
  // itself isn't meaningful without isSlotOpen() also being true, so that's folded in here rather
  // than left for suggestionQuery() to get wrong). Sent to the bridge as openItemId so it can
  // return a plain live-market price for THIS item regardless of flip history or ranking -- this
  // is what makes the quantity/price hint and hotkey work for any item you're actually trading,
  // not only EVI's own top-ranked pick (the original limitation: no reviewed history for an item,
  // or it just isn't the current #1 suggestion, meant no hint/fill at all, even while buying or
  // selling it with a live GE price readily available).
  private volatile int openOfferItemId=-1;
  // Whether the world currently logged into is a members world, read on the client thread every
  // tick like cashStack/openOfferItemId above and cached here (volatile) for the poll thread. A
  // members-only item cannot be traded on a free-to-play world at all, so the bridge needs to know
  // which kind of world this is before suggesting one. Boolean (not boolean) so "not known yet",
  // before login, stays distinct from "free-to-play world" -- unknown sends nothing and filters
  // nothing, same fail-open rule as every other signal here.
  private volatile Boolean membersWorld=null;
  // A snapshot of every item ID currently present (quantity > 0) in the player's inventory, read
  // on the client thread every tick and cached here (volatile) for the background poll thread to
  // read -- same pattern as cashStack/openOfferItemId above, and for the same reason: RuneLite's
  // own Client API asserts that client.* calls happen on the client thread, and verifyPersistedHolding
  // (see its own doc) is called from pollSuggestion() on the background `sender` executor, not from
  // an event-subscriber callback. Calling client.getItemContainer() directly from there -- what an
  // earlier version of this did -- threw "AssertionError: must be called on client thread" on every
  // single poll once a persisted suggestion existed to verify, confirmed against a real client.log.
  // Empty until the inventory has loaded this session, exactly like activeSlotItemIds. A genuinely
  // empty inventory (everything banked) also produces an empty set, so verifyPersistedHolding
  // distinguishes "never successfully refreshed yet" from "genuinely nothing held" via the
  // separate inventorySnapshotEstablished flag below rather than via emptiness alone.
  private volatile Set<Integer> inventoryItemIds=Collections.emptySet();
  private volatile boolean inventorySnapshotEstablished=false;
  // Companion to inventoryItemIds above, refreshed alongside it by the same
  // refreshInventoryItemIds() call: total quantity per item ID currently in the inventory, summed
  // across any unstacked duplicate slots (an inventory can hold the same item across more than one
  // slot when it doesn't stack, e.g. noted vs. unnoted, or simply several separate non-stacking
  // items sharing an ID in edge cases). Sent to the bridge as the inventory= query parameter (see
  // suggestionQuery()), but only when EviLiveConfig.suggestIdleInventory() is turned on -- this is
  // the one piece of session state here that leaves the client for a purpose beyond this plugin's
  // own live hint/hotkey, so it stays opt-in rather than always collected and sent. Empty until the
  // inventory has loaded, same "leave the previous snapshot in place on a hiccup" treatment as
  // inventoryItemIds; a genuinely empty inventory also produces an empty map, which is fine here
  // since an empty map simply means suggestionQuery() sends no inventory= parameter at all.
  private volatile Map<Integer,Integer> inventoryQuantities=Collections.emptyMap();
  // Items bought and collected through the GE this session that haven't been resold yet -- so
  // EVI can remind you to close out a position you already opened (sell price shown again)
  // instead of moving straight on to a brand-new buy suggestion for something else, which was the
  // exact complaint a player made: buying a stack from a suggestion, then having the sidebar jump
  // straight to a totally different market-wide pick while the stack just sat there unsold.
  // Session-only, like everything else in this block: populated purely from observed GE slot
  // transitions (never assumed), keyed by item ID so re-buying the same item just refreshes its
  // held quantity rather than creating a second entry. ConcurrentHashMap because
  // updateHeldForResale() writes on the client thread and suggestionQuery() reads it from the
  // poll's background thread.
  private final Map<Integer,Held> heldForResale=new ConcurrentHashMap<>();
  static final class Held {
    // offerId: the specific buy offer this holding came from, when known -- carried through to the
    // bridge as holdBuyId (see suggestionQuery()) so a "Personal use" flag on the resulting
    // suggestion (see flagPersonalUse) marks that exact purchase, never just "this item ID" broadly.
    // price: the REAL average price actually paid per unit (the buy offer's own spent/filled at
    // the moment it was collected -- never the offer's set/max price, which can differ from what
    // actually filled) -- sent to the bridge as holdBuyPrice so the "you're holding this" reminder
    // can say whether selling right now is a profit or a loss, instead of a plain "sell near X gp"
    // that reads identically either way. See suggestionQuery() and computeHoldingSuggestion's own
    // doc in suggestions.mjs for the exact failure this exists to fix.
    final int itemId,quantity;final long price;final String name,offerId;
    Held(int itemId,int quantity,String name,String offerId,long price){this.itemId=itemId;this.quantity=quantity;this.name=name;this.offerId=offerId;this.price=price;}
  }

  @Provides
  EviLiveConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(EviLiveConfig.class);
  }

  @Override protected void startUp() throws Exception {
    // The sanctioned accessor: Plugin#getPluginDirectory(), which resolves to
    // .runelite/plugin-data/<internalName>/ (internalName is set in @PluginDescriptor above and is
    // required by that method). An earlier version called Filepath.Unchecked.getLegacyPluginDirectory
    // instead, on the mistaken belief that getPluginDirectory() hadn't landed in the client yet; the
    // Plugin Hub maintainer corrected that on runelite/plugin-hub#16640 ("you shouldn't be using
    // unchecked, ever"), and it is indeed present in the client this builds against. Nothing here
    // reads the old .runelite/evi-live/ folder any more: legacyDataDirectory is deliberately NOT set,
    // per the same review, since only the maintainer's own machine ever had that folder.
    // 4.0.0: the internalName is evi-flipping, so the folder is plugin-data/evi-flipping/ (PluginFolder); nothing reads the
    // plugin-data/evi-live/ folder of earlier versions either.
    Filepath dir=getPluginDirectory();
    dir.createDirectories();
    Filepath saltFile=dir.joinSegment("identity-salt.txt");
    if(!saltFile.exists())saltFile.write(UUID.randomUUID().toString());
    salt=readTrimmed(saltFile);
    if(salt.isEmpty())throw new IllegalStateException("EVI identity salt is empty; restore it from your local backup.");
    Runnable createPanel=()->{
      panel=buildPanel();
      navigation=NavigationButton.builder().tooltip("EVI Live").icon(EviLivePanel.icon()).panel(panel).priority(8).build();
      toolbar.addNavigation(navigation);
    };
    if(SwingUtilities.isEventDispatchThread())createPanel.run();else SwingUtilities.invokeAndWait(createPanel);
    status(IN_PROCESS_WAITING);
    if(panel!=null)panel.profitWaiting("Waiting for the first price check.");
    reset();
    suggestionKeybindHandler.register();
    overlayManager.add(searchHighlightOverlay);
    startJournal(dir);
    startPrices(dir);
    startInProcess(dir);
    final long generation=++lifecycle;
    running=true;
    sender=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"evi-poll");t.setDaemon(true);return t;});
    sender.scheduleWithFixedDelay(()->pollSuggestion(generation),0,2,TimeUnit.SECONDS);
  }
  /** The sidebar as startUp() first draws it, from the stored settings: the colour scheme, the risk level together
   *  with "Max share of cash per trade" (so High with "No limit" is named from the first frame), and the action row
   *  dimmed until there is a pick. Swing EDT. Package-private so a test can draw it without a client. */
  EviLivePanel buildPanel() {
    EviLivePanel p=new EviLivePanel(this::skipSuggestion,this::flagPersonalUse,this::flagNotHeld,this::blockSuggestion,this::resetProfit,this::clearSkips,this::acceptSuggestion,this::chooseRisk);
    p.applyTheme(config.panelTheme());
    p.riskLevel(config.riskLevelV2(),config.maxTradeShare());
    p.actions(false,false,false,false,false);
    p.onHoldingLine(this::holdingLinePersonalUse,this::holdingLineGone);
    // Block is kept per character and undone in the panel's Blocked items list
    p.onUnblock(this::unblockItem);
    // the trade-log invitation at the bottom: shown unless "Not now" was pressed at this very version
    p.onShare(this::saveTradeLog,this::dismissShareInvite);
    p.shareInvite(shareInviteShown());
    return p;
  }
  /** Whether the sidebar's trade-log invitation shows: not after "Not now" at this version (ShareInvite.shown). */
  boolean shareInviteShown() {
    return ShareInvite.shown(config==null?null:config.shareInviteDismissedVersion(),WikiPriceClient.VERSION);
  }
  // Called only from the invitation's "Not now" (Swing EDT): keeps the version it was pressed at, through the same config the
  // settings use (a hidden item), so the invitation stays away until the next EVI update. Writes nothing else.
  void dismissShareInvite() {
    java.util.function.BiConsumer<String,String> write=configWriter;
    if(write!=null)write.accept(ShareInvite.CONFIG_KEY,WikiPriceClient.VERSION);
    else if(configManager!=null)configManager.setConfiguration(CONFIG_GROUP,ShareInvite.CONFIG_KEY,WikiPriceClient.VERSION);
    if(panel!=null)panel.shareInvite(false);
  }
  // Called only from the invitation's "Save my trade log..." (Swing EDT). Voluntary sharing, 9 Oct 2026: asks first, then the
  // journal builds and writes the file on ITS thread (never this one, never the client thread); the answer comes back here.
  // Nothing is sent anywhere: the player opens, reads and sends the file themselves.
  void saveTradeLog() {
    EviLivePanel p=panel;
    if(p==null || !ShareInvite.confirm(p))return;
    PluginJournal j=journal;
    if(j==null){ShareInvite.failed(p,new IllegalStateException("EVI's trade record is not running."));return;}
    String name=com.evi.live.journal.TradeLogExport.fileName(java.time.LocalDate.now());
    // EVI's suggested price beside each offer it can be linked to: the suggestion-outcome join (SuggestionOutcomes, the port of
    // suggestionOutcomes.mjs) over the plugin's own suggestion log (the SuggestionRecords startInProcess built), run on the
    // journal's thread. If the engine never started there are no records, and every offer is left unlinked -- never guessed.
    final SuggestionRecords records=suggestionRecords;
    j.exportTradeLog(name,System.currentTimeMillis(),(state,offers)->com.evi.live.inprocess.SuggestionOutcomes.offerLinks(records,state,offers)).whenComplete((saved,err)->SwingUtilities.invokeLater(()->{
      if(err!=null || saved==null){
        log.warn("EVI: the trade log was not saved",err);
        ShareInvite.failed(p,err!=null?err:new IllegalStateException("nothing was saved"));
        return;
      }
      ShareInvite.saved(p,saved.offers,saved.flips,name,copyToClipboard(saved.file.toString()));
    }));
  }
  /** Puts text on the system clipboard; false when there is none to put it on (a headless test, a locked clipboard). */
  static boolean copyToClipboard(String text) {
    try {
      java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text),null);
      return true;
    } catch(RuntimeException ex){return false;}
  }
  @Override protected void shutDown() {
    running=false;
    ++lifecycle;
    // First, so no later teardown step that throws can leave the journal's thread running.
    stopJournal();
    stopPrices();
    stopInProcess();
    // An orderly stop, never shutdownNow(): that interrupts the sender's thread, and Thread.interrupt is on
    // the Hub's forbidden list. Periodic tasks stop; a request in flight finishes (bounded by the transport's
    // own timeouts) and finds the lifecycle moved on. Waiting for it happens off the client thread.
    if(sender!=null)stopOffThread(sender,SENDER_STOP_WAIT_MS,m->log.warn(m));
    suggestionKeybindHandler.unregister();
    overlayManager.remove(searchHighlightOverlay);
    suggestionCache.set(null);
    openItemPriceCache.set(null);
    updatePanelSuggestion(null, null);
    updatePanelOfferHint(Collections.emptyList(), null, null, null, null);
    try { suggestionHintWidget.clear(); } catch (Exception ignored) { }
    try { itemSelectWidget.clear(); } catch (Exception ignored) { }
    if(navigation!=null)toolbar.removeNavigation(navigation);
    reset();
  }
  /** Opens the plugin's own journal in its data folder. Every file operation, including this first load,
   *  runs on the journal's own thread; nothing here touches the disk. A journal that cannot start is
   *  logged and left off -- it never stops the plugin (the engine then answers "no journal"). */
  private void startJournal(Filepath dir) {
    PluginJournal previous=retiredJournal;
    retiredJournal=null;
    try {
      PluginJournal j=new PluginJournal(dir,System::currentTimeMillis,s->gson.fromJson(s,JsonElement.class),
        ()->config.importBridgeHistory(),m->log.info(m));
      // The sidebar's import line follows the journal, which is the one place that looks for the file (on its own
      // thread; the panel only ever hears a sentence or null).
      j.onImportNotice(this::showImportNotice);
      // The import takes the old companion app's machine-wide "Yours, not stock" marks with each account's import.
      j.importItemMarks(()->true);
      // ...and its flip histories from another tracker, kept by character name: told at login, before the first packet.
      j.expectCharacterNames(true);
      j.start(previous); // waits for the previous journal on the new journal's own thread, never here
      journal=j;
    } catch(Exception ex){log.warn("EVI journal: not started",ex);}
  }
  /** Starts the price-data layer. Every request and file operation runs on its own thread (evi-prices); nothing here
   *  touches the disk or the network. A layer that cannot start is logged and left off -- it never stops the plugin. */
  private void startPrices(Filepath dir) {
    PriceDataService previous=retiredPrices;
    retiredPrices=null;
    try {
      PriceDataService p=new PriceDataService(dir,new WikiPriceClient(okHttpClient,()->client!=null&&client.isClientThread()),
        System::currentTimeMillis,PriceDataService.MONOTONIC_MS,m->log.info(m));
      p.start(previous); // waits for the previous layer on the new layer's own thread, never here
      p.loggedIn(client!=null&&client.getGameState()==GameState.LOGGED_IN);
      prices=p;
    } catch(Exception ex){log.warn("EVI prices: not started",ex);}
  }
  /** Builds the plugin's engine and the source the poll asks it through. Starts no thread until the first poll hands it a
   *  query. One that cannot be built is logged; the sidebar then says so on each poll. */
  private void startInProcess(Filepath dir) {
    stopInProcess();
    try {
      inProcessProfitSince=readProfitSince();
      lastProfile=readLastProfile();
      SuggestionRecords records=new SuggestionRecords(dir,text->gson.fromJson(text,JsonElement.class),m->log.info(m));
      inProcessDir=dir;
      InProcessEngine engine=new InProcessEngine(dir,EngineFeed.journalOf(()->journal),EngineFeed.marketOf(()->prices),
        text->gson.fromJson(text,JsonElement.class),m->log.info(m),
        ()->(client!=null&&client.isClientThread())||SwingUtilities.isEventDispatchThread(),()->inProcessProfitSince,null,this::blockedFor,records);
      suggestionRecords=records;
      inProcess=engine;
      answers=new InProcessTransport(engine,System::currentTimeMillis);
    } catch(Exception ex){log.warn("EVI engine: not started",ex);}
  }
  /** The Block list the engine reads for a query naming {@code acct}: that account's character's list; none for an account this
   *  plugin lifetime has not worked out a profile for. A query naming NO account (before login, or between a logout and the next
   *  login) reads the LAST logged-in character's list (lastProfile), or none if no character ever logged in. Any thread (the engine's). */
  java.util.Collection<Integer> blockedFor(String acct) {
    String p=acct==null||acct.isEmpty()?lastProfile:accountProfiles.get(acct);
    return p==null||p.isEmpty()?Collections.emptyList():blockedItems.all(p);
  }
  /** The last logged-in character's RuneScape profile from the config (null: none stored, or no ConfigManager in a test). */
  private String readLastProfile() {
    try {
      ConfigManager cm=configManager;
      String v=cm==null?null:cm.getConfiguration(CONFIG_GROUP,LAST_PROFILE_KEY);
      return v==null||v.trim().isEmpty()?null:v.trim();
    } catch(Exception ex){ return null; }
  }
  /** Remembers {@code rsProfile} as the last logged-in character (the client thread, at login). Written only when it changes.
   *  A config that cannot be written still remembers it for this plugin lifetime. */
  private void rememberLastProfile(String rsProfile) {
    if(rsProfile==null || rsProfile.isEmpty())return;
    lastProfile=rsProfile;
    try {
      ConfigManager cm=configManager;
      if(cm!=null && !rsProfile.equals(cm.getConfiguration(CONFIG_GROUP,LAST_PROFILE_KEY)))cm.setConfiguration(CONFIG_GROUP,LAST_PROFILE_KEY,rsProfile);
    } catch(Exception ignored){ /* kept in memory for this lifetime; the next login tries again */ }
  }
  /** The RuneScape profile the current account belongs to, or null when no account is worked out yet. */
  private String currentProfile() {
    String a=account;
    return a==null?null:accountProfiles.get(a);
  }
  /** Stops the in-process engine's thread: shutdown(), never shutdownNow(); an answer being worked out finishes. */
  private void stopInProcess() {
    InProcessEngine engine=inProcess;
    inProcess=null;
    answers=null;
    suggestionRecords=null;
    if(engine!=null)engine.shutdown();
  }
  /** The profit reset, from the config (null: never reset, or nothing stored, or no ConfigManager in a test). */
  private Long readProfitSince() {
    try {
      ConfigManager cm=configManager;
      String v=cm==null?null:cm.getConfiguration(CONFIG_GROUP,PROFIT_SINCE_KEY);
      return v==null||v.isEmpty()?null:Long.valueOf(v.trim());
    } catch(Exception ex){ return null; }
  }
  /** Stops the price-data thread: shutdown(), never shutdownNow(), so nothing is interrupted; a request in flight
   *  finishes (bounded by its 20 s call limit) and nothing new starts. The stopped layer is kept for the next
   *  startPrices() to wait for. */
  private void stopPrices() {
    PriceDataService p=prices;
    prices=null;
    if(p!=null){p.shutdown();retiredPrices=p;}
  }
  /** Stops the journal's thread: shutdown(), never shutdownNow(), so queued writes finish and nothing is
   *  interrupted. The stopped journal is kept for the next startJournal() to hand over to. */
  private void stopJournal() {
    PluginJournal j=journal;
    journal=null;
    if(j!=null){j.shutdown();retiredJournal=j;}
  }
  /** shutdown() (never shutdownNow()), then a short-lived daemon thread -- not the caller's, which is the
   *  client or Swing thread -- waits up to waitMs for the executor to finish, and says so if it did not.
   *  Nothing is interrupted, including that helper. Returns the helper thread (tests join it). */
  static Thread stopOffThread(ExecutorService executor,long waitMs,java.util.function.Consumer<String> warn) {
    executor.shutdown();
    Thread t=new Thread(()->{
      try {
        if(!executor.awaitTermination(waitMs,TimeUnit.MILLISECONDS))
          warn.accept("EVI Live: the suggestion poll was still busy "+waitMs/1000+" s after shutdown; it finishes on its own (a daemon thread, never interrupted)");
      } catch(InterruptedException ignored) {
        // nothing in EVI interrupts this thread; if anything else does, it simply stops waiting
      }
    },"evi-sender-stop");
    t.setDaemon(true);
    t.start();
    return t;
  }
  private void status(String message){if(panel!=null)panel.status(message);}
  /** The journal's import line ("Import is on, but no file was found at ..."), under the status line: shown while the import
   *  setting is on, its file is missing and the account is not yet imported; hidden (null) otherwise. The journal decides which. */
  private void showImportNotice(String message){EviLivePanel p=panel;if(p!=null)p.importNotice(message);}
  // Filepath has no Files.readString()-equivalent single-call helper, so this reads the whole
  // (small, single-line) file through its buffered UTF-8 Reader and trims it the same way the
  // old Files.readString(...).trim() call did.
  private static String readTrimmed(Filepath fp) throws IOException {
    try(BufferedReader r=fp.openBufferedReader()) {
      StringBuilder sb=new StringBuilder();
      int c;
      while((c=r.read())!=-1)sb.append((char)c);
      return sb.toString().trim();
    }
  }
  /** Whether the player is in an instanced scene -- a raid, and most other kitted-out content.
   *
   *  Uses WorldView.isInstance() rather than the deprecated Client.isInInstancedRegion(). Returns
   *  FALSE on any failure, which is the fail-open direction that matters here: an unknown scene
   *  leaves the tier working exactly as it did before rather than silently switching a feature off
   *  the player turned on.
   *
   *  Captured on the CLIENT THREAD into a volatile field and read from the poll thread, exactly like
   *  activeSlotItemIds, cashStack and inventoryQuantities. An earlier version of this read the
   *  client directly from suggestionQuery(), which runs on the evi-local-sender executor -- a
   *  RuneLite API read off the client thread, which this plugin forbids itself everywhere else (see
   *  verifyPersistedHolding, which deliberately does not refresh the inventory for this reason).
   *  Besides being the kind of thing a Hub reviewer flags, it could read a half-swapped world view
   *  during a region change and report an instance in the overworld, or none inside a raid -- which
   *  is precisely the case this feature exists for. */
  private void refreshInInstance() {
    try {
      net.runelite.api.WorldView wv=client.getTopLevelWorldView();
      inInstance = wv!=null && wv.isInstance();
    } catch(Throwable ignored){ inInstance=false; }
  }

  private void reset(){ready=false;warmTicks=0;ticks=0;profile=null;account=null;economy=null;session=UUID.randomUUID().toString();seq=0;Arrays.fill(slots,null);activeSlotItemIds=Collections.emptySet();activeOffers=Collections.emptyList();geOfferRows=Collections.emptyList();if(panel!=null)panel.offers(geOfferRows);skippedItemIds.clear();unverifiableItemIds.clear();freeSlots=-1;collectableSlots=-1;bridgePositionItems=Collections.emptySet();cashStack=-1;openOfferItemId=-1;membersWorld=null;heldForResale.clear();inventoryItemIds=Collections.emptySet();inventorySnapshotEstablished=false;inventoryQuantities=Collections.emptyMap();inInstance=false;}
  // Tracks heldForResale from a single slot's old -> new transition. Two independent things can
  // happen here, and either, both, or neither may apply on a given tick:
  //  1. A buy-side offer (BUYING/BOUGHT/CANCELLED_BUY) that had at least one unit filled just
  //     emptied (collected) -- those units are now sitting in the player's inventory, unsold.
  //     Recorded (or refreshed, if some of this item was already held) so it can be suggested
  //     again as a sell.
  //  2. The player has started (or finished, or cancelled) a sell-side offer for an item that was
  //     being held -- they're already acting on it, so it's no longer something EVI needs to
  //     remind them about.
  // old==null (the very first observation of a slot on login) never triggers either case --
  // nothing was actually "just collected" or "just started selling" at that point.
  private void updateHeldForResale(Offer old,Offer n) {
    if(old==null)return;
    if(buy(old.state) && !"EMPTY".equals(old.state) && "EMPTY".equals(n.state) && old.filled>0) {
      // The REAL average price paid per unit -- old.spent/old.filled, i.e. what the GE actually
      // charged, never old.price (the offer's set/max price, which a buy can fill below). old.spent
      // can legitimately be 0 or unset on data observed before this field existed; that's sent as
      // price 0, which computeHoldingSuggestion (bridge side) already treats as "unknown, fall back
      // to the plain reminder" -- never a fabricated/guessed cost basis.
      int avgPrice=old.spent>0?(int)Math.round(old.spent/(double)old.filled):0;
      heldForResale.put(old.itemId,new Held(old.itemId,old.filled,old.name,old.offerId,avgPrice));
    }
    if(!"EMPTY".equals(n.state) && !buy(n.state)) {
      heldForResale.remove(n.itemId);
    }
  }
  // Refreshes cashStack from the inventory every tick. Deliberately its own try/catch so a
  // container lookup hiccup (e.g. mid-transition between logged-out and logged-in) never breaks
  // the rest of onGameTick -- it just leaves cashStack at its last known value, or -1 if there
  // never was one. Both IDs are literals rather than RuneLite ItemID constants, since those move
  // between packages across client versions.
  //
  // COINS (995) PLUS PLATINUM TOKENS (13204, worth 1,000 gp each), as of Jagex's "Beyond Max Cash"
  // update on 30 September 2026: the Grand Exchange now settles an offer from both currencies
  // together, so a player's real spending power is the sum. Counting coins alone -- which was
  // CORRECT the day before, because the GE would not take tokens -- now understates anyone holding
  // wealth as tokens, and EVI would quietly size their trades as though the tokens were not there.
  //
  // This is why cashStack is a long. A coin stack and a token stack are each still capped at
  // 2,147,483,647, so coins alone always fitted an int; 2.147b coins plus 2.147b tokens is about
  // 2.149 TRILLION, which does not. Item stack sizes and buy limits were not changed by that
  // update, so every quantity in this plugin is still safely an int.
  private void refreshCashStack() {
    try {
      net.runelite.api.ItemContainer inv=client.getItemContainer(GeIds.INVENTORY);
      if(inv==null){cashStack=-1;return;}
      cashStack=(long)inv.count(995)+(long)inv.count(13204)*1000L;
    } catch(Exception ex){cashStack=-1;}
  }
  // Refreshes inventoryItemIds (see its own field doc) from the inventory's contents every tick,
  // same defensive try/catch pattern as refreshCashStack -- a lookup hiccup just leaves the
  // snapshot at its last known value, or empty if there never was one. Deliberately NOT called
  // from verifyPersistedHolding itself: that method runs on the background poll thread, and
  // RuneLite's Client API must only ever be touched from the client thread (onGameTick runs there,
  // which is why this -- like refreshCashStack -- is safe to call from there directly).
  private void refreshInventoryItemIds() {
    try {
      net.runelite.api.ItemContainer inv=client.getItemContainer(GeIds.INVENTORY);
      if(inv==null)return; // leave the previous snapshot (and inventorySnapshotEstablished) in place
      Set<Integer> ids=new java.util.HashSet<>();
      Map<Integer,Integer> quantities=new java.util.HashMap<>();
      for(net.runelite.api.Item item:inv.getItems()) {
        if(item==null || item.getId()<=0 || item.getQuantity()<=0)continue;
        int resolvedId=resolveNotedItemId(item.getId());
        ids.add(resolvedId);
        quantities.merge(resolvedId,item.getQuantity(),Integer::sum);
      }
      // A holding EVI could not see may be in hand NOW -- collecting a filled buy is exactly this
      // event -- so the moment the inventory changes at all, every such exclusion is dropped and the
      // item becomes eligible again on the next poll. This is what keeps the exclusion transient;
      // without it the set would simply be a slower version of the session blacklist it replaced.
      dropUnverifiableOnInventoryChange(ids);
      inventoryItemIds=ids;
      inventoryQuantities=quantities;
      inventorySnapshotEstablished=true;
    } catch(Exception ignored){} // leave the previous snapshot in place
  }
  /** A holding EVI could not see may be in hand NOW -- collecting a filled buy is exactly this
   *  event -- so the moment the inventory's composition changes, every such exclusion is dropped and
   *  the item is eligible again on the next poll. This is what keeps the exclusion transient; without
   *  it the set would be a slower version of the session blacklist it replaced.
   *
   *  Its own method so it can be tested without a client: the surrounding refreshInventory() needs a
   *  real ItemContainer, and an untestable clear is how a transient exclusion quietly becomes a
   *  permanent one again. */
  void dropUnverifiableOnInventoryChange(Set<Integer> ids) {
    if(!unverifiableItemIds.isEmpty() && !ids.equals(inventoryItemIds))unverifiableItemIds.clear();
  }
  // A noted item's own ItemContainer/Item.getId() is a DIFFERENT item ID from the unnoted item it
  // represents -- confirmed against a real report (a noted stack in the inventory, "Suggest selling
  // idle inventory" turned on, still no suggestion even though the item itself has healthy GE
  // volume and a live price). Both
  // verifyPersistedHolding and computeInventorySuggestion (bridge-side, via the itemId this sends)
  // need the UNNOTED id -- that's what the OSRS Wiki price API and /mapping endpoint are keyed by,
  // not the note's own id -- so every id collected in refreshInventoryItemIds() is resolved through
  // here first. Standard RuneLite idiom: an ItemComposition's getNote()==799 marks it as a note,
  // and getLinkedNoteId() then gives the id of the unnoted item it represents. Falls back to the
  // original id on any lookup failure, or when the item simply isn't noted to begin with (the
  // overwhelmingly common case), so this is always safe to call unconditionally.
  private int resolveNotedItemId(int itemId) {
    try {
      net.runelite.api.ItemComposition comp=client.getItemDefinition(itemId);
      if(comp!=null && comp.getNote()==799)return comp.getLinkedNoteId();
    } catch(Exception ignored){}
    return itemId;
  }
  // Verifies a persistent-fallback "you're still holding this" suggestion (Suggestion.persisted,
  // see its own doc) against the player's actual current inventory before trusting it. The
  // bridge's own reconstruction of what's still held comes from journaled GE offers, not a live
  // read of the game -- it can go stale (a position that, in reality, was fully resold through
  // some combination of separate sale offers the automatic FIFO matcher didn't perfectly
  // reconcile), and since the bridge only ever nominates its single earliest-bought candidate per
  // poll, one stale entry can otherwise crowd out a real, currently-held item behind it forever.
  // Called only for a `persisted` suggestion -- a live-observed one (this session's own
  // buy-then-collect, see updateHeldForResale) is trusted as-is, exactly as before this existed.
  //
  // Reads inventoryItemIds (a client-thread-refreshed snapshot, see its own field doc) rather than
  // calling client.getItemContainer() directly: this method is called from pollSuggestion(), which
  // runs on the background `sender` executor, not an event-subscriber callback -- RuneLite's own
  // Client API asserts that client.* access happens on the client thread, and an earlier version
  // of this method that called it directly from here threw "AssertionError: must be called on
  // client thread" on every single poll once a persisted suggestion existed to verify, confirmed
  // against a real client.log. Deliberately fails open (treats the suggestion as verified) until
  // inventoryItemIds has been successfully refreshed at least once this session
  // (inventorySnapshotEstablished) -- distinct from the set simply being empty, since a genuinely
  // empty inventory (everything banked) is a real, valid state that must still verify false for an
  // item that isn't there. A hiccup or a not-yet-loaded inventory should never silently suppress a
  // real reminder; a confirmed-empty one should. Checks the inventory only, not the bank -- an item
  // collected straight to the bank rather than the inventory will not be recognised this way; a
  // known, accepted limitation for now, not a bug (see the project notes).
  private boolean verifyPersistedHolding(Suggestion s) {
    if(!inventorySnapshotEstablished)return true;
    return inventoryItemIds.contains(s.itemId);
  }
  // Caps a "sell" suggestion's quantity down to what's actually sitting in the inventory right
  // now, when that's less than what the bridge suggested -- confirmed against a real report: a
  // partially-filled buy order (13 of 27 filled, then cancelled) left the journal correctly
  // believing 13 were bought and never resold, but only 11 were actually still in the inventory
  // (the other 2 presumably left some way the GE-only journal has no visibility into -- used for
  // something in-game, banked and later withdrawn differently, etc.). verifyPersistedHolding above
  // only checks that the item is present AT ALL, never that the remembered quantity still matches,
  // so a stale-but-nonzero remaining count like this sailed straight through it. This applies to
  // both a persisted (journal-reconstructed) and a live (this-session-observed) holding suggestion
  // alike -- either one's remembered quantity can in principle drift from reality the same way, and
  // there is no correctness reason to trust one path's number more than the other's here. Deliberately
  // only ever reduces, never increases (a genuinely bigger inventory count than suggested says
  // nothing wrong -- there could be older stock the suggestion isn't even about) and only overrides
  // when inventorySnapshotEstablished, same fail-open-until-refreshed posture as
  // verifyPersistedHolding, so a hiccup or not-yet-loaded inventory never wrongly zeroes out a real
  // suggestion. Never touches a "buy" suggestion, where quantity means something entirely different
  // (how many to acquire, not how many are already held).
  private void correctSellQuantityAgainstInventory(Suggestion s) {
    if(s==null || !"sell".equals(s.action) || !inventorySnapshotEstablished)return;
    Integer held=inventoryQuantities.get(s.itemId);
    int actualHeld=held==null?0:held;
    if(actualHeld>0 && actualHeld<s.quantity) {
      s.reasoning=(s.reasoning==null?"":s.reasoning+" ")+
        "(Reduced from "+s.quantity+" to the "+actualHeld+" actually still in your inventory -- EVI's recorded quantity for this was stale.)";
      s.quantity=actualHeld;
    }
  }
  // Refreshes openOfferItemId (see its own field doc) from GEOffer every tick, same defensive
  // try/catch pattern as refreshCashStack -- a lookup hiccup just leaves it at "no slot open"
  // rather than breaking the rest of onGameTick.
  private void refreshOpenOfferItemId() {
    try { openOfferItemId = geOffer.isSlotOpen() ? geOffer.currentItemId() : -1; }
    catch(Exception ex){ openOfferItemId=-1; }
  }
  // Refreshes membersWorld (see its own field doc) from the client's world type every tick, same
  // defensive pattern as the refreshes above: a lookup hiccup leaves it at "not known".
  private void refreshMembersWorld() {
    try { java.util.Set<net.runelite.api.WorldType> types=client.getWorldType(); membersWorld=types==null?null:types.contains(net.runelite.api.WorldType.MEMBERS); }
    catch(Exception ex){ membersWorld=null; }
  }
  // Recomputes activeSlotItemIds from the current slots[] snapshot; must be called (on the client
  // thread, same as every other slots[] mutation) right after any change to that array so the
  // poll thread's view never goes stale for longer than one game tick.
  private void refreshActiveSlotItemIds() {
    Set<Integer> ids=new TreeSet<>();
    List<ActiveOffer> offers=new ArrayList<>();
    List<OfferRow> rows=new ArrayList<>();
    int free=0,collectable=0;
    // A null slot is one never observed this session (before the login capture fills all 8), not one
    // seen to be empty. Counting it as free would be a fabricated reading, and counting it as busy
    // would invent a constraint, so an incomplete snapshot reports nothing at all.
    boolean established=true;
    for(Offer o:slots) {
      if(o==null){established=false;continue;}
      if("EMPTY".equals(o.state)){free++;continue;}
      ids.add(o.itemId);
      if(terminal(o))collectable++;
      else offers.add(new ActiveOffer(o.itemId,o.price,buy(o.state),o.name,Math.max(0,o.total-o.filled)));
      rows.add(new OfferRow(o.slot,o.itemId,o.price,o.filled,o.total,buy(o.state),o.name,o.state));
    }
    freeSlots=established?free:-1;
    collectableSlots=established?collectable:-1;
    activeSlotItemIds=ids;
    activeOffers=offers;
    geOfferRows=Collections.unmodifiableList(rows);
    if(panel!=null)panel.offers(geOfferRows);
  }

  // Colour scheme changes take effect at once: the panel repaints itself rather than waiting for a
  // client restart, which for a purely cosmetic setting would read as the setting not working.
  // The risk level likewise: chosen in the settings panel, the sidebar's buttons follow at once.
  @Subscribe public void onConfigChanged(ConfigChanged e) {
    if(!CONFIG_GROUP.equals(e.getGroup()))return;
    if("panelTheme".equals(e.getKey())){if(panel!=null)panel.applyTheme(config.panelTheme());}
    else if(RISK_KEY.equals(e.getKey())||SHARE_KEY.equals(e.getKey())){if(panel!=null)panel.riskLevel(config.riskLevelV2(),config.maxTradeShare());}
    // On or off: the journal re-reads the setting, so the import line appears or goes at once, not at the next packet.
    else if("importBridgeHistory".equals(e.getKey())){PluginJournal j=journal;if(j!=null)j.importRequested();}
    else if(ShareInvite.CONFIG_KEY.equals(e.getKey())){if(panel!=null)panel.shareInvite(shareInviteShown());}
  }
  /** One of the sidebar's three risk buttons was pressed (Swing EDT).
   *
   *  Writes the SAME config key the settings panel's "Risk level" uses, through ConfigManager, so the
   *  two places can never disagree and the choice is stored exactly as if it had been made in the
   *  settings. The next poll reads it -- and one is asked for straight away, like Skip, so the press
   *  is seen to take. Nothing about the level itself lives in the plugin: it is sent as risk= and the
   *  bridge decides what it means. */
  void chooseRisk(RiskLevel level) {
    if(level==null)return;
    java.util.function.BiConsumer<String,String> write=configWriter;
    if(write!=null)write.accept(RISK_KEY,level.name());
    else if(configManager!=null)configManager.setConfiguration(CONFIG_GROUP,RISK_KEY,level.name());
    if(panel!=null)panel.riskLevel(level,config==null?null:config.maxTradeShare());
    if(running && sender!=null)sender.execute(()->pollSuggestion(lifecycle));
  }

  @Subscribe public void onGameStateChanged(GameStateChanged e) {
    // The latest Wiki prices are fetched only while a player is logged in.
    PriceDataService p=prices;
    if(p!=null)p.loggedIn(e.getGameState()==GameState.LOGGED_IN);
    if(e.getGameState()==GameState.LOGIN_SCREEN || e.getGameState()==GameState.HOPPING || e.getGameState()==GameState.CONNECTION_LOST) {
      if(ready)enqueue(false);
      reset();
    }
  }
  @Subscribe public void onGameTick(GameTick e) {
    if(!running)return;
    if(client.getGameState()!=GameState.LOGGED_IN)return;
    try { suggestionHintWidget.update(); } catch (Exception ignored) { } // never let a widget hiccup break observation
    try { itemSelectWidget.update(); } catch (Exception ignored) { }
    refreshCashStack();
    refreshInventoryItemIds();
    refreshInInstance();
    refreshOpenOfferItemId();
    refreshMembersWorld();
    String current=configManager.getRSProfileKey();
    if(current==null)return;
    String currentEconomy=EconomyScope.of(client.getWorldType());
    if(profile!=null&&(!profile.equals(current)||!currentEconomy.equals(economy))){if(ready)enqueue(false);reset();}
    if(!ready) {
      if(++warmTicks<5)return; // Initial login emits temporary EMPTY slots. Never treat these as trades.
      try {
        profile=current;
        economy=currentEconomy;
        // Ordinary worlds retain the existing account pseudonym for compatibility.
        account=AccountIds.of(salt,profile,economy);
        accountProfiles.put(account,profile);
        rememberLastProfile(profile);
        tellCharacterName();
        GrandExchangeOffer[] offers=client.getGrandExchangeOffers();
        if(offers==null || offers.length!=8)return;
        for(int i=0;i<8;i++)slots[i]=capture(i,offers[i],null,false);
        refreshActiveSlotItemIds();
        ready=true;enqueue(true);
      } catch(Exception ex){reset();}
    } else if(++ticks%15==0){tellCharacterName();enqueue(true);}
  }
  /** The account's character name to the journal (its import takes the flips kept under that name), once it is known.
   *  Client thread (the local player is read here); the journal only stores it. Told again until it is known. */
  private PluginJournal namedJournal;
  private String namedAccount;
  private void tellCharacterName() {
    PluginJournal j=journal;
    String a=account;
    if(j==null||a==null||(j==namedJournal&&a.equals(namedAccount)))return;
    j.accountIdentity(a,profile,economy); // what this folder's salt was combined with: the import's old-salt match needs it
    try {
      net.runelite.api.Player me=client.getLocalPlayer();
      String name=me==null?null:me.getName();
      if(name==null||name.trim().isEmpty())return;
      j.characterName(a,name);
      namedJournal=j;
      namedAccount=a;
    } catch(Exception ignored) { } // the name is only for the import; a failure tries again at the next packet
  }
  @Subscribe public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e) {
    if(!ready||client.getGameState()!=GameState.LOGGED_IN||!profile.equals(configManager.getRSProfileKey())||!EconomyScope.of(client.getWorldType()).equals(economy))return;
    int i=e.getSlot();if(i<0||i>=8||e.getOffer()==null)return;
    Offer old=slots[i];
    slots[i]=capture(i,e.getOffer(),old,true);
    updateHeldForResale(old,slots[i]);
    refreshActiveSlotItemIds();
    enqueue(true);
  }
  private Offer capture(int i,GrandExchangeOffer o,Offer old,boolean observing) {
    Offer n=new Offer();n.slot=i;n.state=o==null?"EMPTY":o.getState().name();
    if(!"EMPTY".equals(n.state)) {
      n.itemId=o.getItemId();n.price=o.getPrice();n.total=o.getTotalQuantity();n.filled=o.getQuantitySold();n.spent=o.getSpent();
      n.name=client.getItemDefinition(n.itemId).getName();
    }
    boolean same=old!=null&&old.itemId==n.itemId&&old.price==n.price&&old.total==n.total&&
      old.filled<=n.filled&&old.spent<=n.spent&&buy(old.state)==buy(n.state)&&
      !(terminal(old)&&!terminal(n));
    n.offerId=same?old.offerId:UUID.randomUUID().toString();
    n.knownStart=same?old.knownStart:(observing&&old!=null&&"EMPTY".equals(old.state)&&n.filled==0&&!"EMPTY".equals(n.state));
    // How many game ticks passed between seeing this offer empty and its first fill. This is what
    // separates a real trade from a "margin check" -- the one-item probe a flipper places at a
    // deliberately bad price to discover the true spread, which fills almost instantly and is not a
    // trade at all. Without it, a probe that buys high and sells low is journalled as a small losing
    // flip and quietly drags down win rates, the fill model and EVI's own scorecard.
    //
    // startTick is only taken when the offer was actually seen unfilled (-1 otherwise, e.g. an offer
    // already part-filled when first observed at login), so ticksToFill stays -1 = "not known"
    // rather than a fabricated 0. Nothing downstream may classify an offer without this number.
    int tick=-1;
    try { tick=client.getTickCount(); } catch(Exception ignored) { }
    n.startTick=same?old.startTick:(n.filled==0&&tick>=0?tick:-1);
    n.ticksToFill=same?old.ticksToFill:-1;
    if(n.ticksToFill<0 && n.filled>0 && n.startTick>=0 && tick>=n.startTick)n.ticksToFill=tick-n.startTick;
    return n;
  }
  private static boolean buy(String s){return s.equals("BUYING")||s.equals("BOUGHT")||s.equals("CANCELLED_BUY");}
  private static boolean terminal(Offer o){return o.state.equals("BOUGHT")||o.state.equals("SOLD")||o.state.startsWith("CANCELLED")||(o.total>0&&o.total==o.filled);}
  private void enqueue(boolean loggedIn) {
    if(account==null)return;
    Packet p=new Packet();p.session=session;p.account=account;p.seq=++seq;p.ts=System.currentTimeMillis();p.loggedIn=loggedIn;
    if(loggedIn)p.offers.addAll(Arrays.asList(slots));
    String json=gson.toJson(p); // immutable snapshot created on the client thread
    // To the plugin's own journal. Only handed over here; the journal's thread does the rest.
    PluginJournal j=journal;
    if(j!=null)j.offerPacket(json);
  }
  // Polls regardless of whether a GE slot is open, so the sidebar's "Current suggestion" summary
  // stays populated like Copilot's does, not just while an offer screen happens to be on screen.
  // (It used to gate on slotOpenHint; that left the panel stuck on "No suggestion yet." for anyone
  // checking it outside an open offer screen, even with eligible reviewed-flip history.) The engine
  // answers from data already in memory (no request is made per poll), so this stays cheap. Only ever updates the in-memory cache the hotkey handler reads — never writes
  // anything back to the game itself; filling still separately requires the real GE prompt to be
  // open for the matching item (see GEOffer.openFieldFor), so this change does not loosen that gate.
  /** The status line (when the Wiki prices last arrived) and the price-history line, from one answer. */
  private void showInProcessStatus(InProcessEngine.Answer answer) {
    if(answer==null)return;
    status(inProcessStatus(answer.latestFetchedAtMs));
    EviLivePanel p=panel;
    if(p!=null)p.backfillNotice(answer.backfillLine);
  }
  /** The engine's status line. "Connected" only once prices have actually arrived (the dot turns green on it). */
  static String inProcessStatus(long latestFetchedAtMs) {
    if(latestFetchedAtMs<=0)return IN_PROCESS_WAITING;
    return "Connected to OSRS Wiki prices. Last update: "+java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss",java.util.Locale.US)
      .format(java.time.Instant.ofEpochMilli(latestFetchedAtMs).atZone(java.time.ZoneId.systemDefault()));
  }
  private void pollSuggestion(long generation) {
    if(!running || generation!=lifecycle)return;
    try {
      final String query=suggestionQuery();
      final AnswerSource own=answers;
      if(own==null){updatePanelSuggestion(null,"EVI's built-in engine did not start. Details are in the client log.");return;}
      importBridgePreferences();refreshBlockedSection();
      String json=own.get(query);
      if(!running || generation!=lifecycle)return;
      InProcessEngine.Answer answer=own.lastAnswer();
      // Still working on this query and nothing to reuse: leave the sidebar exactly as it is (never blank it).
      if(json==null && own.lastGetStatus()==InProcessTransport.PENDING)return;
      showInProcessStatus(answer);
      if(json==null){updatePanelSuggestion(null,answer==null?null:answer.unavailable);return;}
      SuggestionResponse r=gson.fromJson(json,SuggestionResponse.class);
      Suggestion s=r==null?null:r.suggestion;
      // Remember which positions the bridge believes are held, so the next poll can confirm them
      // against the real inventory. An older bridge that sends none leaves this empty.
      if(r!=null && r.slots!=null && r.slots.positionItems!=null) {
        Set<Integer> named=new TreeSet<>();
        for(int id:r.slots.positionItems)named.add(id);
        bridgePositionItems=Collections.unmodifiableSet(named);
      }
      // A reconstructed-from-history suggestion (see Suggestion.persisted) that isn't actually
      // in the inventory right now is stale, not real -- treat it exactly like a manual "Skip
      // this suggestion" click (excludes it for the rest of this session) and re-poll immediately
      // instead of caching or showing it, so the bridge's next call naturally moves on to its
      // next-earliest candidate rather than repeating the same wrong one every two seconds. Never
      // touches suggestionCache/openItemPriceCache here, so whatever they already held (a prior
      // valid suggestion, or nothing) is left exactly as it was until the retry resolves.
      if(s!=null && s.persisted && !verifyPersistedHolding(s)) {
        unverifiableItemIds.add(s.itemId);
        if(running && sender!=null)sender.execute(()->pollSuggestion(lifecycle));
        return;
      }
      correctSellQuantityAgainstInventory(s);
      suggestionCache.set(s);
      openItemPriceCache.set(r==null?null:r.openItemPrice);
      // A buy held back to keep room for the sells already owed is a different answer from nothing
      // being eligible, and saying "nothing passes your settings" there would be plainly wrong.
      InProcessEngine.Answer used=answer;
      String diagnosis=used!=null&&!used.buysReady
        // the buy tiers were not asked at all, so neither "nothing passes" nor the slot reserve is the reason
        ?BUYS_PAUSED
        :r!=null&&r.slots!=null&&r.slots.buysHeldForExits
        ?sellReserveMessage(r.slots.sellSlotsOwed)
        :noSuggestionMessage(config.includeMarketWide(),config.marginSafetyCushion(),freeSlots,collectableSlots,config.suggestionSource())
          +reachableMessage(r==null?null:r.reachable);
      updatePanelSuggestion(s,s==null?diagnosis:null);
      // The session list is what shapes everything above it; kept on screen so it is never the
      // invisible reason a suggestion looks poor.
      if(panel!=null)panel.skipped(setAsideCount());
      // The second and third positions, when asked for. An older bridge sends none and the section
      // stays hidden; so does a default install, which never asks for more than one.
      if(panel!=null)panel.alsoSuggested(r==null||r.additional==null
        ?java.util.Collections.emptyList():java.util.Arrays.asList(r.additional));
      if(panel!=null)panel.profit(r==null?null:r.profit);
      updatePanelOfferHint(activeOffers,r==null?null:r.slotPrices,r==null?null:r.slotFill,r==null?null:r.relistAdvice,r==null?null:r.sellBreakEven);
    } catch(Throwable ex){
      // Deliberately catches Throwable, not just Exception, and kept that way permanently: a
      // periodic ScheduledExecutorService task that lets ANY throwable escape -- including an
      // Error, which a plain "catch(Exception)" does NOT catch -- gets silently cancelled forever
      // by the executor, with nothing printed anywhere. (This is exactly how a package-private
      // RiskLevel enum being inaccessible to RuneLite's config proxy took suggestions down
      // completely -- see RiskLevel.java and the project notes of 15 Sept 2026.) Logging the full trace
      // and showing a short message keeps any future unanticipated failure visible and recoverable
      // instead of silently killing suggestions forever.
      log.warn("EVI Live: suggestion check failed",ex);
      updatePanelSuggestion(null,"Suggestion check failed ("+ex.getClass().getSimpleName()+"). See client.log for details.");
    }
  }
  // Called only from the sidebar's "Skip this suggestion" button (Swing EDT). Excludes the
  // currently shown item from ranking for the rest of this session and immediately re-polls
  // rather than waiting up to 2s for the next scheduled tick, so the button feels responsive.
  // Never touches the game itself -- purely which suggestion gets shown/filled next.
  /** Puts back everything this session has set aside, and re-polls so the effect is immediate.
   *
   *  The session list is cumulative, and until 28 Sept 2026 it was invisible and one-way: Skip,
   *  Block, "Mark as personal use", "I don't have this anymore" and the stale-holding re-check all
   *  feed it, and it cleared only on a profile change or a client restart. Working down from a
   *  pick worth a few hundred thousand gp to one worth a few hundred looked like a ranking failure, and was really an
   *  accumulated filter with nothing on screen admitting it existed.
   *
   *  Deliberately does NOT touch the bridge's permanent blocklist: an item blocked there stays
   *  blocked and is undone from the scanner's Blocked items list, as before. This only undoes what
   *  this client session did to itself. */
  private void clearSkips() {
    skippedItemIds.clear();
    skipMemory.clear(); // this account's four-hour skips go too: the button promises "show them again"
    if(panel!=null)panel.skipped(0);
    if(running && sender!=null)sender.execute(()->pollSuggestion(lifecycle));
  }

  /** How many items are set aside right now: the session's own exclusions plus this account's
   *  four-hour Skips, each item counted once. Drives the sidebar's "N items set aside" line, so an
   *  expired Skip drops out of the count on the next poll. */
  int setAsideCount() {
    Set<Integer> all=new java.util.HashSet<>(skippedItemIds);
    all.addAll(skipMemory.active());
    return all.size();
  }

  private void skipSuggestion() {
    Suggestion s=suggestionCache.get();
    if(s==null)return;
    // Four hours on this account, surviving hops, relogs and restarts (see SkipMemory). With no
    // account known yet there is nowhere per-account to keep it, so it lasts the session instead --
    // the press must still apply to the item it was pressed on.
    if(!skipMemory.skip(s.itemId))skippedItemIds.add(s.itemId);
    suggestionCache.set(null);
    updatePanelSuggestion(null,"Skipped for 4 hours. Checking for the next suggestion...");
    if(running && sender!=null)sender.execute(()->pollSuggestion(lifecycle));
  }
  // Called only from the sidebar's "Reset" button beside the profit line (Swing EDT). Starts the count
  // from now; nothing about the journal or the flips changes, only where this one line counts from. The plugin keeps
  // the reset time under its own keyName and the engine reads it.
  private void resetProfit() {
    if(panel!=null)panel.profitResetPending();
    long now=System.currentTimeMillis();
    inProcessProfitSince=now;
    try{ConfigManager cm=configManager;if(cm!=null)cm.setConfiguration(CONFIG_GROUP,PROFIT_SINCE_KEY,String.valueOf(now));}catch(Exception ignored){}
    if(running && sender!=null)sender.execute(()->pollSuggestion(lifecycle));
  }
  // Called only from the sidebar's "Block this item" button (Swing EDT). Skip made permanent: the item
  // is excluded for this session at once, and kept on THIS character's Block list (BlockedItems), which the engine reads
  // on the next poll. Only buys are blocked -- if the player already holds some, the engine still sends the sell reminder,
  // since going quiet about stock they own is how GP ends up stuck. Undone from the sidebar's Blocked items list.
  private void blockSuggestion() {
    Suggestion s=suggestionCache.get();
    if(s==null)return;
    final int itemId=s.itemId;
    String name=s.name==null||s.name.isEmpty()?"this item":s.name;
    skippedItemIds.add(itemId);
    suggestionCache.set(null);
    String p=currentProfile();
    boolean saved=p!=null && blockedItems.block(p,itemId);
    updatePanelSuggestion(null,saved
      ?"Blocked "+name+" on this character. EVI won't suggest buying it again; undo it under Blocked items. Checking for the next suggestion..."
      :"Set "+name+" aside for this session only: EVI could not save the block. Checking for the next suggestion...");
    if(saved)refreshBlockedSection();
    if(running && sender!=null)sender.execute(()->pollSuggestion(lifecycle));
  }
  // Called only from an Unblock button in the sidebar's Blocked items list (Swing EDT). Takes the item off THIS character's
  // list, lets it back into the session at once (Block also set it aside for the session), and re-polls so a buy of it can be
  // suggested straight away.
  void unblockItem(int itemId) {
    String p=currentProfile();
    if(p==null || !blockedItems.unblock(p,itemId)) {
      if(panel!=null)panel.blockedNotice("EVI could not unblock that item just now. Try again once you are logged in.");
      return;
    }
    skippedItemIds.remove(itemId);
    if(panel!=null)panel.blockedNotice(null);
    refreshBlockedSection();
    if(panel!=null)panel.skipped(setAsideCount());
    if(running && sender!=null)sender.execute(()->pollSuggestion(lifecycle));
  }
  /** The one-time import of the old companion app's preferences (blocked items per character, the profit reset once;
   *  BridgePreferencesImport) under the trade-history import setting. On the poll thread (it may read one small file). Never throws. */
  private void importBridgePreferences() {
    try {
      ConfigManager cm=configManager;
      if(cm==null)return;
      BridgePreferencesImport.Result r=BridgePreferencesImport.run(config.importBridgeHistory(),inProcessDir,currentProfile(),blockedItems,
        new BridgePreferencesImport.Config(){
          public String get(String profile,String key){return profile==null?cm.getConfiguration(CONFIG_GROUP,key):cm.getConfiguration(CONFIG_GROUP,profile,key);}
          public void set(String profile,String key,String value){if(profile==null)cm.setConfiguration(CONFIG_GROUP,key,value);else cm.setConfiguration(CONFIG_GROUP,profile,key,value);}
        },inProcessProfitSince,text->gson.fromJson(text,JsonElement.class),System.currentTimeMillis(),m->log.info(m),preferencesImportLogged);
      if(r!=null && r.profitSince!=null)inProcessProfitSince=r.profitSince; // stored by the import; the engine reads this field
    } catch(Exception ex){log.warn("EVI: the preferences import did not complete",ex);}
  }
  /** Redraws the sidebar's Blocked items list from this character's stored list (any thread). Names come from
   *  the item list the price layer already holds, never from a request; an item it does not know is named by its id. */
  void refreshBlockedSection() {
    EviLivePanel pl=panel;
    if(pl==null)return;
    String p=currentProfile();
    List<Integer> ids=p==null?Collections.emptyList():blockedItems.all(p);
    com.evi.live.market.ItemCatalog cat=null;
    try{PriceDataService pd=prices;cat=pd==null?null:pd.snapshot().catalog;}catch(Exception ignored){}
    List<EviLivePanel.BlockedRow> rows=new ArrayList<>();
    for(int id:ids) {
      com.evi.live.market.ItemCatalog.Item it=cat==null?null:cat.get(id);
      rows.add(new EviLivePanel.BlockedRow(id,it==null||it.name==null||it.name.isEmpty()?"Item "+id:it.name));
    }
    pl.blockedItems(rows);
  }
  // Called only from the sidebar's "I took this one" button (Swing EDT). Records that the player
  // actually acted on this exact suggestion, which is the only thing that lets EVI honestly say what
  // following it has been worth: every other reading of its track record infers acceptance from an
  // offer appearing soon after a suggestion, and that cannot tell a followed pick from a trade the
  // player meant to make anyway. Unlike Skip, Block and Personal use, this changes NOTHING about what
  // EVI suggests -- it neither hides the pick nor asks for a new one -- so the suggestion stays on
  // screen and the button simply flips to its undo wording. Pressing it again takes it back, because
  // a mistaken tap that cannot be undone is a tap players stop making, and a track record people are
  // afraid to touch is worth less than no track record. The POST runs on the background sender.
  private void acceptSuggestion() {
    Suggestion s=suggestionCache.get();
    if(s==null||s.id==null||s.id.isEmpty())return;
    final boolean nowAccepted=!s.accepted;
    s.accepted=nowAccepted;
    // Remembered LOCALLY until the engine's answer echoes the same for this id. Without it the press
    // races the two-second poll: a poll already in flight when the button is clicked returns the
    // suggestion with accepted=false, because the record has not been written yet, and
    // updatePanelSuggestion resets the button. The player sees their click not register, presses
    // again, and the second press posts accepted:false -- silently undoing the acceptance they just
    // made. That corrupts the one observation this whole feature exists to produce.
    pendingAccept.put(s.id,nowAccepted);
    if(panel!=null)panel.actions(true,nowAccepted,"sell".equals(s.action),
      "sell".equals(s.action)&&s.buyId!=null&&!s.buyId.isEmpty(),"buy".equals(s.action));
    final String id=s.id;final int itemId=s.itemId;
    // The plugin records the press itself (SuggestionRecords), off the Swing thread.
    SuggestionRecords records=suggestionRecords;final String acct=account;
    if(running && sender!=null && records!=null)sender.execute(()->records.accept(id,acct,itemId,nowAccepted,System.currentTimeMillis()));
  }
  // Called only from the sidebar's "Personal use" button (Swing EDT). For supplies bought via the
  // GE for the player's own use rather than to flip -- e.g. buying cannonballs to actually fire
  // them -- marks the specific buy behind the current "you're holding this, sell it" suggestion as
  // personal use with the bridge (see Store.markPersonalUse in bridge/store.mjs), so that exact
  // purchase stops being surfaced as something to sell and stops ever counting toward profit, even
  // if it's later sold anyway. Only meaningful for a "sell" suggestion the bridge could trace back
  // to one specific buy (Suggestion.buyId, see its own doc) -- a "buy" suggestion, or a holding
  // signal with no identifiable single buy behind it, has nothing to mark, and the sidebar says so
  // rather than silently doing nothing. This is deliberately scoped to THIS one purchase, not the
  // item generally -- flipping this same item again later is unaffected; only unmarks if you
  // explicitly ask (not exposed in the sidebar today, mirrors Store.markPersonalUse's own
  // personal:false path for a future undo). The session-local exclusion (skippedItemIds, clearing
  // any live heldForResale entry) applies immediately regardless of whether the bridge call itself
  // succeeds, so the button feels responsive even against a slow or momentarily unreachable bridge;
  // the actual POST runs on the background sender executor, never the Swing thread.
  private void flagPersonalUse() {
    Suggestion s=suggestionCache.get();
    if(s==null)return;
    if(!"sell".equals(s.action)) {
      if(panel!=null)panel.suggestion("Personal use only applies to a \"you're holding this\" suggestion.");
      return;
    }
    // A sell suggestion with no buy behind it comes from the idle-inventory setting: gear EVI only
    // sees in the inventory and never watched being bought (reported live for a piece of gear a player
    // was wearing). There is no purchase to mark, so the item itself is excluded instead --
    // the bridge keys that by item and applies it only to that same idle-inventory tier, so EVI will
    // still suggest buying the item to flip. Before this, the button correctly refused to mark
    // anything and the suggestion simply came back on the next poll.
    markPersonalUse(s.itemId,s.buyId==null||s.buyId.isEmpty()?null:s.buyId);
  }
  // A holding LINE's menu: "Personal use" (Swing EDT). The lines under Active offers state what this account holds
  // (holdingsAdvice); since 8 Oct 2026 each carries its lot's buyId, and the menu marks THAT purchase exactly as the card's
  // Personal use button marks the card's -- the same journal write, the same sentences. One line is one lot, so a second lot
  // of the same item is its own line and is not touched. A line with no lot has no menu; nothing here guesses one.
  void holdingLinePersonalUse(AdviceCard c) {
    if(c==null || c.buyId==null)return;
    markPersonalUse(c.itemId,c.buyId);
  }
  // A holding LINE's menu: "I don't have this anymore" (Swing EDT) -- the card's Gone, for that line's lot. See above.
  void holdingLineGone(AdviceCard c) {
    if(c==null || c.buyId==null)return;
    markNotHeld(c.itemId,c.buyId);
  }
  // What the card's Personal use does once it knows the item and the buy (null: an idle-stock item mark), shared with the
  // holding lines' menu so the two can never drift apart.
  private void markPersonalUse(final int itemId, final String buyId) {
    skippedItemIds.add(itemId);
    heldForResale.remove(itemId);
    suggestionCache.set(null);
    // The plugin's own journal records the mark (PluginJournal.markPersonalUse / markPersonalUseItem). An item mark takes its
    // kept count from the engine (InProcessEngine.keptFor), naming the presser: the bag the idle-stock tier read for this
    // account under a minute ago -- else the blanket mark.
    PluginJournal j=journal;final String acct=account;final InProcessEngine engine=inProcess;
    final boolean canSave=j!=null && (buyId!=null || com.evi.live.journal.Packet.id(acct));
    updatePanelSuggestion(null,!canSave
      ?"Set aside for this session only: EVI could not save the mark just now. Checking for the next suggestion..."
      :buyId==null
      ?"Marked as yours, not stock. EVI won't suggest selling it again. Checking for the next suggestion..."
      :"Marked as personal use. Checking for the next suggestion...");
    if(running && sender!=null)sender.execute(()->{
      if(canSave){
        if(buyId==null)j.markPersonalUseItem(acct,itemId,engine==null?null:engine.keptFor(acct,itemId,System.currentTimeMillis()));
        else j.markPersonalUse(acct,buyId);
      }
      pollSuggestion(lifecycle);
    });
  }
  // Called only from the sidebar's "I don't have this anymore" button (Swing EDT). The in-game
  // counterpart of the scanner's "Still held" close buttons, for the case this plugin cannot detect
  // on its own: a holding EVI reconstructed from its journal that the player has since used in-game
  // or sold while EVI wasn't watching. The inventory check (verifyPersistedHolding) only suppresses
  // such a suggestion for the current session -- and only when the item isn't in the inventory,
  // which a banked item also isn't -- so without this the same stale reminder came back after every
  // restart. Marks it with the bridge for good (POST /api/suggestion/not-held -> Store.closePosition),
  // which keeps whatever part of that purchase EVI did see sold counted as profit and drops only the
  // remainder. Like flagPersonalUse, the session-local exclusion applies immediately whether or not
  // the bridge call lands, and the call itself runs on the background sender, never the Swing thread.
  private void flagNotHeld() {
    Suggestion s=suggestionCache.get();
    if(s==null)return;
    if(!"sell".equals(s.action) || s.buyId==null || s.buyId.isEmpty()) {
      if(panel!=null)panel.suggestion("\"I don't have this anymore\" only applies to a \"you're holding this\" suggestion EVI can trace back to one specific buy.");
      return;
    }
    markNotHeld(s.itemId,s.buyId);
  }
  // What the card's Gone does once it knows the item and the buy, shared with the holding lines' menu.
  private void markNotHeld(final int itemId, final String buyId) {
    skippedItemIds.add(itemId);
    heldForResale.remove(itemId);
    suggestionCache.set(null);
    // The plugin's own journal records the close (PluginJournal.closePosition, "sold-untracked").
    PluginJournal j=journal;final String acct=account;
    updatePanelSuggestion(null,j==null
      ?"Set aside for this session only: EVI could not save the mark just now. Checking for the next suggestion..."
      :"Marked as no longer held. Checking for the next suggestion...");
    if(running && sender!=null)sender.execute(()->{
      if(j!=null)j.closePosition(acct,buyId);
      pollSuggestion(lifecycle);
    });
  }
  // Built from this plugin's own local config plus live, session-only state (never from observed
  // content) and sent as the GET /api/suggestion query string. Each part is left off entirely
  // when it's at its default/empty, so an untouched config with nothing active or skipped sends
  // an empty query, unchanged from before any of these existed.
  private String suggestionQuery() {
    StringBuilder q=new StringBuilder();
    MinProfitTier minProfit=config.minProfitThreshold();
    if(minProfit!=null && minProfit.gp()>0)q.append("minProfit=").append(minProfit.gp());
    // Opt-in pre-buy safety check (EviLiveConfig.marginSafetyCushion(), off by default): tells the
    // bridge to skip -- and rank the next-best candidate instead of -- any "buy" suggestion whose
    // predicted margin doesn't clear that specific item's own recent price volatility. See
    // marginClearsCushion in bridge/suggestions.mjs for exactly what this does and doesn't check.
    // Left off entirely when disabled, same treatment as every other setting here.
    if(config.marginSafetyCushion()){if(q.length()>0)q.append('&');q.append("cushion=1");}
    String blocklist=sanitizeBlocklist(config.itemBlocklist());
    if(!blocklist.isEmpty()){if(q.length()>0)q.append('&');q.append("blocklist=").append(blocklist);}
    // riskLevelV2, NEVER the retired riskLevel: a High stored under the old key (which only ever tuned
    // the history tier) must not leak into the new meaning. Everyone starts at Low on the new key.
    // While the levels are hidden (RiskLevel.SHOWN false, 7 Oct 2026) nothing is read here at all: a Medium or High
    // stored while the buttons were visible must not keep steering suggestions from a setting nobody can see.
    RiskLevel risk=riskLevelsShown?config.riskLevelV2():RiskLevel.LOW;
    // Left off only for LOW, which is now the bridge's own default -- so Medium is sent explicitly,
    // and choosing it still means Medium rather than silently falling back to the new default.
    if(risk!=null && risk!=RiskLevel.LOW){if(q.length()>0)q.append('&');q.append("risk=").append(risk.param());}
    // includeMarketWide, never the retired includeMarketSuggestions (see EviLiveConfig for why the key moved).
    if(config.includeMarketWide()){if(q.length()>0)q.append('&');q.append("includeMarket=1");}
    // How much of the cash stack one market-wide suggestion may commit (see MaxTradeShare for the
    // backtest that produced the default). OFF is left off the query entirely, like every other
    // setting here, so it behaves exactly as before this existed.
    // No profile= is ever sent (6 Oct 2026). The Starter profile restricted market-wide picks to
    // untaxed items; it is retired and hidden, and a STARTER still stored from an older version is
    // deliberately NOT read here, so nobody stays on it by accident. The bridge still accepts
    // profile=starter from older plugins; this one simply never asks for it.
    MaxTradeShare share=config.maxTradeShare();
    if(share!=null && share.percent()>0){if(q.length()>0)q.append('&');q.append("stackShare=").append(share.percent());}
    // Gear, bulk or all items (see SuggestionFocus); All items, the default since 4.0.0, sends nothing: the engine takes any item.
    SuggestionFocus focus=config.suggestionFocus();
    if(focus!=null && focus.param()!=null){if(q.length()>0)q.append('&');q.append("focus=").append(focus.param());}
    SuggestionSource source=config.suggestionSource();
    if(source!=null && source.param()!=null){if(q.length()>0)q.append('&');q.append("source=").append(source.param());}
    // Only sent when it is above one, so a default install's request is byte-identical to before and
    // an older bridge (which ignores the parameter anyway) is never sent something it has no answer
    // for. The bridge caps it at 3 regardless of what arrives.
    // Only sent when it is not the default, so a default install's request is byte-identical.
    PositionSizing sizing=config.positionSizing();
    if(sizing!=null && sizing.param()!=null){if(q.length()>0)q.append('&');q.append("sizing=").append(sizing.param());}
    MaxPositions positions=config.maxPositions();
    if(positions!=null && positions.count()>1){if(q.length()>0)q.append('&');q.append("maxSuggestions=").append(positions.count());}
    // Trade pace replaced the old minute-labelled durations on 26 Sept: measured over 60 days,
    // nothing under two hours ever completed a round trip inside its own window. The old setting is
    // hidden and no longer read.
    TradePace pace=config.tradePace();
    if(pace!=null && pace.minutes()>0){if(q.length()>0)q.append('&');q.append("duration=").append(pace.minutes());}
    // The Exit-risk check before a "buy" suggestion (see ForecastHorizon/ForecastPolicy; keyName exitRiskCheck since 4.0.0).
    // Both left off entirely when it is OFF (the default), so an untouched config costs no extra archive read and changes
    // nothing -- same treatment as every setting above.
    ForecastHorizon horizon=config.exitRiskCheck();
    if(horizon!=null && horizon.param()!=null) {
      if(q.length()>0)q.append('&');q.append("forecast=").append(horizon.param());
      ForecastPolicy policy=config.forecastPolicy();
      if(policy!=null){q.append("&onForecast=").append(policy.param());}
    }
    // The same account identifier already sent with every ingest packet (see Packet.account),
    // included here too once known (null only before the first login this process has seen) so the
    // bridge can scope its own cross-restart open-position fallback to this account specifically --
    // never mistakenly reminding you to sell stock that's actually held on a different account this
    // same bridge happens to track. See pickPersistentOpenPosition in bridge/suggestions.mjs.
    if(account!=null){if(q.length()>0)q.append('&');q.append("account=").append(account);}
    // Session-only, not a config setting: the player's actual current cash stack, so the bridge
    // never suggests a trade bigger than what's actually affordable right now. Left off entirely
    // when not yet known (cashStack==-1, e.g. the very first ticks after login), same treatment
    // as every other session-only signal here.
    if(cashStack>=0){if(q.length()>0)q.append('&');q.append("cash=").append(cashStack);}
    // Session-only, not a config setting: which kind of world this is (see membersWorld), so the
    // bridge never suggests a members-only item on a free-to-play world, where it can't be traded.
    // Left off entirely until known, exactly like cash= above.
    Boolean members=membersWorld;
    if(members!=null){if(q.length()>0)q.append('&');q.append("members=").append(members?1:0);}
    // Session-only, not a config setting: how much room is left in the Grand Exchange (see the
    // freeSlots/collectableSlots fields). With every one of the 8 slots occupied and nothing waiting
    // to be collected, there is nowhere to put a new offer, so the bridge stops ranking new
    // candidates and says so instead of suggesting a trade that cannot be placed. Left off entirely
    // until known, exactly like cash= and members= above.
    int free=freeSlots,collectable=collectableSlots;
    if(free>=0){if(q.length()>0)q.append('&');q.append("freeSlots=").append(free);}
    if(collectable>=0){if(q.length()>0)q.append('&');q.append("collectable=").append(collectable);}
    // Which of the positions the bridge thinks are held are genuinely in the inventory right now (see
    // bridgePositionItems). Sent only once the inventory has actually loaded -- before that, nothing
    // is confirmed, and the bridge reserves nothing for an unconfirmed position. Sorted, like every
    // other ID list here, so the query string is deterministic.
    // Sent even when EMPTY once a check has actually been made: "checked, found none of them" is the
    // most useful answer there is, because that is what exposes a stale position, and omitting the
    // parameter would make it indistinguishable from "never checked".
    Set<Integer> named=bridgePositionItems;
    if(inventorySnapshotEstablished && !named.isEmpty()) {
      Set<Integer> held=new TreeSet<>();
      Set<Integer> inventory=inventoryItemIds;
      for(int id:named)if(inventory.contains(id))held.add(id);
      if(q.length()>0)q.append('&');
      q.append("heldPositions=");
      boolean first=true;
      for(int id:held){if(!first)q.append(',');q.append(id);first=false;}
    }
    // Session-only, not a config setting: items to leave out of ranking right now because you
    // already have an active/uncollected GE slot for them (activeSlotItemIds, auto-detected) or
    // you manually skipped them via the sidebar (skippedItemIds). Merged and sorted here so the
    // query string -- and any test asserting against it -- is deterministic regardless of
    // iteration order.
    Set<Integer> exclude=new TreeSet<>(activeSlotItemIds);
    exclude.addAll(skippedItemIds);
    // This account's Skips from the last four hours. Unlike the session set above, reset() does not
    // touch these: a world hop is not the player changing their mind.
    exclude.addAll(skipMemory.active());
    // Held back for this poll only, so the bridge moves on to its next candidate instead of
    // repeating one we already know we cannot verify. Cleared on the next inventory change.
    exclude.addAll(unverifiableItemIds);
    if(!exclude.isEmpty()) {
      if(q.length()>0)q.append('&');
      q.append("exclude=");
      boolean first=true;
      for(int id:exclude){if(!first)q.append(',');q.append(id);first=false;}
    }
    // Session-only, not a config setting: item IDs (with each one's own remaining, unfilled
    // quantity) behind any still-in-progress (non-terminal) active GE offer (activeOffers, see its
    // own field doc) -- distinct from exclude= above, which also covers terminal/uncollected slots
    // that have nothing left to cancel and so need no live price at all. Lets the bridge return a
    // plain current market price for each one (slotPrices in the response, for offerDriftHint) and,
    // only when a target trade duration is set, a rough volume-based fill-time estimate for it too
    // (slotFill, for offerFillHint) -- both at zero extra Wiki API cost beyond what's already
    // fetched. Remaining quantities are summed per item ID (TreeMap, same reasoning as the sorted
    // `exclude` set and the `inventory=` map below: two separate offers for the same item collapse
    // into one deterministic entry) rather than sent as separate, possibly-conflicting pairs. Empty
    // whenever nothing's in progress, same treatment as every other session-only signal here.
    List<ActiveOffer> offers=activeOffers;
    if(!offers.isEmpty()) {
      Map<Integer,Integer> slotQuantities=new TreeMap<>();
      for(ActiveOffer o:offers)slotQuantities.merge(o.itemId,o.remaining,Integer::sum);
      if(q.length()>0)q.append('&');
      q.append("slots=");
      boolean first=true;
      for(Map.Entry<Integer,Integer> e:slotQuantities.entrySet()){if(!first)q.append(',');q.append(e.getKey()).append(':').append(e.getValue());first=false;}
    }
    // Session-only, not a config setting: an item you already bought and collected this session
    // that hasn't been resold yet (heldForResale, see updateHeldForResale()) -- sent so the bridge
    // can remind you to close out that position instead of moving straight on to a brand-new buy
    // suggestion while it just sits in your inventory unsold. Only one at a time (the lowest item
    // ID, for a deterministic pick when more than one happens to be held) -- this is meant as
    // "don't forget what's already open," not a full multi-position tracker.
    if(!heldForResale.isEmpty()) {
      Held held=null;
      for(Held h:heldForResale.values())if(held==null||h.itemId<held.itemId)held=h;
      if(q.length()>0)q.append('&');
      q.append("holdItemId=").append(held.itemId).append("&holdQty=").append(held.quantity)
        .append("&holdName=").append(URLEncoder.encode(held.name,StandardCharsets.UTF_8));
      // The specific buy offer this holding came from, when known (see Held's own doc) -- lets a
      // "Personal use" flag on the resulting suggestion mark that exact purchase (see
      // flagPersonalUse), not just "this item ID" broadly.
      if(held.offerId!=null && !held.offerId.isEmpty())
        q.append("&holdBuyId=").append(URLEncoder.encode(held.offerId,StandardCharsets.UTF_8));
      // The real average price this was actually bought at, when known (see Held.price's own
      // doc) -- lets the resulting "you're holding this" reminder say whether selling right now is
      // a profit or a loss, instead of a plain "sell near X gp" either way. Left off entirely when
      // 0/unknown, same treatment as every other optional signal here.
      if(held.price>0)q.append("&holdBuyPrice=").append(held.price);
    }
    // Session-only, not a config setting: the item currently selected in an open GE offer (see
    // openOfferItemId's own field doc), so the bridge can return a plain live-market price for it
    // via the response's separate openItemPrice field, regardless of flip history or ranking.
    if(openOfferItemId>0){if(q.length()>0)q.append('&');q.append("openItemId=").append(openOfferItemId);}
    // Only when EviLiveConfig.suggestIdleInventory() is turned on AND there's something to send --
    // the current inventory snapshot (inventoryQuantities, see its own field doc), so the bridge's
    // last-resort fallback (computeInventorySuggestion in suggestions.mjs) can look for anything
    // worth selling that EVI never observed a buy for at all. TreeMap here purely for a
    // deterministic query string (same reasoning as the sorted `exclude` set above), not because
    // ordering matters to the bridge.
    // Never inside an instance. Reported on 28 Sept 2026 from inside a raid: EVI was
    // offering to sell their supplies and raid gear, because to this tier an inventory is an
    // inventory. In a raid it is a loadout, not idle stock -- and the Grand Exchange cannot be
    // reached from in there anyway, so the suggestion could not be acted on even if it were right.
    // The same holds for every other instance a player carries a kit into.
    //
    // Only this tier is suppressed. The holding tier is about stock EVI actually watched them buy,
    // and a reminder about that is still worth having wherever they are standing.
    if(config.suggestIdleInventory() && !inventoryQuantities.isEmpty() && !inInstance) {
      Map<Integer,Integer> sorted=new TreeMap<>(inventoryQuantities);
      if(q.length()>0)q.append('&');
      q.append("includeInventory=1&inventory=");
      boolean first=true;
      for(Map.Entry<Integer,Integer> e:sorted.entrySet()){if(!first)q.append(',');q.append(e.getKey()).append(':').append(e.getValue());first=false;}
    }
    return q.toString();
  }
  // Keeps only well-formed, non-negative integer item IDs from the free-text config field; silently
  // drops anything else rather than sending malformed data to the bridge.
  private static String sanitizeBlocklist(String raw) {
    if(raw==null || raw.isEmpty())return "";
    StringBuilder out=new StringBuilder();
    for(String part:raw.split(",")) {
      String t=part.trim();
      if(t.isEmpty())continue;
      try {
        int id=Integer.parseInt(t);
        if(id<0)continue;
        if(out.length()>0)out.append(',');
        out.append(id);
      } catch(NumberFormatException ignored){}
    }
    return out.toString();
  }
  // What the sidebar says when the bridge answered but had nothing to suggest. It used to always say
  // "No eligible reviewed flip is currently profitable." -- wrong with market-wide suggestions on
  // (those were checked too), and silent about the margin-safety check, which in practice was the
  // thing skipping every candidate. Pure, so it's tested directly.
  static String noSuggestionMessage(boolean includeMarket, boolean cushion) {
    return noSuggestionMessage(includeMarket,cushion,-1,-1);
  }
  // Stock sitting in the inventory with no sell placed has no slot to sell from. While the free
  // slots are only enough for those exits, EVI holds new buys back rather than filling the Grand
  // Exchange with purchases and leaving that stock stranded -- which is how capital gets stuck.
  // Deliberately NOT counting buys still in progress: a buy vacates its own slot when collected and
  // the sell goes straight into it, and counting them is exactly what once capped the player at four
  // slots out of eight. Pure, so it is tested directly. owed may be null from an older bridge.
  static String sellReserveMessage(Integer owed) {
    String count=owed==null?"the items you're holding with no sell placed yet"
      :owed+" item"+(owed==1?"":"s")+" you're holding with no sell placed yet";
    return "Holding off on a new buy: your remaining Grand Exchange slots are being kept for selling "
      +count+". Place those sells and suggestions resume.";
  }
  // As above, plus what the Grand Exchange itself allows right now (see the freeSlots/collectableSlots
  // fields). With all 8 slots occupied and nothing collectable, the bridge deliberately doesn't rank
  // anything, so saying "nothing passes your settings" would be plainly wrong -- the settings were
  // never consulted. Negative counts mean "not known yet" and change nothing.
  static String noSuggestionMessage(boolean includeMarket, boolean cushion, int freeSlots, int collectableSlots) {
    return noSuggestionMessage(includeMarket,cushion,freeSlots,collectableSlots,null);
  }
  // THREE tiers can be switched off, and this must not claim it checked one that was. The slot
  // case below already encoded exactly that principle -- "the settings were never consulted" --
  // and the SOURCE case was missed, which cost a real diagnosis on 4 Oct 2026: with Suggest from
  // = Market only the panel said "neither your reviewed flips nor a market-wide pick" and then
  // blamed the minimum profit, while the history tier it named had been skipped BY INSTRUCTION
  // and a qualifying pick was sitting in it. The advice it gave -- lower your
  // minimum -- was the WORSE of the two available fixes. Same family as "Bridge unreachable" for
  // a rejected key: confident wording pointing at the one setting that was working.
  //
  // A null source means the default (history first), which consults both, so nothing changes for
  // anyone who never touched the setting.
  static String noSuggestionMessage(boolean includeMarket, boolean cushion, int freeSlots, int collectableSlots, SuggestionSource source) {
    // A COUNT, not the hard-coded eight: free-to-play has three slots, so naming eight there states
    // a number the player can see is wrong. Negative means "not known yet" and changes nothing.
    if(freeSlots==0 && collectableSlots==0)
      return "Every Grand Exchange slot is in use, so there is nowhere to place another offer."
        +" EVI will suggest again as soon as one frees up.";
    // The TOGGLE is checked before the source, and the order is the whole fix (6 Oct 2026). Checked
    // the other way round, Market only with the toggle off said "No market-wide pick passes your
    // settings" about a market check that never ran -- the bridge runs neither the history tier
    // (Market only) nor the market tier (toggle off), so nothing was searched at all, and the
    // sentence pointed at the settings rather than at the switch. Plain words throughout: the old
    // "reviewed flip" meant nothing to a new player, and with market-wide on by default the
    // both-tiers sentence is the one a new player now meets most.
    // Nothing was searched, so the margin check never ran either: no cushion clause here, the same
    // rule as the full-GE case above.
    if(!includeMarket && source==SuggestionSource.MARKET_ONLY)
      return "Market-wide suggestions are switched off and \"Suggest from\" is set to Market only, so EVI"
        +" has nowhere to look for a trade. Turn on \"Include market-wide suggestions\" to get suggestions.";
    String base;
    if(!includeMarket)
      // "passes your settings", not "worth flipping": the bar applied is the player's own (minimum
      // profit and the rest), so the sentence must not imply EVI judged the items unprofitable.
      base="Nothing in your own trade history passes your settings right now, and market-wide suggestions"
        +" are switched off. Turn on \"Include market-wide suggestions\" to search the whole Grand Exchange.";
    else if(source==SuggestionSource.MARKET_ONLY)
      // Your history was not consulted, so do not mention it -- and name the lever that would.
      base="No market-wide pick passes your settings right now. \"Suggest from\" is set to Market only,"
        +" so your own trade history was not considered -- switch it to include that too.";
    else
      base="Nothing passes your settings right now -- neither your own trade history nor a market-wide pick.";
    return cushion
      ?base+" \"Require margin above price noise\" is on, and it currently skips most candidates -- turn it off to see them."
      :base;
  }
  // What to add when a minimum profit is the reason there is nothing to show. A new player with a
  // small cash stack who sets a target out of its reach otherwise sees the same blank panel as
  // someone whose market genuinely has nothing, and has no way to learn that one step lower would
  // have given them trades all along. Pure, so it is tested directly; null or a missing figure adds
  // nothing at all rather than a vague hint.
  static String reachableMessage(Reachable r) {
    if(r==null||r.profit==null||r.profit<=0)return "";
    String item=r.name==null||r.name.isEmpty()?"the best trade EVI can see":r.name;
    // Name the setting to pick, when the bridge worked one out. Before 28 Sept 2026 this figure came
    // from one ranking pass with the floor removed, which answered a different question and could
    // understate the ceiling several-fold; the bridge now probes the same rungs this dropdown offers,
    // so the number it returns is reachable at a setting the player can actually select. An older
    // bridge sends no `atMinimum` and gets the original sentence, unchanged.
    if(r.atMinimum!=null&&r.atMinimum>1)
      return String.format(" Your minimum profit is what is filtering everything out: set it to %,d gp"
        +" and the best this cash stack can do right now is %s, at about %,d gp.",r.atMinimum,item,r.profit);
    return String.format(" Your minimum profit is what is filtering everything out: the best this cash stack"
      +" can do right now is %s, at about %,d gp. Lower the minimum to see trades like it.",item,r.profit);
  }
  private void updatePanelSuggestion(Suggestion s, String diag) {
    if(panel==null)return;
    if(s==null){panel.suggestion(diag);panel.suggestionWarning(false);panel.actions(false,false,false,false,false);return;}
    // The accept button only appears when the bridge issued an id for this suggestion. An older
    // bridge cannot record an acceptance, so showing the button would invite a press that does
    // nothing -- the same reasoning as every other capability this plugin degrades rather than fakes.
    // Which of the five row actions apply to THIS suggestion. A buy cannot be personal use or
    // already gone; a holding cannot be blocked. The plugin already knows which it is, so the row
    // dims the rest rather than offering a press that gets refused.
    //
    // "Gone" needs more than a holding: flagNotHeld also requires a buyId, because it closes one
    // specific purchase. An idle-inventory suggestion -- worn gear EVI never watched being bought --
    // has none, so without this the icon would light up and then refuse the press, which is exactly
    // the behaviour the icon row was built to remove.
    boolean holding="sell".equals(s.action), buying="buy".equals(s.action);
    boolean canForget=holding&&s.buyId!=null&&!s.buyId.isEmpty();
    // A local press outranks the engine's answer until the answer agrees, so an in-flight poll cannot undo it.
    Boolean pending=s.id==null?null:pendingAccept.get(s.id);
    boolean accepted=pending!=null?pending:s.accepted;
    if(pending!=null&&pending==s.accepted)pendingAccept.remove(s.id);
    panel.actions(s.id!=null&&!s.id.isEmpty(),accepted,holding,canForget,buying);
    // A sell that would lose GP right now is still shown -- it's the player's call -- but flagged
    // up front with its break-even price and the card turns orange, so it can't read like a normal flip.
    String warning=s.sellsAtLoss()
      ?String.format("LOSS if sold now: about %,d gp.%s ",s.lossIfSoldNow,s.breakEvenPrice==null?"":String.format(" Break-even: %,d gp.",s.breakEvenPrice))
      :"";
    // The same sentence as before, kept as the fallback the panel shows whenever the bridge sends no
    // verdict -- an older bridge, or a pick nothing was measured about. Where there IS one, the panel
    // draws the card instead and this prose never appears.
    panel.suggestion(s,warning+String.format("%s x%,d — buy %,d gp / sell %,d gp%s",s.name,s.quantity,s.buyPrice,s.sellPrice,s.reasoning==null||s.reasoning.isEmpty()?"":" — "+s.reasoning));
    panel.suggestionWarning(s.sellsAtLoss());
  }
  // How far an active offer's own set price may drift from today's market (buyPrice for a buy
  // offer, sellPrice for a sell offer -- the exact same two roles every other suggestion in this
  // plugin already uses, never a different interpretation of the market data) before it's worth
  // flagging. Generous enough to ignore ordinary short-term volatility rather than nagging about
  // every few-percent wobble.
  private static final double OFFER_DRIFT_THRESHOLD=0.05;
  // Pure and independently testable: never touches the game or the offer itself, only ever returns
  // a sentence for the sidebar (see updatePanelOfferHint) so the player can decide whether to cancel
  // and relist nearer the market, or just let it ride -- matches this plugin's "hints only, EVI
  // never opens a menu or confirms an offer itself" design exactly like everything else here. Returns
  // null when nothing's worth flagging: no live price for that item yet, or the drift is within
  // OFFER_DRIFT_THRESHOLD.
  private static String offerDriftHint(ActiveOffer o, Suggestion price) {
    if(price==null || o.price<=0)return null;
    if(o.buying) {
      long ref=price.buyPrice;
      if(ref<=0)return null;
      double drift=(ref-o.price)/(double)ref; // positive: offer priced below today's market
      if(drift>OFFER_DRIFT_THRESHOLD)
        // Shortened 1 Oct 2026. The card above already carries the item name and both prices in its
        // figures row ("4,000,000 yours . 4,200,000 market"), so the sentence only has to say what
        // the numbers MEAN and what to do.
        return String.format("%.0f%% under the market, so it may sit unfilled. Relisting nearer the market price is the usual fix.",drift*100);
    } else {
      long ref=price.sellPrice;
      if(ref<=0)return null;
      double drift=(o.price-ref)/(double)ref; // positive: offer priced above today's market
      if(drift>OFFER_DRIFT_THRESHOLD)
        return String.format("%.0f%% over the market, so it may sit unfilled. Relist nearer the market, or take the current price.",drift*100);
    }
    return null;
  }
  // Turns minutes into a short, human phrase for offerFillHint's message -- never more precise than
  // "roughly N hours", since the underlying estimate itself is only ever a rough one.
  private static String formatMinutes(int minutes) {
    if(minutes<60)return minutes+" minute"+(minutes==1?"":"s");
    int hours=Math.round(minutes/60f);
    return hours+" hour"+(hours==1?"":"s");
  }
  // Whether the REMAINING quantity of an in-progress offer is trading noticeably slower than the
  // player's own target trade duration -- purely from the bridge's rough, volume-based estimate
  // (estimateOfferFill in suggestions.mjs, itself just the OSRS Wiki API's last-hour trading volume
  // for that item). This is never a fill guarantee, and the wording below must stay that way: a
  // "recent volume" observation, not a prediction of what THIS specific offer will do -- there is no
  // real order-book visibility in this data at all (no queue position, no depth at other prices).
  // Pure and independently testable, exactly like offerDriftHint. Returns null when there's nothing
  // to flag: no estimate for this item (fill==null -- no target duration set, or no recent volume
  // data at all for it), or the estimate says it's on pace.
  // Shortened 1 Oct 2026, on a report that the sidebar messages were too long. The old sentence
  // ran to about 310 characters and three of its clauses were already on screen -- it named the item
  // the card shows directly above it, restated the label ("running longer than your target trade
  // duration" IS "May not fill in time"), and hedged twice. It also left the card's figures row EMPTY
  // while the drift card beside it used that row for its numbers.
  //
  // The HEDGE stays. This is a fill estimate, and the standing rule is that anything predicting a
  // fill says plainly that it is not promising one.
  private static String offerFillHint(ActiveOffer o, OfferFillEstimate fill) {
    if(fill==null || fill.likelyToFillInTime)return null;
    return "A rough volume estimate, not a guarantee. Reprice, resize, or cancel if you need it sooner.";
  }
  /** The numbers for the card's own figures row: what the sentence above used to spell out. */
  private static String offerFillFigures(ActiveOffer o, OfferFillEstimate fill) {
    if(fill==null || fill.likelyToFillInTime)return null;
    String pace=fill.estimatedFillMinutes<0
      ?"almost no recent trading"
      :"~"+formatMinutes(fill.estimatedFillMinutes)+" at recent volume";
    return String.format("%,d remaining · %s",o.remaining,pace);
  }
  // Matches each still-in-progress offer (activeOffers, itself already excluding terminal ones --
  // see refreshActiveSlotItemIds) against the live price and fill estimate the bridge just returned
  // for it (slotPrices/slotFill, requested via suggestionQuery()'s slots= param) and joins every
  // resulting hint into one sidebar message -- both a price-drift hint and a fill-time hint can
  // apply to the same offer at once. A linear scan, not a map -- there are at most 8 GE slots, so
  // this is always trivially small. Never throws on a missing/short slotPrices/slotFill array;
  // simply skips any offer with no match.
  private void updatePanelOfferHint(List<ActiveOffer> offers, Suggestion[] slotPrices, OfferFillEstimate[] slotFill, RelistAdvice[] relistAdvice,
                                    Map<String,Long> sellBreakEven) {
    if(panel==null)return;
    if(offers.isEmpty() && (relistAdvice==null || relistAdvice.length==0)){panel.advice(java.util.Collections.emptyList());return;}
    java.util.List<AdviceCard> cards=offerCards(offers,slotPrices,slotFill,relistAdvice,sellBreakEven,true);
    // Announce a NEW warning through RuneLite's own notifier, for the hold times where nobody is
    // looking at the sidebar. Recorded only after notifying, so a throw cannot silence it for ever.
    for(AdviceCard c:cardsToNotify(cards,config.notifyAdvice(),announcedAdvice)) {
      try { notifier.notify("EVI: "+c.label+" -- "+c.name); } catch(Exception ignored) { }
      announcedAdvice.add(adviceKey(c));
    }
    // A warning that has gone must be able to fire again if it returns.
    announcedAdvice.retainAll(currentAdviceKeys(cards));
    panel.advice(cards);
  }
  /**
   * The cards under "Active offers": the engine's advice first, then the plugin's own price-drift and fill hints per running
   * offer. Pure, so the one-voice rule is tested directly.
   *
   * <p>ONE VOICE PER OFFER, with the engine in this process ({@code oneVoice}, always true in the plugin since 4.0.0). The 5%
   * hand-off between the old companion app's relist advice and offerDriftHint existed only because they were two processes;
   * here both speakers are known,
   * so a SELL offer gets exactly one price sentence:
   * <ul>
   *   <li>the relist card when the engine wrote one for that item (above break-even it speaks under 5% or past its clock, so the
   *       hand-off at 5% stays exact; below break-even it speaks whatever the gap and names the loss);</li>
   *   <li>otherwise, below break-even ({@code sellBreakEven}, what this account paid, after tax) the plugin stays quiet too: its
   *       "or take the current price" would lock in the loss the relist sentence exists to name -- that sentence speaks once
   *       the offer has stood 15 minutes;</li>
   *   <li>otherwise the plugin's own drift hint, past 5%, exactly as before.</li>
   * </ul>
   * A BUY offer has no relist voice (relist advice is about sells), so its drift hint is unchanged. The fill hint is a
   * different subject (pace, not price) and already has its own hand-off (the engine's buy-progress notes skip an item the
   * fill hint speaks about). With {@code oneVoice} false (the companion app's rule, kept for the tests that pin the difference)
   * the drift hint speaks whatever the relist advice says.
   */
  static java.util.List<AdviceCard> offerCards(List<ActiveOffer> offers, Suggestion[] slotPrices, OfferFillEstimate[] slotFill, RelistAdvice[] relistAdvice,
                                               Map<String,Long> sellBreakEven, boolean oneVoice) {
    java.util.List<AdviceCard> cards=new java.util.ArrayList<>();
    // Shown first: an offer that has been sitting is the thing most worth acting on.
    if(relistAdvice!=null)for(RelistAdvice advice:relistAdvice) {
      if(advice==null || advice.message==null || advice.message.isEmpty())continue;
      // A holding line keeps its lot, so the panel can offer Personal use / Gone on it; no other note carries one.
      cards.add(new AdviceCard(advice.level,advice.label,advice.name,advice.figures,advice.message,advice.itemId,
        Boolean.TRUE.equals(advice.holding)?advice.buyId:null,advice.detail));
    }
    for(ActiveOffer o:offers) {
      Suggestion price=null;
      if(slotPrices!=null)for(Suggestion p:slotPrices)if(p!=null && p.itemId==o.itemId){price=p;break;}
      OfferFillEstimate fill=null;
      if(slotFill!=null)for(OfferFillEstimate f:slotFill)if(f!=null && f.itemId==o.itemId){fill=f;break;}
      String priceHint=oneVoice&&!o.buying&&relistSpeaks(o.itemId,price,relistAdvice,sellBreakEven)?null:offerDriftHint(o,price);
      String fillHint=offerFillHint(o,fill);
      // The sentences are unchanged and still carry every figure; the card is a short way in, and the
      // sentence is one hover away. A drift the player can act on for free is the cheaper warning, so
      // a buy priced under the market reads as caution rather than as something going wrong.
      if(priceHint!=null)cards.add(new AdviceCard("caution",
        o.buying?"Priced under market":"Priced over market",o.name,
        price==null?null:String.format("%,d yours \u00b7 %,d market",o.price,o.buying?price.buyPrice:price.sellPrice),
        priceHint));
      if(fillHint!=null)cards.add(new AdviceCard("caution","May not fill in time",o.name,
        offerFillFigures(o,fill),fillHint));
    }
    return cards;
  }
  /** The one-voice rule for a SELL offer (see offerCards): true when the relist advice is this offer's price voice --
   *  it wrote a card for the item, or the market is under this account's break-even (where the plugin's own hint would say
   *  "take the current price"). A relist card is recognised by its own field, offerPrice, which only relist notes carry. */
  static boolean relistSpeaks(int itemId, Suggestion price, RelistAdvice[] relistAdvice, Map<String,Long> sellBreakEven) {
    if(relistAdvice!=null)for(RelistAdvice a:relistAdvice)if(a!=null && a.itemId==itemId && a.offerPrice!=null)return true;
    Long breakEven=sellBreakEven==null?null:sellBreakEven.get(String.valueOf(itemId));
    return breakEven!=null && price!=null && price.sellPrice>0 && price.sellPrice<breakEven;
  }
  // price and spent are LONG, not int. RuneLite 1.13.0 widened GrandExchangeOffer.getPrice() and
  // getSpent() to long, and the Plugin Hub compiles every plugin against the version it pins -- so
  // this is not optional, and 3.9.0 failed its build check on exactly these two fields. Widening
  // rather than casting down is the point: an int truncates silently past 2,147,483,647, and a
  // wrong GP figure that looks plausible is the worst failure this project has. The offer total for
  // a large order can reach that range, which is presumably why the API moved.
  static class Offer {int slot,itemId,total,filled;long price,spent;String offerId,state,name="";boolean knownStart;
      // See capture(): ticks between first seeing this offer unfilled and its first fill, or -1 when
      // not known. startTick is local bookkeeping and is not sent to the bridge.
      transient int startTick=-1;int ticksToFill=-1;}
  static class Packet {int version=1;String session,account;long seq,ts;boolean loggedIn;List<Offer> offers=new ArrayList<>();}
  // Immutable per-offer snapshot for the sidebar's cancel/relist hint (offerDriftHint) -- separate
  // from Offer itself because an Offer instance is wholesale-replaced by capture() on every change
  // (see slots[]'s own doc), which is fine for slots[] (client-thread-only) but this one specifically
  // needs a cross-thread-safe reference, exactly like activeSlotItemIds just above it.
  static final class ActiveOffer {
    final int itemId,remaining;final long price;final boolean buying;final String name;
    ActiveOffer(int itemId,long price,boolean buying,String name,int remaining){this.itemId=itemId;this.price=price;this.buying=buying;this.name=name;this.remaining=remaining;}
  }
  // Immutable per-slot snapshot for the sidebar's "Active offers" list (see geOfferRows). state is
  // the raw GrandExchangeOfferState name (BUYING, SOLD, CANCELLED_BUY, ...); EviLivePanel turns it
  // into display text.
  static final class OfferRow {
    final int slot,itemId,filled,total;final long price;final boolean buying;final String name,state;
    OfferRow(int slot,int itemId,long price,int filled,int total,boolean buying,String name,String state){this.slot=slot;this.itemId=itemId;this.price=price;this.filled=filled;this.total=total;this.buying=buying;this.name=name;this.state=state;}
  }
  // slotPrices reuses Suggestion the same way openItemPrice already does just above it -- the
  // bridge's GET /api/suggestion sends each entry as {itemId,buyPrice,sellPrice} (see
  // lookupItemPrice in suggestions.mjs), and Gson populates only those three fields of a Suggestion,
  // leaving the rest (action, reasoning, etc.) at their defaults. See offerDriftHint for how these
  // get matched back up against activeOffers by itemId.
  static class SuggestionResponse {Suggestion suggestion,openItemPrice;Suggestion[] additional;Suggestion[] slotPrices;OfferFillEstimate[] slotFill;RelistAdvice[] relistAdvice;SlotState slots;Profit profit;Reachable reachable;
    // Sent by the plugin's own engine: the break-even sell price of each of this account's live sells whose cost basis is
    // known, keyed by item id, for the one-voice rule (see offerCards). Null when the answer carries none.
    Map<String,Long> sellBreakEven;}
  // Sent only when there is nothing to suggest AND a minimum profit is set: the best trade the
  // market actually offers at this cash stack, so "nothing" can say why instead of looking broken.
  // It is a statement of what exists, not a recommendation -- it has been through none of EVI's
  // checks, and the wording below never tells anyone to buy it.
  static final class Reachable {String name;Integer itemId;Long profit,atMinimum;}
  // Realised profit since the player last pressed Reset (or since EVI's first matched trade), computed by
  // the bridge from matched flips only -- see profitSince in bridge/server.mjs. unmatchedSales and
  // openPositions are what that total deliberately leaves out, shown beside it rather than folded in: a
  // figure that quietly omitted them would read as a loss the moment the records have a gap.
  static final class Profit {Long since;long gp;int trades,winners,losers,unmatchedSales,openPositions;}
  // What the bridge made of the Grand Exchange's capacity this poll (see slotCapacity and the
  // sell-side reserve in bridge/server.mjs). Boxed Integers so "not known" stays distinct from zero,
  // exactly like the plugin's own freeSlots/collectableSlots fields; Gson leaves them null when the
  // bridge is an older build that doesn't send this at all, which reads as "nothing to say".
  static class SlotState {Integer free,collectable,sellSlotsOwed;boolean full,tight,buysHeldForExits;int[] positionItems;}
  // One entry per sell offer that has been sitting long enough to be worth mentioning, built by the
  // bridge from its own journal (see bridge/relist.mjs). `message` is already a complete, hedged
  // sentence naming the market price and, when the cost basis is known, the break-even price; the
  // plugin only displays it. Nothing here relists, cancels or edits anything -- the player does.
  static final class RelistAdvice {int itemId;String name,message,level,label,figures;boolean belowBreakEven;
    // A short message's tooltip (8 Oct 2026: crash notes). Absent from an older engine: the message is the tooltip, as before.
    String detail;
    // holdingsAdvice's lines only (8 Oct 2026): holding true, and the lot's buy offer id -- what the line menu marks.
    Boolean holding;String buyId;
    // Only relist notes carry it (the other advice modules' notes are trimmed to the card fields): how offerCards tells a
    // relist card from the rest.
    Double offerPrice;}
  /** One line of advice about an offer already placed, as something the sidebar can draw rather than a
   *  paragraph to read. `message` is the full sentence and stays as the tooltip, so making the card
   *  small costs no explanation. `label` absent means an older bridge: the panel shows the sentence. */
  /** A stable identity for one warning about one item, so it is announced ONCE and not every poll. */
  static String adviceKey(AdviceCard c) {
    return c==null?"":(c.level+"|"+c.name+"|"+c.label);
  }
  /** The keys on screen now, so a warning that has cleared can fire again if it comes back. */
  static java.util.Set<String> currentAdviceKeys(java.util.List<AdviceCard> cards) {
    java.util.Set<String> keys=new java.util.HashSet<>();
    if(cards!=null)for(AdviceCard c:cards)if(c!=null)keys.add(adviceKey(c));
    return keys;
  }
  /**
   * Which cards deserve a notification right now.
   *
   * WARN ONLY. The levels are warn / caution / info, and only warn means "this offer is costing you
   * money" -- "may not fill in time" is a caution and does not deserve a sound. Starting narrow is
   * deliberate: a notifier that cries wolf gets the whole plugin switched off.
   *
   * ANNOUNCED ONCE per condition per item. The plugin polls every two seconds, so without the
   * dedupe a single standing offer would notify about 1,800 times an hour. The key deliberately
   * excludes the message text, so a figure ticking inside the same warning does not re-fire it.
   *
   * Pure, and does NOT mutate the set -- the caller records what it announced.
   */
  static java.util.List<AdviceCard> cardsToNotify(java.util.List<AdviceCard> cards, boolean enabled, java.util.Set<String> announced) {
    java.util.List<AdviceCard> out=new java.util.ArrayList<>();
    if(!enabled || cards==null)return out;
    for(AdviceCard c:cards) {
      if(c==null || !"warn".equals(c.level))continue;
      if(c.message==null || c.message.isEmpty())continue;
      String k=adviceKey(c);
      if(announced!=null && announced.contains(k))continue;
      boolean dup=false;
      for(AdviceCard seen:out)if(adviceKey(seen).equals(k))dup=true;
      if(!dup)out.add(c);
    }
    return out;
  }
  static final class AdviceCard {
    final String level, label, name, figures, message;
    // A HOLDING line's lot (8 Oct 2026): the item and the buy offer id the engine named (holdingsAdvice's buyId). buyId is
    // null on every other card -- an offer note, a crash note, the plugin's own hints -- and on a holding line from an engine
    // that names no lot (a bridge older than this): the panel offers its line menu (Personal use, Gone) only where it is set.
    final int itemId;
    final String buyId;
    // A note sent as a short line plus a tooltip detail (8 Oct 2026: crash notes): the panel draws `message` on the card and
    // keeps `detail` for the tooltip. Null on every other card, whose tooltip is its message as before.
    final String detail;
    AdviceCard(String level, String label, String name, String figures, String message) {
      this(level,label,name,figures,message,0,null);
    }
    AdviceCard(String level, String label, String name, String figures, String message, int itemId, String buyId) {
      this(level,label,name,figures,message,itemId,buyId,null);
    }
    AdviceCard(String level, String label, String name, String figures, String message, int itemId, String buyId, String detail) {
      this.level=level; this.label=label; this.name=name; this.figures=figures; this.message=message;
      this.itemId=itemId; this.buyId=buyId==null||buyId.isEmpty()?null:buyId;
      this.detail=detail==null||detail.isEmpty()?null:detail;
    }
  }
  // One entry per in-progress offer the bridge could judge against the player's own "Target trade
  // duration" setting -- see estimateOfferFill in suggestions.mjs for exactly what this is (a rough
  // volume-based estimate from the OSRS Wiki API's own last-hour trading data, never a fill
  // guarantee) and offerFillHint for how it's turned into sidebar text. estimatedFillMinutes is -1
  // when there's essentially no recent trading volume at all for this item (see its own doc on the
  // bridge side for why -1, not a fabricated number or JSON null). Only ever populated when the
  // player has a target duration set; otherwise the bridge sends an empty array and this costs
  // nothing, exactly like slotPrices above it.
  static final class OfferFillEstimate {int itemId;boolean likelyToFillInTime;int estimatedFillMinutes;}
}
