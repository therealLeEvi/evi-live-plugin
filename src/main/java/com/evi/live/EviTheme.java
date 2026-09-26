package com.evi.live;

import java.awt.Color;

/**
 * EVI's single shared color palette, used everywhere the plugin draws its own UI: the sidebar
 * panel, the GE quantity/price hint text, the search-results highlight, and the clickable
 * item-select row -- so all of them read as one consistent look instead of drifting independently.
 * Before this class existed, the same brand teal was hand-copied as a raw Color/int literal in
 * separate files (SuggestionHintWidget, SuggestionSearchHighlightOverlay, and the now-removed
 * SuggestionItemPickerWidget), and the
 * sidebar panel (EviLivePanel) used no shared color at all -- just plain Swing/OS-native component
 * defaults (a white JPasswordField, a stock gray JButton) sitting on top of RuneLite's own dark
 * theme, which read as inconsistent/unpolished next to the rest of the client.
 *
 * Neutral grays/borders for panel chrome intentionally are NOT redefined here -- EviLivePanel uses
 * net.runelite.client.ui.ColorScheme directly for those, since that's the exact palette every other
 * plugin's sidebar panel (and the client shell itself) already uses. Matching it, rather than
 * inventing a second gray scale, is what makes the panel look like it belongs in RuneLite rather
 * than a bolted-on demo; this class only owns the one accent color that's actually EVI's own.
 */
final class EviTheme {
  private EviTheme() { }

  /** EVI's brand teal -- the only accent color used anywhere in this plugin's UI. */
  static final Color BRAND = new Color(0x53, 0xCD, 0xB4);

  /** Dark navy paired with BRAND for contrast (the sidebar icon's glyph, and overlay backdrops). */
  static final Color BRAND_DARK = new Color(15, 30, 40);

  /** BRAND packed as 0xRRGGBB, for the RuneLite widget APIs that take a raw int color (e.g.
   *  Widget.setTextColor) rather than a java.awt.Color. */
  static final int BRAND_RGB = BRAND.getRGB() & 0xFFFFFF;

  /** BRAND at a given alpha, for translucent highlights -- keeps every translucent use tied to the
   *  exact same base color instead of separate hand-rolled Color(r,g,b,a) literals. */
  static Color brand(int alpha) {
    return new Color(BRAND.getRed(), BRAND.getGreen(), BRAND.getBlue(), alpha);
  }

  /** One sidebar colour scheme (see PanelTheme). The panel asks the active palette for every colour
   *  it paints, so adding or changing a scheme never means hunting colours through the panel code.
   *  RuneLite's own ColorScheme values are used for the default, which is what makes the panel look
   *  like the rest of the client rather than a second gray scale invented here. */
  static final class Palette {
    final Color background, card, text, muted, accent, warn, buttonFace, buttonText, rule;
    private Palette(Color background, Color card, Color text, Color muted, Color accent, Color warn,
                    Color buttonFace, Color buttonText, Color rule) {
      this.background = background; this.card = card; this.text = text; this.muted = muted;
      this.accent = accent; this.warn = warn; this.buttonFace = buttonFace; this.buttonText = buttonText; this.rule = rule;
    }
  }

  static final Palette RUNELITE = new Palette(
    net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR, net.runelite.client.ui.ColorScheme.DARKER_GRAY_COLOR,
    net.runelite.client.ui.ColorScheme.LIGHT_GRAY_COLOR, net.runelite.client.ui.ColorScheme.LIGHT_GRAY_COLOR,
    BRAND, net.runelite.client.ui.ColorScheme.PROGRESS_INPROGRESS_COLOR,
    net.runelite.client.ui.ColorScheme.DARKER_GRAY_COLOR, Color.WHITE,
    net.runelite.client.ui.ColorScheme.MEDIUM_GRAY_COLOR);

  /** The scanner's Old School scheme, to the same values: parchment on worn leather, OSRS gold. */
  static final Palette OLD_SCHOOL = new Palette(
    new Color(0x2b, 0x24, 0x1a), new Color(0x3a, 0x31, 0x24),
    new Color(0xe8, 0xdc, 0xc0), new Color(0xb6, 0xa8, 0x88),
    new Color(0xff, 0xb0, 0x00), new Color(0xff, 0xcf, 0x5c),
    new Color(0x47, 0x3c, 0x2c), new Color(0xe8, 0xdc, 0xc0),
    new Color(0x5a, 0x4a, 0x33));

  private static volatile Palette active = RUNELITE;
  static Palette palette() { return active; }
  static void use(PanelTheme theme) { active = theme == PanelTheme.OLD_SCHOOL ? OLD_SCHOOL : RUNELITE; }

  /** BRAND_DARK at a given alpha, for painted-overlay backdrops (e.g. the item-picker row) --
   *  mostly-opaque navy instead of a generic black rectangle, so the backdrop itself reads as
   *  "EVI" rather than an unstyled box. */
  static Color backdrop(int alpha) {
    return new Color(BRAND_DARK.getRed(), BRAND_DARK.getGreen(), BRAND_DARK.getBlue(), alpha);
  }
}
