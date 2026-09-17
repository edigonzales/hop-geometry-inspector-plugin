package ch.so.agi.hop.geometry.inspector.data;

import java.io.IOException;
import org.apache.hop.core.row.IRowMeta;

/** Long-addressed immutable row access. Readers own their store lifetime. */
public interface RowStore extends AutoCloseable {
  IRowMeta rowMeta();

  long size();

  Object[] row(long ordinal) throws IOException;

  @Override
  default void close() throws IOException {}
}
