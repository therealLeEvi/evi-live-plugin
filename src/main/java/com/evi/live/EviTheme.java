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

  /** BRAND_DARK at a given alpha, for painted-overlay backdrops (e.g. the item-picker row) --
   *  mostly-opaque navy instead of a generic black rectangle, so the backdrop itself reads as
   *  "EVI" rather than an unstyled box. */
  static Color backdrop(int alpha) {
    return new Color(BRAND_DARK.getRed(), BRAND_DARK.getGreen(), BRAND_DARK.getBlue(), alpha);
  }
}
