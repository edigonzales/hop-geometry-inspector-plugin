package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;

record BackgroundRenderResult(
    GeometryInspectorRasterData raster,
    GeometryInspectorBackgroundMapConfig resolvedConfig,
    String warning) {
  boolean complete() {
    return warning.isEmpty();
  }
}
