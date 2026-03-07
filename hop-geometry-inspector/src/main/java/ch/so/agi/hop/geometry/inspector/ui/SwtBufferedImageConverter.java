package ch.so.agi.hop.geometry.inspector.ui;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.widgets.Display;

final class SwtBufferedImageConverter {

  private SwtBufferedImageConverter() {}

  static Image toImage(Display display, GeometryInspectorRasterData rasterData) {
    PaletteData palette = new PaletteData(0x00FF0000, 0x0000FF00, 0x000000FF);
    ImageData imageData =
        new ImageData(rasterData.pixelWidth(), rasterData.pixelHeight(), 24, palette);
    imageData.alphaData = new byte[rasterData.pixelWidth() * rasterData.pixelHeight()];

    int[] rowPixels = new int[rasterData.pixelWidth()];
    byte[] rowAlpha = new byte[rasterData.pixelWidth()];

    for (int y = 0; y < rasterData.pixelHeight(); y++) {
      int rowStart = y * rasterData.pixelWidth();
      for (int x = 0; x < rasterData.pixelWidth(); x++) {
        int argb = rasterData.argbPixels()[rowStart + x];
        rowPixels[x] = argb & 0x00FFFFFF;
        rowAlpha[x] = (byte) ((argb >>> 24) & 0xFF);
      }
      imageData.setPixels(0, y, rasterData.pixelWidth(), rowPixels, 0);
      imageData.setAlphas(0, y, rasterData.pixelWidth(), rowAlpha, 0);
    }

    return new Image(display, imageData);
  }
}
