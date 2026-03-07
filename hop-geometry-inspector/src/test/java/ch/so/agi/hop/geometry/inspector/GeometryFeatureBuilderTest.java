package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.Test;

class GeometryFeatureBuilderTest {

  @Test
  void buildsFeaturesAndTracksParseStatistics() {
    GeometryFeatureBuilder featureBuilder = new GeometryFeatureBuilder();

    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("geom_wkt"));

    List<Object[]> rows =
        List.of(
            new Object[] {"POINT (1 2)"},
            new Object[] {"this is invalid"},
            new Object[] {null});

    GeometryBuildResult result = featureBuilder.build(rowMeta, rows, "geom_wkt");

    assertThat(result.features()).hasSize(1);
    assertThat(result.parseErrors()).isEqualTo(1);
    assertThat(result.nullGeometries()).isEqualTo(1);
    assertThat(result.parseErrorSamples()).hasSize(1);
    assertThat(result.parseErrorSamples().get(0)).contains("field=geom_wkt");
    assertThat(result.parseErrorSamples().get(0)).contains("valueClass=java.lang.String");
    assertThat(result.parseErrorSamples().get(0)).contains("valueMetaClass=");
    assertThat(result.extent().isEmpty()).isFalse();
    assertThat(result.hasUsableCrs()).isFalse();
    assertThat(result.crsStatusMessage()).isEqualTo("No positive SRID on sampled geometries");
  }

  @Test
  void buildsFeaturesFromForeignGeometryThroughStringBridge() throws Exception {
    GeometryFeatureBuilder featureBuilder = new GeometryFeatureBuilder();

    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ForeignGeometryStringBridgeMeta());

    try (ForeignGeometryHandle foreignGeometry = ForeignGeometryHandle.create("POINT (7 8)", 2056)) {
      GeometryBuildResult result =
          featureBuilder.build(
              rowMeta, List.<Object[]>of(new Object[] {foreignGeometry.geometry()}), "geom");

      assertThat(result.features()).hasSize(1);
      assertThat(result.parseErrors()).isZero();
      assertThat(result.nullGeometries()).isZero();
      assertThat(result.parseErrorSamples()).isEmpty();
      assertThat(result.extent().getMinX()).isEqualTo(7.0);
      assertThat(result.extent().getMaxY()).isEqualTo(8.0);
      assertThat(result.detectedSrid()).isEqualTo(2056);
      assertThat(result.hasUsableCrs()).isTrue();
      assertThat(result.consistentCrs()).isTrue();
      assertThat(result.featureType().getCoordinateReferenceSystem()).isNotNull();
      assertThat(result.extent().getCoordinateReferenceSystem()).isNotNull();
    }
  }

  @Test
  void detectsMixedSridsAsNonUsableCrs() {
    GeometryFeatureBuilder featureBuilder = new GeometryFeatureBuilder();

    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("geom_wkt"));

    List<Object[]> rows =
        List.of(new Object[] {"SRID=2056;POINT (0 0)"}, new Object[] {"SRID=4326;POINT (1 1)"});

    GeometryBuildResult result = featureBuilder.build(rowMeta, rows, "geom_wkt");

    assertThat(result.features()).hasSize(2);
    assertThat(result.hasUsableCrs()).isFalse();
    assertThat(result.consistentCrs()).isFalse();
    assertThat(result.detectedSrid()).isNull();
    assertThat(result.crsStatusMessage()).contains("Mixed SRIDs");
  }

  private static final class ForeignGeometryStringBridgeMeta extends ValueMetaString {
    private ForeignGeometryStringBridgeMeta() {
      super("geom");
    }

    @Override
    public String getString(Object object) {
      try {
        String wkt = object.getClass().getMethod("toText").invoke(object).toString();
        int srid = ((Number) object.getClass().getMethod("getSRID").invoke(object)).intValue();
        return "SRID=" + srid + ";" + wkt;
      } catch (Exception e) {
        throw new IllegalStateException("Unable to convert foreign geometry to EWKT", e);
      }
    }
  }

  private record ForeignGeometryHandle(URLClassLoader classLoader, Object geometry)
      implements AutoCloseable {
    private static ForeignGeometryHandle create(String wkt, int srid) throws Exception {
      URL jtsJar = org.locationtech.jts.geom.Geometry.class.getProtectionDomain().getCodeSource().getLocation();
      URLClassLoader classLoader = new URLClassLoader(new URL[] {jtsJar}, null);
      Class<?> readerClass = Class.forName("org.locationtech.jts.io.WKTReader", true, classLoader);
      Object reader = readerClass.getConstructor().newInstance();
      Object geometry = readerClass.getMethod("read", String.class).invoke(reader, wkt);
      geometry.getClass().getMethod("setSRID", int.class).invoke(geometry, srid);
      return new ForeignGeometryHandle(classLoader, geometry);
    }

    @Override
    public void close() throws Exception {
      classLoader.close();
    }
  }
}
