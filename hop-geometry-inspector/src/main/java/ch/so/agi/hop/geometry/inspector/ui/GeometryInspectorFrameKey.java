package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import org.geotools.geometry.jts.ReferencedEnvelope;

record GeometryInspectorFrameKey(
    long minX,
    long maxX,
    long minY,
    long maxY,
    int pixelWidth,
    int pixelHeight,
    int deviceZoom,
    int outputDpi,
    Integer srid,
    String serviceUrl,
    String layerNames,
    String styleName,
    String imageFormat,
    String version,
    boolean transparent,
    boolean enabled) {

  private static final double ROUNDING_SCALE = 1_000_000.0d;

  static GeometryInspectorFrameKey forBackground(
      GeometryInspectorBackgroundMapConfig config,
      ReferencedEnvelope displayArea,
      int pixelWidth,
      int pixelHeight,
      int deviceZoom,
      int outputDpi,
      Integer srid,
      boolean enabled) {
    ReferencedEnvelope normalized = displayArea == null ? null : new ReferencedEnvelope(displayArea);
    return new GeometryInspectorFrameKey(
        round(normalized == null ? 0.0d : normalized.getMinX()),
        round(normalized == null ? 0.0d : normalized.getMaxX()),
        round(normalized == null ? 0.0d : normalized.getMinY()),
        round(normalized == null ? 0.0d : normalized.getMaxY()),
        pixelWidth,
        pixelHeight,
        deviceZoom,
        outputDpi,
        srid,
        config == null ? "" : config.serviceUrl(),
        config == null ? "" : String.join(",", config.parsedLayerNames()),
        config == null ? "" : config.styleName(),
        config == null ? "" : config.imageFormat(),
        config == null ? "" : config.version(),
        config != null && config.transparent(),
        enabled);
  }

  private static long round(double value) {
    return Math.round(value * ROUNDING_SCALE);
  }
}
