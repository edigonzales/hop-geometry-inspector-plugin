package ch.so.agi.hop.geometry.inspector;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;

/** Settings double that does not initialize Hop GUI services. */
public final class BackgroundMapTestSettings extends GeometryInspectorSettingsService {
  private final GeometryInspectorBackgroundMapConfig initial;
  public GeometryInspectorBackgroundMapConfig saved;

  public BackgroundMapTestSettings(GeometryInspectorBackgroundMapConfig initial) {
    super(
        new SettingsStore() {
          @Override
          public String get(String key, String fallback) {
            return fallback;
          }

          @Override
          public void set(String key, String value) {}
        });
    this.initial = initial;
  }

  @Override
  public GeometryInspectorBackgroundMapConfig loadBackgroundMapConfig() {
    return initial;
  }

  @Override
  public void saveBackgroundMapConfig(GeometryInspectorBackgroundMapConfig value) {
    saved = value;
  }
}
