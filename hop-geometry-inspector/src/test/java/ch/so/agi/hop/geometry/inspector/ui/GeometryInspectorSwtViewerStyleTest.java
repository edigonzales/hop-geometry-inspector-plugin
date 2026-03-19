package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.geotools.styling.SLD;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;

class GeometryInspectorSwtViewerStyleTest {

  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

  @Test
  void defaultPointStyleUsesExpectedSize() {
    var pointSymbolizer = SLD.pointSymbolizer(GeometryInspectorSwtViewer.createPointStyle(false));

    assertThat(SLD.pointOpacity(pointSymbolizer)).isEqualTo(1.0d);
    assertThat(SLD.pointSize(pointSymbolizer)).isEqualTo(11);
  }

  @Test
  void emphasizedPointStyleUsesExpectedSize() {
    var pointSymbolizer = SLD.pointSymbolizer(GeometryInspectorSwtViewer.createPointStyle(true));

    assertThat(SLD.pointOpacity(pointSymbolizer)).isEqualTo(1.0d);
    assertThat(SLD.pointSize(pointSymbolizer)).isEqualTo(22);
  }

  @Test
  void highlightPointStyleUsesValidOpacityAndExpectedSize() {
    var pointSymbolizer =
        SLD.pointSymbolizer(
            GeometryInspectorSwtViewer.createHighlightStyle(GEOMETRY_FACTORY.createPoint()));

    assertThat(SLD.pointOpacity(pointSymbolizer)).isEqualTo(1.0d);
    assertThat(SLD.pointSize(pointSymbolizer)).isEqualTo(24);
  }

  @Test
  void lineStylesUseExpectedWidths() {
    var defaultLineSymbolizer = SLD.lineSymbolizer(GeometryInspectorSwtViewer.createLineStyle(false));
    var emphasizedLineSymbolizer = SLD.lineSymbolizer(GeometryInspectorSwtViewer.createLineStyle(true));
    var highlightLineSymbolizer =
        SLD.lineSymbolizer(
            GeometryInspectorSwtViewer.createHighlightStyle(
                GEOMETRY_FACTORY.createLineString(
                    new org.locationtech.jts.geom.Coordinate[] {
                      new org.locationtech.jts.geom.Coordinate(0, 0),
                      new org.locationtech.jts.geom.Coordinate(1, 1)
                    })));

    assertThat(SLD.stroke(defaultLineSymbolizer).getWidth().evaluate(null, Double.class))
        .isCloseTo(2.2d, within(1.0e-6d));
    assertThat(SLD.stroke(emphasizedLineSymbolizer).getWidth().evaluate(null, Double.class))
        .isCloseTo(4.5d, within(1.0e-6d));
    assertThat(SLD.stroke(highlightLineSymbolizer).getWidth().evaluate(null, Double.class))
        .isCloseTo(5.5d, within(1.0e-6d));
  }
}
