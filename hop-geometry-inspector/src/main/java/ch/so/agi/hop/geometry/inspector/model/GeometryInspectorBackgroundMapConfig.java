package ch.so.agi.hop.geometry.inspector.model;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record GeometryInspectorBackgroundMapConfig(
    String serviceUrl,
    String layerNames,
    String styleName,
    String imageFormat,
    String version,
    boolean transparent,
    boolean enabledByDefault,
    ServiceType serviceType,
    String tileMatrixSet,
    Map<String, String> dimensions) {

  public enum ServiceType {
    WMS,
    WMTS
  }

  public GeometryInspectorBackgroundMapConfig(
      String serviceUrl,
      String layerNames,
      String styleName,
      String imageFormat,
      String version,
      boolean transparent,
      boolean enabledByDefault) {
    this(
        serviceUrl,
        layerNames,
        styleName,
        imageFormat,
        version,
        transparent,
        enabledByDefault,
        ServiceType.WMS,
        "",
        Map.of());
  }

  public GeometryInspectorBackgroundMapConfig {
    serviceType = serviceType == null ? ServiceType.WMS : serviceType;
    tileMatrixSet = normalize(tileMatrixSet);
    dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
    serviceUrl = normalize(serviceUrl);
    layerNames = normalize(layerNames);
    styleName = normalize(styleName);
    imageFormat = normalize(imageFormat).isBlank() ? "image/png" : normalize(imageFormat);
    version = normalize(version).isBlank() ? "1.3.0" : normalize(version);
  }

  public static GeometryInspectorBackgroundMapConfig empty() {
    return new GeometryInspectorBackgroundMapConfig("", "", "", "image/png", "1.3.0", true, true);
  }

  public boolean isValid() {
    return !serviceUrl.isBlank()
        && !parsedLayerNames().isEmpty()
        && (serviceType != ServiceType.WMTS || parsedLayerNames().size() == 1);
  }

  public List<String> parsedLayerNames() {
    if (layerNames.isBlank()) {
      return List.of();
    }

    if (serviceType == ServiceType.WMTS) return List.of(layerNames);

    return Arrays.stream(layerNames.split("[;,]"))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .collect(Collectors.toUnmodifiableList());
  }

  public String displayLabel() {
    if (!isValid()) {
      return "No background map configured";
    }
    return serviceUrl + " [" + String.join(", ", parsedLayerNames()) + "]";
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
