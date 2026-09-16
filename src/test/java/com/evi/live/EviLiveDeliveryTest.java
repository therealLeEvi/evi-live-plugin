package com.evi.live;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Synthetic transport only. Does not connect to the user's bridge. */
public final class EviLiveDeliveryTest {
  static void set(Object target,String name,Object value)throws Exception {
    Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
  }
  static Object get(Object target,String name)throws Exception {
    Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
  }
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static final class Fake implements LocalTransport {
    int code=200;boolean fail;List<String> bodies=new ArrayList<>();
    public int send(String key,String json)throws IOException {
      bodies.add(json);if(fail)throw new IOException("synthetic disconnect");return code;
    }
    public String get(String key,String query)throws IOException {return null;}
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] args)throws Exception {
    EviLivePlugin plugin=new EviLivePlugin();Fake fake=new Fake();
    // gson is @Inject-only on the real plugin (Guice supplies the client's shared instance; the
    // Hub's packager rejects a plugin that constructs its own) -- this harness builds the plugin
    // with `new`, not Guice, so it has to seed the field itself, same as every other @Inject field
    // this file sets via reflection below.
    set(plugin,"gson",new Gson());
    set(plugin,"transport",fake);set(plugin,"running",true);set(plugin,"lifecycle",1L);
    set(plugin,"pluginKey","abcdef0123456789".repeat(4));
    ArrayDeque<String> queue=(ArrayDeque<String>)get(plugin,"queue");
    Method flush=EviLivePlugin.class.getDeclaredMethod("flush",long.class);flush.setAccessible(true);
    queue.add("synthetic-packet-one");fake.code=401;flush.invoke(plugin,1L);
    check(queue.size()==1,"Wrong key must retain pending packet");
    fake.code=200;fake.fail=true;flush.invoke(plugin,1L);
    check(queue.size()==1,"Disconnect must retain pending packet");
    fake.fail=false;flush.invoke(plugin,1L);
    check(queue.isEmpty(),"Successful retry must remove packet");
    check(fake.bodies.size()==3&&fake.bodies.stream().allMatch("synthetic-packet-one"::equals),"Retries must be byte-identical");
    queue.add("new-session-packet");set(plugin,"lifecycle",2L);flush.invoke(plugin,1L);
    check(queue.size()==1&&fake.bodies.size()==3,"Old sender generation must not send new session data");
    set(plugin,"running",false);flush.invoke(plugin,2L);
    check(queue.size()==1&&fake.bodies.size()==3,"Disabled plugin must not deliver");
    set(plugin,"running",true);
    set(plugin,"transport",new LocalTransport(){
      public int send(String key,String json)throws IOException {
        try {
          set(plugin,"pairingRevision",1L);
          queue.clear();queue.add("re-paired-session-packet");
        }catch(Exception ex){throw new IOException(ex);}
        return 200;
      }
      public String get(String key,String query)throws IOException {return null;}
    });
    flush.invoke(plugin,2L);
    check(queue.size()==1&&"re-paired-session-packet".equals(queue.peek()),"Old pairing response must not consume replacement pairing queue");
    queue.clear();for(int i=0;i<512;i++)queue.add("queued-"+i);
    set(plugin,"account","synthetic-account");set(plugin,"session","old-session");
    Method enqueue=EviLivePlugin.class.getDeclaredMethod("enqueue",boolean.class);enqueue.setAccessible(true);enqueue.invoke(plugin,false);
    check(queue.isEmpty()&&get(plugin,"account")==null&&!"old-session".equals(get(plugin,"session")),"Queue overflow must clear unsafe coverage and create a new baseline");

    // suggestionQuery()/sanitizeBlocklist(): built entirely from local config, never from observed
    // content, and must round-trip cleanly to the query string the bridge actually parses.
    Method sanitize=EviLivePlugin.class.getDeclaredMethod("sanitizeBlocklist",String.class);sanitize.setAccessible(true);
    check("4151,995,200".equals(sanitize.invoke(null,"4151, 995,,abc,-3, 200")),"sanitizeBlocklist must trim, drop empty/non-numeric/negative entries, and keep valid IDs in order");
    check("".equals(sanitize.invoke(null,"")),"sanitizeBlocklist must return empty for an empty blocklist");
    check("".equals(sanitize.invoke(null,(Object)null)),"sanitizeBlocklist must return empty for a null blocklist");

    Method suggestionQuery=EviLivePlugin.class.getDeclaredMethod("suggestionQuery");suggestionQuery.setAccessible(true);
    Method resetMethod=EviLivePlugin.class.getDeclaredMethod("reset");resetMethod.setAccessible(true);
    EviLivePlugin defaults=new EviLivePlugin();
    set(defaults,"config",new EviLiveConfig(){});
    check("".equals(suggestionQuery.invoke(defaults)),"An untouched config must produce an empty query, unchanged from before these settings existed");

    EviLivePlugin tuned=new EviLivePlugin();
    set(tuned,"config",new EviLiveConfig(){
      public int minProfitThreshold(){return 500000;}
      public String itemBlocklist(){return "4151, 995";}
      public RiskLevel riskLevel(){return RiskLevel.HIGH;}
      public boolean includeMarketSuggestions(){return true;}
      public TradeDuration tradeDuration(){return TradeDuration.TEN;}
    });
    check("minProfit=500000&blocklist=4151,995&risk=high&includeMarket=1&duration=10".equals(suggestionQuery.invoke(tuned)),"All five settings must appear in the query string when set, includeMarket then duration last");

    EviLivePlugin noDurationPreference=new EviLivePlugin();
    set(noDurationPreference,"config",new EviLiveConfig(){
      public TradeDuration tradeDuration(){return TradeDuration.NONE;}
    });
    check("".equals(suggestionQuery.invoke(noDurationPreference)),"Explicit NONE (no preference) must be left off the query, matching the default");

    EviLivePlugin durationOnly=new EviLivePlugin();
    set(durationOnly,"config",new EviLiveConfig(){
      public TradeDuration tradeDuration(){return TradeDuration.SIXTY;}
    });
    check("duration=60".equals(suggestionQuery.invoke(durationOnly)),"duration=60 alone, with no leading '&', when it's the only tuned setting");

    EviLivePlugin mediumRisk=new EviLivePlugin();
    set(mediumRisk,"config",new EviLiveConfig(){
      public RiskLevel riskLevel(){return RiskLevel.MEDIUM;}
    });
    check("".equals(suggestionQuery.invoke(mediumRisk)),"Explicit medium risk must be left off the query, matching the bridge's own default");

    EviLivePlugin marketOnly=new EviLivePlugin();
    set(marketOnly,"config",new EviLiveConfig(){
      public boolean includeMarketSuggestions(){return true;}
    });
    check("includeMarket=1".equals(suggestionQuery.invoke(marketOnly)),"includeMarket=1 alone, with no leading '&', when it's the only tuned setting");

    EviLivePlugin marketOff=new EviLivePlugin();
    set(marketOff,"config",new EviLiveConfig(){
      public boolean includeMarketSuggestions(){return false;}
    });
    check("".equals(suggestionQuery.invoke(marketOff)),"Explicit includeMarketSuggestions=false must be left off the query, matching the default");

    // cashStack: the player's actual current cash stack (from refreshCashStack(), not a config
    // setting) -- left off the query entirely while unknown (-1, the default before any tick has
    // run), and included as a plain integer once known, so a real reading of 0 gp (dead broke)
    // still reaches the bridge rather than being treated the same as "unknown".
    EviLivePlugin cashPlugin=new EviLivePlugin();
    set(cashPlugin,"config",new EviLiveConfig(){});
    check("".equals(suggestionQuery.invoke(cashPlugin)),"Unknown cash stack (-1, the default) must be left off the query entirely");
    set(cashPlugin,"cashStack",0);
    check("cash=0".equals(suggestionQuery.invoke(cashPlugin)),"A real reading of 0 gp must still be sent, not treated as unknown");
    set(cashPlugin,"cashStack",2147483647);
    check("cash=2147483647".equals(suggestionQuery.invoke(cashPlugin)),"A full cash stack must be sent as-is");
    resetMethod.invoke(cashPlugin);
    check("".equals(suggestionQuery.invoke(cashPlugin)),"reset() (login/profile/world-hop) must clear the cash-stack reading back to unknown");

    // openOfferItemId: the item currently selected in an open GE offer (from
    // refreshOpenOfferItemId(), not a config setting) -- left off the query entirely while no slot
    // is open (-1, the default), and included as openItemId once known, so the bridge can return a
    // plain live-market price for it regardless of flip history or ranking.
    EviLivePlugin openItemPlugin=new EviLivePlugin();
    set(openItemPlugin,"config",new EviLiveConfig(){});
    check("".equals(suggestionQuery.invoke(openItemPlugin)),"No GE slot open (-1, the default) must be left off the query entirely");
    set(openItemPlugin,"openOfferItemId",314);
    check("openItemId=314".equals(suggestionQuery.invoke(openItemPlugin)),"The currently open item's ID must be sent as openItemId");
    resetMethod.invoke(openItemPlugin);
    check("".equals(suggestionQuery.invoke(openItemPlugin)),"reset() (login/profile/world-hop) must clear the open-item reading back to unknown");

    // heldForResale / updateHeldForResale(): reminding the player to close out a position they
    // already opened (bought and collected) but haven't resold yet, instead of moving straight on
    // to a brand-new suggestion while it just sits in the inventory.
    Method updateHeld=EviLivePlugin.class.getDeclaredMethod("updateHeldForResale",EviLivePlugin.Offer.class,EviLivePlugin.Offer.class);
    updateHeld.setAccessible(true);
    EviLivePlugin holdPlugin=new EviLivePlugin();
    set(holdPlugin,"config",new EviLiveConfig(){});
    check("".equals(suggestionQuery.invoke(holdPlugin)),"Nothing held yet: the query must be unaffected");

    EviLivePlugin.Offer buying=new EviLivePlugin.Offer();buying.state="BUYING";buying.itemId=7;buying.name="Steel cannonballs";buying.filled=0;
    updateHeld.invoke(holdPlugin,(Object)null,buying); // the very first observation of a slot: never triggers either case
    check("".equals(suggestionQuery.invoke(holdPlugin)),"An in-progress buy alone (no prior state, nothing collected yet) must not be held");

    EviLivePlugin.Offer bought=new EviLivePlugin.Offer();bought.state="BOUGHT";bought.itemId=7;bought.name="Steel cannonballs";bought.filled=1000;
    updateHeld.invoke(holdPlugin,buying,bought);
    check("".equals(suggestionQuery.invoke(holdPlugin)),"A completed buy not yet collected (slot still non-empty) must not be held yet");

    EviLivePlugin.Offer collected=new EviLivePlugin.Offer();collected.state="EMPTY";collected.itemId=0;
    updateHeld.invoke(holdPlugin,bought,collected);
    check("holdItemId=7&holdQty=1000&holdName=Steel+cannonballs".equals(suggestionQuery.invoke(holdPlugin)),
      "Collecting a completed buy with fills must record it as held for resale and append it to the query, name URL-encoded");

    EviLivePlugin.Offer selling=new EviLivePlugin.Offer();selling.state="SELLING";selling.itemId=7;selling.name="Steel cannonballs";selling.filled=0;
    updateHeld.invoke(holdPlugin,collected,selling);
    check("".equals(suggestionQuery.invoke(holdPlugin)),"Starting to sell a held item must clear it -- the player is already acting on it");

    // Re-buying and re-collecting must refresh (not duplicate) the held entry for the same item.
    updateHeld.invoke(holdPlugin,buying,bought);
    updateHeld.invoke(holdPlugin,bought,collected);
    check("holdItemId=7&holdQty=1000&holdName=Steel+cannonballs".equals(suggestionQuery.invoke(holdPlugin)),
      "Re-collecting the same item must refresh its held entry, not create a second one");

    EviLivePlugin.Offer cancelledEmptyFill=new EviLivePlugin.Offer();cancelledEmptyFill.state="CANCELLED_BUY";cancelledEmptyFill.itemId=9;cancelledEmptyFill.name="Adamant arrow";cancelledEmptyFill.filled=0;
    EviLivePlugin.Offer emptied=new EviLivePlugin.Offer();emptied.state="EMPTY";emptied.itemId=0;
    updateHeld.invoke(holdPlugin,cancelledEmptyFill,emptied);
    check("holdItemId=7&holdQty=1000&holdName=Steel+cannonballs".equals(suggestionQuery.invoke(holdPlugin)),
      "A cancelled buy with nothing filled must not be recorded as held -- nothing was actually bought");

    resetMethod.invoke(holdPlugin);
    check("".equals(suggestionQuery.invoke(holdPlugin)),"reset() (login/profile/world-hop) must clear held-for-resale too");

    // activeSlotItemIds / skippedItemIds / refreshActiveSlotItemIds(): the plugin's own live,
    // session-only exclusions layered on top of the settings-driven query above -- never a config
    // setting, so an untouched config with nothing active or skipped still sends an empty query.
    Method refresh=EviLivePlugin.class.getDeclaredMethod("refreshActiveSlotItemIds");refresh.setAccessible(true);
    EviLivePlugin excludePlugin=new EviLivePlugin();
    set(excludePlugin,"config",new EviLiveConfig(){});
    EviLivePlugin.Offer[] exSlots=(EviLivePlugin.Offer[])get(excludePlugin,"slots");
    exSlots[0]=new EviLivePlugin.Offer();exSlots[0].state="EMPTY";
    exSlots[1]=new EviLivePlugin.Offer();exSlots[1].state="BUYING";exSlots[1].itemId=7;
    exSlots[2]=new EviLivePlugin.Offer();exSlots[2].state="BOUGHT";exSlots[2].itemId=9; // terminal but not yet collected: still occupies the slot
    refresh.invoke(excludePlugin);
    check("exclude=7,9".equals(suggestionQuery.invoke(excludePlugin)),"Active (non-EMPTY) GE slots must be auto-excluded, sorted ascending, even with an untouched config");

    @SuppressWarnings("unchecked")
    java.util.Set<Integer> skipped=(java.util.Set<Integer>)get(excludePlugin,"skippedItemIds");
    skipped.add(3);
    check("exclude=3,7,9".equals(suggestionQuery.invoke(excludePlugin)),"A manually skipped item must merge with the auto-excluded active-slot items, sorted together");

    exSlots[1].state="EMPTY";exSlots[1].itemId=0; // collected: the slot is empty again
    refresh.invoke(excludePlugin);
    check("exclude=3,9".equals(suggestionQuery.invoke(excludePlugin)),"Collecting a slot back to EMPTY must drop it from the exclude set on the next refresh, with no separate bookkeeping needed");

    resetMethod.invoke(excludePlugin);
    check("".equals(suggestionQuery.invoke(excludePlugin)),"reset() (login/profile/world-hop) must clear both the manual skip set and the active-slot snapshot for a fresh session");

    // skipSuggestion(): the sidebar's "Skip this suggestion" button callback.
    EviLivePlugin skipPlugin=new EviLivePlugin();
    SuggestionCache skipCache=new SuggestionCache();
    set(skipPlugin,"suggestionCache",skipCache);
    Method skipMethod=EviLivePlugin.class.getDeclaredMethod("skipSuggestion");skipMethod.setAccessible(true);
    skipCache.set(EviLiveSuggestionTest.suggestion(5,"buy",10,20,30));
    skipMethod.invoke(skipPlugin); // sender is null here (startUp() never ran): must not throw
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> skipPluginSkips=(java.util.Set<Integer>)get(skipPlugin,"skippedItemIds");
    check(skipPluginSkips.contains(5),"skipSuggestion() must add the currently cached suggestion's item ID to skippedItemIds");
    check(skipCache.get()==null,"skipSuggestion() must clear the cache immediately so the panel doesn't show stale text until the next poll");
    skipMethod.invoke(skipPlugin); // nothing cached now: must be a safe no-op, not throw or add anything
    check(skipPluginSkips.size()==1,"Calling skipSuggestion() again with nothing cached must not add anything or throw");

    System.out.println("PASS: authentication failure, disconnect, exact retry, stale sender, disabled delivery, pairing replacement, overflow rebaseline, the suggestion-settings query builder (including target trade duration), the cash-stack query building, the open-offer-item query building, the held-for-resale query building, the active-slot/skip exclude query building, and the skip-suggestion callback");
  }
}
