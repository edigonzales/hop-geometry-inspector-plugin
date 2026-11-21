package ch.so.agi.hop.geometry.inspector.data;

import java.time.Instant;

public record InspectionResult(
    String pipeline,
    StreamDescriptor stream,
    Instant capturedAt,
    RowStore rows,
    boolean sample,
    Completion completion,
    String reason) {
  public enum Completion {
    COMPLETE,
    PARTIAL
  }
}
