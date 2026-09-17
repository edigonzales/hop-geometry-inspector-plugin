package ch.so.agi.hop.geometry.inspector.data;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DiskCacheTest {
  @TempDir Path directory;

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
  void roundTripAndQuerySurviveReopen() throws Exception {
    var meta = schema();
    Path path = directory.resolve("rows");
    try (var writer = new DiskRowStore.Writer(path, 10_000_000)) {
      for (long i = 9000; i >= 0; i--)
        writer.append(meta, new Object[] {i, i % 2 == 0 ? "even" : null});
    }
    try (var rows = new DiskRowStore(path);
        var query =
            QueryIndex.build(
                rows,
                new RowQuery(
                    "even",
                    List.of(new RowQuery.Condition(0, RowQuery.Operator.GT, 4500L)),
                    List.of(new RowQuery.Sort(0, true))),
                () -> false)) {
      assertThat(rows.size()).isEqualTo(9001);
      assertThat(rows.row(0)).containsExactly(9000L, "even");
      assertThat(query.size()).isEqualTo(2250);
      assertThat(rows.row(query.ordinal(0))[0]).isEqualTo(4502L);
      assertThat(rows.row(query.ordinal(query.size() - 1))[0]).isEqualTo(9000L);
    }
  }

  @Test
  void schemaChangesAndBudgetCannotProduceCompleteRows() throws Exception {
    try (var writer = new DiskRowStore.Writer(directory, 4096)) {
      writer.append(schema(), new Object[] {1L, "ok"});
      assertThatThrownBy(() -> writer.append(schema(), new Object[] {2L, "x".repeat(10000)}))
          .hasMessageContaining("Cannot cache row");
    }
    try (var reader = new DiskRowStore(directory)) {
      assertThat(reader.size()).isEqualTo(1);
      assertThat(reader.row(0)).containsExactly(1L, "ok");
    }
  }

  @Test
  void repositoryPreservesCompletionAndProtectsOpenReaders() throws Exception {
    var repository = new CacheRepository(directory, 1_000_000, Duration.ofDays(7));
    var stream =
        new StreamDescriptor(
            "A", StreamDescriptor.Direction.OUTPUT, "", StreamDescriptor.Kind.MAIN, 0);
    UUID id;
    try (var capture = repository.begin("pipeline.hpl", stream, UUID.randomUUID(), "fingerprint")) {
      id = capture.id();
      capture.append(schema(), new Object[] {7L, null});
      capture.finish(InspectionResult.Completion.COMPLETE, "Natural completion");
    }
    var restarted = new CacheRepository(directory, 1_000_000, Duration.ofDays(7));
    var result = restarted.result(id);
    assertThat(result.replayable("fingerprint")).isTrue();
    assertThat(result.replayable("changed")).isFalse();
    assertThatThrownBy(() -> restarted.delete(id)).hasMessageContaining("in use");
    result.rows().close();
    restarted.delete(id);
    assertThat(restarted.entries()).isEmpty();
  }

  @Test
  void blocksDetectDamageWithoutLoadingWholeCache() throws Exception {
    var meta = new RowMeta();
    meta.addValueMeta(new ValueMetaBinary("payload"));
    byte[] payload = new byte[400_000];
    java.util.Arrays.fill(payload, (byte) 37);
    try (var writer = new DiskRowStore.Writer(directory, 200_000_000)) {
      for (int i = 0; i < 320; i++) writer.append(meta, new Object[] {payload});
    }
    try (var files = Files.list(directory)) {
      assertThat(files.filter(p -> p.getFileName().toString().startsWith("rows-")).count())
          .isGreaterThan(1);
    }
    try (var rows = new DiskRowStore(directory)) {
      assertThat((byte[]) rows.row(319)[0]).containsExactly(payload);
    }
    try (var damaged =
        new java.io.RandomAccessFile(directory.resolve("rows-00000000.bin").toFile(), "rw")) {
      damaged.seek(20);
      damaged.writeByte(99);
    }
    try (var rows = new DiskRowStore(directory)) {
      assertThatThrownBy(() -> rows.row(0)).hasMessageContaining("Corrupt cache row");
      assertThat(rows.row(319)).hasSize(1);
    }
  }

  @Test
  void restartRecognizesInterruptedRecordingAndRetainsReadableRows() throws Exception {
    var repo = new CacheRepository(directory, 1_000_000, Duration.ofDays(7));
    var stream =
        new StreamDescriptor(
            "A", StreamDescriptor.Direction.OUTPUT, "", StreamDescriptor.Kind.MAIN, 0);
    UUID id;
    try (var capture = repo.begin("pipeline.hpl", stream, UUID.randomUUID(), "fp")) {
      id = capture.id();
      capture.append(schema(), new Object[] {1L, "retained"});
    }
    Path manifest = directory.resolve(id.toString()).resolve("manifest.properties");
    String interrupted = Files.readString(manifest).replace("state=PARTIAL", "state=RUNNING");
    Files.writeString(manifest, interrupted);
    var restarted = new CacheRepository(directory, 1_000_000, Duration.ofDays(7));
    var result = restarted.result(id);
    assertThat(result.completion()).isEqualTo(InspectionResult.Completion.PARTIAL);
    assertThat(result.replayable("fp")).isFalse();
    assertThat(result.rows().row(0)).containsExactly(1L, "retained");
    result.rows().close();
    var expired = new CacheRepository(directory, 1_000_000, Duration.ZERO);
    expired.cleanup();
    assertThat(expired.entries()).isEmpty();
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
    try (var writer = new DiskRowStore.Writer(directory, 100000)) {
      writer.append(meta, row);
      writer.append(meta, new Object[5]);
    }
    try (var reader = new DiskRowStore(directory)) {
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
