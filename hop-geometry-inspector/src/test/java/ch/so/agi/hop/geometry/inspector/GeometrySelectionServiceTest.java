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
  void ranksPointHitsAheadOfPolygonHits() {
    SimpleFeature polygon =
        createFeature(
            "feature-0",
            geometryFactory.createPolygon(
                new Coordinate[] {
                  new Coordinate(-2, -2),
                  new Coordinate(2, -2),
                  new Coordinate(2, 2),
                  new Coordinate(-2, 2),
                  new Coordinate(-2, -2)
                }),
            0);
    SimpleFeature point = createFeature("feature-1", geometryFactory.createPoint(new Coordinate(0, 0)), 1);

    List<GeometrySelectionService.SelectionCandidate> candidates =
        selectionService.rankHits(List.of(polygon, point), new Coordinate(0, 0), new Envelope(-1, 1, -1, 1));

    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).feature().getID()).isEqualTo("feature-1");
    assertThat(candidates.get(1).feature().getID()).isEqualTo("feature-0");
  }

  @Test
  void breaksDistanceTiesByLowestRowIndexWithinSameGeometryPriority() {
    SimpleFeature first = createFeature("feature-10", geometryFactory.createPoint(new Coordinate(1, 0)), 10);
    SimpleFeature second = createFeature("feature-3", geometryFactory.createPoint(new Coordinate(-1, 0)), 3);

    List<GeometrySelectionService.SelectionCandidate> candidates =
        selectionService.rankHits(List.of(first, second), new Coordinate(0, 0), new Envelope(-2, 2, -2, 2));

    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).feature().getID()).isEqualTo("feature-3");
    assertThat(selectionService.selectFeature(List.of(first, second), new Coordinate(0, 0), new Envelope(-2, 2, -2, 2)))
        .get()
        .extracting(SimpleFeature::getID)
        .isEqualTo("feature-3");
  }

  @Test
  void detectsAmbiguousTopHitForOverlappingPolygons() {
    SimpleFeature first =
        createFeature(
            "feature-0",
            geometryFactory.createPolygon(
                new Coordinate[] {
                  new Coordinate(0, 0),
                  new Coordinate(4, 0),
                  new Coordinate(4, 4),
                  new Coordinate(0, 4),
                  new Coordinate(0, 0)
                }),
            0);
    SimpleFeature second =
        createFeature(
            "feature-1",
            geometryFactory.createPolygon(
                new Coordinate[] {
                  new Coordinate(1, 1),
                  new Coordinate(5, 1),
                  new Coordinate(5, 5),
                  new Coordinate(1, 5),
                  new Coordinate(1, 1)
                }),
            1);

    List<GeometrySelectionService.SelectionCandidate> candidates =
        selectionService.rankHits(List.of(second, first), new Coordinate(2, 2), new Envelope(1.5, 2.5, 1.5, 2.5));

    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).distance()).isZero();
    assertThat(candidates.get(1).distance()).isZero();
    assertThat(candidates.get(0).feature().getID()).isEqualTo("feature-0");
    assertThat(selectionService.hasAmbiguousTopHit(candidates)).isTrue();
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

    assertThat(selectionService.rankHits(List.of(polygon), new Coordinate(0, 0), new Envelope(-1, 1, -1, 1)))
        .isEmpty();
    assertThat(selectionService.hasAmbiguousTopHit(List.of())).isFalse();
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
