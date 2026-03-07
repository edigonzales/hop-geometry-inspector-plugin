package ch.so.agi.hop.geometry.inspector;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import org.apache.hop.ui.core.PropsUi;

public class GeometryInspectorSettingsService {

  static final String KEY_SERVICE_URL = "geometryInspector.background.serviceUrl";
  static final String KEY_LAYER_NAMES = "geometryInspector.background.layerNames";
  static final String KEY_STYLE_NAME = "geometryInspector.background.styleName";
  static final String KEY_IMAGE_FORMAT = "geometryInspector.background.imageFormat";
  static final String KEY_VERSION = "geometryInspector.background.version";
  static final String KEY_TRANSPARENT = "geometryInspector.background.transparent";
  static final String KEY_ENABLED_BY_DEFAULT = "geometryInspector.background.enabledByDefault";

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
        Boolean.parseBoolean(store.get(KEY_ENABLED_BY_DEFAULT, Boolean.TRUE.toString())));
  }

  public void saveBackgroundMapConfig(GeometryInspectorBackgroundMapConfig config) {
    GeometryInspectorBackgroundMapConfig effectiveConfig =
        config == null ? GeometryInspectorBackgroundMapConfig.empty() : config;

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
