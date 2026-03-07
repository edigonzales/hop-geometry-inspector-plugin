package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeometryInspectorToolbarIconsTest {

  @Test
  void usesPngResourcesWithBlackEnabledState() {
    GeometryInspectorToolbarIcons.PaintStyle enabled = GeometryInspectorToolbarIcons.styleFor(true);
    GeometryInspectorToolbarIcons.PaintStyle disabled = GeometryInspectorToolbarIcons.styleFor(false);

    assertThat(GeometryInspectorToolbarIcons.ICON_VIEWPORT_SIZE).isEqualTo(16);
    assertThat(enabled.red()).isZero();
    assertThat(enabled.green()).isZero();
    assertThat(enabled.blue()).isZero();
    assertThat(enabled.alpha()).isEqualTo(255);
    assertThat(disabled.alpha()).isLessThan(255);
    assertThat(disabled.red()).isGreaterThan(0);

    for (GeometryInspectorToolbarIcons.Symbol symbol : GeometryInspectorToolbarIcons.Symbol.values()) {
      assertThat(GeometryInspectorToolbarIcons.sourceResourcePath(symbol)).endsWith(".svg");
      assertThat(GeometryInspectorToolbarIcons.resourcePath(symbol, 16, true)).endsWith("-enabled-16.png");
      assertThat(GeometryInspectorToolbarIcons.resourcePath(symbol, 32, false))
          .endsWith("-disabled-32.png");
      assertThat(
              GeometryInspectorToolbarIcons.class.getResource(
                  GeometryInspectorToolbarIcons.resourcePath(symbol, 16, true)))
          .isNotNull();
      assertThat(
              GeometryInspectorToolbarIcons.class.getResource(
                  GeometryInspectorToolbarIcons.resourcePath(symbol, 48, false)))
          .isNotNull();
    }
  }

  @Test
  void selectsExpectedPngBucketForMonitorZoom() {
    assertThat(GeometryInspectorToolbarIcons.resourceSizeForZoom(100)).isEqualTo(16);
    assertThat(GeometryInspectorToolbarIcons.resourceSizeForZoom(125)).isEqualTo(16);
    assertThat(GeometryInspectorToolbarIcons.resourceSizeForZoom(150)).isEqualTo(32);
    assertThat(GeometryInspectorToolbarIcons.resourceSizeForZoom(250)).isEqualTo(32);
    assertThat(GeometryInspectorToolbarIcons.resourceSizeForZoom(300)).isEqualTo(48);
  }
}
