package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.geotools.http.SimpleHttpClient;
import org.geotools.ows.wmts.WebMapTileServer;
import org.geotools.ows.wmts.model.*;
import org.geotools.referencing.CRS;

/** Capabilities and the effective settings of a single layer. No SWT objects. */
final class WmtsCatalog {
  final WebMapTileServer server;
  final WMTSCapabilities capabilities;

  static SimpleHttpClient httpClient() {
    SimpleHttpClient client = new SimpleHttpClient();
    client.setConnectTimeout(5); // GeoTools HTTPClient timeouts are seconds.
    client.setReadTimeout(10);
    return client;
  }

  static WmtsCatalog load(String url) throws Exception {
    var client = httpClient();
    URL address = new URL(url);
    var response = client.get(address);
    try {
      var parsed = new org.geotools.ows.wmts.response.WMTSGetCapabilitiesResponse(response);
      return new WmtsCatalog(
          new WebMapTileServer(address, client, (WMTSCapabilities) parsed.getCapabilities()));
    } finally {
      response.dispose();
    }
  }

  WmtsCatalog(WebMapTileServer server) {
    this.server = server;
    this.capabilities = server.getCapabilities();
  }

  WMTSLayer layer(String name) {
    return capabilities.getLayerList().stream()
        .filter(l -> name.equals(l.getName()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("WMTS layer not found: " + name));
  }

  List<TileMatrixSet> matrixSets(WMTSLayer layer) {
    return capabilities.getMatrixSets().stream()
        .filter(m -> layer.getTileMatrixLinks().containsKey(m.getIdentifier()))
        .toList();
  }

  static List<String> formats(WMTSLayer layer) {
    return layer.getFormats().stream()
        .filter(f -> f.equals("image/png") || f.equals("image/jpeg"))
        .toList();
  }

  static String defaultStyle(WMTSLayer layer) {
    if (layer.getDefaultStyle() != null) return layer.getDefaultStyle().getName();
    if (!layer.getStyles().isEmpty()) return layer.getStyles().get(0).getName();
    throw new IllegalArgumentException("WMTS layer offers no style");
  }

  static Map<String, String> defaults(WMTSLayer layer) {
    Map<String, String> result = new LinkedHashMap<>();
    for (var dim : layer.getLayerDimensions()) {
      String value = dim.getExtent() == null ? null : dim.getExtent().getDefaultValue();
      result.put(dim.getName(), value == null ? "" : value);
    }
    return result;
  }

  GeometryInspectorBackgroundMapConfig resolve(
      GeometryInspectorBackgroundMapConfig config, Integer srid) throws Exception {
    if (srid == null) throw new IllegalArgumentException("WMTS requires a consistent EPSG code");
    WMTSLayer layer = layer(config.parsedLayerNames().get(0));
    var crs = CRS.decode("EPSG:" + srid, true);
    TileMatrixSet matrixSet = null;
    for (var candidate : matrixSets(layer)) {
      if (!config.tileMatrixSet().isBlank()
          && !config.tileMatrixSet().equals(candidate.getIdentifier())) continue;
      // Metadata/axis normalization only; raster reprojection is deliberately not performed.
      Integer code = CRS.lookupEpsgCode(candidate.getCoordinateReferenceSystem(), false);
      var normalized =
          code == null
              ? candidate.getCoordinateReferenceSystem()
              : CRS.decode("EPSG:" + code, true);
      if (CRS.equalsIgnoreMetadata(crs, normalized)) {
        matrixSet = candidate;
        break;
      }
    }
    if (matrixSet == null)
      throw new IllegalArgumentException("WMTS has no matching TileMatrixSet for EPSG:" + srid);
    String style = config.styleName().isBlank() ? defaultStyle(layer) : config.styleName();
    if (layer.getStyles().stream().noneMatch(s -> style.equals(s.getName())))
      throw new IllegalArgumentException("WMTS style not found: " + style);
    List<String> formats = formats(layer);
    if (formats.isEmpty())
      throw new IllegalArgumentException("WMTS layer offers neither PNG nor JPEG");
    String format =
        formats.contains(config.imageFormat())
            ? config.imageFormat()
            : formats.contains("image/png") ? "image/png" : formats.get(0);
    Map<String, String> dimensions = defaults(layer);
    for (var entry : dimensions.entrySet()) {
      if (entry.getValue().isBlank())
        entry.setValue(config.dimensions().getOrDefault(entry.getKey(), ""));
      if (entry.getValue().isBlank())
        throw new IllegalArgumentException("WMTS dimension requires a value: " + entry.getKey());
    }
    return new GeometryInspectorBackgroundMapConfig(
        config.serviceUrl(),
        layer.getName(),
        style,
        format,
        "1.0.0",
        true,
        config.enabledByDefault(),
        config.serviceType(),
        matrixSet.getIdentifier(),
        dimensions);
  }

  URL tileUrl(GeometryInspectorBackgroundMapConfig config, String matrix, int row, int col)
      throws Exception {
    WMTSLayer layer = layer(config.layerNames());
    Map<String, String> params = new LinkedHashMap<>();
    params.put("Layer", config.layerNames());
    params.put("Style", config.styleName());
    params.put("TileMatrixSet", config.tileMatrixSet());
    params.put("TileMatrix", matrix);
    params.put("TileRow", Integer.toString(row));
    params.put("TileCol", Integer.toString(col));
    params.putAll(config.dimensions());
    String template = layer.getTemplate(config.imageFormat());
    if (template != null && !template.isBlank()) {
      for (var p : params.entrySet()) {
        template =
            template.replaceAll(
                "(?i)\\{" + java.util.regex.Pattern.quote(p.getKey()) + "\\}",
                java.util.regex.Matcher.quoteReplacement(encode(p.getValue())));
      }
      if (template.contains("{") || template.contains("}"))
        throw new IllegalArgumentException("Unresolved WMTS ResourceURL dimension");
      return new URL(new URL(config.serviceUrl()), template);
    }
    var operation = capabilities.getRequest().getGetTile();
    if (operation == null || operation.getGet() == null)
      throw new IllegalArgumentException("WMTS offers no usable REST template or KVP GetTile URL");
    params.put("Service", "WMTS");
    params.put("Request", "GetTile");
    params.put("Version", "1.0.0");
    params.put("Format", config.imageFormat());
    String base = operation.getGet().toExternalForm();
    // Keep unrelated endpoint parameters, replace WMTS parameters case-insensitively.
    int q = base.indexOf('?');
    String endpoint = q < 0 ? base : base.substring(0, q);
    List<String> query = new ArrayList<>();
    if (q >= 0)
      for (String item : base.substring(q + 1).split("&")) {
        String key = java.net.URLDecoder.decode(item.split("=", 2)[0], StandardCharsets.UTF_8);
        if (!item.isBlank() && params.keySet().stream().noneMatch(k -> k.equalsIgnoreCase(key)))
          query.add(item);
      }
    for (var p : params.entrySet()) query.add(encode(p.getKey()) + "=" + encode(p.getValue()));
    return new URL(endpoint + "?" + String.join("&", query));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
