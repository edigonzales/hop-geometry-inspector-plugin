package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import java.awt.image.BufferedImage;
import java.io.IOException;
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

  enum InitializationState {
    UNINITIALIZED,
    READY,
    FAILED
  }

  @FunctionalInterface
  interface WebMapServerFactory {
    WebMapServer create(String capabilitiesUrl) throws Exception;
  }

  private final GeometryInspectorBackgroundMapConfig config;
  private final WebMapServerFactory webMapServerFactory;

  private WebMapServer webMapServer;
  private List<org.geotools.ows.wms.Layer> resolvedLayers;
  private volatile InitializationState initializationState = InitializationState.UNINITIALIZED;
  private volatile String initializationErrorMessage = "";
  private volatile String initializationCapabilitiesUrl = "";
  private volatile long initializationFailedAtMillis;

  GeometryInspectorBackgroundMapClient(GeometryInspectorBackgroundMapConfig config) {
    this(config, capabilitiesUrl -> new WebMapServer(new URL(capabilitiesUrl)));
  }

  GeometryInspectorBackgroundMapClient(
      GeometryInspectorBackgroundMapConfig config, WebMapServerFactory webMapServerFactory) {
    this.config = config == null ? GeometryInspectorBackgroundMapConfig.empty() : config;
    this.webMapServerFactory = webMapServerFactory;
  }

  boolean isConfigured() {
    return config.isValid();
  }

  InitializationState initializationState() {
    return initializationState;
  }

  boolean hasInitializationFailure() {
    return initializationState == InitializationState.FAILED;
  }

  String initializationFailureMessage() {
    return initializationErrorMessage;
  }

  void resetInitialization() {
    webMapServer = null;
    resolvedLayers = null;
    initializationState = InitializationState.UNINITIALIZED;
    initializationErrorMessage = "";
    initializationCapabilitiesUrl = "";
    initializationFailedAtMillis = 0L;
  }

  RequestParameters buildRequestParameters(
      ReferencedEnvelope displayArea,
      int logicalWidth,
      int logicalHeight,
      int deviceZoom,
      Integer srid) {
    ReferencedEnvelope renderArea =
        GeometryInspectorViewportMath.fitToCanvasAspect(displayArea, logicalWidth, logicalHeight);
    int pixelWidth = GeometryInspectorViewportMath.toPixelSize(logicalWidth, deviceZoom);
    int pixelHeight = GeometryInspectorViewportMath.toPixelSize(logicalHeight, deviceZoom);
    return new RequestParameters(
        renderArea,
        logicalWidth,
        logicalHeight,
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
          parameters.logicalWidth(),
          parameters.logicalHeight(),
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
    if (initializationState == InitializationState.READY && webMapServer != null && resolvedLayers != null) {
      return;
    }
    if (initializationState == InitializationState.FAILED) {
      throw new IllegalStateException(initializationErrorMessage);
    }

    String capabilitiesUrl = buildCapabilitiesUrl(config.serviceUrl(), config.version());
    try {
      webMapServer = webMapServerFactory.create(capabilitiesUrl);
      resolvedLayers = new ArrayList<>();
      for (String layerName : config.parsedLayerNames()) {
        resolvedLayers.add(
            webMapServer.getCapabilities().getLayerList().stream()
                .filter(layer -> layerName.equals(layer.getName()))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalStateException("WMS layer not found: " + layerName)));
      }
      initializationState = InitializationState.READY;
      initializationErrorMessage = "";
      initializationCapabilitiesUrl = capabilitiesUrl;
      initializationFailedAtMillis = 0L;
    } catch (Exception exception) {
      initializationState = InitializationState.FAILED;
      initializationCapabilitiesUrl = capabilitiesUrl;
      initializationFailedAtMillis = System.currentTimeMillis();
      initializationErrorMessage =
          "WMS initialization failed for "
              + capabilitiesUrl
              + " at "
              + initializationFailedAtMillis
              + ": "
              + rootCauseMessage(exception);
      webMapServer = null;
      resolvedLayers = null;
      throw new IllegalStateException(initializationErrorMessage, exception);
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
      int logicalWidth,
      int logicalHeight,
      int pixelWidth,
      int pixelHeight,
      String srsCode,
      List<String> layerNames,
      String styleName,
      String imageFormat,
      String version,
      boolean transparent) {}

  private String rootCauseMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    if (current.getMessage() == null || current.getMessage().isBlank()) {
      return current.getClass().getSimpleName();
    }
    return current.getClass().getSimpleName() + ": " + current.getMessage();
  }
}
