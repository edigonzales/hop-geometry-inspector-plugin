package ch.so.agi.hop.geometry.inspector.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import javax.swing.Icon;

public final class GeometryInspectorToolbarIcons {

  private GeometryInspectorToolbarIcons() {}

  public static Icon zoomIn(int size) {
    return new ToolbarIcon(Symbol.ZOOM_IN, size);
  }

  public static Icon zoomOut(int size) {
    return new ToolbarIcon(Symbol.ZOOM_OUT, size);
  }

  public static Icon zoomExtent(int size) {
    return new ToolbarIcon(Symbol.ZOOM_EXTENT, size);
  }

  public static Icon refresh(int size) {
    return new ToolbarIcon(Symbol.REFRESH, size);
  }

  public static Icon background(int size) {
    return new ToolbarIcon(Symbol.BACKGROUND, size);
  }

  private enum Symbol {
    ZOOM_IN,
    ZOOM_OUT,
    ZOOM_EXTENT,
    REFRESH,
    BACKGROUND
  }

  private static final class ToolbarIcon implements Icon {
    private final Symbol symbol;
    private final int size;

    private ToolbarIcon(Symbol symbol, int size) {
      this.symbol = symbol;
      this.size = Math.max(16, size);
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.translate(x, y);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float strokeWidth = Math.max(1.6f, size / 12.0f);
        g2.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(34, 45, 69));

        switch (symbol) {
          case ZOOM_IN -> paintZoom(g2, true);
          case ZOOM_OUT -> paintZoom(g2, false);
          case ZOOM_EXTENT -> paintZoomExtent(g2);
          case REFRESH -> paintRefresh(g2);
          case BACKGROUND -> paintBackground(g2);
        }
      } finally {
        g2.dispose();
      }
    }

    private void paintZoom(Graphics2D g2, boolean addPlus) {
      double lens = size * 0.54;
      double lensX = size * 0.1;
      double lensY = size * 0.1;
      g2.draw(new Ellipse2D.Double(lensX, lensY, lens, lens));
      g2.drawLine((int) (size * 0.58), (int) (size * 0.58), (int) (size * 0.86), (int) (size * 0.86));
      g2.drawLine((int) (size * 0.27), (int) (size * 0.37), (int) (size * 0.47), (int) (size * 0.37));
      if (addPlus) {
        g2.drawLine((int) (size * 0.37), (int) (size * 0.27), (int) (size * 0.37), (int) (size * 0.47));
      }
    }

    private void paintZoomExtent(Graphics2D g2) {
      int inset = Math.round(size * 0.18f);
      int edge = size - (inset * 2);
      g2.drawRect(inset, inset, edge, edge);
      g2.drawLine(inset, inset, (int) (size * 0.38), inset);
      g2.drawLine(inset, inset, inset, (int) (size * 0.38));
      g2.drawLine(size - inset, inset, (int) (size * 0.62), inset);
      g2.drawLine(size - inset, inset, size - inset, (int) (size * 0.38));
      g2.drawLine(inset, size - inset, (int) (size * 0.38), size - inset);
      g2.drawLine(inset, size - inset, inset, (int) (size * 0.62));
      g2.drawLine(size - inset, size - inset, (int) (size * 0.62), size - inset);
      g2.drawLine(size - inset, size - inset, size - inset, (int) (size * 0.62));
    }

    private void paintRefresh(Graphics2D g2) {
      Path2D path = new Path2D.Double();
      path.moveTo(size * 0.28, size * 0.28);
      path.curveTo(size * 0.55, size * 0.08, size * 0.86, size * 0.24, size * 0.84, size * 0.52);
      g2.draw(path);
      g2.drawLine((int) (size * 0.84), (int) (size * 0.52), (int) (size * 0.69), (int) (size * 0.44));
      g2.drawLine((int) (size * 0.84), (int) (size * 0.52), (int) (size * 0.76), (int) (size * 0.34));

      Path2D path2 = new Path2D.Double();
      path2.moveTo(size * 0.72, size * 0.72);
      path2.curveTo(size * 0.45, size * 0.92, size * 0.14, size * 0.76, size * 0.16, size * 0.48);
      g2.draw(path2);
      g2.drawLine((int) (size * 0.16), (int) (size * 0.48), (int) (size * 0.31), (int) (size * 0.56));
      g2.drawLine((int) (size * 0.16), (int) (size * 0.48), (int) (size * 0.24), (int) (size * 0.66));
    }

    private void paintBackground(Graphics2D g2) {
      int inset = Math.round(size * 0.12f);
      int width = size - (inset * 2);
      int height = size - (inset * 2);
      g2.drawRect(inset, inset, width, height);
      g2.drawLine(inset, (int) (size * 0.62), size - inset, (int) (size * 0.62));
      Path2D mountain = new Path2D.Double();
      mountain.moveTo(inset + 2, size - inset - 2);
      mountain.lineTo(size * 0.42, size * 0.44);
      mountain.lineTo(size * 0.57, size * 0.58);
      mountain.lineTo(size * 0.74, size * 0.34);
      mountain.lineTo(size - inset - 2, size - inset - 2);
      g2.draw(mountain);
      g2.fill(new Ellipse2D.Double(size * 0.19, size * 0.22, size * 0.12, size * 0.12));
    }

    @Override
    public int getIconWidth() {
      return size;
    }

    @Override
    public int getIconHeight() {
      return size;
    }
  }
}
