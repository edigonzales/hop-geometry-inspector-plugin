package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeometryInspectorToolbarButtonTest {

  @Test
  void usesSquareButtonAreaLargerThanIconViewport() {
    assertThat(GeometryInspectorToolbarButton.LOGICAL_BUTTON_SIZE).isEqualTo(34);
    assertThat(GeometryInspectorToolbarButton.LOGICAL_ICON_SIZE)
        .isEqualTo(GeometryInspectorToolbarIcons.ICON_VIEWPORT_SIZE);
    assertThat(GeometryInspectorToolbarButton.LOGICAL_BUTTON_SIZE)
        .isGreaterThan(GeometryInspectorToolbarButton.LOGICAL_ICON_SIZE);
  }
}
