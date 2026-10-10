package com.evi.live;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridLayout;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;

/**
 * the maintainer's 6 Oct 2026 decisions, each asserted as a VALUE rather than by restating the code:
 *  - risk level and market-wide each moved to a NEW keyName (everyone starts at Low, market-wide on),
 *    the old items hidden and never read;
 *  - Layout B: three equal Low / Medium / High buttons in the sidebar, the chosen one outlined only;
 *  - a Skip lasts four hours, per RuneScape account, across reset() and a client restart.
 * Synthetic: no RuneLite client and no bridge. Sections 1-6 use stand-in writers and stores; section 7
 * drives the shipping paths through a real RuneLite ConfigManager (see realConfigManager).
 */
public final class EviLiveRiskSkipTest {
  static void set(Object target,String name,Object value)throws Exception {
    Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
  }
  static Object get(Object target,String name)throws Exception {
    Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
  }
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static String query(EviLivePlugin p)throws Exception {
    Method m=EviLivePlugin.class.getDeclaredMethod("suggestionQuery");m.setAccessible(true);return (String)m.invoke(p);
  }
  static void call(EviLivePlugin p,String method)throws Exception {
    Method m=EviLivePlugin.class.getDeclaredMethod(method);m.setAccessible(true);m.invoke(p);
  }
  static void visit(Container parent,List<Component> out){
    for(Component c:parent.getComponents()){out.add(c);if(c instanceof Container)visit((Container)c,out);}
  }
  static JButton button(EviLivePanel panel,String text){
    List<Component> all=new ArrayList<>();visit(panel,all);
    return all.stream().filter(c->c instanceof JButton).map(c->(JButton)c).filter(b->text.equals(b.getText()))
      .findFirst().orElseThrow(()->new AssertionError("No \""+text+"\" button in the sidebar"));
  }
  static Color outline(JButton b){
    check(b.getBorder() instanceof CompoundBorder,"A risk button's border must be an outline plus padding: "+b.getBorder());
    javax.swing.border.Border outside=((CompoundBorder)b.getBorder()).getOutsideBorder();
    check(outside instanceof LineBorder,"A risk button's outline must be a plain LineBorder: "+outside);
    check(((LineBorder)outside).getThickness()==1,"The outline must be 1px");
    return ((LineBorder)outside).getLineColor();
  }
  static void edt()throws Exception {SwingUtilities.invokeAndWait(()->{});}

  /** A config whose ONLY non-default answers are the ones given; trade share off so the query is short. */
  static EviLiveConfig config(RiskLevel oldRisk,RiskLevel newRisk,boolean oldMarket,boolean newMarket) {
    return new EviLiveConfig(){
      public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}
      public RiskLevel riskLevel(){return oldRisk;}
      public RiskLevel riskLevelV2(){return newRisk;}
      public boolean includeMarketSuggestions(){return oldMarket;}
      public boolean includeMarketWide(){return newMarket;}
    };
  }

  /** An in-memory stand-in for RuneLite's per-account RS-profile config. */
  static final class FakeStore implements SkipMemory.Store {
    final Map<String,String> byProfile=new HashMap<>();
    String profile="rsprofile.accountA";
    public String profileKey(){return profile;}
    public String read(String p){return byProfile.get(p);}
    public void write(String p,String v){if(v.isEmpty())byProfile.remove(p);else byProfile.put(p,v);}
  }
  static EviLivePlugin skipPlugin(FakeStore store,AtomicLong clock)throws Exception {
    EviLivePlugin p=new EviLivePlugin();
    set(p,"config",config(RiskLevel.LOW,RiskLevel.LOW,false,false));
    set(p,"suggestionCache",new SuggestionCache());
    set(p,"skipMemory",new SkipMemory(store,clock::get));
    return p;
  }
  static void offer(EviLivePlugin p,int itemId)throws Exception {
    ((SuggestionCache)get(p,"suggestionCache")).set(EviLiveSuggestionTest.suggestion(itemId,"buy",10,20,30));
  }

  public static void main(String[] args)throws Exception {
    // ------------------------------------------------------------- 1. the new keys and their defaults
    EviLiveConfig untouched=new EviLiveConfig(){};
    check(untouched.riskLevelV2()==RiskLevel.LOW,"Everyone must start at Low on the new key, got "+untouched.riskLevelV2());
    check(untouched.includeMarketWide()==true,"Market-wide must default ON on the new key");
    ConfigItem riskItem=EviLiveConfig.class.getDeclaredMethod("riskLevelV2").getAnnotation(ConfigItem.class);
    check("riskLevelV2".equals(riskItem.keyName()),"Risk level must live on the NEW keyName riskLevelV2: "+riskItem.keyName());
    // HIDDEN since 7 Oct 2026 (RiskLevel.SHOWN false) until the levels are calibrated on live sells.
    check(riskItem.hidden(),"While the risk levels are hidden the Risk level setting must be hidden too");
    check("Risk level".equals(riskItem.name()),"Its name must read \"Risk level\": "+riskItem.name());
    check("How much risk you accept. Higher levels add slower or more volatile items.".equals(riskItem.description()),
      "The tooltip must name no odds: "+riskItem.description());
    check(EviLiveConfig.class.getDeclaredMethod("riskLevelV2").getReturnType()==RiskLevel.class,"riskLevelV2 must be the RiskLevel enum");
    ConfigItem oldRiskItem=EviLiveConfig.class.getDeclaredMethod("riskLevel").getAnnotation(ConfigItem.class);
    check("riskLevel".equals(oldRiskItem.keyName()),"The old risk item must keep its keyName so a stored value still loads");
    check(oldRiskItem.hidden(),"The old risk item must be hidden");
    check(EviLiveConfig.class.getDeclaredMethod("riskLevel").getReturnType()==RiskLevel.class,"The old risk item must keep its type, or a stored HIGH confuses the loader");
    check("riskLevelV2".equals(EviLivePlugin.RISK_KEY),"The sidebar must write the SAME key the settings item uses: "+EviLivePlugin.RISK_KEY);
    // First in "What to trade": it is the one trading choice a player makes. Compared against every
    // OTHER visible item in that section, so the check names a value and does not restate the number.
    for(Method m:EviLiveConfig.class.getDeclaredMethods()) {
      ConfigItem ci=m.getAnnotation(ConfigItem.class);
      if(ci==null||ci.hidden()||!EviLiveConfig.tradeSection.equals(ci.section())||"riskLevelV2".equals(ci.keyName()))continue;
      check(riskItem.position()<ci.position(),"Risk level must come first in \"What to trade\", but "+ci.keyName()+" ("+ci.position()+") is not after it ("+riskItem.position()+")");
    }
    // The dropdown labels and the sidebar words, as values.
    check("Low".equals(RiskLevel.LOW.toString())&&"Medium".equals(RiskLevel.MEDIUM.toString())
      &&"High".equals(RiskLevel.HIGH.toString()),"Dropdown labels are the bare names, no odds: "+java.util.Arrays.toString(RiskLevel.values()));
    check("Low".equals(RiskLevel.LOW.label())&&"Medium".equals(RiskLevel.MEDIUM.label())&&"High".equals(RiskLevel.HIGH.label()),"Button labels");
    check("low".equals(RiskLevel.LOW.param())&&"medium".equals(RiskLevel.MEDIUM.param())&&"high".equals(RiskLevel.HIGH.param()),"risk= values");

    // ------------------------------------------- 2. the request reads the NEW risk key, never the old
    // While hidden, NOTHING is sent for any stored level (section 8 covers that); these drive the dormant path.
    check("".equals(query(shown(withConfig(config(RiskLevel.HIGH,RiskLevel.LOW,false,false))))),
      "Old key HIGH, new key Low: nothing may be sent (Low is the bridge's default) -- above all not risk=high: "+query(shown(withConfig(config(RiskLevel.HIGH,RiskLevel.LOW,false,false)))));
    check("risk=medium".equals(query(shown(withConfig(config(RiskLevel.HIGH,RiskLevel.MEDIUM,false,false))))),"Old HIGH, new Medium must send risk=medium");
    check("risk=high".equals(query(shown(withConfig(config(RiskLevel.LOW,RiskLevel.HIGH,false,false))))),"Old Low, new High must send risk=high");

    // ------------------------------------- 3. market-wide reads the NEW key, in the query and the message
    check("includeMarket=1".equals(query(withConfig(config(RiskLevel.LOW,RiskLevel.LOW,false,true)))),
      "Old key false, new key true: market-wide must be asked for");
    check("".equals(query(withConfig(config(RiskLevel.LOW,RiskLevel.LOW,true,false)))),
      "Old key true, new key false: market-wide must NOT be asked for");
    // The no-suggestion message is driven from the same toggle. With the OLD key false and the NEW
    // key true under Market only, the market check ran -- so the sentence must not say it is off.
    check(("No market-wide pick passes your settings right now. \"Suggest from\" is set to Market only,"
        +" so your own trade history was not considered -- switch it to include that too.")
        .equals(diagnosis(marketOnly(false,true))),"The message must follow the NEW key (on): "+diagnosis(marketOnly(false,true)));
    check(("Market-wide suggestions are switched off and \"Suggest from\" is set to Market only, so EVI"
        +" has nowhere to look for a trade. Turn on \"Include market-wide suggestions\" to get suggestions.")
        .equals(diagnosis(marketOnly(true,false))),"The message must follow the NEW key (off): "+diagnosis(marketOnly(true,false)));

    // --------------------------------------------------------------- 4. Layout B: the three buttons
    {
      List<RiskLevel> pressed=new ArrayList<>();
      AtomicReference<EviLivePanel> ref=new AtomicReference<>();
      SwingUtilities.invokeAndWait(()->ref.set(new EviLivePanel(()->{},()->{},()->{},()->{},()->{},()->{},()->{},pressed::add)));
      EviLivePanel panel=ref.get();
      JButton low=button(panel,"Low"),medium=button(panel,"Medium"),high=button(panel,"High");
      // One row, three EQUAL columns.
      check(low.getParent()==medium.getParent()&&medium.getParent()==high.getParent(),"The three buttons must share one row");
      check(low.getParent().getLayout() instanceof GridLayout,"The row must be a GridLayout, so the three are equal widths");
      GridLayout grid=(GridLayout)low.getParent().getLayout();
      check(grid.getRows()==1&&grid.getColumns()==3,"One row of three columns, got "+grid.getRows()+"x"+grid.getColumns());
      check(low.getParent().getComponentCount()==3,"Nothing else in the row");
      SwingUtilities.invokeAndWait(()->{low.doClick();medium.doClick();high.doClick();});
      check(pressed.equals(List.of(RiskLevel.LOW,RiskLevel.MEDIUM,RiskLevel.HIGH)),"Each button must report its own level once: "+pressed);

      EviTheme.Palette p=EviTheme.palette();
      for(RiskLevel chosen:RiskLevel.values()) {
        panel.riskLevel(chosen);edt();
        for(JButton b:new JButton[]{low,medium,high}) {
          boolean isChosen=b.getText().equals(chosen.label());
          check(outline(b).equals(isChosen?p.text:p.rule),
            (isChosen?"The chosen button":"An unchosen button")+" \""+b.getText()+"\" at "+chosen.label()+" must be outlined in "+(isChosen?"the text":"the rule")+" colour, got "+outline(b));
        }
        String aim=((JTextArea)textArea(panel,chosen)).getText();
        check(chosen.aim().equals(aim),"The quiet line must state "+chosen.label()+"'s aim: "+aim);
        // Equal at EVERY level, not only after the last one: a mark that is added to the chosen button
        // and never taken off again would leave all three equal by the end of the loop and pass.
        check(low.getFont().equals(medium.getFont())&&medium.getFont().equals(high.getFont()),
          "Same font on all three at "+chosen.label()+": no bold for the chosen one");
        check(low.getBackground().equals(medium.getBackground())&&medium.getBackground().equals(high.getBackground()),
          "No button may be filled differently at "+chosen.label());
        check(low.getForeground().equals(medium.getForeground())&&medium.getForeground().equals(high.getForeground()),
          "Same text colour on all three at "+chosen.label());
      }
      check(!p.text.equals(p.rule),"The two outline colours must differ, or nothing is marked");
      // NONE filled, and nothing else distinguishes them: same face, same text colour, same font.
      check(low.getBackground().equals(medium.getBackground())&&medium.getBackground().equals(high.getBackground()),"No button may be filled differently");
      check(low.getForeground().equals(medium.getForeground())&&medium.getForeground().equals(high.getForeground()),"Same text colour on all three");
      check(low.getFont().equals(medium.getFont())&&medium.getFont().equals(high.getFont()),"Same font on all three: no bold for the chosen one");
      for(JButton b:new JButton[]{low,medium,high})
        for(Color loud:new Color[]{p.good,p.bad,p.warn,p.accent})
          check(!loud.equals(outline(b))&&!loud.equals(b.getBackground())&&!loud.equals(b.getForeground()),
            "The risk row must stay neutral -- no green, red, amber or brand colour on \""+b.getText()+"\"");
      // The exact words under the buttons.
      check("Mostly busy, high-volume items.".equals(RiskLevel.LOW.aim()),"Low's line, no odds: "+RiskLevel.LOW.aim());
      check("Adds slower, mid-priced items with thinner edges.".equals(RiskLevel.MEDIUM.aim()),"Medium's line, no odds: "+RiskLevel.MEDIUM.aim());
      check("Adds gear whose price can drop suddenly.".equals(RiskLevel.HIGH.aim()),"High's line, no odds: "+RiskLevel.HIGH.aim());
      // A theme switch must not leave the old outline colours behind.
      panel.riskLevel(RiskLevel.HIGH);edt();
      panel.applyTheme(PanelTheme.OLD_SCHOOL);edt();
      check(outline(high).equals(EviTheme.OLD_SCHOOL.text)&&outline(low).equals(EviTheme.OLD_SCHOOL.rule),"Outlines must be redrawn in the new scheme");
      panel.applyTheme(PanelTheme.RUNELITE);edt();
    }

    // The WIRING: a press goes through the plugin, writes riskLevelV2 through the config writer, the
    // panel follows, and the very next request carries the new level.
    {
      Map<String,String> stored=new HashMap<>();
      EviLivePlugin plugin=new EviLivePlugin();
      set(plugin,"config",new EviLiveConfig(){
        public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}
        public boolean includeMarketWide(){return false;}
        public RiskLevel riskLevelV2(){String v=stored.get("riskLevelV2");return v==null?RiskLevel.LOW:RiskLevel.valueOf(v);}
      });
      set(plugin,"configWriter",(java.util.function.BiConsumer<String,String>)stored::put);
      shown(plugin); // the dormant path: what the buttons do once the levels return
      AtomicReference<EviLivePanel> ref=new AtomicReference<>();
      SwingUtilities.invokeAndWait(()->ref.set(new EviLivePanel(()->{},()->{},()->{},()->{},()->{},()->{},()->{},plugin::chooseRisk)));
      EviLivePanel panel=ref.get();
      set(plugin,"panel",panel);
      check("".equals(query(plugin)),"Before any press the default (Low) sends nothing");
      SwingUtilities.invokeAndWait(()->button(panel,"High").doClick());edt();
      check(stored.equals(Map.of("riskLevelV2","HIGH")),"Pressing High must write HIGH under riskLevelV2 and nothing else: "+stored);
      check("risk=high".equals(query(plugin)),"The next request must carry risk=high: "+query(plugin));
      check(outline(button(panel,"High")).equals(EviTheme.palette().text)&&outline(button(panel,"Low")).equals(EviTheme.palette().rule),
        "After the press the panel must outline High, not Low");
      SwingUtilities.invokeAndWait(()->button(panel,"Medium").doClick());edt();
      check(stored.equals(Map.of("riskLevelV2","MEDIUM")),"Pressing Medium must overwrite it: "+stored);
      check("risk=medium".equals(query(plugin)),"...and the next request carries risk=medium: "+query(plugin));
      // A press asks for a fresh suggestion at once (like Skip), rather than leaving the old level's
      // pick on screen until the next scheduled poll.
      AtomicLong polls=new AtomicLong();
      java.util.concurrent.ScheduledThreadPoolExecutor recorder=new java.util.concurrent.ScheduledThreadPoolExecutor(1){
        @Override public void execute(Runnable r){polls.incrementAndGet();}
      };
      try {
        set(plugin,"sender",recorder);set(plugin,"running",true);
        SwingUtilities.invokeAndWait(()->button(panel,"High").doClick());edt();
        check(polls.get()==1,"A press must ask for exactly one fresh poll, got "+polls.get());
        SwingUtilities.invokeAndWait(()->button(panel,"Medium").doClick());edt();
        check(polls.get()==2,"...and again on the next press, got "+polls.get());
      } finally {
        set(plugin,"running",false);set(plugin,"sender",null);recorder.shutdown(); // never shutdownNow(): Thread.interrupt is on the Hub's forbidden list
      }
      // The other direction: chosen in RuneLite's settings panel, the sidebar must follow.
      stored.put("riskLevelV2","LOW");
      net.runelite.client.events.ConfigChanged changed=new net.runelite.client.events.ConfigChanged();
      changed.setGroup("evilive");changed.setKey("riskLevelV2");changed.setNewValue("LOW");
      plugin.onConfigChanged(changed);edt();
      check(outline(button(panel,"Low")).equals(EviTheme.palette().text)&&outline(button(panel,"Medium")).equals(EviTheme.palette().rule),
        "A level chosen in the settings panel must move the sidebar's outline to it");
    }

    // ------------------------------------------------- 5. a Skip lasts four hours, per account
    {
      FakeStore store=new FakeStore();
      AtomicLong clock=new AtomicLong(1_000_000L);
      EviLivePlugin p=skipPlugin(store,clock);
      offer(p,5);
      call(p,"skipSuggestion");
      check("5:15400000".equals(store.byProfile.get("rsprofile.accountA")),"The skip must be stored on account A, expiring 4 hours (14,400,000 ms) later: "+store.byProfile);
      check(((java.util.Set<?>)get(p,"skippedItemIds")).isEmpty(),"With an account known, a Skip must NOT go to the session set that reset() clears");
      check("exclude=5".equals(query(p)),"The skipped item must be excluded: "+query(p));
      check(p.setAsideCount()==1,"The sidebar count must include it");
      // The same item in BOTH lists (e.g. skipped, then also marked in the session) is ONE item set aside.
      ((java.util.Set<Integer>)get(p,"skippedItemIds")).add(5);
      check(p.setAsideCount()==1,"An item in both the session list and the four-hour store counts once, got "+p.setAsideCount());
      // A world hop, relog or lost connection.
      call(p,"reset");
      check("exclude=5".equals(query(p)),"A Skip must survive reset() (world hop / relog / lost connection): "+query(p));
      // A client restart: a brand-new plugin over the same per-account store.
      EviLivePlugin restarted=skipPlugin(store,clock);
      check("exclude=5".equals(query(restarted)),"A Skip must survive a client restart: "+query(restarted));
      // Per account: another account sees nothing of it, and keeps its own.
      clock.set(1_000_000L+3_600_000L);
      store.profile="rsprofile.accountB";
      check("".equals(query(restarted)),"Another account must not inherit account A's skips: "+query(restarted));
      offer(restarted,6);
      call(restarted,"skipSuggestion");
      check("exclude=6".equals(query(restarted)),"Account B's own skip: "+query(restarted));
      check("5:15400000".equals(store.byProfile.get("rsprofile.accountA"))&&"6:19000000".equals(store.byProfile.get("rsprofile.accountB")),
        "Each account keeps its own list: "+store.byProfile);
      store.profile="rsprofile.accountA";
      check("exclude=5".equals(query(restarted)),"Back on account A its skip is still there: "+query(restarted));
      // Expiry, with the injected clock: still there one millisecond before four hours, gone at four.
      clock.set(15_400_000L-1);
      check("exclude=5".equals(query(restarted)),"One millisecond short of four hours the skip still applies");
      clock.set(15_400_000L);
      check("".equals(query(restarted)),"At four hours the skip must have expired: "+query(restarted));
      check(restarted.setAsideCount()==0,"...and drop out of the sidebar count");
      // Expired entries are dropped from storage at the next write, so the stored list cannot grow.
      offer(restarted,8);
      call(restarted,"skipSuggestion");
      check("8:29800000".equals(store.byProfile.get("rsprofile.accountA")),"The expired skip must be pruned on the next write: "+store.byProfile);
      // "Show skipped items again" forgets this account's skips and only this account's.
      call(restarted,"clearSkips");
      check("".equals(query(restarted))&&!store.byProfile.containsKey("rsprofile.accountA"),"Clearing must forget account A's skips: "+store.byProfile);
      check("6:19000000".equals(store.byProfile.get("rsprofile.accountB")),"...and leave account B's alone");
      // Block stays separate and permanent-by-bridge: it never lands in the four-hour store.
      offer(restarted,9);
      set(restarted,"gson",new Gson());
      call(restarted,"blockSuggestion");
      check(!store.byProfile.containsKey("rsprofile.accountA"),"Block must not be written as a four-hour Skip: "+store.byProfile);
      check(((java.util.Set<?>)get(restarted,"skippedItemIds")).contains(9),"Block still sets the item aside at once");
      // Logged out: no account known, so the press still applies -- for the session.
      store.profile=null;
      offer(restarted,11);
      call(restarted,"skipSuggestion");
      check(((java.util.Set<?>)get(restarted,"skippedItemIds")).contains(11),"With no account known, Skip must still apply, for the session");
    }
    // ------------------------------------------- 6. the Skip button's own words, as the player reads them
    {
      AtomicReference<EviLivePanel> ref=new AtomicReference<>();
      SwingUtilities.invokeAndWait(()->ref.set(new EviLivePanel(()->{},()->{},()->{},()->{},()->{},()->{},()->{},r->{})));
      EviLivePanel panel=ref.get();
      check("Set this suggestion aside for 4 hours on this account and check for the next-best one. Does not affect any offer you have already placed."
        .equals(EviLivePanel.plainTip(button(panel,"Skip").getToolTipText())),"The Skip tooltip must promise four hours on this account: "+button(panel,"Skip").getToolTipText());
      EviLivePlugin p=skipPlugin(new FakeStore(),new AtomicLong(1_000_000L));
      set(p,"panel",panel);
      offer(p,5);
      call(p,"skipSuggestion");edt();
      check("Skipped for 4 hours. Checking for the next suggestion...".equals(((JTextArea)get(panel,"suggestion")).getText()),
        "The line shown after a Skip must say four hours: "+((JTextArea)get(panel,"suggestion")).getText());
    }

    // --------------------------- 7. the PRODUCTION paths, through a real RuneLite ConfigManager
    // Everything above swaps in a writer or a store. This drives the code that ships: chooseRisk()
    // with no configWriter, the plugin's own SkipMemory store, and the config proxy RuneLite builds
    // from EviLiveConfig's annotations -- so a keyName typo, a wrong group or a profile guard that
    // reads another account would all fail here and nowhere else.
    {
      net.runelite.client.eventbus.EventBus bus=new net.runelite.client.eventbus.EventBus();
      ConfigManager cm=realConfigManager(bus);
      EviLiveConfig real=cm.getConfig(EviLiveConfig.class);
      check(real.riskLevelV2()==RiskLevel.LOW,"Through RuneLite's own proxy, an untouched install must be at Low: "+real.riskLevelV2());
      check(real.includeMarketWide(),"Through RuneLite's own proxy, market-wide must be on for an untouched install");
      // An existing user: High and market-wide off stored under the RETIRED keys, as an older plugin left them.
      cm.setConfiguration("evilive","riskLevel","HIGH");
      cm.setConfiguration("evilive","includeMarketSuggestions","false");
      check(real.riskLevelV2()==RiskLevel.LOW&&real.includeMarketWide(),"Old stored values must not reach the new keys: "+real.riskLevelV2()+"/"+real.includeMarketWide());
      EviLivePlugin plugin=new EviLivePlugin();
      set(plugin,"config",real);
      set(plugin,"configManager",cm);
      AtomicReference<EviLivePanel> ref=new AtomicReference<>();
      SwingUtilities.invokeAndWait(()->ref.set(new EviLivePanel(()->{},()->{},()->{},()->{},()->{},()->{},()->{},plugin::chooseRisk)));
      EviLivePanel panel=ref.get();
      set(plugin,"panel",panel);
      bus.register(plugin);
      shown(plugin); // the dormant path through the real ConfigManager; section 8 drives the hidden one
      Map<String,String> q=params(query(plugin));
      check(!q.containsKey("risk")&&"1".equals(q.get("includeMarket")),"An existing user with old High/off must ask at Low with market-wide on: "+q);
      // A press, with NO test writer: the real ConfigManager must now hold HIGH under riskLevelV2.
      SwingUtilities.invokeAndWait(()->button(panel,"High").doClick());edt();
      check("HIGH".equals(cm.getConfiguration("evilive","riskLevelV2")),"A press must store HIGH under evilive.riskLevelV2: "+cm.getConfiguration("evilive","riskLevelV2"));
      check("HIGH".equals(cm.getConfiguration("evilive","riskLevel")),"...and leave the retired key exactly as it was");
      check(real.riskLevelV2()==RiskLevel.HIGH,"The settings panel (the same proxy) must now read High: "+real.riskLevelV2());
      check("high".equals(params(query(plugin)).get("risk")),"The next request must carry risk=high: "+query(plugin));
      // Chosen in RuneLite's settings panel: ConfigManager posts ConfigChanged, the sidebar follows.
      cm.setConfiguration("evilive","riskLevelV2",RiskLevel.MEDIUM);edt();
      check(outline(button(panel,"Medium")).equals(EviTheme.palette().text)&&outline(button(panel,"High")).equals(EviTheme.palette().rule),
        "A level set through ConfigManager must move the sidebar's outline to Medium");
      check("medium".equals(params(query(plugin)).get("risk")),"...and reach the request: "+query(plugin));

      // The plugin's OWN skip store over RuneLite's per-account configuration.
      Field key=ConfigManager.class.getDeclaredField("rsProfileKey");key.setAccessible(true);
      key.set(cm,"rsprofile.aaaa");
      set(plugin,"suggestionCache",new SuggestionCache());
      offer(plugin,5);
      long before=System.currentTimeMillis();
      call(plugin,"skipSuggestion");
      long after=System.currentTimeMillis();
      String stored=cm.getRSProfileConfiguration("evilive","skippedUntil");
      Map<Integer,Long> parsed=SkipMemory.parse(stored);
      check(parsed.size()==1&&parsed.containsKey(5),"The Skip must be stored in account aaaa's profile as item 5: "+stored);
      check(parsed.get(5)>=before+14_400_000L&&parsed.get(5)<=after+14_400_000L,"...expiring four hours after the press: "+stored);
      check(((java.util.Set<?>)get(plugin,"skippedItemIds")).isEmpty(),"With a profile known, the Skip must not fall back to the session set");
      check("5".equals(params(query(plugin)).get("exclude")),"The skipped item must be excluded: "+query(plugin));
      // Another account logs in: nothing of aaaa's shows.
      key.set(cm,"rsprofile.bbbb");
      check(!params(query(plugin)).containsKey("exclude"),"Account bbbb must not inherit aaaa's skip: "+query(plugin));
      check(cm.getRSProfileConfiguration("evilive","skippedUntil")==null,"bbbb's profile must hold nothing");
      // THE GUARD: a read or write addressed to a profile that is no longer the current one must do
      // nothing -- RuneLite's RS-profile calls always hit the CURRENT account, so without it a write
      // meant for aaaa would land in bbbb.
      SkipMemory.Store realStore=(SkipMemory.Store)get(get(plugin,"skipMemory"),"store");
      realStore.write("rsprofile.aaaa","7:1");
      check(cm.getRSProfileConfiguration("evilive","skippedUntil")==null,"A write for aaaa while bbbb is current must not land in bbbb: "+cm.getRSProfileConfiguration("evilive","skippedUntil"));
      key.set(cm,"rsprofile.aaaa");
      check(stored.equals(cm.getRSProfileConfiguration("evilive","skippedUntil")),"...nor change aaaa's own value: "+cm.getRSProfileConfiguration("evilive","skippedUntil"));
      check(realStore.read("rsprofile.bbbb")==null,"A read for bbbb while aaaa is current must return nothing, not aaaa's list");
      check(stored.equals(realStore.read("rsprofile.aaaa")),"A read for the current profile returns its list");
      // "Show skipped items again" removes the key from aaaa's profile entirely.
      call(plugin,"clearSkips");
      check(cm.getRSProfileConfiguration("evilive","skippedUntil")==null,"Clearing must unset aaaa's stored skips: "+cm.getRSProfileConfiguration("evilive","skippedUntil"));
      // Logged out: RuneLite has no profile, so the press lasts the session.
      key.set(cm,null);
      offer(plugin,11);
      call(plugin,"skipSuggestion");
      check(((java.util.Set<?>)get(plugin,"skippedItemIds")).contains(11),"With no RuneLite profile, Skip must still apply, for the session");
      check(!nowhere.exists(),"The test ConfigManager must never write a file: "+nowhere);
    }

    // The stored format is read leniently: a damaged entry is dropped, never fatal.
    check(SkipMemory.parse("5:100,bad,7:x,:3,-1:5, 9 : 200 ").equals(Map.of(5,100L,9,200L)),"Lenient parse: "+SkipMemory.parse("5:100,bad,7:x,:3,-1:5, 9 : 200 "));
    check(SkipMemory.SKIP_MILLIS==14_400_000L,"A Skip lasts exactly four hours");

    hiddenLevels();

    System.out.println("PASS: risk level and market-wide on NEW keyNames (Low and on by default, old items hidden with their keys and types, never read by the query or the no-suggestion message); Layout B (three equal buttons in one GridLayout row, chosen one outlined only, neutral colours, the aim line, a press writing riskLevelV2 and reaching the next request); Skip kept four hours per account across reset() and a restart, expiring on the injected clock, pruned on write, separate from Block, session fallback with no account; the Skip tooltip and message say four hours; and through a REAL RuneLite ConfigManager: old stored values ignored, a press stores riskLevelV2, a settings change moves the outline, the per-account skip store guarded against a changed profile; Risk level first in its section, a press asking for exactly one fresh poll, and an item in both set-aside lists counted once");
  }

  static EviLivePlugin withConfig(EviLiveConfig c)throws Exception {EviLivePlugin p=new EviLivePlugin();set(p,"config",c);return p;}
  /** The DORMANT path: a plugin that behaves as it will once RiskLevel.SHOWN is true again. */
  static EviLivePlugin shown(EviLivePlugin p)throws Exception {set(p,"riskLevelsShown",true);return p;}

  /** What an untouched install sends: market-wide on, the 25% trade-size cap, nothing else. Recorded as a LITERAL
   *  (the request a default player's bridge has received since the 6 Oct keys), so the hidden levels are proven
   *  byte-identical for a default player rather than re-derived from the code under test. */
  static final String DEFAULT_QUERY="includeMarket=1&stackShare=25";

  /** A count written in digits or in words, either side of an odds phrase. */
  static final String ODDS_NUM="(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty|fifty|a\\s+hundred|hundred)";
  /** Matches odds in every form the 7 Oct 2026 rule covers -- no odds anywhere in the plugin, permanently (a FAQ
   *  explains them instead):
   *   - "N in M" / "N out of M", digits or words on either side, spaces or hyphens between, an optional "every"
   *     ("9 out of 10", "nine out of ten", "three in four", "one in every ten", "1-in-10");
   *   - a bare "1 in" / "1-in" ("roughly 1 in");
   *   - "1:N" written as a ratio ("1:10"), but not a ternary's "? 1 : 0" or a time like "11:30";
   *   - "N/M" or "N:M" followed by what the odds are OF ("1/10 trades lose", "3/4 times") -- a bare fraction such
   *     as Sizing's "1/24" stays legal, because it is arithmetic, not odds. */
  static final java.util.regex.Pattern ODDS=java.util.regex.Pattern.compile("(?i)"
    +"(?<![\\w.])"+ODDS_NUM+"[\\s-]*(?:in|out[\\s-]+of)[\\s-]*(?:every[\\s-]+)?"+ODDS_NUM+"(?!\\w)"
    +"|(?<![\\w.])1[\\s-]+in\\b"
    +"|(?<![\\w.:/?])1:(?:[2-9]|\\d{2,})(?![\\w.:])"
    +"|(?<![\\w./:])\\d+\\s*[/:]\\s*\\d+[\\s-]+(?:trades?|flips?|los\\w*|times|chances?|odds)\\b");

  /** Every file under the plugin's main source (and the Hub listing beside it) that carries odds text, as "file:line: text". */
  static List<String> oddsInSource(java.nio.file.Path root)throws Exception {
    List<String> hits=new ArrayList<>();
    List<java.nio.file.Path> files=new ArrayList<>();
    try(java.util.stream.Stream<java.nio.file.Path> s=java.nio.file.Files.walk(root)){s.filter(java.nio.file.Files::isRegularFile).forEach(files::add);}
    java.nio.file.Path listing=root.getParent()==null?null:root.getParent().getParent()==null?null:root.getParent().getParent().resolve("runelite-plugin.properties");
    if(listing!=null&&java.nio.file.Files.isRegularFile(listing))files.add(listing);
    for(java.nio.file.Path f:files){
      List<String> lines=java.nio.file.Files.readAllLines(f,java.nio.charset.StandardCharsets.UTF_8);
      for(int i=0;i<lines.size();i++)if(ODDS.matcher(lines.get(i)).find())hits.add(f+":"+(i+1)+": "+lines.get(i).trim());
    }
    return hits;
  }

  /** Whether a component would be drawn: it and every parent up to the sidebar itself are visible. */
  static boolean drawn(Component c,EviLivePanel panel){
    for(Component x=c;x!=null;x=x.getParent()){if(!x.isVisible())return false;if(x==panel)return true;}
    return false;
  }

  /** 8. THE HIDDEN STATE, the maintainer's 7 Oct 2026 decision: no risk section on screen, no odds anywhere, the request unchanged. */
  static void hiddenLevels()throws Exception {
    check(!RiskLevel.SHOWN,"The risk levels must be hidden in this release (RiskLevel.SHOWN false)");

    // (a) The sidebar as startUp() draws it, from the stored settings -- even with High and No limit stored.
    EviLivePlugin p=new EviLivePlugin();
    set(p,"config",new EviLiveConfig(){public RiskLevel riskLevelV2(){return RiskLevel.HIGH;}public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}});
    AtomicReference<EviLivePanel> ref=new AtomicReference<>();
    SwingUtilities.invokeAndWait(()->ref.set(p.buildPanel()));edt();
    EviLivePanel panel=ref.get();
    List<Component> all=new ArrayList<>();visit(panel,all);
    List<String> drawnRisk=new ArrayList<>();
    for(Component c:all){
      if(!drawn(c,panel))continue;
      String text=c instanceof JButton?((JButton)c).getText():c instanceof javax.swing.JLabel?((javax.swing.JLabel)c).getText():c instanceof JTextArea?((JTextArea)c).getText():null;
      if(text==null)continue;
      if(text.equals("Risk level")||text.equals("Low")||text.equals("Medium")||text.equals("High")||ODDS.matcher(text).find()
        ||text.contains(RiskLevel.NO_LIMIT_AT_HIGH)||text.equals(RiskLevel.HIGH.aim()))drawnRisk.add(c.getClass().getSimpleName()+" \""+text+"\"");
      if(c instanceof javax.swing.JComponent){String tip=EviLivePanel.plainTip(((javax.swing.JComponent)c).getToolTipText());if(tip!=null&&ODDS.matcher(tip).find())drawnRisk.add("tooltip \""+tip+"\"");}
    }
    check(drawnRisk.isEmpty(),"Nothing of the risk levels may be drawn in the sidebar: "+drawnRisk);
    // The section is still BUILT (the switch brings it back whole) -- rule, heading, row, line -- and every part is hidden.
    List<?> section=(List<?>)get(panel,"riskSection");
    check(section.size()==4,"The Risk level section must be its rule, heading, button row and line: "+section.size());
    for(Object c:section)check(!((Component)c).isVisible(),"Every part of the Risk level section must be hidden: "+c);
    check(section.contains(button(panel,"High").getParent())&&section.contains(get(panel,"riskAim")),"The hidden parts must include the button row and the line under it");

    // (b) No odds anywhere in the plugin's main source, permanently -- after the scan proves it catches them.
    for(String bad:new String[]{"Low (~1 in 10 lose)","Aims for about 1 in 4 losing.","EVI aims for about 1 in 6 at Medium","one in ten trades","9 out of 10 trades","roughly 1 in",
        "safe 9 out of 10 times","nine out of ten trades","three in four lose","Three In Four","one in every ten","1 in every 4","1-in-10","a 1-in-6 chance",
        "two-in-five","1/10 trades lose","3/4 times","1 / 4 lose","1:10","odds of 1:6","1:4 trades","twenty out of a hundred","nine out of every ten"})
      check(ODDS.matcher(bad).find(),"The odds scan must catch: "+bad);
    for(String good:new String[]{"Low","Mostly busy, high-volume items.","slots 60-62","v1.13.1 in 2026","Total 1,396,000 gp","item 4151 index 3",
        "VOLUME_SHARE_PER_HOUR, 1/24, as the same double.","return d > 0 ? 1 : d < 0 ? -1 : 0;","append(members?1:0)","int w=wide?1:2;","at 11:30 and 14:50",
        "none in stock","often in the list","x/2 + y","hours < 10 ? 1 : 0","a positive one in"})
      check(!ODDS.matcher(good).find(),"The odds scan must not flag: "+good);
    String mainSource=System.getProperty("evi.mainSource");
    check(mainSource!=null,"riskSkipTest must be given evi.mainSource (build.gradle), or the source scan reads nothing");
    java.nio.file.Path root=java.nio.file.Paths.get(mainSource);
    check(java.nio.file.Files.isRegularFile(root.resolve("java/com/evi/live/RiskLevel.java")),"The scan must be pointed at src/main: "+root);
    List<String> hits=oddsInSource(root);
    check(hits.isEmpty(),"No odds text (\"1 in X\") may appear anywhere in the plugin's main source: "+hits);

    // (c) The request: byte-identical to what a default player has always sent, and NO stored level changes it.
    check(DEFAULT_QUERY.equals(query(withConfig(new EviLiveConfig(){}))),"An untouched install must send exactly "+DEFAULT_QUERY+": "+query(withConfig(new EviLiveConfig(){})));
    for(RiskLevel stored:RiskLevel.values())for(RiskLevel old:RiskLevel.values()){
      String q=query(withConfig(new EviLiveConfig(){public RiskLevel riskLevelV2(){return stored;}public RiskLevel riskLevel(){return old;}}));
      check(DEFAULT_QUERY.equals(q),"While hidden, a stored "+stored.name()+" (old key "+old.name()+") must send exactly the default request: "+q);
    }
    // Through a REAL RuneLite ConfigManager, a High stored while the buttons were visible, and a press on the hidden
    // buttons (unreachable on screen, driven here): the request still carries no risk=.
    net.runelite.client.eventbus.EventBus bus=new net.runelite.client.eventbus.EventBus();
    ConfigManager cm=realConfigManager(bus);
    cm.setConfiguration("evilive","riskLevelV2","HIGH");
    EviLiveConfig real=cm.getConfig(EviLiveConfig.class);
    check(real.riskLevelV2()==RiskLevel.HIGH,"setup: High must be stored: "+real.riskLevelV2());
    EviLivePlugin plugin=withConfig(real);
    set(plugin,"configManager",cm);
    check(DEFAULT_QUERY.equals(query(plugin)),"A High stored under riskLevelV2 must not reach the request while hidden: "+query(plugin));
    plugin.chooseRisk(RiskLevel.MEDIUM);
    check("MEDIUM".equals(cm.getConfiguration("evilive","riskLevelV2"))&&DEFAULT_QUERY.equals(query(plugin)),"Even a press must leave the request at the default while hidden: "+query(plugin));
    check(!nowhere.exists(),"The test ConfigManager must never write a file: "+nowhere);
    System.out.println("PASS: risk levels HIDDEN (section built but not drawn, settings item hidden, no odds in any label, line, tooltip or file under src/main, the request byte-identical to a default player's for every stored level, through a real ConfigManager too)");
  }
  static EviLiveConfig marketOnly(boolean oldMarket,boolean newMarket) {
    return new EviLiveConfig(){
      public MaxTradeShare maxTradeShare(){return MaxTradeShare.OFF;}
      public boolean includeMarketSuggestions(){return oldMarket;}
      public boolean includeMarketWide(){return newMarket;}
      public SuggestionSource suggestionSource(){return SuggestionSource.MARKET_ONLY;}
    };
  }
  /** Drives the REAL pollSuggestion() against a bridge that answers 200 with nothing, and returns what
   *  the sidebar then says -- the message's wiring, not the pure function. */
  static String diagnosis(EviLiveConfig c)throws Exception {
    Method poll=EviLivePlugin.class.getDeclaredMethod("pollSuggestion",long.class);poll.setAccessible(true);
    EviLivePlugin p=new EviLivePlugin();
    set(p,"gson",new Gson());set(p,"config",c);
    set(p,"running",true);set(p,"lifecycle",1L);
    set(p,"suggestionCache",new SuggestionCache());
    set(p,"openItemPriceCache",new OpenItemPriceCache());
    AtomicReference<EviLivePanel> ref=new AtomicReference<>();
    SwingUtilities.invokeAndWait(()->ref.set(new EviLivePanel(()->{},()->{},()->{},()->{},()->{})));
    set(p,"panel",ref.get());
    set(p,"answers",(AnswerSource)q->"{\"suggestion\":null}");
    poll.invoke(p,1L);
    edt();
    return ((JTextArea)get(ref.get(),"suggestion")).getText();
  }
  /** A query string as a map, so a check names the parameter it means rather than the whole string. */
  static Map<String,String> params(String q) {
    Map<String,String> out=new HashMap<>();
    if(q==null||q.isEmpty())return out;
    for(String part:q.split("&")){int eq=part.indexOf('=');out.put(eq<0?part:part.substring(0,eq),eq<0?"":part.substring(eq+1));}
    return out;
  }
  /** Where the test ConfigManager's two ConfigData objects point. It never exists: ConfigData only
   *  READS its file in the constructor (a missing one is an empty config) and keeps every change in
   *  memory until RuneLite's own save, which nothing here calls. Section 7 asserts it stays absent. */
  static final java.io.File nowhere=new java.io.File(System.getProperty("java.io.tmpdir"),"evi-riskskip-"+System.nanoTime()+"-never-written.properties");
  /** A REAL RuneLite ConfigManager (1.13.1), built without its injected constructor -- which wants a
   *  live client, a profile manager over ~/.runelite and a session manager. Allocated empty, then given
   *  only what setConfiguration / getConfiguration / the RS-profile calls / getConfig actually touch.
   *  Every method then runs RuneLite's own code. If a RuneLite update renames one of these private
   *  fields this fails loudly with NoSuchFieldException: re-read ConfigManager with javap and update
   *  the names here; do not delete the section, it is the only test of the shipping write paths. */
  static ConfigManager realConfigManager(net.runelite.client.eventbus.EventBus bus)throws Exception {
    check(!nowhere.exists(),"Test config path unexpectedly exists: "+nowhere);
    Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");
    Field theUnsafe=unsafeClass.getDeclaredField("theUnsafe");theUnsafe.setAccessible(true);
    ConfigManager cm=(ConfigManager)unsafeClass.getMethod("allocateInstance",Class.class).invoke(theUnsafe.get(null),ConfigManager.class);
    Constructor<?> data=Class.forName("net.runelite.client.config.ConfigData").getDeclaredConstructor(java.io.File.class);
    data.setAccessible(true);
    setField(cm,"configProfile",data.newInstance(nowhere));
    setField(cm,"rsProfileConfigProfile",data.newInstance(nowhere));
    setField(cm,"eventBus",bus);
    setField(cm,"serializers",new HashMap<>());
    Constructor<?> handler=Class.forName("net.runelite.client.config.ConfigInvocationHandler").getDeclaredConstructor(ConfigManager.class);
    handler.setAccessible(true);
    setField(cm,"handler",handler.newInstance(cm));
    return cm;
  }
  static void setField(ConfigManager cm,String name,Object value)throws Exception {
    Field f=ConfigManager.class.getDeclaredField(name);f.setAccessible(true);f.set(cm,value);
  }
  /** The aim line under the buttons. */
  static Object textArea(EviLivePanel panel,RiskLevel unused)throws Exception {return get(panel,"riskAim");}
}
