package com.evi.live;

import net.runelite.client.util.Filepath;
import com.evi.live.journal.PluginFolder;
import com.evi.live.market.WikiPriceClient;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * THE 4.0.0 RELEASE, as facts the build checks (the self-contained switch-over and the Phase 7 rename):
 * <ol>
 *   <li>the Hub listing (runelite-plugin.properties) is ASCII to the byte -- Properties.load reads ISO-8859-1, so one em dash
 *       becomes three characters of mojibake in the most-read text the plugin has -- carries the decided name, description and
 *       tags, no warning= line, and names no companion app;</li>
 *   <li>@PluginDescriptor says the same thing as the listing, and the version is ONE number in build.gradle, the listing and the
 *       User-Agent;</li>
 *   <li>the internalName is evi-flipping in the descriptor, PluginFolder (every sentence naming the data folder) and
 *       settings.gradle, and "evi-live" survives in the sources only as the GitHub repo name and history comments;</li>
 *   <li>THE CONFIG MIGRATION, through a REAL RuneLite 1.13.1 ConfigManager: a stored "Same as scanner" (SAME_AS_SCANNER, written
 *       into every profile that ever loaded the plugin) is replaced by All items when the plugin's defaults are loaded, a stored
 *       Gear survives, the import setting's stored choice survives its rename, and the Exit-risk check starts at Off under its NEW
 *       key while the old forecastHorizon value is left in the profile, unread.</li>
 * </ol>
 */
public final class ReleaseListingTest {
  static int checks;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }

  static final String DISPLAY_NAME = "EVI Live";
  static final String DESCRIPTION = "Flip suggestions for the Grand Exchange: what to buy, how many and at what price, plus a trade journal"
    + " with exact GE tax. Uses only public OSRS Wiki prices; nothing about you is sent.";
  static final List<String> TAGS = List.of("grand exchange", "ge", "flip", "flipping", "trading", "money making", "profit", "suggestions", "journal", "evi");

  public static void main(String[] args) throws Exception {
    Filepath project = TestFiles.project();
    listing(project);
    rename(project);
    migration();
    System.out.println("PASS: release listing (" + checks + " checks) -- runelite-plugin.properties ASCII to the byte with the decided name,"
      + " description and tags and no warning=; @PluginDescriptor agreeing; one version in build.gradle, the listing and the User-Agent; the"
      + " internalName evi-flipping in the descriptor, PluginFolder and settings.gradle; and the config migration through a real ConfigManager"
      + " (Same as scanner -> All items, Gear kept, the import choice kept, the Exit-risk check at Off on its new key)");
  }

  static void listing(Filepath project) throws Exception {
    byte[] raw = TestFiles.read(project.joinSegment("runelite-plugin.properties"));
    for (int i = 0; i < raw.length; i++) check((raw[i] & 0xff) < 0x80, "runelite-plugin.properties must be ASCII: byte " + i + " is 0x" + Integer.toHexString(raw[i] & 0xff));
    Properties props = new Properties();
    try (InputStream in = project.joinSegment("runelite-plugin.properties").openInputStream()) {
      props.load(in);
    }
    check(DISPLAY_NAME.equals(props.getProperty("displayName")), "displayName: " + props.getProperty("displayName"));
    check(DESCRIPTION.equals(props.getProperty("description")), "description: " + props.getProperty("description"));
    check(TAGS.equals(Arrays.asList(props.getProperty("tags").split(","))), "tags: " + props.getProperty("tags"));
    check("com.evi.live.EviLivePlugin".equals(props.getProperty("plugins")) && "standard".equals(props.getProperty("build"))
      && "therealLeEvi".equals(props.getProperty("author")), "plugins / build / author");
    check(props.getProperty("warning") == null, "no warning= line (the maintainer's decision, 6 Oct 2026)");
    for (String key : props.stringPropertyNames()) {
      String v = props.getProperty(key).toLowerCase();
      for (String bad : new String[]{"companion", "bridge", "local", "127.0.0.1", "scanner"}) check(!v.contains(bad), key + " names \"" + bad + "\": " + v);
    }
    PluginDescriptor d = EviLivePlugin.class.getAnnotation(PluginDescriptor.class);
    check(DISPLAY_NAME.equals(d.name()) && DESCRIPTION.equals(d.description()) && TAGS.equals(Arrays.asList(d.tags())),
      "@PluginDescriptor must say what the listing says: " + d.name() + " | " + d.description() + " | " + Arrays.toString(d.tags()));
    String gradle = TestFiles.text(project.joinSegment("build.gradle"));
    Matcher v = Pattern.compile("(?m)^version = '([^']+)'").matcher(gradle);
    check(v.find(), "build.gradle has no version line");
    check("4.0.0".equals(v.group(1)) && v.group(1).equals(props.getProperty("version")) && v.group(1).equals(WikiPriceClient.VERSION),
      "ONE version, 4.0.0: build.gradle " + v.group(1) + ", listing " + props.getProperty("version") + ", User-Agent " + WikiPriceClient.VERSION);
    Matcher pin = Pattern.compile("def runeLiteVersion = '([^']+)'").matcher(gradle);
    check(pin.find() && pin.group(1).matches("\\d+\\.\\d+\\.\\d+"), "build.gradle pins an exact RuneLite release (match plugin-hub/runelite.version at release)");
  }

  static void rename(Filepath project) throws Exception {
    PluginDescriptor d = EviLivePlugin.class.getAnnotation(PluginDescriptor.class);
    check("evi-flipping".equals(PluginFolder.INTERNAL_NAME) && "evi-flipping".equals(d.internalName()),
      "internalName evi-flipping in PluginFolder and @PluginDescriptor: " + PluginFolder.INTERNAL_NAME + " / " + d.internalName());
    check(".runelite/plugin-data/evi-flipping".equals(PluginFolder.PATH), "the data folder: " + PluginFolder.PATH);
    String settings = TestFiles.text(project.joinSegment("settings.gradle")).trim();
    check("rootProject.name = 'evi-flipping'".equals(settings), "settings.gradle: " + settings);
    // "evi-live" may remain only as the GitHub repo (evi-live-plugin) and in history comments naming the old folder.
    Filepath src = TestFiles.at(project, "src", "main", "java");
    List<String> left = new ArrayList<>();
    for (Filepath f : TestFiles.files(src, ".java")) {
      String[] lines = TestFiles.text(f).split("\n");
      for (int i = 0; i < lines.length; i++) {
        String l = lines[i];
        if (!l.contains("evi-live")) continue;
        String rest = l.replace("evi-live-plugin", "");
        boolean comment = l.trim().startsWith("//") || l.trim().startsWith("*") || l.trim().startsWith("/*");
        if (!rest.contains("evi-live") || comment) continue;
        left.add(f.getFileName() + ":" + (i + 1) + ": " + l.trim());
      }
    }
    check(left.isEmpty(), "\"evi-live\" in code (not the repo name, not a comment): " + left);
    // the panel's title and the navigation tooltip are the plugin's name
    String plugin = TestFiles.text(TestFiles.at(src, "com", "evi", "live", "EviLivePlugin.java"));
    check(plugin.contains(".tooltip(\"EVI Live\")"), "the sidebar button's tooltip is the plugin's name");
  }

  static void migration() throws Exception {
    ConfigManager cm = EviLiveRiskSkipTest.realConfigManager(new net.runelite.client.eventbus.EventBus());
    // What an existing profile holds: the old default focus (RuneLite wrote it on first load), a chosen ~1 hour forecast under
    // the old key, and the import switched on.
    cm.setConfiguration("evilive", "suggestionFocus", "SAME_AS_SCANNER");
    cm.setConfiguration("evilive", "forecastHorizon", "ONE_HOUR");
    cm.setConfiguration("evilive", "importBridgeHistory", "true");
    EviLiveConfig real = cm.getConfig(EviLiveConfig.class);
    cm.setDefaultConfiguration(real, false); // what RuneLite's PluginManager does when the plugin is loaded
    check("ALL_ITEMS".equals(cm.getConfiguration("evilive", "suggestionFocus")) && real.suggestionFocus() == SuggestionFocus.ALL_ITEMS,
      "a stored SAME_AS_SCANNER becomes All items when the defaults load: " + cm.getConfiguration("evilive", "suggestionFocus"));
    check(real.importBridgeHistory() && "true".equals(cm.getConfiguration("evilive", "importBridgeHistory")), "the import's stored choice survives its rename");
    check(real.exitRiskCheck() == ForecastHorizon.OFF, "the Exit-risk check starts at Off on its new key");
    check("ONE_HOUR".equals(cm.getConfiguration("evilive", "forecastHorizon")), "the old key is left alone (nothing declares or reads it)");
    // A chosen Gear is kept, and loading the defaults again changes nothing.
    ConfigManager cm2 = EviLiveRiskSkipTest.realConfigManager(new net.runelite.client.eventbus.EventBus());
    cm2.setConfiguration("evilive", "suggestionFocus", "GEAR");
    cm2.setConfiguration("evilive", "exitRiskCheck", "SIX_HOUR");
    EviLiveConfig real2 = cm2.getConfig(EviLiveConfig.class);
    cm2.setDefaultConfiguration(real2, false);
    cm2.setDefaultConfiguration(real2, false);
    check(real2.suggestionFocus() == SuggestionFocus.GEAR && real2.exitRiskCheck() == ForecastHorizon.SIX_HOUR, "chosen values survive the defaults loading, twice");
    check(new TreeSet<>(Arrays.asList("ALL_ITEMS", "GEAR", "BULK")).equals(new TreeSet<>(Arrays.stream(SuggestionFocus.values()).map(Enum::name).collect(Collectors.toList()))),
      "the stored names of the focus setting");
  }
}
