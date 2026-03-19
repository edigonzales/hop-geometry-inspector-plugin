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

class GeometryInspectorFeatureTableModelTest {

  private final GeometryFeatureBuilder featureBuilder = new GeometryFeatureBuilder();

  @Test
  void buildsEntriesForRenderableFeaturesWithRowAndAllSampleAttributes() {
    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("id"));
    rowMeta.addValueMeta(new ValueMetaString("name"));
    rowMeta.addValueMeta(new ValueMetaString("geom_wkt"));
    rowMeta.addValueMeta(new ValueMetaString("status"));

    List<Object[]> rows =
        List.of(
            new Object[] {"A-001", "Main street", "POINT (1 2)", "open"},
            new Object[] {"B-002", "Broken", "this is invalid", "closed"},
            new Object[] {null, null, null, null},
            new Object[] {"C-003", "Side road", "LINESTRING (0 0, 2 2)", "review"});

    GeometryBuildResult buildResult = featureBuilder.build(rowMeta, rows, "geom_wkt");
    GeometryInspectorFeatureTableModel model =
        new GeometryInspectorFeatureTableModel(
            samplingResult(rows, rowMeta), buildResult, "geom_wkt");

    assertThat(model.size()).isEqualTo(2);
    assertThat(model.columnCount()).isEqualTo(5);
    assertThat(model.columnAt(0).label()).isEqualTo("Row");
    assertThat(model.columnAt(1).label()).isEqualTo("id");
    assertThat(model.columnAt(2).label()).isEqualTo("name");
    assertThat(model.columnAt(3).label()).isEqualTo("geom_wkt");
    assertThat(model.columnAt(4).label()).isEqualTo("status");
    assertThat(model.entryAt(0).rowIndex()).isEqualTo(0);
    assertThat(model.entryAt(0).cellValueAt(0)).isEqualTo("0");
    assertThat(model.entryAt(0).cellValueAt(1)).isEqualTo("A-001");
    assertThat(model.entryAt(0).cellValueAt(2)).isEqualTo("Main street");
    assertThat(model.entryAt(0).cellValueAt(3)).isEqualTo("POINT (1 2)");
    assertThat(model.entryAt(0).cellValueAt(4)).isEqualTo("open");
    assertThat(model.entryAt(0).hitLabel()).isEqualTo("Row 0 | id=A-001 | name=Main street");
    assertThat(model.entryAt(1).rowIndex()).isEqualTo(3);
    assertThat(model.entryAt(1).cellValueAt(0)).isEqualTo("3");
    assertThat(model.entryAt(1).cellValueAt(1)).isEqualTo("C-003");
    assertThat(model.entryAt(1).cellValueAt(2)).isEqualTo("Side road");
    assertThat(model.entryAt(1).cellValueAt(3)).isEqualTo("LINESTRING (0 0, 2 2)");
    assertThat(model.entryAt(1).cellValueAt(4)).isEqualTo("review");
    assertThat(model.indexOfRow(3)).isEqualTo(1);
    assertThat(model.indexOfFeature(model.entryAt(1).feature())).isEqualTo(1);
  }

  @Test
  void buildsCompactHitLabelsFromAttributesAndFallsBackToGeometryText() {
    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("label"));
    rowMeta.addValueMeta(new ValueMetaString("geom_wkt"));

    String longGeometry =
        "LINESTRING (0 0, 1 1, 2 2, 3 3, 4 4, 5 5, 6 6, 7 7, 8 8, 9 9, 10 10, 11 11, 12 12, "
            + "13 13, 14 14, 15 15, 16 16, 17 17, 18 18, 19 19, 20 20, 21 21, 22 22, 23 23, 24 24)";
    List<Object[]> rows = List.<Object[]>of(new Object[] {"", longGeometry});

    GeometryBuildResult buildResult = featureBuilder.build(rowMeta, rows, "geom_wkt");
    GeometryInspectorFeatureTableModel model =
        new GeometryInspectorFeatureTableModel(
            samplingResult(rows, rowMeta), buildResult, "geom_wkt");

    assertThat(model.size()).isEqualTo(1);
    assertThat(model.entryAt(0).cellValueAt(1)).isEqualTo("");
    assertThat(model.entryAt(0).cellValueAt(2)).startsWith("LINESTRING");
    assertThat(model.entryAt(0).hitLabel()).startsWith("Row 0 | LINESTRING");
    assertThat(model.entryAt(0).hitLabel())
        .hasSize(GeometryInspectorFeatureTableModel.MAX_HIT_LABEL_LENGTH);
    assertThat(model.entryAt(0).hitLabel()).endsWith("...");
  }

  @Test
  void resolvesEntryByFeatureIdWhenRowIndexIsMissing() {
    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("name"));
    rowMeta.addValueMeta(new ValueMetaString("geom_wkt"));

    List<Object[]> rows = List.<Object[]>of(new Object[] {"alpha", "POINT (1 2)"});
    GeometryBuildResult buildResult = featureBuilder.build(rowMeta, rows, "geom_wkt");
    GeometryInspectorFeatureTableModel model =
        new GeometryInspectorFeatureTableModel(
            samplingResult(rows, rowMeta), buildResult, "geom_wkt");

    SimpleFeature selected = model.entryAt(0).feature();
    SimpleFeatureBuilder builder = new SimpleFeatureBuilder(selected.getFeatureType());
    for (int index = 0; index < selected.getFeatureType().getAttributeCount(); index++) {
      String attributeName = selected.getFeatureType().getDescriptor(index).getLocalName();
      Object value = "row_index".equals(attributeName) ? -1 : selected.getAttribute(index);
      builder.add(value);
    }
    SimpleFeature sameIdDifferentRowIndex = builder.buildFeature(selected.getID());

    assertThat(GeometryInspectorFeatureTableModel.rowIndexOf(sameIdDifferentRowIndex)).isEqualTo(-1);
    assertThat(model.indexOfFeature(sameIdDifferentRowIndex)).isEqualTo(0);
    assertThat(model.entryForFeature(sameIdDifferentRowIndex)).isEqualTo(model.entryAt(0));
  }

  private SamplingResult samplingResult(List<Object[]> rows, RowMeta rowMeta) {
    return new SamplingResult(
        rows,
        rowMeta,
        false,
        "",
        GeometryInspectionSide.AUTO,
        GeometryInspectionSide.OUTPUT,
        false,
        "");
  }
}
