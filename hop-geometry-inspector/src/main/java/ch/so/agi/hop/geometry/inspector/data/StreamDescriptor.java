package ch.so.agi.hop.geometry.inspector.data;

public record StreamDescriptor(
    String transform, Direction direction, String peer, Kind kind, int copy) {
  public enum Direction {
    INPUT,
    OUTPUT
  }

  public enum Kind {
    MAIN,
    TARGET,
    ERROR
  }

  public String label() {
    return transform
        + " ["
        + copy
        + "] "
        + direction
        + " "
        + kind
        + (peer.isBlank() ? "" : " → " + peer);
  }
}
