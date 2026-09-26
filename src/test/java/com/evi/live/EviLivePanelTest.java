package com.evi.live;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JPasswordField;
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
      JButton skipButton=buttons.stream().filter(b->"Skip this suggestion".equals(b.getText())).findFirst().orElseThrow();
      JButton personalUseButton=buttons.stream().filter(b->"Mark as personal use".equals(b.getText())).findFirst().orElseThrow();
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
      JButton notHeldButton=buttons.stream().filter(b->"I don't have this anymore".equals(b.getText())).findFirst().orElseThrow();
      notHeldButton.doClick();
      if(notHelds.get()!=1)throw new AssertionError("Not-held callback must fire exactly once per click");
      // Block: Skip made permanent, so a player can refuse an item without looking up its ID.
      JButton blockButton=buttons.stream().filter(b->"Block this item".equals(b.getText())).findFirst().orElseThrow(()->new AssertionError("The sidebar needs a Block button"));
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
    if(!line.startsWith("+"+String.format("%,d",2020133)+" gp"))throw new AssertionError("The profit line must lead with the figure: "+line);
    if(!line.contains("everything EVI has matched"))throw new AssertionError("Without a reset it must say what it covers: "+line);
    if(!line.contains("43 trades: 30 up, 13 down"))throw new AssertionError("Trades and the split belong on the line: "+line);
    if(!line.contains("17 sales EVI never saw bought")||!line.contains("4 purchases not yet sold"))
      throw new AssertionError("What the total leaves out must be stated, never folded in: "+line);
    EviLivePlugin.Profit since=new EviLivePlugin.Profit();
    since.since=1758000000000L;since.gp=-5000;since.trades=1;since.losers=1;
    profitPanel.profit(since);
    SwingUtilities.invokeAndWait(()->{});
    String reset=profitText(profitPanel);
    if(!reset.startsWith(String.format("%,d",-5000)+" gp (since you reset"))throw new AssertionError("A reset count says so, and a loss is not dressed up: "+reset);
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
    System.out.println("PASS: the profit line (figure, trades, what it leaves out, a reset count, an unreadable answer, and reset feedback), the colour-scheme switch repainting in place (including rows rebuilt afterwards), sidebar pairing callback, masked key input and clearing after save, the skip-suggestion and block button callbacks, the personal-use and not-held button callbacks, the Active offers list (one row per occupied slot including uncollected/cancelled ones, status text, and clearing), and the scroll fix (no sidebar text area moves its caret, unchanged text is never rewritten)");
  }
  /** The profit line's current text: the one text area that starts with a figure or its own status. */
  private static String profitText(Container panel) {
    List<Component> all=new ArrayList<>();visit(panel,all);
    return all.stream().filter(c->c instanceof javax.swing.JTextArea).map(c->((javax.swing.JTextArea)c).getText())
      .filter(t->t.startsWith("+")||t.startsWith("-")||t.startsWith("Profit unavailable")||t.startsWith("Counting from now")||t.startsWith("Waiting for the bridge"))
      .findFirst().orElse("");
  }

  private static List<String> labels(Container panel) {
    List<Component> all=new ArrayList<>();visit(panel,all);
    return all.stream().filter(c->c instanceof javax.swing.JLabel).map(c->((javax.swing.JLabel)c).getText()).collect(java.util.stream.Collectors.toList());
  }
}
