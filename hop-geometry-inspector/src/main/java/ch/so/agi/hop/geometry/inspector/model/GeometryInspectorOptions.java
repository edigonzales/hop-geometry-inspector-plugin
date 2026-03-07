package ch.so.agi.hop.geometry.inspector.model;

import java.time.Duration;

public record GeometryInspectorOptions(
    int sampleSize, SamplingMode mode, String geometryField, Duration timeout) {

  public GeometryInspectorOptions {
    if (sampleSize <= 0) {
      throw new IllegalArgumentException("sampleSize must be > 0");
    }
    if (mode == null) {
      throw new IllegalArgumentException("mode must not be null");
    }
    if (geometryField == null || geometryField.isBlank()) {
      throw new IllegalArgumentException("geometryField must not be blank");
    }
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be > 0");
    }
  }
}
