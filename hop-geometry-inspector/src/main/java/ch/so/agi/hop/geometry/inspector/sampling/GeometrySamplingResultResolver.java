package ch.so.agi.hop.geometry.inspector.sampling;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.util.List;
import org.apache.hop.core.row.IRowMeta;

final class GeometrySamplingResultResolver {

  SamplingResult resolve(
      GeometryInspectionSide requestedSide,
      List<Object[]> outputRows,
      IRowMeta outputRowMeta,
      List<Object[]> inputRows,
      IRowMeta inputRowMeta,
      boolean partial,
      String reason) {

    GeometryInspectionSide requested =
        requestedSide == null ? GeometryInspectionSide.AUTO : requestedSide;

    return switch (requested) {
      case OUTPUT ->
          new SamplingResult(
              outputRows,
              outputRowMeta,
              partial,
              reason,
              requested,
              GeometryInspectionSide.OUTPUT,
              false,
              outputRows.isEmpty()
                  ? "Selected source=Output, but target emitted no output rows."
                  : "Inspecting output rows.");
      case INPUT ->
          new SamplingResult(
              inputRows,
              inputRowMeta,
              partial,
              reason,
              requested,
              GeometryInspectionSide.INPUT,
              false,
              inputRows.isEmpty()
                  ? "Selected source=Input, but target emitted no input rows."
                  : "Inspecting input rows.");
      case AUTO -> resolveAuto(outputRows, outputRowMeta, inputRows, inputRowMeta, partial, reason);
    };
  }

  private SamplingResult resolveAuto(
      List<Object[]> outputRows,
      IRowMeta outputRowMeta,
      List<Object[]> inputRows,
      IRowMeta inputRowMeta,
      boolean partial,
      String reason) {
    if (!outputRows.isEmpty()) {
      return new SamplingResult(
          outputRows,
          outputRowMeta,
          partial,
          reason,
          GeometryInspectionSide.AUTO,
          GeometryInspectionSide.OUTPUT,
          false,
          "Inspecting output rows.");
    }

    if (!inputRows.isEmpty()) {
      return new SamplingResult(
          inputRows,
          inputRowMeta,
          partial,
          reason,
          GeometryInspectionSide.AUTO,
          GeometryInspectionSide.INPUT,
          true,
          "Auto-switched to input because no output rows were emitted by the target transform.");
    }

    return new SamplingResult(
        List.of(),
        null,
        partial,
        reason,
        GeometryInspectionSide.AUTO,
        null,
        false,
        "No sampled rows observed.");
  }
}
