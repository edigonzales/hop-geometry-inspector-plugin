package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;

interface BackgroundMapClient extends AutoCloseable {
  BackgroundRenderResult render(BackgroundRenderRequest request) throws Exception;

  GeometryInspectorBackgroundMapConfig cacheConfig(Integer srid);

  void resetInitialization();

  default boolean hasInitializationFailure() {
    return false;
  }

  default String initializationFailureMessage() {
    return "";
  }

  default void cancelPending() {}

  @Override
  default void close() {
    cancelPending();
  }

  static BackgroundMapClient create(GeometryInspectorBackgroundMapConfig config) {
    return config.serviceType() == GeometryInspectorBackgroundMapConfig.ServiceType.WMTS
        ? new WmtsBackgroundMapClient(config)
        : new GeometryInspectorBackgroundMapClient(config);
  }
}
