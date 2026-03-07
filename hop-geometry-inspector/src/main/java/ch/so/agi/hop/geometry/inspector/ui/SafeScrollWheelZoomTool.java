package ch.so.agi.hop.geometry.inspector.ui;

import org.geotools.geometry.Position2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.swing.JMapPane;
import org.geotools.swing.event.MapMouseAdapter;
import org.geotools.swing.event.MapMouseEvent;

final class SafeScrollWheelZoomTool extends MapMouseAdapter {

  private static final double DEFAULT_ZOOM_FACTOR = 1.5d;

  private final JMapPane mapPane;
  private final double zoomFactor;

  SafeScrollWheelZoomTool(JMapPane mapPane) {
    this(mapPane, DEFAULT_ZOOM_FACTOR);
  }

  SafeScrollWheelZoomTool(JMapPane mapPane, double zoomFactor) {
    this.mapPane = mapPane;
    this.zoomFactor = zoomFactor > 1.0d ? zoomFactor : DEFAULT_ZOOM_FACTOR;
  }

  @Override
  public void onMouseWheelMoved(MapMouseEvent event) {
    if (event == null) {
      return;
    }
    handleWheelZoom(event.getWorldPos(), event.getWheelAmount());
  }

  boolean handleWheelZoom(Position2D worldPosition, int wheelAmount) {
    if (!GeometryInspectorViewportSupport.isWheelZoomReady(mapPane)
        || worldPosition == null
        || wheelAmount == 0) {
      return false;
    }

    ReferencedEnvelope currentDisplayArea =
        GeometryInspectorViewportSupport.normalizeEnvelope(mapPane.getDisplayArea());
    if (currentDisplayArea == null) {
      return false;
    }

    double factor =
        wheelAmount > 0
            ? Math.pow(zoomFactor, wheelAmount)
            : Math.pow(1.0d / zoomFactor, -wheelAmount);

    ReferencedEnvelope nextDisplayArea =
        GeometryInspectorViewportSupport.scaleAroundAnchor(
            currentDisplayArea, worldPosition, factor);
    return GeometryInspectorViewportSupport.safeSetDisplayArea(mapPane, nextDisplayArea);
  }
}
