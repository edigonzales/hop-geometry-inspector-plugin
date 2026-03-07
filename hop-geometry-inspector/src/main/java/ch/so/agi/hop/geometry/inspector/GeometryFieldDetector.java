package ch.so.agi.hop.geometry.inspector;

import ch.so.agi.hop.geometry.inspector.model.GeometryFieldCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;

public class GeometryFieldDetector {

  private static final Pattern HEURISTIC_NAME_PATTERN =
      Pattern.compile(".*(geom|geometry|wkt|wkb).*", Pattern.CASE_INSENSITIVE);

  public List<GeometryFieldCandidate> detectCandidates(IRowMeta rowMeta) {
    List<GeometryFieldCandidate> candidates = new ArrayList<>();
    if (rowMeta == null) {
      return candidates;
    }

    for (int i = 0; i < rowMeta.size(); i++) {
      IValueMeta valueMeta = rowMeta.getValueMeta(i);
      boolean geometryByMeta = isGeometryValueMeta(valueMeta);
      boolean heuristic = isHeuristicGeometryCandidate(valueMeta);

      if (geometryByMeta || heuristic) {
        candidates.add(
            new GeometryFieldCandidate(valueMeta.getName(), i, geometryByMeta, heuristic));
      }
    }

    candidates.sort(
        Comparator.comparing(GeometryFieldCandidate::geometryValueMeta)
            .reversed()
            .thenComparingInt(GeometryFieldCandidate::index));

    return candidates;
  }

  public String chooseDefaultField(List<GeometryFieldCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    for (GeometryFieldCandidate candidate : candidates) {
      if (candidate.geometryValueMeta()) {
        return candidate.fieldName();
      }
    }
    return candidates.get(0).fieldName();
  }

  private boolean isHeuristicGeometryCandidate(IValueMeta valueMeta) {
    if (valueMeta == null) {
      return false;
    }

    if (valueMeta.getType() != IValueMeta.TYPE_STRING && valueMeta.getType() != IValueMeta.TYPE_BINARY) {
      return false;
    }

    return HEURISTIC_NAME_PATTERN.matcher(valueMeta.getName()).matches();
  }

  private boolean isGeometryValueMeta(IValueMeta valueMeta) {
    if (valueMeta == null) {
      return false;
    }

    String typeDesc = safeLower(valueMeta.getTypeDesc());
    if (typeDesc.contains("geometry")) {
      return true;
    }

    String className = safeLower(valueMeta.getClass().getName());
    return className.contains("valuemetageometry");
  }

  private String safeLower(String text) {
    return text == null ? "" : text.toLowerCase(Locale.ROOT);
  }
}
