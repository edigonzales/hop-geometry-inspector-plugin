package ch.so.agi.hop.geometry.inspector.ui;

import org.geotools.swing.JMapPane;
import org.geotools.swing.event.MapMouseListener;
import org.geotools.swing.tool.PanTool;

public final class GeometryInspectorMapInteractionSupport {

  private GeometryInspectorMapInteractionSupport() {}

  public static void install(JMapPane mapPane, MapMouseListener identifyListener) {
    mapPane.setCursorTool(new PanTool());
    mapPane.addMouseListener(new SafeScrollWheelZoomTool(mapPane));
    if (identifyListener != null) {
      mapPane.addMouseListener(identifyListener);
    }
  }
}
