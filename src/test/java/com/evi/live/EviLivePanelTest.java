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
    SwingUtilities.invokeAndWait(()->{
      AtomicReference<String> saved=new AtomicReference<>();
      java.util.concurrent.atomic.AtomicInteger skips=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger personalUses=new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger notHelds=new java.util.concurrent.atomic.AtomicInteger();
      EviLivePanel panel=new EviLivePanel(saved::set,skips::incrementAndGet,personalUses::incrementAndGet,notHelds::incrementAndGet);
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
    });
    System.out.println("PASS: sidebar pairing callback, masked key input and clearing after save, the skip-suggestion button callback, the personal-use and not-held button callbacks, and the Active offers list (one row per occupied slot including uncollected/cancelled ones, status text, and clearing)");
  }
  private static List<String> labels(Container panel) {
    List<Component> all=new ArrayList<>();visit(panel,all);
    return all.stream().filter(c->c instanceof javax.swing.JLabel).map(c->((javax.swing.JLabel)c).getText()).collect(java.util.stream.Collectors.toList());
  }
}
