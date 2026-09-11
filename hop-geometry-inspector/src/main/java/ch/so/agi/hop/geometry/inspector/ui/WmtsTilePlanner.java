package ch.so.agi.hop.geometry.inspector.ui;

import java.util.*;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.ows.wmts.model.*;
import org.geotools.referencing.CRS;

/** Converts WMTS matrix metadata to east/north tiles; never changes the map CRS. */
final class WmtsTilePlanner {
  record Tile(
      String matrix,
      int row,
      int col,
      double minX,
      double maxY,
      double resolution,
      int width,
      int height) {}

  static double resolution(TileMatrix matrix) throws Exception {
    var crs = matrix.getCrs();
    double metersPerUnit;
    if (crs instanceof GeographicCRS geographic) {
      metersPerUnit =
          geographic.getDatum().getEllipsoid().getSemiMajorAxis()
              * geographic
                  .getDatum()
                  .getEllipsoid()
                  .getAxisUnit()
                  .getConverterToAny(org.geotools.measure.Units.METRE)
                  .convert(1)
              * crs.getCoordinateSystem()
                  .getAxis(0)
                  .getUnit()
                  .getConverterToAny(org.geotools.measure.Units.RADIAN)
                  .convert(1);
    } else {
      metersPerUnit =
          crs.getCoordinateSystem()
              .getAxis(0)
              .getUnit()
              .getConverterToAny(org.geotools.measure.Units.METRE)
              .convert(1);
    }
    return matrix.getDenominator() * 0.00028 / metersPerUnit;
  }

  static List<Tile> plan(TileMatrixSet set, TileMatrixSetLink link, BackgroundRenderRequest request)
      throws Exception {
    double desired =
        Math.min(
            request.displayArea().getWidth() / request.pixelWidth(),
            request.displayArea().getHeight() / request.pixelHeight());
    List<TileMatrix> matrices = new ArrayList<>();
    for (var m : set.getMatrices()) {
      if (link.getLimits() != null
          && !link.getLimits().isEmpty()
          && link.getLimits().stream().noneMatch(l -> l.getTileMatix().equals(m.getIdentifier())))
        continue;
      double r = resolution(m);
      if (!Double.isFinite(r)
          || r <= 0
          || m.getTileWidth() <= 0
          || m.getTileHeight() <= 0
          || m.getMatrixWidth() <= 0
          || m.getMatrixHeight() <= 0
          || m.getTopLeft() == null)
        throw new IllegalArgumentException("Invalid WMTS tile matrix: " + m.getIdentifier());
      matrices.add(m);
    }
    if (matrices.isEmpty())
      throw new IllegalArgumentException("WMTS TileMatrixSet has no available matrices");
    TileMatrix selected = null;
    double chosen = -1;
    for (var m : matrices) {
      double r = resolution(m);
      if (r <= desired * (1 + 1e-10) && r > chosen) {
        selected = m;
        chosen = r;
      }
    }
    if (selected == null)
      for (var m : matrices) {
        double r = resolution(m);
        if (selected == null || r < chosen) {
          selected = m;
          chosen = r;
        }
      }
    var m = selected;
    var axisOrder = CRS.getAxisOrder(set.getCoordinateReferenceSystem());
    if (axisOrder != CRS.AxisOrder.NORTH_EAST && axisOrder != CRS.AxisOrder.EAST_NORTH)
      throw new IllegalArgumentException("Unsupported WMTS coordinate axis order: " + axisOrder);
    boolean northFirst = axisOrder == CRS.AxisOrder.NORTH_EAST;
    double originX = northFirst ? m.getTopLeft().getY() : m.getTopLeft().getX();
    double originY = northFirst ? m.getTopLeft().getX() : m.getTopLeft().getY();
    double tileW = chosen * m.getTileWidth(), tileH = chosen * m.getTileHeight();
    long minCol = 0,
        minRow = 0,
        maxCol = m.getMatrixWidth() - 1L,
        maxRow = m.getMatrixHeight() - 1L;
    if (link.getLimits() != null)
      for (var limit : link.getLimits()) {
        if (!limit.getTileMatix().equals(m.getIdentifier())) continue;
        minCol = Math.max(minCol, limit.getMincol());
        maxCol = Math.min(maxCol, limit.getMaxcol());
        minRow = Math.max(minRow, limit.getMinrow());
        maxRow = Math.min(maxRow, limit.getMaxrow());
      }
    var area = request.displayArea();
    minCol = Math.max(minCol, (long) Math.floor((area.getMinX() - originX) / tileW));
    maxCol = Math.min(maxCol, (long) Math.ceil((area.getMaxX() - originX) / tileW) - 1);
    minRow = Math.max(minRow, (long) Math.floor((originY - area.getMaxY()) / tileH));
    maxRow = Math.min(maxRow, (long) Math.ceil((originY - area.getMinY()) / tileH) - 1);
    List<Tile> result = new ArrayList<>();
    if (minCol > maxCol || minRow > maxRow) return result;
    if ((maxCol - minCol + 1) * (double) (maxRow - minRow + 1) > 4096)
      throw new IllegalArgumentException(
          "WMTS viewport requires too many tiles; reduce the window size");
    for (long row = minRow; row <= maxRow; row++)
      for (long col = minCol; col <= maxCol; col++) {
        result.add(
            new Tile(
                m.getIdentifier(),
                (int) row,
                (int) col,
                originX + col * tileW,
                originY - row * tileH,
                chosen,
                m.getTileWidth(),
                m.getTileHeight()));
      }
    return result;
  }
}
