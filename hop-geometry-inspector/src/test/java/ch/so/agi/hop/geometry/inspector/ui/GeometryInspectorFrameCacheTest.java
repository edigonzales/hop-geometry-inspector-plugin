package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeometryInspectorFrameCacheTest {

  @Test
  void evictsLeastRecentlyUsedEntryAndDisposesIt() {
    GeometryInspectorFrameCache<String, TestResource> cache = new GeometryInspectorFrameCache<>(2);
    TestResource first = new TestResource();
    TestResource second = new TestResource();
    TestResource third = new TestResource();

    cache.put("first", first);
    cache.put("second", second);
    assertThat(cache.get("first")).isSameAs(first);
    cache.put("third", third);

    assertThat(first.disposed).isFalse();
    assertThat(second.disposed).isTrue();
    assertThat(cache.get("second")).isNull();
    assertThat(cache.size()).isEqualTo(2);
  }

  @Test
  void clearDisposesAllEntries() {
    GeometryInspectorFrameCache<String, TestResource> cache = new GeometryInspectorFrameCache<>(2);
    TestResource first = new TestResource();
    TestResource second = new TestResource();

    cache.put("first", first);
    cache.put("second", second);
    cache.clear();

    assertThat(first.disposed).isTrue();
    assertThat(second.disposed).isTrue();
    assertThat(cache.size()).isZero();
  }

  private static final class TestResource implements GeometryInspectorDisposableResource {
    private boolean disposed;

    @Override
    public void dispose() {
      disposed = true;
    }
  }
}
