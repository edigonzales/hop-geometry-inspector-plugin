package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeometryInspectorStatusLabelTest {

  @Test
  void skipsIdenticalStatusText() {
    assertThat(GeometryInspectorSwtViewer.shouldUpdateStatusText("status", "status"))
        .isFalse();
    assertThat(GeometryInspectorSwtViewer.shouldUpdateStatusText(null, "status"))
        .isTrue();
    assertThat(GeometryInspectorSwtViewer.shouldUpdateStatusText("old", "new"))
        .isTrue();
  }

  @Test
  void relayoutsOnlyWhenTheCurrentSizeCannotDisplayWrappedText() {
    assertThat(GeometryInspectorSwtViewer.shouldRelayoutStatusLabel(800, 20, 20)).isFalse();
    assertThat(GeometryInspectorSwtViewer.shouldRelayoutStatusLabel(800, 20, 40)).isTrue();
    assertThat(GeometryInspectorSwtViewer.shouldRelayoutStatusLabel(0, 0, 0)).isTrue();
  }
}
