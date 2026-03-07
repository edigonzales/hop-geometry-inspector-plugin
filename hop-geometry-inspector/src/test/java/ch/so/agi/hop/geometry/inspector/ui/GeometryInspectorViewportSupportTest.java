package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.swing.JMapPane;
import org.junit.jupiter.api.Test;

class GeometryInspectorViewportSupportTest {

  @Test
  void normalizesMissingWidthFromExistingHeight() {
    ReferencedEnvelope envelope = new ReferencedEnvelope(10.0d, 10.0d, 100.0d, 150.0d, null);

    ReferencedEnvelope normalized = GeometryInspectorViewportSupport.normalizeEnvelope(envelope);

    assertThat(normalized.getWidth()).isEqualTo(1.0d);
    assertThat(normalized.getHeight()).isEqualTo(50.0d);
    assertThat(normalized.getCenterX()).isEqualTo(10.0d);
    assertThat(normalized.getCenterY()).isEqualTo(125.0d);
  }

  @Test
  void normalizesMissingHeightFromExistingWidth() {
    ReferencedEnvelope envelope = new ReferencedEnvelope(20.0d, 40.0d, 7.0d, 7.0d, null);

    ReferencedEnvelope normalized = GeometryInspectorViewportSupport.normalizeEnvelope(envelope);

    assertThat(normalized.getWidth()).isEqualTo(20.0d);
    assertThat(normalized.getHeight()).isCloseTo(0.4d, within(1.0e-12d));
    assertThat(normalized.getCenterY()).isEqualTo(7.0d);
  }

  @Test
  void normalizesPointEnvelopeWithGeographicCrsUsingSmallDefaultSpan() {
    ReferencedEnvelope envelope =
        new ReferencedEnvelope(7.0d, 7.0d, 47.0d, 47.0d, DefaultGeographicCRS.WGS84);

    ReferencedEnvelope normalized = GeometryInspectorViewportSupport.normalizeEnvelope(envelope);

    assertThat(normalized.getWidth()).isCloseTo(0.0001d, within(1.0e-12d));
    assertThat(normalized.getHeight()).isCloseTo(0.0001d, within(1.0e-12d));
  }

  @Test
  void safeSetDisplayAreaSuppressesNonInvertibleTransformFailures() {
    TestMapPane mapPane = new TestMapPane();
    mapPane.throwNoninvertible = true;

    boolean applied =
        GeometryInspectorViewportSupport.safeSetDisplayArea(
            mapPane, new ReferencedEnvelope(0.0d, 1.0d, 0.0d, 1.0d, null));

    assertThat(applied).isFalse();
    assertThat(mapPane.lastDisplayArea).isNull();
  }

  @Test
  void safeSetDisplayAreaAppliesNormalizedEnvelopeWhenViewportIsUsable() {
    TestMapPane mapPane = new TestMapPane();

    boolean applied =
        GeometryInspectorViewportSupport.safeSetDisplayArea(
            mapPane,
            new ReferencedEnvelope(10.0d, 10.0d, 100.0d, 150.0d, null));

    assertThat(applied).isTrue();
    assertThat(mapPane.lastDisplayArea).isNotNull();
    assertThat(mapPane.lastDisplayArea.getWidth()).isEqualTo(1.0d);
    assertThat(mapPane.lastDisplayArea.getHeight()).isEqualTo(50.0d);
  }

  private static final class TestMapPane extends JMapPane {
    private boolean throwNoninvertible;
    private ReferencedEnvelope lastDisplayArea;

    @Override
    public Rectangle getVisibleRect() {
      return new Rectangle(0, 0, 640, 480);
    }

    @Override
    public int getWidth() {
      return 640;
    }

    @Override
    public int getHeight() {
      return 480;
    }

    @Override
    public AffineTransform getScreenToWorldTransform() {
      return new AffineTransform();
    }

    @Override
    public void setDisplayArea(org.geotools.api.geometry.Bounds bounds) {
      if (throwNoninvertible) {
        throw new RuntimeException(
            "Unable to create coordinate transforms.",
            new NoninvertibleTransformException("Determinant is 0"));
      }
      lastDisplayArea = new ReferencedEnvelope(bounds);
    }
  }
}
