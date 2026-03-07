package ch.so.agi.hop.geometry.inspector;

import java.util.List;

public record GeometryInspectionFieldSelection(
    List<String> geometryFields, String selectedField, String message) {}
