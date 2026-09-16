package com.evi.live;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import javax.swing.SwingUtilities;

/** Observes GE state; shows your own EVI suggestion as a text hint in the open quantity/price prompt and, on an optional hotkey, fills that one field with it. No menus, clicks, item selection, offer confirmation or other automated actions. */
@PluginDescriptor(name="EVI Live (Local)",description="Passively sends GE snapshots to your local EVI bridge; shows your own suggested quantity/price in the offer prompt and fills it on an optional hotkey",tags={"grand exchange","evi","hotkey","suggestion"},enabledByDefault=false)
public class EviLivePlugin extends Plugin {
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
  private Path pairingPath;
  private volatile boolean running;
  private volatile long lifecycle;
  private long pairingRevision;
  private final Gson gson=new Gson();
  private final ArrayDeque<String> queue=new ArrayDeque<>();
  private LocalTransport transport=new LocalTransport.Http();
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
    final int itemId,quantity;final String name;
    Held(int itemId,int quantity,String name){this.itemId=itemId;this.quantity=quantity;this.name=name;}
  }

  @Provides
  EviLiveConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(EviLiveConfig.class);
  }

  @Override protected void startUp() throws Exception {
    Path dir=RuneLite.RUNELITE_DIR.toPath().resolve("evi-live");
    Files.createDirectories(dir);
    pairingPath=dir.resolve("plugin-key.txt");
    pluginKey=null;
    String pairingStatus="Not paired. Paste your RuneLite plugin key below.";
    try {
      if(Files.exists(pairingPath))pluginKey=PairingKey.normalize(Files.readString(pairingPath,StandardCharsets.UTF_8));
      if(pluginKey!=null)pairingStatus="Paired locally. Log in to begin observing offers.";
    } catch(Exception ex){pairingStatus="The saved pairing key is empty, invalid, or unreadable. Paste a new key below.";}
    Path saltFile=dir.resolve("identity-salt.txt");
    if(!Files.exists(saltFile))Files.writeString(saltFile,UUID.randomUUID().toString(),StandardCharsets.UTF_8);
    salt=Files.readString(saltFile,StandardCharsets.UTF_8).trim();
    if(salt.isEmpty())throw new IllegalStateException("EVI identity salt is empty; restore it from your local backup.");
    Runnable createPanel=()->{
      panel=new EviLivePanel(this::pair,this::skipSuggestion);
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
      Files.writeString(pairingPath,key,StandardCharsets.UTF_8);
      clientThread.invokeLater(()->{
        if(!running || generation!=lifecycle)return;
        synchronized(queue){pluginKey=key;++pairingRevision;queue.clear();}
        reset();
        status("Pairing saved. Log in; connection is checked when the first snapshot is sent.");
      });
    }catch(IllegalArgumentException ex){status(ex.getMessage());}
    catch(Exception ex){status("Could not save the key. Check write access to .runelite/evi-live.");}
  }
  private void reset(){ready=false;warmTicks=0;ticks=0;profile=null;account=null;economy=null;session=UUID.randomUUID().toString();seq=0;Arrays.fill(slots,null);activeSlotItemIds=Collections.emptySet();skippedItemIds.clear();cashStack=-1;openOfferItemId=-1;heldForResale.clear();}
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
      heldForResale.put(old.itemId,new Held(old.itemId,old.filled,old.name));
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
  // Refreshes openOfferItemId (see its own field doc) from GEOffer every tick, same defensive
  // try/catch pattern as refreshCashStack -- a lookup hiccup just leaves it at "no slot open"
  // rather than breaking the rest of onGameTick.
  private void refreshOpenOfferItemId() {
    try { openOfferItemId = geOffer.isSlotOpen() ? geOffer.currentItemId() : -1; }
    catch(Exception ex){ openOfferItemId=-1; }
  }
  // Recomputes activeSlotItemIds from the current slots[] snapshot; must be called (on the client
  // thread, same as every other slots[] mutation) right after any change to that array so the
  // poll thread's view never goes stale for longer than one game tick.
  private void refreshActiveSlotItemIds() {
    Set<Integer> ids=new TreeSet<>();
    for(Offer o:slots)if(o!=null && !"EMPTY".equals(o.state))ids.add(o.itemId);
    activeSlotItemIds=ids;
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
    refreshOpenOfferItemId();
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
        System.err.println("EVI Live: local delivery queue overflow; observation restarted. Review missing trades manually.");
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
      suggestionCache.set(s);
      openItemPriceCache.set(r==null?null:r.openItemPrice);
      updatePanelSuggestion(s,s==null?"No eligible reviewed flip is currently profitable.":null);
    } catch(Throwable ex){
      // Deliberately catches Throwable, not just Exception, and kept that way permanently: a
      // periodic ScheduledExecutorService task that lets ANY throwable escape -- including an
      // Error, which a plain "catch(Exception)" does NOT catch -- gets silently cancelled forever
      // by the executor, with nothing printed anywhere. (This is exactly how a package-private
      // RiskLevel enum being inaccessible to RuneLite's config proxy took suggestions down
      // completely -- see RiskLevel.java and the 2026-09-15 README entry.) Logging the full trace
      // and showing a short message keeps any future unanticipated failure visible and recoverable
      // instead of silently killing suggestions forever.
      ex.printStackTrace();
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
  // Built from this plugin's own local config plus live, session-only state (never from observed
  // content) and sent as the GET /api/suggestion query string. Each part is left off entirely
  // when it's at its default/empty, so an untouched config with nothing active or skipped sends
  // an empty query, unchanged from before any of these existed.
  private String suggestionQuery() {
    StringBuilder q=new StringBuilder();
    int minProfit=config.minProfitThreshold();
    if(minProfit>0)q.append("minProfit=").append(minProfit);
    String blocklist=sanitizeBlocklist(config.itemBlocklist());
    if(!blocklist.isEmpty()){if(q.length()>0)q.append('&');q.append("blocklist=").append(blocklist);}
    RiskLevel risk=config.riskLevel();
    if(risk!=null && risk!=RiskLevel.MEDIUM){if(q.length()>0)q.append('&');q.append("risk=").append(risk.param());}
    if(config.includeMarketSuggestions()){if(q.length()>0)q.append('&');q.append("includeMarket=1");}
    TradeDuration duration=config.tradeDuration();
    if(duration!=null && duration.minutes()>0){if(q.length()>0)q.append('&');q.append("duration=").append(duration.minutes());}
    // Session-only, not a config setting: the player's actual current cash stack, so the bridge
    // never suggests a trade bigger than what's actually affordable right now. Left off entirely
    // when not yet known (cashStack==-1, e.g. the very first ticks after login), same treatment
    // as every other session-only signal here.
    if(cashStack>=0){if(q.length()>0)q.append('&');q.append("cash=").append(cashStack);}
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
    }
    // Session-only, not a config setting: the item currently selected in an open GE offer (see
    // openOfferItemId's own field doc), so the bridge can return a plain live-market price for it
    // via the response's separate openItemPrice field, regardless of flip history or ranking.
    if(openOfferItemId>0){if(q.length()>0)q.append('&');q.append("openItemId=").append(openOfferItemId);}
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
  private void updatePanelSuggestion(Suggestion s, String diag) {
    if(panel==null)return;
    if(s==null){panel.suggestion(diag);return;}
    panel.suggestion(String.format("%s x%,d — buy %,d gp / sell %,d gp%s",s.name,s.quantity,s.buyPrice,s.sellPrice,s.reasoning==null||s.reasoning.isEmpty()?"":" — "+s.reasoning));
  }
  static class Offer {int slot,itemId,price,total,filled,spent;String offerId,state,name="";boolean knownStart;}
  static class Packet {int version=1;String session,account;long seq,ts;boolean loggedIn;List<Offer> offers=new ArrayList<>();}
  static class SuggestionResponse {Suggestion suggestion,openItemPrice;}
}
