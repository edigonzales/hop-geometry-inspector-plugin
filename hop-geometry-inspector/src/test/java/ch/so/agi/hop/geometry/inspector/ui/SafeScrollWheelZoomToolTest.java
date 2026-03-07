package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import org.geotools.geometry.Position2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.swing.JMapPane;
import org.junit.jupiter.api.Test;

class SafeScrollWheelZoomToolTest {

  @Test
  void ignoresWheelZoomWhenVisibleAreaIsZeroSized() {
    TestMapPane mapPane = new TestMapPane();
    mapPane.showing = true;
    mapPane.visibleRect = new Rectangle(0, 0, 0, 0);
    mapPane.displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    SafeScrollWheelZoomTool tool = new SafeScrollWheelZoomTool(mapPane);
    boolean changed = tool.handleWheelZoom(new Position2D(50.0d, 25.0d), 1);

    assertThat(changed).isFalse();
    assertThat(mapPane.lastDisplayArea).isNull();
  }

  @Test
  void appliesWheelZoomWhenViewportIsUsable() {
    TestMapPane mapPane = new TestMapPane();
    mapPane.showing = true;
    mapPane.visibleRect = new Rectangle(0, 0, 400, 300);
    mapPane.displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    SafeScrollWheelZoomTool tool = new SafeScrollWheelZoomTool(mapPane);
    boolean changed = tool.handleWheelZoom(new Position2D(50.0d, 25.0d), 1);

    assertThat(changed).isTrue();
    assertThat(mapPane.lastDisplayArea).isNotNull();
    assertThat(mapPane.lastDisplayArea.getWidth()).isEqualTo(150.0d);
    assertThat(mapPane.lastDisplayArea.getHeight()).isEqualTo(75.0d);
  }

  private static final class TestMapPane extends JMapPane {
    private boolean showing;
    private Rectangle visibleRect = new Rectangle(0, 0, 400, 300);
    private ReferencedEnvelope displayArea;
    private ReferencedEnvelope lastDisplayArea;

    @Override
    public boolean isShowing() {
      return showing;
    }

    @Override
    public Rectangle getVisibleRect() {
      return visibleRect;
    }

    @Override
    public int getWidth() {
      return visibleRect.width;
    }

    @Override
    public int getHeight() {
      return visibleRect.height;
    }

    @Override
    public AffineTransform getScreenToWorldTransform() {
      return new AffineTransform();
    }

    @Override
    public ReferencedEnvelope getDisplayArea() {
      return displayArea;
    }

    @Override
    public void setDisplayArea(org.geotools.api.geometry.Bounds bounds) {
      lastDisplayArea = new ReferencedEnvelope(bounds);
      displayArea = new ReferencedEnvelope(bounds);
    }
  }
}
