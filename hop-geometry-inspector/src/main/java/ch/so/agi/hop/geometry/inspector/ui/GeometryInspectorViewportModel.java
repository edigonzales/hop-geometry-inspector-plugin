package ch.so.agi.hop.geometry.inspector.ui;

import org.geotools.geometry.jts.ReferencedEnvelope;

final class GeometryInspectorViewportModel {

  private ReferencedEnvelope displayArea;
  private int canvasWidth;
  private int canvasHeight;
  private int deviceZoom = 100;
  private boolean dragging;
  private boolean dragMoved;
  private int dragStartX;
  private int dragStartY;
  private ReferencedEnvelope dragStartDisplayArea;

  ReferencedEnvelope displayArea() {
    return displayArea == null ? null : new ReferencedEnvelope(displayArea);
  }

  void setDisplayArea(ReferencedEnvelope displayArea) {
    this.displayArea = displayArea == null ? null : new ReferencedEnvelope(displayArea);
  }

  int canvasWidth() {
    return canvasWidth;
  }

  int canvasHeight() {
    return canvasHeight;
  }

  int deviceZoom() {
    return deviceZoom;
  }

  boolean updateCanvasMetrics(int canvasWidth, int canvasHeight, int deviceZoom) {
    int normalizedWidth = Math.max(0, canvasWidth);
    int normalizedHeight = Math.max(0, canvasHeight);
    int normalizedZoom = Math.max(100, deviceZoom);

    boolean changed =
        this.canvasWidth != normalizedWidth
            || this.canvasHeight != normalizedHeight
            || this.deviceZoom != normalizedZoom;

    this.canvasWidth = normalizedWidth;
    this.canvasHeight = normalizedHeight;
    this.deviceZoom = normalizedZoom;
    return changed;
  }

  boolean isCanvasUsable() {
    return canvasWidth > 1 && canvasHeight > 1;
  }

  void beginDrag(int x, int y) {
    dragging = true;
    dragMoved = false;
    dragStartX = x;
    dragStartY = y;
    dragStartDisplayArea = displayArea();
  }

  boolean isDragging() {
    return dragging;
  }

  int dragStartX() {
    return dragStartX;
  }

  int dragStartY() {
    return dragStartY;
  }

  ReferencedEnvelope dragStartDisplayArea() {
    return dragStartDisplayArea == null ? null : new ReferencedEnvelope(dragStartDisplayArea);
  }

  void markDragMoved() {
    dragMoved = true;
  }

  boolean dragMoved() {
    return dragMoved;
  }

  void endDrag() {
    dragging = false;
    dragMoved = false;
    dragStartDisplayArea = null;
  }
}
