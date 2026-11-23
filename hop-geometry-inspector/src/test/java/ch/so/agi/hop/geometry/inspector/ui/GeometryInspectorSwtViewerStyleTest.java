package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.geotools.styling.SLD;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;

class GeometryInspectorSwtViewerStyleTest {

  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

  @Test
  void highlightPointStyleUsesValidOpacityAndExpectedSize() {
    var pointSymbolizer =
        SLD.pointSymbolizer(
            GeometryInspectorSwtViewer.createHighlightStyle(GEOMETRY_FACTORY.createPoint()));

    assertThat(SLD.pointOpacity(pointSymbolizer)).isEqualTo(1.0d);
    assertThat(SLD.pointSize(pointSymbolizer)).isEqualTo(24);
  }

  @Test
  void highlightLineStyleUsesExpectedWidth() {
    var highlightLineSymbolizer =
        SLD.lineSymbolizer(
            GeometryInspectorSwtViewer.createHighlightStyle(
                GEOMETRY_FACTORY.createLineString(
                    new org.locationtech.jts.geom.Coordinate[] {
                      new org.locationtech.jts.geom.Coordinate(0, 0),
                      new org.locationtech.jts.geom.Coordinate(1, 1)
                    })));

    assertThat(SLD.stroke(highlightLineSymbolizer).getWidth().evaluate(null, Double.class))
        .isCloseTo(5.5d, within(1.0e-6d));
  }
}
