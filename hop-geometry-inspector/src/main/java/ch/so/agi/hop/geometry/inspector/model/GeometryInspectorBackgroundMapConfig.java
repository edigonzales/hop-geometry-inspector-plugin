package ch.so.agi.hop.geometry.inspector.model;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public record GeometryInspectorBackgroundMapConfig(
    String serviceUrl,
    String layerNames,
    String styleName,
    String imageFormat,
    String version,
    boolean transparent,
    boolean enabledByDefault) {

  public GeometryInspectorBackgroundMapConfig {
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
    return !serviceUrl.isBlank() && !parsedLayerNames().isEmpty();
  }

  public List<String> parsedLayerNames() {
    if (layerNames.isBlank()) {
      return List.of();
    }

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
