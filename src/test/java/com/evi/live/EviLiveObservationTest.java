package com.evi.live;
import java.lang.reflect.*;
import net.runelite.api.*;

/** Local synthetic offers only; never launches a game client. */
public class EviLiveObservationTest {
  static class FakeOffer implements GrandExchangeOffer {
    final GrandExchangeOfferState state; final int filled;
    FakeOffer(GrandExchangeOfferState state,int filled){this.state=state;this.filled=filled;}
    public int getQuantitySold(){return filled;}
    public int getItemId(){return 1;}
    public int getTotalQuantity(){return 10;}
    public int getPrice(){return 100;}
    public int getSpent(){return filled*100;}
    public GrandExchangeOfferState getState(){return state;}
  }
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args) throws Exception {
    EviLivePlugin plugin=new EviLivePlugin();
    ItemComposition item=(ItemComposition)Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),new Class[]{ItemComposition.class},(p,m,a)->m.getName().equals("getName")?"Synthetic rune":null);
    Client client=(Client)Proxy.newProxyInstance(Client.class.getClassLoader(),new Class[]{Client.class},(p,m,a)->m.getName().equals("getItemDefinition")?item:null);
    Field f=EviLivePlugin.class.getDeclaredField("client");f.setAccessible(true);f.set(plugin,client);
    Method capture=EviLivePlugin.class.getDeclaredMethod("capture",int.class,GrandExchangeOffer.class,EviLivePlugin.Offer.class,boolean.class);capture.setAccessible(true);
    EviLivePlugin.Offer baseline=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.BOUGHT,10),null,false);
    check(!baseline.knownStart,"Login completion must remain baseline");
    EviLivePlugin.Offer empty=(EviLivePlugin.Offer)capture.invoke(plugin,0,null,null,false);
    EviLivePlugin.Offer start=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.BUYING,0),empty,true);
    check(start.knownStart,"A new zero-fill offer after observed empty is known");
    EviLivePlugin.Offer partial=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.BUYING,4),start,true);
    check(partial.offerId.equals(start.offerId)&&partial.knownStart,"Partial fill retains identity");
    EviLivePlugin.Offer cancel=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.CANCELLED_BUY,4),partial,true);
    check(cancel.offerId.equals(start.offerId),"Cancellation retains identity");
    EviLivePlugin.Offer reused=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.BUYING,0),cancel,true);
    check(!reused.offerId.equals(start.offerId)&&!reused.knownStart,"Slot reuse without observed empty must rebaseline");
    EviLivePlugin.Offer instant=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.BOUGHT,10),empty,true);
    check(!instant.knownStart,"Instant fill without observed zero must remain incomplete");
    EviLivePlugin.Offer complete=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.BOUGHT,10),partial,true);
    check(complete.knownStart&&complete.offerId.equals(start.offerId),"Observed buy completion retains identity");
    EviLivePlugin.Offer duplicate=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.BOUGHT,10),complete,true);
    check(duplicate.offerId.equals(complete.offerId),"Repeated completion is the same offer");
    EviLivePlugin.Offer sell=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.SELLING,0),empty,true);
    EviLivePlugin.Offer sold=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.SOLD,10),sell,true);
    check(sold.knownStart&&sold.offerId.equals(sell.offerId),"Observed sale retains identity");
    EviLivePlugin.Offer reverse=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.BUYING,0),sell,true);
    check(!reverse.offerId.equals(sell.offerId)&&!reverse.knownStart,"Buy and sell cannot share identity");
    EviLivePlugin.Offer regression=(EviLivePlugin.Offer)capture.invoke(plugin,0,new FakeOffer(GrandExchangeOfferState.BUYING,2),partial,true);
    check(!regression.offerId.equals(partial.offerId)&&!regression.knownStart,"Regressing filled counters start incomplete observation");
    Method reset=EviLivePlugin.class.getDeclaredMethod("reset");reset.setAccessible(true);reset.invoke(plugin);
    Field session=EviLivePlugin.class.getDeclaredField("session");session.setAccessible(true);String oldSession=(String)session.get(plugin);
    reset.invoke(plugin);check(!oldSession.equals(session.get(plugin)),"Reconnect creates a new session");
    Field slots=EviLivePlugin.class.getDeclaredField("slots");slots.setAccessible(true);
    for(EviLivePlugin.Offer slot:(EviLivePlugin.Offer[])slots.get(plugin))check(slot==null,"Reconnect clears prior slot baselines");
    System.out.println("PASS: baseline, partial/cancel/instant/completed buys and sells, duplicate events, counter regression, slot reuse and session reset");
  }
}
