package ch.so.agi.hop.geometry.inspector.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Path;

public final class GeometryInspectorToolbarIcons {

  static final int ICON_VIEWPORT_SIZE = 16;

  private GeometryInspectorToolbarIcons() {}

  static PaintStyle styleFor(boolean enabled) {
    if (enabled) {
      return new PaintStyle(0, 0, 0, 255);
    }
    return new PaintStyle(120, 120, 120, 135);
  }

  static void paint(
      GC gc, Symbol symbol, int originX, int originY, int logicalSize, boolean enabled) {
    PaintStyle style = styleFor(enabled);
    int previousAlpha = gc.getAlpha();
    Color color = new Color(gc.getDevice(), style.red(), style.green(), style.blue());
    try {
      gc.setAlpha(style.alpha());
      gc.setForeground(color);
      gc.setLineCap(SWT.CAP_ROUND);
      gc.setLineJoin(SWT.JOIN_ROUND);
      gc.setLineWidth(Math.max(2, Math.round(logicalSize / 8.0f)));
      gc.setAdvanced(true);
      gc.setAntialias(SWT.ON);
      gc.setInterpolation(SWT.HIGH);

      switch (symbol) {
        case ZOOM_IN -> paintZoom(gc, originX, originY, logicalSize, true);
        case ZOOM_OUT -> paintZoom(gc, originX, originY, logicalSize, false);
        case ZOOM_EXTENT -> paintBoundingBox(gc, originX, originY, logicalSize);
        case REFRESH -> paintArrowClockwise(gc, originX, originY, logicalSize);
        case BACKGROUND -> paintImage(gc, originX, originY, logicalSize);
      }
    } finally {
      gc.setAlpha(previousAlpha);
      color.dispose();
    }
  }

  enum Symbol {
    ZOOM_IN,
    ZOOM_OUT,
    ZOOM_EXTENT,
    REFRESH,
    BACKGROUND
  }

  record PaintStyle(int red, int green, int blue, int alpha) {}

  private static void paintZoom(GC gc, int originX, int originY, int size, boolean addPlus) {
    int circleX = sx(originX, size, 1.5f);
    int circleY = sy(originY, size, 1.5f);
    int circleSize = span(size, 9.0f);
    gc.drawOval(circleX, circleY, circleSize, circleSize);
    gc.drawLine(
        sx(originX, size, 9.6f),
        sy(originY, size, 9.6f),
        sx(originX, size, 14.3f),
        sy(originY, size, 14.3f));
    gc.drawLine(
        sx(originX, size, 4.4f),
        sy(originY, size, 6.0f),
        sx(originX, size, 7.6f),
        sy(originY, size, 6.0f));
    if (addPlus) {
      gc.drawLine(
          sx(originX, size, 6.0f),
          sy(originY, size, 4.4f),
          sx(originX, size, 6.0f),
          sy(originY, size, 7.6f));
    }
  }

  private static void paintBoundingBox(GC gc, int originX, int originY, int size) {
    gc.drawLine(sx(originX, size, 2.5f), sy(originY, size, 5.0f), sx(originX, size, 2.5f), sy(originY, size, 2.5f));
    gc.drawLine(sx(originX, size, 2.5f), sy(originY, size, 2.5f), sx(originX, size, 5.0f), sy(originY, size, 2.5f));

    gc.drawLine(sx(originX, size, 11.0f), sy(originY, size, 2.5f), sx(originX, size, 13.5f), sy(originY, size, 2.5f));
    gc.drawLine(sx(originX, size, 13.5f), sy(originY, size, 2.5f), sx(originX, size, 13.5f), sy(originY, size, 5.0f));

    gc.drawLine(sx(originX, size, 2.5f), sy(originY, size, 11.0f), sx(originX, size, 2.5f), sy(originY, size, 13.5f));
    gc.drawLine(sx(originX, size, 2.5f), sy(originY, size, 13.5f), sx(originX, size, 5.0f), sy(originY, size, 13.5f));

    gc.drawLine(sx(originX, size, 11.0f), sy(originY, size, 13.5f), sx(originX, size, 13.5f), sy(originY, size, 13.5f));
    gc.drawLine(sx(originX, size, 13.5f), sy(originY, size, 11.0f), sx(originX, size, 13.5f), sy(originY, size, 13.5f));
  }

  private static void paintArrowClockwise(GC gc, int originX, int originY, int size) {
    Path path = new Path(gc.getDevice());
    try {
      path.moveTo(sx(originX, size, 12.2f), sy(originY, size, 5.2f));
      path.cubicTo(
          sx(originX, size, 11.2f), sy(originY, size, 2.9f),
          sx(originX, size, 8.4f), sy(originY, size, 1.8f),
          sx(originX, size, 5.8f), sy(originY, size, 2.4f));
      path.cubicTo(
          sx(originX, size, 3.5f), sy(originY, size, 2.9f),
          sx(originX, size, 2.0f), sy(originY, size, 4.5f),
          sx(originX, size, 1.8f), sy(originY, size, 6.0f));
      gc.drawPath(path);
      gc.drawLine(sx(originX, size, 10.6f), sy(originY, size, 3.1f), sx(originX, size, 12.5f), sy(originY, size, 5.0f));
      gc.drawLine(sx(originX, size, 12.5f), sy(originY, size, 5.0f), sx(originX, size, 9.8f), sy(originY, size, 5.2f));

      path.dispose();
      Path lower = new Path(gc.getDevice());
      try {
        lower.moveTo(sx(originX, size, 3.8f), sy(originY, size, 10.8f));
        lower.cubicTo(
            sx(originX, size, 4.9f), sy(originY, size, 13.0f),
            sx(originX, size, 7.7f), sy(originY, size, 14.2f),
            sx(originX, size, 10.2f), sy(originY, size, 13.7f));
        lower.cubicTo(
            sx(originX, size, 12.5f), sy(originY, size, 13.2f),
            sx(originX, size, 14.0f), sy(originY, size, 11.5f),
            sx(originX, size, 14.2f), sy(originY, size, 10.0f));
        gc.drawPath(lower);
        gc.drawLine(sx(originX, size, 5.4f), sy(originY, size, 12.9f), sx(originX, size, 3.5f), sy(originY, size, 11.0f));
        gc.drawLine(sx(originX, size, 3.5f), sy(originY, size, 11.0f), sx(originX, size, 6.2f), sy(originY, size, 10.8f));
      } finally {
        lower.dispose();
      }
    } finally {
      if (!path.isDisposed()) {
        path.dispose();
      }
    }
  }

  private static void paintImage(GC gc, int originX, int originY, int size) {
    int frameX = sx(originX, size, 1.5f);
    int frameY = sy(originY, size, 2.5f);
    int frameWidth = span(size, 13.0f);
    int frameHeight = span(size, 10.5f);
    gc.drawRectangle(frameX, frameY, frameWidth, frameHeight);
    gc.drawOval(sx(originX, size, 4.0f), sy(originY, size, 4.3f), span(size, 1.9f), span(size, 1.9f));

    Path mountain = new Path(gc.getDevice());
    try {
      mountain.moveTo(sx(originX, size, 2.3f), sy(originY, size, 11.3f));
      mountain.lineTo(sx(originX, size, 5.8f), sy(originY, size, 7.3f));
      mountain.lineTo(sx(originX, size, 8.2f), sy(originY, size, 9.4f));
      mountain.lineTo(sx(originX, size, 10.9f), sy(originY, size, 5.9f));
      mountain.lineTo(sx(originX, size, 13.8f), sy(originY, size, 11.3f));
      gc.drawPath(mountain);
    } finally {
      mountain.dispose();
    }
  }

  private static int sx(int originX, int size, float viewportX) {
    return originX + Math.round((viewportX / ICON_VIEWPORT_SIZE) * size);
  }

  private static int sy(int originY, int size, float viewportY) {
    return originY + Math.round((viewportY / ICON_VIEWPORT_SIZE) * size);
  }

  private static int span(int size, float viewportSpan) {
    return Math.max(1, Math.round((viewportSpan / ICON_VIEWPORT_SIZE) * size));
  }
}
