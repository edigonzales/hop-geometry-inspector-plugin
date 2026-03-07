package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.ows.wms.WebMapServer;
import org.geotools.ows.wms.request.GetMapRequest;
import org.geotools.ows.wms.response.GetMapResponse;

final class GeometryInspectorBackgroundMapClient {

  private final GeometryInspectorBackgroundMapConfig config;

  private WebMapServer webMapServer;
  private List<org.geotools.ows.wms.Layer> resolvedLayers;

  GeometryInspectorBackgroundMapClient(GeometryInspectorBackgroundMapConfig config) {
    this.config = config == null ? GeometryInspectorBackgroundMapConfig.empty() : config;
  }

  boolean isConfigured() {
    return config.isValid();
  }

  RequestParameters buildRequestParameters(
      ReferencedEnvelope displayArea,
      int logicalWidth,
      int logicalHeight,
      int deviceZoom,
      Integer srid) {
    ReferencedEnvelope normalized =
        GeometryInspectorViewportMath.fitToCanvasAspect(displayArea, logicalWidth, logicalHeight);
    int pixelWidth = GeometryInspectorViewportMath.toPixelSize(logicalWidth, deviceZoom);
    int pixelHeight = GeometryInspectorViewportMath.toPixelSize(logicalHeight, deviceZoom);
    return new RequestParameters(
        normalized,
        pixelWidth,
        pixelHeight,
        srid == null ? "" : "EPSG:" + srid,
        config.parsedLayerNames(),
        config.styleName(),
        config.imageFormat(),
        config.version(),
        config.transparent());
  }

  GeometryInspectorRasterData render(
      ReferencedEnvelope displayArea,
      int logicalWidth,
      int logicalHeight,
      int deviceZoom,
      Integer srid,
      long revision)
      throws Exception {
    if (!config.isValid()) {
      throw new IllegalStateException("Background map is not configured");
    }
    RequestParameters parameters =
        buildRequestParameters(displayArea, logicalWidth, logicalHeight, deviceZoom, srid);
    if (parameters.displayArea() == null || parameters.srsCode().isBlank()) {
      throw new IllegalStateException("Background map requires a renderable extent and EPSG code");
    }

    ensureInitialized();
    GetMapRequest request = webMapServer.createGetMapRequest();
    configureRequest(request, parameters, resolvedLayers);

    GetMapResponse response = webMapServer.issueRequest(request);
    try (InputStream inputStream = response.getInputStream()) {
      BufferedImage image = ImageIO.read(inputStream);
      if (image == null) {
        throw new IllegalStateException("WMS response did not contain a readable image");
      }
      return GeometryInspectorRasterData.fromBufferedImage(
          parameters.displayArea(),
          logicalWidth,
          logicalHeight,
          deviceZoom,
          revision,
          image);
    } finally {
      response.dispose();
    }
  }

  void configureRequest(
      GetMapRequest request,
      RequestParameters parameters,
      List<org.geotools.ows.wms.Layer> layers) {
    request.setVersion(parameters.version());
    request.setFormat(parameters.imageFormat());
    request.setTransparent(parameters.transparent());
    request.setDimensions(parameters.pixelWidth(), parameters.pixelHeight());
    request.setBBox(parameters.displayArea());
    request.setSRS(parameters.srsCode());

    for (org.geotools.ows.wms.Layer layer : layers) {
      if (parameters.styleName().isBlank()) {
        request.addLayer(layer);
      } else {
        request.addLayer(layer, parameters.styleName());
      }
    }
  }

  private synchronized void ensureInitialized() throws Exception {
    if (webMapServer != null && resolvedLayers != null) {
      return;
    }

    String capabilitiesUrl = buildCapabilitiesUrl(config.serviceUrl(), config.version());
    webMapServer = new WebMapServer(new URL(capabilitiesUrl));
    resolvedLayers = new ArrayList<>();
    for (String layerName : config.parsedLayerNames()) {
      resolvedLayers.add(
          webMapServer.getCapabilities().getLayerList().stream()
              .filter(layer -> layerName.equals(layer.getName()))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("WMS layer not found: " + layerName)));
    }
  }

  String buildCapabilitiesUrl(String serviceUrl, String version) {
    String normalized = serviceUrl == null ? "" : serviceUrl.trim();
    normalized = appendQueryParameterIfMissing(normalized, "service", "WMS");
    normalized = appendQueryParameterIfMissing(normalized, "request", "GetCapabilities");
    normalized = appendQueryParameterIfMissing(normalized, "version", version);
    return normalized;
  }

  private String appendQueryParameterIfMissing(String url, String key, String value) {
    if (url.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT) + "=")) {
      return url;
    }
    String separator = url.contains("?") ? "&" : "?";
    return url + separator + key + "=" + value;
  }

  record RequestParameters(
      ReferencedEnvelope displayArea,
      int pixelWidth,
      int pixelHeight,
      String srsCode,
      List<String> layerNames,
      String styleName,
      String imageFormat,
      String version,
      boolean transparent) {}
}
