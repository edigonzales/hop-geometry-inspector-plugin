package ch.so.agi.hop.geometry.inspector.ui;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;

final class GeometryInspectorViewportMath {

  static final double ABS_MIN_SPAN = 1.0e-9d;
  private static final double MISSING_AXIS_RATIO = 0.02d;
  private static final double GEOGRAPHIC_POINT_SPAN = 0.0001d;
  private static final double DEFAULT_POINT_SPAN = 1.0d;
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

  static ReferencedEnvelope zoomAt(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      int screenX,
      int screenY,
      double factor) {
    ReferencedEnvelope fitted = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    if (fitted == null
        || canvasWidth <= 1
        || canvasHeight <= 1
        || !Double.isFinite(factor)
        || factor <= 0.0d) {
      return fitted;
    }

    Coordinate anchor = screenToWorld(fitted, canvasWidth, canvasHeight, screenX, screenY);
    double width = Math.max(fitted.getWidth() * factor, ABS_MIN_SPAN);
    double height = Math.max(fitted.getHeight() * factor, ABS_MIN_SPAN);

    double xRatio = clamp((anchor.x - fitted.getMinX()) / fitted.getWidth());
    double yRatio = clamp((fitted.getMaxY() - anchor.y) / fitted.getHeight());

    double minX = anchor.x - (width * xRatio);
    double maxX = minX + width;
    double maxY = anchor.y + (height * yRatio);
    double minY = maxY - height;

    return fitToCanvasAspect(
        new ReferencedEnvelope(minX, maxX, minY, maxY, fitted.getCoordinateReferenceSystem()),
        canvasWidth,
        canvasHeight);
  }

  static ReferencedEnvelope zoomByFactor(
      ReferencedEnvelope displayArea, int canvasWidth, int canvasHeight, double factor) {
    ReferencedEnvelope fitted = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    if (fitted == null || !Double.isFinite(factor) || factor <= 0.0d) {
      return fitted;
    }

    double width = Math.max(fitted.getWidth() * factor, ABS_MIN_SPAN);
    double height = Math.max(fitted.getHeight() * factor, ABS_MIN_SPAN);

    return fitToCanvasAspect(
        new ReferencedEnvelope(
            fitted.getCenterX() - (width / 2.0d),
            fitted.getCenterX() + (width / 2.0d),
            fitted.getCenterY() - (height / 2.0d),
            fitted.getCenterY() + (height / 2.0d),
            fitted.getCoordinateReferenceSystem()),
        canvasWidth,
        canvasHeight);
  }

  static ReferencedEnvelope pan(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      int deltaX,
      int deltaY) {
    ReferencedEnvelope fitted = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    if (fitted == null || canvasWidth <= 1 || canvasHeight <= 1) {
      return fitted;
    }

    double worldDeltaX = (fitted.getWidth() / canvasWidth) * deltaX;
    double worldDeltaY = (fitted.getHeight() / canvasHeight) * deltaY;

    return fitToCanvasAspect(
        new ReferencedEnvelope(
            fitted.getMinX() - worldDeltaX,
            fitted.getMaxX() - worldDeltaX,
            fitted.getMinY() + worldDeltaY,
            fitted.getMaxY() + worldDeltaY,
            fitted.getCoordinateReferenceSystem()),
        canvasWidth,
        canvasHeight);
  }

  static Coordinate screenToWorld(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      int screenX,
      int screenY) {
    ReferencedEnvelope fitted = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    if (fitted == null || canvasWidth <= 1 || canvasHeight <= 1) {
      return new Coordinate(0.0d, 0.0d);
    }

    double x = fitted.getMinX() + ((double) screenX / canvasWidth) * fitted.getWidth();
    double y = fitted.getMaxY() - ((double) screenY / canvasHeight) * fitted.getHeight();
    return new Coordinate(x, y);
  }

  static Envelope pickEnvelope(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      int screenX,
      int screenY,
      double tolerancePixels) {
    ReferencedEnvelope fitted = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    if (fitted == null || canvasWidth <= 1 || canvasHeight <= 1) {
      return new Envelope();
    }

    double toleranceX = Math.max((fitted.getWidth() / canvasWidth) * tolerancePixels, ABS_MIN_SPAN);
    double toleranceY = Math.max((fitted.getHeight() / canvasHeight) * tolerancePixels, ABS_MIN_SPAN);
    Coordinate coordinate = screenToWorld(fitted, canvasWidth, canvasHeight, screenX, screenY);
    return new Envelope(
        coordinate.x - toleranceX,
        coordinate.x + toleranceX,
        coordinate.y - toleranceY,
        coordinate.y + toleranceY);
  }

  static int worldToScreenX(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      double worldX) {
    ReferencedEnvelope fitted = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    if (fitted == null || canvasWidth <= 1) {
      return 0;
    }
    double ratio = (worldX - fitted.getMinX()) / fitted.getWidth();
    return (int) Math.round(ratio * canvasWidth);
  }

  static int worldToScreenY(
      ReferencedEnvelope displayArea,
      int canvasWidth,
      int canvasHeight,
      double worldY) {
    ReferencedEnvelope fitted = fitToCanvasAspect(displayArea, canvasWidth, canvasHeight);
    if (fitted == null || canvasHeight <= 1) {
      return 0;
    }
    double ratio = (fitted.getMaxY() - worldY) / fitted.getHeight();
    return (int) Math.round(ratio * canvasHeight);
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
