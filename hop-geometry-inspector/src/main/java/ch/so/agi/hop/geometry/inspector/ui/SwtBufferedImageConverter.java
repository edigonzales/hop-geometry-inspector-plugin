package ch.so.agi.hop.geometry.inspector.ui;

import java.awt.image.BufferedImage;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.widgets.Display;

final class SwtBufferedImageConverter {

  private SwtBufferedImageConverter() {}

  static Image toImage(Display display, GeometryInspectorRasterData rasterData) {
    return new Image(
        display,
        toImageData(
            rasterData.pixelWidth(), rasterData.pixelHeight(), rasterData.argbPixels()));
  }

  static Image toImage(Display display, BufferedImage bufferedImage) {
    int width = bufferedImage.getWidth();
    int height = bufferedImage.getHeight();
    int[] argbPixels = bufferedImage.getRGB(0, 0, width, height, null, 0, width);
    return new Image(display, toImageData(width, height, argbPixels));
  }

  private static ImageData toImageData(int width, int height, int[] argbPixels) {
    PaletteData palette = new PaletteData(0x00FF0000, 0x0000FF00, 0x000000FF);
    ImageData imageData = new ImageData(width, height, 24, palette);
    imageData.alphaData = new byte[width * height];

    int[] rowPixels = new int[width];
    byte[] rowAlpha = new byte[width];

    for (int y = 0; y < height; y++) {
      int rowStart = y * width;
      for (int x = 0; x < width; x++) {
        int argb = argbPixels[rowStart + x];
        rowPixels[x] = argb & 0x00FFFFFF;
        rowAlpha[x] = (byte) ((argb >>> 24) & 0xFF);
      }
      imageData.setPixels(0, y, width, rowPixels, 0);
      imageData.setAlphas(0, y, width, rowAlpha, 0);
    }

    return imageData;
  }
}
