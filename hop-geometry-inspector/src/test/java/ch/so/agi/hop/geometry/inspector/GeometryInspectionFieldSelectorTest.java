package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.Test;

class GeometryInspectionFieldSelectorTest {

  private final GeometryInspectionFieldSelector fieldSelector = new GeometryInspectionFieldSelector();

  @Test
  void keepsPreferredFieldWhenItExistsOnInspectedSide() {
    GeometryInspectionFieldSelection selection =
        fieldSelector.resolve(rowMeta("geom", "wkt_geom"), "wkt_geom");

    assertThat(selection.geometryFields()).containsExactly("geom", "wkt_geom");
    assertThat(selection.selectedField()).isEqualTo("wkt_geom");
    assertThat(selection.message()).isBlank();
  }

  @Test
  void fallsBackToDefaultFieldWhenPreferredFieldIsMissing() {
    GeometryInspectionFieldSelection selection =
        fieldSelector.resolve(rowMeta("geom"), "upstream_geom");

    assertThat(selection.selectedField()).isEqualTo("geom");
    assertThat(selection.message()).contains("auto-adjusted").contains("upstream_geom");
  }

  private RowMeta rowMeta(String... fieldNames) {
    RowMeta rowMeta = new RowMeta();
    for (String fieldName : fieldNames) {
      rowMeta.addValueMeta(new ValueMetaString(fieldName));
    }
    return rowMeta;
  }
}
