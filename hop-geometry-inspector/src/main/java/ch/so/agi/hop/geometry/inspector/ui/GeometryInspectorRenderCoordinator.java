package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryInspectorClassLoaderSupport;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class GeometryInspectorRenderCoordinator<T> implements AutoCloseable {

  @FunctionalInterface
  interface RenderTask<T> {
    T render(long revision) throws Exception;
  }

  @FunctionalInterface
  interface RenderResultHandler<T> {
    void handle(long revision, T result);
  }

  @FunctionalInterface
  interface RenderErrorHandler {
    void handle(long revision, Throwable error);
  }

  private final ScheduledExecutorService executorService;
  private final Executor uiExecutor;
  private final AtomicLong latestRevision = new AtomicLong();
  private final AtomicReference<ScheduledFuture<?>> scheduledFuture = new AtomicReference<>();
  private volatile boolean closed;

  GeometryInspectorRenderCoordinator(String threadNamePrefix, Executor uiExecutor) {
    ThreadFactory threadFactory =
        GeometryInspectorClassLoaderSupport.newPluginContextThreadFactory(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setName(threadNamePrefix + "-" + THREAD_COUNTER.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    this.executorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
    this.uiExecutor = uiExecutor;
  }

  long schedule(
      long delayMillis,
      RenderTask<T> renderTask,
      RenderResultHandler<T> resultHandler,
      RenderErrorHandler errorHandler) {
    long revision = latestRevision.incrementAndGet();
    ScheduledFuture<?> previous = scheduledFuture.getAndSet(null);
    if (previous != null) {
      previous.cancel(true);
    }

    if (closed) {
      return revision;
    }

    ScheduledFuture<?> future =
        executorService.schedule(
            () -> {
              if (closed || revision != latestRevision.get()) {
                return;
              }
              try {
                T result =
                    GeometryInspectorClassLoaderSupport.withPluginContextClassLoader(
                        () -> renderTask.render(revision));
                if (closed || revision != latestRevision.get()) {
                  return;
                }
                uiExecutor.execute(
                    () -> {
                      if (!closed && revision == latestRevision.get()) {
                        resultHandler.handle(revision, result);
                      }
                    });
              } catch (Throwable throwable) {
                if (closed || revision != latestRevision.get()) {
                  return;
                }
                uiExecutor.execute(
                    () -> {
                      if (!closed && revision == latestRevision.get()) {
                        errorHandler.handle(revision, throwable);
                      }
                    });
              }
            },
            Math.max(0L, delayMillis),
            TimeUnit.MILLISECONDS);
    scheduledFuture.set(future);
    return revision;
  }

  void cancelPending() {
    latestRevision.incrementAndGet();
    ScheduledFuture<?> previous = scheduledFuture.getAndSet(null);
    if (previous != null) {
      previous.cancel(true);
    }
  }

  long latestRevision() {
    return latestRevision.get();
  }

  @Override
  public void close() {
    closed = true;
    cancelPending();
    executorService.shutdownNow();
  }

  private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
}
