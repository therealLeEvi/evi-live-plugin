package com.evi.live;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JPasswordField;
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
      AtomicReference<String> saved=new AtomicReference<>();
      java.util.concurrent.atomic.AtomicInteger skips=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger personalUses=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger notHelds=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger blocks=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger resets=new java.util.concurrent.atomic.AtomicInteger();
      final EviLivePanel panel=new EviLivePanel(saved::set,skips::incrementAndGet,personalUses::incrementAndGet,notHelds::incrementAndGet,blocks::incrementAndGet,resets::incrementAndGet);
      List<Component> components=new ArrayList<>();visit(panel,components);
      JPasswordField field=(JPasswordField)components.stream().filter(x->x instanceof JPasswordField).findFirst().orElseThrow();
      List<JButton> buttons=components.stream().filter(x->x instanceof JButton).map(x->(JButton)x).collect(java.util.stream.Collectors.toList());
      JButton pairButton=buttons.stream().filter(b->"Save pairing key".equals(b.getText())).findFirst().orElseThrow();
      JButton skipButton=buttons.stream().filter(b->"Skip".equals(b.getText())).findFirst().orElseThrow();
      JButton personalUseButton=buttons.stream().filter(b->"Mine".equals(b.getText())).findFirst().orElseThrow();
      String synthetic="abcdef0123456789".repeat(4);
      field.setText(synthetic);pairButton.doClick();
      if(!synthetic.equals(saved.get()))throw new AssertionError("Pairing callback did not receive key");
      if(field.getPassword().length!=0)throw new AssertionError("Pairing input must be cleared after save");
      if(field.getEchoChar()==0)throw new AssertionError("Key input must be masked");
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
      EviLivePlugin.OfferRow buying=new EviLivePlugin.OfferRow(0,2,243,4000,11000,true,"Steel cannonball","BUYING");
      EviLivePlugin.OfferRow sold=new EviLivePlugin.OfferRow(3,4151,1500000,1,1,false,"Abyssal whip","SOLD");
      EviLivePlugin.OfferRow cancelled=new EviLivePlugin.OfferRow(5,11,97,0,300,true,"","CANCELLED_BUY");
      panel.renderOffers(List.of(buying,sold,cancelled));
      List<String> labels=labels(panel);
      // Built with the same %,d the panel uses, so the grouping separator follows the machine's locale (e.g. 4.000 on a Dutch system).
      for(String expected:List.of("BUY  Steel cannonball",String.format("%,d / %,d at %,d gp - Buying",4000,11000,243),"SELL  Abyssal whip",String.format("1 / 1 at %,d gp - Sold, collect",1500000),"BUY  item 11","0 / 300 at 97 gp - Cancelled, collect"))
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

    // The profit line: the bridge's own matched-flip total, what it leaves out said beside it, and an
    // unreadable answer admitted rather than shown as zero.
    final EviLivePanel profitPanel=built.get();
    EviLivePlugin.Profit p=new EviLivePlugin.Profit();
    p.gp=2020133;p.trades=43;p.winners=30;p.losers=13;p.unmatchedSales=17;p.openPositions=4;
    profitPanel.profit(p);
    SwingUtilities.invokeAndWait(()->{});
    String line=profitText(profitPanel);
    javax.swing.JLabel figure=profitFigure(profitPanel);
    if(figure==null||!("+"+String.format("%,d",2020133)+" gp").equals(figure.getText()))
      throw new AssertionError("The realised figure belongs in its own label: "+(figure==null?"absent":figure.getText()));
    // Green up. Asked for by novi on 29 Sept so the total reads at a glance.
    if(!EviTheme.palette().good.equals(figure.getForeground()))
      throw new AssertionError("A profit must be coloured as one: "+figure.getForeground());
    if(!line.contains("everything EVI has matched"))throw new AssertionError("Without a reset it must say what it covers: "+line);
    if(!line.contains("43 trades: 30 up, 13 down"))throw new AssertionError("Trades and the split belong on the line: "+line);
    if(!line.contains("17 sales EVI never saw bought")||!line.contains("4 purchases not yet sold"))
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
    // it: a player bought 30 Contract of Glyphic Attenuation expecting the 2,977,560 gp the quoted
    // spread implied, when it was worth about 350,000 -- and EVI had demoted the pick and said so, in
    // the middle of a paragraph. These check that the doubt is now drawn, and that a bridge which sends
    // no verdict still gets the old paragraph.
    Suggestion clear=new Suggestion();
    clear.itemId=28919;clear.name="Tonalztics of Ralos";clear.action="buy";clear.quantity=1;
    clear.buyPrice=47451388;clear.sellPrice=49666111;clear.expectedProfit=1221401L;
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
      if(!all.contains("Tonalztics of Ralos"))throw new AssertionError("The card must name the item");
      if(!all.contains("+"+String.format("%,d",1221401L)))throw new AssertionError("The card must lead with what the trade is worth");
      if(!all.contains("BUY "+String.format("%,d",1)))throw new AssertionError("The card must say what it is asking for");
      if(!all.contains("EVERY CHECK PASSED"))throw new AssertionError("The verdict label must show");
      if(!all.contains("Buyers paying more than quoted"))throw new AssertionError("Each check line must show");
    });

    // A flagged pick: the edge, the label and the failing line all have to carry the warning colour,
    // and the failing line needs its own mark -- colour alone is not a distinction everyone can see.
    Suggestion flagged=new Suggestion();
    flagged.itemId=30810;flagged.name="Contract of Glyphic Attenuation";flagged.action="buy";flagged.quantity=30;
    flagged.buyPrice=354488;flagged.sellPrice=463000;flagged.expectedProfit=2977560L;
    flagged.verdict=new Suggestion.Verdict();
    flagged.verdict.level="caution";flagged.verdict.label="Worth less than it looks";
    flagged.verdict.checks=new ArrayList<>();
    Suggestion.Check failed=new Suggestion.Check();failed.ok=Boolean.FALSE;failed.text="349,560 at what buyers really pay";
    flagged.verdict.checks.add(failed);
    panel.suggestion(flagged,"prose");
    SwingUtilities.invokeAndWait(()->{
      List<Component> card=new ArrayList<>();visit(panel,card);
      boolean marked=card.stream().anyMatch(c->c instanceof JTextArea
        && ((JTextArea)c).getText().startsWith("! ")
        && ((JTextArea)c).getText().contains("349,560"));
      if(!marked)throw new AssertionError("A failed check needs a mark of its own, not colour alone");
      boolean warned=card.stream().anyMatch(c->c instanceof javax.swing.JLabel
        && "WORTH LESS THAN IT LOOKS".equals(((javax.swing.JLabel)c).getText())
        && c.getForeground().equals(EviTheme.RUNELITE.warn));
      if(!warned)throw new AssertionError("A cautioned pick must be coloured as one");
    });

    // No verdict -- an older bridge, or a pick nothing was measured about -- falls back to the prose.
    Suggestion bare=new Suggestion();
    bare.itemId=1;bare.name="Thing";bare.action="buy";bare.quantity=1;bare.buyPrice=10;bare.sellPrice=20;
    panel.suggestion(bare,"Thing x1 - buy 10 gp / sell 20 gp");
    SwingUtilities.invokeAndWait(()->{
      List<Component> back=new ArrayList<>();visit(panel,back);
      boolean prose=back.stream().anyMatch(c->c instanceof JTextArea
        && "Thing x1 - buy 10 gp / sell 20 gp".equals(((JTextArea)c).getText()));
      if(!prose)throw new AssertionError("Without a verdict the paragraph must come back");
      boolean leftover=back.stream().anyMatch(c->c instanceof javax.swing.JLabel
        && "Contract of Glyphic Attenuation".equals(((javax.swing.JLabel)c).getText()));
      if(leftover)throw new AssertionError("The previous card must be cleared away, not left underneath");
    });

    // Pairing collapses once a key is held. The sidebar is 225px wide, and setup that is finished is
    // the cheapest thing on it to give up -- but it has to come back if the key is ever cleared, or a
    // player who loses one has no way to enter another.
    panel.paired(true);
    SwingUtilities.invokeAndWait(()->{
      List<Component> after=new ArrayList<>();visit(panel,after);
      boolean fieldShowing=after.stream().anyMatch(c->c instanceof JPasswordField&&c.isShowing());
      if(fieldShowing)throw new AssertionError("A paired sidebar must not keep showing the key field");
      boolean saveShowing=after.stream().anyMatch(c->c instanceof JButton
        &&"Save pairing key".equals(((JButton)c).getText())&&c.isShowing());
      if(saveShowing)throw new AssertionError("The save button goes with it");
    });
    panel.paired(false);
    SwingUtilities.invokeAndWait(()->{
      List<Component> back=new ArrayList<>();visit(panel,back);
      boolean field=back.stream().anyMatch(c->c instanceof JPasswordField&&c.getParent()!=null&&c.getParent().isVisible());
      if(!field)throw new AssertionError("Clearing a key must bring the pairing fields back");
    });
    panel.paired(true);

    // The status line moved to the bottom and carries a dot: green once the bridge has answered.
    panel.status("Connected to the local bridge. Last delivery: 12:00:00");
    SwingUtilities.invokeAndWait(()->{
      List<Component> lit=new ArrayList<>();visit(panel,lit);
      boolean green=lit.stream().anyMatch(c->c instanceof javax.swing.JPanel
        &&c.getBackground()!=null&&c.getBackground().equals(EviTheme.RUNELITE.good));
      if(!green)throw new AssertionError("A connected bridge should show as connected");
    });
    panel.status("Bridge unavailable. Start EVI; queued observations will retry automatically.");
    SwingUtilities.invokeAndWait(()->{
      List<Component> dark=new ArrayList<>();visit(panel,dark);
      boolean warned=dark.stream().anyMatch(c->c instanceof javax.swing.JPanel
        &&c.getBackground()!=null&&c.getBackground().equals(EviTheme.RUNELITE.warn));
      if(!warned)throw new AssertionError("An unreachable bridge must not still look connected");
    });

    // The session's set-aside list, which until 28 Sept 2026 was invisible and one-way. It is fed by
    // four buttons and one automatic path and only ever cleared on a client restart, so a player
    // could work down from a 441,621 gp pick to one worth a few hundred with nothing on screen
    // admitting why. Hidden at zero so it costs nothing until it is actually shaping the answer.
    {
      final int[] cleared={0};
      EviLivePanel sp=new EviLivePanel(k->{},()->{},()->{},()->{},()->{},()->{},()->cleared[0]++);
      sp.skipped(0);SwingUtilities.invokeAndWait(()->{});
      if(labels(sp).stream().anyMatch(t->t!=null&&t.contains("set aside")))
        throw new AssertionError("Nothing set aside must show no line at all");
      sp.skipped(1);SwingUtilities.invokeAndWait(()->{});
      if(labels(sp).stream().noneMatch(t->t!=null&&t.equals("1 item set aside this session")))
        throw new AssertionError("One item must read in the singular: "+labels(sp));
      sp.skipped(7);SwingUtilities.invokeAndWait(()->{});
      if(labels(sp).stream().noneMatch(t->t!=null&&t.equals("7 items set aside this session")))
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

    System.out.println("PASS: the session set-aside count and its undo button; the profit line (the figure in its own label, coloured green up and red down, trades, what it leaves out, a reset count, an unreadable answer, and reset feedback), the five-icon action row (each caption, its callback, and the row replacing the old button stack), the colour-scheme switch repainting in place (including rows rebuilt afterwards), sidebar pairing callback, masked key input and clearing after save, the skip-suggestion and block button callbacks, the personal-use and not-held button callbacks, the Active offers list (one row per occupied slot including uncollected/cancelled ones, status text, and clearing), the scroll fix (no sidebar text area moves its caret, unchanged text is never rewritten), the suggestion card (item, worth, verdict label and check lines; a failed check marked as well as coloured; and the paragraph coming back when the bridge sends no verdict), and the pairing section collapsing once paired and returning when a key is cleared, with the status dot tracking the bridge");
  }
  /** The profit line's current text: the one text area that starts with a figure or its own status. */
  private static String profitText(Container panel) {
    List<Component> all=new ArrayList<>();visit(panel,all);
    return all.stream().filter(c->c instanceof javax.swing.JTextArea).map(c->((javax.swing.JTextArea)c).getText())
      .filter(t->t.startsWith("(")||t.startsWith("Profit unavailable")||t.startsWith("Counting from now")||t.startsWith("Waiting for the bridge"))
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
