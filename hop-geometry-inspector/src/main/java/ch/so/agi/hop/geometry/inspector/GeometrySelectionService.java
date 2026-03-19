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
import org.locationtech.jts.geom.Lineal;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.Puntal;

public class GeometrySelectionService {

  private final GeometryFactory geometryFactory = new GeometryFactory();

  public List<SelectionCandidate> rankHits(
      List<SimpleFeature> features, Coordinate worldCoordinate, Envelope pickEnvelope) {
    if (features == null
        || features.isEmpty()
        || worldCoordinate == null
        || pickEnvelope == null
        || pickEnvelope.isNull()) {
      return List.of();
    }

    Point clickPoint = geometryFactory.createPoint(worldCoordinate);
    Geometry pickGeometry = geometryFactory.toGeometry(pickEnvelope);

    return features.stream()
        .filter(Objects::nonNull)
        .filter(feature -> feature.getDefaultGeometry() instanceof Geometry)
        .map(feature -> toCandidate(feature, clickPoint))
        .filter(Objects::nonNull)
        .filter(candidate -> candidate.geometry().intersects(pickGeometry))
        .sorted(selectionComparator())
        .toList();
  }

  public Optional<SimpleFeature> selectFeature(
      List<SimpleFeature> features, Coordinate worldCoordinate, Envelope pickEnvelope) {
    return rankHits(features, worldCoordinate, pickEnvelope).stream()
        .map(SelectionCandidate::feature)
        .findFirst();
  }

  public boolean hasAmbiguousTopHit(List<SelectionCandidate> candidates) {
    if (candidates == null || candidates.size() < 2) {
      return false;
    }
    SelectionCandidate first = candidates.get(0);
    SelectionCandidate second = candidates.get(1);
    return first.renderPriority() == second.renderPriority()
        && Double.compare(first.distance(), second.distance()) == 0;
  }

  private SelectionCandidate toCandidate(SimpleFeature feature, Point clickPoint) {
    if (!(feature.getDefaultGeometry() instanceof Geometry geometry)) {
      return null;
    }
    return new SelectionCandidate(
        feature,
        geometry,
        renderPriority(geometry),
        geometry.distance(clickPoint),
        rowIndexOf(feature));
  }

  private Comparator<SelectionCandidate> selectionComparator() {
    return Comparator.comparingInt(SelectionCandidate::renderPriority)
        .reversed()
        .thenComparingDouble(SelectionCandidate::distance)
        .thenComparingInt(SelectionCandidate::rowIndex);
  }

  private int renderPriority(Geometry geometry) {
    if (geometry instanceof Puntal) {
      return 3;
    }
    if (geometry instanceof Lineal) {
      return 2;
    }
    if (geometry instanceof Polygonal) {
      return 1;
    }
    return 0;
  }

  private int rowIndexOf(SimpleFeature feature) {
    Object value = feature.getAttribute("row_index");
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.MAX_VALUE;
  }

  public record SelectionCandidate(
      SimpleFeature feature, Geometry geometry, int renderPriority, double distance, int rowIndex) {}
}
