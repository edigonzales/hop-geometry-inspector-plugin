package ch.so.agi.hop.geometry.inspector.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.geometry.jts.ReferencedEnvelope;

public record GeometryBuildResult(
    List<SimpleFeature> features,
    SimpleFeatureType featureType,
    ReferencedEnvelope extent,
    int parseErrors,
    int nullGeometries,
    List<String> parseErrorSamples,
    Integer detectedSrid,
    CoordinateReferenceSystem detectedCrs,
    boolean consistentCrs,
    String crsStatusMessage) {

  public GeometryBuildResult {
    features = features == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(features));
    extent = extent == null ? new ReferencedEnvelope() : new ReferencedEnvelope(extent);
    parseErrorSamples =
        parseErrorSamples == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(parseErrorSamples));
    crsStatusMessage = crsStatusMessage == null ? "" : crsStatusMessage;
  }

  public boolean hasRenderableFeatures() {
    return !features.isEmpty();
  }

  public boolean hasUsableCrs() {
    return consistentCrs && detectedSrid != null && detectedCrs != null;
  }
}
