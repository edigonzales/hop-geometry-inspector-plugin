package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.parsing.GeometryParser;
import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.MultiCurve;
import com.atolcd.hop.gis.geometry.curve.MultiSurface;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
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
  void parsesAllSupportedTrueCurveWkbTypes() throws Exception {
    ValueMetaString valueMeta = new ValueMetaString("geom");
    GeometryFactory factory = new GeometryFactory();

    CircularString circularString = openCircularString(factory);
    CompoundCurve compoundCurve = compoundCurve(factory);
    CurvePolygon curvePolygon = curvePolygon(factory);
    MultiCurve multiCurve =
        new MultiCurve(
            List.of(
                factory.createLineString(
                    new Coordinate[] {new Coordinate(-2, 0), new Coordinate(-1, 0)}),
                openCircularString(factory)),
            factory);
    MultiSurface multiSurface =
        new MultiSurface(List.of(square(factory, -3, -3, -2, -2), curvePolygon(factory)), factory);

    for (Geometry source : List.of(circularString, compoundCurve, curvePolygon, multiCurve, multiSurface)) {
      source.setSRID(2056);
      Geometry parsed = parser.parseGeometry(valueMeta, CurveGeometrySupport.writeWkb(source));

      assertThat(parsed).isInstanceOf(source.getClass());
      assertThat(parsed.getSRID()).isEqualTo(2056);
    }

    Geometry fromHex =
        parser.parseGeometry(valueMeta, toHex(CurveGeometrySupport.writeWkb(curvePolygon)));
    assertThat(fromHex).isInstanceOf(CurvePolygon.class);
    CircularString ring =
        (CircularString) ((CurvePolygon) fromHex).getCurveRings().get(0);
    assertThat(ring.getControlPoints()).hasSize(5);
  }

  @Test
  void parsesForeignCurveWithoutStrokingIt() throws Exception {
    CurvePolygon source = curvePolygon(new GeometryFactory());
    source.setSRID(2056);
    byte[] wkb = CurveGeometrySupport.writeWkb(source);

    try (ForeignCurveGeometryHandle foreignGeometry = ForeignCurveGeometryHandle.create(wkb, 2056)) {
      Geometry parsed = parser.parseGeometry(null, foreignGeometry.geometry());

      assertThat(parsed).isInstanceOf(CurvePolygon.class);
      assertThat(parsed.getSRID()).isEqualTo(2056);
      CurvePolygon parsedPolygon = (CurvePolygon) parsed;
      assertThat(parsedPolygon.getCurveRings()).hasSize(1);
      assertThat(parsedPolygon.getCurveRings().get(0)).isInstanceOf(CircularString.class);
      CircularString ring = (CircularString) parsedPolygon.getCurveRings().get(0);
      assertThat(ring.getControlPoints()).hasSize(5);
      assertThat(ring.getControlPoints()[1].getX()).isEqualTo(4.0);
    }
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
  void prefersForeignGeometryBinaryBridgeOverValueMetaStringBridge() throws Exception {
    try (ForeignGeometryHandle foreignGeometry = ForeignGeometryHandle.create("POINT (9 10)", 21781)) {
      Geometry parsed =
          parser.parseGeometry(new BrokenForeignGeometryStringBridgeMeta(), foreignGeometry.geometry());

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

  private static CircularString openCircularString(GeometryFactory factory) {
    return new CircularString(
        new Coordinate[] {
          new Coordinate(0, 0), new Coordinate(1, 1), new Coordinate(2, 0)
        },
        factory);
  }

  private static CompoundCurve compoundCurve(GeometryFactory factory) {
    LineString tail =
        factory.createLineString(new Coordinate[] {new Coordinate(2, 0), new Coordinate(3, 0)});
    return new CompoundCurve(List.of(openCircularString(factory), tail), factory);
  }

  private static CurvePolygon curvePolygon(GeometryFactory factory) {
    CircularString ring =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0),
              new Coordinate(4, 0),
              new Coordinate(4, 4),
              new Coordinate(0, 4),
              new Coordinate(0, 0)
            },
            factory);
    return new CurvePolygon(List.of(ring), factory);
  }

  private static Polygon square(
      GeometryFactory factory, double minX, double minY, double maxX, double maxY) {
    return factory.createPolygon(
        new Coordinate[] {
          new Coordinate(minX, minY),
          new Coordinate(maxX, minY),
          new Coordinate(maxX, maxY),
          new Coordinate(minX, maxY),
          new Coordinate(minX, minY)
        });
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

  private static final class BrokenForeignGeometryStringBridgeMeta extends ValueMetaString {
    private BrokenForeignGeometryStringBridgeMeta() {
      super("geom");
    }

    @Override
    public String getString(Object object) {
      throw new IllegalStateException("String bridge must not be used for a foreign geometry object");
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

  private record ForeignCurveGeometryHandle(URLClassLoader classLoader, Object geometry)
      implements AutoCloseable {
    private static ForeignCurveGeometryHandle create(byte[] wkb, int srid) throws Exception {
      URL geometryTypeLocation =
          CurveGeometrySupport.class.getProtectionDomain().getCodeSource().getLocation();
      URL jtsLocation = Geometry.class.getProtectionDomain().getCodeSource().getLocation();
      URLClassLoader classLoader =
          new URLClassLoader(new URL[] {geometryTypeLocation, jtsLocation}, null);
      Class<?> supportClass =
          Class.forName(
              "com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport", true, classLoader);
      Object geometry = supportClass.getMethod("readWkb", byte[].class).invoke(null, (Object) wkb);
      geometry.getClass().getMethod("setSRID", int.class).invoke(geometry, srid);
      return new ForeignCurveGeometryHandle(classLoader, geometry);
    }

    @Override
    public void close() throws Exception {
      classLoader.close();
    }
  }
}
