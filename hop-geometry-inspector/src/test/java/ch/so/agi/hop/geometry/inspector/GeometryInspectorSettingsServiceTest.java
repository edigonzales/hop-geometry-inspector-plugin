package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeometryInspectorSettingsServiceTest {

  @Test
  void savesAndLoadsBackgroundMapConfigThroughSettingsStore() {
    InMemorySettingsStore settingsStore = new InMemorySettingsStore();
    GeometryInspectorSettingsService settingsService =
        new GeometryInspectorSettingsService(settingsStore);

    GeometryInspectorBackgroundMapConfig config =
        new GeometryInspectorBackgroundMapConfig(
            " https://maps.example.org/wms ",
            "base,labels",
            "default",
            "image/png",
            "1.3.0",
            true,
            false);

    settingsService.saveBackgroundMapConfig(config);
    GeometryInspectorBackgroundMapConfig loaded = settingsService.loadBackgroundMapConfig();

    assertThat(loaded.serviceUrl()).isEqualTo("https://maps.example.org/wms");
    assertThat(loaded.parsedLayerNames()).containsExactly("base", "labels");
    assertThat(loaded.styleName()).isEqualTo("default");
    assertThat(loaded.imageFormat()).isEqualTo("image/png");
    assertThat(loaded.version()).isEqualTo("1.3.0");
    assertThat(loaded.transparent()).isTrue();
    assertThat(loaded.enabledByDefault()).isFalse();
    assertThat(loaded.isValid()).isTrue();
  }

  @Test
  void missingServiceTypeLoadsLegacyWms() {
    var store = new InMemorySettingsStore();
    store.set(GeometryInspectorSettingsService.KEY_SERVICE_URL, "https://example.org/wms");
    store.set(GeometryInspectorSettingsService.KEY_LAYER_NAMES, "one,two");
    var loaded = new GeometryInspectorSettingsService(store).loadBackgroundMapConfig();
    assertThat(loaded.serviceType())
        .isEqualTo(GeometryInspectorBackgroundMapConfig.ServiceType.WMS);
    assertThat(loaded.parsedLayerNames()).containsExactly("one", "two");
  }

  @Test
  void wmtsRoundTripPreservesDimensionsAndClearRemovesThem() {
    var service = new GeometryInspectorSettingsService(new InMemorySettingsStore());
    var config =
        new GeometryInspectorBackgroundMapConfig(
            "https://example.org/caps.xml",
            "base",
            "default",
            "image/jpeg",
            "1.0.0",
            true,
            false,
            GeometryInspectorBackgroundMapConfig.ServiceType.WMTS,
            "national-grid",
            Map.of("Elevation", "1 & 2=3", "Dimension ä", "a+b/c"));
    service.saveBackgroundMapConfig(config);
    assertThat(service.loadBackgroundMapConfig()).isEqualTo(config);
    service.saveBackgroundMapConfig(GeometryInspectorBackgroundMapConfig.empty());
    assertThat(service.loadBackgroundMapConfig())
        .isEqualTo(GeometryInspectorBackgroundMapConfig.empty());
  }

  private static final class InMemorySettingsStore
      implements GeometryInspectorSettingsService.SettingsStore {
    private final Map<String, String> values = new HashMap<>();

    @Override
    public String get(String key, String defaultValue) {
      return values.getOrDefault(key, defaultValue);
    }

    @Override
    public void set(String key, String value) {
      values.put(key, value);
    }
  }
}
