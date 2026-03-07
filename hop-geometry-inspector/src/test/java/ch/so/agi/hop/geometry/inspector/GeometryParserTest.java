package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.parsing.GeometryParser;
import java.net.URL;
import java.net.URLClassLoader;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

class GeometryParserTest {

  private final GeometryParser parser = new GeometryParser();

  @Test
  void parsesWktAndEwktAndWkb() throws Exception {
    ValueMetaString valueMeta = new ValueMetaString("geom");

    Geometry wkt = parser.parseGeometry(valueMeta, "POINT (1 2)");
    assertThat(wkt).isNotNull();
    assertThat(wkt.getGeometryType()).isEqualTo("Point");

    Geometry ewkt = parser.parseGeometry(valueMeta, "SRID=2056;POINT (7 8)");
    assertThat(ewkt).isNotNull();
    assertThat(ewkt.getSRID()).isEqualTo(2056);

    Geometry source = new WKTReader().read("LINESTRING (0 0, 1 1)");
    byte[] wkbBytes = new WKBWriter().write(source);
    Geometry fromBytes = parser.parseGeometry(valueMeta, wkbBytes);
    assertThat(fromBytes).isNotNull();
    assertThat(fromBytes.getGeometryType()).isEqualTo("LineString");

    String hex = toHex(wkbBytes);
    Geometry fromHex = parser.parseGeometry(valueMeta, hex);
    assertThat(fromHex).isNotNull();
    assertThat(fromHex.getGeometryType()).isEqualTo("LineString");
  }

  @Test
  void parsesForeignGeometryViaReflectiveGeometryMethod() throws Exception {
    try (ForeignGeometryHandle foreignGeometry = ForeignGeometryHandle.create("POINT (3 4)", 2056)) {
      Geometry parsed =
          parser.parseGeometry(new ForeignGeometryReflectiveMeta(), foreignGeometry.geometry());

      assertThat(parsed).isNotNull();
      assertThat(parsed.getGeometryType()).isEqualTo("Point");
      assertThat(parsed.getSRID()).isEqualTo(2056);
      assertThat(parsed.getCoordinate().getX()).isEqualTo(3.0);
    }
  }

  @Test
  void parsesForeignGeometryViaValueMetaStringBridge() throws Exception {
    try (ForeignGeometryHandle foreignGeometry = ForeignGeometryHandle.create("POINT (9 10)", 21781)) {
      Geometry parsed =
          parser.parseGeometry(new ForeignGeometryStringBridgeMeta(), foreignGeometry.geometry());

      assertThat(parsed).isNotNull();
      assertThat(parsed.getGeometryType()).isEqualTo("Point");
      assertThat(parsed.getSRID()).isEqualTo(21781);
      assertThat(parsed.getCoordinate().getY()).isEqualTo(10.0);
    }
  }

  @Test
  void parsesForeignGeometryWithoutValueMetaBridge() throws Exception {
    try (ForeignGeometryHandle foreignGeometry = ForeignGeometryHandle.create("POINT (11 12)", 0)) {
      Geometry parsed = parser.parseGeometry(null, foreignGeometry.geometry());

      assertThat(parsed).isNotNull();
      assertThat(parsed.getGeometryType()).isEqualTo("Point");
      assertThat(parsed.getCoordinate().getX()).isEqualTo(11.0);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      builder.append(String.format("%02x", value));
    }
    return builder.toString();
  }

  private static final class ForeignGeometryReflectiveMeta extends ValueMetaString {
    private ForeignGeometryReflectiveMeta() {
      super("geom");
    }

    public Object getGeometry(Object object) {
      return object;
    }
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
        return srid > 0 ? "SRID=" + srid + ";" + wkt : wkt;
      } catch (Exception e) {
        throw new IllegalStateException("Unable to convert foreign geometry to EWKT", e);
      }
    }
  }

  private record ForeignGeometryHandle(URLClassLoader classLoader, Object geometry)
      implements AutoCloseable {
    private static ForeignGeometryHandle create(String wkt, int srid) throws Exception {
      URL jtsJar = Geometry.class.getProtectionDomain().getCodeSource().getLocation();
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
