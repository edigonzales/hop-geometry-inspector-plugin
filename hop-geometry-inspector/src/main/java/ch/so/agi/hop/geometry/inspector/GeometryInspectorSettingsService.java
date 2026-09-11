package ch.so.agi.hop.geometry.inspector;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hop.ui.core.PropsUi;

public class GeometryInspectorSettingsService {

  static final String KEY_SERVICE_URL = "geometryInspector.background.serviceUrl";
  static final String KEY_LAYER_NAMES = "geometryInspector.background.layerNames";
  static final String KEY_STYLE_NAME = "geometryInspector.background.styleName";
  static final String KEY_IMAGE_FORMAT = "geometryInspector.background.imageFormat";
  static final String KEY_VERSION = "geometryInspector.background.version";
  static final String KEY_TRANSPARENT = "geometryInspector.background.transparent";
  static final String KEY_ENABLED_BY_DEFAULT = "geometryInspector.background.enabledByDefault";

  static final String KEY_SERVICE_TYPE = "geometryInspector.background.serviceType";
  static final String KEY_MATRIX_SET = "geometryInspector.background.wmts.tileMatrixSet";
  static final String KEY_DIMENSIONS = "geometryInspector.background.wmts.dimensions";

  public static String encodeDimensions(Map<String, String> values) {
    return new TreeMap<>(values)
        .entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
  }

  static Map<String, String> decodeDimensions(String value) {
    Map<String, String> result = new TreeMap<>();
    if (!value.isBlank())
      for (String item : value.split("&")) {
        String[] pair = item.split("=", 2);
        if (pair.length == 2)
          result.put(
              URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
              URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
      }
    return result;
  }

  private final SettingsStore store;

  public GeometryInspectorSettingsService() {
    this(new PropsUiSettingsStore(PropsUi.getInstance()));
  }

  GeometryInspectorSettingsService(SettingsStore store) {
    this.store = store;
  }

  public GeometryInspectorBackgroundMapConfig loadBackgroundMapConfig() {
    return new GeometryInspectorBackgroundMapConfig(
        store.get(KEY_SERVICE_URL, ""),
        store.get(KEY_LAYER_NAMES, ""),
        store.get(KEY_STYLE_NAME, ""),
        store.get(KEY_IMAGE_FORMAT, "image/png"),
        store.get(KEY_VERSION, "1.3.0"),
        Boolean.parseBoolean(store.get(KEY_TRANSPARENT, Boolean.TRUE.toString())),
        Boolean.parseBoolean(store.get(KEY_ENABLED_BY_DEFAULT, Boolean.TRUE.toString())),
        GeometryInspectorBackgroundMapConfig.ServiceType.valueOf(
            store.get(KEY_SERVICE_TYPE, "WMS")),
        store.get(KEY_MATRIX_SET, ""),
        decodeDimensions(store.get(KEY_DIMENSIONS, "")));
  }

  public void saveBackgroundMapConfig(GeometryInspectorBackgroundMapConfig config) {
    GeometryInspectorBackgroundMapConfig effectiveConfig =
        config == null ? GeometryInspectorBackgroundMapConfig.empty() : config;

    store.set(KEY_SERVICE_TYPE, effectiveConfig.serviceType().name());
    store.set(KEY_MATRIX_SET, effectiveConfig.tileMatrixSet());
    store.set(KEY_DIMENSIONS, encodeDimensions(effectiveConfig.dimensions()));
    store.set(KEY_SERVICE_URL, effectiveConfig.serviceUrl());
    store.set(KEY_LAYER_NAMES, effectiveConfig.layerNames());
    store.set(KEY_STYLE_NAME, effectiveConfig.styleName());
    store.set(KEY_IMAGE_FORMAT, effectiveConfig.imageFormat());
    store.set(KEY_VERSION, effectiveConfig.version());
    store.set(KEY_TRANSPARENT, Boolean.toString(effectiveConfig.transparent()));
    store.set(KEY_ENABLED_BY_DEFAULT, Boolean.toString(effectiveConfig.enabledByDefault()));
  }

  interface SettingsStore {
    String get(String key, String defaultValue);

    void set(String key, String value);
  }

  private static final class PropsUiSettingsStore implements SettingsStore {
    private final PropsUi propsUi;

    private PropsUiSettingsStore(PropsUi propsUi) {
      this.propsUi = propsUi;
    }

    @Override
    public String get(String key, String defaultValue) {
      return propsUi.getCustomParameter(key, defaultValue);
    }

    @Override
    public void set(String key, String value) {
      propsUi.setCustomParameter(key, value == null ? "" : value);
    }
  }
}
