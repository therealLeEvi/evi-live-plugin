package com.evi.live;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
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

  EviLivePanel(Consumer<String> pair, Runnable skip, Runnable personalUse) {
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
