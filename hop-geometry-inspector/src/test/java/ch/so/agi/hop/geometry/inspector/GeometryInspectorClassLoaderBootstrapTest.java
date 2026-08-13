package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.hop.core.gui.plugin.GuiPluginType;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.IPluginType;
import org.apache.hop.core.plugins.Plugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.row.value.ValueMetaPluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;

class GeometryInspectorClassLoaderBootstrapTest {

  @TempDir Path tempDir;

  @Test
  void inspectorJoinsGeometryOwnedClassLoaderBeforeItsClassesAreLoaded() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    registry.reset();

    try {
      IPlugin geometryPlugin =
          plugin(
              ValueMetaPluginType.class,
              GeometryInspectorClassLoaderBootstrap.GEOMETRY_VALUE_META_PLUGIN_ID,
              "geometry",
              GeometryInspectorClassLoaderBootstrap.GEOMETRY_CLASSLOADER_GROUP,
              Map.of(Object.class, "com.atolcd.hop.core.row.value.ValueMetaGeometry"),
              List.of(locationOf(CurveGeometrySupport.class), locationOf(Geometry.class)),
              CurveGeometrySupport.class.getProtectionDomain().getCodeSource().getLocation());
      registry.registerPlugin(ValueMetaPluginType.class, geometryPlugin);

      GeometryInspectorClassLoaderBootstrap.initialize(registry);
      ClassLoader geometryLoader = registry.getClassLoader(geometryPlugin);

      String markerClassName = compileMarkerClass(tempDir);
      LinkedHashMap<Class<?>, String> guiClassMap = new LinkedHashMap<>();
      // The first class is deliberately invisible to the test parent classloader. This forces
      // PluginRegistry to append the GUI plugin library to the already-created group loader.
      guiClassMap.put(String.class, markerClassName);
      guiClassMap.put(
          Object.class, GeometryInspectorClassLoaderBootstrap.INSPECTOR_GUI_CLASS_NAME);

      IPlugin guiPlugin =
          plugin(
              GuiPluginType.class,
              "geometry-inspector-test",
              "inspector",
              null,
              guiClassMap,
              List.of(tempDir.toString()),
              tempDir.toUri().toURL());
      registry.registerPlugin(GuiPluginType.class, guiPlugin);

      assertThat(guiPlugin.getClassLoaderGroup())
          .isEqualTo(GeometryInspectorClassLoaderBootstrap.GEOMETRY_CLASSLOADER_GROUP);

      ClassLoader inspectorLoader = registry.getClassLoader(guiPlugin);
      assertThat(inspectorLoader).isSameAs(geometryLoader);

      Class<?> markerClass = inspectorLoader.loadClass(markerClassName);
      assertThat(markerClass.getClassLoader()).isSameAs(geometryLoader);

      Class<?> geometryFromGeometryPlugin =
          geometryLoader.loadClass("org.locationtech.jts.geom.Geometry");
      Class<?> geometryFromInspector =
          inspectorLoader.loadClass("org.locationtech.jts.geom.Geometry");
      assertThat(geometryFromInspector).isSameAs(geometryFromGeometryPlugin);
      assertThat(geometryFromInspector.getClassLoader()).isSameAs(geometryLoader);

      Class<?> curveFromGeometryPlugin =
          geometryLoader.loadClass(CircularString.class.getName());
      Class<?> curveFromInspector = inspectorLoader.loadClass(CircularString.class.getName());
      assertThat(curveFromInspector).isSameAs(curveFromGeometryPlugin);
      assertThat(curveFromInspector.getClassLoader()).isSameAs(geometryLoader);
    } finally {
      registry.reset();
    }
  }

  private static IPlugin plugin(
      Class<? extends IPluginType> pluginType,
      String id,
      String name,
      String classLoaderGroup,
      Map<Class<?>, String> classMap,
      List<String> libraries,
      URL pluginFolder) {
    return new Plugin(
        new String[] {id},
        pluginType,
        Object.class,
        "",
        name,
        name,
        null,
        false,
        classLoaderGroup,
        false,
        classMap,
        new ArrayList<>(libraries),
        null,
        new String[0],
        pluginFolder,
        false,
        null,
        null,
        null);
  }

  private static String locationOf(Class<?> type) throws Exception {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  }

  private static String compileMarkerClass(Path outputDirectory) throws Exception {
    String className = "ch.so.agi.hop.geometry.inspector.dynamic.GuiPluginMarker";
    Path source =
        outputDirectory.resolve(
            "ch/so/agi/hop/geometry/inspector/dynamic/GuiPluginMarker.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        "package ch.so.agi.hop.geometry.inspector.dynamic; public final class GuiPluginMarker {}",
        StandardCharsets.UTF_8);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("Tests require a JDK, not a JRE").isNotNull();
    int result = compiler.run(null, null, null, "-d", outputDirectory.toString(), source.toString());
    assertThat(result).isZero();
    return className;
  }
}
