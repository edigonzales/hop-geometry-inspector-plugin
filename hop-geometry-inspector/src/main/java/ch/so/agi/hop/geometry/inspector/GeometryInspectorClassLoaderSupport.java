package ch.so.agi.hop.geometry.inspector;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class GeometryInspectorClassLoaderSupport {

  private GeometryInspectorClassLoaderSupport() {}

  @FunctionalInterface
  public interface ThrowingSupplier<T, E extends Throwable> {
    T get() throws E;
  }

  @FunctionalInterface
  public interface ThrowingRunnable<E extends Throwable> {
    void run() throws E;
  }

  public static <T, E extends Throwable> T withPluginContextClassLoader(
      ThrowingSupplier<T, E> supplier) throws E {
    ClassLoader pluginClassLoader = GeometryInspectorClassLoaderSupport.class.getClassLoader();
    Thread currentThread = Thread.currentThread();
    ClassLoader originalClassLoader = currentThread.getContextClassLoader();

    if (pluginClassLoader == null || pluginClassLoader == originalClassLoader) {
      return supplier.get();
    }

    currentThread.setContextClassLoader(pluginClassLoader);
    try {
      return supplier.get();
    } finally {
      currentThread.setContextClassLoader(originalClassLoader);
    }
  }

  public static <E extends Throwable> void withPluginContextClassLoader(
      ThrowingRunnable<E> runnable) throws E {
    withPluginContextClassLoader(
        () -> {
          runnable.run();
          return null;
        });
  }

  public static ThreadFactory newPluginContextThreadFactory() {
    return newPluginContextThreadFactory(Executors.defaultThreadFactory());
  }

  public static ThreadFactory newPluginContextThreadFactory(ThreadFactory delegate) {
    ClassLoader pluginClassLoader = GeometryInspectorClassLoaderSupport.class.getClassLoader();

    return runnable ->
        delegate.newThread(
            () -> {
              Thread currentThread = Thread.currentThread();
              ClassLoader originalClassLoader = currentThread.getContextClassLoader();

              if (pluginClassLoader == null || pluginClassLoader == originalClassLoader) {
                runnable.run();
                return;
              }

              currentThread.setContextClassLoader(pluginClassLoader);
              try {
                runnable.run();
              } finally {
                currentThread.setContextClassLoader(originalClassLoader);
              }
            });
  }
}
