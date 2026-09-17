package com.evi.live;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    // marginSafetyCushion briefly defaulted to ON, which blocked every live candidate and left the
    // player with no suggestion at all (see EviLiveConfig's own doc). It's back to off by default
    // like every other setting here, so an untouched config sends an empty query again. The
    // explicit marginSafetyCushion(){return false;} overrides in the tests below predate that and
    // are now redundant, but still pin those tests to "cushion off" whatever the default becomes.
    EviLivePlugin defaults=new EviLivePlugin();
    set(defaults,"config",new EviLiveConfig(){});
    check("".equals(suggestionQuery.invoke(defaults)),"An untouched config must send an empty query -- marginSafetyCushion is off by default");
    Method cushionItem=EviLiveConfig.class.getDeclaredMethod("marginSafetyCushion");
    check("requireMarginAboveNoise".equals(cushionItem.getAnnotation(net.runelite.client.config.ConfigItem.class).keyName()),"marginSafetyCushion must use its new keyName, so a 'true' RuneLite stored under the old on-by-default key is not picked up");

    EviLivePlugin tuned=new EviLivePlugin();
    set(tuned,"config",new EviLiveConfig(){
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T500K;}
      public boolean marginSafetyCushion(){return false;}
      public String itemBlocklist(){return "4151, 995";}
      public RiskLevel riskLevel(){return RiskLevel.HIGH;}
      public boolean includeMarketSuggestions(){return true;}
      public TradeDuration tradeDuration(){return TradeDuration.TEN;}
    });
    check("minProfit=500000&blocklist=4151,995&risk=high&includeMarket=1&duration=10".equals(suggestionQuery.invoke(tuned)),"All five settings must appear in the query string when set, includeMarket then duration last (cushion disabled here so this test stays about exactly those five)");

    // MinProfitTier: replaces the old free-form gp field with plain preset tiers, AUTO meaning
    // "any profit, no floor" -- must behave exactly like the old default of 0 (left off the query
    // entirely), and each tier must send its own documented gp figure.
    EviLivePlugin autoTier=new EviLivePlugin();
    set(autoTier,"config",new EviLiveConfig(){
      public MinProfitTier minProfitThreshold(){return MinProfitTier.AUTO;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(autoTier)),"AUTO (the default tier) must be left off the query entirely, same as the old free-form field's 0");

    EviLivePlugin t100k=new EviLivePlugin();
    set(t100k,"config",new EviLiveConfig(){
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T100K;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("minProfit=100000".equals(suggestionQuery.invoke(t100k)),"T100K alone, with no leading '&', when it's the only tuned setting");

    EviLivePlugin t200k=new EviLivePlugin();
    set(t200k,"config",new EviLiveConfig(){
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T200K;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("minProfit=200000".equals(suggestionQuery.invoke(t200k)),"T200K's gp figure");

    EviLivePlugin t1m=new EviLivePlugin();
    set(t1m,"config",new EviLiveConfig(){
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T1M;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("minProfit=1000000".equals(suggestionQuery.invoke(t1m)),"T1M's gp figure");

    EviLivePlugin t2m=new EviLivePlugin();
    set(t2m,"config",new EviLiveConfig(){
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T2M;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("minProfit=2000000".equals(suggestionQuery.invoke(t2m)),"T2M's gp figure");

    // marginSafetyCushion: off by default (see the `defaults` test above); explicitly disabling it
    // must leave the query exactly as before this setting existed, and turning it on must combine
    // with a profit tier in the documented order (minProfit=, then cushion=1, then everything else).
    EviLivePlugin cushionExplicitOff=new EviLivePlugin();
    set(cushionExplicitOff,"config",new EviLiveConfig(){
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(cushionExplicitOff)),"Explicit marginSafetyCushion=false must be left off the query, matching pre-existing behaviour");

    EviLivePlugin cushionWithTier=new EviLivePlugin();
    set(cushionWithTier,"config",new EviLiveConfig(){
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T500K;}
      public boolean marginSafetyCushion(){return true;}
    });
    check("minProfit=500000&cushion=1".equals(suggestionQuery.invoke(cushionWithTier)),"A profit tier plus an opted-in cushion must both appear, minProfit= before cushion=1");

    EviLivePlugin noDurationPreference=new EviLivePlugin();
    set(noDurationPreference,"config",new EviLiveConfig(){
      public TradeDuration tradeDuration(){return TradeDuration.NONE;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(noDurationPreference)),"Explicit NONE (no preference) must be left off the query, matching the default");

    EviLivePlugin durationOnly=new EviLivePlugin();
    set(durationOnly,"config",new EviLiveConfig(){
      public TradeDuration tradeDuration(){return TradeDuration.SIXTY;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("duration=60".equals(suggestionQuery.invoke(durationOnly)),"duration=60 alone, with no leading '&', when it's the only tuned setting");
    int[] longMinutes={120,240,480,720,1440};TradeDuration[] longOptions={TradeDuration.TWO_HOURS,TradeDuration.FOUR_HOURS,TradeDuration.EIGHT_HOURS,TradeDuration.TWELVE_HOURS,TradeDuration.DAY};
    for(int i=0;i<longOptions.length;i++){
      final TradeDuration d=longOptions[i];
      EviLivePlugin longTrade=new EviLivePlugin();
      set(longTrade,"config",new EviLiveConfig(){public TradeDuration tradeDuration(){return d;}});
      check(("duration="+longMinutes[i]).equals(suggestionQuery.invoke(longTrade)),"Hour-plus trade durations must send their minutes: "+d);
    }
    // members=: which kind of world the player is on, so the bridge never suggests a members-only
    // item on a free-to-play world. Unknown (not yet logged in) must send nothing at all.
    EviLivePlugin worldPlugin=new EviLivePlugin();
    set(worldPlugin,"config",new EviLiveConfig(){});
    check("".equals(suggestionQuery.invoke(worldPlugin)),"An unknown world type must not send members= at all");
    set(worldPlugin,"membersWorld",Boolean.TRUE);
    check("members=1".equals(suggestionQuery.invoke(worldPlugin)),"A members world must send members=1");
    set(worldPlugin,"membersWorld",Boolean.FALSE);
    check("members=0".equals(suggestionQuery.invoke(worldPlugin)),"A free-to-play world must send members=0");
    resetMethod.invoke(worldPlugin);
    check("".equals(suggestionQuery.invoke(worldPlugin)),"reset() (logout/world hop) must forget the world type");
    check(TradeDuration.valueOf("SIXTY")==TradeDuration.SIXTY && TradeDuration.values()[4]==TradeDuration.SIXTY,"Existing constants keep their names so stored settings still load");
    // Loss fields from the bridge: null when unknown (Gson leaves boxed fields null), set for a losing sell.
    Gson lossGson=new Gson();
    Suggestion unknownCost=lossGson.fromJson("{\"itemId\":1,\"action\":\"sell\",\"sellPrice\":130,\"breakEvenPrice\":null,\"lossIfSoldNow\":null}",Suggestion.class);
    check(unknownCost.breakEvenPrice==null && unknownCost.lossIfSoldNow==null && !unknownCost.sellsAtLoss(),"Unknown cost basis must never read as a loss");
    Suggestion losingSell=lossGson.fromJson("{\"itemId\":1,\"action\":\"sell\",\"sellPrice\":130,\"breakEvenPrice\":153,\"lossIfSoldNow\":1100}",Suggestion.class);
    check(losingSell.sellsAtLoss() && losingSell.breakEvenPrice==153 && losingSell.lossIfSoldNow==1100L,"A losing sell must parse its break-even price and loss");
    Suggestion profitableSell=lossGson.fromJson("{\"itemId\":1,\"action\":\"sell\",\"sellPrice\":130,\"breakEvenPrice\":91,\"lossIfSoldNow\":null}",Suggestion.class);
    check(!profitableSell.sellsAtLoss(),"A profitable sell with a known break-even is not a loss");

    EviLivePlugin mediumRisk=new EviLivePlugin();
    set(mediumRisk,"config",new EviLiveConfig(){
      public RiskLevel riskLevel(){return RiskLevel.MEDIUM;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(mediumRisk)),"Explicit medium risk must be left off the query, matching the bridge's own default");

    EviLivePlugin marketOnly=new EviLivePlugin();
    set(marketOnly,"config",new EviLiveConfig(){
      public boolean includeMarketSuggestions(){return true;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("includeMarket=1".equals(suggestionQuery.invoke(marketOnly)),"includeMarket=1 alone, with no leading '&', when it's the only tuned setting");

    EviLivePlugin marketOff=new EviLivePlugin();
    set(marketOff,"config",new EviLiveConfig(){
      public boolean includeMarketSuggestions(){return false;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(marketOff)),"Explicit includeMarketSuggestions=false must be left off the query, matching the default");

    // forecastHorizon/forecastPolicy: OFF (the default) must send neither param, even if the policy
    // alone is non-default -- there's nothing to have a policy about without a horizon. An enabled
    // horizon always sends its policy too, even the default (warn), since the bridge's own default
    // for an unset onForecast is also warn and this keeps the query string self-explanatory either way.
    EviLivePlugin forecastOff=new EviLivePlugin();
    set(forecastOff,"config",new EviLiveConfig(){
      public ForecastHorizon forecastHorizon(){return ForecastHorizon.OFF;}
      public ForecastPolicy forecastPolicy(){return ForecastPolicy.SKIP;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(forecastOff)),"Explicit forecastHorizon=OFF must be left off the query even with a non-default policy, matching the default");

    EviLivePlugin forecastOneHour=new EviLivePlugin();
    set(forecastOneHour,"config",new EviLiveConfig(){
      public ForecastHorizon forecastHorizon(){return ForecastHorizon.ONE_HOUR;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("forecast=1h&onForecast=warn".equals(suggestionQuery.invoke(forecastOneHour)),"An enabled horizon must always send its policy too, even when the policy itself is left at its default (warn)");

    EviLivePlugin forecastSixHour=new EviLivePlugin();
    set(forecastSixHour,"config",new EviLiveConfig(){
      public ForecastHorizon forecastHorizon(){return ForecastHorizon.SIX_HOUR;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("forecast=6h&onForecast=warn".equals(suggestionQuery.invoke(forecastSixHour)),"6h horizon param value");

    EviLivePlugin forecastOvernightSkip=new EviLivePlugin();
    set(forecastOvernightSkip,"config",new EviLiveConfig(){
      public ForecastHorizon forecastHorizon(){return ForecastHorizon.OVERNIGHT;}
      public ForecastPolicy forecastPolicy(){return ForecastPolicy.SKIP;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("forecast=overnight&onForecast=skip".equals(suggestionQuery.invoke(forecastOvernightSkip)),"Overnight horizon with skip policy");

    EviLivePlugin forecastWithDuration=new EviLivePlugin();
    set(forecastWithDuration,"config",new EviLiveConfig(){
      public TradeDuration tradeDuration(){return TradeDuration.THIRTY;}
      public ForecastHorizon forecastHorizon(){return ForecastHorizon.OVERNIGHT;}
      public ForecastPolicy forecastPolicy(){return ForecastPolicy.SKIP;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("duration=30&forecast=overnight&onForecast=skip".equals(suggestionQuery.invoke(forecastWithDuration)),"forecast=/onForecast= must follow duration= in the query string, joined with '&' like every other setting here");

    // includeInventory/inventory: opt-in (EviLiveConfig.suggestIdleInventory()) plus the client-thread
    // inventoryQuantities snapshot (see refreshInventoryItemIds/its own field doc) -- both must be
    // true/non-empty before anything is sent, and item IDs must appear sorted for a deterministic
    // query string, matching the existing `exclude=` convention.
    Map<Integer,Integer> snapshot=new java.util.HashMap<>();
    snapshot.put(30810,11);snapshot.put(995,50000000);snapshot.put(4151,1);
    EviLivePlugin inventoryOff=new EviLivePlugin();
    set(inventoryOff,"config",new EviLiveConfig(){
      public boolean marginSafetyCushion(){return false;}
    });
    set(inventoryOff,"inventoryQuantities",snapshot);
    check("".equals(suggestionQuery.invoke(inventoryOff)),"suggestIdleInventory defaults to off, so a populated inventory snapshot must still be left off the query");

    EviLivePlugin inventoryOnEmpty=new EviLivePlugin();
    set(inventoryOnEmpty,"config",new EviLiveConfig(){
      public boolean suggestIdleInventory(){return true;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(inventoryOnEmpty)),"suggestIdleInventory=true with an empty inventory snapshot (the default) must still produce an empty query");

    EviLivePlugin inventoryOn=new EviLivePlugin();
    set(inventoryOn,"config",new EviLiveConfig(){
      public boolean suggestIdleInventory(){return true;}
      public boolean marginSafetyCushion(){return false;}
    });
    set(inventoryOn,"inventoryQuantities",snapshot);
    check("includeInventory=1&inventory=995:50000000,4151:1,30810:11".equals(suggestionQuery.invoke(inventoryOn)),"Inventory items must be sent sorted by item ID for a deterministic query string");

    // account: the same identifier already sent with every ingest packet, not a config setting --
    // left off the query entirely before the first login this process has seen (null, the
    // default), and included once known so the bridge can scope its cross-restart open-position
    // fallback (pickPersistentOpenPosition, bridge/suggestions.mjs) to this account specifically.
    EviLivePlugin accountPlugin=new EviLivePlugin();
    set(accountPlugin,"config",new EviLiveConfig(){
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(accountPlugin)),"No account known yet (null, the default) must be left off the query entirely");
    set(accountPlugin,"account","deadbeef0123456789");
    check("account=deadbeef0123456789".equals(suggestionQuery.invoke(accountPlugin)),"A known account must be sent as-is");
    resetMethod.invoke(accountPlugin);
    check("".equals(suggestionQuery.invoke(accountPlugin)),"reset() (login/profile/world-hop) must clear the account back to unknown until the next login completes");

    // cashStack: the player's actual current cash stack (from refreshCashStack(), not a config
    // setting) -- left off the query entirely while unknown (-1, the default before any tick has
    // run), and included as a plain integer once known, so a real reading of 0 gp (dead broke)
    // still reaches the bridge rather than being treated the same as "unknown".
    EviLivePlugin cashPlugin=new EviLivePlugin();
    set(cashPlugin,"config",new EviLiveConfig(){
      public boolean marginSafetyCushion(){return false;}
    });
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
    set(openItemPlugin,"config",new EviLiveConfig(){
      public boolean marginSafetyCushion(){return false;}
    });
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
    set(holdPlugin,"config",new EviLiveConfig(){
      public boolean marginSafetyCushion(){return false;}
    });
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

    // Held.offerId / holdBuyId: the specific buy offer behind a held-for-resale item, when the
    // observed Offer carried one, is appended to the query too -- lets a later "Personal use" flag
    // mark that exact purchase (see flagPersonalUse) rather than the item broadly.
    EviLivePlugin.Offer buyingWithId=new EviLivePlugin.Offer();buyingWithId.state="BUYING";buyingWithId.itemId=7;buyingWithId.name="Steel cannonballs";buyingWithId.filled=0;buyingWithId.offerId="buy-42";
    EviLivePlugin.Offer boughtWithId=new EviLivePlugin.Offer();boughtWithId.state="BOUGHT";boughtWithId.itemId=7;boughtWithId.name="Steel cannonballs";boughtWithId.filled=1000;boughtWithId.offerId="buy-42";
    updateHeld.invoke(holdPlugin,buyingWithId,boughtWithId);
    updateHeld.invoke(holdPlugin,boughtWithId,collected);
    check("holdItemId=7&holdQty=1000&holdName=Steel+cannonballs&holdBuyId=buy-42".equals(suggestionQuery.invoke(holdPlugin)),
      "A held item's own buy offerId must be appended as holdBuyId, URL-encoded, after the existing hold fields");

    // Held.price / holdBuyPrice: the REAL average price actually paid (spent/filled), not the
    // offer's set/max price -- this is the fix for the real-world failure report of EVI suggesting
    // a buy, then coming back with a plain "sell near X gp" reminder with no idea whether X was
    // above or below what was actually paid. Reusing holdPlugin/collected/buying deliberately: a
    // held entry recorded with NO spent data (every case above this point) must keep sending no
    // holdBuyPrice= at all, exactly as before this field existed.
    check(!suggestionQuery.invoke(holdPlugin).toString().contains("holdBuyPrice"),"Every hold above this point had no spent data (Offer.spent defaults to 0) and must never fabricate a cost basis");
    EviLivePlugin.Offer buyingPriced=new EviLivePlugin.Offer();buyingPriced.state="BUYING";buyingPriced.itemId=7;buyingPriced.name="Steel cannonballs";buyingPriced.filled=0;
    EviLivePlugin.Offer boughtPriced=new EviLivePlugin.Offer();boughtPriced.state="BOUGHT";boughtPriced.itemId=7;boughtPriced.name="Steel cannonballs";boughtPriced.filled=1000;boughtPriced.spent=12345; // 12.345 gp/unit average
    updateHeld.invoke(holdPlugin,buyingPriced,boughtPriced);
    updateHeld.invoke(holdPlugin,boughtPriced,collected);
    check("holdItemId=7&holdQty=1000&holdName=Steel+cannonballs&holdBuyPrice=12".equals(suggestionQuery.invoke(holdPlugin)),
      "A real spent/filled figure must be sent as holdBuyPrice, rounded to the nearest gp (12345/1000 = 12.345 -> 12)");
    @SuppressWarnings("unchecked")
    Map<Integer,EviLivePlugin.Held> holdPluginHeld=(Map<Integer,EviLivePlugin.Held>)get(holdPlugin,"heldForResale");
    check(holdPluginHeld.get(7).price==12,"Held.price itself must carry the same rounded average, independent of the query string");

    // activeSlotItemIds / skippedItemIds / refreshActiveSlotItemIds(): the plugin's own live,
    // session-only exclusions layered on top of the settings-driven query above -- never a config
    // setting, so an untouched config with nothing active or skipped still sends an empty query.
    Method refresh=EviLivePlugin.class.getDeclaredMethod("refreshActiveSlotItemIds");refresh.setAccessible(true);
    EviLivePlugin excludePlugin=new EviLivePlugin();
    set(excludePlugin,"config",new EviLiveConfig(){
      public boolean marginSafetyCushion(){return false;}
    });
    EviLivePlugin.Offer[] exSlots=(EviLivePlugin.Offer[])get(excludePlugin,"slots");
    exSlots[0]=new EviLivePlugin.Offer();exSlots[0].state="EMPTY";
    exSlots[1]=new EviLivePlugin.Offer();exSlots[1].state="BUYING";exSlots[1].itemId=7;exSlots[1].total=100;exSlots[1].filled=40; // 60 remaining
    exSlots[2]=new EviLivePlugin.Offer();exSlots[2].state="BOUGHT";exSlots[2].itemId=9; // terminal but not yet collected: still occupies the slot
    refresh.invoke(excludePlugin);
    check("exclude=7,9&slots=7:60".equals(suggestionQuery.invoke(excludePlugin)),"Active (non-EMPTY) GE slots must be auto-excluded, sorted ascending, even with an untouched config; slots= must additionally carry only the still-in-progress (non-terminal) ones with their own remaining quantity, since a terminal-but-uncollected slot has nothing left to cancel");

    @SuppressWarnings("unchecked")
    java.util.Set<Integer> skipped=(java.util.Set<Integer>)get(excludePlugin,"skippedItemIds");
    skipped.add(3);
    check("exclude=3,7,9&slots=7:60".equals(suggestionQuery.invoke(excludePlugin)),"A manually skipped item must merge with the auto-excluded active-slot items, sorted together, without affecting slots=");

    exSlots[1].state="EMPTY";exSlots[1].itemId=0; // collected: the slot is empty again
    refresh.invoke(excludePlugin);
    check("exclude=3,9".equals(suggestionQuery.invoke(excludePlugin)),"Collecting a slot back to EMPTY must drop it from both the exclude set and slots= on the next refresh, with no separate bookkeeping needed");

    resetMethod.invoke(excludePlugin);
    check("".equals(suggestionQuery.invoke(excludePlugin)),"reset() (login/profile/world-hop) must clear the manual skip set, the active-slot snapshot and the in-progress-offer snapshot for a fresh session");

    // activeOffers itself (refreshActiveSlotItemIds' other output, alongside activeSlotItemIds):
    // must carry the offer's own set price and buy/sell direction, and exclude a terminal offer
    // entirely -- not just from slots= but from the snapshot itself, since nothing else ever reads
    // activeOffers except through this same field.
    EviLivePlugin.Offer[] offerSlots=(EviLivePlugin.Offer[])get(excludePlugin,"slots");
    offerSlots[0]=new EviLivePlugin.Offer();offerSlots[0].state="BUYING";offerSlots[0].itemId=11;offerSlots[0].price=100;offerSlots[0].name="Buying item";offerSlots[0].total=500;offerSlots[0].filled=200; // 300 remaining
    offerSlots[1]=new EviLivePlugin.Offer();offerSlots[1].state="SELLING";offerSlots[1].itemId=12;offerSlots[1].price=200;offerSlots[1].name="Selling item";offerSlots[1].total=50;offerSlots[1].filled=0; // 50 remaining
    offerSlots[2]=new EviLivePlugin.Offer();offerSlots[2].state="SOLD";offerSlots[2].itemId=13;offerSlots[2].price=300;offerSlots[2].name="Terminal item"; // terminal: must not appear
    refresh.invoke(excludePlugin);
    @SuppressWarnings("unchecked")
    List<EviLivePlugin.ActiveOffer> offers=(List<EviLivePlugin.ActiveOffer>)get(excludePlugin,"activeOffers");
    check(offers.size()==2,"activeOffers must contain exactly the two non-terminal offers, not the terminal (SOLD) one");
    check(offers.get(0).itemId==11 && offers.get(0).price==100 && offers.get(0).buying && "Buying item".equals(offers.get(0).name) && offers.get(0).remaining==300,"A BUYING offer's snapshot must carry its own item, price, direction, name and remaining (total-filled) quantity");
    check(offers.get(1).itemId==12 && offers.get(1).price==200 && !offers.get(1).buying && offers.get(1).remaining==50,"A SELLING offer's snapshot must record buying=false and its own remaining quantity");
    // geOfferRows: the sidebar's full list -- unlike activeOffers, a terminal-but-uncollected offer stays in it.
    @SuppressWarnings("unchecked")
    List<EviLivePlugin.OfferRow> rows=(List<EviLivePlugin.OfferRow>)get(excludePlugin,"geOfferRows");
    check(rows.size()==3,"geOfferRows must list every occupied slot, including the terminal (SOLD) one");
    check(rows.get(0).buying && rows.get(0).filled==200 && rows.get(0).total==500 && "BUYING".equals(rows.get(0).state),"A row must carry direction, filled/total and raw state");
    check(!rows.get(2).buying && "SOLD".equals(rows.get(2).state) && "Terminal item".equals(rows.get(2).name),"The uncollected SOLD offer must be listed with its own state and name");
    resetMethod.invoke(excludePlugin);
    check(((List<?>)get(excludePlugin,"geOfferRows")).isEmpty(),"reset() must clear the sidebar offer list");
    // noSuggestionMessage: must not claim only reviewed flips were checked when market-wide is on,
    // and must name the margin check when it's on.
    check(EviLivePlugin.noSuggestionMessage(false,false).startsWith("No eligible reviewed flip"),"Market-wide off keeps the reviewed-flip wording");
    check(EviLivePlugin.noSuggestionMessage(true,false).contains("market-wide") && !EviLivePlugin.noSuggestionMessage(true,false).contains("price noise"),"Market-wide on must say market-wide picks were checked too");
    check(EviLivePlugin.noSuggestionMessage(true,true).contains("Require margin above price noise"),"With the margin check on, the message must name it");

    // offerDriftHint: pure, so tested directly rather than through a full poll round-trip.
    Method driftHint=EviLivePlugin.class.getDeclaredMethod("offerDriftHint",EviLivePlugin.ActiveOffer.class,Suggestion.class);
    driftHint.setAccessible(true);
    EviLivePlugin.ActiveOffer buyOffer=new EviLivePlugin.ActiveOffer(11,97,true,"Buying item",300);
    Suggestion priceFor11=new Suggestion();priceFor11.itemId=11;priceFor11.buyPrice=100;priceFor11.sellPrice=110;
    check(driftHint.invoke(null,buyOffer,priceFor11)==null,"A buy offer only 3% below the market's buyPrice must not be flagged (within OFFER_DRIFT_THRESHOLD)");
    EviLivePlugin.ActiveOffer buyOfferFar=new EviLivePlugin.ActiveOffer(11,80,true,"Buying item",300);
    String buyHint=(String)driftHint.invoke(null,buyOfferFar,priceFor11);
    check(buyHint!=null && buyHint.contains("Buying item") && buyHint.contains("cancelling"),"A buy offer priced well below the market must produce a cancel/relist hint naming the item");
    EviLivePlugin.ActiveOffer sellOffer=new EviLivePlugin.ActiveOffer(12,250,false,"Selling item",50);
    Suggestion priceFor12=new Suggestion();priceFor12.itemId=12;priceFor12.buyPrice=190;priceFor12.sellPrice=200;
    String sellHint=(String)driftHint.invoke(null,sellOffer,priceFor12);
    check(sellHint!=null && sellHint.contains("Selling item") && sellHint.contains("selling at the current price"),"A sell offer priced well above the market must produce a hint mentioning selling now as an alternative");
    check(driftHint.invoke(null,buyOffer,null)==null,"No live price for this item at all must never be flagged");
    EviLivePlugin.ActiveOffer zeroPriceOffer=new EviLivePlugin.ActiveOffer(11,0,true,"Buying item",300);
    check(driftHint.invoke(null,zeroPriceOffer,priceFor11)==null,"An offer with no set price yet must never be flagged");

    // offerFillHint: pure, reusing the bridge's rough volume-based estimate (see
    // estimateOfferFill in suggestions.mjs) -- wording must stay a "recent volume" observation,
    // never a fill guarantee or a claim about what THIS offer specifically will do.
    Method fillHintMethod=EviLivePlugin.class.getDeclaredMethod("offerFillHint",EviLivePlugin.ActiveOffer.class,EviLivePlugin.OfferFillEstimate.class);
    fillHintMethod.setAccessible(true);
    EviLivePlugin.OfferFillEstimate onPace=new EviLivePlugin.OfferFillEstimate();onPace.itemId=11;onPace.likelyToFillInTime=true;onPace.estimatedFillMinutes=6;
    check(fillHintMethod.invoke(null,buyOffer,onPace)==null,"An estimate marked likelyToFillInTime must never be flagged, whatever its minutes figure");
    check(fillHintMethod.invoke(null,buyOffer,null)==null,"No estimate at all (no target duration set, or no volume data) must never be flagged");
    EviLivePlugin.OfferFillEstimate slowMinutes=new EviLivePlugin.OfferFillEstimate();slowMinutes.itemId=11;slowMinutes.likelyToFillInTime=false;slowMinutes.estimatedFillMinutes=6000;
    String slowHint=(String)fillHintMethod.invoke(null,buyOffer,slowMinutes);
    check(slowHint!=null && slowHint.contains("Buying item") && slowHint.contains("buy offer") && slowHint.contains("300 remaining") && slowHint.contains("100 hours") && slowHint.contains("rough volume-based estimate") && slowHint.contains("not a guarantee"),"A slow estimate must name the item, direction and remaining quantity, phrase the minutes as hours, and stay explicitly hedged -- never a fill promise: "+slowHint);
    EviLivePlugin.OfferFillEstimate noVolumeAtAll=new EviLivePlugin.OfferFillEstimate();noVolumeAtAll.itemId=12;noVolumeAtAll.likelyToFillInTime=false;noVolumeAtAll.estimatedFillMinutes=-1;
    String noVolumeHint=(String)fillHintMethod.invoke(null,sellOffer,noVolumeAtAll);
    check(noVolumeHint!=null && noVolumeHint.contains("sell offer") && noVolumeHint.contains("almost no recent trading volume"),"The -1 sentinel (essentially no recent volume) must never be printed as a literal number");
    EviLivePlugin.OfferFillEstimate fastMinutes=new EviLivePlugin.OfferFillEstimate();fastMinutes.itemId=11;fastMinutes.likelyToFillInTime=false;fastMinutes.estimatedFillMinutes=45;
    String fastHint=(String)fillHintMethod.invoke(null,buyOffer,fastMinutes);
    check(fastHint!=null && fastHint.contains("45 minutes"),"An estimate under an hour must be phrased in minutes, not a fractional hour");

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

    // flagPersonalUse(): the sidebar's "Mark as personal use" button callback. The session-local
    // effects (skippedItemIds, clearing any live heldForResale entry, clearing suggestionCache) run
    // synchronously before the bridge POST/re-poll is handed to the (here, null -- startUp() never
    // ran) sender executor, so they're directly observable without needing a real executor.
    Method flagMethod=EviLivePlugin.class.getDeclaredMethod("flagPersonalUse");flagMethod.setAccessible(true);

    EviLivePlugin buyFlagPlugin=new EviLivePlugin();
    SuggestionCache buyFlagCache=new SuggestionCache();
    set(buyFlagPlugin,"suggestionCache",buyFlagCache);
    buyFlagCache.set(EviLiveSuggestionTest.suggestion(11,"buy",100,10,20));
    flagMethod.invoke(buyFlagPlugin); // a "buy" suggestion has nothing to mark
    check(buyFlagCache.get()!=null,"Personal use on a \"buy\" suggestion must not clear or otherwise touch the cache");
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> buyFlagSkips=(java.util.Set<Integer>)get(buyFlagPlugin,"skippedItemIds");
    check(buyFlagSkips.isEmpty(),"Personal use on a \"buy\" suggestion must not add anything to skippedItemIds");

    EviLivePlugin noBuyIdFlagPlugin=new EviLivePlugin();
    SuggestionCache noBuyIdCache=new SuggestionCache();
    set(noBuyIdFlagPlugin,"suggestionCache",noBuyIdCache);
    Suggestion sellNoBuyId=EviLiveSuggestionTest.suggestion(12,"sell",50,10,20); // buyId left null: no single buy identifiable
    noBuyIdCache.set(sellNoBuyId);
    flagMethod.invoke(noBuyIdFlagPlugin);
    check(noBuyIdCache.get()!=null,"Personal use on a sell suggestion with no identifiable buyId must not clear the cache");

    EviLivePlugin flagPlugin=new EviLivePlugin();
    SuggestionCache flagCache=new SuggestionCache();
    set(flagPlugin,"suggestionCache",flagCache);
    Suggestion heldSuggestion=EviLiveSuggestionTest.suggestion(13,"sell",25,10,20);heldSuggestion.buyId="buy-99";
    flagCache.set(heldSuggestion);
    Map<Integer,EviLivePlugin.Held> flagHeld=(Map<Integer,EviLivePlugin.Held>)get(flagPlugin,"heldForResale");
    flagHeld.put(13,new EviLivePlugin.Held(13,25,"Test item","buy-99",0));
    flagMethod.invoke(flagPlugin); // sender is null here (startUp() never ran): must not throw
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> flagSkips=(java.util.Set<Integer>)get(flagPlugin,"skippedItemIds");
    check(flagSkips.contains(13),"Marking personal use must add the item to skippedItemIds, same as a manual skip");
    check(flagCache.get()==null,"Marking personal use must clear the cache immediately so the panel doesn't show stale text");
    check(!flagHeld.containsKey(13),"Marking personal use must clear any live heldForResale entry for the item too");

    check("{\"buyId\":\"buy-99\",\"personal\":true}".equals(new Gson().toJson(new EviLivePlugin.PersonalUseRequest("buy-99"))),
      "PersonalUseRequest's JSON shape must match what the bridge's POST /api/suggestion/personal-use expects");

    // flagNotHeld(): the sidebar's "I don't have this anymore" button -- the in-game counterpart of
    // the scanner's "Still held" close buttons, for a reminder about stock that was used in-game or
    // sold while EVI wasn't watching. Same gating and same immediate session-local effects as
    // flagPersonalUse above; the difference is only which bridge endpoint it posts to.
    Method notHeldMethod=EviLivePlugin.class.getDeclaredMethod("flagNotHeld");notHeldMethod.setAccessible(true);

    EviLivePlugin buyNotHeldPlugin=new EviLivePlugin();
    SuggestionCache buyNotHeldCache=new SuggestionCache();
    set(buyNotHeldPlugin,"suggestionCache",buyNotHeldCache);
    buyNotHeldCache.set(EviLiveSuggestionTest.suggestion(21,"buy",100,10,20));
    notHeldMethod.invoke(buyNotHeldPlugin); // nothing held yet on a "buy" suggestion
    check(buyNotHeldCache.get()!=null,"\"I don't have this anymore\" on a buy suggestion must not clear the cache");
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> buyNotHeldSkips=(java.util.Set<Integer>)get(buyNotHeldPlugin,"skippedItemIds");
    check(buyNotHeldSkips.isEmpty(),"\"I don't have this anymore\" on a buy suggestion must not skip anything");

    EviLivePlugin notHeldPlugin=new EviLivePlugin();
    SuggestionCache notHeldCache=new SuggestionCache();
    set(notHeldPlugin,"suggestionCache",notHeldCache);
    Suggestion staleHolding=EviLiveSuggestionTest.suggestion(22,"sell",2241,2000,2200);staleHolding.buyId="buy-77";staleHolding.persisted=true;
    notHeldCache.set(staleHolding);
    Map<Integer,EviLivePlugin.Held> notHeldHeld=(Map<Integer,EviLivePlugin.Held>)get(notHeldPlugin,"heldForResale");
    notHeldHeld.put(22,new EviLivePlugin.Held(22,2241,"Blue dragon leather","buy-77",2162));
    notHeldMethod.invoke(notHeldPlugin); // sender is null here (startUp() never ran): must not throw
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> notHeldSkips=(java.util.Set<Integer>)get(notHeldPlugin,"skippedItemIds");
    check(notHeldSkips.contains(22),"\"I don't have this anymore\" must skip the item for the rest of the session too");
    check(notHeldCache.get()==null,"\"I don't have this anymore\" must clear the cache immediately");
    check(!notHeldHeld.containsKey(22),"\"I don't have this anymore\" must clear any live heldForResale entry for the item");

    check("{\"buyId\":\"buy-77\",\"reason\":\"sold-untracked\"}".equals(new Gson().toJson(new EviLivePlugin.NotHeldRequest("buy-77"))),
      "NotHeldRequest's JSON shape must match what the bridge's POST /api/suggestion/not-held expects");

    // verifyPersistedHolding(): a "persisted" suggestion (the bridge's restart-safe reconstruction
    // from journaled GE offers, see Suggestion.persisted's own doc) must be checked against the
    // player's actual current inventory before being trusted -- a stale reconstruction (e.g. an
    // old position that was, in reality, fully resold through separate sales the automatic
    // matcher didn't perfectly reconcile) must never sit there forever crowding out a real,
    // currently-held item behind it. This is exactly the failure reported live: an old, already
    // fully-resold blue dragon hide position crowding out the genuinely-still-held eternal boots
    // and zamorak chaps behind it. Exercised entirely through inventoryItemIds/
    // inventorySnapshotEstablished (never through client.getItemContainer() directly): a real
    // client.log confirmed a version of this method that called the Client API from here -- off
    // the background poll thread, not an event-subscriber callback -- threw "AssertionError: must
    // be called on client thread" on every single poll, so this now only ever reads a snapshot
    // that onGameTick refreshes safely from the client thread (see refreshInventoryItemIds).
    Method verify=EviLivePlugin.class.getDeclaredMethod("verifyPersistedHolding",Suggestion.class);verify.setAccessible(true);
    EviLivePlugin verifyPlugin=new EviLivePlugin();
    Suggestion held=EviLiveSuggestionTest.suggestion(5,"sell",2712,1000,1200);held.persisted=true;
    check(((Boolean)verify.invoke(verifyPlugin,held)).booleanValue(),"Before the inventory has ever been successfully snapshotted this session, must fail open (verified), never silently suppress a real reminder");
    set(verifyPlugin,"inventorySnapshotEstablished",true);
    set(verifyPlugin,"inventoryItemIds",java.util.Set.of(5));
    check(((Boolean)verify.invoke(verifyPlugin,held)).booleanValue(),"An item genuinely present in an established inventory snapshot must verify true");
    set(verifyPlugin,"inventoryItemIds",java.util.Set.of());
    check(!((Boolean)verify.invoke(verifyPlugin,held)).booleanValue(),"A confirmed, genuinely empty inventory (e.g. everything banked) must verify false -- it isn't actually held any more");
    set(verifyPlugin,"inventoryItemIds",java.util.Set.of(9));
    check(!((Boolean)verify.invoke(verifyPlugin,held)).booleanValue(),"An established snapshot that simply doesn't contain this item must verify false");

    // correctSellQuantityAgainstInventory(): caps a "sell" suggestion's quantity down to what's
    // actually in the inventory right now -- confirmed against a real report (a partially-filled,
    // later-cancelled buy order left the journal correctly believing 13 were bought and never
    // resold, but only 11 were actually still held).
    Method correct=EviLivePlugin.class.getDeclaredMethod("correctSellQuantityAgainstInventory",Suggestion.class);correct.setAccessible(true);
    EviLivePlugin correctPlugin=new EviLivePlugin();
    correct.invoke(correctPlugin,(Suggestion)null); // must never throw on a null suggestion (pollSuggestion's s can be null)

    Suggestion beforeSnapshot=EviLiveSuggestionTest.suggestion(30810,"sell",13,500000,550000);
    correct.invoke(correctPlugin,beforeSnapshot);
    check(beforeSnapshot.quantity==13,"Before the inventory has ever been successfully snapshotted this session, must fail open -- leave the quantity exactly as the bridge sent it");

    set(correctPlugin,"inventorySnapshotEstablished",true);
    set(correctPlugin,"inventoryQuantities",java.util.Map.of(30810,11));
    Suggestion buySuggestion=EviLiveSuggestionTest.suggestion(30810,"buy",13,500000,550000);
    correct.invoke(correctPlugin,buySuggestion);
    check(buySuggestion.quantity==13,"A \"buy\" suggestion's quantity means something entirely different (how many to acquire) and must never be touched");

    Suggestion overstated=EviLiveSuggestionTest.suggestion(30810,"sell",13,500000,550000);
    correct.invoke(correctPlugin,overstated);
    check(overstated.quantity==11,"An overstated sell quantity must be reduced to what's actually held");
    check(overstated.reasoning.contains("Reduced from 13 to the 11"),"The reduction must be explained in the reasoning text, the same way cash/duration limits already are");

    Suggestion exact=EviLiveSuggestionTest.suggestion(30810,"sell",11,500000,550000);
    correct.invoke(correctPlugin,exact);
    check(exact.quantity==11,"A quantity that already matches what's held must be left unchanged");

    Suggestion understated=EviLiveSuggestionTest.suggestion(30810,"sell",5,500000,550000);
    correct.invoke(correctPlugin,understated);
    check(understated.quantity==5,"A quantity LOWER than what's actually held must never be increased -- there could be older stock this suggestion isn't even about");

    Suggestion unknownItem=EviLiveSuggestionTest.suggestion(9999,"sell",7,500000,550000);
    correct.invoke(correctPlugin,unknownItem);
    check(unknownItem.quantity==7,"An item with no entry at all in the inventory snapshot (not what this correction is for) must be left alone, not zeroed out");

    // pollSuggestion(): a persisted suggestion the inventory check rejects must be auto-skipped
    // (same effect as the manual "Skip this suggestion" button above) and re-polled, never cached
    // or shown to the player.
    Method poll=EviLivePlugin.class.getDeclaredMethod("pollSuggestion",long.class);poll.setAccessible(true);
    EviLivePlugin pollPlugin=new EviLivePlugin();
    set(pollPlugin,"gson",new Gson());
    set(pollPlugin,"config",new EviLiveConfig(){ // suggestionQuery() (called at the top of every real pollSuggestion()) needs this
      public boolean marginSafetyCushion(){return false;}
    });
    set(pollPlugin,"running",true);set(pollPlugin,"lifecycle",1L);
    set(pollPlugin,"pluginKey","abcdef0123456789".repeat(4));
    set(pollPlugin,"inventorySnapshotEstablished",true);
    set(pollPlugin,"inventoryItemIds",java.util.Set.of()); // established, empty: item 5 is not actually held
    set(pollPlugin,"transport",new LocalTransport(){
      public int send(String key,String json)throws IOException {return 200;}
      public String get(String key,String query)throws IOException {
        return "{\"suggestion\":{\"itemId\":5,\"name\":\"Blue dragon hide\",\"action\":\"sell\",\"quantity\":2712,\"buyPrice\":1000,\"sellPrice\":1200,\"source\":\"holding\",\"reasoning\":\"stale\",\"persisted\":true}}";
      }
    });
    poll.invoke(pollPlugin,1L); // sender is null here (startUp() never ran): the auto-retry attempt must not throw
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> pollPluginSkips=(java.util.Set<Integer>)get(pollPlugin,"skippedItemIds");
    check(pollPluginSkips.contains(5),"A persisted suggestion for an item genuinely absent from the inventory must be auto-skipped, exactly like a manual skip");
    check(get(pollPlugin,"suggestionCache")==null,"The rejected phantom suggestion must never reach suggestionCache (left uninitialized/untouched in this harness)");

    // The same suggestion, but the item genuinely IS in the inventory: must be trusted and cached
    // normally, exactly as an unpersisted (live-observed) suggestion always has been.
    EviLivePlugin pollPlugin2=new EviLivePlugin();
    set(pollPlugin2,"gson",new Gson());
    set(pollPlugin2,"config",new EviLiveConfig(){
      public boolean marginSafetyCushion(){return false;}
    });
    set(pollPlugin2,"running",true);set(pollPlugin2,"lifecycle",1L);
    set(pollPlugin2,"pluginKey","abcdef0123456789".repeat(4));
    set(pollPlugin2,"suggestionCache",new SuggestionCache());
    set(pollPlugin2,"openItemPriceCache",new OpenItemPriceCache());
    set(pollPlugin2,"inventorySnapshotEstablished",true);
    set(pollPlugin2,"inventoryItemIds",java.util.Set.of(5)); // genuinely still held
    set(pollPlugin2,"transport",new LocalTransport(){
      public int send(String key,String json)throws IOException {return 200;}
      public String get(String key,String query)throws IOException {
        return "{\"suggestion\":{\"itemId\":5,\"name\":\"Blue dragon hide\",\"action\":\"sell\",\"quantity\":2712,\"buyPrice\":1000,\"sellPrice\":1200,\"source\":\"holding\",\"reasoning\":\"real\",\"persisted\":true}}";
      }
    });
    poll.invoke(pollPlugin2,1L);
    SuggestionCache resultCache=(SuggestionCache)get(pollPlugin2,"suggestionCache");
    check(resultCache.get()!=null && resultCache.get().itemId==5,"A persisted suggestion for an item genuinely still in the inventory must be cached and shown normally");
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> pollPlugin2Skips=(java.util.Set<Integer>)get(pollPlugin2,"skippedItemIds");
    check(pollPlugin2Skips.isEmpty(),"A verified-real persisted suggestion must not be skipped");

    // pollSuggestion() end-to-end: the bridge's remembered quantity (13, matching the journal) is
    // higher than what's actually in the inventory right now (11) -- the exact real-world shape of
    // the bug report this fix came from (a partially-filled buy order, cancelled, left a stale
    // remaining count). Must come through corrected, not as the bridge originally sent it.
    EviLivePlugin pollPlugin3=new EviLivePlugin();
    set(pollPlugin3,"gson",new Gson());
    set(pollPlugin3,"config",new EviLiveConfig(){
      public boolean marginSafetyCushion(){return false;}
    });
    set(pollPlugin3,"running",true);set(pollPlugin3,"lifecycle",1L);
    set(pollPlugin3,"pluginKey","abcdef0123456789".repeat(4));
    set(pollPlugin3,"suggestionCache",new SuggestionCache());
    set(pollPlugin3,"openItemPriceCache",new OpenItemPriceCache());
    set(pollPlugin3,"inventorySnapshotEstablished",true);
    set(pollPlugin3,"inventoryItemIds",java.util.Set.of(30810)); // genuinely still held, just not 13 of it
    set(pollPlugin3,"inventoryQuantities",java.util.Map.of(30810,11));
    set(pollPlugin3,"transport",new LocalTransport(){
      public int send(String key,String json)throws IOException {return 200;}
      public String get(String key,String query)throws IOException {
        return "{\"suggestion\":{\"itemId\":30810,\"name\":\"Contract of glyphic attenuation\",\"action\":\"sell\",\"quantity\":13,\"buyPrice\":500000,\"sellPrice\":550000,\"source\":\"holding\",\"reasoning\":\"You're holding 13 from an earlier buy\",\"persisted\":true}}";
      }
    });
    poll.invoke(pollPlugin3,1L);
    Suggestion corrected=((SuggestionCache)get(pollPlugin3,"suggestionCache")).get();
    check(corrected!=null && corrected.quantity==11,"pollSuggestion must apply the inventory correction before caching, not just verify presence");
    check(corrected.reasoning.contains("Reduced from 13 to the 11"),"The corrected suggestion's reasoning must explain the reduction");

    System.out.println("PASS: authentication failure, disconnect, exact retry, stale sender, disabled delivery, pairing replacement, overflow rebaseline, the suggestion-settings query builder (including target trade duration), the cash-stack query building, the open-offer-item query building, the held-for-resale query building (including the held item's own buy offerId), the active-slot/skip exclude query building, the skip-suggestion callback, the persisted-suggestion inventory verification, the poll-time auto-skip of a stale persisted suggestion, the personal-use button callback (no-op on a buy suggestion or a sell suggestion with no identifiable buyId; the session-local exclusion on an actual held item), the PersonalUseRequest JSON shape, the inventory-quantity/idle-inventory-suggestion query building, the sell-quantity correction against actual current inventory (including its end-to-end effect through pollSuggestion), the in-progress-offer slots= query building (item:remainingQty pairs, excluding terminal-but-uncollected offers), the activeOffers snapshot itself (price/direction/name/remaining quantity, terminal offers excluded), the offer-drift cancel/relist hint (buy offers below market, sell offers above market, within-threshold and missing-price cases all left unflagged), the offer fill-time hint (on-pace and no-estimate cases left unflagged, minutes phrased as hours past 60, the -1 no-volume sentinel never printed as a number, and the wording kept to a hedged volume observation rather than a fill guarantee), Held.price/holdBuyPrice (the real spent/filled average paid, correctly rounded, sent only when known, and never fabricated when no spent data was observed), the MinProfitTier preset tiers (AUTO left off the query exactly like the old free-form field's 0, each tier's own gp figure), and marginSafetyCushion (off by default under its new keyName, combining correctly with a profit tier when opted in, and explicit-off matching pre-existing behaviour), the sidebar's full GE offer list snapshot (uncollected offers included, cleared on reset), the no-suggestion message wording, and the members= world-type parameter");
  }
}
