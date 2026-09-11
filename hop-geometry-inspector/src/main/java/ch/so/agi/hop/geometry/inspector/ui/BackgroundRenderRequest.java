package ch.so.agi.hop.geometry.inspector.ui;

import org.geotools.geometry.jts.ReferencedEnvelope;

record BackgroundRenderRequest(
    ReferencedEnvelope displayArea,
    int logicalWidth,
    int logicalHeight,
    int pixelWidth,
    int pixelHeight,
    int deviceZoom,
    int outputDpi,
    Integer srid,
    long revision,
    java.util.function.BooleanSupplier current) {
  BackgroundRenderRequest withValidity(java.util.function.BooleanSupplier current) {
    return new BackgroundRenderRequest(
        displayArea,
        logicalWidth,
        logicalHeight,
        pixelWidth,
        pixelHeight,
        deviceZoom,
        outputDpi,
        srid,
        revision,
        current);
  }

  static BackgroundRenderRequest create(
      ReferencedEnvelope area,
      int width,
      int height,
      int zoom,
      int dpi,
      Integer srid,
      long revision) {
    return new BackgroundRenderRequest(
        GeometryInspectorViewportMath.fitToCanvasAspect(area, width, height),
        width,
        height,
        GeometryInspectorViewportMath.toPixelSize(width, zoom),
        GeometryInspectorViewportMath.toPixelSize(height, zoom),
        zoom,
        dpi,
        srid,
        revision,
        () -> true);
  }
}
