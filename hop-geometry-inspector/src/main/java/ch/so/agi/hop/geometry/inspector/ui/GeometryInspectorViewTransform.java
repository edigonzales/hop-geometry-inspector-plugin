package ch.so.agi.hop.geometry.inspector.ui;

import org.geotools.geometry.jts.ReferencedEnvelope;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;

record GeometryInspectorViewTransform(
    ReferencedEnvelope displayArea,
    int canvasWidth,
    int canvasHeight,
    double scale,
    double offsetX,
    double offsetY) {

  int logicalRenderWidth() {
    return Math.max(1, canvasWidth);
  }

  int logicalRenderHeight() {
    return Math.max(1, canvasHeight);
  }

  int paintX() {
    return (int) Math.round(offsetX);
  }

  int paintY() {
    return (int) Math.round(offsetY);
  }

  boolean containsScreenPoint(int screenX, int screenY) {
    return screenX >= 0 && screenX <= canvasWidth && screenY >= 0 && screenY <= canvasHeight;
  }

  int worldToScreenX(double worldX) {
    return (int) Math.round(offsetX + ((worldX - displayArea.getMinX()) * scale));
  }

  int worldToScreenY(double worldY) {
    return (int) Math.round(offsetY + ((displayArea.getMaxY() - worldY) * scale));
  }

  Coordinate screenToWorld(int screenX, int screenY) {
    double worldX = displayArea.getMinX() + ((screenX - offsetX) / scale);
    double worldY = displayArea.getMaxY() - ((screenY - offsetY) / scale);
    return new Coordinate(worldX, worldY);
  }

  Envelope pickEnvelope(int screenX, int screenY, double tolerancePixels) {
    double toleranceWorld = Math.max(tolerancePixels / scale, GeometryInspectorViewportMath.ABS_MIN_SPAN);
    Coordinate coordinate = screenToWorld(screenX, screenY);
    return new Envelope(
        coordinate.x - toleranceWorld,
        coordinate.x + toleranceWorld,
        coordinate.y - toleranceWorld,
        coordinate.y + toleranceWorld);
  }
}
