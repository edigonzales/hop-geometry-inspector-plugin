package ch.so.agi.hop.geometry.inspector.ui;

import java.awt.image.BufferedImage;
import org.geotools.geometry.jts.ReferencedEnvelope;

record GeometryInspectorRasterData(
    ReferencedEnvelope displayArea,
    int logicalWidth,
    int logicalHeight,
    int deviceZoom,
    int pixelWidth,
    int pixelHeight,
    long revision,
    int[] argbPixels) {

  static GeometryInspectorRasterData fromBufferedImage(
      ReferencedEnvelope displayArea,
      int logicalWidth,
      int logicalHeight,
      int deviceZoom,
      long revision,
      BufferedImage image) {
    int width = image.getWidth();
    int height = image.getHeight();
    int[] argbPixels = image.getRGB(0, 0, width, height, null, 0, width);
    return new GeometryInspectorRasterData(
        displayArea == null ? null : new ReferencedEnvelope(displayArea),
        logicalWidth,
        logicalHeight,
        deviceZoom,
        width,
        height,
        revision,
        argbPixels);
  }
}
