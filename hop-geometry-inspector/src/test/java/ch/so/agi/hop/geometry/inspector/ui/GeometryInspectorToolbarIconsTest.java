package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeometryInspectorToolbarIconsTest {

  @Test
  void usesBootstrapSizedViewportAndBlackEnabledState() {
    GeometryInspectorToolbarIcons.PaintStyle enabled = GeometryInspectorToolbarIcons.styleFor(true);
    GeometryInspectorToolbarIcons.PaintStyle disabled = GeometryInspectorToolbarIcons.styleFor(false);

    assertThat(GeometryInspectorToolbarIcons.ICON_VIEWPORT_SIZE).isEqualTo(16);
    assertThat(enabled.red()).isZero();
    assertThat(enabled.green()).isZero();
    assertThat(enabled.blue()).isZero();
    assertThat(enabled.alpha()).isEqualTo(255);
    assertThat(disabled.alpha()).isLessThan(255);
    assertThat(disabled.red()).isGreaterThan(0);
  }
}
