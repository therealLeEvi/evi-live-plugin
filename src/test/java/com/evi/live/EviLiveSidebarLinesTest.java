package com.evi.live;

import com.evi.live.journal.PluginFolder;
import com.evi.live.journal.PluginJournal;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Filepath;

/**
 * The three sidebar lines the maintainer decided on 6 Oct 2026, each asserted as the exact words a player reads:
 * <ol>
 *   <li>"Import is on, but no file was found at ..." -- ONLY while "Import old trade history" is on and its file
 *       is missing; gone once imported or the setting is off. Driven through a real PluginJournal on a temporary
 *       folder and through the plugin's own wiring.</li>
 *   <li>Every BUY card states its TOTAL COST ("Total 1,396,000 gp" = buy price x quantity, long arithmetic, en-US
 *       grouping under a Dutch default locale); never on a sell or holding card; nothing when quantity or price is
 *       unknown or the product does not fit a long.</li>
 *   <li>High with "Max share of cash per trade" at "No limit": the line under the risk buttons names it, updating at
 *       once when either setting changes, in the settings panel (a real RuneLite ConfigManager) or by a button.</li>
 * </ol>
 * Synthetic: no RuneLite client, no bridge, an invented account id. Temporary folders are deleted afterwards.
 */
public final class EviLiveSidebarLinesTest {
  static final String ACCOUNT = "c3".repeat(32);
  static final String OTHER = "d4".repeat(32);
  static final long T0 = 1789992000000L;
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

  static void edt() throws Exception {
    SwingUtilities.invokeAndWait(() -> { });
  }

  static void visit(Container parent, List<Component> out) {
    for (Component c : parent.getComponents()) {
      out.add(c);
      if (c instanceof Container) visit((Container) c, out);
    }
  }

  /** Every label text under a container, in order. */
  static List<String> labels(Container c) {
    List<Component> all = new ArrayList<>();
    visit(c, all);
    List<String> out = new ArrayList<>();
    for (Component x : all) if (x instanceof JLabel) out.add(((JLabel) x).getText());
    return out;
  }

  static JLabel label(Container c, String text) {
    List<Component> all = new ArrayList<>();
    visit(c, all);
    for (Component x : all) if (x instanceof JLabel && text.equals(((JLabel) x).getText())) return (JLabel) x;
    return null;
  }

  static EviLivePanel panel(java.util.function.Consumer<RiskLevel> chooseRisk) throws Exception {
    AtomicReference<EviLivePanel> ref = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> ref.set(new EviLivePanel(() -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, chooseRisk)));
    ref.get().applyTheme(PanelTheme.RUNELITE); // as startUp() does, so every role carries its colour
    edt();
    return ref.get();
  }

  static Suggestion pick(String action, int quantity, long buyPrice, long sellPrice, boolean withVerdict) {
    Suggestion s = new Suggestion();
    s.itemId = 4151;
    s.name = "Test item";
    s.action = action;
    s.quantity = quantity;
    s.buyPrice = buyPrice;
    s.sellPrice = sellPrice;
    s.expectedProfit = 1000L;
    if (withVerdict) {
      Suggestion.Verdict v = new Suggestion.Verdict();
      v.level = "clear";
      v.label = "Checks passed";
      Suggestion.Check c = new Suggestion.Check();
      c.ok = Boolean.TRUE;
      c.text = "A check line";
      v.checks = Collections.singletonList(c);
      s.verdict = v;
    }
    return s;
  }

  public static void main(String[] args) throws Exception {
    Locale saved = Locale.getDefault();
    // A Dutch default, as on the maintainer's machine: %,d would print 1.396.000 here. The total must not.
    Locale.setDefault(new Locale("nl", "NL"));
    try {
      check(String.format("%,d", 1396000).equals("1.396.000"), "setup: the default locale must group with dots for this test to mean anything");
      totalCost();
      highWithNoLimit();
      importLinePanel();
      importLineJournal();
      importLineWiring();
    } finally {
      Locale.setDefault(saved);
    }
    System.out.println("PASS: sidebar lines (" + checks + " checks): every BUY card's total cost (\"Total 1,396,000 gp\", long arithmetic past 2^31,"
      + " en-US grouping under a Dutch locale, on the card, the paragraph and the extra positions; never on a sell; nothing when unknown or past a long);"
      + " High with \"No limit\" named on the risk line, updated at once from the settings (a real ConfigManager) and the buttons; and the import line"
      + " shown only while the import is on and its file missing, gone once imported or switched off, per account, from the journal's own thread");
  }

  // ------------------------------------------------------------------------------------------- 2. total cost

  static void totalCost() throws Exception {
    check("Total 1,396,000 gp".equals(EviLivePanel.totalCost(pick("buy", 698, 2000, 2100, false))),
      "698 x 2,000 must read \"Total 1,396,000 gp\" with en-US grouping: " + EviLivePanel.totalCost(pick("buy", 698, 2000, 2100, false)));
    // Past 2^31 in the product AND in the unit price: int arithmetic would wrap to a wrong (negative) figure.
    check("Total 4,294,967,296,000 gp".equals(EviLivePanel.totalCost(pick("buy", 2000, 2_147_483_648L, 2_200_000_000L, false))),
      "2,000 x 2,147,483,648 must be computed in long: " + EviLivePanel.totalCost(pick("buy", 2000, 2_147_483_648L, 2_200_000_000L, false)));
    check("Total 46,340 gp".equals(EviLivePanel.totalCost(pick("buy", 46340, 1, 2, false))), "a 1 gp item: quantity times one");
    // Never on a sell or holding, never for an unknown quantity or price, never a wrapped figure.
    check(EviLivePanel.totalCost(pick("sell", 698, 2000, 2100, false)) == null, "a SELL (holding) card must carry no total");
    check(EviLivePanel.totalCost(pick(null, 698, 2000, 2100, false)) == null, "no action named: no total");
    check(EviLivePanel.totalCost(pick("buy", 0, 2000, 2100, false)) == null, "quantity unknown (0): no total");
    check(EviLivePanel.totalCost(pick("buy", -5, 2000, 2100, false)) == null, "a negative quantity: no total");
    check(EviLivePanel.totalCost(pick("buy", 698, 0, 2100, false)) == null, "price unknown (0): no total");
    check(EviLivePanel.totalCost(pick("buy", Integer.MAX_VALUE, 5_000_000_000L, 1, false)) == null,
      "a product past a long must show nothing, never a wrapped figure: " + EviLivePanel.totalCost(pick("buy", Integer.MAX_VALUE, 5_000_000_000L, 1, false)));
    check(EviLivePanel.totalCost(null) == null, "no suggestion: no total");

    EviLivePanel panel = panel(r -> { });
    JPanel card = (JPanel) get(panel, "suggestionCard");
    EviTheme.Palette p = EviTheme.palette();
    // The card, with a verdict.
    panel.suggestion(pick("buy", 698, 2000, 2100, true), "prose");
    edt();
    JLabel total = label(card, "Total 1,396,000 gp");
    check(total != null, "the BUY card must state \"Total 1,396,000 gp\": " + labels(card));
    check(total.getForeground().equals(p.muted), "the total is a plain figure in the muted text colour, not a verdict colour: " + total.getForeground());
    // A sell card: no total at all.
    panel.suggestion(pick("sell", 698, 2000, 2100, true), "prose");
    edt();
    check(labels(card).stream().noneMatch(t -> t != null && t.startsWith("Total")), "a SELL card must carry no total: " + labels(card));
    // A buy whose quantity is unknown: the card is drawn, without a total.
    panel.suggestion(pick("buy", 0, 2000, 2100, true), "prose");
    edt();
    check(labels(card).stream().noneMatch(t -> t != null && t.startsWith("Total")), "a buy with no quantity must carry no total: " + labels(card));
    // The paragraph (no verdict from the bridge): the total on its own last line, for a buy only.
    JTextArea prose = (JTextArea) get(panel, "suggestion");
    panel.suggestion(pick("buy", 698, 2000, 2100, false), "Test item x698");
    edt();
    check("Test item x698\nTotal 1,396,000 gp".equals(prose.getText()), "a buy drawn as a paragraph must end with its total: " + prose.getText());
    panel.suggestion(pick("sell", 698, 2000, 2100, false), "Test item x698");
    edt();
    check("Test item x698".equals(prose.getText()), "a sell drawn as a paragraph must carry no total: " + prose.getText());
    panel.suggestion(pick("buy", 698, 2000, 2100, false), "");
    edt();
    check("No suggestion yet.".equals(prose.getText()), "no paragraph at all: the placeholder, with no stray total: " + prose.getText());
    // The extra positions ("Also worth buying") are buy cards too.
    JPanel also = (JPanel) get(panel, "alsoList");
    panel.alsoSuggested(Arrays.asList(pick("buy", 10, 1_500_000, 1_600_000, false), pick("buy", 0, 7, 8, false)));
    edt();
    check(labels(also).contains("Total 15,000,000 gp"), "an extra position must state its total: " + labels(also));
    check(labels(also).stream().filter(t -> t != null && t.startsWith("Total")).count() == 1, "only the extra with a known quantity carries a total: " + labels(also));
  }

  // --------------------------------------------------------------------------------- 3. High with No limit

  // The line is part of the hidden Risk level section since 7 Oct 2026 (EviLiveRiskSkipTest.hiddenLevels proves it is
  // not drawn); these checks keep its dormant wiring whole for when the levels return. No odds, permanently.
  static final String HIGH_NO_LIMIT = "Adds gear whose price can drop suddenly."
    + " No trade-size limit (your setting): one High trade can use most of your stack.";

  static void highWithNoLimit() throws Exception {
    check(HIGH_NO_LIMIT.equals(RiskLevel.HIGH.aim(MaxTradeShare.OFF)), "High + No limit: " + RiskLevel.HIGH.aim(MaxTradeShare.OFF));
    // Every other combination is the plain aim, word for word.
    for (RiskLevel level : RiskLevel.values()) {
      for (MaxTradeShare share : new MaxTradeShare[]{null, MaxTradeShare.OFF, MaxTradeShare.TENTH, MaxTradeShare.QUARTER, MaxTradeShare.THIRD, MaxTradeShare.HALF}) {
        if (level == RiskLevel.HIGH && share == MaxTradeShare.OFF) continue;
        check(level.aim().equals(level.aim(share)), level + " with " + share + " must read exactly its plain aim: " + level.aim(share));
      }
    }
    check("No limit".equals(MaxTradeShare.OFF.toString()), "the setting the line names is the one labelled \"No limit\"");

    // The FIRST frame: startUp() draws the sidebar through buildPanel() from the stored settings, so a player who
    // already chose High and No limit sees the words before touching anything.
    for (MaxTradeShare share : new MaxTradeShare[]{MaxTradeShare.OFF, MaxTradeShare.QUARTER}) {
      EviLivePlugin fresh = new EviLivePlugin();
      set(fresh, "config", new EviLiveConfig() {
        public RiskLevel riskLevelV2() {
          return RiskLevel.HIGH;
        }

        public MaxTradeShare maxTradeShare() {
          return share;
        }
      });
      AtomicReference<EviLivePanel> built = new AtomicReference<>();
      SwingUtilities.invokeAndWait(() -> built.set(fresh.buildPanel()));
      edt();
      String first = ((JTextArea) get(built.get(), "riskAim")).getText();
      check((share == MaxTradeShare.OFF ? HIGH_NO_LIMIT : RiskLevel.HIGH.aim()).equals(first),
        "startUp's first frame at High with " + share + " must read: " + first);
    }

    // Through the shipping paths: a REAL RuneLite ConfigManager (the settings panel) and the sidebar's buttons.
    net.runelite.client.eventbus.EventBus bus = new net.runelite.client.eventbus.EventBus();
    ConfigManager cm = EviLiveRiskSkipTest.realConfigManager(bus);
    EviLiveConfig real = cm.getConfig(EviLiveConfig.class);
    EviLivePlugin plugin = new EviLivePlugin();
    set(plugin, "config", real);
    set(plugin, "configManager", cm);
    EviLivePanel panel = panel(plugin::chooseRisk);
    set(plugin, "panel", panel);
    bus.register(plugin);
    JTextArea aim = (JTextArea) get(panel, "riskAim");
    // As startUp() draws it: the stored level and share (defaults Low / 25%).
    panel.riskLevel(real.riskLevelV2(), real.maxTradeShare());
    edt();
    check(RiskLevel.LOW.aim().equals(aim.getText()), "default Low / 25%: " + aim.getText());
    // Settings panel: High, then No limit -- the line follows each change at once.
    cm.setConfiguration("evilive", "riskLevelV2", RiskLevel.HIGH);
    edt();
    check(RiskLevel.HIGH.aim().equals(aim.getText()), "High with the default 25% cap names no missing limit: " + aim.getText());
    cm.setConfiguration("evilive", "maxTradeShare", MaxTradeShare.OFF);
    edt();
    check(HIGH_NO_LIMIT.equals(aim.getText()), "High, then \"No limit\" chosen in the settings: the line must name it at once: " + aim.getText());
    cm.setConfiguration("evilive", "maxTradeShare", MaxTradeShare.HALF);
    edt();
    check(RiskLevel.HIGH.aim().equals(aim.getText()), "a cap chosen again (50%) must take the words away at once: " + aim.getText());
    cm.setConfiguration("evilive", "maxTradeShare", MaxTradeShare.OFF);
    edt();
    check(HIGH_NO_LIMIT.equals(aim.getText()), "back to No limit: " + aim.getText());
    // The sidebar's buttons, with No limit still set.
    SwingUtilities.invokeAndWait(() -> EviLiveRiskSkipTest.button(panel, "Medium").doClick());
    edt();
    check(RiskLevel.MEDIUM.aim().equals(aim.getText()), "Medium pressed: No limit is only named at High: " + aim.getText());
    SwingUtilities.invokeAndWait(() -> EviLiveRiskSkipTest.button(panel, "High").doClick());
    edt();
    check(HIGH_NO_LIMIT.equals(aim.getText()), "High pressed with No limit set: the line must name it: " + aim.getText());
    // Settings panel again: the level moved away from High.
    cm.setConfiguration("evilive", "riskLevelV2", RiskLevel.LOW);
    edt();
    check(RiskLevel.LOW.aim().equals(aim.getText()), "Low chosen in the settings: " + aim.getText());
    // The line stays neutral text: no warning colour for a setting the player chose.
    check(aim.getForeground().equals(EviTheme.palette().text), "the risk line must stay in the plain text colour: " + aim.getForeground());
    // And the button tooltips stay the bare aims.
    check(RiskLevel.HIGH.aim().equals(EviLivePanel.plainTip(EviLiveRiskSkipTest.button(panel, "High").getToolTipText())), "the High button's tooltip is its plain aim");
    check(!EviLiveRiskSkipTest.nowhere.exists(), "the test ConfigManager must never write a file");
  }

  // ------------------------------------------------------------------------------------------ 1. import line

  static void importLinePanel() throws Exception {
    EviLivePanel panel = panel(r -> { });
    JTextArea note = panel.importNote;
    check(!note.isVisible() && note.getText().isEmpty(), "the import line starts hidden and empty");
    check(note.getCaret() instanceof DefaultCaret && ((DefaultCaret) note.getCaret()).getUpdatePolicy() == DefaultCaret.NEVER_UPDATE,
      "the import line must pin its caret, or every update drags the sidebar");
    panel.importNotice(PluginJournal.IMPORT_MISSING_NOTICE);
    edt();
    check(note.isVisible() && PluginJournal.IMPORT_MISSING_NOTICE.equals(note.getText()), "shown: " + note.getText());
    EviTheme.Palette p = EviTheme.palette();
    check(note.getForeground().equals(p.text), "the import line is plain text, never a warning colour: " + note.getForeground());
    panel.importNotice(null);
    edt();
    check(!note.isVisible() && note.getText().isEmpty(), "null must hide and clear it");
    panel.importNotice(PluginJournal.IMPORT_MISSING_NOTICE);
    panel.importNotice("");
    edt();
    check(!note.isVisible(), "an empty text hides it too");
    // The folder's name comes from ONE constant (Phase 7 renames it with the internalName): the words follow it, and the
    // constant must name the folder RuneLite actually uses, i.e. agree with @PluginDescriptor's internalName.
    check(("Import is on, but no file was found at " + PluginFolder.PATH + "/import/events.jsonl.").equals(PluginJournal.IMPORT_MISSING_NOTICE),
      "the exact words: " + PluginJournal.IMPORT_MISSING_NOTICE);
    check(PluginFolder.PATH.equals(".runelite/plugin-data/" + PluginFolder.INTERNAL_NAME), "the data folder's path: " + PluginFolder.PATH);
    check(PluginFolder.INTERNAL_NAME.equals(EviLivePlugin.class.getAnnotation(net.runelite.client.plugins.PluginDescriptor.class).internalName()),
      "PluginFolder.INTERNAL_NAME must equal @PluginDescriptor's internalName, or every sentence names a folder RuneLite does not use");
    try {
      String described = EviLiveConfig.class.getMethod("importBridgeHistory").getAnnotation(net.runelite.client.config.ConfigItem.class).description();
      check(("Copies once: " + PluginFolder.PATH + "/import/events.jsonl and preferences.json. Off: reads nothing.").equals(described),
        "the import setting's tooltip names the data folder from the one constant: " + described);
    } catch (NoSuchMethodException e) {
      throw new AssertionError("no importBridgeHistory setting", e);
    }
  }

  /** One packet of eight empty slots for {@code account}, as the plugin builds it. */
  static String packet(String account, String session, long seq, long ts) {
    JsonObject p = new JsonObject();
    p.addProperty("version", 1);
    p.addProperty("session", session);
    p.addProperty("account", account);
    p.addProperty("seq", seq);
    p.addProperty("ts", ts);
    p.addProperty("loggedIn", true);
    JsonArray offers = new JsonArray();
    for (int i = 0; i < 8; i++) {
      JsonObject o = new JsonObject();
      o.addProperty("slot", i);
      o.addProperty("itemId", 0);
      o.addProperty("total", 0);
      o.addProperty("filled", 0);
      o.addProperty("price", 0);
      o.addProperty("spent", 0);
      o.addProperty("offerId", session + "-e" + i);
      o.addProperty("state", "EMPTY");
      o.addProperty("name", "");
      o.addProperty("knownStart", false);
      o.addProperty("ticksToFill", -1);
      offers.add(o);
    }
    p.add("offers", offers);
    return p.toString();
  }

  /** A one-line bridge journal holding a logged-out packet of {@code account}: enough to import. */
  static void writeImportFile(Filepath root, String account) throws Exception {
    Filepath d = root.joinSegment(PluginJournal.IMPORT_DIR);
    d.createDirectories();
    String line = "{\"type\":\"packet\",\"received\":" + (T0 - 100) + ",\"packet\":{\"version\":1,\"session\":\"bridge-1\",\"account\":\"" + account
      + "\",\"seq\":1,\"ts\":" + (T0 - 200) + ",\"loggedIn\":false,\"offers\":[]}}";
    d.joinSegment(PluginJournal.IMPORT_FILE).write((line + "\n").getBytes(StandardCharsets.UTF_8));
  }

  static final class Notices {
    final List<String> seen = Collections.synchronizedList(new ArrayList<>());
    final List<String> threads = Collections.synchronizedList(new ArrayList<>());

    void accept(String s) {
      seen.add(s);
      threads.add(Thread.currentThread().getName());
    }

    List<String> list() {
      synchronized (seen) {
        return new ArrayList<>(seen);
      }
    }
  }

  static PluginJournal journal(Filepath root, AtomicBoolean enabled, Notices n) {
    PluginJournal j = new PluginJournal(root, () -> T0 + 1000, s -> new JsonParser().parse(s), enabled::get, m -> { });
    j.onImportNotice(n::accept);
    j.start();
    return j;
  }

  static void importLineJournal() throws Exception {
    final String M = PluginJournal.IMPORT_MISSING_NOTICE;
    // (a) On, no file: shown once (not again at every packet), then the file arrives: imported, and hidden.
    {
      Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-sidebar-test"));
      AtomicBoolean on = new AtomicBoolean(true);
      Notices n = new Notices();
      PluginJournal j = journal(root, on, n);
      check(j.awaitIdle(30000) && n.list().isEmpty(), "nothing is said before the first packet names an account: " + n.list());
      j.offerPacket(packet(ACCOUNT, "s1", 1, T0));
      j.offerPacket(packet(ACCOUNT, "s1", 2, T0 + 1));
      check(j.awaitIdle(30000) && n.list().equals(Collections.singletonList(M)), "on with no file: shown exactly once: " + n.list());
      writeImportFile(root, ACCOUNT);
      j.offerPacket(packet(ACCOUNT, "s1", 3, T0 + 2));
      check(j.awaitIdle(30000) && n.list().equals(Arrays.asList(M, null)), "the file arrived and was imported: the line must go: " + n.list());
      check(root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment("imported-" + ACCOUNT + ".jsonl").exists(), "setup: the import ran");
      // Imported, and then the player tidies the import file away: the line must NOT come back.
      root.joinSegment(PluginJournal.IMPORT_DIR).joinSegment(PluginJournal.IMPORT_FILE).delete();
      j.offerPacket(packet(ACCOUNT, "s1", 4, T0 + 3));
      check(j.awaitIdle(30000) && n.list().equals(Arrays.asList(M, null)), "once imported, a removed import file must not bring the line back: " + n.list());
      // Switched off and on again: the account is still imported, so nothing to say either way.
      on.set(false);
      j.importRequested();
      on.set(true);
      j.importRequested();
      check(j.awaitIdle(30000) && n.list().equals(Arrays.asList(M, null)), "an imported account never shows the line: " + n.list());
      // Another account on the same machine, not imported, with no file: the line is about it.
      j.offerPacket(packet(OTHER, "s2", 1, T0 + 4));
      check(j.awaitIdle(30000) && n.list().equals(Arrays.asList(M, null, M)), "another account still waiting for a file: shown for it: " + n.list());
      j.offerPacket(packet(ACCOUNT, "s1", 5, T0 + 5));
      check(j.awaitIdle(30000) && n.list().equals(Arrays.asList(M, null, M, null)), "back on the imported account: hidden: " + n.list());
      for (String t : n.threads) check(PluginJournal.THREAD_NAME.equals(t), "the line must be decided on the journal's thread, not '" + t + "'");
      j.shutdown();
      check(j.awaitIdle(30000), "drain");
      root.deleteRecursively();
    }
    // (b) Off: never shown, file or no file. Switched on with no packet in between: shown AT ONCE; off: gone at once.
    {
      Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-sidebar-test"));
      AtomicBoolean on = new AtomicBoolean(false);
      Notices n = new Notices();
      PluginJournal j = journal(root, on, n);
      j.offerPacket(packet(ACCOUNT, "s1", 1, T0));
      j.offerPacket(packet(ACCOUNT, "s1", 2, T0 + 1));
      check(j.awaitIdle(30000) && n.list().equals(Collections.singletonList((String) null)), "off with no file: never shown: " + n.list());
      on.set(true);
      j.importRequested();
      check(j.awaitIdle(30000) && n.list().equals(Arrays.asList(null, M)), "switched on: shown at once, before any packet: " + n.list());
      on.set(false);
      j.importRequested();
      check(j.awaitIdle(30000) && n.list().equals(Arrays.asList(null, M, null)), "switched off: gone at once: " + n.list());
      j.offerPacket(packet(ACCOUNT, "s1", 3, T0 + 2));
      check(j.awaitIdle(30000) && n.list().equals(Arrays.asList(null, M, null)), "still off: nothing new at the next packet: " + n.list());
      j.shutdown();
      check(j.awaitIdle(30000), "drain");
      root.deleteRecursively();
    }
    // (c) Already imported on disk -- the imported file alone (no counts marker), or the marker alone -- with the
    //     setting on and no import file: never shown. The line must not ask for a file the account no longer needs.
    for (String present : new String[]{"imported-" + ACCOUNT + ".jsonl", "import-done-" + ACCOUNT + ".json"}) {
      Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-sidebar-test"));
      Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
      dir.createDirectories();
      dir.joinSegment(present).write((present.startsWith("imported") ? "" : "{}\n").getBytes(StandardCharsets.UTF_8));
      AtomicBoolean on = new AtomicBoolean(true);
      Notices n = new Notices();
      PluginJournal j = journal(root, on, n);
      j.offerPacket(packet(ACCOUNT, "s1", 1, T0));
      j.importRequested();
      j.offerPacket(packet(ACCOUNT, "s1", 2, T0 + 1));
      check(j.awaitIdle(30000) && !n.list().contains(M), "with only " + present + " on disk the account is imported: the line must never show: " + n.list());
      j.shutdown();
      check(j.awaitIdle(30000), "drain");
      root.deleteRecursively();
    }
    // (d) Two accounts, one imported (its marker on disk) and one still waiting for a file. A settings change decides
    //     the line for the account of the LAST packet, not the first one the journal saw -- run in both orders, so a
    //     journal stuck on either account fails. Each step waits for the journal, so the setting it reads is the one set.
    for (boolean importedFirst : new boolean[]{true, false}) {
      Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-sidebar-test"));
      Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
      dir.createDirectories();
      dir.joinSegment("import-done-" + ACCOUNT + ".json").write("{}\n".getBytes(StandardCharsets.UTF_8));
      AtomicBoolean on = new AtomicBoolean(true);
      Notices n = new Notices();
      PluginJournal j = journal(root, on, n);
      String first = importedFirst ? ACCOUNT : OTHER, second = importedFirst ? OTHER : ACCOUNT;
      String forFirst = importedFirst ? null : M, forSecond = importedFirst ? M : null;
      int seq = 1;
      j.offerPacket(packet(first, "s-" + first.substring(0, 2), seq++, T0));
      j.offerPacket(packet(second, "s-" + second.substring(0, 2), seq++, T0 + 1));
      check(j.awaitIdle(30000) && n.list().equals(Arrays.asList(forFirst, forSecond)), "setup, " + first.substring(0, 2) + " then " + second.substring(0, 2) + ": " + n.list());
      on.set(false);
      j.importRequested();
      check(j.awaitIdle(30000), "drain");
      on.set(true);
      j.importRequested();
      check(j.awaitIdle(30000), "drain");
      // The last packet was the second account's, so switching off and on ends on ITS answer: off hides the line
      // (sent only if it was showing), on brings back the second account's line -- never the first account's.
      List<String> want = new ArrayList<>(Arrays.asList(forFirst, forSecond));
      if (forSecond != null) want.addAll(Arrays.asList(null, M));
      check(n.list().equals(want), "off then on after " + second.substring(0, 2) + "'s packet must decide for " + second.substring(0, 2)
        + " (" + (forSecond == null ? "hidden" : "shown") + "): " + n.list());
      // And back to the first account: its packet moves the line, and a settings change now speaks for IT.
      j.offerPacket(packet(first, "s-" + first.substring(0, 2), seq++, T0 + 2));
      check(j.awaitIdle(30000), "drain");
      if (!java.util.Objects.equals(want.get(want.size() - 1), forFirst)) want.add(forFirst);
      on.set(false);
      j.importRequested();
      check(j.awaitIdle(30000), "drain");
      if (want.get(want.size() - 1) != null) want.add(null);
      on.set(true);
      j.importRequested();
      check(j.awaitIdle(30000), "drain");
      if (forFirst != null) want.add(M);
      check(n.list().equals(want), "off then on after " + first.substring(0, 2) + "'s packet must decide for " + first.substring(0, 2)
        + " (" + (forFirst == null ? "hidden" : "shown") + "): " + n.list() + ", wanted " + want);
      // The literal sequences, so the bookkeeping above cannot drift into agreeing with a wrong journal.
      List<String> literal = importedFirst ? Arrays.asList(null, M, null, M, null) : Arrays.asList(M, null, M, null, M);
      check(n.list().equals(literal), "the full sequence, " + (importedFirst ? "imported account first" : "waiting account first") + ": " + n.list());
      j.shutdown();
      check(j.awaitIdle(30000), "drain");
      root.deleteRecursively();
    }
  }

  /** A plugin with the journal started on {@code root} through startJournal(), its panel and the import setting read from {@code on}. */
  static final class Wired {
    final EviLivePlugin plugin = new EviLivePlugin();
    final EviLivePanel panel;
    final PluginJournal journal;

    Wired(Filepath root, AtomicBoolean on) throws Exception {
      set(plugin, "gson", new Gson());
      set(plugin, "config", new EviLiveConfig() {
        public boolean importBridgeHistory() {
          return on.get();
        }
      });
      panel = panel(r -> { });
      set(plugin, "panel", panel);
      Method start = EviLivePlugin.class.getDeclaredMethod("startJournal", Filepath.class);
      start.setAccessible(true);
      start.invoke(plugin, root);
      journal = (PluginJournal) get(plugin, "journal");
      check(journal != null, "startJournal() left no journal");
    }

    void packet(String account, int seq, long ts) throws Exception {
      journal.offerPacket(EviLiveSidebarLinesTest.packet(account, "s-" + account.substring(0, 2), seq, ts)); // one session per account
      check(journal.awaitIdle(30000), "drain");
      edt();
    }

    void setting(AtomicBoolean on, boolean value) throws Exception {
      on.set(value);
      ConfigChanged e = new ConfigChanged();
      e.setGroup("evilive");
      e.setKey("importBridgeHistory");
      e.setNewValue(Boolean.toString(value));
      plugin.onConfigChanged(e);
      check(journal.awaitIdle(30000), "drain");
      edt();
    }

    boolean shown() {
      return panel.importNote.isVisible() && PluginJournal.IMPORT_MISSING_NOTICE.equals(panel.importNote.getText());
    }

    void close(Filepath root) throws Exception {
      journal.shutdown();
      check(journal.awaitIdle(30000), "drain");
      root.deleteRecursively();
    }
  }

  /**
   * The plugin's own wiring (4.0.0, the maintainer's call of 8 Oct 2026: the line is SHOWN): startJournal() relays the journal's
   * line to the panel and a settings change re-reads it. Shown when the import is on and no file exists; NOT shown when the
   * setting is off, when the file exists (it is imported), or when the account is already imported. Every case goes through
   * the real plugin and a real journal, and the shown case is asserted first, so the hidden ones are not vacuous.
   */
  static void importLineWiring() throws Exception {
    // (1) On, no file: shown. Off in the settings panel: gone at once. On again: back at once.
    {
      Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-sidebar-test"));
      AtomicBoolean on = new AtomicBoolean(true);
      Wired w = new Wired(root, on);
      w.packet(ACCOUNT, 1, T0);
      check(w.shown(), "import on, no file: the line must be shown: visible=" + w.panel.importNote.isVisible() + " \"" + w.panel.importNote.getText() + "\"");
      w.setting(on, false);
      check(!w.panel.importNote.isVisible(), "switching the import OFF must hide the line at once");
      w.setting(on, true);
      check(w.shown(), "switching it ON again with no file must show the line at once");
      w.close(root);
    }
    // (2) Off from the start, no file: never shown.
    {
      Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-sidebar-test"));
      AtomicBoolean on = new AtomicBoolean(false);
      Wired w = new Wired(root, on);
      w.packet(ACCOUNT, 1, T0);
      w.packet(ACCOUNT, 2, T0 + 1);
      check(!w.panel.importNote.isVisible(), "import off, no file: the line must not be shown");
      w.close(root);
    }
    // (3) On, and the file exists: it is imported, so nothing is asked for.
    {
      Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-sidebar-test"));
      writeImportFile(root, ACCOUNT);
      AtomicBoolean on = new AtomicBoolean(true);
      Wired w = new Wired(root, on);
      w.packet(ACCOUNT, 1, T0);
      w.packet(ACCOUNT, 2, T0 + 1);
      check(root.joinSegment(PluginJournal.JOURNAL_DIR).joinSegment("imported-" + ACCOUNT + ".jsonl").exists(), "setup: the import ran");
      check(!w.panel.importNote.isVisible(), "import on and the file present: the line must not be shown");
      w.close(root);
    }
    // (4) On, no file, but the account is already imported (its marker on disk): not shown, and not after a settings change.
    {
      Filepath root = Filepath.Unchecked.getRooted(Files.createTempDirectory("evi-sidebar-test"));
      Filepath dir = root.joinSegment(PluginJournal.JOURNAL_DIR);
      dir.createDirectories();
      dir.joinSegment("import-done-" + ACCOUNT + ".json").write("{}\n".getBytes(StandardCharsets.UTF_8));
      AtomicBoolean on = new AtomicBoolean(true);
      Wired w = new Wired(root, on);
      w.packet(ACCOUNT, 1, T0);
      check(!w.panel.importNote.isVisible(), "an already-imported account must not be asked for a file");
      w.setting(on, false);
      w.setting(on, true);
      check(!w.panel.importNote.isVisible(), "an already-imported account: still not shown after the setting goes off and on");
      // and another, not imported account on the same machine is asked for its file
      w.packet(OTHER, 1, T0 + 2);
      check(w.shown(), "another account with no file: shown for it");
      w.close(root);
    }
  }
}
