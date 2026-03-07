package ch.so.agi.hop.geometry.inspector;

import ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult;
import ch.so.agi.hop.geometry.inspector.parsing.GeometryParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

public class GeometryFeatureBuilder {

  private static final int MAX_PARSE_ERROR_SAMPLES = 5;

  private final GeometryParser geometryParser = new GeometryParser();

  public GeometryBuildResult build(IRowMeta rowMeta, List<Object[]> rows, String geometryField) {
    if (rowMeta == null || rows == null || rows.isEmpty()) {
      return new GeometryBuildResult(
          List.of(),
          null,
          new ReferencedEnvelope(),
          0,
          0,
          List.of(),
          null,
          null,
          false,
          "No sampled rows");
    }

    int geometryIndex = rowMeta.indexOfValue(geometryField);
    if (geometryIndex < 0) {
      throw new IllegalArgumentException("Geometry field not found in row meta: " + geometryField);
    }

    IValueMeta valueMeta = rowMeta.getValueMeta(geometryIndex);
    List<RowGeometry> renderableRows = new ArrayList<>();
    int parseErrors = 0;
    int nullGeometries = 0;
    List<String> parseErrorSamples = new ArrayList<>();
    Set<Integer> positiveSrids = new LinkedHashSet<>();

    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      Object[] row = rows.get(rowIndex);
      Object value = row.length > geometryIndex ? row[geometryIndex] : null;

      try {
        Geometry geometry = geometryParser.parseGeometry(valueMeta, value);
        if (geometry == null) {
          nullGeometries++;
          continue;
        }

        renderableRows.add(new RowGeometry(rowIndex, geometry));
        if (geometry.getSRID() > 0) {
          positiveSrids.add(geometry.getSRID());
        }
      } catch (Exception e) {
        parseErrors++;
        if (parseErrorSamples.size() < MAX_PARSE_ERROR_SAMPLES) {
          parseErrorSamples.add(
              buildParseErrorSample(rowIndex, geometryField, valueMeta, value, e));
        }
      }
    }

    CoordinateReferenceSystem detectedCrs = null;
    Integer detectedSrid = null;
    boolean consistentCrs = false;
    String crsStatusMessage = "No renderable geometries";

    if (!renderableRows.isEmpty()) {
      if (positiveSrids.isEmpty()) {
        crsStatusMessage = "No positive SRID on sampled geometries";
      } else if (positiveSrids.size() > 1) {
        crsStatusMessage =
            "Mixed SRIDs in sample: "
                + positiveSrids.stream().map(String::valueOf).reduce((left, right) -> left + ", " + right).orElse("");
      } else {
        detectedSrid = positiveSrids.iterator().next();
        try {
          detectedCrs = CRS.decode("EPSG:" + detectedSrid, true);
          consistentCrs = true;
          crsStatusMessage = "EPSG:" + detectedSrid;
        } catch (Exception e) {
          crsStatusMessage = "Unable to decode EPSG:" + detectedSrid;
        }
      }
    }

    SimpleFeatureType featureType = createFeatureType(detectedCrs);
    SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
    ReferencedEnvelope envelope =
        detectedCrs == null ? new ReferencedEnvelope() : new ReferencedEnvelope(detectedCrs);
    List<SimpleFeature> features = new ArrayList<>();

    for (RowGeometry rowGeometry : renderableRows) {
      featureBuilder.reset();
      featureBuilder.add(rowGeometry.geometry());
      featureBuilder.add(rowGeometry.rowIndex());
      SimpleFeature feature = featureBuilder.buildFeature("feature-" + rowGeometry.rowIndex());
      features.add(feature);

      Envelope geometryEnvelope = rowGeometry.geometry().getEnvelopeInternal();
      if (geometryEnvelope != null) {
        envelope.expandToInclude(geometryEnvelope);
      }
    }

    return new GeometryBuildResult(
        features,
        featureType,
        envelope,
        parseErrors,
        nullGeometries,
        parseErrorSamples,
        detectedSrid,
        detectedCrs,
        consistentCrs,
        crsStatusMessage);
  }

  private String buildParseErrorSample(
      int rowIndex, String geometryField, IValueMeta valueMeta, Object value, Exception error) {
    String message = rootCauseMessage(error);
    String valueClass = value == null ? "null" : value.getClass().getName();
    String valueMetaClass = valueMeta == null ? "null" : valueMeta.getClass().getName();
    String valueMetaType = valueMeta == null ? "null" : valueMeta.getTypeDesc();
    String sample =
        "row="
            + rowIndex
            + ", field="
            + geometryField
            + ", valueClass="
            + valueClass
            + ", valueMetaClass="
            + valueMetaClass
            + ", valueMetaType="
            + valueMetaType
            + ", message="
            + message;
    return abbreviate(sample, 500);
  }

  private String rootCauseMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    if (current.getMessage() == null || current.getMessage().isBlank()) {
      return current.getClass().getName();
    }
    return current.getClass().getSimpleName() + ": " + current.getMessage();
  }

  private String abbreviate(String value, int maxLength) {
    if (value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, Math.max(0, maxLength - 3)) + "...";
  }

  private SimpleFeatureType createFeatureType(CoordinateReferenceSystem coordinateReferenceSystem) {
    SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
    typeBuilder.setName("inspector_geometry_sample");
    if (coordinateReferenceSystem != null) {
      typeBuilder.setCRS(coordinateReferenceSystem);
    }
    typeBuilder.add("the_geom", Geometry.class);
    typeBuilder.add("row_index", Integer.class);
    return typeBuilder.buildFeatureType();
  }

  private record RowGeometry(int rowIndex, Geometry geometry) {}
}
