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
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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
 * JPasswordField, a stock gray JButton) sitting on top of it. Grouped into visually distinct
 * sections separated by a thin top border and spacing, rather than one unstructured stack of labels.
 */
final class EviLivePanel extends PluginPanel {
  private final JTextArea status = bodyText("Starting up.");
  private final JTextArea suggestion = bodyText("No suggestion yet.");
  /** The signature of the card currently drawn, or null when the plain paragraph is showing.
   *  Stops the two-second poll relaying out the sidebar when nothing about the pick has changed. */
  private String shownCard = null;
  /** A six-pixel square beside the status line: green once the OSRS Wiki prices have arrived, the warn colour until
   *  then. Read from the message itself rather than a second signal, because every caller already
   *  writes one and the alternative was an overload on a method used from a dozen places. */
  private final JPanel connectionDot = new JPanel();
  /** How many items are set aside right now, and the way to get them back. Hidden at zero. Since
   *  6 Oct 2026 that is the session's own exclusions plus this account's four-hour Skips.
   *
   *  It exists because the list was invisible and one-way. Every Skip, Block, "Mark as personal use"
   *  and "I don't have this anymore" adds to it, as does a stale holding EVI re-checks and drops, and
   *  it only ever cleared on a profile change or a client restart. On 28 Sept 2026 a player worked down
   *  from a pick worth a few hundred thousand gp to one worth a few hundred, and the
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
  /** "Import is on, but no file was found ...", under the connection line. Hidden unless the journal says so.
   *  Package-private so a test can read it without reflection. */
  final JTextArea importNote = bodyText("");
  /** One blocked item in the Blocked items list: its id (what Unblock sends back) and the name shown. */
  static final class BlockedRow {
    final int itemId;
    final String name;

    BlockedRow(int itemId, String name) {
      this.itemId = itemId;
      this.name = name;
    }
  }
  /** The Blocked items list's heading, rows and its one plain line (a failed Unblock). Hidden until the built-in engine shows a
   *  list; package-private so a test can read them. */
  final JLabel blockedHeader = sectionHeader("Blocked on this character");
  final JPanel blockedList = new JPanel();
  final JTextArea blockedNote = bodyText("");
  /** What an Unblock button calls with its item id (the plugin's unblockItem); nothing until the plugin sets it. */
  private volatile java.util.function.IntConsumer unblock = id -> { };
  /** What a holding line's menu calls with that line (the plugin's holdingLinePersonalUse / holdingLineGone); nothing until
   *  the plugin sets them. */
  private volatile Consumer<EviLivePlugin.AdviceCard> linePersonalUse = c -> { };
  private volatile Consumer<EviLivePlugin.AdviceCard> lineGone = c -> { };
  /** What the list shows now, so a poll that changes nothing does not relayout the sidebar. */
  private String shownBlocked = "";
  /** "Loading price history: 140 of 336 hours. ...", under the connection line. Shown only while buy suggestions wait for
   *  the price history; hidden otherwise. A bodyText, so its caret is pinned. Package-private so a test can read it without reflection. */
  final JTextArea backfillNote = bodyText("");
  /** The invitation to share a trade log, at the very bottom: a rule, the line, "Save my trade log..." and "Not now", and the
   *  muted line under them. Hidden until the plugin shows it. Package-private so a test can read it without reflection. */
  final JPanel shareSection = new JPanel();
  private volatile Runnable shareSaveAction = () -> { };
  private volatile Runnable shareNotNowAction = () -> { };
  /** What the list currently shows, so a poll that changes nothing does not relayout the sidebar. */
  private String shownAdvice = "";
  // Held so suggestionWarning can recolour its accent stripe, exactly as an offer row carries its own.
  private JPanel suggestionCard;
  // Whether the current suggestion would lose GP, kept so a theme switch repaints the right stripe.
  private volatile boolean warned;
  private final JTextArea offerHint = bodyText("");
  // The realised total is split in two on purpose: the figure carries the colour, the line below it
  // carries the caveats. Colouring one text area would paint "Not counted: 9 sales EVI never saw
  // bought" green as well, which reads as if those were profit -- the precise misreading that line
  // exists to prevent.
  private final JLabel profitFigure = new JLabel(" ");
  private final JTextArea profitLine = bodyText("Waiting for your trade record.");
  private final JPanel offerList = new JPanel();
  /** The three risk buttons, Low / Medium / High, in RiskLevel order. All drawn identically; the chosen
   *  one differs ONLY by a lighter outline (see paintRisk). */
  private final JButton[] riskButtons = new JButton[RiskLevel.values().length];
  /** The one quiet line under the buttons: the chosen level's aim. */
  private final JTextArea riskAim = bodyText(RiskLevel.LOW.aim());
  /** Which level the buttons currently show, so a theme switch can redraw the right outline. */
  private volatile RiskLevel shownRisk = RiskLevel.LOW;
  /** The player's "Max share of cash per trade", as last told; null until the plugin says. It decides whether the
   *  High line names the missing trade-size limit. */
  private volatile MaxTradeShare shownShare;
  /** Client-property key carrying a risk button's level. */
  static final String RISK = "eviRisk";
  /** Every component of the Risk level section -- its rule, heading, button row and the line under it -- so ONE
   *  switch (RiskLevel.SHOWN) shows or hides all four together. Hidden since 7 Oct 2026; BoxLayout gives an
   *  invisible child no room, so a hidden section leaves no gap. */
  final List<JComponent> riskSection = new java.util.ArrayList<>();

  // What a component is, so applyTheme can repaint it: "bg" panel background, "card" a raised card,
  // "accent" EVI-coloured text, "text" body text, "muted" a section heading, "button", "field",
  // "rule" a separator, "stripe" the suggestion card's accent border.
  private static final String ROLE = "eviRole";
  private static <T extends JComponent> T role(T c, String role) { c.putClientProperty(ROLE, role); return c; }

  EviLivePanel(Runnable skip, Runnable personalUse, Runnable notHeld) {
    this(skip, personalUse, notHeld, () -> { }, () -> { });
  }

  EviLivePanel(Runnable skip, Runnable personalUse, Runnable notHeld, Runnable block) {
    this(skip, personalUse, notHeld, block, () -> { });
  }

  EviLivePanel(Runnable skip, Runnable personalUse, Runnable notHeld, Runnable block, Runnable resetProfit) {
    this(skip, personalUse, notHeld, block, resetProfit, () -> { });
  }

  EviLivePanel(Runnable skip, Runnable personalUse, Runnable notHeld, Runnable block,
               Runnable resetProfit, Runnable clearSkips) {
    this(skip, personalUse, notHeld, block, resetProfit, clearSkips, () -> { });
  }

  EviLivePanel(Runnable skip, Runnable personalUse, Runnable notHeld, Runnable block,
               Runnable resetProfit, Runnable clearSkips, Runnable accept) {
    this(skip, personalUse, notHeld, block, resetProfit, clearSkips, accept, level -> { });
  }

  EviLivePanel(Runnable skip, Runnable personalUse, Runnable notHeld, Runnable block,
               Runnable resetProfit, Runnable clearSkips, Runnable accept, Consumer<RiskLevel> chooseRisk) {
    this.clearSkips = clearSkips;
    setLayout(new BorderLayout());
    role(this, "bg");

    JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    role(content, "bg");
    content.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));

    // -- About --
    JLabel title = new JLabel("EVI Live");
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
    // -- Realised profit since the count began, with a Reset. Deliberately the journal's own matched-flip
    // total, and it says what it leaves out rather than rounding the story: unmatched sales and unsold
    // purchases are not profit. --
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
    resetButton.getAccessibleContext().setAccessibleDescription("Starts this profit line counting from now. Your trade records and flips are untouched.");
    resetButton.addActionListener(e -> resetProfit.run());
    content.add(Box.createVerticalStrut(6));
    content.add(resetButton);

    // -- Risk level: Layout B of the 6 Oct 2026 mock-up, chosen by the maintainer. Between the profit and the
    // pick on purpose -- the choice sits right above the result it shapes, and a press shows on the
    // next poll. Three EQUAL buttons: same face, same font, same text colour, nothing filled. The
    // chosen one is marked by a lighter outline and nothing else, because the panel must never give
    // one level visual prominence that nudges a player into it (the sidebar rule that rejected an
    // orange "I took this one"). Deliberately NO green/amber/red anywhere in this row: those colours
    // already mean the verdict, and a green Low would read as "recommended", a red High as a warning.
    // Pressing one writes the same config key the settings panel uses (see EviLivePlugin.chooseRisk),
    // so the two can never disagree. --
    // HIDDEN since 7 Oct 2026 until the levels are calibrated on live sells: built exactly as before, so the code
    // and its tests stay whole, and shown only when RiskLevel.SHOWN is true (see the end of this block).
    JPanel riskRule = section();
    content.add(riskRule);
    JLabel riskHeader = sectionHeader("Risk level");
    content.add(riskHeader);
    JPanel riskRow = new JPanel(new GridLayout(1, RiskLevel.values().length, 5, 0));
    riskRow.setAlignmentX(Component.LEFT_ALIGNMENT);
    riskRow.setOpaque(false);
    for (RiskLevel level : RiskLevel.values()) {
      JButton b = new JButton(level.label());
      b.setFont(FontManager.getRunescapeSmallFont());
      b.setFocusPainted(false);
      tip(b, level.aim());
      b.putClientProperty(RISK, level);
      b.addActionListener(e -> chooseRisk.accept(level));
      role(b, "button");
      riskButtons[level.ordinal()] = b;
      riskRow.add(b);
    }
    paintRisk(RiskLevel.LOW);
    riskRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, riskRow.getPreferredSize().height));
    content.add(riskRow);
    riskAim.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
    content.add(riskAim);
    riskSection.addAll(Arrays.asList(riskRule, riskHeader, riskRow, riskAim));
    for (JComponent c : riskSection) c.setVisible(RiskLevel.SHOWN);

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
      "Set this suggestion aside for 4 hours on this account and check for the next-best one. Does not affect any offer you have already placed.", skip);
    blockButton = iconButton(EviIcons.Glyph.BLOCK, "Block",
      BLOCK_TIP_HERE, block);
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
      "Clears everything you have skipped, blocked for this session, or marked as personal use, so EVI can suggest those items again. Blocked items stay blocked; this only undoes the set-aside list.");
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

    // -- Blocked items (8 Oct 2026): the plugin keeps Block per character, and this is where it is undone -- one row per
    // blocked item, its name and an Unblock button. Shown only while this character has something blocked, so an empty list
    // costs no room at all. --
    blockedHeader.setVisible(false);
    content.add(blockedHeader);
    blockedList.setLayout(new BoxLayout(blockedList, BoxLayout.Y_AXIS));
    blockedList.setOpaque(false);
    blockedList.setAlignmentX(Component.LEFT_ALIGNMENT);
    blockedList.setVisible(false);
    role(blockedList, "bg");
    content.add(blockedList);
    blockedNote.setVisible(false);
    content.add(blockedNote);

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

    // The import line (the maintainer's decision, 6 Oct 2026): ONLY while "Import old trade history" is on and its
    // file is missing. A fact about setup, so it sits with the connection line; neutral body text, never a
    // warning colour, because nothing is wrong -- a file has not been put there yet. Hidden otherwise.
    importNote.setVisible(false);
    importNote.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
    content.add(importNote);

    // The price-history line (Phase 6): a fact about setup, like the import line, so it sits with the connection line in
    // neutral body text. Hidden unless the engine says buys are waiting for it.
    backfillNote.setVisible(false);
    backfillNote.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
    content.add(backfillNote);

    // The invitation to share a trade log (9 Oct 2026, the maintainer's approved layout B): the very bottom of the sidebar, under
    // the status strip and its notes, because it is about EVI, not about a trade. Presented PLAINLY -- the same body text and the
    // same ordinary buttons as everything else, no accent colour, nothing filled -- because the sidebar never nudges. "Not now"
    // hides it until the next EVI update (the plugin keeps the version it was dismissed at); hidden until the plugin says.
    shareSection.setLayout(new BoxLayout(shareSection, BoxLayout.Y_AXIS));
    shareSection.setOpaque(false);
    shareSection.setAlignmentX(Component.LEFT_ALIGNMENT);
    shareSection.add(section());
    shareSection.add(bodyText(ShareInvite.INVITE));
    JPanel shareRow = new JPanel();
    shareRow.setLayout(new BoxLayout(shareRow, BoxLayout.X_AXIS));
    shareRow.setOpaque(false);
    shareRow.setAlignmentX(Component.LEFT_ALIGNMENT);
    JButton shareSave = secondaryButton(ShareInvite.SAVE_BUTTON);
    shareSave.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    shareSave.addActionListener(e -> shareSaveAction.run());
    JButton shareLater = secondaryButton(ShareInvite.NOT_NOW_BUTTON);
    shareLater.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    shareLater.addActionListener(e -> shareNotNowAction.run());
    shareRow.add(shareSave);
    shareRow.add(Box.createHorizontalStrut(5));
    shareRow.add(shareLater);
    shareRow.add(Box.createHorizontalGlue());
    shareRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, shareRow.getPreferredSize().height));
    shareSection.add(shareRow);
    JTextArea shareNote = bodyText(ShareInvite.NOT_NOW_NOTE);
    role(shareNote, "muted");
    shareSection.add(shareNote);
    shareSection.setVisible(false);
    content.add(shareSection);

    add(content, BorderLayout.NORTH);
  }

  /** What the share invitation's two buttons call (the plugin's saveTradeLog / dismissShareInvite); nothing until it sets them. */
  void onShare(Runnable save, Runnable notNow) {
    shareSaveAction = save == null ? () -> { } : save;
    shareNotNowAction = notNow == null ? () -> { } : notNow;
  }

  /** Shows or hides the share invitation ({@link ShareInvite#shown} decides which). Safe from any thread. */
  void shareInvite(boolean show) {
    SwingUtilities.invokeLater(() -> {
      if (shareSection.isVisible() == show) return;
      shareSection.setVisible(show);
      shareSection.revalidate();
      shareSection.repaint();
    });
  }

  /** The import line: the journal's text to show it, null or empty to hide it. Safe from any thread. */
  void importNotice(String message) {
    SwingUtilities.invokeLater(() -> {
      boolean show = message != null && !message.isEmpty();
      setIfChanged(importNote, show ? message : "");
      if (importNote.isVisible() != show) {
        importNote.setVisible(show);
        importNote.revalidate();
        importNote.repaint();
      }
    });
  }

  /** The price-history line: the text to show it, null or empty to hide it. Safe from any thread. */
  void backfillNotice(String message) {
    SwingUtilities.invokeLater(() -> {
      boolean show = message != null && !message.isEmpty();
      setIfChanged(backfillNote, show ? message : "");
      if (backfillNote.isVisible() != show) {
        backfillNote.setVisible(show);
        backfillNote.revalidate();
        backfillNote.repaint();
      }
    });
  }

  /** The profit line's text before the first figure arrives. Safe from any thread. */
  void profitWaiting(String message) {
    SwingUtilities.invokeLater(() -> setIfChanged(profitLine, message));
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
    tip(b, description);
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
   *  "I took this one" is dimmed rather than shown doing nothing when the suggestion carries no id
   *  (the engine gives every suggestion one, so this should be rare). EDT only. */
  void actions(boolean canAccept, boolean accepted, boolean holding, boolean canForget, boolean buying) {
    SwingUtilities.invokeLater(() -> {
      acceptButton.setEnabled(canAccept);
      acceptButton.setText(accepted ? "Taken" : "Took it");
      tip(acceptButton, !canAccept
        ? "EVI has no record of this suggestion, so it cannot be marked as taken."
        : accepted
          ? "Recorded as taken. Press again to take that back."
          : "Tell EVI you acted on this suggestion, so it can measure whether following it actually made GP. Recorded on your own machine only; press again to take it back.");
      personalUseButton.setEnabled(holding);
      tip(personalUseButton, holding
        ? PERSONAL_USE_TIP
        : "Only applies to something you are already holding.");
      // Not just `holding`: this closes one specific purchase, so it needs a buy behind it. Gear the
      // idle-inventory tier offered was never watched being bought and has none.
      notHeldButton.setEnabled(canForget);
      tip(notHeldButton, canForget
        ? GONE_TIP
        : holding
          ? "EVI cannot trace this back to one purchase, so there is nothing to close. Use Mine instead."
          : "Only applies to something you are already holding.");
      // Block stays buy-only on purpose: when you already own some, EVI keeps reminding you to sell
      // it, because going quiet about stock you hold is how GP gets stuck.
      blockButton.setEnabled(buying);
      tip(blockButton, buying
        ? BLOCK_TIP_HERE
        : "Only applies to a buy suggestion. EVI still reminds you to sell stock you own.");
    });
  }

  /** Shows which risk level is chosen. Called with the stored setting at startup, on every change
   *  made in the settings panel, and on a press of one of the buttons. Null means Low, the default.
   *  Safe from any thread. */
  void riskLevel(RiskLevel level) {
    RiskLevel chosen = level == null ? RiskLevel.LOW : level;
    SwingUtilities.invokeLater(() -> paintRisk(chosen));
  }

  /** The level AND the player's "Max share of cash per trade": at High with "No limit" the line under the buttons
   *  says plainly that nothing caps the size of one trade. Called at startup, on a change of either setting and
   *  on a button press. Safe from any thread. */
  void riskLevel(RiskLevel level, MaxTradeShare share) {
    RiskLevel chosen = level == null ? RiskLevel.LOW : level;
    SwingUtilities.invokeLater(() -> {
      shownShare = share;
      paintRisk(chosen);
    });
  }

  /** EDT only. The chosen button gets the palette's TEXT colour as a 1px outline and the others its
   *  RULE colour -- the same neutral pair a section rule and body text already use, so the marking is
   *  quiet and works under both schemes. Everything else (face, font, text colour) is left exactly as
   *  the theme paints every other button, which is what keeps the three equal. */
  void paintRisk(RiskLevel chosen) {
    shownRisk = chosen;
    EviTheme.Palette p = EviTheme.palette();
    for (JButton b : riskButtons) {
      if (b == null) continue;
      boolean on = b.getClientProperty(RISK) == chosen;
      b.setBorder(new CompoundBorder(
        BorderFactory.createLineBorder(on ? p.text : p.rule, 1),
        BorderFactory.createEmptyBorder(5, 0, 5, 0)));
      b.getAccessibleContext().setAccessibleName("Risk level " + ((RiskLevel) b.getClientProperty(RISK)).label() + (on ? ", chosen" : ""));
      b.repaint();
    }
    setIfChanged(riskAim, chosen.aim(shownShare));
  }

  /** Personal use and Gone, as the card's buttons explain them when they apply -- and as a holding line's menu items do. */
  static final String PERSONAL_USE_TIP = "Bought for your own use, not to flip. It stops being suggested and never counts toward profit. Only this purchase -- buying the item again later is unaffected.";
  static final String GONE_TIP = "You no longer have this: used in-game, or sold while EVI was not running. Whatever part of it EVI saw sold still counts toward profit.";

  /** A holding line's menu (8 Oct 2026, the maintainer's approved mock): these two choices, in this order, drawn alike -- neither is
   *  highlighted or made to stand out (the sidebar never nudges a press). */
  static final String LINE_PERSONAL_USE = "Personal use";
  static final String LINE_GONE = "I don't have this anymore";
  /** Added after a holding line's own sentence in its tooltip, so the menu can be found. ASCII. */
  static final String LINE_MENU_HINT = "Click this line for Personal use or I don't have this anymore.";
  /** The client property a holding line's card carries: the AdviceCard its menu acts on. Package-private for the tests. */
  static final String HOLDING_LINE = "eviHoldingLine";

  /** What a holding line's two menu items call. Any thread. */
  void onHoldingLine(Consumer<EviLivePlugin.AdviceCard> personalUse, Consumer<EviLivePlugin.AdviceCard> gone) {
    linePersonalUse = personalUse == null ? c -> { } : personalUse;
    lineGone = gone == null ? c -> { } : gone;
  }

  /** The menu one holding line opens: Personal use, then I don't have this anymore. Two plain items, identical in every
   *  respect but their words and what they do; nothing is preselected. Package-private so a test can open it without a
   *  screen. EDT only. */
  JPopupMenu holdingMenu(EviLivePlugin.AdviceCard c) {
    JPopupMenu menu = new JPopupMenu();
    JMenuItem personal = new JMenuItem(LINE_PERSONAL_USE);
    tip(personal, PERSONAL_USE_TIP);
    personal.addActionListener(e -> linePersonalUse.accept(c));
    JMenuItem gone = new JMenuItem(LINE_GONE);
    tip(gone, GONE_TIP);
    gone.addActionListener(e -> lineGone.accept(c));
    menu.add(personal);
    menu.add(gone);
    return menu;
  }

  /** Block's tooltip: per character, undone in this panel. ASCII. */
  static final String BLOCK_TIP_HERE = "Never suggest buying this item again on this character. Undo it under Blocked items in this panel. Stock you already hold still gets its sell reminder.";

  /** What each Unblock button calls with its item id. */
  void onUnblock(java.util.function.IntConsumer action) {
    unblock = action == null ? id -> { } : action;
  }

  /** The built-in engine's Blocked items list for the current character, ascending by id. Empty or null hides the whole section.
   *  Rebuilt only when it changed. Any thread. */
  void blockedItems(java.util.List<BlockedRow> rows) {
    SwingUtilities.invokeLater(() -> {
      StringBuilder sig = new StringBuilder();
      if (rows != null) for (BlockedRow r : rows) sig.append(r.itemId).append('|').append(r.name).append(';');
      if (sig.toString().equals(shownBlocked)) return;
      shownBlocked = sig.toString();
      blockedList.removeAll();
      boolean any = rows != null && !rows.isEmpty();
      blockedHeader.setVisible(any);
      blockedList.setVisible(any);
      if (any) {
        EviTheme.Palette p = EviTheme.palette();
        for (BlockedRow r : rows) {
          JPanel row = new JPanel(new BorderLayout(6, 0));
          row.setOpaque(false);
          row.setAlignmentX(Component.LEFT_ALIGNMENT);
          row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
          JLabel name = new JLabel(r.name);
          name.setFont(FontManager.getRunescapeSmallFont());
          role(name, "text");
          name.setForeground(p.text);
          tip(name, r.name);
          JButton undo = secondaryButton("Unblock");
          undo.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
          undo.setBackground(p.buttonFace);
          undo.setForeground(p.buttonText);
          tip(undo, "Let EVI suggest buying " + r.name + " again on this character.");
          undo.getAccessibleContext().setAccessibleName("Unblock " + r.name);
          final int id = r.itemId;
          undo.addActionListener(e -> unblock.accept(id));
          row.add(name, BorderLayout.CENTER);
          row.add(undo, BorderLayout.EAST);
          blockedList.add(Box.createVerticalStrut(3));
          blockedList.add(row);
        }
      }
      blockedList.revalidate();
      blockedList.repaint();
    });
  }

  /** One plain line under the Blocked items list (a failed Unblock); null or empty hides it. Any thread. */
  void blockedNotice(String message) {
    SwingUtilities.invokeLater(() -> {
      boolean any = message != null && !message.isEmpty();
      setIfChanged(blockedNote, any ? message : "");
      if (blockedNote.isVisible() != any) blockedNote.setVisible(any);
    });
  }

  void skipped(int count) {
    SwingUtilities.invokeLater(() -> {
      boolean any = count > 0;
      // Cleared rather than merely hidden at zero, so nothing stale is left behind a hidden label for
      // a screen reader or a future caller to pick up.
      // No longer "this session": since 6 Oct 2026 a Skip lasts four hours and outlives the session,
      // so the line says what is set aside without claiming when it ends -- the Skip tooltip says that.
      skipCount.setText(!any ? "" : count == 1 ? "1 item set aside" : count + " items set aside");
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
          if (s.reasoning != null && !s.reasoning.isEmpty()) tip(card, s.reasoning);

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
          String total = totalCost(s);
          if (total != null) {
            JLabel cost = new JLabel(total);
            cost.setFont(FontManager.getRunescapeSmallFont());
            cost.setForeground(p.muted);
            cost.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(cost);
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
        sig.append(c.level).append(c.label).append(c.name).append(c.figures).append(c.message).append(c.detail).append(c.itemId).append(c.buyId).append('|');
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
        // drew amber -- and the holdings lines added 30 Sept ("you're holding 1 of an item, so much
        // over cost") therefore looked exactly like "your sell is below
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
        // The full sentence, one hover away. It is the same text the scanner shows. A note that comes as a short line AND a
        // detail (8 Oct 2026, the maintainer: crash notes, whose one sentence was too long to read as a single tooltip line) shows its
        // line ON the card, under the figures, and keeps the detail for the tooltip; any other note is exactly as before.
        boolean split = c.detail != null && !c.detail.isEmpty() && c.message != null && !c.message.isEmpty();
        String cardTip = split ? c.detail : c.message;
        tip(card, cardTip);
        // A HOLDING line naming its lot (8 Oct 2026): a click, left or right, opens Personal use / I don't have this anymore
        // for that lot -- the card's two buttons, for stock that is not the card. The line looks exactly as before; only the
        // tooltip says it can be clicked. Not on any other line: an offer, a crash note or a hint is not a position.
        if (c.buyId != null) {
          tip(card, (c.message == null || c.message.isEmpty() ? "" : c.message + " ") + LINE_MENU_HINT);
          card.putClientProperty(HOLDING_LINE, c);
          final JPanel line = card;
          // mouseClicked, not mousePressed: opened on the press, the release could land on the first item and choose it.
          card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
              if (line.isShowing()) holdingMenu(c).show(line, e.getX(), e.getY());
            }
          });
        }

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
        if (split) {
          // Wrapped, read-only, caret pinned (bodyText); the theme's own text colour, never green. It carries the card's
          // tooltip too: a text area takes the mouse, so without it hovering the line would show nothing.
          JTextArea line = bodyText(c.message);
          line.setForeground(p.text);
          line.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
          tip(line, cardTip);
          line.putClientProperty(CARD_LINE, Boolean.TRUE);
          card.add(line);
        }
        adviceList.add(Box.createVerticalStrut(6));
        adviceList.add(card);
      }
      adviceList.revalidate();
      adviceList.repaint();
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
   *  bought a batch of an item expecting the profit the quoted spread implied; it
   *  was worth about a tenth of that, and EVI knew -- it had demoted the pick and said so, in the middle of a
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
        String total = totalCost(s);
        boolean any = prose != null && !prose.isEmpty();
        // A buy drawn as a paragraph still states its total, on its own last line.
        setIfChanged(suggestion, !any ? "No suggestion yet." : total == null ? prose : prose + "\n" + total);
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

  /**
   * "Total 1,396,000 gp": what a BUY costs in full, buy price times quantity (the maintainer's decision, 6 Oct 2026,
   * in place of a share of cash). Null -- show nothing -- for a sell or holding, when the quantity or the price is
   * not known (zero or less), and when the product does not fit a long: never a wrapped or guessed figure. long
   * arithmetic throughout (a unit price can pass max cash), and en-US grouping whatever the machine's locale, so
   * a Dutch client prints 1,396,000 and not 1.396.000.
   */
  static String totalCost(Suggestion s) {
    if (s == null || !"buy".equals(s.action) || s.quantity <= 0 || s.buyPrice <= 0) return null;
    long total;
    try {
      total = Math.multiplyExact((long) s.quantity, s.buyPrice);
    } catch (ArithmeticException e) {
      return null;
    }
    return String.format(Locale.US, "Total %,d gp", total);
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

    // What the buy costs in full (the maintainer's decision, 6 Oct 2026: the total, always, not a share of cash).
    String total = totalCost(s);
    if (total != null) {
      JLabel cost = new JLabel(total);
      cost.setFont(FontManager.getRunescapeSmallFont());
      cost.setForeground(p.muted);
      cost.setAlignmentX(Component.LEFT_ALIGNMENT);
      suggestionCard.add(Box.createVerticalStrut(2));
      suggestionCard.add(cost);
    }

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
      paintRisk(shownRisk); // the outline colours belong to the palette, so they are redrawn too
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
  /** The journal's realised-profit figure, or null when the answer carried none this poll. EDT only. */
  void profit(EviLivePlugin.Profit p) {
    SwingUtilities.invokeLater(() -> {
      if (p == null) {
        profitFigure.setText(" ");
        role(profitFigure, "text");
        profitFigure.setForeground(EviTheme.palette().text);
        setIfChanged(profitLine, "Profit unavailable just now.");
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

  /** Every sidebar tooltip goes through here (8 Oct 2026, the maintainer: a crash alert's tooltip was one line wider than the screen).
   *  A tooltip longer than {@link #TIP_PLAIN_MAX} characters is drawn as HTML at a fixed {@link #TIP_WIDTH} px, so Swing wraps
   *  it into a few short lines; a short one stays plain text. The text is escaped, so nothing in it is read as markup. */
  static void tip(JComponent c, String text) {
    c.setToolTipText(wrapTip(text));
  }

  static final int TIP_WIDTH = 300;
  static final int TIP_PLAIN_MAX = 60;
  private static final String TIP_OPEN = "<html><body style='width:" + TIP_WIDTH + "px'>";
  private static final String TIP_CLOSE = "</body></html>";
  /** The client property on an advice card's own line (a split note's short message). Package-private for the tests. */
  static final String CARD_LINE = "eviCardLine";

  /** The tooltip form of {@code text}: unchanged when null, empty or short; otherwise escaped and width-wrapped HTML. */
  static String wrapTip(String text) {
    if (text == null || text.length() <= TIP_PLAIN_MAX) return text;
    StringBuilder sb = new StringBuilder(text.length() + 48).append(TIP_OPEN);
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '&') sb.append("&amp;");
      else if (ch == '<') sb.append("&lt;");
      else if (ch == '>') sb.append("&gt;");
      else if (ch == '\n') sb.append("<br>");
      else sb.append(ch);
    }
    return sb.append(TIP_CLOSE).toString();
  }

  /** The text a tooltip shows, with {@link #wrapTip}'s wrapping undone: what the tests compare. */
  static String plainTip(String tip) {
    if (tip == null || !tip.startsWith(TIP_OPEN) || !tip.endsWith(TIP_CLOSE)) return tip;
    return tip.substring(TIP_OPEN.length(), tip.length() - TIP_CLOSE.length())
      .replace("<br>", "\n").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
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
