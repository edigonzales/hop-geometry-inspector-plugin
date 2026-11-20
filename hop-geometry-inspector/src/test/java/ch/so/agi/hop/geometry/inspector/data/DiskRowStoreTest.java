package ch.so.agi.hop.geometry.inspector.data;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DiskRowStoreTest {

  @BeforeAll
  static void initialize() throws Exception {
    if (!HopEnvironment.isInitialized()) HopEnvironment.init();
  }

  private RowMeta schema() {
    RowMeta meta = new RowMeta();
    meta.addValueMeta(new ValueMetaInteger("id"));
    meta.addValueMeta(new ValueMetaString("name"));
    return meta;
  }

  @Test
  void queriesTypedTemporaryRowsAcrossSortRuns() throws Exception {
    var meta = schema();
    try (var rows = new DiskRowStore(meta, 10_000_000)) {
      for (long i = 9000; i >= 0; i--) rows.append(new Object[] {i, i % 2 == 0 ? "even" : null});
      try (var query =
          QueryIndex.build(
              rows,
              new RowQuery(
                  "even",
                  List.of(new RowQuery.Condition(0, RowQuery.Operator.GT, 100L)),
                  List.of(new RowQuery.Sort(0, true))),
              () -> false)) {
        assertThat(rows.size()).isEqualTo(9001);
        assertThat(rows.row(0)).containsExactly(9000L, "even");
        assertThat(query.size()).isEqualTo(4450);
        assertThat(rows.row(query.ordinal(0))[0]).isEqualTo(102L);
        assertThat(rows.row(query.ordinal(query.size() - 1))[0]).isEqualTo(9000L);
      }
    }
  }

  @Test
  void storageLimitRejectsARowWithoutPublishingAPartialRecord() throws Exception {
    try (var rows = new DiskRowStore(schema(), 4096)) {
      rows.append(new Object[] {1L, "ok"});
      assertThatThrownBy(() -> rows.append(new Object[] {2L, "x".repeat(10000)}))
          .hasRootCauseMessage(
              "Temporary inspection storage limit reached. Close a result or use Sample.");
      assertThat(rows.size()).isEqualTo(1);
      assertThat(rows.row(0)).containsExactly(1L, "ok");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closeReleasesHandlesAndReservationEvenIfDirectoryDeletionFails(boolean blockDeletion)
      throws Exception {
    var rows = new DiskRowStore(schema(), 4096);
    var directory = (Path) field(rows, "directory");
    var leftover = directory.resolve("leftover");
    try (rows) {
      var data = ((RandomAccessFile) field(rows, "data")).getChannel();
      var index = ((RandomAccessFile) field(rows, "index")).getChannel();
      var reserved = (AtomicLong) field(rows, "TEMP_BYTES");
      long before = reserved.get();
      rows.append(new Object[] {1L, "ok"});
      assertThat(reserved.get()).isGreaterThan(before);

      if (blockDeletion) {
        Files.createFile(leftover);
        assertThatThrownBy(rows::close).isInstanceOf(IOException.class);
        assertThat(leftover).exists();
      } else {
        rows.close();
        assertThat(directory).doesNotExist();
      }

      assertThat(data.isOpen()).isFalse();
      assertThat(index.isOpen()).isFalse();
      assertThat(reserved.get()).isEqualTo(before);
      assertThatThrownBy(() -> rows.row(0))
          .isInstanceOf(IOException.class)
          .hasMessage("Inspection rows are closed");
      assertThatThrownBy(() -> rows.append(new Object[] {2L, "closed"}))
          .isInstanceOf(IOException.class)
          .hasMessage("Inspection rows are closed");
      rows.close();
      assertThat(reserved.get()).isEqualTo(before);
    } finally {
      Files.deleteIfExists(leftover);
      Files.deleteIfExists(directory);
    }
  }

  private static Object field(DiskRowStore rows, String name) throws ReflectiveOperationException {
    var field = DiskRowStore.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(rows);
  }

  @Test
  void preservesScalarTypesAndTimestampPrecision() throws Exception {
    var meta = new RowMeta();
    meta.addValueMeta(new ValueMetaBoolean("flag"));
    meta.addValueMeta(new ValueMetaBigNumber("decimal"));
    meta.addValueMeta(new ValueMetaDate("date"));
    meta.addValueMeta(new ValueMetaTimestamp("timestamp"));
    meta.addValueMeta(new ValueMetaNumber("number"));
    var timestamp = java.sql.Timestamp.valueOf("2026-09-17 12:34:56.123456789");
    Object[] row = {
      true,
      new java.math.BigDecimal("12345678901234567890.123456789"),
      new Date(1234567890123L),
      timestamp,
      Double.NaN
    };
    try (var reader = new DiskRowStore(meta, 100000)) {
      reader.append(row);
      reader.append(new Object[5]);
      assertThat(reader.row(0)).containsExactly(row);
      assertThat(reader.row(1)).containsOnlyNulls();
    }
  }

  @Test
  void nullsRemainLastWithDescendingTypedSort() {
    var query = new RowQuery("", List.of(), List.of(new RowQuery.Sort(0, false)));
    assertThat(query.compareRows(new Object[] {null}, new Object[] {2L})).isPositive();
    assertThat(query.compareRows(new Object[] {10L}, new Object[] {2L})).isNegative();
  }
}
