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
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;
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
  // Held so suggestionWarning can recolour its accent stripe, exactly as an offer row carries its own.
  private JPanel suggestionCard;
  // Whether the current suggestion would lose GP, kept so a theme switch repaints the right stripe.
  private volatile boolean warned;
  private final JTextArea offerHint = bodyText("");
  private final JTextArea profitLine = bodyText("Waiting for the bridge.");
  private final JPanel offerList = new JPanel();

  // What a component is, so applyTheme can repaint it: "bg" panel background, "card" a raised card,
  // "accent" EVI-coloured text, "text" body text, "muted" a section heading, "button", "field",
  // "rule" a separator, "stripe" the suggestion card's accent border.
  private static final String ROLE = "eviRole";
  private static <T extends JComponent> T role(T c, String role) { c.putClientProperty(ROLE, role); return c; }

  EviLivePanel(Consumer<String> pair, Runnable skip, Runnable personalUse, Runnable notHeld) {
    this(pair, skip, personalUse, notHeld, () -> { }, () -> { });
  }

  EviLivePanel(Consumer<String> pair, Runnable skip, Runnable personalUse, Runnable notHeld, Runnable block) {
    this(pair, skip, personalUse, notHeld, block, () -> { });
  }

  EviLivePanel(Consumer<String> pair, Runnable skip, Runnable personalUse, Runnable notHeld, Runnable block, Runnable resetProfit) {
    setLayout(new BorderLayout());
    role(this, "bg");

    JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    role(content, "bg");
    content.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));

    // -- About --
    JLabel title = new JLabel("EVI Live · Local");
    title.setFont(FontManager.getRunescapeBoldFont());
    role(title, "accent");
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    content.add(title);
    content.add(bodyText("Observes Grand Exchange offers only. You place and manage every offer yourself."));
    content.add(status);

    // -- Current suggestion, styled exactly like an Active offers row below (see renderOffers):
    // RuneLite's own darker-gray card with a coloured stripe down the left edge, body text in the
    // client's standard light gray. It used to be dark navy with brand-teal body text, which made
    // the one card the player reads most the only part of the sidebar that did not look like the
    // rest of the client. The stripe keeps EVI's teal, and turns orange for a sale that would lose
    // GP right now -- the same colour an offer row uses for its own in-progress state. --
    // -- Realised profit since the count began, with a Reset. Deliberately the bridge's own matched-flip
    // total, the same number the scanner shows, and it says what it leaves out rather than rounding the
    // story: unmatched sales and unsold purchases are not profit. --
    content.add(section());
    content.add(sectionHeader("Profit since counting began"));
    profitLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    content.add(profitLine);
    JButton resetButton = secondaryButton("Reset profit count");
    resetButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    resetButton.getAccessibleContext().setAccessibleDescription("Starts this profit line counting from now. Your trade records, flips and the scanner's own total are untouched.");
    resetButton.addActionListener(e -> resetProfit.run());
    content.add(Box.createVerticalStrut(6));
    content.add(resetButton);

    content.add(section());
    JLabel suggestionLabel = sectionHeader("Current suggestion");
    content.add(suggestionLabel);
    suggestionCard = new JPanel(new BorderLayout());
    suggestionCard.setAlignmentX(Component.LEFT_ALIGNMENT);
    role(suggestionCard, "card");
    role(suggestion, "text");
    suggestion.setFont(FontManager.getRunescapeSmallFont());
    suggestion.setOpaque(false);
    suggestionCard.add(suggestion, BorderLayout.CENTER);
    content.add(suggestionCard);
    JButton skipButton = secondaryButton("Skip this suggestion");
    skipButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    skipButton.getAccessibleContext().setAccessibleDescription("Excludes the current suggestion and checks for the next-best one. Does not affect any offer you've already placed.");
    skipButton.addActionListener(e -> skip.run());
    // Block is Skip made permanent: asked for so a player who never wants an item suggested again
    // can say so in one click, instead of looking up its item ID for the blocklist setting.
    JButton blockButton = secondaryButton("Block this item");
    blockButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    blockButton.getAccessibleContext().setAccessibleDescription("Never suggest buying this item again. Undo it in the scanner's Blocked items list. Stock you already hold still gets its sell reminder.");
    blockButton.addActionListener(e -> block.run());
    content.add(Box.createVerticalStrut(6));
    content.add(skipButton);
    content.add(Box.createVerticalStrut(6));
    content.add(blockButton);
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
    SwingUtilities.invokeLater(() -> setIfChanged(status, message));
  }

  void suggestion(String message) {
    SwingUtilities.invokeLater(() -> setIfChanged(suggestion, message == null || message.isEmpty() ? "No suggestion yet." : message));
  }

  /** Rewrites a text area only when its text actually differs. The poll re-sends the same text every
   *  two seconds, and rewriting it anyway re-lays-out the sidebar for nothing -- and, before the
   *  caret fix in bodyText, scrolled it too. EDT only. Package-private so the panel test can check it. */
  static boolean setIfChanged(JTextArea area, String text) {
    String next = text == null ? "" : text;
    if (next.equals(area.getText())) return false;
    area.setText(next);
    return true;
  }

  /** An orange stripe and orange text for a sell that would lose GP right now; the usual teal stripe
   *  and light-gray text otherwise. The text colour still changes as well as the stripe: a losing
   *  sale is the one thing here the player must not skim past. */
  void suggestionWarning(boolean loss) {
    warned = loss;
    SwingUtilities.invokeLater(() -> {
      EviTheme.Palette p = EviTheme.palette();
      suggestion.putClientProperty(ROLE, loss ? "warn" : "text");
      suggestion.setForeground(loss ? p.warn : p.text);
      if (suggestionCard != null) suggestionCard.setBorder(suggestionBorder(loss ? p.warn : p.accent));
    });
  }

  /** Repaints every component this panel owns in the chosen scheme (see PanelTheme). Called once at
   *  construction and again whenever the setting changes, so switching schemes never needs the panel
   *  rebuilt or the client restarted. Components are found by the role they were tagged with rather
   *  than by being held in fields: the offer rows and the suggestion card are rebuilt constantly, and
   *  a list of references would go stale every poll. EDT only. */
  void applyTheme(PanelTheme theme) {
    SwingUtilities.invokeLater(() -> {
      EviTheme.use(theme);
      paintTree(this);
      if (suggestionCard != null) suggestionCard.setBorder(suggestionBorder(warned ? EviTheme.palette().warn : EviTheme.palette().accent));
      revalidate();
      repaint();
    });
  }

  private static void paintTree(Component c) {
    if (c instanceof JComponent) {
      EviTheme.Palette p = EviTheme.palette();
      JComponent jc = (JComponent) c;
      Object role = jc.getClientProperty(ROLE);
      if ("bg".equals(role)) jc.setBackground(p.background);
      else if ("card".equals(role)) jc.setBackground(p.card);
      else if ("accent".equals(role)) jc.setForeground(p.accent);
      else if ("sell".equals(role)) jc.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
      else if ("text".equals(role)) jc.setForeground(p.text);
      else if ("muted".equals(role)) jc.setForeground(p.muted);
      else if ("warn".equals(role)) jc.setForeground(p.warn);
      else if ("field".equals(role)) { jc.setBackground(p.card); jc.setForeground(p.text); }
      else if ("button".equals(role) || "primary".equals(role)) {
        jc.setBackground(p.buttonFace);
        jc.setForeground(p.buttonText);
        if ("primary".equals(role)) jc.setBorder(new CompoundBorder(
          BorderFactory.createLineBorder(p.accent), BorderFactory.createEmptyBorder(5, 9, 5, 9)));
      }
    }
    if (c instanceof java.awt.Container) for (Component child : ((java.awt.Container) c).getComponents()) paintTree(child);
  }

  /** The Active offers row's own border: a 3px accent stripe on the left, then padding. */
  private static javax.swing.border.Border suggestionBorder(Color accent) {
    return new CompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
      BorderFactory.createEmptyBorder(6, 6, 6, 8));
  }

  void offerHint(String message) {
    SwingUtilities.invokeLater(() -> {
      boolean any = message != null && !message.isEmpty();
      setIfChanged(offerHint, any ? message : "");
      if (offerHint.isVisible() != any) offerHint.setVisible(any);
    });
  }

  /** Replaces the Active offers list with one row per occupied GE slot. Safe from any thread. */
  /** The bridge's realised-profit figure, or null when it could not be read this poll. EDT only. */
  void profit(EviLivePlugin.Profit p) {
    SwingUtilities.invokeLater(() -> {
      if (p == null) { setIfChanged(profitLine, "Profit unavailable -- the bridge did not answer."); return; }
      StringBuilder text = new StringBuilder();
      text.append(p.gp >= 0 ? "+" : "").append(String.format("%,d", p.gp)).append(" gp");
      text.append(p.since == null ? " (everything EVI has matched" : " (since you reset");
      text.append(", ").append(p.trades).append(p.trades == 1 ? " trade" : " trades");
      if (p.winners + p.losers > 0) text.append(": ").append(p.winners).append(" up, ").append(p.losers).append(" down");
      text.append(")");
      // What the figure is not: a running tally of the cash stack. Said every time, because it is the
      // difference between a total a player can trust and one they quietly stop believing.
      if (p.unmatchedSales > 0 || p.openPositions > 0) {
        text.append("\nNot counted: ");
        if (p.unmatchedSales > 0) text.append(p.unmatchedSales).append(" sale").append(p.unmatchedSales == 1 ? "" : "s").append(" EVI never saw bought");
        if (p.unmatchedSales > 0 && p.openPositions > 0) text.append(", ");
        if (p.openPositions > 0) text.append(p.openPositions).append(" purchase").append(p.openPositions == 1 ? "" : "s").append(" not yet sold");
        text.append(".");
      }
      setIfChanged(profitLine, text.toString());
    });
  }

  /** Immediate feedback on the Reset click; the real figure arrives with the next poll. */
  void profitResetPending() {
    SwingUtilities.invokeLater(() -> setIfChanged(profitLine, "Counting from now..."));
  }

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
    role(card, "card");
    card.setBorder(new CompoundBorder(
      BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
      BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    JLabel title = new JLabel(offerTitle(row));
    title.setFont(FontManager.getRunescapeSmallFont());
    role(title, row.buying ? "accent" : "sell");
    JLabel detail = new JLabel(offerDetail(row));
    detail.setFont(FontManager.getRunescapeSmallFont());
    role(detail, "text");
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
      BorderFactory.createMatteBorder(1, 0, 0, 0, EviTheme.palette().rule)));
    role(rule, "rule");
    rule.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
    return rule;
  }

  private static JLabel sectionHeader(String value) {
    JLabel label = new JLabel(value);
    label.setFont(FontManager.getRunescapeBoldFont());
    role(label, "muted");
    label.setAlignmentX(Component.LEFT_ALIGNMENT);
    label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
    return label;
  }

  private static JTextArea bodyText(String value) {
    JTextArea field = new JTextArea(value);
    // A JTextArea's default caret follows every setText to the end of the new text and then scrolls
    // the sidebar to keep that caret visible. These areas are rewritten on every 2-second poll, and
    // the offer warnings sit at the bottom -- so the sidebar was dragged back down to them every two
    // seconds and could not stay scrolled to the top (reported from the live client). Read-only text
    // has no use for a caret that moves, so it never does.
    ((DefaultCaret) field.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
    field.setEditable(false);
    field.setLineWrap(true);
    field.setWrapStyleWord(true);
    field.setOpaque(false);
    role(field, "text");
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
    role(button, "button");
    button.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
    return button;
  }

  /** The pairing save button, EVI's one primary/committing action in this panel -- outlined in the
   *  brand color so it stands out from the plain secondary button below. */
  private static JButton primaryButton(String text) {
    JButton button = flatButton(text);
    role(button, "primary");
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
