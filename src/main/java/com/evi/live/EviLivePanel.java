package com.evi.live;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The sidebar panel. Styled against net.runelite.client.ui.ColorScheme (the same palette every
 * other plugin's own panel, and the client shell itself, already uses) for all its neutral chrome
 * -- panel/section backgrounds, borders, body text -- plus EviTheme.BRAND as the one accent color,
 * so this reads as a native part of RuneLite rather than plain Swing/OS-native controls (a white
 * JPasswordField, a stock gray JButton) sitting on top of it. Grouped into three visually distinct
 * sections (about, suggestion, pairing) separated by a thin top border and spacing, rather than one
 * unstructured stack of labels.
 */
final class EviLivePanel extends PluginPanel {
  private final JTextArea status = bodyText("Waiting for setup.");
  private final JTextArea suggestion = bodyText("No suggestion yet.");
  private final JTextArea offerHint = bodyText("");
  private final JPanel offerList = new JPanel();

  EviLivePanel(Consumer<String> pair, Runnable skip, Runnable personalUse, Runnable notHeld) {
    setLayout(new BorderLayout());
    setBackground(ColorScheme.DARK_GRAY_COLOR);

    JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setBackground(ColorScheme.DARK_GRAY_COLOR);
    content.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));

    // -- About --
    JLabel title = new JLabel("EVI Live · Local");
    title.setFont(FontManager.getRunescapeBoldFont());
    title.setForeground(EviTheme.BRAND);
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    content.add(title);
    content.add(bodyText("Observes Grand Exchange offers only. You place and manage every offer yourself."));
    content.add(status);

    // -- Current suggestion, set off as its own card so it reads the same way the item-picker
    // overlay's own backdrop does (dark navy behind the brand-teal suggestion text). --
    content.add(section());
    JLabel suggestionLabel = sectionHeader("Current suggestion");
    content.add(suggestionLabel);
    JPanel suggestionCard = new JPanel(new BorderLayout());
    suggestionCard.setAlignmentX(Component.LEFT_ALIGNMENT);
    suggestionCard.setBackground(EviTheme.BRAND_DARK);
    suggestionCard.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    suggestion.setForeground(EviTheme.BRAND);
    suggestion.setFont(FontManager.getRunescapeSmallFont());
    suggestion.setOpaque(false);
    suggestionCard.add(suggestion, BorderLayout.CENTER);
    content.add(suggestionCard);
    JButton skipButton = secondaryButton("Skip this suggestion");
    skipButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    skipButton.getAccessibleContext().setAccessibleDescription("Excludes the current suggestion and checks for the next-best one. Does not affect any offer you've already placed.");
    skipButton.addActionListener(e -> skip.run());
    content.add(Box.createVerticalStrut(6));
    content.add(skipButton);
    JButton personalUseButton = secondaryButton("Mark as personal use");
    personalUseButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    personalUseButton.getAccessibleContext().setAccessibleDescription("For a \"you're holding this, sell it\" suggestion: marks this one purchase as bought for your own use, not a flip. It won't be suggested again and won't count toward profit if sold. Only this purchase -- buying this item again later is unaffected.");
    personalUseButton.addActionListener(e -> personalUse.run());
    content.add(Box.createVerticalStrut(6));
    content.add(personalUseButton);
    JButton notHeldButton = secondaryButton("I don't have this anymore");
    notHeldButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    notHeldButton.getAccessibleContext().setAccessibleDescription("For a \"you're holding this, sell it\" suggestion about something you no longer have: used in-game, or sold while EVI wasn't running. It stops being suggested for good, and whatever part of it EVI saw sold still counts toward profit.");
    notHeldButton.addActionListener(e -> notHeld.run());
    content.add(Box.createVerticalStrut(6));
    content.add(notHeldButton);

    // -- Active offers: one row per occupied GE slot (see offers()), so everything sitting in the
    // GE is visible at a glance, followed by any cancel/relist or slow-fill hint for those offers
    // (see EviLivePlugin.offerDriftHint/offerFillHint), hidden when there's nothing to flag. Text
    // only, same as every other panel element here -- EVI never opens a menu, clicks a button, or
    // touches the offer itself; the player decides whether to act on it. --
    content.add(section());
    content.add(sectionHeader("Active offers"));
    offerList.setLayout(new BoxLayout(offerList, BoxLayout.Y_AXIS));
    offerList.setOpaque(false);
    offerList.setAlignmentX(Component.LEFT_ALIGNMENT);
    renderOffers(Collections.emptyList());
    content.add(offerList);
    offerHint.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
    offerHint.setVisible(false);
    content.add(offerHint);

    // -- Pairing --
    content.add(section());
    content.add(sectionHeader("Pairing"));
    content.add(bodyText("Start the EVI bridge, then paste its RuneLite plugin key below. This is not your Scanner key or Jagex login."));
    JPasswordField key = new JPasswordField(20);
    key.setAlignmentX(Component.LEFT_ALIGNMENT);
    key.setMaximumSize(new Dimension(Integer.MAX_VALUE, key.getPreferredSize().height));
    key.getAccessibleContext().setAccessibleName("RuneLite plugin key");
    key.setBackground(ColorScheme.DARKER_GRAY_COLOR);
    key.setForeground(Color.WHITE);
    key.setCaretColor(Color.WHITE);
    key.setBorder(new CompoundBorder(
      BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
      BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    content.add(key);
    JButton save = primaryButton("Save pairing key");
    save.setAlignmentX(Component.LEFT_ALIGNMENT);
    save.addActionListener(e -> {
      char[] entered = key.getPassword();
      try { pair.accept(new String(entered)); }
      finally { Arrays.fill(entered, '\0'); key.setText(""); }
    });
    content.add(Box.createVerticalStrut(6));
    content.add(save);
    content.add(bodyText("Saved only on this PC. Destination: 127.0.0.1:51743. No account password, chat, or inventory is collected."));

    add(content, BorderLayout.NORTH);
  }

  void status(String message) {
    SwingUtilities.invokeLater(() -> status.setText(message));
  }

  void suggestion(String message) {
    SwingUtilities.invokeLater(() -> suggestion.setText(message == null || message.isEmpty() ? "No suggestion yet." : message));
  }

  /** Orange suggestion text for a sell that would lose GP right now; brand teal otherwise. */
  void suggestionWarning(boolean loss) {
    SwingUtilities.invokeLater(() -> suggestion.setForeground(loss ? ColorScheme.PROGRESS_INPROGRESS_COLOR : EviTheme.BRAND));
  }

  void offerHint(String message) {
    SwingUtilities.invokeLater(() -> {
      boolean any = message != null && !message.isEmpty();
      offerHint.setText(any ? message : "");
      offerHint.setVisible(any);
    });
  }

  /** Replaces the Active offers list with one row per occupied GE slot. Safe from any thread. */
  void offers(List<EviLivePlugin.OfferRow> rows) {
    List<EviLivePlugin.OfferRow> snapshot = rows == null ? Collections.emptyList() : rows;
    SwingUtilities.invokeLater(() -> renderOffers(snapshot));
  }

  /** EDT only; offers() is the thread-safe entry point. Package-private so the panel test can
   *  render synchronously. */
  void renderOffers(List<EviLivePlugin.OfferRow> rows) {
    offerList.removeAll();
    if (rows.isEmpty()) {
      offerList.add(bodyText("No offers in the Grand Exchange."));
    } else {
      for (EviLivePlugin.OfferRow row : rows) {
        offerList.add(offerRow(row));
        offerList.add(Box.createVerticalStrut(4));
      }
    }
    offerList.revalidate();
    offerList.repaint();
  }

  /** e.g. "BUY  Steel cannonball". */
  static String offerTitle(EviLivePlugin.OfferRow row) {
    return (row.buying ? "BUY  " : "SELL  ") + (row.name == null || row.name.isEmpty() ? "item " + row.itemId : row.name);
  }

  /** e.g. "4,000 / 11,000 at 243 gp - Buying". */
  static String offerDetail(EviLivePlugin.OfferRow row) {
    return String.format("%,d / %,d at %,d gp - %s", row.filled, row.total, row.price, offerStatus(row));
  }

  /** Short status text for a raw GrandExchangeOfferState name. */
  static String offerStatus(EviLivePlugin.OfferRow row) {
    switch (row.state == null ? "" : row.state) {
      case "BUYING": return "Buying";
      case "SELLING": return "Selling";
      case "BOUGHT": return "Bought, collect";
      case "SOLD": return "Sold, collect";
      case "CANCELLED_BUY":
      case "CANCELLED_SELL": return "Cancelled, collect";
      default: return row.state == null ? "" : row.state;
    }
  }

  /** Left accent: green once finished (ready to collect), red if cancelled, orange while still
   *  trading -- RuneLite's own progress colors, so the state reads without the text. */
  private static JPanel offerRow(EviLivePlugin.OfferRow row) {
    String state = row.state == null ? "" : row.state;
    Color accent = state.startsWith("CANCELLED") ? ColorScheme.PROGRESS_ERROR_COLOR
      : ("BOUGHT".equals(state) || "SOLD".equals(state)) ? ColorScheme.PROGRESS_COMPLETE_COLOR
      : ColorScheme.PROGRESS_INPROGRESS_COLOR;
    JPanel card = new JPanel(new BorderLayout());
    card.setAlignmentX(Component.LEFT_ALIGNMENT);
    card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
    card.setBorder(new CompoundBorder(
      BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
      BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    JLabel title = new JLabel(offerTitle(row));
    title.setFont(FontManager.getRunescapeSmallFont());
    title.setForeground(row.buying ? EviTheme.BRAND : ColorScheme.GRAND_EXCHANGE_PRICE);
    JLabel detail = new JLabel(offerDetail(row));
    detail.setFont(FontManager.getRunescapeSmallFont());
    detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    card.add(title, BorderLayout.NORTH);
    card.add(detail, BorderLayout.SOUTH);
    int height = card.getPreferredSize().height;
    card.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    // A long item name would otherwise force the card wider than the sidebar and get clipped; with
    // no minimum width the labels shrink to fit and Swing ends them with "...".
    card.setMinimumSize(new Dimension(0, height));
    return card;
  }

  /** A thin top rule with breathing room above/below it, used to visually separate the panel's
   *  three sections instead of one unbroken stack of labels. */
  private static JPanel section() {
    JPanel rule = new JPanel();
    rule.setAlignmentX(Component.LEFT_ALIGNMENT);
    rule.setOpaque(false);
    rule.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createEmptyBorder(10, 0, 10, 0),
      BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR)));
    rule.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
    return rule;
  }

  private static JLabel sectionHeader(String value) {
    JLabel label = new JLabel(value);
    label.setFont(FontManager.getRunescapeBoldFont());
    label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    label.setAlignmentX(Component.LEFT_ALIGNMENT);
    label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
    return label;
  }

  private static JTextArea bodyText(String value) {
    JTextArea field = new JTextArea(value);
    field.setEditable(false);
    field.setLineWrap(true);
    field.setWrapStyleWord(true);
    field.setOpaque(false);
    field.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    field.setFont(FontManager.getRunescapeSmallFont());
    field.setAlignmentX(Component.LEFT_ALIGNMENT);
    field.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
    return field;
  }

  /** Flat, RuneLite-native button chrome shared by both buttons -- no OS-native gray/beveled look. */
  private static JButton flatButton(String text) {
    JButton button = new JButton(text);
    button.setFocusPainted(false);
    button.setFont(FontManager.getRunescapeSmallFont());
    button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
    button.setForeground(Color.WHITE);
    button.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
    return button;
  }

  /** The pairing save button, EVI's one primary/committing action in this panel -- outlined in the
   *  brand color so it stands out from the plain secondary button below. */
  private static JButton primaryButton(String text) {
    JButton button = flatButton(text);
    button.setBorder(new CompoundBorder(
      BorderFactory.createLineBorder(EviTheme.BRAND),
      BorderFactory.createEmptyBorder(5, 9, 5, 9)));
    return button;
  }

  private static JButton secondaryButton(String text) {
    return flatButton(text);
  }

  static BufferedImage icon() {
    BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setColor(EviTheme.BRAND);
      g.fillRoundRect(2, 2, 20, 20, 6, 6);
      g.setColor(EviTheme.BRAND_DARK);
      g.fillRect(7, 6, 3, 12);
      g.fillRect(10, 6, 7, 3);
      g.fillRect(10, 11, 5, 2);
      g.fillRect(10, 15, 7, 3);
    } finally { g.dispose(); }
    return image;
  }
}
