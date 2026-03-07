package ch.so.agi.hop.geometry.inspector.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.hop.core.row.IRowMeta;

public record SamplingResult(List<Object[]> rows, IRowMeta rowMeta, boolean partial, String reason) {

  public SamplingResult {
    rows = rows == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(rows));
    reason = reason == null ? "" : reason;
  }
}
