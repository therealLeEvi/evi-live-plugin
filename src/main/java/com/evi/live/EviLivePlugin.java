package com.evi.live;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
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
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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

/** Observes GE state; shows your own EVI suggestion as a text hint in the open quantity/price prompt and, on an optional hotkey, fills that one field with it. No menus, clicks, item selection, offer confirmation or other automated actions. */
@PluginDescriptor(name="EVI Live (Local)",internalName="evi-live",description="Passively sends GE snapshots to your local EVI bridge; shows your own suggested quantity/price in the offer prompt and fills it on an optional hotkey",tags={"grand exchange","evi","hotkey","suggestion"})
public class EviLivePlugin extends Plugin {
  private static final Logger log=LoggerFactory.getLogger(EviLivePlugin.class);
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
  private Filepath pairingPath;
  private volatile boolean running;
  private volatile long lifecycle;
  private long pairingRevision;
  @Inject private Gson gson;
  private final ArrayDeque<String> queue=new ArrayDeque<>();
  // RuneLite's own shared HTTP client, injected rather than built here, so every request this
  // plugin makes goes through the client the user's installation already governs. Assigned to
  // transport in startUp() (not at field-initialisation time) because injection hasn't happened
  // yet when fields are initialised; tests replace transport directly and never see this.
  @Inject private okhttp3.OkHttpClient okHttpClient;
  private LocalTransport transport;
  private ScheduledExecutorService sender;
  private volatile String pluginKey;
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
  private final Set<Integer> skippedItemIds=ConcurrentHashMap.newKeySet();
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
  private volatile int freeSlots=-1;
  private volatile int collectableSlots=-1;
  // The player's actual current cash stack, read from their own inventory's coins (item 995) on
  // the client thread every tick and cached here for the background poll thread to read (hence
  // volatile) -- never fabricated or assumed. -1 means "not yet known" (e.g. before the inventory
  // has loaded this session), which suggestionQuery() treats as "send no cash figure at all" so a
  // missing reading never falsely constrains suggestions. Sent to the bridge so it can cap/re-rank
  // suggestions to trades actually affordable right now, instead of e.g. a huge-margin item at a
  // quantity that would cost more than the player has, or more than OSRS's own ~2.147bn gp cap.
  private volatile int cashStack=-1;
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
  // exact complaint: buying steel cannonballs from a suggestion, then having the sidebar jump
  // straight to a totally different market-wide pick while the cannonballs just sat there unsold.
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
    final int itemId,quantity,price;final String name,offerId;
    Held(int itemId,int quantity,String name,String offerId,int price){this.itemId=itemId;this.quantity=quantity;this.name=name;this.offerId=offerId;this.price=price;}
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
    // per the same review, since only this developer's own machine ever had that folder.
    // Built here rather than at field initialisation because okHttpClient is only injected by the
    // time startUp() runs. A test that installed its own transport keeps it.
    if(transport==null)transport=new LocalTransport.Http(okHttpClient);
    Filepath dir=getPluginDirectory();
    dir.createDirectories();
    pairingPath=dir.joinSegment("plugin-key.txt");
    pluginKey=null;
    String pairingStatus="Not paired. Paste your RuneLite plugin key below.";
    try {
      if(pairingPath.exists())pluginKey=PairingKey.normalize(readTrimmed(pairingPath));
      if(pluginKey!=null)pairingStatus="Paired locally. Log in to begin observing offers.";
    } catch(Exception ex){pairingStatus="The saved pairing key is empty, invalid, or unreadable. Paste a new key below.";}
    Filepath saltFile=dir.joinSegment("identity-salt.txt");
    if(!saltFile.exists())saltFile.write(UUID.randomUUID().toString());
    salt=readTrimmed(saltFile);
    if(salt.isEmpty())throw new IllegalStateException("EVI identity salt is empty; restore it from your local backup.");
    Runnable createPanel=()->{
      panel=new EviLivePanel(this::pair,this::skipSuggestion,this::flagPersonalUse,this::flagNotHeld);
      navigation=NavigationButton.builder().tooltip("EVI Live").icon(EviLivePanel.icon()).panel(panel).priority(8).build();
      toolbar.addNavigation(navigation);
    };
    if(SwingUtilities.isEventDispatchThread())createPanel.run();else SwingUtilities.invokeAndWait(createPanel);
    status(pairingStatus);
    reset();
    suggestionKeybindHandler.register();
    overlayManager.add(searchHighlightOverlay);
    final long generation=++lifecycle;
    running=true;
    sender=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"evi-local-sender");t.setDaemon(true);return t;});
    sender.scheduleWithFixedDelay(()->flush(generation),0,1,TimeUnit.SECONDS);
    sender.scheduleWithFixedDelay(()->pollSuggestion(generation),0,2,TimeUnit.SECONDS);
  }
  @Override protected void shutDown() {
    running=false;
    ++lifecycle;
    if(sender!=null)sender.shutdownNow();
    suggestionKeybindHandler.unregister();
    overlayManager.remove(searchHighlightOverlay);
    suggestionCache.set(null);
    openItemPriceCache.set(null);
    updatePanelSuggestion(null, null);
    updatePanelOfferHint(Collections.emptyList(), null, null, null);
    try { suggestionHintWidget.clear(); } catch (Exception ignored) { }
    try { itemSelectWidget.clear(); } catch (Exception ignored) { }
    if(navigation!=null)toolbar.removeNavigation(navigation);
    synchronized(queue){queue.clear();}
    reset();
  }
  private void status(String message){if(panel!=null)panel.status(message);}
  private void pair(String entered) {
    try {
      final long generation=lifecycle;
      String key=PairingKey.normalize(entered);
      pairingPath.write(key);
      clientThread.invokeLater(()->{
        if(!running || generation!=lifecycle)return;
        synchronized(queue){pluginKey=key;++pairingRevision;queue.clear();}
        reset();
        status("Pairing saved. Log in; connection is checked when the first snapshot is sent.");
      });
    }catch(IllegalArgumentException ex){status(ex.getMessage());}
    catch(Exception ex){status("Could not save the key. Check write access to .runelite/plugin-data/evi-live.");}
  }
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
  private void reset(){ready=false;warmTicks=0;ticks=0;profile=null;account=null;economy=null;session=UUID.randomUUID().toString();seq=0;Arrays.fill(slots,null);activeSlotItemIds=Collections.emptySet();activeOffers=Collections.emptyList();geOfferRows=Collections.emptyList();if(panel!=null)panel.offers(geOfferRows);skippedItemIds.clear();freeSlots=-1;collectableSlots=-1;cashStack=-1;openOfferItemId=-1;membersWorld=null;heldForResale.clear();inventoryItemIds=Collections.emptySet();inventorySnapshotEstablished=false;inventoryQuantities=Collections.emptyMap();}
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
  // Refreshes cashStack from the inventory's coins every tick. Deliberately its own try/catch so
  // a container lookup hiccup (e.g. mid-transition between logged-out and logged-in) never breaks
  // the rest of onGameTick -- it just leaves cashStack at its last known value, or -1 if there
  // never was one. Item ID 995 is Coins; looked up by literal ID rather than a RuneLite ItemID
  // constant since those move between packages across client versions.
  private void refreshCashStack() {
    try {
      net.runelite.api.ItemContainer inv=client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
      cashStack=inv==null?-1:inv.count(995);
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
      net.runelite.api.ItemContainer inv=client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
      if(inv==null)return; // leave the previous snapshot (and inventorySnapshotEstablished) in place
      Set<Integer> ids=new java.util.HashSet<>();
      Map<Integer,Integer> quantities=new java.util.HashMap<>();
      for(net.runelite.api.Item item:inv.getItems()) {
        if(item==null || item.getId()<=0 || item.getQuantity()<=0)continue;
        int resolvedId=resolveNotedItemId(item.getId());
        ids.add(resolvedId);
        quantities.merge(resolvedId,item.getQuantity(),Integer::sum);
      }
      inventoryItemIds=ids;
      inventoryQuantities=quantities;
      inventorySnapshotEstablished=true;
    } catch(Exception ignored){} // leave the previous snapshot in place
  }
  // A noted item's own ItemContainer/Item.getId() is a DIFFERENT item ID from the unnoted item it
  // represents -- confirmed against a real report (11 noted "Contract of glyphic attenuation" in
  // inventory, "Suggest selling idle inventory" turned on, still no suggestion even though the
  // item itself has healthy GE volume and a live price around 130k+ gp each). Both
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
  // known, accepted limitation for now, not a bug (see README).
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

  @Subscribe public void onGameStateChanged(GameStateChanged e) {
    if(e.getGameState()==GameState.LOGIN_SCREEN || e.getGameState()==GameState.HOPPING || e.getGameState()==GameState.CONNECTION_LOST) {
      if(ready)enqueue(false);
      reset();
    }
  }
  @Subscribe public void onGameTick(GameTick e) {
    if(!running || pluginKey==null || client.getGameState()!=GameState.LOGGED_IN)return;
    try { suggestionHintWidget.update(); } catch (Exception ignored) { } // never let a widget hiccup break observation
    try { itemSelectWidget.update(); } catch (Exception ignored) { }
    refreshCashStack();
    refreshInventoryItemIds();
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
        String identity=salt+":"+profile+(economy.isEmpty()?"":":economy:"+economy);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder h=new StringBuilder();for(byte b:hash)h.append(String.format("%02x",b&255));account=h.toString();
        GrandExchangeOffer[] offers=client.getGrandExchangeOffers();
        if(offers==null || offers.length!=8)return;
        for(int i=0;i<8;i++)slots[i]=capture(i,offers[i],null,false);
        refreshActiveSlotItemIds();
        ready=true;enqueue(true);
      } catch(Exception ex){reset();}
    } else if(++ticks%15==0)enqueue(true);
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
    synchronized(queue) {
      if(queue.size()>=512) {
        queue.clear();reset(); // Conservative rebaseline after data loss; do not fabricate complete trades.
        log.warn("EVI Live: local delivery queue overflow; observation restarted. Review missing trades manually.");
        status("Delivery queue filled while disconnected. Observation restarted; review missing trades manually.");
        return;
      }
      queue.add(json);
    }
  }
  private void flush(long generation) {
    for(int count=0;count<32&&running&&generation==lifecycle&&!Thread.currentThread().isInterrupted();count++) {
      String json,key;long revision;
      synchronized(queue){json=queue.peek();key=pluginKey;revision=pairingRevision;}if(json==null)return;
      try {
        int status=transport.send(key,json);
        if(!running || generation!=lifecycle)return;
        synchronized(queue){if(revision!=pairingRevision)return;}
        if(status!=200){status(status==401?"Bridge rejected the key. Paste this bridge's RuneLite plugin key.":"Bridge returned HTTP "+status+". Pending observations retained for retry.");return;}
        synchronized(queue){if(json.equals(queue.peek()))queue.remove();}
        status("Connected to the local bridge. Last delivery: "+java.time.LocalTime.now().withNano(0));
      } catch(Exception ignored){if(running&&generation==lifecycle)status("Bridge unavailable. Start EVI; queued observations will retry automatically.");return;}
    }
  }
  // Polls regardless of whether a GE slot is open, so the sidebar's "Current suggestion" summary
  // stays populated like Copilot's does, not just while an offer screen happens to be on screen.
  // (It used to gate on slotOpenHint; that left the panel stuck on "No suggestion yet." for anyone
  // checking it outside an open offer screen, even with eligible reviewed-flip history.) The bridge
  // call is local-loopback and the underlying Wiki price fetch is itself cached for 60s, so this
  // stays cheap. Only ever updates the in-memory cache the hotkey handler reads — never writes
  // anything back to the game itself; filling still separately requires the real GE prompt to be
  // open for the matching item (see GEOffer.openFieldFor), so this change does not loosen that gate.
  private void pollSuggestion(long generation) {
    if(!running || generation!=lifecycle)return;
    String key=pluginKey;
    if(key==null)return;
    try {
      String json=transport.get(key,suggestionQuery());
      if(!running || generation!=lifecycle)return;
      // Distinguishes "bridge reachable but returned nothing" from a request that never even got a
      // 200, so the sidebar can say which one is happening instead of collapsing both into a bare
      // "No suggestion yet."
      if(json==null){updatePanelSuggestion(null,"Bridge unreachable -- check it's running and the pairing key matches.");return;}
      SuggestionResponse r=gson.fromJson(json,SuggestionResponse.class);
      Suggestion s=r==null?null:r.suggestion;
      // A reconstructed-from-history suggestion (see Suggestion.persisted) that isn't actually
      // in the inventory right now is stale, not real -- treat it exactly like a manual "Skip
      // this suggestion" click (excludes it for the rest of this session) and re-poll immediately
      // instead of caching or showing it, so the bridge's next call naturally moves on to its
      // next-earliest candidate rather than repeating the same wrong one every two seconds. Never
      // touches suggestionCache/openItemPriceCache here, so whatever they already held (a prior
      // valid suggestion, or nothing) is left exactly as it was until the retry resolves.
      if(s!=null && s.persisted && !verifyPersistedHolding(s)) {
        skippedItemIds.add(s.itemId);
        if(running && sender!=null)sender.execute(()->pollSuggestion(lifecycle));
        return;
      }
      correctSellQuantityAgainstInventory(s);
      suggestionCache.set(s);
      openItemPriceCache.set(r==null?null:r.openItemPrice);
      updatePanelSuggestion(s,s==null?noSuggestionMessage(config.includeMarketSuggestions(),config.marginSafetyCushion(),freeSlots,collectableSlots):null);
      updatePanelOfferHint(activeOffers,r==null?null:r.slotPrices,r==null?null:r.slotFill,r==null?null:r.relistAdvice);
    } catch(Throwable ex){
      // Deliberately catches Throwable, not just Exception, and kept that way permanently: a
      // periodic ScheduledExecutorService task that lets ANY throwable escape -- including an
      // Error, which a plain "catch(Exception)" does NOT catch -- gets silently cancelled forever
      // by the executor, with nothing printed anywhere. (This is exactly how a package-private
      // RiskLevel enum being inaccessible to RuneLite's config proxy took suggestions down
      // completely -- see RiskLevel.java and the 2026-09-15 README entry.) Logging the full trace
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
  private void skipSuggestion() {
    Suggestion s=suggestionCache.get();
    if(s==null)return;
    skippedItemIds.add(s.itemId);
    suggestionCache.set(null);
    updatePanelSuggestion(null,"Skipped. Checking for the next suggestion...");
    if(running && sender!=null)sender.execute(()->pollSuggestion(lifecycle));
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
    if(!"sell".equals(s.action) || s.buyId==null || s.buyId.isEmpty()) {
      if(panel!=null)panel.suggestion("Personal use only applies to a \"you're holding this\" suggestion EVI can trace back to one specific buy.");
      return;
    }
    String key=pluginKey,buyId=s.buyId;
    skippedItemIds.add(s.itemId);
    heldForResale.remove(s.itemId);
    suggestionCache.set(null);
    updatePanelSuggestion(null,"Marked as personal use. Checking for the next suggestion...");
    if(running && sender!=null)sender.execute(()->{
      if(key!=null){try{transport.markPersonalUse(key,gson.toJson(new PersonalUseRequest(buyId)));}catch(Exception ignored){}}
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
    String key=pluginKey,buyId=s.buyId;
    skippedItemIds.add(s.itemId);
    heldForResale.remove(s.itemId);
    suggestionCache.set(null);
    updatePanelSuggestion(null,"Marked as no longer held. Checking for the next suggestion...");
    if(running && sender!=null)sender.execute(()->{
      if(key!=null){try{transport.markNotHeld(key,gson.toJson(new NotHeldRequest(buyId)));}catch(Exception ignored){}}
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
    RiskLevel risk=config.riskLevel();
    if(risk!=null && risk!=RiskLevel.MEDIUM){if(q.length()>0)q.append('&');q.append("risk=").append(risk.param());}
    if(config.includeMarketSuggestions()){if(q.length()>0)q.append('&');q.append("includeMarket=1");}
    // How much of the cash stack one market-wide suggestion may commit (see MaxTradeShare for the
    // backtest that produced the default). OFF is left off the query entirely, like every other
    // setting here, so it behaves exactly as before this existed.
    // Which market-wide candidates to consider at all (see TradingProfile); STANDARD sends nothing.
    TradingProfile profile=config.tradingProfile();
    if(profile!=null && profile.param()!=null){if(q.length()>0)q.append('&');q.append("profile=").append(profile.param());}
    MaxTradeShare share=config.maxTradeShare();
    if(share!=null && share.percent()>0){if(q.length()>0)q.append('&');q.append("stackShare=").append(share.percent());}
    TradeDuration duration=config.tradeDuration();
    if(duration!=null && duration.minutes()>0){if(q.length()>0)q.append('&');q.append("duration=").append(duration.minutes());}
    // Price-direction forecast for a "buy" suggestion (see ForecastHorizon/ForecastPolicy). Both
    // left off entirely when forecastHorizon is OFF (the default), so an untouched config costs no
    // extra bridge-side Wiki API call and changes nothing -- same treatment as every setting above.
    ForecastHorizon horizon=config.forecastHorizon();
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
    // Session-only, not a config setting: items to leave out of ranking right now because you
    // already have an active/uncollected GE slot for them (activeSlotItemIds, auto-detected) or
    // you manually skipped them via the sidebar (skippedItemIds). Merged and sorted here so the
    // query string -- and any test asserting against it -- is deterministic regardless of
    // iteration order.
    Set<Integer> exclude=new TreeSet<>(activeSlotItemIds);
    exclude.addAll(skippedItemIds);
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
    if(config.suggestIdleInventory() && !inventoryQuantities.isEmpty()) {
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
  // As above, plus what the Grand Exchange itself allows right now (see the freeSlots/collectableSlots
  // fields). With all 8 slots occupied and nothing collectable, the bridge deliberately doesn't rank
  // anything, so saying "nothing passes your settings" would be plainly wrong -- the settings were
  // never consulted. Negative counts mean "not known yet" and change nothing.
  static String noSuggestionMessage(boolean includeMarket, boolean cushion, int freeSlots, int collectableSlots) {
    if(freeSlots==0 && collectableSlots==0)
      return "All 8 Grand Exchange slots are in use, so there is nowhere to place another offer. EVI will suggest again as soon as one frees up.";
    String base=includeMarket
      ?"Nothing passes your settings right now -- neither your reviewed flips nor a market-wide pick."
      :"No eligible reviewed flip is currently profitable. (Market-wide suggestions are off.)";
    return cushion
      ?base+" \"Require margin above price noise\" is on, and it currently skips most candidates -- turn it off to see them."
      :base;
  }
  private void updatePanelSuggestion(Suggestion s, String diag) {
    if(panel==null)return;
    if(s==null){panel.suggestion(diag);panel.suggestionWarning(false);return;}
    // A sell that would lose GP right now is still shown -- it's the player's call -- but flagged
    // up front with its break-even price and the card turns orange, so it can't read like a normal flip.
    String warning=s.sellsAtLoss()
      ?String.format("LOSS if sold now: about %,d gp.%s ",s.lossIfSoldNow,s.breakEvenPrice==null?"":String.format(" Break-even: %,d gp.",s.breakEvenPrice))
      :"";
    panel.suggestion(warning+String.format("%s x%,d — buy %,d gp / sell %,d gp%s",s.name,s.quantity,s.buyPrice,s.sellPrice,s.reasoning==null||s.reasoning.isEmpty()?"":" — "+s.reasoning));
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
      int ref=price.buyPrice;
      if(ref<=0)return null;
      double drift=(ref-o.price)/(double)ref; // positive: offer priced below today's market
      if(drift>OFFER_DRIFT_THRESHOLD)
        return String.format("Your buy offer for %s is priced at %,d gp, but today's market is around %,d gp (%.0f%% below) -- it may sit unfilled. Consider cancelling and relisting closer to the market price.",o.name,o.price,ref,drift*100);
    } else {
      int ref=price.sellPrice;
      if(ref<=0)return null;
      double drift=(o.price-ref)/(double)ref; // positive: offer priced above today's market
      if(drift>OFFER_DRIFT_THRESHOLD)
        return String.format("Your sell offer for %s is priced at %,d gp, but today's market is around %,d gp (%.0f%% above) -- it may sit unfilled. Consider cancelling and relisting closer to the market price, or selling at the current price.",o.name,o.price,ref,drift*100);
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
  private static String offerFillHint(ActiveOffer o, OfferFillEstimate fill) {
    if(fill==null || fill.likelyToFillInTime)return null;
    String pace=fill.estimatedFillMinutes<0
      ?"there's been almost no recent trading volume for it at all"
      :"recent volume suggests its remaining quantity typically takes roughly "+formatMinutes(fill.estimatedFillMinutes)+" to trade";
    return String.format("Your %s offer for %s (%,d remaining) is running longer than your target trade duration -- %s. This is a rough volume-based estimate, not a guarantee either way -- consider adjusting the price or quantity, or cancelling if you need it sooner.",o.buying?"buy":"sell",o.name,o.remaining,pace);
  }
  // Matches each still-in-progress offer (activeOffers, itself already excluding terminal ones --
  // see refreshActiveSlotItemIds) against the live price and fill estimate the bridge just returned
  // for it (slotPrices/slotFill, requested via suggestionQuery()'s slots= param) and joins every
  // resulting hint into one sidebar message -- both a price-drift hint and a fill-time hint can
  // apply to the same offer at once. A linear scan, not a map -- there are at most 8 GE slots, so
  // this is always trivially small. Never throws on a missing/short slotPrices/slotFill array;
  // simply skips any offer with no match.
  private void updatePanelOfferHint(List<ActiveOffer> offers, Suggestion[] slotPrices, OfferFillEstimate[] slotFill, RelistAdvice[] relistAdvice) {
    if(panel==null)return;
    if(offers.isEmpty() && (relistAdvice==null || relistAdvice.length==0)){panel.offerHint(null);return;}
    StringBuilder combined=new StringBuilder();
    // Shown first: an offer that has been sitting is the thing most worth acting on, and the
    // sentence already carries the market price and the break-even floor.
    if(relistAdvice!=null)for(RelistAdvice advice:relistAdvice) {
      if(advice==null || advice.message==null || advice.message.isEmpty())continue;
      if(combined.length()>0)combined.append("\n\n");
      combined.append(advice.message);
    }
    for(ActiveOffer o:offers) {
      Suggestion price=null;
      if(slotPrices!=null)for(Suggestion p:slotPrices)if(p!=null && p.itemId==o.itemId){price=p;break;}
      OfferFillEstimate fill=null;
      if(slotFill!=null)for(OfferFillEstimate f:slotFill)if(f!=null && f.itemId==o.itemId){fill=f;break;}
      String priceHint=offerDriftHint(o,price);
      String fillHint=offerFillHint(o,fill);
      if(priceHint!=null){if(combined.length()>0)combined.append("\n\n");combined.append(priceHint);}
      if(fillHint!=null){if(combined.length()>0)combined.append("\n\n");combined.append(fillHint);}
    }
    panel.offerHint(combined.length()>0?combined.toString():null);
  }
  static class Offer {int slot,itemId,price,total,filled,spent;String offerId,state,name="";boolean knownStart;
      // See capture(): ticks between first seeing this offer unfilled and its first fill, or -1 when
      // not known. startTick is local bookkeeping and is not sent to the bridge.
      transient int startTick=-1;int ticksToFill=-1;}
  static class Packet {int version=1;String session,account;long seq,ts;boolean loggedIn;List<Offer> offers=new ArrayList<>();}
  // Immutable per-offer snapshot for the sidebar's cancel/relist hint (offerDriftHint) -- separate
  // from Offer itself because an Offer instance is wholesale-replaced by capture() on every change
  // (see slots[]'s own doc), which is fine for slots[] (client-thread-only) but this one specifically
  // needs a cross-thread-safe reference, exactly like activeSlotItemIds just above it.
  static final class ActiveOffer {
    final int itemId,price,remaining;final boolean buying;final String name;
    ActiveOffer(int itemId,int price,boolean buying,String name,int remaining){this.itemId=itemId;this.price=price;this.buying=buying;this.name=name;this.remaining=remaining;}
  }
  // Immutable per-slot snapshot for the sidebar's "Active offers" list (see geOfferRows). state is
  // the raw GrandExchangeOfferState name (BUYING, SOLD, CANCELLED_BUY, ...); EviLivePanel turns it
  // into display text.
  static final class OfferRow {
    final int slot,itemId,price,filled,total;final boolean buying;final String name,state;
    OfferRow(int slot,int itemId,int price,int filled,int total,boolean buying,String name,String state){this.slot=slot;this.itemId=itemId;this.price=price;this.filled=filled;this.total=total;this.buying=buying;this.name=name;this.state=state;}
  }
  // slotPrices reuses Suggestion the same way openItemPrice already does just above it -- the
  // bridge's GET /api/suggestion sends each entry as {itemId,buyPrice,sellPrice} (see
  // lookupItemPrice in suggestions.mjs), and Gson populates only those three fields of a Suggestion,
  // leaving the rest (action, reasoning, etc.) at their defaults. See offerDriftHint for how these
  // get matched back up against activeOffers by itemId.
  static class SuggestionResponse {Suggestion suggestion,openItemPrice;Suggestion[] slotPrices;OfferFillEstimate[] slotFill;RelistAdvice[] relistAdvice;}
  // One entry per sell offer that has been sitting long enough to be worth mentioning, built by the
  // bridge from its own journal (see bridge/relist.mjs). `message` is already a complete, hedged
  // sentence naming the market price and, when the cost basis is known, the break-even price; the
  // plugin only displays it. Nothing here relists, cancels or edits anything -- the player does.
  static final class RelistAdvice {int itemId;String name,message;boolean belowBreakEven;}
  // One entry per in-progress offer the bridge could judge against the player's own "Target trade
  // duration" setting -- see estimateOfferFill in suggestions.mjs for exactly what this is (a rough
  // volume-based estimate from the OSRS Wiki API's own last-hour trading data, never a fill
  // guarantee) and offerFillHint for how it's turned into sidebar text. estimatedFillMinutes is -1
  // when there's essentially no recent trading volume at all for this item (see its own doc on the
  // bridge side for why -1, not a fabricated number or JSON null). Only ever populated when the
  // player has a target duration set; otherwise the bridge sends an empty array and this costs
  // nothing, exactly like slotPrices above it.
  static final class OfferFillEstimate {int itemId;boolean likelyToFillInTime;int estimatedFillMinutes;}
  // Request body for POST /api/suggestion/personal-use (see flagPersonalUse and
  // LocalTransport.markPersonalUse). personal defaults true -- this plugin only ever flags, never
  // unflags, today; the field exists on the bridge side for a possible future undo.
  static class PersonalUseRequest {String buyId;boolean personal=true;PersonalUseRequest(String buyId){this.buyId=buyId;}}
  // Request body for POST /api/suggestion/not-held (see flagNotHeld and LocalTransport.markNotHeld).
  // reason mirrors Store.closePosition's own two values; the sidebar offers the general "gone some
  // way EVI couldn't see" case, which is sold-untracked.
  static class NotHeldRequest {String buyId;String reason="sold-untracked";NotHeldRequest(String buyId){this.buyId=buyId;}}
}
