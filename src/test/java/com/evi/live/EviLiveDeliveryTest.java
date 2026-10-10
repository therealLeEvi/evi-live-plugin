package com.evi.live;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** The plugin's query building, sidebar wording, button callbacks and poll path, with recorded answers only (no engine, no
 *  network). Since 4.0.0 there is no delivery to a companion app: the packet tests that gave this file its name went with it. */
public final class EviLiveDeliveryTest {
  static void set(Object target,String name,Object value)throws Exception {
    Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
  }
  static Object get(Object target,String name)throws Exception {
    Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
  }
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  /** A plugin whose inventory reports exactly these many coins (995) and platinum tokens (13204),
   *  so refreshCashStack can be exercised without a real client. */
  static EviLivePlugin pluginHolding(int coins,int plat) throws Exception {
    java.lang.reflect.InvocationHandler container=(pr,m,ar)->
      m.getName().equals("count") ? (Integer.valueOf(995).equals(ar[0]) ? coins : Integer.valueOf(13204).equals(ar[0]) ? plat : 0) : null;
    java.lang.reflect.InvocationHandler cl=(pr,m,ar)->m.getName().equals("getItemContainer")
      ?java.lang.reflect.Proxy.newProxyInstance(net.runelite.api.ItemContainer.class.getClassLoader(),new Class[]{net.runelite.api.ItemContainer.class},container):null;
    EviLivePlugin p=new EviLivePlugin();
    set(p,"client",java.lang.reflect.Proxy.newProxyInstance(net.runelite.api.Client.class.getClassLoader(),new Class[]{net.runelite.api.Client.class},cl));
    return p;
  }
  /** Drives the REAL pollSuggestion() against a bridge that answers 200 with no suggestion, and
   *  returns what the sidebar's prose area then shows. The message tests below call the pure
   *  noSuggestionMessage directly, so on their own they would stay green if the poll path passed it
   *  the wrong source, toggle or slot counts -- the verifier proved that by sabotage (a null source and
   *  a hard-coded true both passed). This is the wiring check: config and slot fields go in, the
   *  panel's text comes out, and nothing in between is restated. */
  static String diagnosisFromPoll(boolean market,boolean cushion,SuggestionSource source,int free,int collectable)throws Exception {
    Method poll=EviLivePlugin.class.getDeclaredMethod("pollSuggestion",long.class);poll.setAccessible(true);
    EviLivePlugin p=new EviLivePlugin();
    set(p,"gson",new Gson());
    set(p,"config",new EviLiveConfig(){
      public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}
      public boolean includeMarketWide(){return market;}
      public boolean marginSafetyCushion(){return cushion;}
      public SuggestionSource suggestionSource(){return source;}
    });
    set(p,"running",true);set(p,"lifecycle",1L);
    set(p,"suggestionCache",new SuggestionCache());
    set(p,"openItemPriceCache",new OpenItemPriceCache());
    set(p,"freeSlots",free);set(p,"collectableSlots",collectable);
    EviLivePanel panel=new EviLivePanel(()->{},()->{},()->{},()->{},()->{});
    set(p,"panel",panel);
    set(p,"answers",(AnswerSource)query->"{\"suggestion\":null}");
    poll.invoke(p,1L);
    javax.swing.SwingUtilities.invokeAndWait(()->{}); // the panel writes on the EDT
    return ((javax.swing.JTextArea)get(panel,"suggestion")).getText();
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] args)throws Exception {
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
    set(defaults,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}});
    check("".equals(suggestionQuery.invoke(defaults)),"With the trade-size cap and market-wide suggestions turned off, an otherwise-untouched config must send an empty query");
    Method cushionItem=EviLiveConfig.class.getDeclaredMethod("marginSafetyCushion");
    check("requireMarginAboveNoise".equals(cushionItem.getAnnotation(net.runelite.client.config.ConfigItem.class).keyName()),"marginSafetyCushion must use its new keyName, so a 'true' RuneLite stored under the old on-by-default key is not picked up");

    EviLivePlugin tuned=new EviLivePlugin();
    set(tuned,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T500K;}
      public boolean marginSafetyCushion(){return false;}
      public String itemBlocklist(){return "4151, 995";}
      public RiskLevel riskLevelV2(){return RiskLevel.HIGH;}
      public boolean includeMarketWide(){return true;}
      public TradePace tradePace(){return TradePace.FAST;}
    });
    // The risk levels are HIDDEN (RiskLevel.SHOWN false, 7 Oct 2026): a High stored under riskLevelV2 must NOT reach the
    // request, so the other four settings appear exactly as before and risk= does not.
    check("minProfit=500000&blocklist=4151,995&includeMarket=1&duration=120".equals(suggestionQuery.invoke(tuned)),"While the risk levels are hidden a stored High must not be sent; the other four settings appear as before, includeMarket then duration last: "+suggestionQuery.invoke(tuned));
    // The dormant path, kept whole for when the levels return: with the switch on, all five appear.
    set(tuned,"riskLevelsShown",true);
    check("minProfit=500000&blocklist=4151,995&risk=high&includeMarket=1&duration=120".equals(suggestionQuery.invoke(tuned)),"With the risk levels shown, all five settings must appear in the query string when set, includeMarket then duration last (cushion disabled here so this test stays about exactly those five)");

    // MinProfitTier: replaces the old free-form gp field with plain preset tiers, AUTO meaning
    // "any profit, no floor" -- must behave exactly like the old default of 0 (left off the query
    // entirely), and each tier must send its own documented gp figure.
    EviLivePlugin autoTier=new EviLivePlugin();
    set(autoTier,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public MinProfitTier minProfitThreshold(){return MinProfitTier.AUTO;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(autoTier)),"AUTO (the default tier) must be left off the query entirely, same as the old free-form field's 0");

    EviLivePlugin t100k=new EviLivePlugin();
    set(t100k,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T100K;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("minProfit=100000".equals(suggestionQuery.invoke(t100k)),"T100K alone, with no leading '&', when it's the only tuned setting");

    EviLivePlugin t200k=new EviLivePlugin();
    set(t200k,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T200K;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("minProfit=200000".equals(suggestionQuery.invoke(t200k)),"T200K's gp figure");

    EviLivePlugin t1m=new EviLivePlugin();
    set(t1m,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T1M;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("minProfit=1000000".equals(suggestionQuery.invoke(t1m)),"T1M's gp figure");

    EviLivePlugin t2m=new EviLivePlugin();
    set(t2m,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T2M;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("minProfit=2000000".equals(suggestionQuery.invoke(t2m)),"T2M's gp figure");

    // marginSafetyCushion: off by default (see the `defaults` test above); explicitly disabling it
    // must leave the query exactly as before this setting existed, and turning it on must combine
    // with a profit tier in the documented order (minProfit=, then cushion=1, then everything else).
    EviLivePlugin cushionExplicitOff=new EviLivePlugin();
    set(cushionExplicitOff,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(cushionExplicitOff)),"Explicit marginSafetyCushion=false must be left off the query, matching pre-existing behaviour");

    EviLivePlugin cushionWithTier=new EviLivePlugin();
    set(cushionWithTier,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public MinProfitTier minProfitThreshold(){return MinProfitTier.T500K;}
      public boolean marginSafetyCushion(){return true;}
    });
    check("minProfit=500000&cushion=1".equals(suggestionQuery.invoke(cushionWithTier)),"A profit tier plus an opted-in cushion must both appear, minProfit= before cushion=1");

    EviLivePlugin noDurationPreference=new EviLivePlugin();
    set(noDurationPreference,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public TradePace tradePace(){return TradePace.NONE;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(noDurationPreference)),"Explicit NONE (no preference) must be left off the query, matching the default");

    EviLivePlugin durationOnly=new EviLivePlugin();
    set(durationOnly,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public TradePace tradePace(){return TradePace.MEDIUM;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("duration=360".equals(suggestionQuery.invoke(durationOnly)),"duration alone, with no leading '&', when it's the only tuned setting");
    // Every pace sends the minutes measured to actually complete a round trip: see TradePace's own
    // doc and the replay behind it. Nothing under two hours is offered, because nothing under two
    // hours ever finished one.
    int[] paceMinutes={120,360,720,2880};TradePace[] paces={TradePace.FAST,TradePace.MEDIUM,TradePace.OVERNIGHT,TradePace.SLOW};
    for(int i=0;i<paces.length;i++){
      final TradePace p=paces[i];
      EviLivePlugin longTrade=new EviLivePlugin();
      set(longTrade,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}public TradePace tradePace(){return p;}});
      check(("duration="+paceMinutes[i]).equals(suggestionQuery.invoke(longTrade)),"Each trade pace must send its own minutes: "+p);
      check(p.minutes()>=120,"No pace may promise less than two hours, which never completed: "+p);
    }
    // Where a suggestion may come from. The default is what EVI always did, so it sends nothing.
    for(SuggestionSource src:SuggestionSource.values()){
      final SuggestionSource v=src;
      EviLivePlugin p=new EviLivePlugin();
      set(p,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
        public SuggestionSource suggestionSource(){return v;}
        public boolean marginSafetyCushion(){return false;}
      });
      String q=(String)suggestionQuery.invoke(p);
      if(v==SuggestionSource.HISTORY_FIRST)check("".equals(q),"The default source must add nothing to the query: "+q);
      else check(("source="+v.param()).equals(q),"Each source must send its own value: "+v+" -> "+q);
    }
    // The old setting is hidden and must no longer reach the query, whatever it still holds.
    EviLivePlugin staleDuration=new EviLivePlugin();
    set(staleDuration,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public TradeDuration tradeDuration(){return TradeDuration.FIVE;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(staleDuration)),"A value left in the retired duration setting must have no effect at all");
    // AUTO leaves the floor to the bridge (which applies a small one); NONE asks for none at all.
    EviLivePlugin noFloor=new EviLivePlugin();
    set(noFloor,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public MinProfitTier minProfitThreshold(){return MinProfitTier.NONE;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("minProfit=1".equals(suggestionQuery.invoke(noFloor)),"\"No minimum at all\" must send the smallest positive floor, so the bridge leaves it alone");
    check(MinProfitTier.AUTO.gp()==0,"AUTO sends nothing and lets the bridge choose");
    // tradingProfile is RETIRED (6 Oct 2026): hidden, and never sent whatever is stored. Most
    // existing installs hold a stored STARTER (the old default, which RuneLite wrote into their
    // profile), so the case that matters is a STORED STARTER, asserted as whole query VALUES.
    EviLivePlugin storedStarter=new EviLivePlugin();
    set(storedStarter,"config",new EviLiveConfig(){
      public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}
      public boolean includeMarketWide(){return false;}
      public TradingProfile tradingProfile(){return TradingProfile.STARTER;}
    });
    check("".equals(suggestionQuery.invoke(storedStarter)),"A stored Starter profile must send nothing at all -- no profile=starter: "+suggestionQuery.invoke(storedStarter));
    EviLivePlugin storedStarterDefaults=new EviLivePlugin();
    set(storedStarterDefaults,"config",new EviLiveConfig(){
      public TradingProfile tradingProfile(){return TradingProfile.STARTER;}
    });
    check("includeMarket=1&stackShare=25".equals(suggestionQuery.invoke(storedStarterDefaults)),"An otherwise-untouched config with a stored Starter must send exactly the new default query: "+suggestionQuery.invoke(storedStarterDefaults));
    Method profileGetter=EviLiveConfig.class.getDeclaredMethod("tradingProfile");
    net.runelite.client.config.ConfigItem profileItem=profileGetter.getAnnotation(net.runelite.client.config.ConfigItem.class);
    check("tradingProfile".equals(profileItem.keyName()),"tradingProfile must keep its keyName, so a stored value still loads");
    check(profileItem.hidden(),"tradingProfile must be hidden: the Starter profile is gone from the settings panel");
    check(profileGetter.getReturnType()==TradingProfile.class,"tradingProfile must keep its enum return type, so a stored STARTER is still a valid value for the loader");
    // Market-wide: ON by default under a NEW keyName (6 Oct 2026), so existing installs -- which all
    // hold a stored "false" under the old key, written by RuneLite on first load -- get it on too.
    // The old item stays, hidden, under its own keyName and type, so a stored value still loads.
    check(new EviLiveConfig(){}.includeMarketWide()==true,"Market-wide suggestions must be ON by default");
    Method marketGetter=EviLiveConfig.class.getDeclaredMethod("includeMarketWide");
    net.runelite.client.config.ConfigItem marketItem=marketGetter.getAnnotation(net.runelite.client.config.ConfigItem.class);
    check("includeMarketWide".equals(marketItem.keyName()),"The live market-wide setting must be on the NEW keyName includeMarketWide: "+marketItem.keyName());
    check(!marketItem.hidden(),"The live market-wide setting must be visible");
    check("Include market-wide suggestions".equals(marketItem.name()),"The visible name must not change, so the no-suggestion messages that quote it stay true: "+marketItem.name());
    check(marketGetter.getReturnType()==boolean.class,"includeMarketWide must be a boolean");
    Method oldMarketGetter=EviLiveConfig.class.getDeclaredMethod("includeMarketSuggestions");
    net.runelite.client.config.ConfigItem oldMarketItem=oldMarketGetter.getAnnotation(net.runelite.client.config.ConfigItem.class);
    check("includeMarketSuggestions".equals(oldMarketItem.keyName()),"The retired market-wide item must keep its keyName, so a stored value still loads");
    check(oldMarketItem.hidden(),"The retired market-wide item must be hidden");
    check(oldMarketGetter.getReturnType()==boolean.class,"The retired market-wide item must stay a boolean");
    // maxTradeShare: on by default at 25% (see MaxTradeShare's own doc for the backtest), so an
    // untouched config sends stackShare=25 -- and, since 6 Oct 2026, includeMarket=1 and no profile.
    // No limit restores the old, uncapped sizing.
    EviLivePlugin shareDefault=new EviLivePlugin();
    set(shareDefault,"config",new EviLiveConfig(){});
    check("includeMarket=1&stackShare=25".equals(suggestionQuery.invoke(shareDefault)),"An untouched config must ask for market-wide suggestions, send no profile, and cap one trade at 25% of the cash stack: "+suggestionQuery.invoke(shareDefault));
    EviLivePlugin shareOff=new EviLivePlugin();
    set(shareOff,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}});
    check("".equals(suggestionQuery.invoke(shareOff)),"No limit must be left off the query entirely");
    for(MaxTradeShare s:new MaxTradeShare[]{MaxTradeShare.TENTH,MaxTradeShare.THIRD,MaxTradeShare.HALF}){
      final MaxTradeShare chosen=s;
      EviLivePlugin p=new EviLivePlugin();
      set(p,"config",new EviLiveConfig(){
        public MaxTradeShare maxTradeShare(){return chosen;}
        public boolean includeMarketWide(){return false;}
      });
      check(("stackShare="+chosen.percent()).equals(suggestionQuery.invoke(p)),"Each share option must send its own percentage: "+chosen);
    }
    // focus=: the plugin's own Suggestion focus. All items (the default since 4.0.0) sends nothing -- the engine's default
    // with no focus is any item, and the untouched-config check above proves the query is unchanged -- and Gear and Bulk
    // send their own value.
    for(SuggestionFocus chosenFocus:new SuggestionFocus[]{SuggestionFocus.ALL_ITEMS,SuggestionFocus.GEAR,SuggestionFocus.BULK}){
      final SuggestionFocus pick=chosenFocus;
      EviLivePlugin p=new EviLivePlugin();
      set(p,"config",new EviLiveConfig(){
        public SuggestionFocus suggestionFocus(){return pick;}
        public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}
        public boolean includeMarketWide(){return false;}
      });
      String expect=pick==SuggestionFocus.ALL_ITEMS?"":"focus="+pick.param();
      check(expect.equals(suggestionQuery.invoke(p)),"Each focus must send its own value (All items nothing): "+pick+" -> "+suggestionQuery.invoke(p));
    }
    check(SuggestionFocus.values().length==3 && SuggestionFocus.ALL_ITEMS.param()==null && new EviLiveConfig(){}.suggestionFocus()==SuggestionFocus.ALL_ITEMS,
      "4.0.0: \"Same as scanner\" is gone; All items is the default and sends no focus (the engine's own default, any item)");
    check(java.util.Arrays.stream(SuggestionFocus.values()).noneMatch(v->v.toString().toLowerCase().contains("scanner")),"No focus label may name the scanner");
    check("suggestionFocus".equals(EviLiveConfig.class.getDeclaredMethod("suggestionFocus").getAnnotation(net.runelite.client.config.ConfigItem.class).keyName()),
      "suggestionFocus keeps its keyName, so a stored Gear or Bulk is kept");
    // members=: which kind of world the player is on, so the bridge never suggests a members-only
    // item on a free-to-play world. Unknown (not yet logged in) must send nothing at all.
    EviLivePlugin worldPlugin=new EviLivePlugin();
    set(worldPlugin,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}});
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
    set(mediumRisk,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public RiskLevel riskLevelV2(){return RiskLevel.MEDIUM;}
      public boolean marginSafetyCushion(){return false;}
    });
    // Low is now the default on both sides, so Medium must be sent explicitly -- otherwise choosing
    // Medium would silently get the bridge's Low default and the setting would lie.
    check("".equals(suggestionQuery.invoke(mediumRisk)),"While the risk levels are hidden a stored Medium must send nothing (Low, the bridge's default): "+suggestionQuery.invoke(mediumRisk));
    set(mediumRisk,"riskLevelsShown",true);
    check("risk=medium".equals(suggestionQuery.invoke(mediumRisk)),"With the risk levels shown, Medium must be sent explicitly now that Low is the default");
    EviLivePlugin lowRisk=new EviLivePlugin();
    set(lowRisk,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean marginSafetyCushion(){return false;}
    });
    check(EviLiveConfig.class.getMethod("riskLevelV2").invoke(new EviLiveConfig(){})==RiskLevel.LOW,"Low is the default risk level");
    check("".equals(suggestionQuery.invoke(lowRisk)),"The default (Low) is left off the query, matching the bridge's own default");

    EviLivePlugin marketOnly=new EviLivePlugin();
    set(marketOnly,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}
      public boolean includeMarketWide(){return true;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("includeMarket=1".equals(suggestionQuery.invoke(marketOnly)),"includeMarket=1 alone, with no leading '&', when it's the only tuned setting");

    EviLivePlugin marketOff=new EviLivePlugin();
    set(marketOff,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}
      public boolean includeMarketWide(){return false;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(marketOff)),"Explicit includeMarketWide=false must be left off the query entirely");

    // The Exit-risk check (keyName exitRiskCheck since 4.0.0) and its policy: OFF (the default) must send neither param, even
    // if the policy alone is non-default -- there's nothing to have a policy about without a horizon. ~6 hours always sends its
    // policy too, even the default (warn), so the query stays self-explanatory either way.
    EviLivePlugin forecastOff=new EviLivePlugin();
    set(forecastOff,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public ForecastHorizon exitRiskCheck(){return ForecastHorizon.OFF;}
      public ForecastPolicy forecastPolicy(){return ForecastPolicy.SKIP;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(forecastOff)),"Explicit OFF must be left off the query even with a non-default policy, matching the default");
    check(new EviLiveConfig(){}.exitRiskCheck()==ForecastHorizon.OFF,"The Exit-risk check defaults to Off");

    EviLivePlugin forecastSixHour=new EviLivePlugin();
    set(forecastSixHour,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public ForecastHorizon exitRiskCheck(){return ForecastHorizon.SIX_HOUR;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("forecast=6h&onForecast=warn".equals(suggestionQuery.invoke(forecastSixHour)),"An enabled check must always send its policy too, even when the policy itself is left at its default (warn)");

    EviLivePlugin forecastWithDuration=new EviLivePlugin();
    set(forecastWithDuration,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public TradePace tradePace(){return TradePace.FAST;}
      public ForecastHorizon exitRiskCheck(){return ForecastHorizon.SIX_HOUR;}
      public ForecastPolicy forecastPolicy(){return ForecastPolicy.SKIP;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("duration=120&forecast=6h&onForecast=skip".equals(suggestionQuery.invoke(forecastWithDuration)),"forecast=/onForecast= must follow duration= in the query string, joined with '&' like every other setting here");
    // THE NEW KEYNAME (the maintainer's decision, 8 Oct 2026): ~1 hour and Overnight are gone, so the setting moved to a key no
    // stored value of theirs can be under. Only Off and ~6 hours remain, and nothing the plugin sends is 1h or overnight.
    net.runelite.client.config.ConfigItem exitItem=EviLiveConfig.class.getDeclaredMethod("exitRiskCheck").getAnnotation(net.runelite.client.config.ConfigItem.class);
    check("exitRiskCheck".equals(exitItem.keyName()) && "Exit-risk check".equals(exitItem.name()) && !exitItem.hidden(),"The Exit-risk check lives under the NEW keyName exitRiskCheck: "+exitItem.keyName());
    check(java.util.Arrays.equals(ForecastHorizon.values(),new ForecastHorizon[]{ForecastHorizon.OFF,ForecastHorizon.SIX_HOUR}),"Only Off and ~6 hours remain: "+java.util.Arrays.toString(ForecastHorizon.values()));
    check("~6 hours".equals(ForecastHorizon.SIX_HOUR.toString()) && "Off".equals(ForecastHorizon.OFF.toString()) && ForecastHorizon.OFF.param()==null,"The two labels, and Off sends nothing");
    for(Method m:EviLiveConfig.class.getDeclaredMethods()) {
      net.runelite.client.config.ConfigItem it=m.getAnnotation(net.runelite.client.config.ConfigItem.class);
      check(it==null || !"forecastHorizon".equals(it.keyName()),"The old forecastHorizon key must not be declared any more (its stored 1h/overnight would no longer unmarshal): "+m.getName());
    }

    // includeInventory/inventory: opt-in (EviLiveConfig.suggestIdleInventory()) plus the client-thread
    // inventoryQuantities snapshot (see refreshInventoryItemIds/its own field doc) -- both must be
    // true/non-empty before anything is sent, and item IDs must appear sorted for a deterministic
    // query string, matching the existing `exclude=` convention.
    Map<Integer,Integer> snapshot=new java.util.HashMap<>();
    snapshot.put(11232,15);snapshot.put(995,50000000);snapshot.put(4151,1);
    EviLivePlugin inventoryOff=new EviLivePlugin();
    set(inventoryOff,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean marginSafetyCushion(){return false;}
    });
    set(inventoryOff,"inventoryQuantities",snapshot);
    check("".equals(suggestionQuery.invoke(inventoryOff)),"suggestIdleInventory defaults to off, so a populated inventory snapshot must still be left off the query");

    EviLivePlugin inventoryOnEmpty=new EviLivePlugin();
    set(inventoryOnEmpty,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean suggestIdleInventory(){return true;}
      public boolean marginSafetyCushion(){return false;}
    });
    check("".equals(suggestionQuery.invoke(inventoryOnEmpty)),"suggestIdleInventory=true with an empty inventory snapshot (the default) must still produce an empty query");

    EviLivePlugin inventoryOn=new EviLivePlugin();
    set(inventoryOn,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean suggestIdleInventory(){return true;}
      public boolean marginSafetyCushion(){return false;}
    });
    set(inventoryOn,"inventoryQuantities",snapshot);
    check("includeInventory=1&inventory=995:50000000,4151:1,11232:15".equals(suggestionQuery.invoke(inventoryOn)),"Inventory items must be sent sorted by item ID for a deterministic query string");

    // ...but never from inside an instance. Reported from inside a raid on 28 Sept 2026: EVI
    // was offering to sell their supplies and raid gear, because to this tier an inventory is just an
    // inventory. In a raid it is a loadout, and the Grand Exchange cannot be reached from in there
    // anyway, so the suggestion could not be acted on even if it had been right.
    java.lang.reflect.InvocationHandler worldView=(pr,m,ar)->m.getName().equals("isInstance")?Boolean.TRUE:null;
    java.lang.reflect.InvocationHandler inRaid=(pr,m,ar)->m.getName().equals("getTopLevelWorldView")
      ?java.lang.reflect.Proxy.newProxyInstance(net.runelite.api.WorldView.class.getClassLoader(),new Class[]{net.runelite.api.WorldView.class},worldView):null;
    EviLivePlugin inventoryInRaid=new EviLivePlugin();
    set(inventoryInRaid,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean suggestIdleInventory(){return true;}
      public boolean marginSafetyCushion(){return false;}
    });
    set(inventoryInRaid,"inventoryQuantities",snapshot);
    set(inventoryInRaid,"client",java.lang.reflect.Proxy.newProxyInstance(net.runelite.api.Client.class.getClassLoader(),new Class[]{net.runelite.api.Client.class},inRaid));
    // Two steps, and both are checked, because the bug this replaced was entirely in the first one:
    // the flag is CAPTURED on the client thread (refreshInInstance, called from onGameTick) and only
    // READ by suggestionQuery, which runs on the background sender. Testing the read alone would
    // have passed just as happily while the client was being touched from the wrong thread.
    java.lang.reflect.Method refreshInInstance=EviLivePlugin.class.getDeclaredMethod("refreshInInstance");
    refreshInInstance.setAccessible(true);
    refreshInInstance.invoke(inventoryInRaid);
    check(Boolean.TRUE.equals(get(inventoryInRaid,"inInstance")),"refreshInInstance must capture the world view's own answer");
    check("".equals(suggestionQuery.invoke(inventoryInRaid)),"Inside an instance the idle-inventory tier must send nothing: a raid loadout is not idle stock");
    // And suggestionQuery must read the captured flag, never the client: blanking the client here
    // would throw if anything on this path still reached for it.
    set(inventoryInRaid,"client",null);
    check("".equals(suggestionQuery.invoke(inventoryInRaid)),"suggestionQuery must use the captured flag, not touch the client off the client thread");

    // Outside one it is unchanged, so the fix suppresses the raid case and nothing else.
    java.lang.reflect.InvocationHandler openWorld=(pr,m,ar)->m.getName().equals("getTopLevelWorldView")
      ?java.lang.reflect.Proxy.newProxyInstance(net.runelite.api.WorldView.class.getClassLoader(),new Class[]{net.runelite.api.WorldView.class},
        (p2,m2,a2)->m2.getName().equals("isInstance")?Boolean.FALSE:null):null;
    EviLivePlugin inventoryOverworld=new EviLivePlugin();
    set(inventoryOverworld,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean suggestIdleInventory(){return true;}
      public boolean marginSafetyCushion(){return false;}
    });
    set(inventoryOverworld,"inventoryQuantities",snapshot);
    set(inventoryOverworld,"client",java.lang.reflect.Proxy.newProxyInstance(net.runelite.api.Client.class.getClassLoader(),new Class[]{net.runelite.api.Client.class},openWorld));
    refreshInInstance.invoke(inventoryOverworld);
    check(Boolean.FALSE.equals(get(inventoryOverworld,"inInstance")),"The overworld must be captured as no instance");
    check("includeInventory=1&inventory=995:50000000,4151:1,11232:15".equals(suggestionQuery.invoke(inventoryOverworld)),"Outside an instance the tier must behave exactly as before");

    // An unknown scene fails OPEN. A null client here stands in for any failure reading the world
    // view: the tier keeps working rather than silently switching off a feature the player turned on.
    check("includeInventory=1&inventory=995:50000000,4151:1,11232:15".equals(suggestionQuery.invoke(inventoryOn)),"An unreadable world view must leave the tier working, not quietly disable it");
    // The capture itself must fail open too, and must not throw into onGameTick: a null client is
    // the stand-in for any read that goes wrong mid scene-swap.
    EviLivePlugin unreadable=new EviLivePlugin();
    set(unreadable,"inInstance",Boolean.TRUE);
    set(unreadable,"client",null);
    refreshInInstance.invoke(unreadable);
    check(Boolean.FALSE.equals(get(unreadable,"inInstance")),"A failed world-view read must clear the flag, never leave a stale instance set");

    // refreshCashStack(): coins PLUS platinum tokens, as of Jagex's "Beyond Max Cash" update on
    // 30 Sept 2026. The Grand Exchange now settles an offer from both currencies together, so
    // counting coins alone -- correct the day before -- understates anyone holding wealth as tokens
    // and would quietly size their trades as though the tokens were not there.
    Method refreshCash=EviLivePlugin.class.getDeclaredMethod("refreshCashStack");
    refreshCash.setAccessible(true);
    EviLivePlugin coinsOnly=pluginHolding(500_000,0);
    refreshCash.invoke(coinsOnly);
    check(Long.valueOf(500_000L).equals(get(coinsOnly,"cashStack")),"Coins alone are counted as before: "+get(coinsOnly,"cashStack"));
    EviLivePlugin mixed=pluginHolding(1_500,7);
    refreshCash.invoke(mixed);
    check(Long.valueOf(8_500L).equals(get(mixed,"cashStack")),"Seven platinum tokens are 7,000 gp on top of 1,500 coins: "+get(mixed,"cashStack"));
    // The reason cashStack had to become a long: each stack is still capped at 2,147,483,647, but
    // both together reach about 2.149 TRILLION, which an int cannot hold.
    EviLivePlugin maxed=pluginHolding(2_147_483_647,2_147_483_647);
    refreshCash.invoke(maxed);
    check(Long.valueOf(2_149_631_130_647L).equals(get(maxed,"cashStack")),
      "A full coin stack plus a full token stack must not overflow: "+get(maxed,"cashStack"));
    EviLivePlugin noInventory=new EviLivePlugin();
    set(noInventory,"client",null);
    set(noInventory,"cashStack",123L);
    refreshCash.invoke(noInventory);
    check(Long.valueOf(-1L).equals(get(noInventory,"cashStack")),"An unreadable inventory means UNKNOWN (-1), never zero -- zero would mean 'afford nothing'");

    // account: the same identifier already sent with every ingest packet, not a config setting --
    // left off the query entirely before the first login this process has seen (null, the
    // default), and included once known so the bridge can scope its cross-restart open-position
    // fallback (pickPersistentOpenPosition, bridge/suggestions.mjs) to this account specifically.
    EviLivePlugin accountPlugin=new EviLivePlugin();
    set(accountPlugin,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
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
    set(cashPlugin,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
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
    set(openItemPlugin,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
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
    set(holdPlugin,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
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
    // offer's set/max price -- this is the fix for the in-game failure report of EVI suggesting
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
    set(excludePlugin,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
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
    // noSuggestionMessage: asserted as WHOLE STRINGS, for every (source, includeMarket) pair. A
    // contains() on one phrase is what let the 6 Oct order bug through: Market only with the toggle
    // OFF said "No market-wide pick passes your settings" about a market check that never ran.
    final String NOTHING_SEARCHED="Market-wide suggestions are switched off and \"Suggest from\" is set to Market only, so EVI"
      +" has nowhere to look for a trade. Turn on \"Include market-wide suggestions\" to get suggestions.";
    final String HISTORY_ONLY="Nothing in your own trade history passes your settings right now, and market-wide suggestions"
      +" are switched off. Turn on \"Include market-wide suggestions\" to search the whole Grand Exchange.";
    final String MARKET_ONLY_NONE="No market-wide pick passes your settings right now. \"Suggest from\" is set to Market only,"
      +" so your own trade history was not considered -- switch it to include that too.";
    final String BOTH_NONE="Nothing passes your settings right now -- neither your own trade history nor a market-wide pick.";
    final String CUSHION=" \"Require margin above price noise\" is on, and it currently skips most candidates -- turn it off to see them.";
    final String GE_FULL="Every Grand Exchange slot is in use, so there is nowhere to place another offer."
      +" EVI will suggest again as soon as one frees up.";
    SuggestionSource[] sources={null,SuggestionSource.HISTORY_FIRST,SuggestionSource.BEST_OF_BOTH,SuggestionSource.MARKET_ONLY};
    String[] whenOff={HISTORY_ONLY,HISTORY_ONLY,HISTORY_ONLY,NOTHING_SEARCHED};
    String[] whenOn={BOTH_NONE,BOTH_NONE,BOTH_NONE,MARKET_ONLY_NONE};
    for(int i=0;i<sources.length;i++) {
      String off=EviLivePlugin.noSuggestionMessage(false,false,1,0,sources[i]);
      String on=EviLivePlugin.noSuggestionMessage(true,false,1,0,sources[i]);
      check(whenOff[i].equals(off),"Market-wide OFF, source "+sources[i]+": wrong message: "+off);
      check(whenOn[i].equals(on),"Market-wide ON, source "+sources[i]+": wrong message: "+on);
      // The margin check is named only when something was actually searched.
      check((i==3?whenOff[i]:whenOff[i]+CUSHION).equals(EviLivePlugin.noSuggestionMessage(false,true,1,0,sources[i])),
        "Market-wide OFF with the margin check on, source "+sources[i]+": "+EviLivePlugin.noSuggestionMessage(false,true,1,0,sources[i]));
      check((whenOn[i]+CUSHION).equals(EviLivePlugin.noSuggestionMessage(true,true,1,0,sources[i])),
        "Market-wide ON with the margin check on, source "+sources[i]+": "+EviLivePlugin.noSuggestionMessage(true,true,1,0,sources[i]));
      // A full Grand Exchange is said as such whatever the settings, and blames neither the source,
      // the toggle nor the margin check, none of which ran.
      for(boolean market:new boolean[]{false,true})for(boolean cushion:new boolean[]{false,true})
        check(GE_FULL.equals(EviLivePlugin.noSuggestionMessage(market,cushion,0,0,sources[i])),"A full GE must say so, source "+sources[i]+", market "+market+", cushion "+cushion);
      // Finished offers waiting to be collected mean ranking DID happen, so the ordinary wording stands.
      check(whenOn[i].equals(EviLivePlugin.noSuggestionMessage(true,false,0,2,sources[i])),"Collectable offers must leave the ordinary wording, source "+sources[i]);
    }
    // No player-facing sentence may carry the old jargon. Checked over what the PLUGIN returns for
    // every (source, toggle, margin check, slot state) combination, not over this test's own
    // literals, which could never fail from a change to the plugin.
    int jargonChecked=0;
    for(SuggestionSource src:new SuggestionSource[]{null,SuggestionSource.HISTORY_FIRST,SuggestionSource.BEST_OF_BOTH,SuggestionSource.MARKET_ONLY})
      for(boolean market:new boolean[]{false,true})for(boolean cushion:new boolean[]{false,true})
        for(int[] sl:new int[][]{{1,0},{0,0},{0,2},{-1,-1}}) {
          String m=EviLivePlugin.noSuggestionMessage(market,cushion,sl[0],sl[1],src);
          check(m!=null && !m.isEmpty(),"Every combination must produce a sentence");
          check(!m.toLowerCase().contains("reviewed flip"),"\"reviewed flip\" is jargon a new player cannot read: "+m);
          jargonChecked++;
        }
    check(jargonChecked==64,"The jargon sweep must cover all 64 combinations, ran "+jargonChecked);
    // THE WIRING, through the real poll path into the real panel. Each case is chosen so that a
    // wrong value on ONE argument at the call site changes the sentence: the source (null or any
    // other), the toggle (hard-coded either way), the margin check, and the slot counts.
    String wired;
    wired=diagnosisFromPoll(false,true,SuggestionSource.MARKET_ONLY,1,0);
    check(NOTHING_SEARCHED.equals(wired),"Poll path, Market only + market-wide off: the sidebar must say nothing was searched, got: "+wired);
    wired=diagnosisFromPoll(true,false,SuggestionSource.MARKET_ONLY,1,0);
    check(MARKET_ONLY_NONE.equals(wired),"Poll path, Market only + market-wide on: got: "+wired);
    wired=diagnosisFromPoll(false,false,SuggestionSource.BEST_OF_BOTH,1,0);
    check(HISTORY_ONLY.equals(wired),"Poll path, market-wide off must reach the message as off, got: "+wired);
    wired=diagnosisFromPoll(true,true,SuggestionSource.HISTORY_FIRST,1,0);
    check((BOTH_NONE+CUSHION).equals(wired),"Poll path, the margin check must reach the message, got: "+wired);
    wired=diagnosisFromPoll(true,false,SuggestionSource.HISTORY_FIRST,0,0);
    check(GE_FULL.equals(wired),"Poll path, the slot counts must reach the message, got: "+wired);
    wired=diagnosisFromPoll(true,false,SuggestionSource.HISTORY_FIRST,0,2);
    check(BOTH_NONE.equals(wired),"Poll path, collectable offers must reach the message, got: "+wired);
    // The shorter overloads are the default source with unknown slot counts.
    check(BOTH_NONE.equals(EviLivePlugin.noSuggestionMessage(true,false)),"Two-arg call, market on");
    check(HISTORY_ONLY.equals(EviLivePlugin.noSuggestionMessage(false,false)),"Two-arg call, market off");
    check((BOTH_NONE+CUSHION).equals(EviLivePlugin.noSuggestionMessage(true,true)),"Two-arg call, margin check on");
    check(BOTH_NONE.equals(EviLivePlugin.noSuggestionMessage(true,false,-1,-1)),"An unknown slot count must change nothing");
    // NOTIFIER: warn-level only, announced once per condition per item. The plugin polls every two
    // seconds, so without the dedupe one standing offer would notify about 1,800 times an hour --
    // and a notifier that cries wolf gets the whole plugin switched off.
    java.util.List<EviLivePlugin.AdviceCard> warnOne=java.util.Arrays.asList(
      new EviLivePlugin.AdviceCard("warn","Below break-even","Varrock teleport","200 @ 1,500","The market is now BELOW your break-even."),
      new EviLivePlugin.AdviceCard("caution","May not fill in time","Air rune","50,000","Running longer than your target."),
      new EviLivePlugin.AdviceCard("info","You own this","Bagged nice tree","453","Shown because you own it."));
    java.util.Set<String> announced=new java.util.HashSet<>();
    check(EviLivePlugin.cardsToNotify(warnOne,false,announced).isEmpty(),"Off by default means no notification at all");
    java.util.List<EviLivePlugin.AdviceCard> first=EviLivePlugin.cardsToNotify(warnOne,true,announced);
    check(first.size()==1,"Only the warn-level card notifies; caution and info must stay silent");
    check("Below break-even".equals(first.get(0).label),"and it must be the break-even warning");
    // The caller records what it announced, which is what makes the helper pure and testable.
    for(EviLivePlugin.AdviceCard c:first)announced.add(EviLivePlugin.adviceKey(c));
    check(EviLivePlugin.cardsToNotify(warnOne,true,announced).isEmpty(),"The same warning must never notify twice");
    // A figure ticking inside the same warning is not a new warning: the key excludes the message.
    java.util.List<EviLivePlugin.AdviceCard> moved=java.util.Arrays.asList(
      new EviLivePlugin.AdviceCard("warn","Below break-even","Varrock teleport","200 @ 1,500","The market fell FURTHER below your break-even."));
    check(EviLivePlugin.cardsToNotify(moved,true,announced).isEmpty(),"A changed figure in the same warning must not re-fire");
    // A different item under the same label is a different warning and must be announced.
    java.util.List<EviLivePlugin.AdviceCard> otherItem=java.util.Arrays.asList(
      new EviLivePlugin.AdviceCard("warn","Below break-even","Mystic robe top","3 @ 120,000","Also below your break-even."));
    check(EviLivePlugin.cardsToNotify(otherItem,true,announced).size()==1,"The same warning about a DIFFERENT item must notify");
    // And a warning that has cleared can fire again if it returns -- that is what retainAll does.
    check(EviLivePlugin.currentAdviceKeys(otherItem).size()==1,"currentAdviceKeys must report what is on screen now");
    check(!EviLivePlugin.currentAdviceKeys(otherItem).contains(EviLivePlugin.adviceKey(warnOne.get(0))),"a card no longer shown must not be retained");
    // Nulls and empties must be inert rather than throwing, since this runs on every poll.
    check(EviLivePlugin.cardsToNotify(null,true,announced).isEmpty(),"A null card list must be inert");
    check(EviLivePlugin.cardsToNotify(java.util.Arrays.asList((EviLivePlugin.AdviceCard)null),true,announced).isEmpty(),"A null card must be skipped");
    check(EviLivePlugin.cardsToNotify(java.util.Arrays.asList(new EviLivePlugin.AdviceCard("warn","L","N","F","")),true,new java.util.HashSet<>()).isEmpty(),"An empty message must not notify");
    check("".equals(EviLivePlugin.adviceKey(null)),"adviceKey(null) must not throw");
    // A new player with a small cash stack who sets a profit target out of its reach must be told
    // that the target is the reason, and what is actually reachable -- otherwise the panel looks the
    // same as a market with nothing in it, and they have no way to find out.
    EviLivePlugin.Reachable reach=new EviLivePlugin.Reachable();
    reach.name="Iron javelin tips";reach.profit=250000L;
    String withReach=EviLivePlugin.reachableMessage(reach);
    check(withReach.contains("Iron javelin tips")&&withReach.contains(String.format("%,d",250000L)),"The reachable message must name the trade and its figure: "+withReach);
    check(withReach.contains("Lower the minimum"),"It must say what to do about it: "+withReach);
    check(EviLivePlugin.reachableMessage(null).isEmpty(),"No reading means no message at all, never a vague hint");
    EviLivePlugin.Reachable empty=new EviLivePlugin.Reachable();
    check(EviLivePlugin.reachableMessage(empty).isEmpty(),"A reading with no figure adds nothing");
    EviLivePlugin.Reachable loss=new EviLivePlugin.Reachable();loss.profit=-5L;loss.name="Anything";
    check(EviLivePlugin.reachableMessage(loss).isEmpty(),"A losing trade is never offered as what is reachable");
    EviLivePlugin.Reachable unnamed=new EviLivePlugin.Reachable();unnamed.profit=1000L;
    check(EviLivePlugin.reachableMessage(unnamed).contains(String.format("%,d",1000L)),"A figure with no item name still reports the figure");
    // With a rung the bridge actually probed, the message names the setting to pick rather than
    // saying "lower it" and leaving the player to guess how far. On 28 Sept 2026 the old wording sat
    // on top of a figure computed by removing the floor entirely, which reported a small fraction of
    // what was available one rung down -- so naming the rung is also what keeps the number honest.
    EviLivePlugin.Reachable rung=new EviLivePlugin.Reachable();
    rung.name="Mystic armour set";rung.profit=350000L;rung.atMinimum=200000L;
    String withRung=EviLivePlugin.reachableMessage(rung);
    check(withRung.contains(String.format("%,d",200000L)),"It must name the setting to pick: "+withRung);
    check(withRung.contains(String.format("%,d",350000L))&&withRung.contains("Mystic armour set"),"...and still name the trade and figure: "+withRung);
    check(!withRung.contains("Lower the minimum"),"Naming the rung replaces the vague instruction: "+withRung);
    // An older bridge sends no rung and must keep the original sentence rather than a broken one.
    EviLivePlugin.Reachable noRung=new EviLivePlugin.Reachable();noRung.name="Iron javelin tips";noRung.profit=250000L;
    check(EviLivePlugin.reachableMessage(noRung).contains("Lower the minimum"),"A bridge with no rung keeps the original wording");
    // minProfit=1 is MinProfitTier.NONE, which is "no minimum at all" rather than a number to set.
    EviLivePlugin.Reachable none=new EviLivePlugin.Reachable();none.name="Anything";none.profit=900L;none.atMinimum=1L;
    check(EviLivePlugin.reachableMessage(none).contains("Lower the minimum"),"The NONE rung is not a figure to tell anyone to type in");
    // The sell-side reserve: a buy held back so the exits already owed still have somewhere to go.
    check(EviLivePlugin.sellReserveMessage(3).contains("3 items you're holding with no sell placed yet"),"The reserve message must say how many exits are owed");
    check(EviLivePlugin.sellReserveMessage(1).contains("1 item you're holding"),"and read correctly for a single one");
    check(!EviLivePlugin.sellReserveMessage(2).contains("settings"),"It must never blame the settings, which were never the reason");
    // Buys in progress bring their own slot for the sell; the wording must not claim otherwise, or it
    // describes the four-slot bug a player found rather than the rule that replaced it.
    check(!EviLivePlugin.sellReserveMessage(2).contains("buying"),"In-progress buys never owe a slot, so the message must not say they do");
    check(EviLivePlugin.sellReserveMessage(null).contains("holding with no sell placed yet"),"An older bridge sending no count must still produce sensible wording");
    // Gson must accept the bridge's slots object, including an older build that omits it entirely.
    EviLivePlugin.SuggestionResponse withSlots=new Gson().fromJson("{\"suggestion\":null,\"slots\":{\"free\":2,\"collectable\":0,\"sellSlotsOwed\":3,\"buysHeldForExits\":true}}",EviLivePlugin.SuggestionResponse.class);
    check(withSlots.slots!=null && withSlots.slots.buysHeldForExits && withSlots.slots.sellSlotsOwed==3,"The slots object must parse");
    EviLivePlugin.SuggestionResponse noSlots=new Gson().fromJson("{\"suggestion\":null}",EviLivePlugin.SuggestionResponse.class);
    check(noSlots.slots==null,"A bridge that doesn't send slots must leave it null rather than fabricating a reserve");

    // heldPositions=: confirms which journal positions are really in the inventory. Reported live:
    // two long-gone positions (adamant darts, adamant keel parts) reserved the last two slots,
    // because the journal only sees the Grand Exchange and never learns stock left another way.
    EviLivePlugin heldPlugin=new EviLivePlugin();
    set(heldPlugin,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean marginSafetyCushion(){return false;}
    });
    // The bridge believes 810 (darts), 32032 (keel parts) and 4151 are held; only 4151 really is.
    set(heldPlugin,"bridgePositionItems",new java.util.TreeSet<>(java.util.Arrays.asList(810,32032,4151)));
    set(heldPlugin,"inventoryItemIds",new java.util.HashSet<>(java.util.Arrays.asList(4151,995,2572)));
    set(heldPlugin,"inventorySnapshotEstablished",false);
    check("".equals(suggestionQuery.invoke(heldPlugin)),"Before the inventory has loaded, nothing is confirmed, so nothing is sent");
    set(heldPlugin,"inventorySnapshotEstablished",true);
    String heldQuery=(String)suggestionQuery.invoke(heldPlugin);
    check("heldPositions=4151".equals(heldQuery),"Only the position genuinely in the inventory is confirmed (got "+heldQuery+")");
    check(!heldQuery.contains("810") && !heldQuery.contains("32032"),"Stale positions are never confirmed -- this is the reported bug");
    check(!heldQuery.contains("995") && !heldQuery.contains("2572"),"Coins and a ring the bridge never asked about must never leave the client");
    set(heldPlugin,"inventoryItemIds",new java.util.HashSet<>(java.util.Arrays.asList(995,2572)));
    // Checked and found none: said explicitly, since that is what exposes a stale position -- and an
    // empty confirmation still reserves nothing on the bridge.
    check("heldPositions=".equals(suggestionQuery.invoke(heldPlugin)),"A check that finds none of the named positions must still say it checked");
    resetMethod.invoke(heldPlugin);
    set(heldPlugin,"inventoryItemIds",new java.util.HashSet<>(java.util.Arrays.asList(4151)));
    set(heldPlugin,"inventorySnapshotEstablished",true);
    check("".equals(suggestionQuery.invoke(heldPlugin)),"reset() must forget what the bridge named, so a new session starts unconfirmed");
    EviLivePlugin.SuggestionResponse withPositions=new Gson().fromJson("{\"slots\":{\"positionItems\":[810,32032]}}",EviLivePlugin.SuggestionResponse.class);
    check(withPositions.slots.positionItems.length==2 && withPositions.slots.positionItems[0]==810,"positionItems must parse from the bridge's response");

    // freeSlots=/collectable=: counted from the 8 real slots and sent only once every one of them
    // has actually been observed this session.
    EviLivePlugin slotPlugin=new EviLivePlugin();
    set(slotPlugin,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean marginSafetyCushion(){return false;}
    });
    EviLivePlugin.Offer[] capacitySlots=(EviLivePlugin.Offer[])get(slotPlugin,"slots");
    for(int i=0;i<8;i++){capacitySlots[i]=new EviLivePlugin.Offer();capacitySlots[i].state="SELLING";capacitySlots[i].itemId=100+i;capacitySlots[i].total=10;capacitySlots[i].filled=0;}
    refresh.invoke(slotPlugin);
    check(((String)suggestionQuery.invoke(slotPlugin)).contains("freeSlots=0&collectable=0"),"Eight in-progress offers must report a full Grand Exchange with nothing to collect");
    capacitySlots[3].state="SOLD";capacitySlots[3].total=10;capacitySlots[3].filled=10;
    refresh.invoke(slotPlugin);
    check(((String)suggestionQuery.invoke(slotPlugin)).contains("freeSlots=0&collectable=1"),"A finished, uncollected offer still occupies its slot but is counted separately");
    capacitySlots[3].state="EMPTY";capacitySlots[3].itemId=0;capacitySlots[3].total=0;capacitySlots[3].filled=0;
    refresh.invoke(slotPlugin);
    check(((String)suggestionQuery.invoke(slotPlugin)).contains("freeSlots=1&collectable=0"),"Collecting frees the slot again, with no separate bookkeeping");
    capacitySlots[5]=null; // a slot never observed this session
    refresh.invoke(slotPlugin);
    check(!((String)suggestionQuery.invoke(slotPlugin)).contains("freeSlots="),"An incomplete slot snapshot must send no count at all rather than a fabricated one");
    resetMethod.invoke(slotPlugin);
    check("".equals(suggestionQuery.invoke(slotPlugin)),"reset() (logout/world hop) must forget the slot counts entirely");

    // offerDriftHint: pure, so tested directly rather than through a full poll round-trip.
    Method driftHint=EviLivePlugin.class.getDeclaredMethod("offerDriftHint",EviLivePlugin.ActiveOffer.class,Suggestion.class);
    driftHint.setAccessible(true);
    EviLivePlugin.ActiveOffer buyOffer=new EviLivePlugin.ActiveOffer(11,97,true,"Buying item",300);
    Suggestion priceFor11=new Suggestion();priceFor11.itemId=11;priceFor11.buyPrice=100;priceFor11.sellPrice=110;
    check(driftHint.invoke(null,buyOffer,priceFor11)==null,"A buy offer only 3% below the market's buyPrice must not be flagged (within OFFER_DRIFT_THRESHOLD)");
    EviLivePlugin.ActiveOffer buyOfferFar=new EviLivePlugin.ActiveOffer(11,80,true,"Buying item",300);
    String buyHint=(String)driftHint.invoke(null,buyOfferFar,priceFor11);
    // Shortened 1 Oct 2026: the sentence no longer repeats the item name or the two prices, because
    // the card above it already shows both (name as its title, prices in its figures row). What it
    // must still carry is the SIZE of the drift and what to do -- those are not on the card.
    check(buyHint!=null && buyHint.contains("20%") && buyHint.contains("under the market"),"A buy offer well below the market must say how far under it is: "+buyHint);
    check(buyHint.toLowerCase().contains("relist"),"...and what to do about it: "+buyHint);
    check(!buyHint.contains("Buying item"),"The card already shows the item name; repeating it is what made these unreadable");
    EviLivePlugin.ActiveOffer sellOffer=new EviLivePlugin.ActiveOffer(12,250,false,"Selling item",50);
    Suggestion priceFor12=new Suggestion();priceFor12.itemId=12;priceFor12.buyPrice=190;priceFor12.sellPrice=200;
    String sellHint=(String)driftHint.invoke(null,sellOffer,priceFor12);
    check(sellHint!=null && sellHint.contains("over the market"),"A sell offer above the market must say so: "+sellHint);
    check(sellHint.contains("take the current price"),"...and must still offer selling now as the alternative, which is the useful half: "+sellHint);
    check(!sellHint.contains("Selling item"),"The card already shows the item name");
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
    // Shortened 1 Oct 2026. The facts moved to the card's figures row, which this used to leave
    // EMPTY while the drift card beside it used its own. What the SENTENCE must still carry is the
    // hedge, because this is a fill estimate and the standing rule is that anything predicting a
    // fill says plainly it is not promising one.
    check(slowHint!=null && slowHint.contains("rough volume estimate") && slowHint.contains("not a guarantee"),"A fill estimate must stay explicitly hedged -- never a fill promise: "+slowHint);
    check(slowHint.length()<140,"...and must stay short enough to read at a glance: "+slowHint.length()+" chars");
    check(!slowHint.contains("Buying item"),"The card already shows the item name");
    Method fillFiguresMethod=EviLivePlugin.class.getDeclaredMethod("offerFillFigures",EviLivePlugin.ActiveOffer.class,EviLivePlugin.OfferFillEstimate.class);
    fillFiguresMethod.setAccessible(true);
    String slowFigures=(String)fillFiguresMethod.invoke(null,buyOffer,slowMinutes);
    check(slowFigures!=null && slowFigures.contains("300 remaining") && slowFigures.contains("100 hours"),"The figures row carries the quantity and the pace, phrased as hours: "+slowFigures);
    check(fillFiguresMethod.invoke(null,buyOffer,onPace)==null,"No figures for an offer that is not flagged at all");
    EviLivePlugin.OfferFillEstimate noVolumeAtAll=new EviLivePlugin.OfferFillEstimate();noVolumeAtAll.itemId=12;noVolumeAtAll.likelyToFillInTime=false;noVolumeAtAll.estimatedFillMinutes=-1;
    String noVolumeHint=(String)fillHintMethod.invoke(null,sellOffer,noVolumeAtAll);
    String noVolumeFigures=(String)fillFiguresMethod.invoke(null,sellOffer,noVolumeAtAll);
    check(noVolumeFigures!=null && noVolumeFigures.contains("almost no recent trading") && !noVolumeFigures.contains("-1"),"The -1 sentinel (essentially no recent volume) must never be printed as a literal number: "+noVolumeFigures);
    EviLivePlugin.OfferFillEstimate fastMinutes=new EviLivePlugin.OfferFillEstimate();fastMinutes.itemId=11;fastMinutes.likelyToFillInTime=false;fastMinutes.estimatedFillMinutes=45;
    String fastHint=(String)fillHintMethod.invoke(null,buyOffer,fastMinutes);
    String fastFigures=(String)fillFiguresMethod.invoke(null,buyOffer,fastMinutes);
    check(fastFigures!=null && fastFigures.contains("45 minutes"),"An estimate under an hour must be phrased in minutes, not a fractional hour: "+fastFigures);

    // skipSuggestion(): the sidebar's "Skip this suggestion" button callback.
    EviLivePlugin skipPlugin=new EviLivePlugin();
    SuggestionCache skipCache=new SuggestionCache();
    set(skipPlugin,"suggestionCache",skipCache);
    Method skipMethod=EviLivePlugin.class.getDeclaredMethod("skipSuggestion");skipMethod.setAccessible(true);
    skipCache.set(EviLiveSuggestionTest.suggestion(5,"buy",10,20,30));
    skipMethod.invoke(skipPlugin); // sender is null here (startUp() never ran): must not throw
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> skipPluginSkips=(java.util.Set<Integer>)get(skipPlugin,"skippedItemIds");
    check(skipPluginSkips.contains(5),"With no RuneScape account known (no ConfigManager here), skipSuggestion() must fall back to the session set, so the press still applies");
    check(skipCache.get()==null,"skipSuggestion() must clear the cache immediately so the panel doesn't show stale text until the next poll");
    skipMethod.invoke(skipPlugin); // nothing cached now: must be a safe no-op, not throw or add anything
    check(skipPluginSkips.size()==1,"Calling skipSuggestion() again with nothing cached must not add anything or throw");

    // blockSuggestion(): the sidebar's "Block this item" button. Same immediate session-local effect as
    // Skip; the permanent part is the bridge POST, whose body must carry the item.
    EviLivePlugin blockPlugin=new EviLivePlugin();
    SuggestionCache blockCache=new SuggestionCache();
    set(blockPlugin,"suggestionCache",blockCache);
    Method blockMethod=EviLivePlugin.class.getDeclaredMethod("blockSuggestion");blockMethod.setAccessible(true);
    blockCache.set(EviLiveSuggestionTest.suggestion(7,"buy",10,20,30));
    blockMethod.invoke(blockPlugin); // sender is null here: must not throw
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> blockSkips=(java.util.Set<Integer>)get(blockPlugin,"skippedItemIds");
    check(blockSkips.contains(7),"Block must exclude the item for this session at once");
    check(blockCache.get()==null,"Block must clear the cached suggestion immediately");
    blockMethod.invoke(blockPlugin); // nothing cached now: a safe no-op
    check(blockSkips.size()==1,"Block with nothing cached must do nothing");

    // acceptSuggestion(): the sidebar's "I took this one" button. Unlike Skip, Block and Personal
    // use, this must change NOTHING about what EVI suggests -- it records that the player acted on
    // the pick, so the suggestion has to stay on screen and the item must not be set aside. It also
    // has to toggle, because an acceptance nobody can undo is one players stop recording.
    EviLivePlugin acceptPlugin=new EviLivePlugin();
    SuggestionCache acceptCache=new SuggestionCache();
    set(acceptPlugin,"suggestionCache",acceptCache);
    Method acceptMethod=EviLivePlugin.class.getDeclaredMethod("acceptSuggestion");acceptMethod.setAccessible(true);
    Suggestion accepted=EviLiveSuggestionTest.suggestion(9,"buy",4,50,60);
    accepted.id="abc-9-00";
    acceptCache.set(accepted);
    acceptMethod.invoke(acceptPlugin); // sender is null here: must not throw
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> acceptSkips=(java.util.Set<Integer>)get(acceptPlugin,"skippedItemIds");
    check(acceptSkips.isEmpty(),"Accepting a suggestion must not set the item aside -- the player took it, they did not reject it");
    check(acceptCache.get()!=null,"Accepting must leave the suggestion on screen, not clear it like Skip does");
    check(accepted.accepted,"Accepting must mark the cached suggestion as accepted so the button reads as pressed");
    acceptMethod.invoke(acceptPlugin);
    check(!accepted.accepted,"Pressing it again must take the acceptance back");
    // A suggestion the bridge issued no id for cannot be recorded, so the callback must do nothing
    // rather than post an acceptance that refers to no logged suggestion.
    Suggestion noId=EviLiveSuggestionTest.suggestion(12,"buy",4,50,60);
    acceptCache.set(noId);
    acceptMethod.invoke(acceptPlugin);
    check(!noId.accepted,"With no suggestion id from the engine, accepting must be a no-op");

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
    // A sell suggestion with no buyId is the idle-inventory tier: gear EVI only sees in the inventory
    // and never watched being bought (a piece of gear a player was wearing, reported live).
    // There is no purchase to mark, so the ITEM is excluded instead -- before this the button refused
    // to mark anything and the same suggestion came straight back on the next poll.
    Suggestion sellNoBuyId=EviLiveSuggestionTest.suggestion(12,"sell",50,10,20); // buyId left null
    noBuyIdCache.set(sellNoBuyId);
    flagMethod.invoke(noBuyIdFlagPlugin);
    check(noBuyIdCache.get()==null,"Personal use on owned gear must clear the cache, so the same suggestion cannot return");
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> noBuyIdSkips=(java.util.Set<Integer>)get(noBuyIdFlagPlugin,"skippedItemIds");
    check(noBuyIdSkips.contains(12),"Personal use on owned gear must exclude that item for this session too");

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
    // later-cancelled buy order left the journal correctly believing more were bought and never
    // resold than were actually still held).
    Method correct=EviLivePlugin.class.getDeclaredMethod("correctSellQuantityAgainstInventory",Suggestion.class);correct.setAccessible(true);
    EviLivePlugin correctPlugin=new EviLivePlugin();
    correct.invoke(correctPlugin,(Suggestion)null); // must never throw on a null suggestion (pollSuggestion's s can be null)

    Suggestion beforeSnapshot=EviLiveSuggestionTest.suggestion(11232,"sell",20,500000,550000);
    correct.invoke(correctPlugin,beforeSnapshot);
    check(beforeSnapshot.quantity==20,"Before the inventory has ever been successfully snapshotted this session, must fail open -- leave the quantity exactly as the bridge sent it");

    set(correctPlugin,"inventorySnapshotEstablished",true);
    set(correctPlugin,"inventoryQuantities",java.util.Map.of(11232,15));
    Suggestion buySuggestion=EviLiveSuggestionTest.suggestion(11232,"buy",20,500000,550000);
    correct.invoke(correctPlugin,buySuggestion);
    check(buySuggestion.quantity==20,"A \"buy\" suggestion's quantity means something entirely different (how many to acquire) and must never be touched");

    Suggestion overstated=EviLiveSuggestionTest.suggestion(11232,"sell",20,500000,550000);
    correct.invoke(correctPlugin,overstated);
    check(overstated.quantity==15,"An overstated sell quantity must be reduced to what's actually held");
    check(overstated.reasoning.contains("Reduced from 20 to the 15"),"The reduction must be explained in the reasoning text, the same way cash/duration limits already are");

    Suggestion exact=EviLiveSuggestionTest.suggestion(11232,"sell",15,500000,550000);
    correct.invoke(correctPlugin,exact);
    check(exact.quantity==15,"A quantity that already matches what's held must be left unchanged");

    Suggestion understated=EviLiveSuggestionTest.suggestion(11232,"sell",5,500000,550000);
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
    set(pollPlugin,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;} // suggestionQuery() (called at the top of every real pollSuggestion()) needs this
      public boolean marginSafetyCushion(){return false;}
    });
    set(pollPlugin,"running",true);set(pollPlugin,"lifecycle",1L);
    set(pollPlugin,"inventorySnapshotEstablished",true);
    set(pollPlugin,"inventoryItemIds",java.util.Set.of()); // established, empty: item 5 is not actually held
    set(pollPlugin,"answers",(AnswerSource)query->
      "{\"suggestion\":{\"itemId\":5,\"name\":\"Blue dragon hide\",\"action\":\"sell\",\"quantity\":2712,\"buyPrice\":1000,\"sellPrice\":1200,\"source\":\"holding\",\"reasoning\":\"stale\",\"persisted\":true}}");
    poll.invoke(pollPlugin,1L); // sender is null here (startUp() never ran): the auto-retry attempt must not throw
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> pollPluginSkips=(java.util.Set<Integer>)get(pollPlugin,"skippedItemIds");
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> pollPluginUnverifiable=(java.util.Set<Integer>)get(pollPlugin,"unverifiableItemIds");
    check(pollPluginUnverifiable.contains(5),"A persisted suggestion for an item not in the inventory must be held back for this poll");
    // NOT the manual-skip list, which is the whole point. It used to go there, and a buy that has
    // FILLED but not been COLLECTED is not in the inventory -- so a poll landing in that window
    // excluded the item until RuneLite restarted, and collecting it changed nothing. A player hit this
    // twice; the second time the bridge was answering "sell 1 ..." with a profit
    // while the plugin dropped it. A transient condition must not cause a permanent exclusion.
    check(!pollPluginSkips.contains(5),"...and must NOT enter the manual-skip list, which lasts the whole session");
    check(get(pollPlugin,"suggestionCache")==null,"The rejected phantom suggestion must never reach suggestionCache (left uninitialized/untouched in this harness)");

    // The exclusion lasts exactly until the inventory changes -- collecting the filled buy IS that
    // event. Without this the new set would just be a slower version of the blacklist it replaced.
    pollPlugin.dropUnverifiableOnInventoryChange(java.util.Set.of());
    check(pollPluginUnverifiable.contains(5),"An unchanged inventory must not clear the hold-back");
    pollPlugin.dropUnverifiableOnInventoryChange(java.util.Set.of(5));
    check(pollPluginUnverifiable.isEmpty(),"The item arriving in the inventory must clear the hold-back");
    pollPluginUnverifiable.add(5);
    pollPlugin.dropUnverifiableOnInventoryChange(java.util.Set.of(99));
    check(pollPluginUnverifiable.isEmpty(),"Any inventory change clears it, not only the item's own arrival");
    set(pollPlugin,"inventoryItemIds",java.util.Set.of()); // restore for anything after this block

    // The same suggestion, but the item genuinely IS in the inventory: must be trusted and cached
    // normally, exactly as an unpersisted (live-observed) suggestion always has been.
    EviLivePlugin pollPlugin2=new EviLivePlugin();
    set(pollPlugin2,"gson",new Gson());
    set(pollPlugin2,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean marginSafetyCushion(){return false;}
    });
    set(pollPlugin2,"running",true);set(pollPlugin2,"lifecycle",1L);
    set(pollPlugin2,"suggestionCache",new SuggestionCache());
    set(pollPlugin2,"openItemPriceCache",new OpenItemPriceCache());
    set(pollPlugin2,"inventorySnapshotEstablished",true);
    set(pollPlugin2,"inventoryItemIds",java.util.Set.of(5)); // genuinely still held
    set(pollPlugin2,"answers",(AnswerSource)query->
      "{\"suggestion\":{\"itemId\":5,\"name\":\"Blue dragon hide\",\"action\":\"sell\",\"quantity\":2712,\"buyPrice\":1000,\"sellPrice\":1200,\"source\":\"holding\",\"reasoning\":\"real\",\"persisted\":true}}");
    poll.invoke(pollPlugin2,1L);
    SuggestionCache resultCache=(SuggestionCache)get(pollPlugin2,"suggestionCache");
    check(resultCache.get()!=null && resultCache.get().itemId==5,"A persisted suggestion for an item genuinely still in the inventory must be cached and shown normally");
    @SuppressWarnings("unchecked")
    java.util.Set<Integer> pollPlugin2Skips=(java.util.Set<Integer>)get(pollPlugin2,"skippedItemIds");
    check(pollPlugin2Skips.isEmpty(),"A verified-real persisted suggestion must not be skipped");

    // pollSuggestion() end-to-end: the bridge's remembered quantity (13, matching the journal) is
    // higher than what's actually in the inventory right now (11) -- the exact in-game shape of
    // the bug report this fix came from (a partially-filled buy order, cancelled, left a stale
    // remaining count). Must come through corrected, not as the bridge originally sent it.
    EviLivePlugin pollPlugin3=new EviLivePlugin();
    set(pollPlugin3,"gson",new Gson());
    set(pollPlugin3,"config",new EviLiveConfig(){public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}public boolean includeMarketWide(){return false;}
      public boolean marginSafetyCushion(){return false;}
    });
    set(pollPlugin3,"running",true);set(pollPlugin3,"lifecycle",1L);
    set(pollPlugin3,"suggestionCache",new SuggestionCache());
    set(pollPlugin3,"openItemPriceCache",new OpenItemPriceCache());
    set(pollPlugin3,"inventorySnapshotEstablished",true);
    set(pollPlugin3,"inventoryItemIds",java.util.Set.of(11232)); // genuinely still held, just not 20 of it
    set(pollPlugin3,"inventoryQuantities",java.util.Map.of(11232,15));
    set(pollPlugin3,"answers",(AnswerSource)query->
      "{\"suggestion\":{\"itemId\":11232,\"name\":\"Dragon dart tip\",\"action\":\"sell\",\"quantity\":20,\"buyPrice\":500000,\"sellPrice\":550000,\"source\":\"holding\",\"reasoning\":\"You're holding 20 from an earlier buy\",\"persisted\":true}}");
    poll.invoke(pollPlugin3,1L);
    Suggestion corrected=((SuggestionCache)get(pollPlugin3,"suggestionCache")).get();
    check(corrected!=null && corrected.quantity==15,"pollSuggestion must apply the inventory correction before caching, not just verify presence");
    check(corrected.reasoning.contains("Reduced from 20 to the 15"),"The corrected suggestion's reasoning must explain the reduction");

    // Settings panel: each description is a hover tooltip. A player reported the old ones -- some
    // over 700 characters -- as unreadable, so they are held to one short sentence here.
    java.util.Set<String> keyNames=new java.util.TreeSet<>();
    for(Method m:EviLiveConfig.class.getDeclaredMethods()) {
      net.runelite.client.config.ConfigItem item=m.getAnnotation(net.runelite.client.config.ConfigItem.class);
      if(item==null)continue;
      keyNames.add(item.keyName());
      check(item.description().length()<=110,"Tooltip for \""+item.name()+"\" is "+item.description().length()+" characters; keep it to one short sentence (110 max)");
      check(!item.description().isEmpty(),"Every visible setting needs a tooltip: "+item.name());
    }
    // RuneLite stores settings by keyName, so renaming one silently resets that setting for every
    // existing user. Changing names and tooltips is safe; this list must only ever grow -- EXCEPT the 4.0.0 switch-over, by
    // decision: the three hidden developer keys (journalCompare, shadowEngine, engineSource) went with the companion app,
    // and forecastHorizon was REPLACED by exitRiskCheck (its 1h/overnight values no longer exist; everyone starts at Off).
    check(keyNames.equals(new java.util.TreeSet<>(java.util.Arrays.asList(
      "suggestionKeybind","showSuggestionHint","minProfitTier","itemBlocklist","riskLevel","includeMarketSuggestions","riskLevelV2","includeMarketWide",
      "tradingProfile","maxTradeShare","tradeDuration","tradePace","suggestionSource","suggestIdleInventory","exitRiskCheck","forecastPolicy","requireMarginAboveNoise","notifyAdvice","panelTheme","suggestionFocus",
      "maxPositions","positionSizing","importBridgeHistory","shareInviteDismissedVersion"))),
      "A setting's keyName changed or disappeared, which would reset it for existing users: "+keyNames);
    // The import setting: renamed for players at 4.0.0, keyName kept (a stored "on" carries over).
    net.runelite.client.config.ConfigItem importItem=EviLiveConfig.class.getDeclaredMethod("importBridgeHistory").getAnnotation(net.runelite.client.config.ConfigItem.class);
    check("Import old trade history".equals(importItem.name()) && "importBridgeHistory".equals(importItem.keyName()),"The import setting reads \"Import old trade history\" under its old keyName: "+importItem.name());
    check(!importItem.description().toLowerCase().contains("built-in engine") && !importItem.description().toLowerCase().contains("bridge"),"The import tooltip names no engine and no bridge: "+importItem.description());
    // No visible setting names the bridge, the companion app, the scanner or the dashboard (4.0.0 wording inventory).
    for(Method m:EviLiveConfig.class.getDeclaredMethods()) {
      net.runelite.client.config.ConfigItem it=m.getAnnotation(net.runelite.client.config.ConfigItem.class);
      if(it==null || it.hidden())continue;
      String words=(it.name()+" "+it.description()).toLowerCase();
      for(String bad:new String[]{"bridge","companion","scanner","dashboard","pairing","127.0.0.1"})
        check(!words.contains(bad),"Visible setting \""+it.name()+"\" names \""+bad+"\": "+words);
    }

    System.out.println("PASS: the suggestion-settings query builder (including target trade duration), the cash-stack query building, the open-offer-item query building, the held-for-resale query building (including the held item's own buy offerId), the active-slot/skip exclude query building, the skip-suggestion and block callbacks, the accept-suggestion callback (the pick left on screen and not set aside, the toggle, the no-op without an engine-issued id), the persisted-suggestion inventory verification, the poll-time auto-skip of a stale persisted suggestion, the personal-use button callback (no-op on a buy suggestion; an item-level exclusion for owned gear the idle-inventory tier offered, with no buy behind it; the session-local exclusion on an actual held item), the inventory-quantity/idle-inventory-suggestion query building, the sell-quantity correction against actual current inventory (including its end-to-end effect through pollSuggestion), the in-progress-offer slots= query building (item:remainingQty pairs, excluding terminal-but-uncollected offers), the activeOffers snapshot itself (price/direction/name/remaining quantity, terminal offers excluded), the offer-drift cancel/relist hint (buy offers below market, sell offers above market, within-threshold and missing-price cases all left unflagged), the offer fill-time hint (on-pace and no-estimate cases left unflagged, minutes phrased as hours past 60, the -1 no-volume sentinel never printed as a number, and the wording kept to a hedged volume observation rather than a fill guarantee), Held.price/holdBuyPrice (the real spent/filled average paid, correctly rounded, sent only when known, and never fabricated when no spent data was observed), the MinProfitTier preset tiers (AUTO left off the query exactly like the old free-form field's 0, each tier's own gp figure), and marginSafetyCushion (off by default under its new keyName, combining correctly with a profit tier when opted in, and explicit-off matching pre-existing behaviour), the sidebar's full GE offer list snapshot (uncollected offers included, cleared on reset), the no-suggestion message wording, the cash stack counting coins AND platinum tokens at 1,000 gp each without overflowing (a full stack of both is ~2.149 trillion) and reporting -1 rather than 0 when the inventory cannot be read, the members= world-type parameter, the focus= parameter (All items sends nothing), the Exit-risk check under its new keyName, and the keyName registry");
  }
}
