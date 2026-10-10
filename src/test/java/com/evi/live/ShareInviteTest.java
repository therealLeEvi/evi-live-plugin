package com.evi.live;

import com.evi.live.journal.PluginFolder;
import com.evi.live.market.WikiPriceClient;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

/**
 * Voluntary trade-log sharing (9 Oct 2026), the sidebar half: the "Not now" rule as a pure function, the approved wording word
 * for word, the dialogs' figures (US grouping, the folder named by PluginFolder, an error with no stack trace), and the wiring --
 * "Not now" writes the CURRENT version under the hidden key and hides the block; a stored version equal to the current one keeps
 * it hidden on the next start; any other (or none) shows it. Headless: the modal dialogs themselves are not opened here.
 */
public final class ShareInviteTest {
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

  static void edt() throws Exception {
    SwingUtilities.invokeAndWait(() -> { });
    SwingUtilities.invokeAndWait(() -> { });
  }

  static void visit(Container parent, List<Component> out) {
    for (Component c : parent.getComponents()) {
      out.add(c);
      if (c instanceof Container) visit((Container) c, out);
    }
  }

  static JButton button(Container root, String text) {
    List<Component> all = new ArrayList<>();
    visit(root, all);
    for (Component c : all) if (c instanceof JButton && text.equals(((JButton) c).getText())) return (JButton) c;
    throw new AssertionError("no button \"" + text + "\"");
  }

  static EviLivePlugin plugin(Map<String, String> stored) throws Exception {
    EviLivePlugin plugin = new EviLivePlugin();
    set(plugin, "config", new EviLiveConfig() {
      public String shareInviteDismissedVersion() {
        String v = stored.get(ShareInvite.CONFIG_KEY);
        return v == null ? "" : v;
      }
    });
    set(plugin, "configWriter", (java.util.function.BiConsumer<String, String>) stored::put);
    return plugin;
  }

  static EviLivePanel built(EviLivePlugin plugin) throws Exception {
    AtomicReference<EviLivePanel> ref = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> ref.set(plugin.buildPanel()));
    set(plugin, "panel", ref.get());
    edt();
    return ref.get();
  }

  public static void main(String[] args) throws Exception {
    // The rule, as a pure function.
    String v = WikiPriceClient.VERSION;
    check(!ShareInvite.shown(v, v), "dismissed at this version: hidden");
    check(ShareInvite.shown("", v), "never dismissed (empty): shown");
    check(ShareInvite.shown(null, v), "never dismissed (null): shown");
    check(ShareInvite.shown("3.0.0", "4.0.0"), "dismissed at an older version: shown again after the update");
    check(ShareInvite.shown("4.0.0", "3.12.1"), "any OTHER version shows it, older or newer");
    check("shareInviteDismissedVersion".equals(ShareInvite.CONFIG_KEY), "the hidden keyName");

    // The approved wording, word for word.
    check("Want to help improve EVI? Your logs will make a difference!".equals(ShareInvite.INVITE), "the invitation");
    check("Save my trade log...".equals(ShareInvite.SAVE_BUTTON) && "Not now".equals(ShareInvite.NOT_NOW_BUTTON), "the two buttons");
    check("Not now hides this until the next EVI update.".equals(ShareInvite.NOT_NOW_NOTE), "the muted line");
    check("Share your trade log".equals(ShareInvite.CONFIRM_TITLE) && "Save file".equals(ShareInvite.CONFIRM_SAVE)
      && "Cancel".equals(ShareInvite.CONFIRM_CANCEL), "the confirm dialog's title and buttons");
    check(ShareInvite.CONFIRM_TEXT.equals(List.of(
      "This saves a file on your computer that you can open, read and send yourself. EVI never uploads anything.",
      "It contains: every Grand Exchange offer EVI saw (item, buy or sell, price, quantity, how much filled, when, and whether it was cancelled), EVI's suggested price when it suggested that item, and your finished flips with their profit or loss.",
      "It does not contain: your account, character name, cash stack or bank.",
      "Times are exact, because that is what makes the data useful. On a rarely traded item, someone watching that item could still recognise a trade by its time.")),
      "the confirm dialog's four paragraphs");
    check("Trade log saved".equals(ShareInvite.SAVED_TITLE), "the saved dialog's title");
    check("Saved 12,345 offers and 1,002 flips to:".equals(ShareInvite.savedCounts(12345, 1002)), "real counts, US grouping: "
      + ShareInvite.savedCounts(12345, 1002));
    check("Saved 0 offers and 1 flips to:".equals(ShareInvite.savedCounts(0, 1)), "the counts are printed as they are");
    check((PluginFolder.PATH + "/share/evi-trade-log-2026-10-09.csv").equals(ShareInvite.displayPath("evi-trade-log-2026-10-09.csv")),
      "the location shown names the plugin's own folder (PluginFolder), never a typed one");
    check(("The location is copied to your clipboard. To share it, go to #share-your-log on the EVI Discord "
      + "(discord.gg/gFcEBHknVN), press Create Ticket and upload the file in the private channel that opens. Thank you, it really helps.").equals(ShareInvite.savedClosing(true)), "the saved dialog's closing, copied");
    check(ShareInvite.savedClosing(false).startsWith("To share it, go to #share-your-log"), "no clipboard claim when the copy failed");
    String failed = ShareInvite.failedText(new java.util.concurrent.CompletionException(new java.nio.file.AccessDeniedException("evi-trade-log.csv")));
    check(failed.equals("The trade log could not be saved: evi-trade-log.csv"), "the failure states why, unwrapped: " + failed);
    check(ShareInvite.failedText(new IllegalStateException()).equals("The trade log could not be saved: IllegalStateException"),
      "no message: the kind of failure, never a stack trace");

    // The wiring: shown at first, "Not now" stores THIS version and hides it; the next start keeps it hidden.
    Map<String, String> stored = new HashMap<>();
    EviLivePlugin first = plugin(stored);
    check(first.shareInviteShown(), "never dismissed: shown");
    EviLivePanel panel = built(first);
    check(panel.shareSection.isVisible(), "the panel shows the invitation from the first frame");
    SwingUtilities.invokeAndWait(() -> button(panel, "Not now").doClick());
    edt();
    check(stored.equals(Map.of(ShareInvite.CONFIG_KEY, v)), "Not now writes the current version under the hidden key, and nothing else: " + stored);
    check(!panel.shareSection.isVisible(), "Not now hides the whole block at once");
    EviLivePanel again = built(plugin(stored));
    check(!again.shareSection.isVisible(), "the next start, same version: still hidden");
    Map<String, String> older = new HashMap<>(Map.of(ShareInvite.CONFIG_KEY, "0.0.1"));
    EviLivePanel updated = built(plugin(older));
    check(updated.shareSection.isVisible(), "dismissed at another version: shown again");
    System.out.println("PASS: share invitation (" + checks + " checks): the Not-now rule, the approved wording, the dialogs' figures,"
      + " and Not now storing this version and hiding the block until the version changes");
  }
}
