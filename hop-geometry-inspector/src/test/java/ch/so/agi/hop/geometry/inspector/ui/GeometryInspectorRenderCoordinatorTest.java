package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GeometryInspectorRenderCoordinatorTest {

  @Test
  void discardsStaleResultsWhenNewerRevisionIsScheduled() throws Exception {
    try (GeometryInspectorRenderCoordinator<String> coordinator =
        new GeometryInspectorRenderCoordinator<>("test-render", Runnable::run)) {
      CountDownLatch firstStarted = new CountDownLatch(1);
      CountDownLatch releaseFirst = new CountDownLatch(1);
      CountDownLatch resultLatch = new CountDownLatch(1);
      CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();

      coordinator.schedule(
          0L,
          revision -> {
            firstStarted.countDown();
            releaseFirst.await(1, TimeUnit.SECONDS);
            return "first";
          },
          (revision, result) -> results.add(result),
          (revision, error) -> {
            throw new AssertionError(error);
          });

      assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

      coordinator.schedule(
          0L,
          revision -> "second",
          (revision, result) -> {
            results.add(result);
            resultLatch.countDown();
          },
          (revision, error) -> {
            throw new AssertionError(error);
          });

      releaseFirst.countDown();

      assertThat(resultLatch.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(results).containsExactly("second");
    }
  }

  @Test
  void cancelPendingAdvancesRevisionAndSuppressesCallbacks() throws Exception {
    try (GeometryInspectorRenderCoordinator<String> coordinator =
        new GeometryInspectorRenderCoordinator<>("test-render", Runnable::run)) {
      CountDownLatch callbackLatch = new CountDownLatch(1);

      coordinator.schedule(
          100L,
          revision -> "never",
          (revision, result) -> callbackLatch.countDown(),
          (revision, error) -> callbackLatch.countDown());
      coordinator.cancelPending();

      assertThat(callbackLatch.await(200, TimeUnit.MILLISECONDS)).isFalse();
    }
  }

  @Test
  void ignoresInterruptedOrCancelledRenderTasks() throws Exception {
    try (GeometryInspectorRenderCoordinator<String> coordinator =
        new GeometryInspectorRenderCoordinator<>("test-render", Runnable::run)) {
      CountDownLatch callbackLatch = new CountDownLatch(1);

      coordinator.schedule(
          0L,
          revision -> {
            throw new InterruptedException("cancelled");
          },
          (revision, result) -> callbackLatch.countDown(),
          (revision, error) -> callbackLatch.countDown());

      coordinator.schedule(
          0L,
          revision -> {
            throw new CancellationException("cancelled");
          },
          (revision, result) -> callbackLatch.countDown(),
          (revision, error) -> callbackLatch.countDown());

      assertThat(callbackLatch.await(200, TimeUnit.MILLISECONDS)).isFalse();
    }
  }
}
