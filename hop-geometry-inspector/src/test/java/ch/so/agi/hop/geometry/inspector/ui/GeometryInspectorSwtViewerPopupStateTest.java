package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.GeometryFeatureBuilder;
import ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.util.List;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.junit.jupiter.api.Test;

class GeometryInspectorSwtViewerPopupStateTest {

  private final GeometryFeatureBuilder featureBuilder = new GeometryFeatureBuilder();

  @Test
  void shouldIgnorePreviewRowIsNullSafe() {
    assertThat(GeometryInspectorSwtViewer.shouldIgnorePreviewRow(7, null)).isFalse();
    assertThat(GeometryInspectorSwtViewer.shouldIgnorePreviewRow(-1, null)).isTrue();
    assertThat(GeometryInspectorSwtViewer.shouldIgnorePreviewRow(7, 7)).isTrue();
    assertThat(GeometryInspectorSwtViewer.shouldIgnorePreviewRow(7, 8)).isFalse();
  }

  @Test
  void shouldProcessMapMouseUpOnlyWhenDragSessionIsActive() {
    assertThat(GeometryInspectorSwtViewer.shouldProcessMapMouseUp(1, true)).isTrue();
    assertThat(GeometryInspectorSwtViewer.shouldProcessMapMouseUp(1, false)).isFalse();
    assertThat(GeometryInspectorSwtViewer.shouldProcessMapMouseUp(3, true)).isFalse();
  }

  @Test
  void shouldRequestPopupMenuVisibilityCloseOnlyForVisibleMenus() {
    assertThat(GeometryInspectorSwtViewer.shouldRequestPopupMenuVisibilityClose(true)).isTrue();
    assertThat(GeometryInspectorSwtViewer.shouldRequestPopupMenuVisibilityClose(false)).isFalse();
  }

  @Test
  void resolveFeatureTableSelectionIndexReturnsNoSelectionWhenNeitherKeyMatches() {
    GeometryInspectorFeatureTableModel model = testModel();

    int selectionIndex =
        GeometryInspectorSwtViewer.resolveFeatureTableSelectionIndex(model, -1, null);

    assertThat(selectionIndex).isEqualTo(-1);
  }

  @Test
  void resolveFeatureTableSelectionIndexUsesRowIndexBeforeFeatureFallback() {
    GeometryInspectorFeatureTableModel model = testModel();
    int selectionIndex =
        GeometryInspectorSwtViewer.resolveFeatureTableSelectionIndex(
            model, 1, model.entryAt(0).feature());

    assertThat(selectionIndex).isEqualTo(1);
  }

  @Test
  void resolveFeatureTableSelectionIndexFallsBackToFeatureIdentity() {
    GeometryInspectorFeatureTableModel model = testModel();
    SimpleFeature originalFeature = model.entryAt(0).feature();

    SimpleFeatureBuilder builder = new SimpleFeatureBuilder(originalFeature.getFeatureType());
    for (int index = 0; index < originalFeature.getFeatureType().getAttributeCount(); index++) {
      String attributeName = originalFeature.getFeatureType().getDescriptor(index).getLocalName();
      Object value = "row_index".equals(attributeName) ? -1 : originalFeature.getAttribute(index);
      builder.add(value);
    }
    SimpleFeature sameIdDifferentRowIndex = builder.buildFeature(originalFeature.getID());

    int selectionIndex =
        GeometryInspectorSwtViewer.resolveFeatureTableSelectionIndex(
            model, -1, sameIdDifferentRowIndex);

    assertThat(selectionIndex).isEqualTo(0);
  }

  @Test
  void formatFeatureTableSelectionForClipboardUsesTabSeparatedCellValues() {
    GeometryInspectorFeatureTableModel model = testModel();

    String clipboardContent =
        GeometryInspectorSwtViewer.formatFeatureTableSelectionForClipboard(model, new int[] {1, 0});

    assertThat(clipboardContent)
        .isEqualTo(
            "0\talpha\tPOINT (1 2)"
                + System.lineSeparator()
                + "1\tbeta\tPOINT (3 4)");
  }

  @Test
  void formatFeatureTableSelectionForClipboardUsesFullValuesWithoutEllipsis() {
    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("name"));
    rowMeta.addValueMeta(new ValueMetaString("geom_wkt"));
    String longName = "x".repeat(GeometryInspectorFeatureTableModel.MAX_CELL_VALUE_LENGTH + 24);
    List<Object[]> rows = List.<Object[]>of(new Object[] {longName, "POINT (1 2)"});
    GeometryBuildResult buildResult = featureBuilder.build(rowMeta, rows, "geom_wkt");
    GeometryInspectorFeatureTableModel model =
        new GeometryInspectorFeatureTableModel(
            samplingResult(rows, rowMeta), buildResult, "geom_wkt");

    String clipboardContent =
        GeometryInspectorSwtViewer.formatFeatureTableSelectionForClipboard(model, new int[] {0});

    assertThat(clipboardContent).isEqualTo("0\t" + longName + "\tPOINT (1 2)");
    assertThat(clipboardContent).doesNotContain("...");
  }

  @Test
  void formatAttributeSelectionForClipboardUsesFullValuesWithoutEllipsis() {
    String longValue = "v".repeat(GeometryInspectorFeatureTableModel.MAX_CELL_VALUE_LENGTH + 32);

    String clipboardContent =
        GeometryInspectorSwtViewer.formatAttributeSelectionForClipboard(
            List.<String[]>of(new String[] {"name", longValue}), new int[] {0});

    assertThat(clipboardContent).isEqualTo("name\t" + longValue);
    assertThat(clipboardContent).doesNotContain("...");
  }

  @Test
  void formatAttributeSelectionForClipboardReturnsEmptyWhenSelectionIsMissing() {
    String clipboardContent =
        GeometryInspectorSwtViewer.formatAttributeSelectionForClipboard(
            List.<String[]>of(new String[] {"name", "value"}), new int[0]);

    assertThat(clipboardContent).isEmpty();
  }

  @Test
  void formatFeatureTableSelectionForClipboardReturnsEmptyWhenSelectionIsMissing() {
    GeometryInspectorFeatureTableModel model = testModel();

    String clipboardContent =
        GeometryInspectorSwtViewer.formatFeatureTableSelectionForClipboard(model, new int[0]);

    assertThat(clipboardContent).isEmpty();
  }

  private GeometryInspectorFeatureTableModel testModel() {
    return testModel(
        List.<Object[]>of(
            new Object[] {"alpha", "POINT (1 2)"},
            new Object[] {"beta", "POINT (3 4)"}));
  }

  private GeometryInspectorFeatureTableModel testModel(List<Object[]> rows) {
    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("name"));
    rowMeta.addValueMeta(new ValueMetaString("geom_wkt"));
    GeometryBuildResult buildResult = featureBuilder.build(rowMeta, rows, "geom_wkt");
    return new GeometryInspectorFeatureTableModel(samplingResult(rows, rowMeta), buildResult, "geom_wkt");
  }

  private SamplingResult samplingResult(List<Object[]> rows, RowMeta rowMeta) {
    SamplingResult samplingResult =
        new SamplingResult(
            rows,
            rowMeta,
            false,
            "",
            GeometryInspectionSide.AUTO,
            GeometryInspectionSide.OUTPUT,
            false,
            "");
    return samplingResult;
  }
}
