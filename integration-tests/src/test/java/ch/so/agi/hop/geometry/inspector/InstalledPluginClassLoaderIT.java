package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.gui.plugin.GuiPluginType;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.JarCache;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.row.value.ValueMetaPluginType;
import org.apache.hop.ui.hopgui.HopGuiEnvironment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class InstalledPluginClassLoaderIT {

  private static final String PLUGINS_DIR_PROPERTY = "geometry.inspector.plugins.dir";
  private static final String GEOMETRY_VALUE_META_PLUGIN_ID = "43663879";
  private static final String SHARED_GROUP = "sogeo-geometry";
  private static final String INSPECTOR_GUI_CLASS =
      "ch.so.agi.hop.geometry.inspector.GeometryInspectorGuiPlugin";
  private static final String VALUE_META_GEOMETRY_CLASS =
      "com.atolcd.hop.core.row.value.ValueMetaGeometry";
  private static final String JTS_GEOMETRY_CLASS = "org.locationtech.jts.geom.Geometry";
  private static final String CIRCULAR_STRING_CLASS =
      "com.atolcd.hop.gis.geometry.curve.CircularString";

  @Test
  void installedPluginsShareTheGeometryRuntimeClassLoader() throws Exception {
    String configuredPluginsDir = System.getProperty(PLUGINS_DIR_PROPERTY);
    Assumptions.assumeTrue(
        configuredPluginsDir != null && !configuredPluginsDir.isBlank(),
        () -> "Set -D" + PLUGINS_DIR_PROPERTY + " to run the installed-plugin integration test");

    Path pluginsDir = Path.of(configuredPluginsDir).toAbsolutePath().normalize();
    Path geometryTypeDir = pluginsDir.resolve("misc/hop-geometry-type");
    Path inspectorDir = pluginsDir.resolve("misc/hop-geometry-inspector");

    assertThat(Files.isDirectory(geometryTypeDir)).as("installed geometry-type plugin").isTrue();
    assertThat(Files.isDirectory(inspectorDir)).as("installed inspector plugin").isTrue();

    // Prove the test harness itself cannot see either plugin. The only path to these classes must
    // be Hop's plugin scan of the installed directory tree.
    ClassLoader harnessLoader = InstalledPluginClassLoaderIT.class.getClassLoader();
    assertThatThrownBy(() -> Class.forName(INSPECTOR_GUI_CLASS, false, harnessLoader))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() -> Class.forName(VALUE_META_GEOMETRY_CLASS, false, harnessLoader))
        .isInstanceOf(ClassNotFoundException.class);

    String previousPluginFolders = System.getProperty(Const.HOP_PLUGIN_BASE_FOLDERS);
    try {
      System.setProperty(Const.HOP_PLUGIN_BASE_FOLDERS, pluginsDir.toString());

      HopEnvironment.reset();
      JarCache.getInstance().clear();

      // This scans ValueMeta and ExtensionPoint plugins from the installed tree. The Inspector
      // bootstrap is invoked by HopEnvironmentAfterInit and installs the GuiPluginType listener.
      HopEnvironment.init();

      // Match normal GUI startup order, but only initialize the GUI plugin type needed here.
      // This scans the installed Inspector and then asks PluginRegistry for its runtime loader.
      HopGuiEnvironment.init(List.of(GuiPluginType.getInstance()));

      PluginRegistry registry = PluginRegistry.getInstance();
      IPlugin geometryPlugin =
          registry.findPluginWithId(ValueMetaPluginType.class, GEOMETRY_VALUE_META_PLUGIN_ID);
      IPlugin inspectorPlugin = findInspectorPlugin(registry);

      assertThat(geometryPlugin).as("ValueMetaGeometry plugin").isNotNull();
      assertThat(inspectorPlugin).as("Geometry Inspector GUI plugin").isNotNull();
      assertThat(geometryPlugin.getClassLoaderGroup()).isEqualTo(SHARED_GROUP);
      assertThat(inspectorPlugin.getClassLoaderGroup()).isEqualTo(SHARED_GROUP);

      ClassLoader geometryLoader = registry.getClassLoader(geometryPlugin);
      ClassLoader inspectorLoader = registry.getClassLoader(inspectorPlugin);

      assertThat(inspectorLoader).isSameAs(geometryLoader);

      Class<?> valueMetaGeometry = geometryLoader.loadClass(VALUE_META_GEOMETRY_CLASS);
      Class<?> inspectorGui = inspectorLoader.loadClass(INSPECTOR_GUI_CLASS);
      Class<?> geometryFromGeometryLoader = geometryLoader.loadClass(JTS_GEOMETRY_CLASS);
      Class<?> geometryFromInspectorLoader = inspectorLoader.loadClass(JTS_GEOMETRY_CLASS);
      Class<?> curveFromGeometryLoader = geometryLoader.loadClass(CIRCULAR_STRING_CLASS);
      Class<?> curveFromInspectorLoader = inspectorLoader.loadClass(CIRCULAR_STRING_CLASS);

      assertThat(valueMetaGeometry.getClassLoader()).isSameAs(geometryLoader);
      assertThat(inspectorGui.getClassLoader()).isSameAs(geometryLoader);
      assertThat(geometryFromInspectorLoader).isSameAs(geometryFromGeometryLoader);
      assertThat(curveFromInspectorLoader).isSameAs(curveFromGeometryLoader);
      assertThat(geometryFromGeometryLoader.isAssignableFrom(curveFromGeometryLoader)).isTrue();
    } finally {
      HopEnvironment.reset();
      JarCache.getInstance().clear();
      if (previousPluginFolders == null) {
        System.clearProperty(Const.HOP_PLUGIN_BASE_FOLDERS);
      } else {
        System.setProperty(Const.HOP_PLUGIN_BASE_FOLDERS, previousPluginFolders);
      }
    }
  }

  private IPlugin findInspectorPlugin(PluginRegistry registry) {
    return registry.getPlugins(GuiPluginType.class).stream()
        .filter(plugin -> plugin.getClassMap().containsValue(INSPECTOR_GUI_CLASS))
        .findFirst()
        .orElse(null);
  }
}
