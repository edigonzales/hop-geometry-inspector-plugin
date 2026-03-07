package ch.so.agi.hop.geometry.inspector.model;

public enum GeometryInspectionSide {
  AUTO("Auto"),
  OUTPUT("Output"),
  INPUT("Input");

  private final String label;

  GeometryInspectionSide(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public String rowsLabel() {
    return switch (this) {
      case AUTO -> "auto";
      case OUTPUT -> "output rows";
      case INPUT -> "input rows";
    };
  }

  public static GeometryInspectionSide fromLabel(String label) {
    if (label == null || label.isBlank()) {
      return AUTO;
    }

    for (GeometryInspectionSide side : values()) {
      if (side.label.equalsIgnoreCase(label.trim())
          || side.name().equalsIgnoreCase(label.trim())) {
        return side;
      }
    }
    return AUTO;
  }
}
