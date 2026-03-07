package ch.so.agi.hop.geometry.inspector.ui;

import java.awt.Rectangle;
import java.awt.geom.NoninvertibleTransformException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.geometry.Position2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.swing.JMapPane;

final class GeometryInspectorViewportSupport {

  static final double ABS_MIN_SPAN = 1.0e-9d;
  private static final double MISSING_AXIS_RATIO = 0.02d;
  private static final double GEOGRAPHIC_POINT_SPAN = 0.0001d;
  private static final double DEFAULT_POINT_SPAN = 1.0d;

  private GeometryInspectorViewportSupport() {}

  static boolean isWheelZoomReady(JMapPane mapPane) {
    if (mapPane == null || !mapPane.isShowing()) {
      return false;
    }

    Rectangle visibleRect = mapPane.getVisibleRect();
    if (visibleRect == null || visibleRect.width <= 1 || visibleRect.height <= 1) {
      return false;
    }

    return normalizeEnvelope(mapPane.getDisplayArea()) != null;
  }

  static boolean canApplyDisplayArea(JMapPane mapPane) {
    if (mapPane == null) {
      return false;
    }

    Rectangle visibleRect = mapPane.getVisibleRect();
    if (visibleRect != null && visibleRect.width > 1 && visibleRect.height > 1) {
      return true;
    }

    return mapPane.getWidth() > 1 && mapPane.getHeight() > 1;
  }

  static ReferencedEnvelope normalizeEnvelope(ReferencedEnvelope envelope) {
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

  static ReferencedEnvelope scaleAroundCenter(ReferencedEnvelope displayArea, double factor) {
    ReferencedEnvelope normalized = normalizeEnvelope(displayArea);
    if (normalized == null || !Double.isFinite(factor) || factor <= 0.0d) {
      return null;
    }

    double width = Math.max(normalized.getWidth() * factor, ABS_MIN_SPAN);
    double height = Math.max(normalized.getHeight() * factor, ABS_MIN_SPAN);

    return new ReferencedEnvelope(
        normalized.getCenterX() - (width / 2.0d),
        normalized.getCenterX() + (width / 2.0d),
        normalized.getCenterY() - (height / 2.0d),
        normalized.getCenterY() + (height / 2.0d),
        normalized.getCoordinateReferenceSystem());
  }

  static ReferencedEnvelope scaleAroundAnchor(
      ReferencedEnvelope displayArea, Position2D anchor, double factor) {
    ReferencedEnvelope normalized = normalizeEnvelope(displayArea);
    if (normalized == null || anchor == null || !Double.isFinite(factor) || factor <= 0.0d) {
      return null;
    }

    double width = Math.max(normalized.getWidth() * factor, ABS_MIN_SPAN);
    double height = Math.max(normalized.getHeight() * factor, ABS_MIN_SPAN);

    double xRatio = safeRatio(anchor.getX(), normalized.getMinX(), normalized.getWidth());
    double yRatio = safeRatio(normalized.getMaxY() - anchor.getY(), 0.0d, normalized.getHeight());

    double minX = anchor.getX() - (width * xRatio);
    double maxX = minX + width;
    double maxY = anchor.getY() + (height * yRatio);
    double minY = maxY - height;

    return normalizeEnvelope(
        new ReferencedEnvelope(
            minX, maxX, minY, maxY, normalized.getCoordinateReferenceSystem()));
  }

  static boolean safeSetDisplayArea(JMapPane mapPane, ReferencedEnvelope targetEnvelope) {
    ReferencedEnvelope normalized = normalizeEnvelope(targetEnvelope);
    if (normalized == null || !canApplyDisplayArea(mapPane)) {
      return false;
    }

    try {
      mapPane.setDisplayArea(normalized);
      return true;
    } catch (RuntimeException e) {
      if (hasNoninvertibleTransformRootCause(e)) {
        return false;
      }
      throw e;
    }
  }

  private static boolean hasNoninvertibleTransformRootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof NoninvertibleTransformException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
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

  private static double safeRatio(double numerator, double minimum, double denominator) {
    if (!Double.isFinite(numerator) || !Double.isFinite(denominator) || denominator <= ABS_MIN_SPAN) {
      return 0.5d;
    }
    double ratio = (numerator - minimum) / denominator;
    if (!Double.isFinite(ratio)) {
      return 0.5d;
    }
    return Math.max(0.0d, Math.min(1.0d, ratio));
  }

  private static double defaultPointSpan(CoordinateReferenceSystem coordinateReferenceSystem) {
    if (coordinateReferenceSystem instanceof GeographicCRS) {
      return GEOGRAPHIC_POINT_SPAN;
    }
    return DEFAULT_POINT_SPAN;
  }
}
