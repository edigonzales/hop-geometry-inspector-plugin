package ch.so.agi.hop.geometry.inspector.sampling;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.model.SamplingMode;
import org.apache.hop.core.IRowSet;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.Test;

class TargetMainOutputCaptureTest {

  @Test
  void syntheticRowSetCapturesRowsAndPreservesRowMeta() {
    TargetMainOutputCapture capture = new TargetMainOutputCapture(2, SamplingMode.FIRST);
    IRowSet rowSet = capture.createRowSet("Target", 0);

    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("geometry"));

    rowSet.putRow(rowMeta, new Object[] {"POINT (1 1)"});

    assertThat(capture.snapshotRows()).extracting(row -> row[0]).containsExactly("POINT (1 1)");
    assertThat(capture.snapshotRowMeta().getValueMeta(0).getName()).isEqualTo("geometry");
    assertThat(rowSet.getDestinationTransformName())
        .isEqualTo(TargetMainOutputCapture.syntheticDestinationName("Target"));
  }

  @Test
  void fullnessAggregatesAcrossMultipleSyntheticRowSets() {
    TargetMainOutputCapture capture = new TargetMainOutputCapture(2, SamplingMode.FIRST);
    IRowSet first = capture.createRowSet("Target", 0);
    IRowSet second = capture.createRowSet("Target", 1);

    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("geometry"));

    first.putRow(rowMeta, new Object[] {"POINT (1 1)"});
    second.putRow(rowMeta, new Object[] {"POINT (2 2)"});

    assertThat(capture.rowSetCount()).isEqualTo(2);
    assertThat(capture.isFull()).isTrue();
    assertThat(capture.snapshotRows()).hasSize(2);
  }
}
