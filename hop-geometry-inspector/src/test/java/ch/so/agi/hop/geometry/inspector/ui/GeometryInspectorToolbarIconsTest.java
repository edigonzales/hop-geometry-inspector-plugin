package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.Icon;
import org.junit.jupiter.api.Test;

class GeometryInspectorToolbarIconsTest {

  @Test
  void createsIconsAtRequestedSize() {
    Icon zoomIn = GeometryInspectorToolbarIcons.zoomIn(24);
    Icon background = GeometryInspectorToolbarIcons.background(32);

    assertThat(zoomIn).isNotNull();
    assertThat(zoomIn.getIconWidth()).isEqualTo(24);
    assertThat(zoomIn.getIconHeight()).isEqualTo(24);
    assertThat(background).isNotNull();
    assertThat(background.getIconWidth()).isEqualTo(32);
    assertThat(background.getIconHeight()).isEqualTo(32);
  }
}
