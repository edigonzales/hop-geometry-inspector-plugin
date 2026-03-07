package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryInspectorClassLoaderSupport;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.geotools.swing.DefaultRenderingExecutor;
import org.geotools.swing.RenderingExecutor;

public final class GeometryInspectorRenderingExecutorFactory {

  private GeometryInspectorRenderingExecutorFactory() {}

  public static RenderingExecutor create() {
    DefaultRenderingExecutor renderingExecutor = new DefaultRenderingExecutor();
    ExecutorService taskExecutor =
        Executors.newCachedThreadPool(
            GeometryInspectorClassLoaderSupport.newPluginContextThreadFactory());

    try {
      Field field = DefaultRenderingExecutor.class.getDeclaredField("taskExecutor");
      field.setAccessible(true);
      ExecutorService originalExecutor = (ExecutorService) field.get(renderingExecutor);
      field.set(renderingExecutor, taskExecutor);
      if (originalExecutor != null) {
        originalExecutor.shutdownNow();
      }
      return renderingExecutor;
    } catch (ReflectiveOperationException e) {
      taskExecutor.shutdownNow();
      throw new IllegalStateException(
          "Unable to configure GeoTools rendering executor with the plugin class loader", e);
    }
  }
}
