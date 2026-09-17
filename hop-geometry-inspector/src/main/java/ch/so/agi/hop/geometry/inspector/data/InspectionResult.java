package ch.so.agi.hop.geometry.inspector.data;

import java.time.Instant;
import java.util.UUID;

public record InspectionResult(
    UUID id,
    UUID generation,
    String pipeline,
    StreamDescriptor stream,
    Instant capturedAt,
    RowStore rows,
    boolean sample,
    Completion completion,
    String reason,
    String fingerprint) {
  public enum Completion {
    RUNNING,
    COMPLETE,
    PARTIAL,
    FAILED
  }

  public boolean replayable(String currentFingerprint) {
    return !sample && completion == Completion.COMPLETE && fingerprint.equals(currentFingerprint);
  }

  public record RowId(UUID result, long ordinal) {}
}
