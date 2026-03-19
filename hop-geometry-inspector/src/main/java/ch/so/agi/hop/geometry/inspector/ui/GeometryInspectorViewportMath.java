package ch.so.agi.hop.geometry.inspector.ui;

import java.awt.Rectangle;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

final class GeometryInspectorViewportMath {

  static final double ABS_MIN_SPAN = 1.0e-9d;
  private static final double MISSING_AXIS_RATIO = 0.02d;
  private static final double GEOGRAPHIC_POINT_SPAN = 0.0001d;
  private static final double DEFAULT_POINT_SPAN = 1.0d;
  private static final double INITIAL_PADDING_RATIO = 0.02d;
  private static final double ASPECT_EPSILON = 1.0e-9d;

  private GeometryInspectorViewportMath() {}

  static ReferencedEnvelope normalizeExtent(ReferencedEnvelope envelope) {
    if (envelope == null || envelope.isEmpty()) {
      return null;
    }

    CoordinateReferenceSystem coordinateReferenceSystem = envelope.getCoordinateReferenceSystem();
    double centerX = finiteMidpoint(envelope.getMinX(), envelope.getMaxX());
    double centerY = finiteMidpoint(envelope.getMinY(), envelope.getMaxY());
    double originalWidth = envelope.getWidth();
    double originalHeight = envelope.getHeight();

    boolean validWidth = isPositiveFinite(originalWidth);
    boolean validHeight = isPositiveFinite(originalHeight);

    double width;
    double height;

    if (validWidth && validHeight) {
      width = originalWidth;
      height = originalHeight;
    } else if (validWidth) {
      width = originalWidth;
      height = Math.max(originalWidth * MISSING_AXIS_RATIO, ABS_MIN_SPAN);
    } else if (validHeight) {
      height = originalHeight;
      width = Math.max(originalHeight * MISSING_AXIS_RATIO, ABS_MIN_SPAN);
    } else {
      double defaultSpan = defaultPointSpan(coordinateReferenceSystem);
      width = defaultSpan;
      height = defaultSpan;
    }

    if (!Double.isFinite(centerX)) {
      centerX = 0.0d;
    }
    if (!Double.isFinite(centerY)) {
      centerY = 0.0d;
    }

    return new ReferencedEnvelope(
        centerX - (width / 2.0d),
        centerX + (width / 2.0d),
        centerY - (height / 2.0d),
        centerY + (height / 2.0d),
        coordinateReferenceSystem);
  }

  static ReferencedEnvelope paddedInitialExtent(ReferencedEnvelope envelope) {
    ReferencedEnvelope normalized = normalizeExtent(envelope);
    if (normalized == null) {
      return null;
    }
    double widthPadding = Math.max(normalized.getWidth() * INITIAL_PADDING_RATIO, ABS_MIN_SPAN);
    double heightPadding = Math.max(normalized.getHeight() * INITIAL_PADDING_RATIO, ABS_MIN_SPAN);
    return new ReferencedEnvelope(
        normalized.getMinX() - widthPadding,
        normalized.getMaxX() + widthPadding,
        normalized.getMinY() - heightPadding,
        normalized.getMaxY() + heightPadding,
        normalized.getCoordinateReferenceSystem());
  }

  static ReferencedEnvelope paddedFeatureExtent(
      Geometry geometry, CoordinateReferenceSystem coordinateReferenceSystem) {
    if (geometry == null) {
      return null;
    }
    Envelope geometryEnvelope = geometry.getEnvelopeInternal();
    if (geometryEnvelope == null || geometryEnvelope.isNull()) {
      return null;
    }
    return paddedInitialExtent(
        new ReferencedEnvelope(
            geometryEnvelope.getMinX(),
            geometryEnvelope.getMaxX(),
            geometryEnvelope.getMinY(),
            geometryEnvelope.getMaxY(),
            coordinateReferenceSystem));
  }

  static ReferencedEnvelope fitToCanvasAspect(
      ReferencedEnvelope envelope, int canvasWidth, int canvasHeight) {
    ReferencedEnvelope normalized = normalizeExtent(envelope);
    if (normalized == null || canvasWidth <= 1 || canvasHeight <= 1) {
      return normalized;
    }

    double canvasAspect = (double) canvasWidth / canvasHeight;
    if (!Double.isFinite(canvasAspect) || canvasAspect <= 0.0d) {
      return normalized;
    }

    double width = normalized.getWidth();
    double height = normalized.getHeight();
    double envelopeAspect = width / height;
    if (!Double.isFinite(envelopeAspect) || envelopeAspect <= 0.0d) {
      return normalized;
    }
    if (Math.abs(envelopeAspect - canvasAspect) <= ASPECT_EPSILON) {
      return normalized;
    }

    if (envelopeAspect < canvasAspect) {
      width = Math.max(width, height * canvasAspect);
    } else {
      height = Math.max(height, width / canvasAspect);
    }

    return new ReferencedEnvelope(
        normalized.getCenterX() - (width / 2.0d),
        normalized.getCenterX() + (width / 2.0d),
        normalized.getCenterY() - (height / 2.0d),
        normalized.getCenterY() + (height / 2.0d),
        normalized.getCoordinateReferenceSystem());
  }

  static ReferencedEnvelope intersectAreas(
      ReferencedEnvelope first, ReferencedEnvelope second) {
    ReferencedEnvelope normalizedFirst = normalizeExtent(first);
    ReferencedEnvelope normalizedSecond = normalizeExtent(second);
    if (normalizedFirst == null || normalizedSecond == null) {
      return null;
    }

    double minX = Math.max(normalizedFirst.getMinX(), normalizedSecond.getMinX());
    double maxX = Math.min(normalizedFirst.getMaxX(), normalizedSecond.getMaxX());
    double minY = Math.max(normalizedFirst.getMinY(), normalizedSecond.getMinY());
    double maxY = Math.min(normalizedFirst.getMaxY(), normalizedSecond.getMaxY());
    if (!Double.isFinite(minX)
        || !Double.isFinite(maxX)
        || !Double.isFinite(minY)
        || !Double.isFinite(maxY)
        || maxX <= minX
        || maxY <= minY) {
      return null;
    }

    CoordinateReferenceSystem coordinateReferenceSystem =
        normalizedFirst.getCoordinateReferenceSystem() != null
            ? normalizedFirst.getCoordinateReferenceSystem()
            : normalizedSecond.getCoordinateReferenceSystem();
    return new ReferencedEnvelope(minX, maxX, minY, maxY, coordinateReferenceSystem);
  }

  static Rectangle worldToPixelRect(
      ReferencedEnvelope viewArea, int pixelWidth, int pixelHeight, ReferencedEnvelope worldArea) {
    ReferencedEnvelope normalizedViewArea = normalizeExtent(viewArea);
    ReferencedEnvelope clippedWorldArea = intersectAreas(normalizedViewArea, worldArea);
    if (normalizedViewArea == null
        || clippedWorldArea == null
        || pixelWidth <= 0
        || pixelHeight <= 0) {
      return new Rectangle();
    }

    double scaleX = pixelWidth / normalizedViewArea.getWidth();
    double scaleY = pixelHeight / normalizedViewArea.getHeight();
    if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY) || scaleX <= 0.0d || scaleY <= 0.0d) {
      return new Rectangle();
    }

    int minX =
        clampToRange(
            (int)
                Math.floor((clippedWorldArea.getMinX() - normalizedViewArea.getMinX()) * scaleX),
            0,
            pixelWidth);
    int maxX =
        clampToRange(
            (int)
                Math.ceil((clippedWorldArea.getMaxX() - normalizedViewArea.getMinX()) * scaleX),
            0,
            pixelWidth);
    int minY =
        clampToRange(
            (int)
                Math.floor((normalizedViewArea.getMaxY() - clippedWorldArea.getMaxY()) * scaleY),
            0,
            pixelHeight);
    int maxY =
        clampToRange(
            (int)
                Math.ceil((normalizedViewArea.getMaxY() - clippedWorldArea.getMinY()) * scaleY),
            0,
            pixelHeight);

    return new Rectangle(minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY));
  }

  static GeometryInspectorViewTransform createViewTransform(
      ReferencedEnvelope displayArea, int canvasWidth, int canvasHeight) {
    ReferencedEnvelope renderArea = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    if (renderArea == null || canvasWidth <= 1 || canvasHeight <= 1) {
      return null;
    }

    double scaleX = canvasWidth / renderArea.getWidth();
    double scaleY = canvasHeight / renderArea.getHeight();
    double scale = Math.min(scaleX, scaleY);
    if (!Double.isFinite(scale) || scale <= 0.0d) {
      return null;
    }

    return new GeometryInspectorViewTransform(
        renderArea, canvasWidth, canvasHeight, scale, 0.0d, 0.0d);
  }

  static ReferencedEnvelope zoomAt(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      int screenX,
      int screenY,
      double factor) {
    ReferencedEnvelope normalized = normalizeExtent(displayArea);
    ReferencedEnvelope renderArea = fitToCanvasAspect(normalized, canvasWidth, canvasHeight);
    GeometryInspectorViewTransform viewTransform =
        createViewTransform(renderArea, canvasWidth, canvasHeight);
    if (viewTransform == null
        || canvasWidth <= 1
        || canvasHeight <= 1
        || !Double.isFinite(factor)
        || factor <= 0.0d) {
      return normalized;
    }

    Coordinate anchor = viewTransform.screenToWorld(screenX, screenY);
    double width = Math.max(renderArea.getWidth() * factor, ABS_MIN_SPAN);
    double height = Math.max(renderArea.getHeight() * factor, ABS_MIN_SPAN);

    double xRatio = clamp((anchor.x - renderArea.getMinX()) / renderArea.getWidth());
    double yRatio = clamp((renderArea.getMaxY() - anchor.y) / renderArea.getHeight());

    double minX = anchor.x - (width * xRatio);
    double maxX = minX + width;
    double maxY = anchor.y + (height * yRatio);
    double minY = maxY - height;

    return normalizeExtent(
        new ReferencedEnvelope(minX, maxX, minY, maxY, normalized.getCoordinateReferenceSystem()));
  }

  static ReferencedEnvelope zoomByFactor(
      ReferencedEnvelope displayArea, int canvasWidth, int canvasHeight, double factor) {
    ReferencedEnvelope renderArea = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    if (renderArea == null || !Double.isFinite(factor) || factor <= 0.0d) {
      return normalizeExtent(displayArea);
    }

    double width = Math.max(renderArea.getWidth() * factor, ABS_MIN_SPAN);
    double height = Math.max(renderArea.getHeight() * factor, ABS_MIN_SPAN);

    return normalizeExtent(
        new ReferencedEnvelope(
            renderArea.getCenterX() - (width / 2.0d),
            renderArea.getCenterX() + (width / 2.0d),
            renderArea.getCenterY() - (height / 2.0d),
            renderArea.getCenterY() + (height / 2.0d),
            renderArea.getCoordinateReferenceSystem()));
  }

  static ReferencedEnvelope pan(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      int deltaX,
      int deltaY) {
    ReferencedEnvelope renderArea = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    GeometryInspectorViewTransform viewTransform =
        createViewTransform(renderArea, canvasWidth, canvasHeight);
    if (viewTransform == null || canvasWidth <= 1 || canvasHeight <= 1) {
      return normalizeExtent(displayArea);
    }

    double worldDeltaX = deltaX / viewTransform.scale();
    double worldDeltaY = deltaY / viewTransform.scale();

    return normalizeExtent(
        new ReferencedEnvelope(
            renderArea.getMinX() - worldDeltaX,
            renderArea.getMaxX() - worldDeltaX,
            renderArea.getMinY() + worldDeltaY,
            renderArea.getMaxY() + worldDeltaY,
            renderArea.getCoordinateReferenceSystem()));
  }

  static Coordinate screenToWorld(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      int screenX,
      int screenY) {
    GeometryInspectorViewTransform viewTransform =
        createViewTransform(displayArea, canvasWidth, canvasHeight);
    if (viewTransform == null) {
      return new Coordinate(0.0d, 0.0d);
    }
    return viewTransform.screenToWorld(screenX, screenY);
  }

  static Envelope pickEnvelope(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      int screenX,
      int screenY,
      double tolerancePixels) {
    GeometryInspectorViewTransform viewTransform =
        createViewTransform(displayArea, canvasWidth, canvasHeight);
    if (viewTransform == null || canvasWidth <= 1 || canvasHeight <= 1) {
      return new Envelope();
    }
    return viewTransform.pickEnvelope(screenX, screenY, tolerancePixels);
  }

  static int worldToScreenX(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      double worldX) {
    GeometryInspectorViewTransform viewTransform =
        createViewTransform(displayArea, canvasWidth, canvasHeight);
    if (viewTransform == null || canvasWidth <= 1) {
      return 0;
    }
    return viewTransform.worldToScreenX(worldX);
  }

  static int worldToScreenY(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      double worldY) {
    GeometryInspectorViewTransform viewTransform =
        createViewTransform(displayArea, canvasWidth, canvasHeight);
    if (viewTransform == null || canvasHeight <= 1) {
      return 0;
    }
    return viewTransform.worldToScreenY(worldY);
  }

  static boolean sameArea(ReferencedEnvelope left, ReferencedEnvelope right) {
    ReferencedEnvelope normalizedLeft = normalizeExtent(left);
    ReferencedEnvelope normalizedRight = normalizeExtent(right);
    if (normalizedLeft == null || normalizedRight == null) {
      return normalizedLeft == normalizedRight;
    }
    return nearlyEqual(normalizedLeft.getMinX(), normalizedRight.getMinX())
        && nearlyEqual(normalizedLeft.getMaxX(), normalizedRight.getMaxX())
        && nearlyEqual(normalizedLeft.getMinY(), normalizedRight.getMinY())
        && nearlyEqual(normalizedLeft.getMaxY(), normalizedRight.getMaxY());
  }

  static int toPixelSize(int logicalSize, int deviceZoom) {
    return Math.max(1, (int) Math.round(logicalSize * (Math.max(100, deviceZoom) / 100.0d)));
  }

  private static boolean nearlyEqual(double left, double right) {
    return Math.abs(left - right) <= 1.0e-9d;
  }

  private static double clamp(double value) {
    if (!Double.isFinite(value)) {
      return 0.5d;
    }
    return Math.max(0.0d, Math.min(1.0d, value));
  }

  private static int clampToRange(int value, int min, int max) {
    if (value < min) {
      return min;
    }
    return Math.min(value, max);
  }

  private static double finiteMidpoint(double min, double max) {
    if (Double.isFinite(min) && Double.isFinite(max)) {
      return (min + max) * 0.5d;
    }
    if (Double.isFinite(min)) {
      return min;
    }
    if (Double.isFinite(max)) {
      return max;
    }
    return 0.0d;
  }

  private static boolean isPositiveFinite(double value) {
    return Double.isFinite(value) && value > ABS_MIN_SPAN;
  }

  private static double defaultPointSpan(CoordinateReferenceSystem coordinateReferenceSystem) {
    if (coordinateReferenceSystem instanceof GeographicCRS) {
      return GEOGRAPHIC_POINT_SPAN;
    }
    return DEFAULT_POINT_SPAN;
  }
}
