package ch.so.agi.hop.geometry.inspector.data;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.hop.geometry.inspector.model.*;
import java.time.*;
import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaInteger;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.junit.jupiter.api.*;

class ReplayPlannerTest {
  @BeforeAll
  static void init() throws Exception {
    if (!HopEnvironment.isInitialized()) HopEnvironment.init();
  }

  PipelineMeta pipeline() {
    var p = new PipelineMeta();
    p.setName("test");
    var a = new TransformMeta("Dummy", "A", new DummyMeta());
    var b = new TransformMeta("Dummy", "B", new DummyMeta());
    var c = new TransformMeta("Dummy", "C", new DummyMeta());
    p.addTransform(a);
    p.addTransform(b);
    p.addTransform(c);
    p.addPipelineHop(new PipelineHopMeta(a, b));
    p.addPipelineHop(new PipelineHopMeta(b, c));
    return p;
  }

  InspectionResult cache(PipelineMeta p, String name, UUID generation) throws Exception {
    var meta = new RowMeta();
    meta.addValueMeta(new ValueMetaInteger("id"));
    return new InspectionResult(
        UUID.randomUUID(),
        generation,
        "test",
        new StreamDescriptor(
            name, StreamDescriptor.Direction.OUTPUT, "", StreamDescriptor.Kind.MAIN, 0),
        Instant.now(),
        new MemoryRowStore(meta, List.of(new Object[] {1L}, new Object[] {2L})),
        false,
        InspectionResult.Completion.COMPLETE,
        "Natural completion",
        CacheFingerprint.of(p, name, new Variables(), new MemoryMetadataProvider()));
  }

  @Test
  void replaysFromBoundaryWithoutExecutingOriginalSource() throws Exception {
    var p = pipeline();
    var input = cache(p, "A", UUID.randomUUID());
    var variables = new Variables();
    var provider = new MemoryMetadataProvider();
    var plan =
        new ReplayPlanner()
            .plan(p, variables, provider, ReplayPlanner.Mode.FROM, "B", null, List.of(input));
    assertThat(plan.executing()).containsExactly("B", "C");
    assertThat(plan.pipeline().findTransform("A").getTransform())
        .isInstanceOf(ReplaySourceMeta.class);
    assertThat(p.findTransform("A").getTransform()).isInstanceOf(DummyMeta.class);
    var stream =
        new StreamDescriptor(
            "C", StreamDescriptor.Direction.OUTPUT, "", StreamDescriptor.Kind.MAIN, 0);
    var results =
        new CaptureService()
            .execute(
                p,
                plan.pipeline(),
                variables,
                provider,
                List.of(stream),
                new GeometryInspectorOptions(
                    100,
                    SamplingMode.FIRST,
                    GeometryInspectionSide.OUTPUT,
                    "",
                    Duration.ofSeconds(5)),
                null,
                () -> false);
    assertThat(results.getFirst().rows().size()).isEqualTo(2);
    assertThat(results.getFirst().rows().row(1)).containsExactly(2L);
  }

  @Test
  void acceptsVectorReaderAsReplayStartWithoutAReferenceCache() throws Exception {
    var p = new PipelineMeta();
    p.setName("vector-reader");
    var reader = new TransformMeta("SOGIS_VECTOR_READER", "Reader", new DummyMeta());
    var result = new TransformMeta("Dummy", "Result", new DummyMeta());
    p.addTransform(reader);
    p.addTransform(result);
    p.addPipelineHop(new PipelineHopMeta(reader, result));

    var planner = new ReplayPlanner();
    var variables = new Variables();
    var provider = new MemoryMetadataProvider();
    var to = planner.plan(p, variables, provider, ReplayPlanner.Mode.TO, "Reader", null, List.of());
    assertThat(to.executing()).containsExactly("Reader");

    var from =
        planner.plan(p, variables, provider, ReplayPlanner.Mode.FROM, "Reader", null, List.of());
    assertThat(from.executing()).containsExactly("Reader", "Result");
  }

  @Test
  void usesCompleteVectorReaderCacheForDownstreamFromAndBetweenReplay() throws Exception {
    var p = new PipelineMeta();
    p.setName("vector-reader-boundary");
    var reader = new TransformMeta("SOGIS_VECTOR_READER", "Reader", new DummyMeta());
    var middle = new TransformMeta("Dummy", "Middle", new DummyMeta());
    var result = new TransformMeta("Dummy", "Result", new DummyMeta());
    p.addTransform(reader);
    p.addTransform(middle);
    p.addTransform(result);
    p.addPipelineHop(new PipelineHopMeta(reader, middle));
    p.addPipelineHop(new PipelineHopMeta(middle, result));

    var planner = new ReplayPlanner();
    var variables = new Variables();
    var provider = new MemoryMetadataProvider();
    assertThatThrownBy(
            () ->
                planner.plan(
                    p,
                    variables,
                    provider,
                    ReplayPlanner.Mode.FROM,
                    "Middle",
                    null,
                    List.of()))
        .hasMessageContaining("Missing complete current cache for Reader");

    var readerCache = cache(p, "Reader", UUID.randomUUID());
    var from =
        planner.plan(
            p,
            variables,
            provider,
            ReplayPlanner.Mode.FROM,
            "Middle",
            null,
            List.of(readerCache));
    assertThat(from.executing()).containsExactly("Middle", "Result");
    assertThat(from.pipeline().findTransform("Reader").getTransform())
        .isInstanceOf(ReplaySourceMeta.class);

    assertThatThrownBy(
            () ->
                planner.plan(
                    p,
                    variables,
                    provider,
                    ReplayPlanner.Mode.BETWEEN,
                    "Middle",
                    "Result",
                    List.of()))
        .hasMessageContaining("Missing complete current cache for Reader");
    var between =
        planner.plan(
            p,
            variables,
            provider,
            ReplayPlanner.Mode.BETWEEN,
            "Middle",
            "Result",
            List.of(readerCache));
    assertThat(between.executing()).containsExactly("Middle", "Result");
  }

  @Test
  void rejectsMissingInputsAndCycles() throws Exception {
    var p = pipeline();
    assertThatThrownBy(
            () ->
                new ReplayPlanner()
                    .plan(
                        p,
                        new Variables(),
                        new MemoryMetadataProvider(),
                        ReplayPlanner.Mode.FROM,
                        "B",
                        null,
                        List.of()))
        .hasMessageContaining("Missing complete");
    p.addPipelineHop(new PipelineHopMeta(p.findTransform("C"), p.findTransform("A")));
    assertThatThrownBy(
            () ->
                new ReplayPlanner()
                    .plan(
                        p,
                        new Variables(),
                        new MemoryMetadataProvider(),
                        ReplayPlanner.Mode.TO,
                        "B",
                        null,
                        List.of()))
        .hasMessageContaining("cycles");
  }

  @Test
  void validatesSideInputsGenerationsAndTransformCopies() throws Exception {
    var p = pipeline();
    var d = new TransformMeta("Dummy", "D", new DummyMeta());
    p.addTransform(d);
    p.addPipelineHop(new PipelineHopMeta(d, p.findTransform("B")));
    var planner = new ReplayPlanner();
    var variables = new Variables();
    var provider = new MemoryMetadataProvider();
    UUID generation = UUID.randomUUID();
    var a = cache(p, "A", generation);
    var other = cache(p, "D", UUID.randomUUID());
    assertThatThrownBy(
            () ->
                planner.plan(
                    p, variables, provider, ReplayPlanner.Mode.BETWEEN, "B", "C", List.of(a)))
        .hasMessageContaining("Missing complete");
    assertThatThrownBy(
            () ->
                planner.plan(
                    p,
                    variables,
                    provider,
                    ReplayPlanner.Mode.BETWEEN,
                    "B",
                    "C",
                    List.of(a, other)))
        .hasMessageContaining("same capture generation");
    var same = cache(p, "D", generation);
    var plan =
        planner.plan(
            p, variables, provider, ReplayPlanner.Mode.BETWEEN, "B", "C", List.of(a, same));
    assertThat(plan.executing()).containsExactlyInAnyOrder("B", "C");
    p.findTransform("B").setCopies(2);
    assertThatThrownBy(
            () ->
                planner.plan(
                    p, variables, provider, ReplayPlanner.Mode.FROM, "B", null, List.of(a, same)))
        .hasMessageContaining("does not support");
  }

  @Test
  void rejectsStaleAndSampleBoundaries() throws Exception {
    var p = pipeline();
    var full = cache(p, "A", UUID.randomUUID());
    var sample =
        new InspectionResult(
            full.id(),
            full.generation(),
            full.pipeline(),
            full.stream(),
            full.capturedAt(),
            full.rows(),
            true,
            full.completion(),
            full.reason(),
            full.fingerprint());
    var planner = new ReplayPlanner();
    var variables = new Variables();
    var provider = new MemoryMetadataProvider();
    assertThatThrownBy(
            () ->
                planner.plan(
                    p, variables, provider, ReplayPlanner.Mode.FROM, "B", null, List.of(sample)))
        .hasMessageContaining("Missing complete");
    p.findTransform("A").setDescription("changed upstream");
    assertThatThrownBy(
            () ->
                planner.plan(
                    p, variables, provider, ReplayPlanner.Mode.FROM, "B", null, List.of(full)))
        .hasMessageContaining("Missing complete");
  }

  @Test
  void metadataFingerprintFollowsOnlyUpstreamReferences() throws Exception {
    var p = pipeline();
    var provider = new MemoryMetadataProvider();
    var variables = new Variables();
    var configuration = new org.apache.hop.pipeline.config.PipelineRunConfiguration();
    configuration.setName("only-downstream");
    configuration.setDescription("first");
    var serializer =
        provider.getSerializer(org.apache.hop.pipeline.config.PipelineRunConfiguration.class);
    serializer.save(configuration);
    p.findTransform("C").setDescription(configuration.getName());
    String before = CacheFingerprint.of(p, "A", variables, provider);
    configuration.setDescription("second");
    serializer.save(configuration);
    assertThat(CacheFingerprint.of(p, "A", variables, provider)).isEqualTo(before);
    p.findTransform("A").setDescription(configuration.getName());
    String referenced = CacheFingerprint.of(p, "A", variables, provider);
    configuration.setDescription("third");
    serializer.save(configuration);
    assertThat(CacheFingerprint.of(p, "A", variables, provider)).isNotEqualTo(referenced);
  }

  @Test
  void downstreamChangesDoNotInvalidateUpstreamCache() throws Exception {
    var p = pipeline();
    String before = CacheFingerprint.of(p, "A", new Variables(), new MemoryMetadataProvider());
    p.findTransform("C").setDescription("changed");
    assertThat(CacheFingerprint.of(p, "A", new Variables(), new MemoryMetadataProvider()))
        .isEqualTo(before);
  }
}
