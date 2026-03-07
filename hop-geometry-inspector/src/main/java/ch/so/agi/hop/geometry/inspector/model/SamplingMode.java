package ch.so.agi.hop.geometry.inspector.model;

public enum SamplingMode {
  FIRST,
  LAST,
  RANDOM;

  public static SamplingMode fromLabel(String label) {
    if (label == null || label.isBlank()) {
      return FIRST;
    }
    return SamplingMode.valueOf(label.trim().toUpperCase());
  }
}
