package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.geotools.swing.DefaultRenderingExecutor;
import org.junit.jupiter.api.Test;

class GeometryInspectorRenderingExecutorFactoryTest {

  @Test
  void renderingExecutorUsesPluginClassLoaderOnTaskThreads() throws Exception {
    DefaultRenderingExecutor executor =
        (DefaultRenderingExecutor) GeometryInspectorRenderingExecutorFactory.create();
    try {
      ExecutorService taskExecutor = getTaskExecutor(executor);
      Future<ClassLoader> future = taskExecutor.submit(() -> Thread.currentThread().getContextClassLoader());

      assertThat(future.get())
          .isSameAs(GeometryInspectorRenderingExecutorFactory.class.getClassLoader());
    } finally {
      executor.shutdown();
    }
  }

  private ExecutorService getTaskExecutor(DefaultRenderingExecutor executor) throws Exception {
    Field field = DefaultRenderingExecutor.class.getDeclaredField("taskExecutor");
    field.setAccessible(true);
    return (ExecutorService) field.get(executor);
  }
}
