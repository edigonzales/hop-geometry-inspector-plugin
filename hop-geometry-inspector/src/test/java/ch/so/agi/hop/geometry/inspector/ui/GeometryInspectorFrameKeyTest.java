package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.junit.jupiter.api.Test;

class GeometryInspectorFrameKeyTest {

  @Test
  void includesPixelSizeWithoutAspectFittingTheWorldExtent() {
    GeometryInspectorBackgroundMapConfig config =
        new GeometryInspectorBackgroundMapConfig(
            "https://example.com/wms", "base", "", "image/png", "1.3.0", true, true);
    ReferencedEnvelope displayArea = new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null);

    GeometryInspectorFrameKey first =
        GeometryInspectorFrameKey.forBackground(config, displayArea, 300, 150, 100, 96, 2056, true);
    GeometryInspectorFrameKey second =
        GeometryInspectorFrameKey.forBackground(
            config, displayArea, 600, 300, 200, 192, 2056, true);

    assertThat(first.minX()).isEqualTo(second.minX());
    assertThat(first.maxX()).isEqualTo(second.maxX());
    assertThat(first.minY()).isEqualTo(second.minY());
    assertThat(first.maxY()).isEqualTo(second.maxY());
    assertThat(first.pixelWidth()).isNotEqualTo(second.pixelWidth());
    assertThat(first.deviceZoom()).isNotEqualTo(second.deviceZoom());
    assertThat(first.outputDpi()).isNotEqualTo(second.outputDpi());
  }

  @Test
  void wmtsFramesAreSeparatedByMatrixSetAndEffectiveDimensions() {
    var area = new ReferencedEnvelope(0, 100, 0, 100, null);
    var first =
        new GeometryInspectorBackgroundMapConfig(
            "https://example.org/caps",
            "base",
            "default",
            "image/png",
            "1.0.0",
            true,
            true,
            GeometryInspectorBackgroundMapConfig.ServiceType.WMTS,
            "grid-a",
            java.util.Map.of("Time", "2020"));
    var second =
        new GeometryInspectorBackgroundMapConfig(
            first.serviceUrl(),
            first.layerNames(),
            first.styleName(),
            first.imageFormat(),
            first.version(),
            true,
            true,
            first.serviceType(),
            "grid-b",
            first.dimensions());
    var third =
        new GeometryInspectorBackgroundMapConfig(
            first.serviceUrl(),
            first.layerNames(),
            first.styleName(),
            first.imageFormat(),
            first.version(),
            true,
            true,
            first.serviceType(),
            first.tileMatrixSet(),
            java.util.Map.of("Time", "2021"));
    var a = GeometryInspectorFrameKey.forBackground(first, area, 100, 100, 100, 96, 2056, true);
    assertThat(a)
        .isNotEqualTo(
            GeometryInspectorFrameKey.forBackground(second, area, 100, 100, 100, 96, 2056, true));
    assertThat(a)
        .isNotEqualTo(
            GeometryInspectorFrameKey.forBackground(third, area, 100, 100, 100, 96, 2056, true));
  }
}
