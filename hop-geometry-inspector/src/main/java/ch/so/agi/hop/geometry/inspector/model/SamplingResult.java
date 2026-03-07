package ch.so.agi.hop.geometry.inspector.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.hop.core.row.IRowMeta;

public record SamplingResult(
    List<Object[]> rows,
    IRowMeta rowMeta,
    boolean partial,
    String reason,
    GeometryInspectionSide requestedSide,
    GeometryInspectionSide effectiveSide,
    boolean autoSwitched,
    String sideResolutionMessage) {

  public SamplingResult {
    rows = rows == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(rows));
    reason = reason == null ? "" : reason;
    requestedSide = requestedSide == null ? GeometryInspectionSide.AUTO : requestedSide;
    sideResolutionMessage = sideResolutionMessage == null ? "" : sideResolutionMessage;
  }

  public boolean hasRows() {
    return !rows.isEmpty();
  }

  public SamplingResult withSideResolutionMessage(String message) {
    return new SamplingResult(
        rows, rowMeta, partial, reason, requestedSide, effectiveSide, autoSwitched, message);
  }

  public SamplingResult appendSideResolutionMessage(String additionalMessage) {
    if (additionalMessage == null || additionalMessage.isBlank()) {
      return this;
    }
    if (sideResolutionMessage.isBlank()) {
      return withSideResolutionMessage(additionalMessage);
    }
    return withSideResolutionMessage(sideResolutionMessage + " " + additionalMessage);
  }
}
