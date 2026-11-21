package ch.so.agi.hop.geometry.inspector.model;

import ch.so.agi.hop.geometry.inspector.data.StoreRowList;
import java.util.List;
import org.apache.hop.core.row.IRowMeta;

public record SamplingResult(
    List<Object[]> rows, IRowMeta rowMeta, boolean partial, String reason, String streamLabel) {
  public SamplingResult {
    if (!(rows instanceof StoreRowList)) rows = List.copyOf(rows);
  }
}
