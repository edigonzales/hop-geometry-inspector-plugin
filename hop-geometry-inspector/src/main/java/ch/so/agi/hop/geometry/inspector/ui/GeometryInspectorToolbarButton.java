package ch.so.agi.hop.geometry.inspector.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;

final class GeometryInspectorToolbarButton extends Canvas {

  static final int LOGICAL_BUTTON_SIZE = 34;
  static final int LOGICAL_ICON_SIZE = 16;
  private static final int ARC = 8;

  private final GeometryInspectorToolbarIcons.Symbol symbol;
  private final boolean toggle;
  private boolean selected;
  private boolean hover;
  private boolean pressed;
  private Image enabledIconImage;
  private Image disabledIconImage;
  private boolean enabledIconResolved;
  private boolean disabledIconResolved;
  private int cachedDeviceZoom = -1;

  GeometryInspectorToolbarButton(
      Composite parent, GeometryInspectorToolbarIcons.Symbol symbol, boolean toggle) {
    super(parent, SWT.DOUBLE_BUFFERED);
    this.symbol = symbol;
    this.toggle = toggle;
    addListener(SWT.Paint, this::paintButton);
    addListener(
        SWT.MouseEnter,
        event -> {
          hover = true;
          redraw();
        });
    addListener(
        SWT.MouseExit,
        event -> {
          hover = false;
          pressed = false;
          redraw();
        });
    addListener(
        SWT.MouseDown,
        event -> {
          if (event.button == 1 && isEnabled()) {
            pressed = true;
            redraw();
          }
        });
    addListener(
        SWT.MouseUp,
        event -> {
          if (event.button != 1 || !isEnabled()) {
            return;
          }
          boolean fire = pressed && contains(event.x, event.y);
          pressed = false;
          if (fire) {
            if (toggle) {
              selected = !selected;
            }
            Event selection = new Event();
            selection.widget = this;
            selection.display = getDisplay();
            notifyListeners(SWT.Selection, selection);
          }
          redraw();
        });
    addListener(SWT.Dispose, event -> disposeCachedImages());
  }

  @Override
  public Point computeSize(int wHint, int hHint, boolean changed) {
    int width = wHint == SWT.DEFAULT ? LOGICAL_BUTTON_SIZE : wHint;
    int height = hHint == SWT.DEFAULT ? LOGICAL_BUTTON_SIZE : hHint;
    int side = Math.max(width, height);
    return new Point(side, side);
  }

  boolean isSelected() {
    return selected;
  }

  void setSelected(boolean selected) {
    if (this.selected == selected) {
      return;
    }
    this.selected = selected;
    redraw();
  }

  @Override
  public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    redraw();
  }

  private void paintButton(Event event) {
    GC gc = event.gc;
    gc.setAdvanced(true);
    gc.setAntialias(SWT.ON);
    Rectangle area = getClientArea();
    if (area.width <= 0 || area.height <= 0) {
      return;
    }

    ButtonPaintStyle style = resolveStyle();
    Color fill = new Color(getDisplay(), style.fillRed(), style.fillGreen(), style.fillBlue());
    Color border = new Color(getDisplay(), style.borderRed(), style.borderGreen(), style.borderBlue());
    try {
      gc.setBackground(fill);
      gc.setForeground(border);
      gc.fillRoundRectangle(area.x, area.y, area.width - 1, area.height - 1, ARC, ARC);
      gc.drawRoundRectangle(area.x, area.y, area.width - 1, area.height - 1, ARC, ARC);

      GeometryInspectorToolbarIcons.PaintStyle iconStyle =
          GeometryInspectorToolbarIcons.styleFor(isEnabled());
      int iconX = area.x + Math.max(0, (area.width - LOGICAL_ICON_SIZE) / 2);
      int iconY = area.y + Math.max(0, (area.height - LOGICAL_ICON_SIZE) / 2);
      Image iconImage = getIconImage(isEnabled());
      if (iconImage != null && !iconImage.isDisposed()) {
        int previousAlpha = gc.getAlpha();
        gc.setAlpha(iconStyle.alpha());
        Rectangle iconBounds = iconImage.getBounds();
        gc.drawImage(
            iconImage,
            0,
            0,
            iconBounds.width,
            iconBounds.height,
            iconX,
            iconY,
            LOGICAL_ICON_SIZE,
            LOGICAL_ICON_SIZE);
        gc.setAlpha(previousAlpha);
      }
    } finally {
      fill.dispose();
      border.dispose();
    }
  }

  private Image getIconImage(boolean enabled) {
    int deviceZoom = currentDeviceZoom();
    if (cachedDeviceZoom != deviceZoom) {
      disposeCachedImages();
      cachedDeviceZoom = deviceZoom;
    }

    if (enabled) {
      if (!enabledIconResolved) {
        enabledIconImage =
            GeometryInspectorToolbarIcons.createImage(
                getDisplay(), symbol, LOGICAL_ICON_SIZE, deviceZoom, true);
        enabledIconResolved = true;
      }
      return enabledIconImage;
    }

    if (!disabledIconResolved) {
      disabledIconImage =
          GeometryInspectorToolbarIcons.createImage(
              getDisplay(), symbol, LOGICAL_ICON_SIZE, deviceZoom, false);
      disabledIconResolved = true;
    }
    return disabledIconImage;
  }

  private int currentDeviceZoom() {
    return Math.max(100, getMonitor() == null ? 100 : getMonitor().getZoom());
  }

  private void disposeCachedImages() {
    if (enabledIconImage != null && !enabledIconImage.isDisposed()) {
      enabledIconImage.dispose();
    }
    if (disabledIconImage != null && !disabledIconImage.isDisposed()) {
      disabledIconImage.dispose();
    }
    enabledIconImage = null;
    disabledIconImage = null;
    enabledIconResolved = false;
    disabledIconResolved = false;
  }

  private boolean contains(int x, int y) {
    Rectangle area = getClientArea();
    return x >= area.x && x < area.x + area.width && y >= area.y && y < area.y + area.height;
  }

  private ButtonPaintStyle resolveStyle() {
    if (!isEnabled()) {
      return new ButtonPaintStyle(250, 250, 250, 225, 225, 225);
    }
    if (pressed) {
      return new ButtonPaintStyle(232, 232, 232, 145, 145, 145);
    }
    if (selected) {
      return new ButtonPaintStyle(237, 237, 237, 120, 120, 120);
    }
    if (hover) {
      return new ButtonPaintStyle(245, 245, 245, 150, 150, 150);
    }
    return new ButtonPaintStyle(255, 255, 255, 188, 188, 188);
  }

  private record ButtonPaintStyle(
      int fillRed,
      int fillGreen,
      int fillBlue,
      int borderRed,
      int borderGreen,
      int borderBlue) {}
}
