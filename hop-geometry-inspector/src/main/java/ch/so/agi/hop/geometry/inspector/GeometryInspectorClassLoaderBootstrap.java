package ch.so.agi.hop.geometry.inspector;

import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopPluginException;
import org.apache.hop.core.extension.ExtensionPoint;
import org.apache.hop.core.extension.IExtensionPoint;
import org.apache.hop.core.gui.plugin.GuiPluginType;
import org.apache.hop.core.logging.ILogChannel;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.IPluginTypeListener;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.row.value.ValueMetaPluginType;
import org.apache.hop.core.variables.IVariables;

/**
 * Assigns the Geometry Inspector GUI plugin to the shared geometry classloader group before Hop GUI
 * loads the GUI plugin class.
 *
 * <p>{@code @GuiPlugin} does not expose a classLoaderGroup attribute in Hop 2.19. The registered
 * {@link IPlugin} metadata is mutable, however, and {@link PluginRegistry#getClassLoader(IPlugin)}
 * evaluates the group only when the runtime classloader is requested. Hop calls
 * {@code HopEnvironmentAfterInit} before {@code HopGuiEnvironment.init()}, which gives this
 * extension point a deterministic window to register a GUI-plugin listener.
 */
@ExtensionPoint(
    id = "GeometryInspectorClassLoaderBootstrap",
    extensionPointId = "HopEnvironmentAfterInit",
    description = "Attach the Geometry Inspector GUI plugin to the shared geometry classloader")
public final class GeometryInspectorClassLoaderBootstrap
    implements IExtensionPoint<PluginRegistry> {

  static final String GEOMETRY_CLASSLOADER_GROUP = "sogeo-geometry";
  static final String GEOMETRY_VALUE_META_PLUGIN_ID = "43663879";
  static final String INSPECTOR_GUI_CLASS_NAME =
      "ch.so.agi.hop.geometry.inspector.GeometryInspectorGuiPlugin";

  @Override
  public void callExtensionPoint(
      ILogChannel log, IVariables variables, PluginRegistry pluginRegistry) throws HopException {
    initialize(pluginRegistry);
  }

  static void initialize(PluginRegistry pluginRegistry) throws HopException {
    IPlugin geometryPlugin =
        pluginRegistry.findPluginWithId(
            ValueMetaPluginType.class, GEOMETRY_VALUE_META_PLUGIN_ID);
    if (geometryPlugin == null) {
      throw new HopException(
          "Geometry Inspector requires the hop-geometry-type plugin (ValueMeta plugin id "
              + GEOMETRY_VALUE_META_PLUGIN_ID
              + ")");
    }

    if (!GEOMETRY_CLASSLOADER_GROUP.equals(geometryPlugin.getClassLoaderGroup())) {
      throw new HopException(
          "Geometry Inspector requires hop-geometry-type to use classloader group '"
              + GEOMETRY_CLASSLOADER_GROUP
              + "' but found '"
              + geometryPlugin.getClassLoaderGroup()
              + "'");
    }

    // Make the geometry type the deterministic owner/creator of the shared loader. This ensures
    // JTS and the SQL/MM true-curve classes are defined before the Inspector joins the group.
    try {
      pluginRegistry.getClassLoader(geometryPlugin);
    } catch (HopPluginException e) {
      throw new HopException("Unable to initialize the shared geometry classloader", e);
    }

    // Normal Hop GUI startup has not registered GUI plugins yet. Register the listener before
    // HopGuiEnvironment searches GuiPluginType so the group is assigned before its first
    // PluginRegistry.getClassLoader(guiPlugin) call.
    pluginRegistry.addPluginListener(
        GuiPluginType.class,
        new IPluginTypeListener() {
          @Override
          public void pluginAdded(Object serviceObject) {
            assignInspectorGroup((IPlugin) serviceObject);
          }

          @Override
          public void pluginRemoved(Object serviceObject) {
            // Nothing to do.
          }

          @Override
          public void pluginChanged(Object serviceObject) {
            assignInspectorGroup((IPlugin) serviceObject);
          }
        });

    // Also cover non-standard embedding/test startup orders where the GUI plugin metadata may
    // already have been registered but not loaded yet.
    for (IPlugin guiPlugin : pluginRegistry.getPlugins(GuiPluginType.class)) {
      assignInspectorGroup(guiPlugin);
    }
  }

  static void assignInspectorGroup(IPlugin plugin) {
    if (plugin != null && plugin.getClassMap().containsValue(INSPECTOR_GUI_CLASS_NAME)) {
      plugin.setClassLoaderGroup(GEOMETRY_CLASSLOADER_GROUP);
    }
  }
}
