package ch.so.agi.hop.geometry.inspector.ui;

import java.util.LinkedHashMap;
import java.util.Map;

final class GeometryInspectorFrameCache<K, V extends GeometryInspectorDisposableResource> {

  private final int maxEntries;
  private final LinkedHashMap<K, V> entries;

  GeometryInspectorFrameCache(int maxEntries) {
    this.maxEntries = Math.max(1, maxEntries);
    this.entries = new LinkedHashMap<>(16, 0.75f, true);
  }

  V get(K key) {
    return entries.get(key);
  }

  void put(K key, V value) {
    V previous = entries.put(key, value);
    if (previous != null && previous != value) {
      previous.dispose();
    }

    while (entries.size() > maxEntries) {
      Map.Entry<K, V> eldest = entries.entrySet().iterator().next();
      V evicted = eldest.getValue();
      entries.remove(eldest.getKey());
      if (evicted != null && evicted != value) {
        evicted.dispose();
      }
    }
  }

  void clear() {
    for (V value : entries.values()) {
      if (value != null) {
        value.dispose();
      }
    }
    entries.clear();
  }

  int size() {
    return entries.size();
  }
}
