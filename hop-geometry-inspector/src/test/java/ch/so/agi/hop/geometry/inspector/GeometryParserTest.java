package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.parsing.GeometryParser;
import com.atolcd.hop.core.row.value.GeometryInterface;
import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.MultiCurve;
import com.atolcd.hop.gis.geometry.curve.MultiSurface;
import java.sql.ResultSetMetaData;
import java.util.List;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.exception.HopDatabaseException;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.apache.hop.core.variables.IVariables;
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

    for (Geometry source :
        List.of(circularString, compoundCurve, curvePolygon, multiCurve, multiSurface)) {
      source.setSRID(2056);
      Geometry parsed = parser.parseGeometry(valueMeta, CurveGeometrySupport.writeWkb(source));

      assertThat(parsed).isInstanceOf(source.getClass());
      assertThat(parsed.getSRID()).isEqualTo(2056);
    }

    Geometry fromHex =
        parser.parseGeometry(valueMeta, toHex(CurveGeometrySupport.writeWkb(curvePolygon)));
    assertThat(fromHex).isInstanceOf(CurvePolygon.class);
    CircularString ring = (CircularString) ((CurvePolygon) fromHex).getCurveRings().get(0);
    assertThat(ring.getControlPoints()).hasSize(5);
  }

  @Test
  void keepsSharedGeometryObjectWithoutRoundTrip() throws Exception {
    Geometry source = new GeometryFactory().createPoint(new Coordinate(3, 4));
    source.setSRID(2056);

    Geometry parsed = parser.parseGeometry(null, source);

    assertThat(parsed).isSameAs(source);
    assertThat(parsed.getSRID()).isEqualTo(2056);
  }

  @Test
  void keepsSharedTrueCurveObjectWithoutRoundTrip() throws Exception {
    CurvePolygon source = curvePolygon(new GeometryFactory());
    source.setSRID(2056);

    Geometry parsed = parser.parseGeometry(null, source);

    assertThat(parsed).isSameAs(source);
    assertThat(parsed).isInstanceOf(CurvePolygon.class);
    CircularString ring = (CircularString) ((CurvePolygon) parsed).getCurveRings().get(0);
    assertThat(ring.getControlPoints()).hasSize(5);
    assertThat(ring.getControlPoints()[1].getX()).isEqualTo(4.0);
  }

  @Test
  void parsesViaSharedGeometryInterfaceWithoutReflection() throws Exception {
    Geometry source = new GeometryFactory().createPoint(new Coordinate(9, 10));
    source.setSRID(21781);
    GeometryInterfaceMeta valueMeta = new GeometryInterfaceMeta(source);

    Geometry parsed = parser.parseGeometry(valueMeta, new Object());

    assertThat(parsed).isSameAs(source);
    assertThat(parsed.getSRID()).isEqualTo(21781);
  }

  private static CircularString openCircularString(GeometryFactory factory) {
    return new CircularString(
        new Coordinate[] {new Coordinate(0, 0), new Coordinate(1, 1), new Coordinate(2, 0)},
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

  private static final class GeometryInterfaceMeta extends ValueMetaString
      implements GeometryInterface {
    private final Geometry geometry;

    private GeometryInterfaceMeta(Geometry geometry) {
      super("geom");
      this.geometry = geometry;
    }

    @Override
    public Geometry getGeometry(Object object) {
      return geometry;
    }

    @Override
    public IValueMeta getValueFromSqlType(
        IVariables variables,
        DatabaseMeta databaseMeta,
        String name,
        ResultSetMetaData resultSetMetaData,
        int index,
        boolean ignoreLength,
        boolean lazyConversion)
        throws HopDatabaseException {
      return null;
    }
  }
}
