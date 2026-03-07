package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.geotools.styling.SLD;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;

class GeometryInspectorSwtViewerStyleTest {

  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

  @Test
  void pointStyleUsesValidOpacityAndExpectedSize() {
    var pointSymbolizer = SLD.pointSymbolizer(GeometryInspectorSwtViewer.createPointStyle());

    assertThat(SLD.pointOpacity(pointSymbolizer)).isEqualTo(1.0d);
    assertThat(SLD.pointSize(pointSymbolizer)).isEqualTo(11);
  }

  @Test
  void highlightPointStyleUsesValidOpacityAndExpectedSize() {
    var pointSymbolizer =
        SLD.pointSymbolizer(
            GeometryInspectorSwtViewer.createHighlightStyle(GEOMETRY_FACTORY.createPoint()));

    assertThat(SLD.pointOpacity(pointSymbolizer)).isEqualTo(1.0d);
    assertThat(SLD.pointSize(pointSymbolizer)).isEqualTo(15);
  }
}
