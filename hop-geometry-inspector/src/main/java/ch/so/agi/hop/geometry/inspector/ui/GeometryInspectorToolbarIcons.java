package ch.so.agi.hop.geometry.inspector.ui;

import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;

public final class GeometryInspectorToolbarIcons {

  static final int ICON_VIEWPORT_SIZE = 16;

  private static final Logger LOG =
      Logger.getLogger(GeometryInspectorToolbarIcons.class.getName());
  private static final Set<String> LOGGED_WARNINGS = ConcurrentHashMap.newKeySet();

  private GeometryInspectorToolbarIcons() {}

  static PaintStyle styleFor(boolean enabled) {
    if (enabled) {
      return new PaintStyle(0, 0, 0, 255);
    }
    return new PaintStyle(120, 120, 120, 160);
  }

  static Image createImage(
      Display display, Symbol symbol, int logicalSize, int deviceZoom, boolean enabled) {
    String path = resourcePath(symbol, resourceSizeForZoom(deviceZoom), enabled);
    try (InputStream inputStream =
        GeometryInspectorToolbarIcons.class.getResourceAsStream(path)) {
      if (inputStream == null) {
        warnOnce("Missing toolbar icon resource: " + path, null);
        return null;
      }
      return new Image(display, inputStream);
    } catch (Exception exception) {
      warnOnce("Unable to load toolbar icon resource: " + path, exception);
      return null;
    }
  }

  static int resourceSizeForZoom(int deviceZoom) {
    if (deviceZoom <= 125) {
      return 16;
    }
    if (deviceZoom <= 250) {
      return 32;
    }
    return 48;
  }

  static String sourceResourcePath(Symbol symbol) {
    return "/ch/so/agi/hop/geometry/inspector/ui/toolbar-icons/" + symbol.fileName + ".svg";
  }

  static String resourcePath(Symbol symbol, int size, boolean enabled) {
    return "/ch/so/agi/hop/geometry/inspector/ui/toolbar-icons/png/"
        + symbol.fileName
        + "-"
        + (enabled ? "enabled" : "disabled")
        + "-"
        + size
        + ".png";
  }

  private static void warnOnce(String message, Exception exception) {
    if (!LOGGED_WARNINGS.add(message)) {
      return;
    }
    if (exception == null) {
      LOG.warning(message);
      return;
    }
    LOG.log(Level.WARNING, message, exception);
  }

  enum Symbol {
    ZOOM_IN("zoom-in"),
    ZOOM_OUT("zoom-out"),
    ZOOM_EXTENT("bounding-box"),
    REFRESH("arrow-clockwise"),
    BACKGROUND("map");

    private final String fileName;

    Symbol(String fileName) {
      this.fileName = fileName;
    }
  }

  record PaintStyle(int red, int green, int blue, int alpha) {}
}
