package com.evi.live;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Exercises sidebar controls without RuneLite login or real key files. */
public final class EviLivePanelTest {
  private static void visit(Container parent,List<Component> result) {
    for(Component child:parent.getComponents()) {
      result.add(child);
      if(child instanceof Container)visit((Container)child,result);
    }
  }
  public static void main(String[] args) throws Exception {
    AtomicReference<EviLivePanel> built=new AtomicReference<>();
    SwingUtilities.invokeAndWait(()->{
      java.util.concurrent.atomic.AtomicInteger skips=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger personalUses=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger notHelds=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger blocks=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger resets=new java.util.concurrent.atomic.AtomicInteger();
      final EviLivePanel panel=new EviLivePanel(skips::incrementAndGet,personalUses::incrementAndGet,notHelds::incrementAndGet,blocks::incrementAndGet,resets::incrementAndGet);
      List<Component> components=new ArrayList<>();visit(panel,components);
      if(components.stream().anyMatch(x->x instanceof javax.swing.JPasswordField))throw new AssertionError("4.0.0: no key field -- there is nothing to pair");
      List<JButton> buttons=components.stream().filter(x->x instanceof JButton).map(x->(JButton)x).collect(java.util.stream.Collectors.toList());
      if(buttons.stream().anyMatch(b->b.getText()!=null&&b.getText().toLowerCase().contains("pair")))throw new AssertionError("4.0.0: no pairing button");
      JButton skipButton=buttons.stream().filter(b->"Skip".equals(b.getText())).findFirst().orElseThrow();
      JButton personalUseButton=buttons.stream().filter(b->"Mine".equals(b.getText())).findFirst().orElseThrow();
      // 4.0.0: the sidebar's title is the plugin's name, ASCII (the bridge-era "EVI Live . Local" carried a non-ASCII dot)
      if(!labels(panel).contains("EVI Live")||labels(panel).stream().anyMatch(t->t!=null&&t.contains("Local")))throw new AssertionError("The title must read \"EVI Live\": "+labels(panel));
      for(Component c:components) {
        String text=c instanceof javax.swing.JLabel?((javax.swing.JLabel)c).getText():c instanceof javax.swing.text.JTextComponent?((javax.swing.text.JTextComponent)c).getText():c instanceof JButton?((JButton)c).getText():null;
        String tip=c instanceof javax.swing.JComponent?((javax.swing.JComponent)c).getToolTipText():null;
        String access=c instanceof javax.swing.JComponent&&c.getAccessibleContext()!=null?c.getAccessibleContext().getAccessibleDescription():null;
        for(String s:new String[]{text,tip,access}) {
          if(s==null)continue;
          for(String bad:new String[]{"bridge","Bridge","companion","dashboard","scanner","127.0.0.1","pairing"})
            if(s.contains(bad))throw new AssertionError("The sidebar names \""+bad+"\": "+s);
        }
      }
      if(!"Starting up.".equals(components.stream().filter(c->c instanceof JTextArea).map(c->((JTextArea)c).getText()).filter(t->t.startsWith("Starting")).findFirst().orElse(null)))
        throw new AssertionError("The status line's first words are \"Starting up.\"");
      if(!"Waiting for your trade record.".equals(profitText(panel)))throw new AssertionError("The profit line's first words: "+profitText(panel));
      if(EviLivePanel.icon().getWidth()!=24)throw new AssertionError("Sidebar icon dimensions");
      skipButton.doClick();
      if(skips.get()!=1)throw new AssertionError("Skip callback must fire exactly once per click");
      personalUseButton.doClick();
      if(personalUses.get()!=1)throw new AssertionError("Personal-use callback must fire exactly once per click");
      JButton notHeldButton=buttons.stream().filter(b->"Gone".equals(b.getText())).findFirst().orElseThrow();
      notHeldButton.doClick();
      if(notHelds.get()!=1)throw new AssertionError("Not-held callback must fire exactly once per click");
      // Block: Skip made permanent, so a player can refuse an item without looking up its ID.
      JButton blockButton=buttons.stream().filter(b->"Block".equals(b.getText())).findFirst().orElseThrow(()->new AssertionError("The sidebar needs a Block button"));
      blockButton.doClick();
      if(blocks.get()!=1)throw new AssertionError("Block callback must fire exactly once per click");
      // The profit line and its Reset: asked for so the sidebar shows what EVI has actually realised.
      JButton resetButton=buttons.stream().filter(b->"Reset profit count".equals(b.getText())).findFirst().orElseThrow(()->new AssertionError("The sidebar needs a profit Reset button"));
      resetButton.doClick();
      if(resets.get()!=1)throw new AssertionError("Reset callback must fire exactly once per click");

      // Active offers list: every occupied slot gets its own row, terminal-but-uncollected included.
      EviLivePlugin.OfferRow buying=new EviLivePlugin.OfferRow(0,2,150,1000,2500,true,"Steel cannonball","BUYING");
      EviLivePlugin.OfferRow sold=new EviLivePlugin.OfferRow(3,4151,1500000,1,1,false,"Abyssal whip","SOLD");
      EviLivePlugin.OfferRow cancelled=new EviLivePlugin.OfferRow(5,11,97,0,300,true,"","CANCELLED_BUY");
      panel.renderOffers(List.of(buying,sold,cancelled));
      List<String> labels=labels(panel);
      // Built with the same %,d the panel uses, so the grouping separator follows the machine's locale (e.g. 4.000 on a Dutch system).
      for(String expected:List.of("BUY  Steel cannonball",String.format("%,d / %,d at %,d gp - Buying",1000,2500,150),"SELL  Abyssal whip",String.format("1 / 1 at %,d gp - Sold, collect",1500000),"BUY  item 11","0 / 300 at 97 gp - Cancelled, collect"))
        if(!labels.contains(expected))throw new AssertionError("Active offers list is missing \""+expected+"\"; got "+labels);
      if(EviLivePanel.offerStatus(new EviLivePlugin.OfferRow(1,1,1,0,1,false,"x","CANCELLED_SELL")).equals("Cancelled, collect")==false)throw new AssertionError("CANCELLED_SELL status text");
      panel.renderOffers(List.of());
      if(labels(panel).stream().anyMatch(l->l.startsWith("BUY ")||l.startsWith("SELL ")))throw new AssertionError("An empty offer list must remove every previous row");
      List<Component> afterClear=new ArrayList<>();visit(panel,afterClear);
      if(afterClear.stream().noneMatch(c->c instanceof javax.swing.JTextArea && "No offers in the Grand Exchange.".equals(((javax.swing.JTextArea)c).getText())))throw new AssertionError("An empty offer list must say so");

      // Scrolling: reported from the live client, the sidebar could not stay scrolled to the top
      // because every text area's caret followed each 2-second rewrite and scrolled to it. No
      // sidebar text area may move its caret on an update.
      for(Component c:afterClear) {
        if(!(c instanceof javax.swing.JTextArea))continue;
        javax.swing.text.Caret caret=((javax.swing.JTextArea)c).getCaret();
        if(!(caret instanceof javax.swing.text.DefaultCaret)||((javax.swing.text.DefaultCaret)caret).getUpdatePolicy()!=javax.swing.text.DefaultCaret.NEVER_UPDATE)
          throw new AssertionError("A sidebar text area still moves its caret on update, which scrolls the sidebar: \""+((javax.swing.JTextArea)c).getText()+"\"");
      }
      // And the same text re-sent by the poll must not be rewritten at all.
      javax.swing.JTextArea probe=new javax.swing.JTextArea("same");
      if(EviLivePanel.setIfChanged(probe,"same"))throw new AssertionError("Unchanged text must not be rewritten");
      if(!EviLivePanel.setIfChanged(probe,"different")||!"different".equals(probe.getText()))throw new AssertionError("Changed text must be written");
      if(!EviLivePanel.setIfChanged(probe,null)||!"".equals(probe.getText()))throw new AssertionError("null clears the text rather than throwing");

      built.set(panel);
    });

    // The profit line: the journal's own matched-flip total, what it leaves out said beside it, and an
    // unreadable answer admitted rather than shown as zero.
    final EviLivePanel profitPanel=built.get();
    EviLivePlugin.Profit p=new EviLivePlugin.Profit();
    p.gp=1234567;p.trades=41;p.winners=29;p.losers=12;p.unmatchedSales=9;p.openPositions=3;
    profitPanel.profit(p);
    SwingUtilities.invokeAndWait(()->{});
    String line=profitText(profitPanel);
    javax.swing.JLabel figure=profitFigure(profitPanel);
    if(figure==null||!("+"+String.format("%,d",1234567)+" gp").equals(figure.getText()))
      throw new AssertionError("The realised figure belongs in its own label: "+(figure==null?"absent":figure.getText()));
    // Green up. Asked for on 29 Sept so the total reads at a glance.
    if(!EviTheme.palette().good.equals(figure.getForeground()))
      throw new AssertionError("A profit must be coloured as one: "+figure.getForeground());
    if(!line.contains("everything EVI has matched"))throw new AssertionError("Without a reset it must say what it covers: "+line);
    if(!line.contains("41 trades: 29 up, 12 down"))throw new AssertionError("Trades and the split belong on the line: "+line);
    if(!line.contains("9 sales EVI never saw bought")||!line.contains("3 purchases not yet sold"))
      throw new AssertionError("What the total leaves out must be stated, never folded in: "+line);
    EviLivePlugin.Profit since=new EviLivePlugin.Profit();
    since.since=1758000000000L;since.gp=-5000;since.trades=1;since.losers=1;
    profitPanel.profit(since);
    SwingUtilities.invokeAndWait(()->{});
    String reset=profitText(profitPanel);
    javax.swing.JLabel lossFigure=profitFigure(profitPanel);
    if(lossFigure==null||!(String.format("%,d",-5000)+" gp").equals(lossFigure.getText()))
      throw new AssertionError("A loss is not dressed up: "+(lossFigure==null?"absent":lossFigure.getText()));
    if(!EviTheme.palette().bad.equals(lossFigure.getForeground()))
      throw new AssertionError("A loss must be coloured as one, never green: "+lossFigure.getForeground());
    if(!reset.startsWith("(since you reset"))throw new AssertionError("A reset count says so: "+reset);
    if(reset.contains("Not counted"))throw new AssertionError("With nothing left out, the caveat must not appear: "+reset);
    profitPanel.profit(null);
    SwingUtilities.invokeAndWait(()->{});
    if(!profitText(profitPanel).contains("Profit unavailable"))throw new AssertionError("An unreadable figure must be admitted, not shown as zero");
    profitPanel.profitResetPending();
    SwingUtilities.invokeAndWait(()->{});
    if(!profitText(profitPanel).contains("Counting from now"))throw new AssertionError("Reset needs immediate feedback while the poll catches up");

    // Colour scheme: switching it repaints the panel in place -- a cosmetic setting that needed a
    // client restart would read as the setting not working. Each step is checked in a later EDT turn,
    // because applyTheme queues its repaint rather than painting inside the caller's own turn.
    EviLivePanel panel=built.get();
    panel.applyTheme(PanelTheme.OLD_SCHOOL);
    panel.offers(java.util.Arrays.asList(new EviLivePlugin.OfferRow(0,2,100,5,10,true,"Test item","BUYING")));
    SwingUtilities.invokeAndWait(()->{
      if(!panel.getBackground().equals(EviTheme.OLD_SCHOOL.background))throw new AssertionError("The panel must repaint in the chosen scheme");
      List<Component> themed=new ArrayList<>();visit(panel,themed);
      if(themed.stream().noneMatch(c->c instanceof javax.swing.JPanel && c.getBackground().equals(EviTheme.OLD_SCHOOL.card)))
        throw new AssertionError("An offer row rebuilt after the switch must use the chosen scheme too");
    });
    panel.applyTheme(PanelTheme.RUNELITE);
    SwingUtilities.invokeAndWait(()->{
      if(!panel.getBackground().equals(EviTheme.RUNELITE.background))throw new AssertionError("Switching back must restore RuneLite's own colours");
    });
    // The suggestion card (layout D). The sidebar used to state a number and bury whether EVI trusted
    // it: a player bought a batch of an item expecting the profit the quoted spread implied, when it
    // was worth about a tenth of that -- and EVI had demoted the pick and said so, in
    // the middle of a paragraph. These check that the doubt is now drawn, and that a bridge which sends
    // no verdict still gets the old paragraph.
    Suggestion clear=new Suggestion();
    clear.itemId=11832;clear.name="Bandos chestplate";clear.action="buy";clear.quantity=1;
    clear.buyPrice=20000000;clear.sellPrice=21000000;clear.expectedProfit=580000L;
    clear.verdict=new Suggestion.Verdict();
    clear.verdict.level="clear";clear.verdict.label="Every check passed";
    clear.verdict.checks=new ArrayList<>();
    Suggestion.Check passed=new Suggestion.Check();passed.ok=Boolean.TRUE;passed.text="Buyers paying more than quoted";
    clear.verdict.checks.add(passed);
    panel.suggestion(clear,"fallback prose that must not be shown");
    SwingUtilities.invokeAndWait(()->{
      List<Component> card=new ArrayList<>();visit(panel,card);
      String all=card.stream().filter(c->c instanceof JTextArea||c instanceof javax.swing.JLabel)
        .map(c->c instanceof JTextArea?((JTextArea)c).getText():((javax.swing.JLabel)c).getText())
        .collect(java.util.stream.Collectors.joining("\n"));
      if(all.contains("fallback prose"))throw new AssertionError("A verdict must replace the paragraph, not sit beside it");
      if(!all.contains("Bandos chestplate"))throw new AssertionError("The card must name the item");
      if(!all.contains("+"+String.format("%,d",580000L)))throw new AssertionError("The card must lead with what the trade is worth");
      if(!all.contains("BUY "+String.format("%,d",1)))throw new AssertionError("The card must say what it is asking for");
      if(!all.contains("EVERY CHECK PASSED"))throw new AssertionError("The verdict label must show");
      if(!all.contains("Buyers paying more than quoted"))throw new AssertionError("Each check line must show");
    });

    // A flagged pick: the edge, the label and the failing line all have to carry the warning colour,
    // and the failing line needs its own mark -- colour alone is not a distinction everyone can see.
    Suggestion flagged=new Suggestion();
    flagged.itemId=536;flagged.name="Dragon bones";flagged.action="buy";flagged.quantity=500;
    flagged.buyPrice=3000;flagged.sellPrice=3400;flagged.expectedProfit=166000L;
    flagged.verdict=new Suggestion.Verdict();
    flagged.verdict.level="caution";flagged.verdict.label="Worth less than it looks";
    flagged.verdict.checks=new ArrayList<>();
    Suggestion.Check failed=new Suggestion.Check();failed.ok=Boolean.FALSE;failed.text="16,600 at what buyers really pay";
    flagged.verdict.checks.add(failed);
    panel.suggestion(flagged,"prose");
    SwingUtilities.invokeAndWait(()->{
      List<Component> card=new ArrayList<>();visit(panel,card);
      boolean marked=card.stream().anyMatch(c->c instanceof JTextArea
        && ((JTextArea)c).getText().startsWith("! ")
        && ((JTextArea)c).getText().contains("16,600"));
      if(!marked)throw new AssertionError("A failed check needs a mark of its own, not colour alone");
      boolean warned=card.stream().anyMatch(c->c instanceof javax.swing.JLabel
        && "WORTH LESS THAN IT LOOKS".equals(((javax.swing.JLabel)c).getText())
        && c.getForeground().equals(EviTheme.RUNELITE.warn));
      if(!warned)throw new AssertionError("A cautioned pick must be coloured as one");
    });

    // No verdict -- an older bridge, or a pick nothing was measured about -- falls back to the prose. Since
    // 7 Oct 2026 a BUY paragraph ends with its total cost on a line of its own (the maintainer's decision: every buy
    // states its total), so the paragraph that comes back is the prose plus that line.
    Suggestion bare=new Suggestion();
    bare.itemId=1;bare.name="Thing";bare.action="buy";bare.quantity=1;bare.buyPrice=10;bare.sellPrice=20;
    panel.suggestion(bare,"Thing x1 - buy 10 gp / sell 20 gp");
    SwingUtilities.invokeAndWait(()->{
      List<Component> back=new ArrayList<>();visit(panel,back);
      boolean prose=back.stream().anyMatch(c->c instanceof JTextArea
        && "Thing x1 - buy 10 gp / sell 20 gp\nTotal 10 gp".equals(((JTextArea)c).getText()));
      if(!prose)throw new AssertionError("Without a verdict the paragraph must come back");
      boolean leftover=back.stream().anyMatch(c->c instanceof javax.swing.JLabel
        && "Dragon bones".equals(((javax.swing.JLabel)c).getText()));
      if(leftover)throw new AssertionError("The previous card must be cleared away, not left underneath");
    });

    // The status line moved to the bottom and carries a dot: green once the OSRS Wiki prices have arrived.
    panel.status("Connected to OSRS Wiki prices. Last update: 12:00:00");
    SwingUtilities.invokeAndWait(()->{
      List<Component> lit=new ArrayList<>();visit(panel,lit);
      boolean green=lit.stream().anyMatch(c->c instanceof javax.swing.JPanel
        &&c.getBackground()!=null&&c.getBackground().equals(EviTheme.RUNELITE.good));
      if(!green)throw new AssertionError("Prices that have arrived should show as connected");
    });
    panel.status(EviLivePlugin.IN_PROCESS_WAITING);
    SwingUtilities.invokeAndWait(()->{
      List<Component> dark=new ArrayList<>();visit(panel,dark);
      boolean warned=dark.stream().anyMatch(c->c instanceof javax.swing.JPanel
        &&c.getBackground()!=null&&c.getBackground().equals(EviTheme.RUNELITE.warn));
      if(!warned)throw new AssertionError("Prices still on their way must not look connected");
    });

    // The session's set-aside list, which until 28 Sept 2026 was invisible and one-way. It is fed by
    // four buttons and one automatic path and only ever cleared on a client restart, so a player
    // could work down from a pick worth a few hundred thousand gp to one worth a few hundred with nothing on screen
    // admitting why. Hidden at zero so it costs nothing until it is actually shaping the answer.
    {
      final int[] cleared={0};
      EviLivePanel sp=new EviLivePanel(()->{},()->{},()->{},()->{},()->{},()->cleared[0]++);
      sp.skipped(0);SwingUtilities.invokeAndWait(()->{});
      if(labels(sp).stream().anyMatch(t->t!=null&&t.contains("set aside")))
        throw new AssertionError("Nothing set aside must show no line at all");
      sp.skipped(1);SwingUtilities.invokeAndWait(()->{});
      if(labels(sp).stream().noneMatch(t->t!=null&&t.equals("1 item set aside")))
        throw new AssertionError("One item must read in the singular: "+labels(sp));
      sp.skipped(7);SwingUtilities.invokeAndWait(()->{});
      if(labels(sp).stream().noneMatch(t->t!=null&&t.equals("7 items set aside")))
        throw new AssertionError("The count must be shown: "+labels(sp));
      // And the way back has to actually be reachable and wired.
      List<Component> spAll=new ArrayList<>();visit(sp,spAll);
      javax.swing.JButton back=spAll.stream().filter(c->c instanceof javax.swing.JButton)
        .map(c->(javax.swing.JButton)c).filter(b->"Show skipped items again".equals(b.getText()))
        .findFirst().orElseThrow(()->new AssertionError("No way to undo the session's own filter"));
      if(!back.isVisible())throw new AssertionError("The undo must be visible once something is set aside");
      back.doClick();SwingUtilities.invokeAndWait(()->{});
      if(cleared[0]!=1)throw new AssertionError("The undo button must call back exactly once, got "+cleared[0]);
      sp.skipped(0);SwingUtilities.invokeAndWait(()->{});
      if(back.isVisible())throw new AssertionError("It must disappear again once nothing is set aside");
    }

    // HOLDING LINES (8 Oct 2026, the maintainer's approved mock): a line naming its lot opens a menu -- Personal use, then I don't have this
    // anymore, drawn alike -- and each item hands that line to the plugin; every other line is drawn exactly as before, no menu.
    {
      EviLivePanel hp=new EviLivePanel(()->{},()->{},()->{},()->{},()->{},()->{});
      List<EviLivePlugin.AdviceCard> chosenUse=new ArrayList<>(), chosenGone=new ArrayList<>();
      hp.onHoldingLine(chosenUse::add,chosenGone::add);
      EviLivePlugin.AdviceCard mist=new EviLivePlugin.AdviceCard("info","Holding","Mist rune","2,000 · +1,234 after tax","Bought for 400,000. Listed because you own it, not as advice to sell.",22,"a1-4");
      EviLivePlugin.AdviceCard teak=new EviLivePlugin.AdviceCard("caution","Holding, outcome unknown","Teak logs","9,384 · last listed at 141","Bought for 1,000,000. EVI last saw it listed at 141 gp.",6333,"a1-9");
      EviLivePlugin.AdviceCard oldBridge=new EviLivePlugin.AdviceCard("info","Holding","Yew logs","20 · +300 after tax","Bought for 5,600.");
      EviLivePlugin.AdviceCard offer=new EviLivePlugin.AdviceCard("warn","Below break-even","Varrock teleport","200 @ 1,500","The market is now BELOW your break-even.");
      hp.advice(List.of(offer,mist,oldBridge,teak));
      SwingUtilities.invokeAndWait(()->{});
      List<Component> all=new ArrayList<>();visit(hp,all);
      List<javax.swing.JPanel> cards=all.stream().filter(c->c instanceof javax.swing.JPanel&&"card".equals(((javax.swing.JPanel)c).getClientProperty("eviRole")))
        .filter(c->((javax.swing.JPanel)c).getComponentCount()>0&&((javax.swing.JPanel)c).getComponent(0) instanceof javax.swing.JLabel
          &&List.of("Varrock teleport","Mist rune","Yew logs","Teak logs").contains(((javax.swing.JLabel)((javax.swing.JPanel)c).getComponent(0)).getText()))
        .map(c->(javax.swing.JPanel)c).collect(java.util.stream.Collectors.toList());
      if(cards.size()!=4)throw new AssertionError("four advice cards: "+cards.size());
      javax.swing.JPanel offerCard=cards.get(0),mistCard=cards.get(1),oldCard=cards.get(2),teakCard=cards.get(3);
      if(mistCard.getClientProperty(EviLivePanel.HOLDING_LINE)!=mist||teakCard.getClientProperty(EviLivePanel.HOLDING_LINE)!=teak)
        throw new AssertionError("each holding line carries its own card, an ended-unseen one too");
      if(offerCard.getClientProperty(EviLivePanel.HOLDING_LINE)!=null||oldCard.getClientProperty(EviLivePanel.HOLDING_LINE)!=null)
        throw new AssertionError("an offer note, and a holding line with no lot (an older bridge), are not position lines: no menu");
      // the click: a listener on the two lines and on no other card (each card also has the tooltip manager's own)
      int base=offerCard.getMouseListeners().length;
      if(oldCard.getMouseListeners().length!=base||mistCard.getMouseListeners().length!=base+1||teakCard.getMouseListeners().length!=base+1)
        throw new AssertionError("a click listener on exactly the lines with a lot");
      // the tooltip: the line's sentence, then that it can be clicked; every other card keeps its sentence alone
      if(!EviLivePanel.plainTip(mistCard.getToolTipText()).equals(mist.message+" "+EviLivePanel.LINE_MENU_HINT)||!EviLivePanel.plainTip(teakCard.getToolTipText()).equals(teak.message+" "+EviLivePanel.LINE_MENU_HINT))
        throw new AssertionError("the line's tooltip says it can be clicked: "+mistCard.getToolTipText());
      if(!EviLivePanel.plainTip(offerCard.getToolTipText()).equals(offer.message)||!EviLivePanel.plainTip(oldCard.getToolTipText()).equals(oldBridge.message))
        throw new AssertionError("other cards' tooltips are unchanged");
      if(!EviLivePanel.LINE_MENU_HINT.equals("Click this line for Personal use or I don't have this anymore."))throw new AssertionError("the hint's wording");
      // the line looks exactly as before: name, LABEL, figures, the same border and cursor as a card with no menu
      List<String> mistText=new ArrayList<>();for(Component c:mistCard.getComponents())mistText.add(((javax.swing.JLabel)c).getText());
      if(!mistText.equals(List.of("Mist rune","HOLDING","2,000 · +1,234 after tax")))throw new AssertionError("the line's text is unchanged: "+mistText);
      if(!mistCard.getBorder().getClass().equals(oldCard.getBorder().getClass())||mistCard.getCursor().getType()!=oldCard.getCursor().getType())
        throw new AssertionError("drawn like any other card");
      // a click while the sidebar is not on screen opens nothing and throws nothing; a click alone chooses nothing
      mistCard.dispatchEvent(new java.awt.event.MouseEvent(mistCard,java.awt.event.MouseEvent.MOUSE_CLICKED,0L,0,5,5,1,false,java.awt.event.MouseEvent.BUTTON1));
      mistCard.dispatchEvent(new java.awt.event.MouseEvent(mistCard,java.awt.event.MouseEvent.MOUSE_CLICKED,0L,0,5,5,1,false,java.awt.event.MouseEvent.BUTTON3));
      if(!chosenUse.isEmpty()||!chosenGone.isEmpty())throw new AssertionError("a click alone chooses nothing");
      // the menu: two items, in order, alike in every respect but their words; nothing preselected
      SwingUtilities.invokeAndWait(()->{
        javax.swing.JPopupMenu menu=hp.holdingMenu(mist);
        if(menu.getComponentCount()!=2)throw new AssertionError("two items: "+menu.getComponentCount());
        javax.swing.JMenuItem use=(javax.swing.JMenuItem)menu.getComponent(0),gone=(javax.swing.JMenuItem)menu.getComponent(1);
        if(!"Personal use".equals(use.getText())||!"I don't have this anymore".equals(gone.getText()))
          throw new AssertionError("Personal use, then I don't have this anymore: "+use.getText()+" / "+gone.getText());
        if(use.getClass()!=javax.swing.JMenuItem.class||gone.getClass()!=javax.swing.JMenuItem.class||use.getIcon()!=null||gone.getIcon()!=null
          ||!use.getFont().equals(gone.getFont())||!use.getForeground().equals(gone.getForeground())||!use.getBackground().equals(gone.getBackground())
          ||use.isOpaque()!=gone.isOpaque()||!use.isEnabled()||!gone.isEnabled()||use.isArmed()||gone.isArmed()||use.isSelected()||gone.isSelected()
          ||menu.getSelectionModel().isSelected())
          throw new AssertionError("the two items are drawn alike and neither is highlighted");
        if(!EviLivePanel.PERSONAL_USE_TIP.equals(EviLivePanel.plainTip(use.getToolTipText()))||!EviLivePanel.GONE_TIP.equals(EviLivePanel.plainTip(gone.getToolTipText())))
          throw new AssertionError("each item explains itself as the card's button does");
        use.doClick();
        if(!chosenUse.equals(List.of(mist))||!chosenGone.isEmpty())throw new AssertionError("Personal use hands over that line: "+chosenUse);
        ((javax.swing.JMenuItem)hp.holdingMenu(teak).getComponent(1)).doClick();
        if(!chosenGone.equals(List.of(teak))||chosenUse.size()!=1)throw new AssertionError("Gone hands over that line: "+chosenGone);
      });
      // the card's own buttons say the same as the menu items when they apply
      hp.actions(false,false,true,true,false);
      SwingUtilities.invokeAndWait(()->{});
      List<Component> afterActions=new ArrayList<>();visit(hp,afterActions);
      List<JButton> row=afterActions.stream().filter(x->x instanceof JButton).map(x->(JButton)x).collect(java.util.stream.Collectors.toList());
      if(!EviLivePanel.PERSONAL_USE_TIP.equals(EviLivePanel.plainTip(row.stream().filter(b->"Mine".equals(b.getText())).findFirst().orElseThrow().getToolTipText()))
        ||!EviLivePanel.GONE_TIP.equals(EviLivePanel.plainTip(row.stream().filter(b->"Gone".equals(b.getText())).findFirst().orElseThrow().getToolTipText())))
        throw new AssertionError("the card's buttons keep their tooltips");
      // a new lot on an otherwise identical line is redrawn (the menu must act on the lot now named)
      EviLivePlugin.AdviceCard mist2=new EviLivePlugin.AdviceCard(mist.level,mist.label,mist.name,mist.figures,mist.message,22,"a1-5");
      hp.advice(List.of(offer,mist2,oldBridge,teak));
      SwingUtilities.invokeAndWait(()->{});
      List<Component> redrawn=new ArrayList<>();visit(hp,redrawn);
      if(redrawn.stream().noneMatch(c->c instanceof javax.swing.JPanel&&((javax.swing.JPanel)c).getClientProperty(EviLivePanel.HOLDING_LINE)==mist2))
        throw new AssertionError("a changed lot redraws the line");
    }

    // CRASH NOTES, SPLIT (8 Oct 2026, the maintainer: "too much to fully even read" -- one tooltip line of 400-550 characters). A note
    // with a detail shows its SHORT line on the card, under the figures, and the detail is the tooltip, wrapped to a fixed
    // width; a note without one is drawn exactly as before. And no tooltip anywhere on the sidebar is one long line.
    {
      EviLivePanel cp=new EviLivePanel(()->{},()->{},()->{},()->{},()->{},()->{});
      String crashLine="Giant seaweed is crashing: buyers pay about 44 gp, 36% under its 24-hour average of 69 gp. Your buy for 20,945 is still running (11,000 bought).";
      String detail="52,181 traded in the last 30 minutes (normally about 11,154 an hour). Cause unknown. Of 432 similar crashes in items under 10k (90 days), 57% had made back most of the drop a day later and 6% were lower still. History, not a forecast.";
      EviLivePlugin.AdviceCard crash=new EviLivePlugin.AdviceCard("warn","Crashing","Giant seaweed","One of yours",crashLine,21504,null,detail);
      EviLivePlugin.AdviceCard plain=new EviLivePlugin.AdviceCard("caution","Market moved away","Nature rune","1,110 yours · 1,000 market",
        "29.3% over the going rate, 100 still unsold. Past 1%, asks have taken 6-7h to sell in EVI's measurements, and a third never did. Hold or cut -- EVI won't choose for you.");
      cp.advice(List.of(crash,plain));
      SwingUtilities.invokeAndWait(()->{});
      List<Component> all=new ArrayList<>();visit(cp,all);
      javax.swing.JPanel crashCard=null,plainCard=null;
      for(Component c:all)if(c instanceof javax.swing.JPanel&&"card".equals(((javax.swing.JPanel)c).getClientProperty("eviRole"))&&((javax.swing.JPanel)c).getComponentCount()>0
        &&((javax.swing.JPanel)c).getComponent(0) instanceof javax.swing.JLabel) {
        String n=((javax.swing.JLabel)((javax.swing.JPanel)c).getComponent(0)).getText();
        if("Giant seaweed".equals(n))crashCard=(javax.swing.JPanel)c;
        if("Nature rune".equals(n))plainCard=(javax.swing.JPanel)c;
      }
      if(crashCard==null||plainCard==null)throw new AssertionError("both cards are drawn");
      if(crashCard.getComponentCount()!=4||!(crashCard.getComponent(3) instanceof JTextArea))throw new AssertionError("the split note's line sits under name, label and figures");
      JTextArea shown=(JTextArea)crashCard.getComponent(3);
      if(!crashLine.equals(shown.getText())||shown.getClientProperty(EviLivePanel.CARD_LINE)!=Boolean.TRUE)throw new AssertionError("the card shows the short line: "+shown.getText());
      if(!shown.getLineWrap()||!shown.getWrapStyleWord()||shown.isEditable())throw new AssertionError("the line wraps by word and is read-only");
      if(((javax.swing.text.DefaultCaret)shown.getCaret()).getUpdatePolicy()!=javax.swing.text.DefaultCaret.NEVER_UPDATE)throw new AssertionError("its caret is pinned");
      if(shown.getForeground().equals(net.runelite.client.ui.ColorScheme.PROGRESS_COMPLETE_COLOR))throw new AssertionError("never green");
      String tip=crashCard.getToolTipText();
      if(!tip.startsWith("<html><body style='width:"+EviLivePanel.TIP_WIDTH+"px'>")||!detail.equals(EviLivePanel.plainTip(tip)))
        throw new AssertionError("the tooltip is the detail, wrapped to a fixed width: "+tip);
      if(!tip.equals(shown.getToolTipText()))throw new AssertionError("hovering the line shows the same tooltip as the card");
      if(EviLivePanel.plainTip(tip).contains(crashLine))throw new AssertionError("the tooltip does not repeat the line");
      if(plainCard.getComponentCount()!=3)throw new AssertionError("a note with no detail is drawn exactly as before: "+plainCard.getComponentCount());
      if(!plain.message.equals(EviLivePanel.plainTip(plainCard.getToolTipText()))||!plainCard.getToolTipText().startsWith("<html>"))
        throw new AssertionError("a long sentence with no detail is its own tooltip, wrapped");
      // escaping: markup in the text is shown, never read
      if(!"a < b & c > d, and some more words to pass the plain limit here".equals(EviLivePanel.plainTip(EviLivePanel.wrapTip("a < b & c > d, and some more words to pass the plain limit here")))
        ||EviLivePanel.wrapTip("a < b & c > d, and some more words to pass the plain limit here").contains("a < b")
        ||!EviLivePanel.wrapTip("a < b & c > d, and some more words to pass the plain limit here").contains("a &lt; b &amp; c &gt; d"))throw new AssertionError("tooltip text is escaped");
      if(!"Skip".equals(EviLivePanel.wrapTip("Skip"))||EviLivePanel.wrapTip(null)!=null)throw new AssertionError("a short tooltip stays plain");
      // a changed detail alone redraws the card (the redraw signature carries it)
      cp.advice(List.of(new EviLivePlugin.AdviceCard("warn","Crashing","Giant seaweed","One of yours",crashLine,21504,null,detail+" Changed."),plain));
      SwingUtilities.invokeAndWait(()->{});
      List<Component> redrawn=new ArrayList<>();visit(cp,redrawn);
      if(redrawn.stream().noneMatch(c->c instanceof JTextArea&&((JTextArea)c).getClientProperty(EviLivePanel.CARD_LINE)!=null
        &&(detail+" Changed.").equals(EviLivePanel.plainTip(((JTextArea)c).getToolTipText()))))throw new AssertionError("a new detail redraws the card");
      // every tooltip the sidebar carries -- each row of actions (Block on a buy), a blocked row, an extra pick with its reasoning --
      // is short, or wrapped (Block's tooltip is BLOCK_TIP_HERE on a buy)
      cp.actions(true,false,true,true,true);
      cp.blockedItems(List.of(new EviLivePanel.BlockedRow(4151,"Abyssal whip")));
      Suggestion extra=new Suggestion();
      extra.itemId=565;extra.name="Blood rune";extra.action="buy";extra.quantity=100;extra.buyPrice=400;extra.sellPrice=430;
      extra.reasoning="Buyers have paid at least this much in most of the last 336 hours; this is history, not a forecast, and nothing here promises a fill.";
      cp.alsoSuggested(List.of(extra));
      SwingUtilities.invokeAndWait(()->{});
      List<Component> beforeDim=new ArrayList<>();visit(cp,beforeDim);
      for(Component c:beforeDim)if(c instanceof javax.swing.JComponent&&((javax.swing.JComponent)c).getToolTipText()!=null) {
        String t=((javax.swing.JComponent)c).getToolTipText();
        if(t.length()>EviLivePanel.TIP_PLAIN_MAX&&!t.startsWith("<html><body style='width:"))throw new AssertionError("a one-line tooltip of "+t.length()+" characters: "+t);
      }
      if(beforeDim.stream().noneMatch(c->c instanceof javax.swing.JComponent&&extra.reasoning.equals(EviLivePanel.plainTip(((javax.swing.JComponent)c).getToolTipText()))))
        throw new AssertionError("the extra pick's reasoning is its tooltip");
      if(beforeDim.stream().noneMatch(c->c instanceof javax.swing.JComponent&&EviLivePanel.BLOCK_TIP_HERE.equals(EviLivePanel.plainTip(((javax.swing.JComponent)c).getToolTipText()))
        &&((javax.swing.JComponent)c).getToolTipText().startsWith("<html>")))throw new AssertionError("Block's tooltip, wrapped");
      cp.actions(false,true,false,false,false);
      SwingUtilities.invokeAndWait(()->{});
      List<Component> every=new ArrayList<>();visit(cp,every);
      int tips=0;
      for(Component c:every)if(c instanceof javax.swing.JComponent) {
        String t=((javax.swing.JComponent)c).getToolTipText();
        if(t==null)continue;
        tips++;
        if(t.length()>EviLivePanel.TIP_PLAIN_MAX&&!t.startsWith("<html><body style='width:"))throw new AssertionError("a one-line tooltip of "+t.length()+" characters: "+t);
      }
      for(javax.swing.JMenuItem m:new javax.swing.JMenuItem[]{(javax.swing.JMenuItem)cp.holdingMenu(crash).getComponent(0),(javax.swing.JMenuItem)cp.holdingMenu(crash).getComponent(1)})
        if(!m.getToolTipText().startsWith("<html>"))throw new AssertionError("the crashLine menu's tooltips are wrapped too");
      if(tips<8)throw new AssertionError("too few tooltips seen to prove anything: "+tips);
    }

    // The trade-log invitation (9 Oct 2026, approved layout B): the LAST thing in the sidebar, after the status strip and its
    // notes; hidden until the plugin shows it; the approved words; two ORDINARY buttons side by side (no accent outline, nothing
    // filled); each calls its own action once; the muted line in the muted colour; every text area's caret pinned.
    {
      AtomicReference<EviLivePanel> sref=new AtomicReference<>();
      java.util.concurrent.atomic.AtomicInteger saves=new java.util.concurrent.atomic.AtomicInteger(),laters=new java.util.concurrent.atomic.AtomicInteger();
      SwingUtilities.invokeAndWait(()->{EviLivePanel sp=new EviLivePanel(()->{},()->{},()->{},()->{});sp.onShare(saves::incrementAndGet,laters::incrementAndGet);sref.set(sp);});
      EviLivePanel sp=sref.get();
      SwingUtilities.invokeAndWait(()->{});
      if(sp.shareSection.isVisible())throw new AssertionError("the invitation is hidden until the plugin shows it");
      Container content=(Container)sp.getComponent(0);
      if(content.getComponent(content.getComponentCount()-1)!=sp.shareSection)throw new AssertionError("the invitation is the very last thing in the sidebar");
      List<Component> order=java.util.Arrays.asList(content.getComponents());
      if(order.indexOf(sp.shareSection)<order.indexOf(sp.backfillNote)||order.indexOf(sp.backfillNote)<order.indexOf(sp.importNote))
        throw new AssertionError("after the status strip, the import line and the price-history line");
      sp.shareInvite(true);SwingUtilities.invokeAndWait(()->{});
      if(!sp.shareSection.isVisible())throw new AssertionError("shareInvite(true) shows it");
      List<Component> share=new ArrayList<>();visit(sp.shareSection,share);
      List<String> texts=new ArrayList<>();
      for(Component c:share)if(c instanceof JTextArea){
        texts.add(((JTextArea)c).getText());
        if(((javax.swing.text.DefaultCaret)((JTextArea)c).getCaret()).getUpdatePolicy()!=javax.swing.text.DefaultCaret.NEVER_UPDATE)throw new AssertionError("the invitation's text pins its caret");
      }
      if(!texts.equals(List.of("Want to help improve EVI? Your logs will make a difference!","Not now hides this until the next EVI update.")))
        throw new AssertionError("the invitation's words: "+texts);
      List<JButton> sb=share.stream().filter(c->c instanceof JButton).map(c->(JButton)c).collect(java.util.stream.Collectors.toList());
      if(sb.size()!=2||!"Save my trade log...".equals(sb.get(0).getText())||!"Not now".equals(sb.get(1).getText()))throw new AssertionError("the two buttons, in order");
      if(sb.get(0).getParent()!=sb.get(1).getParent()||!(sb.get(0).getParent().getLayout() instanceof javax.swing.BoxLayout))throw new AssertionError("side by side in one row");
      for(JButton b:sb)if(!"button".equals(b.getClientProperty("eviRole")))throw new AssertionError("an ordinary button, never the accent-outlined primary: "+b.getText());
      JTextArea note=(JTextArea)share.stream().filter(c->c instanceof JTextArea&&((JTextArea)c).getText().startsWith("Not now")).findFirst().orElseThrow();
      if(!"muted".equals(note.getClientProperty("eviRole")))throw new AssertionError("the line under the buttons is muted");
      SwingUtilities.invokeAndWait(()->sb.get(0).doClick());
      if(saves.get()!=1||laters.get()!=0)throw new AssertionError("Save my trade log... calls its action once");
      SwingUtilities.invokeAndWait(()->sb.get(1).doClick());
      if(saves.get()!=1||laters.get()!=1)throw new AssertionError("Not now calls its action once");
      sp.shareInvite(false);SwingUtilities.invokeAndWait(()->{});
      if(sp.shareSection.isVisible())throw new AssertionError("shareInvite(false) hides the whole block");
    }

    System.out.println("PASS: the trade-log invitation (last in the sidebar, hidden until shown, its words, two ordinary buttons side by side, each calling its action, the muted line); a split crash note shows its short line on the card and its detail as a width-wrapped tooltip, and every sidebar tooltip is short or wrapped; holding lines open Personal use / I don't have this anymore (in order, drawn alike, nothing preselected) for their own lot, with a tooltip saying so, and no other line does; the session set-aside count and its undo button; the profit line (the figure in its own label, coloured green up and red down, trades, what it leaves out, a reset count, an unreadable answer, and reset feedback), the five-icon action row (each caption, its callback, and the row replacing the old button stack), the colour-scheme switch repainting in place (including rows rebuilt afterwards), no pairing field or button and no bridge-era wording, the title and the first status and profit lines, the skip-suggestion and block button callbacks, the personal-use and not-held button callbacks, the Active offers list (one row per occupied slot including uncollected/cancelled ones, status text, and clearing), the scroll fix (no sidebar text area moves its caret, unchanged text is never rewritten), the suggestion card (item, worth, verdict label and check lines; a failed check marked as well as coloured; and the paragraph coming back when the engine sends no verdict), and the status dot tracking the Wiki prices");
  }
  /** The profit line's current text: the one text area that starts with a figure or its own status. */
  private static String profitText(Container panel) {
    List<Component> all=new ArrayList<>();visit(panel,all);
    return all.stream().filter(c->c instanceof javax.swing.JTextArea).map(c->((javax.swing.JTextArea)c).getText())
      .filter(t->t.startsWith("(")||t.startsWith("Profit unavailable")||t.startsWith("Counting from now")||t.startsWith("Waiting for your trade record"))
      .findFirst().orElse("");
  }

  /** The realised figure, which is now a bold JLabel of its own so it can carry the up/down colour
   *  without painting the "Not counted" caveats beside it the same shade. */
  private static javax.swing.JLabel profitFigure(Container panel) {
    List<Component> all=new ArrayList<>();visit(panel,all);
    return all.stream().filter(c->c instanceof javax.swing.JLabel).map(c->(javax.swing.JLabel)c)
      .filter(l->l.getText()!=null&&l.getText().endsWith(" gp"))
      .findFirst().orElse(null);
  }

  private static List<String> labels(Container panel) {
    List<Component> all=new ArrayList<>();visit(panel,all);
    return all.stream().filter(c->c instanceof javax.swing.JLabel).map(c->((javax.swing.JLabel)c).getText()).collect(java.util.stream.Collectors.toList());
  }
}
