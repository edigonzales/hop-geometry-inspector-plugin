package ch.so.agi.hop.geometry.inspector.model;

public record GeometryFieldCandidate(
    String fieldName, int index, boolean geometryValueMeta, boolean heuristicMatch) {}
