package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;

class GeometryInspectorViewportMathTest {

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
  void fitToCanvasAspectExpandsWidthForWiderCanvas() {
    ReferencedEnvelope envelope = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 100.0d, null);

    ReferencedEnvelope fitted = GeometryInspectorViewportMath.fitToCanvasAspect(envelope, 600, 300);

    assertThat(fitted.getWidth()).isEqualTo(200.0d);
    assertThat(fitted.getHeight()).isEqualTo(100.0d);
    assertThat(fitted.getCenterX()).isEqualTo(50.0d);
    assertThat(fitted.getCenterY()).isEqualTo(50.0d);
  }

  @Test
  void fitToCanvasAspectExpandsHeightForTallerCanvas() {
    ReferencedEnvelope envelope = new ReferencedEnvelope(0.0d, 200.0d, 0.0d, 100.0d, null);

    ReferencedEnvelope fitted = GeometryInspectorViewportMath.fitToCanvasAspect(envelope, 300, 300);

    assertThat(fitted.getWidth()).isEqualTo(200.0d);
    assertThat(fitted.getHeight()).isEqualTo(200.0d);
    assertThat(fitted.getCenterX()).isEqualTo(100.0d);
    assertThat(fitted.getCenterY()).isEqualTo(50.0d);
  }

  @Test
  void zoomAtKeepsAnchorStableAndAspectFit() {
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    ReferencedEnvelope zoomed =
        GeometryInspectorViewportMath.zoomAt(displayArea, 400, 200, 200, 100, 0.5d);
    Coordinate anchorBefore =
        GeometryInspectorViewportMath.screenToWorld(displayArea, 400, 200, 200, 100);
    Coordinate anchorAfter = GeometryInspectorViewportMath.screenToWorld(zoomed, 400, 200, 200, 100);

    assertThat(anchorAfter.x).isCloseTo(anchorBefore.x, within(1.0e-9d));
    assertThat(anchorAfter.y).isCloseTo(anchorBefore.y, within(1.0e-9d));
    assertThat(zoomed.getWidth() / zoomed.getHeight()).isCloseTo(2.0d, within(1.0e-9d));
  }

  @Test
  void panKeepsDisplayAreaAspectRatio() {
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    ReferencedEnvelope panned = GeometryInspectorViewportMath.pan(displayArea, 400, 200, 40, -20);

    assertThat(panned.getMinX()).isEqualTo(-10.0d);
    assertThat(panned.getMaxX()).isEqualTo(90.0d);
    assertThat(panned.getMinY()).isEqualTo(-5.0d);
    assertThat(panned.getMaxY()).isEqualTo(45.0d);
    assertThat(panned.getWidth() / panned.getHeight()).isCloseTo(2.0d, within(1.0e-9d));
  }

  @Test
  void pickEnvelopeExpandsAroundPointerPosition() {
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    Envelope pickEnvelope =
        GeometryInspectorViewportMath.pickEnvelope(displayArea, 400, 200, 200, 100, 6.0d);

    assertThat(pickEnvelope.getWidth()).isCloseTo(3.0d, within(1.0e-9d));
    assertThat(pickEnvelope.getHeight()).isCloseTo(3.0d, within(1.0e-9d));
    assertThat(pickEnvelope.centre().x).isCloseTo(50.0d, within(1.0e-9d));
    assertThat(pickEnvelope.centre().y).isCloseTo(25.0d, within(1.0e-9d));
  }
}
