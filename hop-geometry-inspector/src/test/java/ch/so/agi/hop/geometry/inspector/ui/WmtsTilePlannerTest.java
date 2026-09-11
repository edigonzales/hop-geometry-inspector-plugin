package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.ows.wmts.model.*;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

class WmtsTilePlannerTest {
  TileMatrixSet set(int srid) throws Exception {
    TileMatrixSet set = new TileMatrixSet();
    set.setIdentifier("not-an-epsg-code");
    set.setCoordinateReferenceSystem(CRS.decode("EPSG:" + srid, false));
    return set;
  }

  TileMatrix matrix(TileMatrixSet set, String name, double resolution, int columns, int rows) {
    TileMatrix m = new TileMatrix();
    m.setIdentifier(name);
    m.setParent(set);
    m.setDenominator(resolution / .00028);
    m.setTileWidth(256);
    m.setTileHeight(128);
    m.setMatrixWidth(columns);
    m.setMatrixHeight(rows);
    m.setTopLeft(new GeometryFactory().createPoint(new Coordinate(0, 512)));
    set.addMatrix(m);
    return m;
  }

  TileMatrixSetLink link(TileMatrixSet set) {
    TileMatrixSetLink link = new TileMatrixSetLink();
    link.setIdentifier(set.getIdentifier());
    return link;
  }

  BackgroundRenderRequest request(
      TileMatrixSet set, double x1, double x2, double y1, double y2, int w, int h) {
    return BackgroundRenderRequest.create(
        new ReferencedEnvelope(x1, x2, y1, y2, set.getCoordinateReferenceSystem()),
        w,
        h,
        100,
        96,
        3857,
        1);
  }

  @Test
  void selectsNextFinerResolutionWithNonNumericUnorderedMatrixNames() throws Exception {
    var set = set(3857);
    matrix(set, "fine", .5, 8, 8);
    matrix(set, "coarse", 4, 1, 1);
    matrix(set, "middle", 2, 2, 2);
    var tiles = WmtsTilePlanner.plan(set, link(set), request(set, 0, 256, 256, 512, 100, 100));
    assertThat(tiles).allMatch(t -> t.matrix().equals("middle"));
    assertThat(WmtsTilePlanner.plan(set, link(set), request(set, 0, 256, 256, 512, 1000, 1000)))
        .allMatch(t -> t.matrix().equals("fine"));
  }

  @Test
  void honorsInclusiveMatrixLimitsAndNonSquareTiles() throws Exception {
    var set = set(3857);
    matrix(set, "grid", 1, 4, 4);
    var link = link(set);
    var limit = new TileMatrixLimits();
    limit.setTileMatix("grid");
    limit.setMinRow(1);
    limit.setMaxRow(2);
    limit.setMinCol(1);
    limit.setMaxCol(2);
    link.setLimits(List.of(limit));
    var tiles = WmtsTilePlanner.plan(set, link, request(set, 0, 1024, 0, 512, 1024, 512));
    assertThat(tiles).hasSize(4);
    assertThat(tiles).allMatch(t -> t.row() >= 1 && t.row() <= 2 && t.col() >= 1 && t.col() <= 2);
    assertThat(tiles.get(0).minX()).isEqualTo(256);
    assertThat(tiles.get(0).maxY()).isEqualTo(384);
  }

  @Test
  void outsideCoverageDoesNotRequestAnEdgeTile() throws Exception {
    var set = set(3857);
    matrix(set, "grid", 1, 2, 4);
    assertThat(WmtsTilePlanner.plan(set, link(set), request(set, 512, 768, 0, 256, 256, 256)))
        .isEmpty();
  }

  @Test
  void geographicNorthEastOriginIsConvertedToEastNorth() throws Exception {
    var set = set(4326);
    var m = matrix(set, "geographic", 1, 2, 2);
    m.setDenominator(111319.49079327358 / .00028);
    m.setTopLeft(new GeometryFactory().createPoint(new Coordinate(90, -180)));
    var tiles = WmtsTilePlanner.plan(set, link(set), request(set, -180, -80, -10, 90, 100, 100));
    assertThat(tiles).hasSize(1);
    assertThat(tiles.get(0).minX()).isEqualTo(-180);
    assertThat(tiles.get(0).maxY()).isEqualTo(90);
    assertThat(tiles.get(0).resolution()).isCloseTo(1, within(1e-9));
  }
}
