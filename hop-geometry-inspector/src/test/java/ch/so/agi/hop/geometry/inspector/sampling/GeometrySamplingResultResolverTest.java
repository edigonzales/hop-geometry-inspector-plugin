package ch.so.agi.hop.geometry.inspector.sampling;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.util.List;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.Test;

class GeometrySamplingResultResolverTest {

  private final GeometrySamplingResultResolver resolver = new GeometrySamplingResultResolver();

  @Test
  void prefersOutputRowsInAutoMode() {
    SamplingResult result =
        resolver.resolve(
            GeometryInspectionSide.AUTO,
            List.<Object[]>of(new Object[] {"output"}),
            rowMeta("geom"),
            List.<Object[]>of(new Object[] {"input"}),
            rowMeta("geom"),
            false,
            "");

    assertThat(result.effectiveSide()).isEqualTo(GeometryInspectionSide.OUTPUT);
    assertThat(result.autoSwitched()).isFalse();
    assertThat(result.rows()).hasSize(1);
  }

  @Test
  void switchesToInputWhenAutoModeSeesNoOutputRows() {
    SamplingResult result =
        resolver.resolve(
            GeometryInspectionSide.AUTO,
            List.of(),
            null,
            List.<Object[]>of(new Object[] {"input"}),
            rowMeta("geom"),
            false,
            "");

    assertThat(result.effectiveSide()).isEqualTo(GeometryInspectionSide.INPUT);
    assertThat(result.autoSwitched()).isTrue();
    assertThat(result.sideResolutionMessage()).contains("Auto-switched to input");
  }

  @Test
  void keepsManualOutputEvenWhenOnlyInputRowsExist() {
    SamplingResult result =
        resolver.resolve(
            GeometryInspectionSide.OUTPUT,
            List.of(),
            null,
            List.<Object[]>of(new Object[] {"input"}),
            rowMeta("geom"),
            false,
            "");

    assertThat(result.effectiveSide()).isEqualTo(GeometryInspectionSide.OUTPUT);
    assertThat(result.autoSwitched()).isFalse();
    assertThat(result.rows()).isEmpty();
    assertThat(result.sideResolutionMessage()).contains("no output rows");
  }

  @Test
  void reportsUnresolvedWhenNoRowsWereObserved() {
    SamplingResult result =
        resolver.resolve(
            GeometryInspectionSide.AUTO,
            List.of(),
            null,
            List.of(),
            null,
            false,
            "");

    assertThat(result.effectiveSide()).isNull();
    assertThat(result.sideResolutionMessage()).isEqualTo("No sampled rows observed.");
  }

  private RowMeta rowMeta(String fieldName) {
    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString(fieldName));
    return rowMeta;
  }
}
