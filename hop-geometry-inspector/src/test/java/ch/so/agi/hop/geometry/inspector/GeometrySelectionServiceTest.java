package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

class GeometrySelectionServiceTest {

  private final GeometrySelectionService selectionService = new GeometrySelectionService();
  private final GeometryFactory geometryFactory = new GeometryFactory();

  @Test
  void selectsNearestFeatureInsidePickEnvelope() {
    SimpleFeature nearPoint = createFeature("feature-0", geometryFactory.createPoint(new Coordinate(0, 0)), 0);
    SimpleFeature farPoint = createFeature("feature-1", geometryFactory.createPoint(new Coordinate(4, 4)), 1);

    SimpleFeature selected =
        selectionService
            .selectFeature(
                List.of(farPoint, nearPoint),
                new Coordinate(0.4, 0.3),
                new Envelope(-1, 5, -1, 5))
            .orElseThrow();

    assertThat(selected.getID()).isEqualTo("feature-0");
  }

  @Test
  void breaksDistanceTiesByLowestRowIndex() {
    SimpleFeature first = createFeature("feature-10", geometryFactory.createPoint(new Coordinate(1, 0)), 10);
    SimpleFeature second = createFeature("feature-3", geometryFactory.createPoint(new Coordinate(-1, 0)), 3);

    SimpleFeature selected =
        selectionService
            .selectFeature(
                List.of(first, second),
                new Coordinate(0, 0),
                new Envelope(-2, 2, -2, 2))
            .orElseThrow();

    assertThat(selected.getID()).isEqualTo("feature-3");
  }

  @Test
  void returnsEmptyWhenNothingIntersectsPickEnvelope() {
    SimpleFeature polygon =
        createFeature(
            "feature-0",
            geometryFactory.createPolygon(
                new Coordinate[] {
                  new Coordinate(10, 10),
                  new Coordinate(12, 10),
                  new Coordinate(12, 12),
                  new Coordinate(10, 12),
                  new Coordinate(10, 10)
                }),
            0);

    assertThat(
            selectionService.selectFeature(
                List.of(polygon), new Coordinate(0, 0), new Envelope(-1, 1, -1, 1)))
        .isEmpty();
  }

  private SimpleFeature createFeature(String id, Geometry geometry, int rowIndex) {
    SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
    typeBuilder.setName("test_selection");
    typeBuilder.add("the_geom", Geometry.class);
    typeBuilder.add("row_index", Integer.class);
    SimpleFeatureType featureType = typeBuilder.buildFeatureType();

    SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
    featureBuilder.add(geometry);
    featureBuilder.add(rowIndex);
    return featureBuilder.buildFeature(id);
  }
}
