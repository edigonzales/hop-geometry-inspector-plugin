package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.geotools.api.geometry.Bounds;
import org.geotools.data.ows.Response;
import org.geotools.http.HTTPResponse;
import org.geotools.ows.ServiceException;
import org.geotools.ows.wms.Layer;
import org.geotools.ows.wms.request.GetMapRequest;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.junit.jupiter.api.Test;

class GeometryInspectorBackgroundMapClientTest {

  @Test
  void buildsCapabilitiesUrlAndRequestParameters() {
    GeometryInspectorBackgroundMapClient client =
        new GeometryInspectorBackgroundMapClient(
            new GeometryInspectorBackgroundMapConfig(
                "https://example.com/wms",
                "base,overlay",
                "bright",
                "image/png",
                "1.3.0",
                true,
                true));

    String capabilitiesUrl = client.buildCapabilitiesUrl("https://example.com/wms", "1.3.0");
    GeometryInspectorBackgroundMapClient.RequestParameters parameters =
        client.buildRequestParameters(
            new ReferencedEnvelope(2600000.0d, 2601000.0d, 1200000.0d, 1200500.0d, null),
            400,
            200,
            200,
            192,
            2056);

    assertThat(capabilitiesUrl)
        .isEqualTo(
            "https://example.com/wms?service=WMS&request=GetCapabilities&version=1.3.0");
    assertThat(parameters.logicalWidth()).isEqualTo(400);
    assertThat(parameters.logicalHeight()).isEqualTo(200);
    assertThat(parameters.pixelWidth()).isEqualTo(800);
    assertThat(parameters.pixelHeight()).isEqualTo(400);
    assertThat(parameters.outputDpi()).isEqualTo(192);
    assertThat(parameters.srsCode()).isEqualTo("EPSG:2056");
    assertThat(parameters.layerNames()).containsExactly("base", "overlay");
    assertThat(parameters.styleName()).isEqualTo("bright");
  }

  @Test
  void usesTransientRenderAreaAndFullCanvasPixelSize() {
    GeometryInspectorBackgroundMapClient client =
        new GeometryInspectorBackgroundMapClient(
            new GeometryInspectorBackgroundMapConfig(
                "https://example.com/wms",
                "base",
                "",
                "image/png",
                "1.3.0",
                true,
                true));

    GeometryInspectorBackgroundMapClient.RequestParameters parameters =
        client.buildRequestParameters(
            new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null), 300, 300, 100, 96, 2056);

    assertThat(parameters.displayArea().getWidth()).isEqualTo(100.0d);
    assertThat(parameters.displayArea().getHeight()).isEqualTo(100.0d);
    assertThat(parameters.logicalWidth()).isEqualTo(300);
    assertThat(parameters.logicalHeight()).isEqualTo(300);
    assertThat(parameters.pixelWidth()).isEqualTo(300);
    assertThat(parameters.pixelHeight()).isEqualTo(300);
  }

  @Test
  void configuresGetMapRequestWithLayersAndStyle() {
    GeometryInspectorBackgroundMapClient client =
        new GeometryInspectorBackgroundMapClient(
            new GeometryInspectorBackgroundMapConfig(
                "https://example.com/wms?foo=bar",
                "base,overlay",
                "bright",
                "image/png",
                "1.3.0",
                true,
                true));
    GeometryInspectorBackgroundMapClient.RequestParameters parameters =
        client.buildRequestParameters(
            new ReferencedEnvelope(0.0d, 10.0d, 0.0d, 5.0d, null), 200, 100, 100, 144, 4326);
    RecordingGetMapRequest request = new RecordingGetMapRequest();
    Layer base = new Layer();
    base.setName("base");
    Layer overlay = new Layer();
    overlay.setName("overlay");

    client.configureRequest(request, parameters, List.of(base, overlay));

    assertThat(request.version).isEqualTo("1.3.0");
    assertThat(request.format).isEqualTo("image/png");
    assertThat(request.transparent).isTrue();
    assertThat(request.width).isEqualTo(200);
    assertThat(request.height).isEqualTo(100);
    assertThat(request.srs).isEqualTo("EPSG:4326");
    assertThat(request.bounds).isEqualTo(parameters.displayArea());
    assertThat(request.layerNames).containsExactly("base", "overlay");
    assertThat(request.styles).containsExactly("bright", "bright");
    assertThat(request.vendorSpecificParameters)
        .containsEntry("DPI", "144")
        .containsEntry("MAP_RESOLUTION", "144")
        .containsEntry("FORMAT_OPTIONS", "dpi:144");
  }

  @Test
  void failedInitializationDoesNotRetryUntilReset() {
    AtomicInteger attempts = new AtomicInteger();
    GeometryInspectorBackgroundMapClient client =
        new GeometryInspectorBackgroundMapClient(
            new GeometryInspectorBackgroundMapConfig(
                "https://example.com/wms",
                "base",
                "",
                "image/png",
                "1.3.0",
                true,
                true),
            capabilitiesUrl -> {
              attempts.incrementAndGet();
              throw new IOException("boom");
            });

    assertThatThrownBy(
            () -> client.render(
                new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null),
                300,
                300,
                100,
                96,
                2056,
                1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WMS initialization failed");
    assertThat(client.initializationState())
        .isEqualTo(GeometryInspectorBackgroundMapClient.InitializationState.FAILED);
    assertThat(attempts).hasValue(1);

    assertThatThrownBy(
            () -> client.render(
                new ReferencedEnvelope(0.0d, 100.0d, 0.0d, 50.0d, null),
                300,
                300,
                100,
                96,
                2056,
                2L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WMS initialization failed");
    assertThat(attempts).hasValue(1);

    client.resetInitialization();
    assertThat(client.initializationState())
        .isEqualTo(GeometryInspectorBackgroundMapClient.InitializationState.UNINITIALIZED);
  }

  private static final class RecordingGetMapRequest implements GetMapRequest {
    private final Properties properties = new Properties();
    private String version;
    private String format;
    private boolean transparent;
    private int width;
    private int height;
    private Bounds bounds;
    private String srs;
    private final List<String> layerNames = new ArrayList<>();
    private final List<String> styles = new ArrayList<>();
    private final Map<String, String> vendorSpecificParameters = new LinkedHashMap<>();

    @Override
    public void setVersion(String version) {
      this.version = version;
    }

    @Override
    public void addLayer(Layer layer, org.geotools.ows.wms.StyleImpl style) {
      layerNames.add(layer.getName());
      styles.add(style == null ? "" : style.getName());
    }

    @Override
    public void addLayer(String layerName, org.geotools.ows.wms.StyleImpl style) {
      layerNames.add(layerName);
      styles.add(style == null ? "" : style.getName());
    }

    @Override
    public void addLayer(String layerName, String styleName) {
      layerNames.add(layerName);
      styles.add(styleName == null ? "" : styleName);
    }

    @Override
    public void addLayer(Layer layer, String styleName) {
      layerNames.add(layer.getName());
      styles.add(styleName == null ? "" : styleName);
    }

    @Override
    public void addLayer(Layer layer) {
      layerNames.add(layer.getName());
      styles.add("");
    }

    @Override
    public void setSRS(String srs) {
      this.srs = srs;
    }

    @Override
    public void setBBox(String bbox) {}

    @Override
    public void setBBox(Bounds bounds) {
      this.bounds = bounds;
    }

    @Override
    public void setFormat(String format) {
      this.format = format;
    }

    @Override
    public void setDimensions(String key, String value) {}

    @Override
    public void setDimensions(int width, int height) {
      this.width = width;
      this.height = height;
    }

    @Override
    public void setDimensions(java.awt.Dimension dimension) {
      this.width = dimension.width;
      this.height = dimension.height;
    }

    @Override
    public void setTransparent(boolean transparent) {
      this.transparent = transparent;
    }

    @Override
    public void setBGColour(String bgColour) {}

    @Override
    public void setExceptions(String exceptions) {}

    @Override
    public void setTime(String time) {}

    @Override
    public void setElevation(String elevation) {}

    @Override
    public void setSampleDimensionValue(String dimensionName, String value) {}

    @Override
    public void setVendorSpecificParameter(String name, String value) {
      vendorSpecificParameters.put(name, value);
    }

    @Override
    public void setProperties(Properties properties) {
      this.properties.clear();
      this.properties.putAll(properties);
    }

    @Override
    public URL getFinalURL() {
      return null;
    }

    @Override
    public void setProperty(String key, String value) {
      properties.setProperty(key, value);
    }

    @Override
    public Properties getProperties() {
      return properties;
    }

    @Override
    public Response createResponse(HTTPResponse response) throws ServiceException, IOException {
      return null;
    }

    @Override
    public boolean requiresPost() {
      return false;
    }

    @Override
    public String getPostContentType() {
      return "";
    }

    @Override
    public void performPostOutput(OutputStream outputStream) {}
  }
}
