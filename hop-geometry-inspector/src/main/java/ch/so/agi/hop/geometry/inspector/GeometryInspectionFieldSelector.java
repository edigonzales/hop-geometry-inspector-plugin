package ch.so.agi.hop.geometry.inspector;

import ch.so.agi.hop.geometry.inspector.model.GeometryFieldCandidate;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.hop.core.row.IRowMeta;

public class GeometryInspectionFieldSelector {

  private final GeometryFieldDetector geometryFieldDetector;

  public GeometryInspectionFieldSelector() {
    this(new GeometryFieldDetector());
  }

  GeometryInspectionFieldSelector(GeometryFieldDetector geometryFieldDetector) {
    this.geometryFieldDetector = geometryFieldDetector;
  }

  public GeometryInspectionFieldSelection resolve(IRowMeta rowMeta, String preferredField) {
    List<GeometryFieldCandidate> candidates = geometryFieldDetector.detectCandidates(rowMeta);
    List<String> geometryFields =
        candidates.stream().map(GeometryFieldCandidate::fieldName).collect(Collectors.toList());

    if (geometryFields.isEmpty()) {
      return new GeometryInspectionFieldSelection(List.of(), null, "");
    }

    if (preferredField != null && geometryFields.contains(preferredField)) {
      return new GeometryInspectionFieldSelection(geometryFields, preferredField, "");
    }

    String fallbackField = geometryFieldDetector.chooseDefaultField(candidates);
    if (preferredField != null && !preferredField.isBlank() && fallbackField != null) {
      return new GeometryInspectionFieldSelection(
          geometryFields,
          fallbackField,
          "Geometry field auto-adjusted to '"
              + fallbackField
              + "' because '"
              + preferredField
              + "' is not available on the inspected side.");
    }

    return new GeometryInspectionFieldSelection(geometryFields, fallbackField, "");
  }
}
