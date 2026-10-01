package com.evi.live;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
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
import javax.swing.SwingConstants;
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
  /** The signature of the card currently drawn, or null when the plain paragraph is showing.
   *  Stops the two-second poll relaying out the sidebar when nothing about the pick has changed. */
  private String shownCard = null;
  /** Everything to do with pairing, kept together so it can be hidden in one move once a key is saved. */
  private final JPanel pairingSection = new JPanel();
  /** A six-pixel square beside the status line: green when the bridge answered, the warn colour when it
   *  has not. Read from the message itself rather than a second signal, because every caller already
   *  writes one and the alternative was an overload on a method used from a dozen places. */
  private final JPanel connectionDot = new JPanel();
  /** How many items this session has set aside, and the way to get them back. Hidden at zero.
   *
   *  It exists because the list was invisible and one-way. Every Skip, Block, "Mark as personal use"
   *  and "I don't have this anymore" adds to it, as does a stale holding EVI re-checks and drops, and
   *  it only ever cleared on a profile change or a client restart. On 28 Sept 2026 novi worked down
   *  from a 441,621 gp 3rd Age robe to a Blighted teleport spell sack worth a few hundred, and the
   *  cause was not the ranking or any floor -- it was that everything better had quietly been set
   *  aside earlier in the session, with nothing on screen saying so or offering it back. */
  private final JLabel skipCount = new JLabel();
  private final JButton clearSkipsButton;
  // The five action-row buttons. Held as fields because which of them APPLY changes with every
  // suggestion: a buy cannot be personal use or already gone, and a holding cannot be blocked.
  private final JButton acceptButton;
  private final JButton personalUseButton;
  private final JButton notHeldButton;
  private final JButton blockButton;
  /** Client-property key carrying a button's glyph, so a theme switch can redraw it in the new colours. */
  private static final String GLYPH = "eviGlyph";
  private final Runnable clearSkips;
  /** Further positions, when the player has asked for more than one. Empty and hidden by default. */
  private final JPanel alsoList = new JPanel();
  private final JLabel alsoHeader = sectionHeader("Also worth buying");
  /** What the list currently shows, so a poll that changes nothing does not relayout the sidebar. */
  private String shownAlso = "";
  /** The advice about offers already placed, one small card each. */
  private final JPanel adviceList = new JPanel();
  /** "Your companion app is out of date", under the connection line. Hidden when there is nothing to say. */
  private final JTextArea staleBridge = new JTextArea();
  /** What the list currently shows, so a poll that changes nothing does not relayout the sidebar. */
  private String shownAdvice = "";
  // Held so suggestionWarning can recolour its accent stripe, exactly as an offer row carries its own.
  private JPanel suggestionCard;
  // Whether the current suggestion would lose GP, kept so a theme switch repaints the right stripe.
  private volatile boolean warned;
  private final JTextArea offerHint = bodyText("");
  // The realised total is split in two on purpose: the figure carries the colour, the line below it
  // carries the caveats. Colouring one text area would paint "Not counted: 17 sales EVI never saw
  // bought" green as well, which reads as if those were profit -- the precise misreading that line
  // exists to prevent.
  private final JLabel profitFigure = new JLabel(" ");
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
    this(pair, skip, personalUse, notHeld, block, resetProfit, () -> { });
  }

  EviLivePanel(Consumer<String> pair, Runnable skip, Runnable personalUse, Runnable notHeld, Runnable block,
               Runnable resetProfit, Runnable clearSkips) {
    this(pair, skip, personalUse, notHeld, block, resetProfit, clearSkips, () -> { });
  }

  EviLivePanel(Consumer<String> pair, Runnable skip, Runnable personalUse, Runnable notHeld, Runnable block,
               Runnable resetProfit, Runnable clearSkips, Runnable accept) {
    this.clearSkips = clearSkips;
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
    // The "observes only / you place every offer" line used to sit here. It is the plugin's own
    // description on the Hub listing and the first line of its README, so the sidebar was the third
    // place saying it -- and on a 225px panel those two lines cost more room than they were worth.
    // The connection state moved to the bottom (see the status strip at the end of this method): it is
    // something to glance at when EVI goes quiet, not something to read past on the way to the pick.

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
    profitFigure.setFont(FontManager.getRunescapeBoldFont());
    profitFigure.setAlignmentX(Component.LEFT_ALIGNMENT);
    role(profitFigure, "text");
    content.add(profitFigure);
    profitLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    content.add(profitLine);
    JButton resetButton = secondaryButton("Reset profit count");
    resetButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    resetButton.getAccessibleContext().setAccessibleDescription("Starts this profit line counting from now. Your trade records, flips and the dashboard's own total are untouched.");
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
    // The five actions as ONE row of icons rather than five stacked buttons.
    //
    // They were added one at a time, each justified on its own, and together they became a stack
    // that crowded a panel 225px wide -- which is all RuneLite gives a plugin. Five icons at 37px
    // is exactly what that width allows, so this row is at its limit and a sixth action would have
    // to earn its place by displacing something.
    //
    // Every one is drawn identically: same face, same border, same colour. None is filled or
    // accented to stand out, because these are ALTERNATIVES and the panel must not express a
    // preference between them. That matters most for "I took this one": a record-keeping control
    // people press because it is the brightest thing on screen produces a record that only looks
    // like evidence, which is worse than having none. A one-word caption under each icon means the
    // row can be read without hovering; the tooltip carries the full sentence.
    JPanel actionRow = new JPanel(new GridLayout(1, 5, 5, 0));
    actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
    actionRow.setOpaque(false);
    actionRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
    acceptButton = iconButton(EviIcons.Glyph.TOOK_IT, "Took it",
      "Tell EVI you acted on this suggestion, so it can measure whether following it actually made GP. Recorded on your own machine only; press again to take it back.", accept);
    personalUseButton = iconButton(EviIcons.Glyph.PERSONAL_USE, "Mine",
      "For something you are holding: bought for your own use, not to flip. It stops being suggested and never counts toward profit. Only this purchase -- buying the item again later is unaffected.", personalUse);
    notHeldButton = iconButton(EviIcons.Glyph.GONE, "Gone",
      "For something you are holding but no longer have: used in-game, or sold while EVI was not running. Whatever part of it EVI saw sold still counts toward profit.", notHeld);
    JButton skipButton = iconButton(EviIcons.Glyph.SKIP, "Skip",
      "Set this suggestion aside for this session and check for the next-best one. Does not affect any offer you have already placed.", skip);
    blockButton = iconButton(EviIcons.Glyph.BLOCK, "Block",
      "Never suggest buying this item again. Undo it in the dashboard's Blocked items list. Stock you already hold still gets its sell reminder.", block);
    actionRow.add(acceptButton);
    actionRow.add(personalUseButton);
    actionRow.add(notHeldButton);
    actionRow.add(skipButton);
    actionRow.add(blockButton);
    content.add(Box.createVerticalStrut(6));
    content.add(actionRow);

    // What this session has set aside, and the way back. Hidden entirely at zero, so it costs nothing
    // on screen until it is the thing actually shaping what EVI can offer.
    skipCount.setFont(FontManager.getRunescapeSmallFont());
    skipCount.setAlignmentX(Component.LEFT_ALIGNMENT);
    skipCount.setVisible(false);
    role(skipCount, "muted");
    content.add(Box.createVerticalStrut(6));
    content.add(skipCount);
    clearSkipsButton = secondaryButton("Show skipped items again");
    clearSkipsButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    clearSkipsButton.setVisible(false);
    clearSkipsButton.getAccessibleContext().setAccessibleDescription(
      "Clears everything you have skipped, blocked for this session, or marked as personal use, so EVI can suggest those items again. Blocked items stay blocked; this only undoes the session's own list.");
    clearSkipsButton.addActionListener(e -> clearSkips.run());
    content.add(Box.createVerticalStrut(4));
    content.add(clearSkipsButton);

    // -- Also worth buying: the second and third positions, when the player has asked for more than
    // one ("Suggestions at once"). Deliberately BELOW the buttons above, so Skip, Block and the rest
    // read as belonging to the primary card they sit under, which is the only one they act on.
    // Hidden entirely at the default of one, so nothing about this panel changes for anyone who has
    // not gone looking for it. --
    alsoHeader.setVisible(false);
    content.add(alsoHeader);
    alsoList.setLayout(new BoxLayout(alsoList, BoxLayout.Y_AXIS));
    alsoList.setOpaque(false);
    alsoList.setAlignmentX(Component.LEFT_ALIGNMENT);
    alsoList.setVisible(false);
    role(alsoList, "bg");
    content.add(alsoList);

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
    adviceList.setLayout(new BoxLayout(adviceList, BoxLayout.Y_AXIS));
    adviceList.setOpaque(false);
    adviceList.setAlignmentX(Component.LEFT_ALIGNMENT);
    role(adviceList, "bg");
    content.add(adviceList);

    // -- Pairing. One section rather than loose children, so it can be taken away entirely once the
    // key is saved: it is setup, and setup that is done is just room the sidebar no longer has. The
    // fields stay built and are only hidden, so clearing a key puts them back without rebuilding. --
    pairingSection.setLayout(new BoxLayout(pairingSection, BoxLayout.Y_AXIS));
    pairingSection.setOpaque(false);
    pairingSection.setAlignmentX(Component.LEFT_ALIGNMENT);
    role(pairingSection, "bg");
    content.add(pairingSection);
    pairingSection.add(section());
    pairingSection.add(sectionHeader("Pairing"));
    pairingSection.add(bodyText("Start the EVI bridge, then paste its RuneLite plugin key below. This is not your Scanner key or Jagex login."));
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
    pairingSection.add(key);
    JButton save = primaryButton("Save pairing key");
    save.setAlignmentX(Component.LEFT_ALIGNMENT);
    save.addActionListener(e -> {
      char[] entered = key.getPassword();
      try { pair.accept(new String(entered)); }
      finally { Arrays.fill(entered, '\0'); key.setText(""); }
    });
    pairingSection.add(Box.createVerticalStrut(6));
    pairingSection.add(save);
    pairingSection.add(bodyText("Saved only on this PC. Destination: 127.0.0.1:51743. No account password, chat, or inventory is collected."));

    // -- The status strip, last on purpose. What EVI is doing is worth a glance when it goes quiet; it
    // is not worth the top of the panel every time you look for a trade. --
    content.add(section());
    JPanel strip = new JPanel(new BorderLayout(6, 0));
    strip.setOpaque(false);
    strip.setAlignmentX(Component.LEFT_ALIGNMENT);
    role(strip, "bg");
    connectionDot.setPreferredSize(new Dimension(6, 6));
    connectionDot.setMaximumSize(new Dimension(6, 6));
    connectionDot.setOpaque(true);
    strip.add(connectionDot, BorderLayout.WEST);
    strip.add(status, BorderLayout.CENTER);
    content.add(strip);

    // Directly under the connection line, at novi's request on 1 Oct 2026: it is a fact ABOUT the
    // connection, not advice about a trade, so it belongs beside the thing it describes rather than
    // at the top of the advice list competing with offers. Hidden until there is something to say.
    staleBridge.setVisible(false);
    staleBridge.setAlignmentX(Component.LEFT_ALIGNMENT);
    staleBridge.setFont(FontManager.getRunescapeSmallFont());
    staleBridge.setLineWrap(true);
    staleBridge.setWrapStyleWord(true);
    staleBridge.setEditable(false);
    staleBridge.setFocusable(false);
    staleBridge.setOpaque(false);
    // The same caret rule every other sidebar text area follows: a JTextArea's default caret chases
    // setText and drags the sidebar with it on every poll. Caught by panelTest the moment this was
    // added, which is exactly what that test is for.
    ((DefaultCaret) staleBridge.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
    staleBridge.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
    role(staleBridge, "warn");
    content.add(staleBridge);

    add(content, BorderLayout.NORTH);
  }

  /** The companion app is older than this plugin. Null or empty hides the line entirely.
   *
   *  Sits under the connection status rather than in the advice list: an advice card is about an
   *  offer, and this is about the connection the whole panel depends on. EDT only. */
  void staleBridge(String message) {
    SwingUtilities.invokeLater(() -> {
      boolean show = message != null && !message.isEmpty();
      if (show) setIfChanged(staleBridge, message);
      staleBridge.setVisible(show);
      staleBridge.revalidate();
      staleBridge.repaint();
    });
  }

  void status(String message) {
    SwingUtilities.invokeLater(() -> {
      setIfChanged(status, message);
      EviTheme.Palette p = EviTheme.palette();
      connectionDot.setBackground(message != null && message.startsWith("Connected") ? p.good : p.warn);
      connectionDot.repaint();
    });
  }

  /** How many items this session has set aside. Zero hides the line and the button entirely.
   *
   *  Worth stating on screen rather than only in the request, because the list is cumulative, it is
   *  fed by four buttons and one automatic path, and until 28 Sept 2026 nothing revealed it: a player
   *  who had skipped their way down to a worthless suggestion had no way to tell that was why, and no
   *  way back short of restarting the client. EDT only. */
  /** One action in the row: a drawn glyph, a one-word caption beneath it, and the whole sentence as
   *  its tooltip. An icon-only row is guessable only after hovering everything once; the caption
   *  costs about ten pixels and removes that. */
  private JButton iconButton(EviIcons.Glyph glyph, String caption, String description, Runnable action) {
    JButton b = new JButton(caption);
    EviTheme.Palette p = EviTheme.palette();
    b.setIcon(EviIcons.of(glyph, p.buttonText, 16));
    b.setDisabledIcon(EviIcons.of(glyph, p.muted, 16));
    b.setVerticalTextPosition(SwingConstants.BOTTOM);
    b.setHorizontalTextPosition(SwingConstants.CENTER);
    b.setIconTextGap(3);
    b.setFont(FontManager.getRunescapeSmallFont());
    b.setFocusPainted(false);
    b.setMargin(new Insets(3, 0, 2, 0));
    b.setToolTipText(description);
    b.getAccessibleContext().setAccessibleDescription(description);
    b.addActionListener(e -> action.run());
    role(b, "button");
    b.putClientProperty(GLYPH, glyph);
    return b;
  }

  /** Which of the five row actions apply to what is on screen.
   *
   *  The ones that do not apply are DIMMED, never hidden, so the row keeps its length and every icon
   *  keeps its position -- the row stays learnable, and a player is not left wondering where a
   *  control went. A dimmed icon keeps a tooltip too, but one that says why it is unavailable rather
   *  than only what it would do: before this, pressing Personal use on a buy popped a refusal, which
   *  is a worse way to learn the same thing.
   *
   *  "I took this one" is dimmed rather than shown doing nothing when the bridge sent no id for the
   *  suggestion -- an older bridge cannot record an acceptance. EDT only. */
  void actions(boolean canAccept, boolean accepted, boolean holding, boolean canForget, boolean buying) {
    SwingUtilities.invokeLater(() -> {
      acceptButton.setEnabled(canAccept);
      acceptButton.setText(accepted ? "Taken" : "Took it");
      acceptButton.setToolTipText(!canAccept
        ? "This needs a newer companion app before EVI can record what you took."
        : accepted
          ? "Recorded as taken. Press again to take that back."
          : "Tell EVI you acted on this suggestion, so it can measure whether following it actually made GP. Recorded on your own machine only; press again to take it back.");
      personalUseButton.setEnabled(holding);
      personalUseButton.setToolTipText(holding
        ? "Bought for your own use, not to flip. It stops being suggested and never counts toward profit. Only this purchase -- buying the item again later is unaffected."
        : "Only applies to something you are already holding.");
      // Not just `holding`: this closes one specific purchase, so it needs a buy behind it. Gear the
      // idle-inventory tier offered was never watched being bought and has none.
      notHeldButton.setEnabled(canForget);
      notHeldButton.setToolTipText(canForget
        ? "You no longer have this: used in-game, or sold while EVI was not running. Whatever part of it EVI saw sold still counts toward profit."
        : holding
          ? "EVI cannot trace this back to one purchase, so there is nothing to close. Use Mine instead."
          : "Only applies to something you are already holding.");
      // Block stays buy-only on purpose: when you already own some, EVI keeps reminding you to sell
      // it, because going quiet about stock you hold is how GP gets stuck.
      blockButton.setEnabled(buying);
      blockButton.setToolTipText(buying
        ? "Never suggest buying this item again. Undo it in the dashboard's Blocked items list. Stock you already hold still gets its sell reminder."
        : "Only applies to a buy suggestion. EVI still reminds you to sell stock you own.");
    });
  }

  void skipped(int count) {
    SwingUtilities.invokeLater(() -> {
      boolean any = count > 0;
      // Cleared rather than merely hidden at zero, so nothing stale is left behind a hidden label for
      // a screen reader or a future caller to pick up.
      skipCount.setText(!any ? "" : count == 1 ? "1 item set aside this session" : count + " items set aside this session");
      skipCount.setVisible(any);
      clearSkipsButton.setVisible(any);
      skipCount.revalidate();
      skipCount.repaint();
    });
  }

  /** The second and third positions, when the player has asked for more than one.
   *
   *  Drawn smaller and plainer than the primary card on purpose. These are not alternatives to it and
   *  not a plan for the whole stack: each was ranked on the cash the ones before it left unspent, and
   *  each passed the same checks, so the order is the order to buy them in. The Skip and Block buttons
   *  sit above this list and act only on the primary, which is why the list is placed below them.
   *
   *  An empty or absent list hides the section completely, so the default of one suggestion leaves the
   *  panel exactly as it was. EDT only. */
  void alsoSuggested(java.util.List<Suggestion> extras) {
    SwingUtilities.invokeLater(() -> {
      StringBuilder sig = new StringBuilder();
      if (extras != null) for (Suggestion s : extras)
        if (s != null) sig.append(s.itemId).append('|').append(s.quantity).append('|')
          .append(s.buyPrice).append('|').append(s.expectedProfit).append(';');
      if (sig.toString().equals(shownAlso)) return;
      shownAlso = sig.toString();

      alsoList.removeAll();
      boolean any = extras != null && !extras.isEmpty();
      alsoHeader.setVisible(any);
      alsoList.setVisible(any);
      if (any) {
        EviTheme.Palette p = EviTheme.palette();
        int n = 1;
        for (Suggestion s : extras) {
          if (s == null) continue;
          JPanel card = new JPanel();
          card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
          card.setAlignmentX(Component.LEFT_ALIGNMENT);
          role(card, "card");
          card.setBorder(new CompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, p.muted),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
          if (s.reasoning != null && !s.reasoning.isEmpty()) card.setToolTipText(s.reasoning);

          JLabel name = new JLabel((++n) + ". " + (s.name == null ? "" : s.name));
          name.setFont(FontManager.getRunescapeSmallFont());
          name.setForeground(p.text);
          name.setAlignmentX(Component.LEFT_ALIGNMENT);
          card.add(name);

          if (s.quantity > 0 && s.buyPrice > 0) {
            JLabel line = new JLabel(String.format("Buy %,d at %,d gp", s.quantity, s.buyPrice));
            line.setFont(FontManager.getRunescapeSmallFont());
            line.setForeground(p.muted);
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(line);
          }
          if (s.expectedProfit != null) {
            JLabel profit = new JLabel((s.expectedProfit >= 0 ? "+" : "") + String.format("%,d", s.expectedProfit));
            profit.setFont(FontManager.getRunescapeSmallFont());
            profit.setForeground(s.expectedProfit >= 0 ? p.good : p.bad);
            profit.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(profit);
          }
          // The same verdict the primary card carries. An extra position that nobody checked would be
          // the weakest card on the panel wearing the same clothes as the strongest.
          if (s.verdict != null && s.verdict.label != null && !s.verdict.label.isEmpty()) {
            JLabel v = new JLabel(s.verdict.label.toUpperCase());
            v.setFont(FontManager.getRunescapeSmallFont());
            v.setForeground("warn".equals(s.verdict.level) ? p.bad
              : "caution".equals(s.verdict.level) ? p.warn : p.good);
            v.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(v);
          }
          alsoList.add(Box.createVerticalStrut(6));
          alsoList.add(card);
        }
      }
      alsoList.revalidate();
      alsoList.repaint();
    });
  }

  /** Advice about offers already placed, as small cards rather than one paragraph.
   *
   *  These used to be concatenated into a single text area with blank lines between them: four full
   *  sentences, each carrying every figure, stacked under Active offers. On a 225px panel that is a
   *  wall. Each is now a coloured edge, the item, a few words and one line of figures -- and the whole
   *  sentence is the tooltip, so nothing is lost, it is just one hover away instead of always there.
   *
   *  A card with no label came from a bridge older than this and is drawn as its sentence, the way it
   *  always was. EDT only. */
  void advice(java.util.List<EviLivePlugin.AdviceCard> cards) {
    SwingUtilities.invokeLater(() -> {
      StringBuilder sig = new StringBuilder();
      if (cards != null) for (EviLivePlugin.AdviceCard c : cards)
        sig.append(c.level).append(c.label).append(c.name).append(c.figures).append(c.message).append('|');
      if (sig.toString().equals(shownAdvice)) return;
      shownAdvice = sig.toString();

      adviceList.removeAll();
      if (cards == null || cards.isEmpty()) {
        adviceList.setVisible(false);
        adviceList.revalidate();
        adviceList.repaint();
        return;
      }
      adviceList.setVisible(true);
      EviTheme.Palette p = EviTheme.palette();
      for (EviLivePlugin.AdviceCard c : cards) {
        if (c == null) continue;
        if (c.label == null || c.label.isEmpty()) {          // older bridge: the sentence, as before
          JTextArea plain = bodyText(c.message == null ? "" : c.message);
          plain.setAlignmentX(Component.LEFT_ALIGNMENT);
          adviceList.add(Box.createVerticalStrut(6));
          adviceList.add(plain);
          continue;
        }
        // Three tones, not two. "warn" is red, "info" is the THEME'S OWN TEXT colour, and anything
        // else is amber. Before 1 Oct 2026 there were only two, so every card that was not a warning
        // drew amber -- and the holdings lines added 30 Sept ("you're holding 1 Gilded d'hide
        // vambraces, +254,063 over cost") therefore looked exactly like "your sell is below
        // break-even". A plain statement of what you own must not wear a warning's colour.
        //
        // The theme's text colour rather than a literal white: that is near-white under RuneLite's
        // scheme and a parchment cream under the Old School one, so it stays right in both. Green is
        // deliberately not an option here -- it already means a FILLED offer in the Active offers
        // list (ColorScheme.PROGRESS_COMPLETE_COLOR) and a positive profit figure.
        Color edge = "warn".equals(c.level) ? p.bad : "info".equals(c.level) ? p.text : p.warn;
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        role(card, "card");
        card.setBorder(new CompoundBorder(
          BorderFactory.createMatteBorder(0, 3, 0, 0, edge),
          BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        // The full sentence, one hover away. It is the same text the scanner shows.
        card.setToolTipText(c.message);

        JLabel name = new JLabel(c.name == null ? "" : c.name);
        name.setFont(FontManager.getRunescapeSmallFont());
        name.setForeground(p.text);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(name);

        JLabel label = new JLabel(c.label.toUpperCase());
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(edge);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(label);

        if (c.figures != null && !c.figures.isEmpty()) {
          JLabel figures = new JLabel(c.figures);
          figures.setFont(FontManager.getRunescapeSmallFont());
          figures.setForeground(p.muted);
          figures.setAlignmentX(Component.LEFT_ALIGNMENT);
          card.add(figures);
        }
        adviceList.add(Box.createVerticalStrut(6));
        adviceList.add(card);
      }
      adviceList.revalidate();
      adviceList.repaint();
    });
  }

  /** Hides the pairing fields once a key is saved, and brings them back if it is ever cleared. The
   *  sidebar is 225px wide and setup that is finished is the cheapest thing on it to give up. */
  void paired(boolean paired) {
    SwingUtilities.invokeLater(() -> {
      if (pairingSection.isVisible() == !paired) return;
      pairingSection.setVisible(!paired);
      pairingSection.revalidate();
      pairingSection.repaint();
    });
  }

  void suggestion(String message) {
    SwingUtilities.invokeLater(() -> {
      showProse();
      setIfChanged(suggestion, message == null || message.isEmpty() ? "No suggestion yet." : message);
    });
  }

  /** The suggestion as a card rather than a paragraph.
   *
   *  The sidebar used to state a number and bury whether EVI trusted it. On 28 September 2026 a player
   *  bought 30 Contract of Glyphic Attenuation expecting the 2,977,560 gp the quoted spread implied; it
   *  was worth about 350,000, and EVI knew -- it had demoted the pick and said so, in the middle of a
   *  paragraph. The figure was visible and the doubt was not.
   *
   *  So the doubt gets a shape: a coloured edge, a few words of verdict, the headline profit, and the
   *  checks as short lines. `prose` is the reasoning that used to be the whole card; it is still what
   *  gets shown when the bridge sends no verdict, which is how this stays compatible with a bridge
   *  older than it. EDT only. */
  void suggestion(Suggestion s, String prose) {
    SwingUtilities.invokeLater(() -> {
      if (s == null || s.verdict == null || s.verdict.checks == null || s.verdict.checks.isEmpty()) {
        showProse();
        setIfChanged(suggestion, prose == null || prose.isEmpty() ? "No suggestion yet." : prose);
        return;
      }
      // The poll re-sends the same pick every two seconds. Rebuilding the card each time would relayout
      // the sidebar for nothing, the same reason setIfChanged exists for the text areas.
      String signature = cardSignature(s);
      if (signature.equals(shownCard)) return;
      shownCard = signature;
      buildCard(s);
      suggestionCard.revalidate();
      suggestionCard.repaint();
    });
  }

  private static String cardSignature(Suggestion s) {
    StringBuilder b = new StringBuilder();
    b.append(s.itemId).append('|').append(s.quantity).append('|').append(s.buyPrice).append('|')
      .append(s.sellPrice).append('|').append(s.expectedProfit).append('|').append(s.action).append('|')
      .append(s.verdict.level).append('|').append(s.verdict.label);
    for (Suggestion.Check c : s.verdict.checks) b.append('|').append(c.ok).append(c.text);
    return b.toString();
  }

  /** Puts the plain reasoning paragraph back, undoing any card built over it. */
  private void showProse() {
    if (shownCard == null) return;
    shownCard = null;
    suggestionCard.removeAll();
    suggestionCard.setBorder(cardPadding());
    suggestionCard.add(suggestion, BorderLayout.CENTER);
    suggestionCard.revalidate();
    suggestionCard.repaint();
  }

  private static CompoundBorder cardPadding() {
    return new CompoundBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0),
      BorderFactory.createEmptyBorder(8, 8, 8, 8));
  }

  private void buildCard(Suggestion s) {
    EviTheme.Palette p = EviTheme.palette();
    Color edge = "clear".equals(s.verdict.level) ? p.good
      : "warn".equals(s.verdict.level) ? p.bad : p.warn;

    suggestionCard.removeAll();
    suggestionCard.setLayout(new BoxLayout(suggestionCard, BoxLayout.Y_AXIS));
    // The coloured edge IS the verdict at a glance; the words beside it are for when that is not enough.
    suggestionCard.setBorder(new CompoundBorder(
      BorderFactory.createMatteBorder(0, 3, 0, 0, edge),
      BorderFactory.createEmptyBorder(8, 8, 8, 8)));

    JPanel head = new JPanel(new BorderLayout());
    head.setOpaque(false);
    head.setAlignmentX(Component.LEFT_ALIGNMENT);
    JLabel what = new JLabel(("sell".equals(s.action) ? "SELL " : "BUY ") + String.format("%,d", s.quantity));
    what.setFont(FontManager.getRunescapeSmallFont());
    what.setForeground(p.muted);
    JLabel verdict = new JLabel(s.verdict.label == null ? "" : s.verdict.label.toUpperCase());
    verdict.setFont(FontManager.getRunescapeSmallFont());
    verdict.setForeground(edge);
    head.add(what, BorderLayout.WEST);
    head.add(verdict, BorderLayout.EAST);
    suggestionCard.add(head);

    JLabel name = new JLabel(s.name == null ? "" : s.name);
    name.setFont(FontManager.getRunescapeBoldFont());
    name.setForeground(p.text);
    name.setAlignmentX(Component.LEFT_ALIGNMENT);
    suggestionCard.add(Box.createVerticalStrut(3));
    suggestionCard.add(name);

    if (s.expectedProfit != null) {
      JLabel profit = new JLabel((s.expectedProfit >= 0 ? "+" : "") + String.format("%,d", s.expectedProfit));
      profit.setFont(FontManager.getRunescapeBoldFont());
      profit.setForeground(s.expectedProfit >= 0 ? p.good : p.bad);
      profit.setAlignmentX(Component.LEFT_ALIGNMENT);
      suggestionCard.add(Box.createVerticalStrut(2));
      suggestionCard.add(profit);
    }

    JLabel prices = new JLabel(String.format("%,d → %,d", s.buyPrice, s.sellPrice));
    prices.setFont(FontManager.getRunescapeSmallFont());
    prices.setForeground(p.muted);
    prices.setAlignmentX(Component.LEFT_ALIGNMENT);
    suggestionCard.add(Box.createVerticalStrut(2));
    suggestionCard.add(prices);

    suggestionCard.add(Box.createVerticalStrut(5));
    for (Suggestion.Check c : s.verdict.checks) {
      if (c == null || c.text == null) continue;
      // A mark as well as a colour: colour alone is not a distinction everyone can see, and these lines
      // carry the reason a pick is or is not trustworthy.
      String mark = Boolean.TRUE.equals(c.ok) ? "✓ " : Boolean.FALSE.equals(c.ok) ? "! " : "· ";
      JTextArea line = bodyText(mark + c.text);
      line.setForeground(Boolean.FALSE.equals(c.ok) ? edge : p.muted);
      line.setAlignmentX(Component.LEFT_ALIGNMENT);
      suggestionCard.add(line);
    }
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
      else if ("good".equals(role)) jc.setForeground(p.good);
      else if ("bad".equals(role)) jc.setForeground(p.bad);
      else if ("field".equals(role)) { jc.setBackground(p.card); jc.setForeground(p.text); }
      else if ("button".equals(role) || "primary".equals(role)) {
        jc.setBackground(p.buttonFace);
        jc.setForeground(p.buttonText);
        // A drawn glyph carries its colour inside the Icon, so a theme switch has to rebuild it --
        // repainting the button alone would leave the old colour sitting in the icon.
        Object glyph = jc.getClientProperty(GLYPH);
        if (glyph instanceof EviIcons.Glyph && jc instanceof JButton) {
          ((JButton) jc).setIcon(EviIcons.of((EviIcons.Glyph) glyph, p.buttonText, 16));
          ((JButton) jc).setDisabledIcon(EviIcons.of((EviIcons.Glyph) glyph, p.muted, 16));
        }
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
      if (p == null) {
        profitFigure.setText(" ");
        role(profitFigure, "text");
        profitFigure.setForeground(EviTheme.palette().text);
        setIfChanged(profitLine, "Profit unavailable -- the bridge did not answer.");
        return;
      }
      // Green up, red down, plain at nothing. Zero is deliberately NOT green: a player who has made
      // exactly nothing has not made a profit, and a green nought says otherwise at a glance. The
      // colour is set through the role so a theme switch repaints it with everything else.
      String tone = p.gp > 0 ? "good" : p.gp < 0 ? "bad" : "text";
      profitFigure.setText((p.gp >= 0 ? "+" : "") + String.format("%,d", p.gp) + " gp");
      role(profitFigure, tone);
      EviTheme.Palette pal = EviTheme.palette();
      profitFigure.setForeground("good".equals(tone) ? pal.good : "bad".equals(tone) ? pal.bad : pal.text);
      StringBuilder text = new StringBuilder();
      text.append(p.since == null ? "(everything EVI has matched" : "(since you reset");
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
    SwingUtilities.invokeLater(() -> {
      // The old figure has to go with the old count -- leaving a green total above "Counting from
      // now..." would show a number that no longer describes anything.
      profitFigure.setText(" ");
      role(profitFigure, "text");
      profitFigure.setForeground(EviTheme.palette().text);
      setIfChanged(profitLine, "Counting from now...");
    });
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
