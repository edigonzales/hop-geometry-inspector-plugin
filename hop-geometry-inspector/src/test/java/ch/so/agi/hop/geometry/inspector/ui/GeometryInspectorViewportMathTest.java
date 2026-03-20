package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Rectangle;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;

class GeometryInspectorViewportMathTest {

  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
  private static final double POINT_HALF_SPAN_METERS = 20.0d;
  private static final double METERS_PER_DEGREE_LAT = 111_320.0d;

  @Test
  void normalizesMissingWidthFromExistingHeight() {
    ReferencedEnvelope envelope = new ReferencedEnvelope(10.0d, 10.0d, 100.0d, 150.0d, null);

    ReferencedEnvelope normalized = GeometryInspectorViewportMath.normalizeExtent(envelope);

    assertThat(normalized.getWidth()).isEqualTo(1.0d);
    assertThat(normalized.getHeight()).isEqualTo(50.0d);
    assertThat(normalized.getCenterX()).isEqualTo(10.0d);
    assertThat(normalized.getCenterY()).isEqualTo(125.0d);
  }

  @Test
  void normalizesPointEnvelopeWithGeographicCrsUsingSmallDefaultSpan() {
    ReferencedEnvelope envelope =
        new ReferencedEnvelope(7.0d, 7.0d, 47.0d, 47.0d, DefaultGeographicCRS.WGS84);

    ReferencedEnvelope normalized = GeometryInspectorViewportMath.normalizeExtent(envelope);

    assertThat(normalized.getWidth()).isCloseTo(0.0001d, within(1.0e-12d));
    assertThat(normalized.getHeight()).isCloseTo(0.0001d, within(1.0e-12d));
  }

  @Test
  void paddedInitialExtentAddsSmallOneTimeMargin() {
    ReferencedEnvelope envelope = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    ReferencedEnvelope padded = GeometryInspectorViewportMath.paddedInitialExtent(envelope);

    assertThat(padded.getWidth()).isEqualTo(104.0d);
    assertThat(padded.getHeight()).isEqualTo(52.0d);
    assertThat(padded.getCenterX()).isEqualTo(50.0d);
    assertThat(padded.getCenterY()).isEqualTo(25.0d);
  }

  @Test
  void paddedFeatureExtentUsesFixedSelectionBoxForProjectedPointGeometry() {
    ReferencedEnvelope padded =
        GeometryInspectorViewportMath.paddedFeatureExtent(
            GEOMETRY_FACTORY.createPoint(new Coordinate(2_600_000.0d, 1_200_000.0d)), null);

    assertThat(padded.getWidth()).isEqualTo(40.0d);
    assertThat(padded.getHeight()).isEqualTo(40.0d);
    assertThat(padded.getCenterX()).isEqualTo(2_600_000.0d);
    assertThat(padded.getCenterY()).isEqualTo(1_200_000.0d);
  }

  @Test
  void paddedFeatureExtentUsesFixedSelectionBoxForGeographicPointGeometry() {
    ReferencedEnvelope padded =
        GeometryInspectorViewportMath.paddedFeatureExtent(
            GEOMETRY_FACTORY.createPoint(new Coordinate(7.0d, 47.0d)), DefaultGeographicCRS.WGS84);

    double expectedHalfLatDegrees = POINT_HALF_SPAN_METERS / METERS_PER_DEGREE_LAT;
    double expectedHalfLonDegrees =
        POINT_HALF_SPAN_METERS / (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(47.0d)));

    assertThat(padded.getWidth()).isCloseTo(expectedHalfLonDegrees * 2.0d, within(1.0e-12d));
    assertThat(padded.getHeight()).isCloseTo(expectedHalfLatDegrees * 2.0d, within(1.0e-12d));
    assertThat(padded.getCenterX()).isEqualTo(7.0d);
    assertThat(padded.getCenterY()).isEqualTo(47.0d);
  }

  @Test
  void paddedFeatureExtentUsesFixedSelectionBoxForMultiPointGeometry() {
    ReferencedEnvelope padded =
        GeometryInspectorViewportMath.paddedFeatureExtent(
            GEOMETRY_FACTORY.createMultiPoint(
                new Coordinate[] {new Coordinate(100.0d, 200.0d), new Coordinate(102.0d, 198.0d)}),
            null);

    assertThat(padded.getWidth()).isEqualTo(40.0d);
    assertThat(padded.getHeight()).isEqualTo(40.0d);
    assertThat(padded.getCenterX()).isEqualTo(101.0d);
    assertThat(padded.getCenterY()).isEqualTo(199.0d);
  }

  @Test
  void paddedFeatureExtentAddsMarginForLineGeometry() {
    ReferencedEnvelope padded =
        GeometryInspectorViewportMath.paddedFeatureExtent(
            GEOMETRY_FACTORY.createLineString(
                new Coordinate[] {new Coordinate(0, 0), new Coordinate(10, 5)}),
            null);

    assertThat(padded.getMinX()).isEqualTo(-0.2d);
    assertThat(padded.getMaxX()).isEqualTo(10.2d);
    assertThat(padded.getMinY()).isEqualTo(-0.1d);
    assertThat(padded.getMaxY()).isEqualTo(5.1d);
  }

  @Test
  void fitToCanvasAspectExpandsWidthForWiderCanvas() {
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 100.0d, null);

    ReferencedEnvelope renderArea =
        GeometryInspectorViewportMath.fitToCanvasAspect(displayArea, 600, 300);

    assertThat(renderArea.getWidth()).isEqualTo(200.0d);
    assertThat(renderArea.getHeight()).isEqualTo(100.0d);
    assertThat(renderArea.getCenterX()).isEqualTo(50.0d);
    assertThat(renderArea.getCenterY()).isEqualTo(50.0d);
  }

  @Test
  void fitToCanvasAspectExpandsHeightForTallerCanvas() {
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 200.0d, 0.0d, 100.0d, null);

    ReferencedEnvelope renderArea =
        GeometryInspectorViewportMath.fitToCanvasAspect(displayArea, 300, 300);

    assertThat(renderArea.getWidth()).isEqualTo(200.0d);
    assertThat(renderArea.getHeight()).isEqualTo(200.0d);
    assertThat(renderArea.getCenterX()).isEqualTo(100.0d);
    assertThat(renderArea.getCenterY()).isEqualTo(50.0d);
  }

  @Test
  void createViewTransformUsesFullCanvasWithoutOffsets() {
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    GeometryInspectorViewTransform transform =
        GeometryInspectorViewportMath.createViewTransform(displayArea, 300, 300);

    assertThat(transform.logicalRenderWidth()).isEqualTo(300);
    assertThat(transform.logicalRenderHeight()).isEqualTo(300);
    assertThat(transform.paintX()).isEqualTo(0);
    assertThat(transform.paintY()).isEqualTo(0);
    assertThat(transform.displayArea().getWidth()).isEqualTo(100.0d);
    assertThat(transform.displayArea().getHeight()).isEqualTo(100.0d);
  }

  @Test
  void zoomAtKeepsAnchorStableAgainstTransientRenderArea() {
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    ReferencedEnvelope zoomed =
        GeometryInspectorViewportMath.zoomAt(displayArea, 300, 300, 150, 150, 0.5d);
    Coordinate anchorBefore =
        GeometryInspectorViewportMath.screenToWorld(displayArea, 300, 300, 150, 150);
    Coordinate anchorAfter = GeometryInspectorViewportMath.screenToWorld(zoomed, 300, 300, 150, 150);

    assertThat(anchorAfter.x).isCloseTo(anchorBefore.x, within(1.0e-9d));
    assertThat(anchorAfter.y).isCloseTo(anchorBefore.y, within(1.0e-9d));
    assertThat(zoomed.getWidth()).isEqualTo(50.0d);
    assertThat(zoomed.getHeight()).isEqualTo(50.0d);
  }

  @Test
  void panUsesTransientRenderAreaScale() {
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    ReferencedEnvelope panned = GeometryInspectorViewportMath.pan(displayArea, 300, 300, 30, -15);

    assertThat(panned.getMinX()).isEqualTo(-10.0d);
    assertThat(panned.getMaxX()).isEqualTo(90.0d);
    assertThat(panned.getMinY()).isEqualTo(-30.0d);
    assertThat(panned.getMaxY()).isEqualTo(70.0d);
  }

  @Test
  void pickEnvelopeExpandsAroundPointerPositionUsingFullCanvasRenderArea() {
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    Envelope pickEnvelope =
        GeometryInspectorViewportMath.pickEnvelope(displayArea, 300, 300, 150, 150, 6.0d);

    assertThat(pickEnvelope.getWidth()).isCloseTo(4.0d, within(1.0e-9d));
    assertThat(pickEnvelope.getHeight()).isCloseTo(4.0d, within(1.0e-9d));
    assertThat(pickEnvelope.centre().x).isCloseTo(50.0d, within(1.0e-9d));
    assertThat(pickEnvelope.centre().y).isCloseTo(25.0d, within(1.0e-9d));
  }

  @Test
  void worldToPixelRectFillsCanvasWhenFrameAndViewMatch() {
    ReferencedEnvelope renderArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    Rectangle rect =
        GeometryInspectorViewportMath.worldToPixelRect(renderArea, 400, 200, renderArea);

    assertThat(rect.x).isEqualTo(0);
    assertThat(rect.y).isEqualTo(0);
    assertThat(rect.width).isEqualTo(400);
    assertThat(rect.height).isEqualTo(200);
  }

  @Test
  void worldToPixelRectShiftsDestinationDuringPanPreview() {
    ReferencedEnvelope currentRenderArea = new ReferencedEnvelope(-10.0d, 90.0d, 0.0d, 50.0d, null);
    ReferencedEnvelope previousFrameArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);
    ReferencedEnvelope visibleArea =
        GeometryInspectorViewportMath.intersectAreas(currentRenderArea, previousFrameArea);

    Rectangle destinationRect =
        GeometryInspectorViewportMath.worldToPixelRect(currentRenderArea, 400, 200, visibleArea);

    assertThat(destinationRect.x).isEqualTo(40);
    assertThat(destinationRect.y).isEqualTo(0);
    assertThat(destinationRect.width).isEqualTo(360);
    assertThat(destinationRect.height).isEqualTo(200);
  }

  @Test
  void worldToPixelRectCropsSourceDuringZoomInPreview() {
    ReferencedEnvelope currentRenderArea = new ReferencedEnvelope(25.0d, 75.0d, 0.0d, 50.0d, null);
    ReferencedEnvelope previousFrameArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);
    ReferencedEnvelope visibleArea =
        GeometryInspectorViewportMath.intersectAreas(currentRenderArea, previousFrameArea);

    Rectangle sourceRect =
        GeometryInspectorViewportMath.worldToPixelRect(previousFrameArea, 400, 200, visibleArea);
    Rectangle destinationRect =
        GeometryInspectorViewportMath.worldToPixelRect(currentRenderArea, 400, 200, visibleArea);

    assertThat(sourceRect.x).isEqualTo(100);
    assertThat(sourceRect.y).isEqualTo(0);
    assertThat(sourceRect.width).isEqualTo(200);
    assertThat(sourceRect.height).isEqualTo(200);
    assertThat(destinationRect.x).isEqualTo(0);
    assertThat(destinationRect.y).isEqualTo(0);
    assertThat(destinationRect.width).isEqualTo(400);
    assertThat(destinationRect.height).isEqualTo(200);
  }
}
