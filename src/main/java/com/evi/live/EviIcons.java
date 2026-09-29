package com.evi.live;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * The five sidebar action icons, drawn rather than shipped as images.
 *
 * <p>Drawing them keeps the plugin jar free of binary assets, which matters for a Plugin Hub
 * submission: there is nothing for a reviewer to have to take on trust, and nothing to go missing
 * when the jar is repacked. Each shape is a few strokes, so the code is shorter than the PNGs would
 * have been anyway.
 *
 * <p>Colour is passed in rather than fixed, because the sidebar has two themes and because a
 * disabled action is drawn in the same shape at a dimmer colour -- Swing only derives a greyed
 * icon automatically for {@code ImageIcon}, so a custom {@code Icon} must supply its own.
 */
final class EviIcons {

  /** Which action a glyph stands for, in the order they appear in the sidebar. */
  enum Glyph { TOOK_IT, PERSONAL_USE, GONE, SKIP, BLOCK }

  private EviIcons() { }

  static Icon of(Glyph glyph, Color colour, int size) {
    return new GlyphIcon(glyph, colour, size);
  }

  private static final class GlyphIcon implements Icon {
    private final Glyph glyph;
    private final Color colour;
    private final int size;

    GlyphIcon(Glyph glyph, Color colour, int size) {
      this.glyph = glyph;
      this.colour = colour;
      this.size = size;
    }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
      Graphics2D g2 = (Graphics2D) g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setColor(colour);
        // Everything below is expressed on a 0..1 square and scaled, so one set of coordinates
        // serves whatever icon size the panel asks for.
        final double s = size;
        final double inset = s * 0.06;
        g2.translate(x, y);
        g2.setStroke(new BasicStroke((float) Math.max(1.4, s * 0.115), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (glyph) {
          case TOOK_IT: {
            Path2D.Double p = new Path2D.Double();
            p.moveTo(s * 0.18, s * 0.53);
            p.lineTo(s * 0.41, s * 0.75);
            p.lineTo(s * 0.83, s * 0.25);
            g2.draw(p);
            break;
          }
          case PERSONAL_USE: {
            double head = s * 0.20;
            g2.draw(new Ellipse2D.Double(s * 0.5 - head, s * 0.14, head * 2, head * 2));
            Path2D.Double body = new Path2D.Double();
            body.moveTo(s * 0.17, s * 0.90);
            body.curveTo(s * 0.17, s * 0.60, s * 0.83, s * 0.60, s * 0.83, s * 0.90);
            g2.draw(body);
            break;
          }
          case GONE: {
            // A crate with a line through it: stock that is no longer yours.
            g2.drawRect((int) Math.round(inset), (int) Math.round(s * 0.24),
              (int) Math.round(s - 2 * inset), (int) Math.round(s * 0.58));
            g2.drawLine((int) Math.round(inset), (int) Math.round(s * 0.24),
              (int) Math.round(s - inset), (int) Math.round(s * 0.82));
            break;
          }
          case SKIP: {
            Path2D.Double tri = new Path2D.Double();
            tri.moveTo(s * 0.16, s * 0.15);
            tri.lineTo(s * 0.62, s * 0.5);
            tri.lineTo(s * 0.16, s * 0.85);
            tri.closePath();
            g2.draw(tri);
            g2.drawLine((int) Math.round(s * 0.80), (int) Math.round(s * 0.16),
              (int) Math.round(s * 0.80), (int) Math.round(s * 0.84));
            break;
          }
          case BLOCK: {
            double r = s * 0.37;
            g2.draw(new Ellipse2D.Double(s * 0.5 - r, s * 0.5 - r, r * 2, r * 2));
            double d = r * 0.707;
            g2.drawLine((int) Math.round(s * 0.5 - d), (int) Math.round(s * 0.5 - d),
              (int) Math.round(s * 0.5 + d), (int) Math.round(s * 0.5 + d));
            break;
          }
          default:
            break;
        }
      } finally {
        g2.dispose();
      }
    }
  }
}
