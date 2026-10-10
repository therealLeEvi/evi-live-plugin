package com.evi.live;

import com.evi.live.engine.AdviceNote;
import com.evi.live.engine.RelistProbe;
import com.evi.live.inprocess.Answerer;
import com.evi.live.inprocess.EngineFeed;
import com.evi.live.inprocess.InProcessEngine;
import com.evi.live.journal.PluginJournal;
import com.evi.live.journal.Tax;
import com.evi.live.market.HourBucket;
import com.evi.live.market.ItemCatalog;
import com.evi.live.market.WikiJson;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import net.runelite.client.util.Filepath;

/**
 * THE PLUGIN ON ITS OWN ENGINE (4.0.0: the only mode), driven through the REAL plugin methods (pollSuggestion, enqueue, every
 * sidebar button, offerCards):
 * <ol>
 *   <li>NO BRIDGE CODE AT ALL: no engine switch, no pairing key, no delivery queue, no version handshake, no transport to a
 *       companion app -- checked on the class itself and on build.gradle, so none of it can quietly come back;</li>
 *   <li>the poll, a packet and every button run on the plugin's own stores and engine, and the sidebar draws the engine's
 *       answer with no bridge, pairing or companion-app wording on screen;</li>
 *   <li>the poll never runs Engine.decide on the calling thread (a thread the plugin treats as the client thread polls; decide
 *       runs on evi-engine), never waits past InProcessTransport.WAIT_MS, leaves the sidebar alone while the engine works, and
 *       shows a late answer on the next poll;</li>
 *   <li>the price-history line and the paused-buys sentence while buys wait for the basis;</li>
 *   <li>ONE VOICE PER OFFER in IN_PROCESS mode, over a grid of sell offers against the REAL ported relist advice: never a relist
 *       card and a drift card for one offer, never "take the current price" below break-even, the drift hint still speaking
 *       above break-even where relist hands over at 5%; the old two-process rule (oneVoice false) kept only to show the
 *       difference is real.</li>
 * </ol>
 * (Filepath.Unchecked.getRooted is used HERE ONLY, to aim a Filepath at a temporary folder.)
 */
public final class EviLiveInProcessWiringTest {
  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  static void set(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  static Object get(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
    Method m = target.getClass().getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(target, args);
  }

  public static void main(String[] args) throws Exception {
    noBridgeCode();
    ownEngineOnly();
    pollThread();
    oneVoice();
    System.out.println("PASS: in-process wiring (" + checks + " checks) -- no bridge code (no switch, key, queue, handshake or transport;"
      + " build.gradle has no bridge flags); the poll, a packet and every button on the plugin's own stores, no bridge or pairing wording on"
      + " screen, the engine's answer drawn; Engine.decide on evi-engine and never on the polling (client) thread, the poll never waiting past "
      + InProcessTransport.WAIT_MS + " ms and the sidebar left alone meanwhile, a late answer shown next poll; the price-history line and paused"
      + " buys; one voice per offer against the real relist advice");
  }

  // ------------------------------------------------------------------------------------------- 1. the default

  static EviLiveConfig config() {
    return new EviLiveConfig() {
      public MaxTradeShare maxTradeShare() {
        return MaxTradeShare.QUARTER;
      }
    };
  }

  static boolean hasField(Class<?> c, String name) {
    for (Field f : c.getDeclaredFields()) if (f.getName().equals(name)) return true;
    return false;
  }

  static boolean hasMethod(Class<?> c, String name) {
    for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) return true;
    return false;
  }

  static boolean classExists(String name) {
    try {
      Class.forName(name);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** The 4.0.0 switch-over, as a fact about the code: nothing that existed only for the companion app is left to run. */
  static void noBridgeCode() throws Exception {
    for (String gone : new String[]{"com.evi.live.EngineSource", "com.evi.live.PairingKey", "com.evi.live.LocalTransport",
      "com.evi.live.shadow.ShadowEngine", "com.evi.live.shadow.ShadowDiff", "com.evi.live.shadow.ShadowLog"})
      check(!classExists(gone), gone + " must be gone with the bridge");
    for (String field : new String[]{"engineSource", "pluginKey", "pairingPath", "pairingRevision", "queue", "keyRejected", "bridgeApi",
      "EXPECTED_BRIDGE_API", "transport", "shadow", "retiredShadow", "pairingPollTicks", "PAIRING_POLL_TICKS", "developerMode", "inProcessTransport"})
      check(!hasField(EviLivePlugin.class, field), "EviLivePlugin." + field + " must be gone with the bridge");
    for (String method : new String[]{"pair", "showAsPaired", "refreshPairingVisibility", "bridgeOutOfDate", "bridgeOutOfDateMessage",
      "bridgeFailureMessage", "shouldPollForPairingKey", "shouldAdoptPairingKey", "pollForPairingKey", "flush", "refreshBridgeApi",
      "compareJournal", "journalCompareEnabled", "shadowEnabled", "shadowCompare", "startShadow", "stopShadow", "chosenEngineSource",
      "inProcessMode", "shadowJournal", "acceptedReply", "acceptedKept", "dropLegacyBlockList"})
      check(!hasMethod(EviLivePlugin.class, method), "EviLivePlugin." + method + "() must be gone with the bridge");
    for (String gone : new String[]{"BlockRequest", "AcceptRequest", "PersonalUseRequest", "NotHeldRequest"})
      check(!classExists("com.evi.live.EviLivePlugin$" + gone), "the bridge POST body " + gone + " must be gone");
    for (String method : new String[]{"paired", "staleBridge", "blockUndoneHere"})
      check(!hasMethod(EviLivePanel.class, method), "EviLivePanel." + method + "() must be gone with the bridge");
    for (String field : new String[]{"pairingSection", "staleBridge", "blockUndoneHere"})
      check(!hasField(EviLivePanel.class, field), "EviLivePanel." + field + " must be gone with the bridge");
    for (String method : new String[]{"journalCompare", "shadowEngine", "engineSource"})
      check(!hasMethod(EviLiveConfig.class, method), "the developer setting " + method + " must be gone with the bridge");
    check(!hasMethod(com.evi.live.journal.PluginJournal.class, "compare") && !classExists("com.evi.live.journal.PluginJournal$BridgeView")
      && !classExists("com.evi.live.journal.PluginJournal$BridgeProfit"), "the journal-vs-bridge comparison must be gone");
    check(!hasMethod(com.evi.live.market.WikiPriceClient.class, "currentHour") && !hasMethod(com.evi.live.market.PriceDataService.class, "followUnstampedHour")
      && !hasField(com.evi.live.market.MarketSnapshot.class, "unstampedHour") && !hasMethod(EngineFeed.Market.class, "withLatestHour"),
      "the shadow's unstamped hour must be gone (the plugin reads the Wiki's /1h only by timestamp)");
    String gradle = new String(Files.readAllBytes(java.nio.file.Paths.get("build.gradle")), StandardCharsets.UTF_8);
    for (String flag : new String[]{"hasProperty('inProcessEngine')", "hasProperty('shadowEngine')", "hasProperty('journalCompare')", "evi.inProcessEngine",
      "evi.shadowEngine", "evi.journalCompare", "pairingTest", "shadowWiringTest", "shadowEngineTest", "shadowTranscriptTest", "com.evi.live.shadow"})
      check(!gradle.contains(flag), "build.gradle must not mention " + flag);
    // The plugin's one door to an answer is its own engine: a fresh plugin has none until startUp builds it.
    EviLivePlugin p = new EviLivePlugin();
    check(get(p, "answers") == null, "a fresh plugin has no answer source until startUp builds the engine");
  }

  /** A real recorded answer body: a SELL suggestion with a buy id, and relist advice (the public golden transcript). */
  static String recordedBody() throws IOException {
    InputStream raw = EviLiveInProcessWiringTest.class.getResourceAsStream("/parity/transcripts/tier-holding-slots-tight.json.gz");
    try (InputStream in = new GZIPInputStream(raw); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      JsonObject t = new JsonParser().parse(r).getAsJsonObject();
      for (JsonElement se : t.getAsJsonArray("steps")) {
        JsonObject s = se.getAsJsonObject();
        if (!s.has("response") || !s.getAsJsonObject("response").has("body")) continue;
        JsonElement b = s.getAsJsonObject("response").get("body");
        if (b.isJsonObject() && b.getAsJsonObject().has("suggestion") && b.getAsJsonObject().get("suggestion").isJsonObject()
          && b.getAsJsonObject().getAsJsonArray("relistAdvice").size() > 0) return b.toString();
      }
    }
    throw new IllegalStateException("no recorded body with a suggestion and advice");
  }

  static final class Run {
    final EviLivePlugin plugin = new EviLivePlugin();
    EviLivePanel panel;
  }

  static Run inProcess(Answerer answerer) throws Exception {
    Run run = new Run();
    EviLivePlugin p = run.plugin;
    set(p, "gson", new Gson());
    set(p, "config", config());
    set(p, "account", "acct-a");
    set(p, "running", true);
    set(p, "lifecycle", 1L);
    set(p, "suggestionCache", new SuggestionCache());
    set(p, "openItemPriceCache", new OpenItemPriceCache());
    set(p, "answers", new InProcessTransport(answerer, System::currentTimeMillis));
    SwingUtilities.invokeAndWait(() -> run.panel = new EviLivePanel(() -> { }, () -> { }, () -> { }, () -> { }, () -> { }));
    set(p, "panel", run.panel);
    return run;
  }

  static void edt() throws Exception {
    SwingUtilities.invokeAndWait(() -> { });
  }

  /** The words a player can SEE: visible labels, buttons and text areas (tooltips aside), in component order. */
  static String visibleText(Component c) {
    StringBuilder sb = new StringBuilder();
    visible(c, sb);
    return sb.toString();
  }

  private static void visible(Component c, StringBuilder sb) {
    if (!c.isVisible()) return;
    if (c instanceof JLabel) sb.append(((JLabel) c).getText()).append('\n');
    if (c instanceof AbstractButton) sb.append(((AbstractButton) c).getText()).append('\n');
    if (c instanceof JTextComponent) sb.append(((JTextComponent) c).getText()).append('\n');
    if (c instanceof Container) for (Component k : ((Container) c).getComponents()) visible(k, sb);
  }

  static Answerer answering(String body, String backfillLine, boolean buysReady) {
    return (q, at) -> CompletableFuture.completedFuture(new InProcessEngine.Answer(q, body, body == null ? "Waiting for the latest prices from the OSRS Wiki." : null,
      backfillLine, buysReady, at - 5_000));
  }

  static void ownEngineOnly() throws Exception {
    String body = recordedBody(); // a real recorded body: a SELL suggestion with a buy id, and relist advice
    Run run = inProcess(answering(body, null, true));
    EviLivePlugin p = run.plugin;
    ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
    set(p, "sender", sender);
    try {
      // a packet goes to the plugin's journal only (none is running here: nothing happens, nothing throws)
      call(p, "enqueue", new Class<?>[]{boolean.class}, true);
      call(p, "enqueue", new Class<?>[]{boolean.class}, false);
      call(p, "pollSuggestion", new Class<?>[]{long.class}, 1L);
      edt();
      Suggestion shown = ((SuggestionCache) get(p, "suggestionCache")).get();
      check(shown != null && "sell".equals(shown.action), "the engine's answer is drawn: " + (shown == null ? null : shown.action));
      // every sidebar button, each after a fresh poll so there is a pick to act on
      for (String button : new String[]{"acceptSuggestion", "blockSuggestion", "flagPersonalUse", "flagNotHeld", "skipSuggestion", "resetProfit", "clearSkips"}) {
        call(p, "pollSuggestion", new Class<?>[]{long.class}, 1L);
        call(p, button, new Class<?>[0]);
        sender.submit(() -> { }).get(10, TimeUnit.SECONDS);
        sender.submit(() -> { }).get(10, TimeUnit.SECONDS);
      }
      check(get(p, "inProcessProfitSince") != null, "Reset keeps its time in the plugin");
      // the import line is shown (4.0.0): the journal's notice reaches the sidebar, and carries no bridge-era word either
      call(p, "showImportNotice", new Class<?>[]{String.class}, PluginJournal.IMPORT_MISSING_NOTICE);
      call(p, "pollSuggestion", new Class<?>[]{long.class}, 1L);
      edt();
      edt();
      String seen = visibleText(run.panel);
      for (String word : new String[]{"ridge", "ompanion", "127.0.0.1", "plugin key", "Pairing", "pairing", "scanner", "dashboard", "Local"})
        check(!seen.contains(word), "the sidebar shows bridge-era wording (\"" + word + "\"):\n" + seen);
      check(seen.contains("Connected to OSRS Wiki prices. Last update: "), "the status line names the Wiki prices:\n" + seen);
      check(seen.startsWith("EVI Live\n"), "the sidebar's title is \"EVI Live\" (no \"Local\", no non-ASCII dot):\n" + seen);
      check(seen.contains(PluginJournal.IMPORT_MISSING_NOTICE + "\n"), "the journal's import line reaches the sidebar:\n" + seen);
      // and the journal's "hide" (null) takes it away again
      call(p, "showImportNotice", new Class<?>[]{String.class}, (Object) null);
      edt();
      check(!visibleText(run.panel).contains("Import is on"), "the journal's hide must take the import line away:\n" + visibleText(run.panel));
    } finally {
      sender.shutdown();
    }
  }

  // ------------------------------------------------------------------------------------------- 3. the poll's thread

  static final String LATEST = "{\"data\":{\"554\":{\"high\":6,\"highTime\":1,\"low\":5,\"lowTime\":1}}}";
  static final String MAPPING = "[{\"id\":554,\"name\":\"Fire rune\",\"members\":false,\"limit\":25000}]";

  static void pollThread() throws Exception {
    Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-inprocess-wiring"));
    PluginJournal journal = new PluginJournal(root, System::currentTimeMillis, s -> new JsonParser().parse(s), () -> false, m -> { });
    journal.start();
    EngineFeed.Market m = new EngineFeed.Market(WikiJson.latest(LATEST), System.currentTimeMillis(), null, ItemCatalog.parse(MAPPING), null, null,
      Long.MIN_VALUE, 140, InProcessEngine.BASIS_HOURS + 1, 0, 0, 1, 0, true, 120);
    EngineFeed.MarketSource market = new EngineFeed.MarketSource() {
      @Override public EngineFeed.Market market(long nowMs) {
        return m;
      }

      @Override public CompletableFuture<List<HourBucket>> hoursFrom(long fromTs) {
        return CompletableFuture.completedFuture(new ArrayList<>());
      }
    };
    // The plugin's own guard: true on the client thread or the EDT. Here a thread named "client" stands for the client thread.
    InProcessEngine engine = new InProcessEngine(root, EngineFeed.journalOf(() -> journal), market, s -> new JsonParser().parse(s), x -> { },
      () -> "client".equals(Thread.currentThread().getName()) || SwingUtilities.isEventDispatchThread(), () -> null);
    try {
      Run run = inProcess(engine);
      Throwable[] failed = {null};
      Thread client = new Thread(() -> {
        try {
          for (int i = 0; i < 20 && engine.decisions() == 0; i++) call(run.plugin, "pollSuggestion", new Class<?>[]{long.class}, 1L);
        } catch (Throwable t) {
          failed[0] = t;
        }
      }, "client");
      client.start();
      client.join(60_000);
      check(failed[0] == null, "the poll threw: " + failed[0]);
      check(engine.decisions() > 0, "decide never ran");
      check(InProcessEngine.THREAD_NAME.equals(engine.lastDecidedOn()), "decide ran on '" + engine.lastDecidedOn() + "', not " + InProcessEngine.THREAD_NAME);
      edt();
      edt();
      String note = ((javax.swing.JTextArea) get(run.panel, "backfillNote")).getText();
      check(("Loading price history: 140 of " + InProcessEngine.BASIS_HOURS + " hours. Buy suggestions start when it's done.").equals(note)
        && ((JComponent) get(run.panel, "backfillNote")).isVisible(), "the price-history line in the sidebar: " + note);
      String seen = visibleText(run.panel);
      check(seen.contains(EviLivePlugin.BUYS_PAUSED), "with no sell to show, the card says buys are paused (never \"nothing passes your settings\"):\n" + seen);
      check(!seen.contains("passes your settings"), "the no-suggestion sentence must not claim the buy tiers were searched");
    } finally {
      engine.shutdown();
      journal.shutdown();
      journal.awaitIdle(30_000);
      root.deleteRecursively();
    }

    // never blocking: an engine that does not answer leaves the poll at WAIT_MS and the sidebar exactly as it was
    CompletableFuture<InProcessEngine.Answer> never = new CompletableFuture<>();
    Run stuck = inProcess((q, at) -> never);
    stuck.panel.suggestion("before");
    edt();
    long t0 = System.nanoTime();
    Thread poll = new Thread(() -> {
      try {
        call(stuck.plugin, "pollSuggestion", new Class<?>[]{long.class}, 1L);
      } catch (Exception ignored) {
        // reported by the check below
      }
    }, "evi-local-sender");
    poll.setDaemon(true);
    poll.start();
    poll.join(InProcessTransport.WAIT_MS + 3_000);
    long ms = (System.nanoTime() - t0) / 1_000_000;
    check(!poll.isAlive() && ms < InProcessTransport.WAIT_MS + 1_000, "the poll waited " + ms + " ms for a stuck engine" + (poll.isAlive() ? " and is still waiting" : ""));
    edt();
    check(visibleText(stuck.panel).contains("before"), "a pending answer must leave the sidebar as it was:\n" + visibleText(stuck.panel));

    // a late answer is shown on the next poll for the same query
    String body = recordedBody();
    AtomicLong asked = new AtomicLong();
    Run late = inProcess((q, at) -> {
      asked.incrementAndGet();
      CompletableFuture<InProcessEngine.Answer> f = new CompletableFuture<>();
      CompletableFuture.delayedExecutor(InProcessTransport.WAIT_MS + 300, TimeUnit.MILLISECONDS)
        .execute(() -> f.complete(new InProcessEngine.Answer(q, body, null, null, true, at)));
      return f;
    });
    call(late.plugin, "pollSuggestion", new Class<?>[]{long.class}, 1L);
    check(((SuggestionCache) get(late.plugin, "suggestionCache")).get() == null, "nothing drawn while the first answer is still coming");
    CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(600, TimeUnit.MILLISECONDS)).get();
    call(late.plugin, "pollSuggestion", new Class<?>[]{long.class}, 1L);
    check(((SuggestionCache) get(late.plugin, "suggestionCache")).get() != null && asked.get() == 2, "the late answer is shown on the next poll");
  }

  // ------------------------------------------------------------------------------------------- 4. one voice per offer

  static EviLivePlugin.RelistAdvice[] wire(List<AdviceNote> notes) {
    EviLivePlugin.RelistAdvice[] out = new EviLivePlugin.RelistAdvice[notes.size()];
    for (int i = 0; i < out.length; i++) {
      // through JSON, exactly as the plugin parses the engine's body
      com.google.gson.JsonElement o = com.evi.live.engine.ResponseJson.note(notes.get(i));
      out[i] = new Gson().fromJson(o, EviLivePlugin.RelistAdvice.class);
    }
    return out;
  }

  static final java.util.Set<String> PRICE_LABELS = new java.util.HashSet<>(java.util.Arrays.asList("Priced over market", "Not selling", "Market moved away"));

  static void oneVoice() throws Exception {
    int item = 4151; // taxed, so break-even sits above what was paid
    long price = 1_000_000;
    int cases = 0, overlapInBridge = 0, relistVoice = 0, driftVoice = 0, belowBreakEvenSilenced = 0, silentBoth = 0;
    for (double gap : new double[]{0.003, 0.01, 0.016, 0.03, 0.045, 0.049, 0.05, 0.051, 0.06, 0.1, 0.25}) {
      long market = Math.round(price * (1 - gap));
      for (Double paidShare : new Double[]{null, 0.5, 0.9, 0.95, 0.97, 0.99, 1.05}) {
        Double paid = paidShare == null ? null : price * paidShare;
        for (double minutes : new double[]{5, 14, 15, 30, 200, 359, 360, 2000}) {
          for (double target : new double[]{120, 1440}) {
            cases++;
            List<AdviceNote> notes = RelistProbe.notes(item, "Abyssal whip", price, 10, market, paid, minutes, target);
            EviLivePlugin.RelistAdvice[] advice = wire(notes);
            Suggestion live = new Suggestion();
            live.itemId = item;
            live.buyPrice = market;
            live.sellPrice = market;
            Map<String, Long> breakEven = new HashMap<>();
            Long be = paid == null ? null : Tax.breakEvenSellPrice(item, paid);
            if (be != null) breakEven.put(String.valueOf(item), be);
            List<EviLivePlugin.ActiveOffer> offers = Collections.singletonList(new EviLivePlugin.ActiveOffer(item, price, false, "Abyssal whip", 10));
            List<EviLivePlugin.AdviceCard> one = EviLivePlugin.offerCards(offers, new Suggestion[]{live}, null, advice, breakEven, true);
            List<EviLivePlugin.AdviceCard> two = EviLivePlugin.offerCards(offers, new Suggestion[]{live}, null, advice, breakEven, false);
            String where = "gap " + gap + ", paid " + paid + ", break-even " + be + ", " + minutes + " min, pace " + target;
            int voices = 0;
            boolean drift = false;
            for (EviLivePlugin.AdviceCard c : one) if (PRICE_LABELS.contains(c.label)) {
              voices++;
              if ("Priced over market".equals(c.label)) drift = true;
            }
            check(voices <= 1, where + ": one offer got " + voices + " price sentences in IN_PROCESS mode");
            boolean below = be != null && market < be;
            if (below) check(!drift, where + ": below break-even the plugin must never add \"take the current price\"");
            if (!notes.isEmpty()) {
              check(voices == 1 && !drift, where + ": the relist sentence is the one voice when it speaks");
              relistVoice++;
            }
            // above break-even (or unknown) and silent relist: the plugin's own hint speaks exactly as before past 5%
            boolean pluginWould = (price - market) / (double) market > 0.05;
            if (notes.isEmpty() && !below) {
              check(drift == pluginWould, where + ": above break-even with relist silent, the drift hint must speak exactly as before");
              if (drift) driftVoice++;
            }
            if (notes.isEmpty() && below && pluginWould) belowBreakEvenSilenced++;
            if (voices == 0) silentBoth++;
            // BRIDGE mode is untouched: the relist cards plus the drift hint whenever it would speak, independently
            int bridgeVoices = 0;
            boolean bridgeDrift = false;
            for (EviLivePlugin.AdviceCard c : two) if (PRICE_LABELS.contains(c.label)) {
              bridgeVoices++;
              if ("Priced over market".equals(c.label)) bridgeDrift = true;
            }
            check(bridgeDrift == pluginWould && bridgeVoices == notes.size() + (pluginWould ? 1 : 0), where + ": BRIDGE mode must build the cards as before");
            if (bridgeVoices > 1) overlapInBridge++;
          }
        }
      }
    }
    check(overlapInBridge > 0, "the grid never reached an offer the two processes BOTH spoke about -- the rule is untested");
    check(relistVoice > 0 && driftVoice > 0 && belowBreakEvenSilenced > 0, "the grid must reach every voice: relist " + relistVoice + ", drift " + driftVoice
      + ", below break-even before the relist clock " + belowBreakEvenSilenced);
    // a BUY offer has no relist voice: its drift hint is unchanged in both modes
    Suggestion live = new Suggestion();
    live.itemId = item;
    live.buyPrice = 1_100_000;
    live.sellPrice = 1_100_000;
    List<EviLivePlugin.ActiveOffer> buy = Collections.singletonList(new EviLivePlugin.ActiveOffer(item, 1_000_000, true, "Abyssal whip", 3));
    for (boolean mode : new boolean[]{true, false}) {
      List<EviLivePlugin.AdviceCard> cards = EviLivePlugin.offerCards(buy, new Suggestion[]{live}, null, null, Collections.singletonMap(String.valueOf(item), 2_000_000L), mode);
      check(cards.size() == 1 && "Priced under market".equals(cards.get(0).label), "a buy offer's drift hint, oneVoice=" + mode);
    }
    System.out.println("  one voice: " + cases + " sell offers -- relist the voice in " + relistVoice + ", the plugin's hint in " + driftVoice
      + ", both silent in " + silentBoth + " (" + belowBreakEvenSilenced + " below break-even inside the relist clock's first 15 minutes or under 1.5%),"
      + " and " + overlapInBridge + " where BRIDGE mode still gives two");
  }
}
