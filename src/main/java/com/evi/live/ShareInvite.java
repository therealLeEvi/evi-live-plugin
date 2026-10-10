package com.evi.live;

import com.evi.live.journal.PluginFolder;
import com.evi.live.journal.TradeLogExport;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Voluntary trade-log sharing (9 Oct 2026, the maintainer's decision and approved mock; SELF-CONTAINED-PLAN, the 9 Oct bullets).
 * NO NETWORK: EVI only saves a file the player can open, read and send themselves; uploading it is the player's own act.
 *
 * <p>The wording here is the approved mock's, word for word. The sidebar's invitation is plain on purpose (the sidebar never
 * nudges); "Not now" stores the version it was dismissed at under {@link #CONFIG_KEY}, and the invitation comes back only when
 * the plugin's version differs ({@link #shown}).
 *
 * <p>"Open folder" from the mock is NOT offered: the only RuneLite ways to open a folder ({@code LinkBrowser.open},
 * {@code Desktop.open}) are on the Plugin Hub's forbidden list (HubRulesScanTest), so the saved dialog copies the file's location
 * to the clipboard instead and says so.
 */
final class ShareInvite {
  private ShareInvite() {}

  /** Hidden config item (group evilive): the plugin version at which "Not now" was pressed. A new keyName, never a reused one. */
  static final String CONFIG_KEY = "shareInviteDismissedVersion";

  static final String INVITE = "Want to help improve EVI? Your logs will make a difference!";
  static final String SAVE_BUTTON = "Save my trade log...";
  static final String NOT_NOW_BUTTON = "Not now";
  static final String NOT_NOW_NOTE = "Not now hides this until the next EVI update.";

  static final String CONFIRM_TITLE = "Share your trade log";
  static final List<String> CONFIRM_TEXT = List.of(
    "This saves a file on your computer that you can open, read and send yourself. EVI never uploads anything.",
    "It contains: every Grand Exchange offer EVI saw (item, buy or sell, price, quantity, how much filled, when, and whether it was "
      + "cancelled), EVI's suggested price when it suggested that item, and your finished flips with their profit or loss.",
    "It does not contain: your account, character name, cash stack or bank.",
    "Times are exact, because that is what makes the data useful. On a rarely traded item, someone watching that item could still "
      + "recognise a trade by its time.");
  static final String CONFIRM_SAVE = "Save file";
  static final String CONFIRM_CANCEL = "Cancel";

  static final String SAVED_TITLE = "Trade log saved";
  static final String SHARE_WHERE = "To share it, go to #share-your-log on the EVI Discord (discord.gg/gFcEBHknVN), press Create Ticket "
    + "and upload the file in the private channel that opens. Thank you, it really helps.";
  static final String COPIED = "The location is copied to your clipboard. ";
  static final String OK = "OK";

  static final String FAILED_TITLE = "Trade log not saved";

  /** Whether the invitation shows: always, unless "Not now" was pressed at exactly this version. */
  static boolean shown(String dismissedVersion, String currentVersion) {
    return dismissedVersion == null || !dismissedVersion.equals(currentVersion);
  }

  /** "Saved 1,234 offers and 56 flips to:" -- the real counts, grouped the US way whatever the machine's locale. */
  static String savedCounts(int offers, int flips) {
    return String.format(Locale.US, "Saved %,d offers and %,d flips to:", offers, flips);
  }

  /** The file as a player finds it, relative to their home folder (the folder's name from PluginFolder, never typed here). */
  static String displayPath(String fileName) {
    return PluginFolder.PATH + "/" + TradeLogExport.SHARE_DIR + "/" + fileName;
  }

  /** The saved dialog's closing paragraph: the clipboard sentence only when the copy worked. */
  static String savedClosing(boolean copied) {
    return (copied ? COPIED : "") + SHARE_WHERE;
  }

  /** The failure dialog's text: what failed and why, never a stack trace. */
  static String failedText(Throwable error) {
    Throwable t = error;
    while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException
      || t instanceof java.util.concurrent.ExecutionException)) t = t.getCause();
    String why = t.getMessage();
    if (why == null || why.trim().isEmpty()) why = t.getClass().getSimpleName();
    return "The trade log could not be saved: " + why;
  }

  // ------------------------------------------------------------------------------------------ dialogs (Swing EDT)

  /** The confirm dialog: true only when "Save file" was pressed. Modal. */
  static boolean confirm(Component parent) {
    JPanel body = body();
    for (String s : CONFIRM_TEXT) body.add(paragraph(s));
    return show(parent, CONFIRM_TITLE, body, CONFIRM_SAVE, CONFIRM_CANCEL) == 0;
  }

  /** The saved dialog: the counts, the file's location in a selectable field, and where to share it. Modal. */
  static void saved(Component parent, int offers, int flips, String fileName, boolean copied) {
    JPanel body = body();
    body.add(paragraph(savedCounts(offers, flips)));
    JTextField path = new JTextField(displayPath(fileName));
    path.setEditable(false);
    path.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    path.setBackground(ColorScheme.DARKER_GRAY_COLOR);
    path.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    path.setCaretColor(ColorScheme.LIGHT_GRAY_COLOR);
    path.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
      BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    path.setAlignmentX(Component.LEFT_ALIGNMENT);
    path.setMaximumSize(new Dimension(Integer.MAX_VALUE, path.getPreferredSize().height));
    body.add(path);
    body.add(Box.createVerticalStrut(6));
    body.add(paragraph(savedClosing(copied)));
    show(parent, SAVED_TITLE, body, OK);
  }

  /** The failure dialog. Modal. */
  static void failed(Component parent, Throwable error) {
    JPanel body = body();
    body.add(paragraph(failedText(error)));
    show(parent, FAILED_TITLE, body, OK);
  }

  private static final int PARAGRAPH_WIDTH = 360;

  private static JPanel body() {
    JPanel body = new JPanel();
    body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
    body.setBackground(ColorScheme.DARK_GRAY_COLOR);
    return body;
  }

  private static JTextArea paragraph(String text) {
    JTextArea a = new JTextArea(text);
    ((DefaultCaret) a.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
    a.setEditable(false);
    a.setFocusable(false);
    a.setLineWrap(true);
    a.setWrapStyleWord(true);
    a.setOpaque(false);
    a.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    a.setFont(FontManager.getRunescapeFont());
    a.setAlignmentX(Component.LEFT_ALIGNMENT);
    a.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
    // A wrapping text area needs a width to wrap at; without one it asks for a single line as wide as its text.
    a.setSize(new Dimension(PARAGRAPH_WIDTH, Short.MAX_VALUE));
    a.setPreferredSize(new Dimension(PARAGRAPH_WIDTH, a.getPreferredSize().height));
    return a;
  }

  /** A modal dialog in RuneLite's colours with the given buttons, the first on the left; returns the index pressed, -1 if closed. */
  private static int show(Component parent, String title, JComponent body, String... buttons) {
    Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    JDialog d = new JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
    d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    final int[] pressed = {-1};
    JPanel root = new JPanel(new BorderLayout(0, 10));
    root.setBackground(ColorScheme.DARK_GRAY_COLOR);
    root.setBorder(BorderFactory.createEmptyBorder(14, 14, 12, 14));
    root.add(body, BorderLayout.CENTER);
    JPanel row = new JPanel();
    row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
    row.setOpaque(false);
    row.add(Box.createHorizontalGlue());
    for (int i = 0; i < buttons.length; i++) {
      if (i > 0) row.add(Box.createHorizontalStrut(6));
      JButton b = button(buttons[i]);
      final int index = i;
      b.addActionListener(e -> {
        pressed[0] = index;
        d.dispose();
      });
      row.add(b);
    }
    root.add(row, BorderLayout.SOUTH);
    d.setContentPane(root);
    d.pack(); // each paragraph was sized to PARAGRAPH_WIDTH first, so it asks for its wrapped height at that width
    d.setResizable(false);
    d.setLocationRelativeTo(owner);
    d.setVisible(true); // modal: returns when a button or the close box ends it
    return pressed[0];
  }

  /** The dialogs' plain button: RuneLite's darker grey, its border grey and its text colour; nothing marks one as preferred. */
  private static JButton button(String text) {
    JButton b = new JButton(text);
    b.setFocusPainted(false);
    b.setFont(FontManager.getRunescapeSmallFont());
    b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
    b.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
      BorderFactory.createEmptyBorder(5, 12, 5, 12)));
    return b;
  }
}
