package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.Test;

class GeometryInspectorClassLoaderSupportTest {

  @Test
  void pluginContextThreadFactoryAppliesPluginClassLoaderToWorkerThreads() throws Exception {
    ThreadFactory threadFactory = GeometryInspectorClassLoaderSupport.newPluginContextThreadFactory();
    FutureTask<ClassLoader> task = new FutureTask<>(() -> Thread.currentThread().getContextClassLoader());

    Thread thread = threadFactory.newThread(task);
    thread.start();

    assertThat(task.get()).isSameAs(GeometryInspectorClassLoaderSupport.class.getClassLoader());
  }
}
