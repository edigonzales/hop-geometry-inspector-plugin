package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.List;
import org.geotools.swing.JMapPane;
import org.geotools.swing.event.DefaultMapMouseEventDispatcher;
import org.geotools.swing.event.MapMouseAdapter;
import org.geotools.swing.event.MapMouseListener;
import org.geotools.swing.tool.PanTool;
import org.junit.jupiter.api.Test;

class GeometryInspectorMapInteractionSupportTest {

  @Test
  void installsPanToolAndRegistersSafeWheelAndIdentifyListeners() throws Exception {
    JMapPane mapPane = new TestMapPane();
    MapMouseAdapter identifyListener = new MapMouseAdapter() {};

    GeometryInspectorMapInteractionSupport.install(mapPane, identifyListener);

    assertThat(mapPane.getCursorTool()).isInstanceOf(PanTool.class);
    List<MapMouseListener> listeners = getListeners(mapPane);
    assertThat(listeners).contains(identifyListener);
    assertThat(listeners).anyMatch(listener -> listener instanceof SafeScrollWheelZoomTool);
  }

  @SuppressWarnings("unchecked")
  private List<MapMouseListener> getListeners(JMapPane mapPane) throws Exception {
    DefaultMapMouseEventDispatcher dispatcher =
        (DefaultMapMouseEventDispatcher) mapPane.getMouseEventDispatcher();
    Field field = DefaultMapMouseEventDispatcher.class.getDeclaredField("listeners");
    field.setAccessible(true);
    return (List<MapMouseListener>) field.get(dispatcher);
  }

  private static final class TestMapPane extends JMapPane {
    @Override
    public boolean isShowing() {
      return true;
    }

    @Override
    public Rectangle getVisibleRect() {
      return new Rectangle(0, 0, 400, 300);
    }

    @Override
    public int getWidth() {
      return 400;
    }

    @Override
    public int getHeight() {
      return 300;
    }

    @Override
    public AffineTransform getScreenToWorldTransform() {
      return new AffineTransform();
    }
  }
}
