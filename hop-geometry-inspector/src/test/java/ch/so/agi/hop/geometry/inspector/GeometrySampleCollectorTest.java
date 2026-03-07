package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.model.SamplingMode;
import ch.so.agi.hop.geometry.inspector.sampling.GeometrySampleCollector;
import java.util.Random;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.Test;

class GeometrySampleCollectorTest {

  @Test
  void firstSamplingStopsAtLimit() throws Exception {
    GeometrySampleCollector collector = new GeometrySampleCollector(2, SamplingMode.FIRST);
    RowMeta rowMeta = rowMeta();

    collector.accept(rowMeta, new Object[] {"a"});
    collector.accept(rowMeta, new Object[] {"b"});
    collector.accept(rowMeta, new Object[] {"c"});

    assertThat(collector.snapshotRows()).hasSize(2);
    assertThat((String) collector.snapshotRows().get(0)[0]).isEqualTo("a");
    assertThat((String) collector.snapshotRows().get(1)[0]).isEqualTo("b");
    assertThat(collector.isFull()).isTrue();
  }

  @Test
  void lastSamplingKeepsTail() throws Exception {
    GeometrySampleCollector collector = new GeometrySampleCollector(2, SamplingMode.LAST);
    RowMeta rowMeta = rowMeta();

    collector.accept(rowMeta, new Object[] {"a"});
    collector.accept(rowMeta, new Object[] {"b"});
    collector.accept(rowMeta, new Object[] {"c"});

    assertThat(collector.snapshotRows()).hasSize(2);
    assertThat((String) collector.snapshotRows().get(0)[0]).isEqualTo("c");
    assertThat((String) collector.snapshotRows().get(1)[0]).isEqualTo("b");
  }

  @Test
  void randomSamplingKeepsConfiguredSize() throws Exception {
    GeometrySampleCollector collector =
        new GeometrySampleCollector(3, SamplingMode.RANDOM, new Random(123));
    RowMeta rowMeta = rowMeta();

    collector.accept(rowMeta, new Object[] {"a"});
    collector.accept(rowMeta, new Object[] {"b"});
    collector.accept(rowMeta, new Object[] {"c"});
    collector.accept(rowMeta, new Object[] {"d"});
    collector.accept(rowMeta, new Object[] {"e"});

    assertThat(collector.snapshotRows()).hasSize(3);
  }

  private RowMeta rowMeta() {
    RowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("val"));
    return rowMeta;
  }
}
