package ch.so.agi.hop.geometry.inspector.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.hop.geometry.inspector.GeometryFeatureBuilder;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.SamplingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaBinary;
import org.apache.hop.core.row.value.ValueMetaInteger;
import org.apache.hop.core.row.value.ValueMetaString;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.Pipeline;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.BaseTransform;
import org.apache.hop.pipeline.transform.BaseTransformData;
import org.apache.hop.pipeline.transform.BaseTransformMeta;
import org.apache.hop.pipeline.transform.ITransformIOMeta;
import org.apache.hop.pipeline.transform.TransformIOMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CaptureServiceTest {
  private static final String SOURCE_PLUGIN = "InspectorTestSource";
  private static final AtomicInteger EMITTED = new AtomicInteger();
  private final Variables variables = new Variables();
  private final MemoryMetadataProvider provider = new MemoryMetadataProvider();
  private final CaptureService service = new CaptureService();

  @BeforeAll
  static void initialize() throws Exception {
    if (!HopEnvironment.isInitialized()) HopEnvironment.init();
    TransformPluginType.getInstance()
        .registerCustom(SourceMeta.class, "Test", SOURCE_PLUGIN, "Inspector test source", "", "");
  }

  @Test
  void samplesFirstLastAndRandomWithBoundedSize() throws Exception {
    var pipeline = pipeline(100);
    for (var mode : SamplingMode.values()) {
      var result = capture(pipeline, 3, mode, Duration.ofSeconds(5), () -> false).getFirst();
      try (var rows = result.rows()) {
        var ids = new ArrayList<Long>();
        for (long i = 0; i < rows.size(); i++) ids.add((Long) rows.row(i)[0]);
        assertThat(ids).hasSize(3).doesNotHaveDuplicates().allMatch(id -> id >= 0 && id < 100);
        if (mode == SamplingMode.FIRST) assertThat(ids).containsExactly(0L, 1L, 2L);
        if (mode == SamplingMode.LAST) assertThat(ids).containsExactly(99L, 98L, 97L);
      }
    }
  }

  @Test
  void capturesInputPeersAndOutputCopiesWithoutModifyingThePipeline() throws Exception {
    var pipeline = pipeline(40);
    var source = pipeline.findTransform("Source");
    source.setCopies(2);
    var other = source("Other");
    var merge = new TransformMeta("Dummy", "Combine", new DummyMeta());
    var downstream = source("Downstream");
    pipeline.addTransform(other);
    pipeline.addTransform(merge);
    pipeline.addTransform(downstream);
    pipeline.addPipelineHop(new PipelineHopMeta(source, merge));
    pipeline.addPipelineHop(new PipelineHopMeta(other, merge));
    pipeline.addPipelineHop(new PipelineHopMeta(merge, downstream));
    String original = pipeline.getXml(variables);
    EMITTED.set(0);

    var selected =
        CaptureService.streams(pipeline, variables).stream()
            .filter(s -> s.transform().equals("Source") || s.transform().equals("Combine"))
            .toList();
    var results =
        service.capture(
            pipeline,
            variables,
            provider,
            selected,
            options(400, SamplingMode.LAST, Duration.ofSeconds(5)),
            () -> false);

    assertThat(results).hasSize(5);
    for (var result : results) {
      var stream = result.stream();
      try (var rows = result.rows()) {
        if (stream.direction() == StreamDescriptor.Direction.INPUT) {
          assertThat(rows.size()).isEqualTo(stream.peer().equals("Source") ? 80 : 40);
          for (long i = 0; i < rows.size(); i++)
            assertThat(rows.row(i)[1]).isEqualTo(stream.peer());
        } else if (stream.transform().equals("Source")) {
          assertThat(rows.size()).isEqualTo(40);
          for (long i = 0; i < rows.size(); i++)
            assertThat(rows.row(i)[2]).isEqualTo((long) stream.copy());
        } else {
          assertThat(rows.size()).isEqualTo(120);
        }
      }
    }
    assertThat(EMITTED.get()).isEqualTo(120);
    assertThat(pipeline.getXml(variables)).isEqualTo(original);
  }

  @Test
  void timeoutAndCancellationApplyToEverySamplingMode() throws Exception {
    var pipeline = pipeline(10_000);
    variables.setVariable("INSPECTOR_TEST_DELAY", "5");
    for (var mode : SamplingMode.values()) {
      var timed = capture(pipeline, 100, mode, Duration.ofMillis(120), () -> false).getFirst();
      try (var rows = timed.rows()) {
        assertThat(timed.completion()).isEqualTo(InspectionResult.Completion.PARTIAL);
        assertThat(timed.reason()).isEqualTo("Timeout");
        assertThat(rows.size()).isLessThanOrEqualTo(100);
      }
      EMITTED.set(0);
      assertThatThrownBy(
              () -> capture(pipeline, 100, mode, Duration.ofSeconds(5), () -> EMITTED.get() >= 3))
          .isInstanceOf(CancellationException.class)
          .hasMessage("Capture cancelled");
    }
  }

  @Test
  void releasesTemporaryRowsWhenCancelledOrWhenThePipelineFails() throws Exception {
    var pipeline = pipeline(10_000);
    variables.setVariable("INSPECTOR_TEST_DELAY", "5");
    Set<Path> before = temporaryRows();
    EMITTED.set(0);
    assertThatThrownBy(
            () ->
                capture(
                    pipeline,
                    0,
                    SamplingMode.FIRST,
                    Duration.ofSeconds(5),
                    () -> EMITTED.get() >= 3))
        .isInstanceOf(CancellationException.class);
    assertThat(EMITTED.get()).isGreaterThanOrEqualTo(3);
    assertThat(temporaryRows()).isEqualTo(before);

    variables.setVariable("INSPECTOR_TEST_FAIL_AFTER", "3");
    EMITTED.set(0);
    assertThatThrownBy(
            () -> capture(pipeline, 0, SamplingMode.FIRST, Duration.ofSeconds(5), () -> false))
        .hasMessageContaining("Pipeline failed");
    assertThat(EMITTED.get()).isEqualTo(3);
    assertThat(temporaryRows()).isEqualTo(before);
  }

  @Test
  void inspectsAFullStreamLargerThanTheHeapWithoutMaterializingRows() throws Exception {
    int count = 50_010;
    int payloadSize = 4096;
    var pipeline = pipeline(count);
    variables.setVariable("INSPECTOR_TEST_PAYLOAD", Integer.toString(payloadSize));
    Set<Path> before = temporaryRows();
    var result =
        capture(pipeline, 0, SamplingMode.FIRST, Duration.ofSeconds(60), () -> false).getFirst();
    try (var rows = result.rows()) {
      assertThat(result.completion()).isEqualTo(InspectionResult.Completion.COMPLETE);
      assertThat(rows.size()).isEqualTo(count);
      assertThat(rows.row(0)[4]).isNull();
      assertThat((byte[]) rows.row(count - 1)[4]).hasSize(payloadSize);
      var query =
          new RowQuery(
              "point",
              List.of(new RowQuery.Condition(0, RowQuery.Operator.GT, 49_990L)),
              List.of(new RowQuery.Sort(0, false)));
      try (var index = QueryIndex.build(rows, query, () -> false)) {
        assertThat(index.size()).isEqualTo(19);
        assertThat(rows.row(index.ordinal(0))[0]).isEqualTo(count - 1L);
        assertThat(index.position(count - 1)).isZero();
      }
      var build =
          new GeometryFeatureBuilder()
              .buildLimited(
                  rows.rowMeta(),
                  new StoreRowList(rows),
                  "geometry",
                  50_000,
                  row -> true,
                  () -> false);
      assertThat(build.features()).hasSize(50_000);
      assertThat(build.features().getLast().getAttribute("row_index")).isEqualTo(49_999);
      assertThat(rows.row(count - 1)[3]).isEqualTo("POINT (1 2)");
    }
    assertThat(temporaryRows()).isEqualTo(before);
  }

  private PipelineMeta pipeline(int count) {
    variables.setVariable("INSPECTOR_TEST_ROWS", Integer.toString(count));
    var pipeline = new PipelineMeta();
    pipeline.addTransform(source("Source"));
    return pipeline;
  }

  private TransformMeta source(String name) {
    return new TransformMeta(SOURCE_PLUGIN, name, new SourceMeta());
  }

  private List<InspectionResult> capture(
      PipelineMeta pipeline,
      int size,
      SamplingMode mode,
      Duration timeout,
      BooleanSupplier cancelled)
      throws Exception {
    return service.capture(
        pipeline,
        variables,
        provider,
        CaptureService.streams(pipeline, variables),
        options(size, mode, timeout),
        cancelled);
  }

  private GeometryInspectorOptions options(int size, SamplingMode mode, Duration timeout) {
    return new GeometryInspectorOptions(size, mode, GeometryInspectionSide.OUTPUT, "", timeout);
  }

  private Set<Path> temporaryRows() throws Exception {
    try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return files
          .filter(p -> p.getFileName().toString().startsWith(DiskRowStore.DIRECTORY_PREFIX))
          .collect(Collectors.toSet());
    }
  }

  public static class SourceMeta extends BaseTransformMeta<SourceTransform, SourceData> {
    @Override
    public ITransformIOMeta getTransformIOMeta() {
      return new TransformIOMeta(false, true, false, false, false, false);
    }
  }

  public static class SourceData extends BaseTransformData {}

  public static class SourceTransform extends BaseTransform<SourceMeta, SourceData> {
    private final RowMeta schema = new RowMeta();
    private long next;
    private final long count, delay, failAfter;
    private final byte[] payload;

    public SourceTransform(
        TransformMeta transform,
        SourceMeta meta,
        SourceData data,
        int copy,
        PipelineMeta pipeline,
        Pipeline engine) {
      super(transform, meta, data, copy, pipeline, engine);
      schema.addValueMeta(new ValueMetaInteger("id"));
      schema.addValueMeta(new ValueMetaString("peer"));
      schema.addValueMeta(new ValueMetaInteger("copy"));
      schema.addValueMeta(new ValueMetaString("geometry"));
      schema.addValueMeta(new ValueMetaBinary("payload"));
      count = Long.parseLong(engine.getVariable("INSPECTOR_TEST_ROWS", "0"));
      delay = Long.parseLong(engine.getVariable("INSPECTOR_TEST_DELAY", "0"));
      failAfter = Long.parseLong(engine.getVariable("INSPECTOR_TEST_FAIL_AFTER", "-1"));
      payload = new byte[Integer.parseInt(engine.getVariable("INSPECTOR_TEST_PAYLOAD", "0"))];
    }

    @Override
    public boolean processRow() throws HopException {
      if (isStopped() || next == count) {
        setOutputDone();
        return false;
      }
      if (next == failAfter) throw new HopException("Expected test transform failure");
      if (delay > 0)
        try {
          Thread.sleep(delay);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          setOutputDone();
          return false;
        }
      putRow(
          schema,
          new Object[] {
            next, getTransformName(), (long) getCopy(), "POINT (1 2)", next == 0 ? null : payload
          });
      next++;
      EMITTED.incrementAndGet();
      return true;
    }
  }
}
