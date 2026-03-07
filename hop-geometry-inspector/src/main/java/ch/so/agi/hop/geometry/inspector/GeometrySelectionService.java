package ch.so.agi.hop.geometry.inspector;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

public class GeometrySelectionService {

  private final GeometryFactory geometryFactory = new GeometryFactory();

  public Optional<SimpleFeature> selectFeature(
      List<SimpleFeature> features, Coordinate worldCoordinate, Envelope pickEnvelope) {
    if (features == null
        || features.isEmpty()
        || worldCoordinate == null
        || pickEnvelope == null
        || pickEnvelope.isNull()) {
      return Optional.empty();
    }

    Point clickPoint = geometryFactory.createPoint(worldCoordinate);
    Geometry pickGeometry = geometryFactory.toGeometry(pickEnvelope);

    return features.stream()
        .filter(Objects::nonNull)
        .filter(feature -> feature.getDefaultGeometry() instanceof Geometry)
        .filter(feature -> ((Geometry) feature.getDefaultGeometry()).intersects(pickGeometry))
        .min(
            Comparator.comparingDouble(
                    (SimpleFeature feature) ->
                        ((Geometry) feature.getDefaultGeometry()).distance(clickPoint))
                .thenComparingInt(this::rowIndexOf));
  }

  private int rowIndexOf(SimpleFeature feature) {
    Object value = feature.getAttribute("row_index");
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.MAX_VALUE;
  }
}
