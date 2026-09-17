package ch.so.agi.hop.geometry.inspector.data;

import java.util.List;
import org.apache.hop.core.row.IRowMeta;

public final class MemoryRowStore implements RowStore {
  private final IRowMeta meta;
  private final List<Object[]> rows;

  public MemoryRowStore(IRowMeta meta, List<Object[]> rows) {
    this.meta = meta == null ? new org.apache.hop.core.row.RowMeta() : meta.clone();
    this.rows =
        rows.stream()
            .map(
                r -> {
                  try {
                    return meta.cloneRow(r);
                  } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                  }
                })
            .toList();
  }

  public IRowMeta rowMeta() {
    return meta;
  }

  public long size() {
    return rows.size();
  }

  public Object[] row(long index) {
    try {
      return meta.cloneRow(rows.get(Math.toIntExact(index)));
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }
}
