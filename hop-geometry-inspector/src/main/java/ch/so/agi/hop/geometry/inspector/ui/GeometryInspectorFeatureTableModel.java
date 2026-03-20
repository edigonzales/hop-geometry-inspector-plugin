package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;

final class GeometryInspectorFeatureTableModel {

  static final int MAX_HIT_LABEL_FIELDS = 2;
  static final int MAX_HIT_LABEL_LENGTH = 120;
  static final int MAX_CELL_VALUE_LENGTH = 180;

  private final List<Column> columns;
  private final List<Entry> entries;
  private final Map<Integer, Integer> tableIndexByRowIndex;
  private final Map<String, Integer> tableIndexByFeatureId;
  private final Map<SimpleFeature, Integer> tableIndexByFeatureReference;

  GeometryInspectorFeatureTableModel(
      SamplingResult samplingResult, GeometryBuildResult buildResult, String geometryField) {
    IRowMeta rowMeta = samplingResult == null ? null : samplingResult.rowMeta();
    List<Column> nextColumns = List.copyOf(buildColumns(rowMeta));
    List<Entry> nextEntries = List.copyOf(buildEntries(samplingResult, buildResult, rowMeta, geometryField));
    this.columns = nextColumns;
    this.entries = nextEntries;
    this.tableIndexByRowIndex = buildIndex(nextEntries);
    this.tableIndexByFeatureId = buildFeatureIdIndex(nextEntries);
    this.tableIndexByFeatureReference = buildFeatureReferenceIndex(nextEntries);
  }

  private GeometryInspectorFeatureTableModel(List<Column> columns, List<Entry> entries) {
    List<Column> nextColumns = columns == null ? List.of() : List.copyOf(columns);
    List<Entry> nextEntries = entries == null ? List.of() : List.copyOf(entries);
    this.columns = nextColumns;
    this.entries = nextEntries;
    this.tableIndexByRowIndex = buildIndex(nextEntries);
    this.tableIndexByFeatureId = buildFeatureIdIndex(nextEntries);
    this.tableIndexByFeatureReference = buildFeatureReferenceIndex(nextEntries);
  }

  GeometryInspectorFeatureTableModel sortedByColumn(int columnIndex, boolean ascending) {
    if (entries.isEmpty()) {
      return new GeometryInspectorFeatureTableModel(columns, entries);
    }
    int normalizedColumnIndex = normalizeColumnIndex(columnIndex);
    Comparator<Entry> comparator = comparatorForColumn(normalizedColumnIndex);
    if (!ascending) {
      comparator = comparator.reversed();
    }
    comparator = comparator.thenComparingInt(Entry::rowIndex);
    List<Entry> sorted = new ArrayList<>(entries);
    sorted.sort(comparator);
    return new GeometryInspectorFeatureTableModel(columns, sorted);
  }

  int normalizeColumnIndex(int columnIndex) {
    if (columns.isEmpty()) {
      return -1;
    }
    if (columnIndex < 0 || columnIndex >= columns.size()) {
      return 0;
    }
    return columnIndex;
  }

  private Comparator<Entry> comparatorForColumn(int columnIndex) {
    if (columnIndex <= 0) {
      return Comparator.comparingInt(Entry::rowIndex);
    }
    return Comparator.comparing(
        entry -> entry.clipboardCellValueAt(columnIndex).toLowerCase(Locale.ROOT));
  }

  int size() {
    return entries.size();
  }

  int columnCount() {
    return columns.size();
  }

  List<Column> columns() {
    return columns;
  }

  Column columnAt(int index) {
    return columns.get(index);
  }

  Entry entryAt(int index) {
    return entries.get(index);
  }

  Entry entryForFeature(SimpleFeature feature) {
    int tableIndex = indexOfFeature(feature);
    return tableIndex < 0 ? null : entries.get(tableIndex);
  }

  Entry entryForRow(int rowIndex) {
    Integer tableIndex = tableIndexByRowIndex.get(rowIndex);
    return tableIndex == null ? null : entries.get(tableIndex);
  }

  int indexOfRow(int rowIndex) {
    return tableIndexByRowIndex.getOrDefault(rowIndex, -1);
  }

  int indexOfFeature(SimpleFeature feature) {
    if (feature == null) {
      return -1;
    }
    String featureId = feature.getID();
    if (featureId != null && !featureId.isBlank()) {
      Integer byId = tableIndexByFeatureId.get(featureId);
      if (byId != null) {
        return byId;
      }
    }
    Integer byReference = tableIndexByFeatureReference.get(feature);
    return byReference == null ? -1 : byReference;
  }

  List<Entry> entries() {
    return entries;
  }

  private List<Column> buildColumns(IRowMeta rowMeta) {
    List<Column> nextColumns = new ArrayList<>();
    nextColumns.add(new Column("Row", null));
    if (rowMeta == null) {
      return nextColumns;
    }
    for (int index = 0; index < rowMeta.size(); index++) {
      nextColumns.add(new Column(rowMeta.getValueMeta(index).getName(), index));
    }
    return nextColumns;
  }

  private List<Entry> buildEntries(
      SamplingResult samplingResult,
      GeometryBuildResult buildResult,
      IRowMeta rowMeta,
      String geometryField) {
    if (samplingResult == null || buildResult == null || buildResult.features().isEmpty()) {
      return List.of();
    }

    int geometryFieldIndex =
        rowMeta == null || geometryField == null || geometryField.isBlank()
            ? -1
            : rowMeta.indexOfValue(geometryField);

    List<Entry> nextEntries = new ArrayList<>(buildResult.features().size());
    for (SimpleFeature feature : buildResult.features()) {
      if (feature == null) {
        continue;
      }
      int rowIndex = rowIndexOf(feature);
      Geometry geometry = feature.getDefaultGeometry() instanceof Geometry current ? current : null;
      Object[] row =
          rowIndex >= 0 && rowIndex < samplingResult.rows().size()
              ? samplingResult.rows().get(rowIndex)
              : new Object[0];
      List<String> displayCellValues = buildCellValues(rowMeta, rowIndex, row, true);
      List<String> clipboardCellValues = buildCellValues(rowMeta, rowIndex, row, false);
      nextEntries.add(
          new Entry(
              feature,
              rowIndex,
              displayCellValues,
              clipboardCellValues,
              buildHitLabel(rowMeta, row, geometryFieldIndex, geometry, rowIndex)));
    }

    nextEntries.sort(Comparator.comparingInt(Entry::rowIndex));
    return nextEntries;
  }

  private Map<Integer, Integer> buildIndex(List<Entry> nextEntries) {
    Map<Integer, Integer> index = new LinkedHashMap<>();
    for (int tableIndex = 0; tableIndex < nextEntries.size(); tableIndex++) {
      index.put(nextEntries.get(tableIndex).rowIndex(), tableIndex);
    }
    return Map.copyOf(index);
  }

  private Map<String, Integer> buildFeatureIdIndex(List<Entry> nextEntries) {
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int tableIndex = 0; tableIndex < nextEntries.size(); tableIndex++) {
      String featureId = nextEntries.get(tableIndex).feature().getID();
      if (featureId == null || featureId.isBlank()) {
        continue;
      }
      index.putIfAbsent(featureId, tableIndex);
    }
    return Map.copyOf(index);
  }

  private Map<SimpleFeature, Integer> buildFeatureReferenceIndex(List<Entry> nextEntries) {
    Map<SimpleFeature, Integer> index = new IdentityHashMap<>();
    for (int tableIndex = 0; tableIndex < nextEntries.size(); tableIndex++) {
      index.put(nextEntries.get(tableIndex).feature(), tableIndex);
    }
    return index;
  }

  private List<String> buildCellValues(
      IRowMeta rowMeta, int rowIndex, Object[] row, boolean abbreviateValues) {
    int capacity = rowMeta == null ? 1 : rowMeta.size() + 1;
    List<String> values = new ArrayList<>(capacity);
    values.add(String.valueOf(rowIndex));
    if (rowMeta == null) {
      return values;
    }
    for (int index = 0; index < rowMeta.size(); index++) {
      IValueMeta valueMeta = rowMeta.getValueMeta(index);
      Object value = row.length > index ? row[index] : null;
      String formatted = formatValue(valueMeta, value);
      values.add(abbreviateValues ? abbreviate(formatted, MAX_CELL_VALUE_LENGTH) : formatted);
    }
    return values;
  }

  private String buildHitLabel(
      IRowMeta rowMeta, Object[] row, int geometryFieldIndex, Geometry geometry, int rowIndex) {
    String prefix = "Row " + rowIndex;
    if (rowMeta != null) {
      List<String> parts = new ArrayList<>(MAX_HIT_LABEL_FIELDS);
      for (int index = 0; index < rowMeta.size() && parts.size() < MAX_HIT_LABEL_FIELDS; index++) {
        if (index == geometryFieldIndex) {
          continue;
        }
        IValueMeta valueMeta = rowMeta.getValueMeta(index);
        Object value = row.length > index ? row[index] : null;
        String formatted = formatValue(valueMeta, value);
        if (formatted == null || formatted.isBlank() || "null".equals(formatted)) {
          continue;
        }
        parts.add(valueMeta.getName() + "=" + formatted);
      }
      if (!parts.isEmpty()) {
        return abbreviate(prefix + " | " + String.join(" | ", parts), MAX_HIT_LABEL_LENGTH);
      }

      if (geometryFieldIndex >= 0) {
        Object geometryValue = row.length > geometryFieldIndex ? row[geometryFieldIndex] : null;
        String formatted = formatValue(rowMeta.getValueMeta(geometryFieldIndex), geometryValue);
        if (formatted != null && !formatted.isBlank() && !"null".equals(formatted)) {
          return abbreviate(prefix + " | " + formatted, MAX_HIT_LABEL_LENGTH);
        }
      }
    }

    if (geometry == null) {
      return prefix;
    }
    String fallback =
        geometry.getSRID() > 0
            ? "SRID=" + geometry.getSRID() + ";" + geometry.toText()
            : geometry.toText();
    return abbreviate(prefix + " | " + fallback, MAX_HIT_LABEL_LENGTH);
  }

  private String formatValue(IValueMeta valueMeta, Object value) {
    if (value == null) {
      return "null";
    }

    try {
      String formatted = valueMeta == null ? null : valueMeta.getString(value);
      if (formatted != null) {
        return formatted;
      }
    } catch (Exception e) {
      // Fall through to generic formatting.
    }

    if (value instanceof byte[] bytes) {
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format(Locale.ROOT, "%02x", current));
      }
      return builder.toString();
    }
    return String.valueOf(value);
  }

  private String abbreviate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value == null ? "" : value;
    }
    return value.substring(0, Math.max(0, maxLength - 3)) + "...";
  }

  static int rowIndexOf(SimpleFeature feature) {
    if (feature == null) {
      return -1;
    }
    Object value = feature.getAttribute("row_index");
    if (value instanceof Number number) {
      return number.intValue();
    }
    return -1;
  }

  record Column(String label, Integer rowMetaIndex) {

    boolean isRowIndex() {
      return rowMetaIndex == null;
    }
  }

  record Entry(
      SimpleFeature feature,
      int rowIndex,
      List<String> cellValues,
      List<String> clipboardCellValues,
      String popupLabel) {

    String cellValueAt(int columnIndex) {
      if (columnIndex < 0 || columnIndex >= cellValues.size()) {
        return "";
      }
      return cellValues.get(columnIndex);
    }

    String clipboardCellValueAt(int columnIndex) {
      if (columnIndex < 0 || columnIndex >= clipboardCellValues.size()) {
        return "";
      }
      return clipboardCellValues.get(columnIndex);
    }

    String hitLabel() {
      return popupLabel == null || popupLabel.isBlank() ? "Row " + rowIndex : popupLabel;
    }
  }
}
