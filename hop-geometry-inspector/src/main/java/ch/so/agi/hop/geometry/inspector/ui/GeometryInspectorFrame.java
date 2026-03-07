package ch.so.agi.hop.geometry.inspector.ui;

import org.eclipse.swt.graphics.Image;
import org.geotools.geometry.jts.ReferencedEnvelope;

final class GeometryInspectorFrame implements GeometryInspectorDisposableResource {

  private final ReferencedEnvelope displayArea;
  private final int logicalWidth;
  private final int logicalHeight;
  private final int deviceZoom;
  private final long revision;
  private final Image image;

  GeometryInspectorFrame(
      ReferencedEnvelope displayArea,
      int logicalWidth,
      int logicalHeight,
      int deviceZoom,
      long revision,
      Image image) {
    this.displayArea = displayArea == null ? null : new ReferencedEnvelope(displayArea);
    this.logicalWidth = logicalWidth;
    this.logicalHeight = logicalHeight;
    this.deviceZoom = deviceZoom;
    this.revision = revision;
    this.image = image;
  }

  ReferencedEnvelope displayArea() {
    return displayArea == null ? null : new ReferencedEnvelope(displayArea);
  }

  int logicalWidth() {
    return logicalWidth;
  }

  int logicalHeight() {
    return logicalHeight;
  }

  int deviceZoom() {
    return deviceZoom;
  }

  long revision() {
    return revision;
  }

  Image image() {
    return image;
  }

  boolean matches(GeometryInspectorViewportModel viewportModel) {
    return viewportModel != null
        && GeometryInspectorViewportMath.sameArea(displayArea, viewportModel.displayArea())
        && logicalWidth == viewportModel.canvasWidth()
        && logicalHeight == viewportModel.canvasHeight()
        && deviceZoom == viewportModel.deviceZoom();
  }

  @Override
  public void dispose() {
    if (image != null && !image.isDisposed()) {
      image.dispose();
    }
  }
}
