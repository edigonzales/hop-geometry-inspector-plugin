package ch.so.agi.hop.geometry.inspector.data;

import java.io.*;
import java.util.AbstractList;

/** Read-only adapter for the existing viewer; no eager copy of disk data. */
public final class StoreRowList extends AbstractList<Object[]> {
  private final RowStore store;

  public StoreRowList(RowStore store) {
    this.store = store;
    if (store.size() > Integer.MAX_VALUE)
      throw new IllegalArgumentException(
          "This viewer supports at most 2,147,483,647 rows per stream");
  }

  public RowStore store() {
    return store;
  }

  public int size() {
    return Math.toIntExact(store.size());
  }

  public Object[] get(int index) {
    try {
      return store.row(index);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
