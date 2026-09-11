package ch.so.agi.hop.geometry.inspector.sampling;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.PreviewPipelinePruner;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.SamplingMode;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GeometrySamplerServiceIntegrationTest {

  @BeforeAll
  static void initializeHop() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void samplesUsingPrunedPreviewPipelineAndForwardsOptions() throws Exception {
    PipelineMeta pipelineMeta = new PipelineMeta();
    TransformMeta input = dummy("Input");
    TransformMeta branch = dummy("Branch");
    TransformMeta target = dummy("Target");
    TransformMeta downstream = dummy("Downstream");

    pipelineMeta.addTransform(input);
    pipelineMeta.addTransform(branch);
    pipelineMeta.addTransform(target);
    pipelineMeta.addTransform(downstream);

    pipelineMeta.addPipelineHop(new PipelineHopMeta(input, target));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(branch, target));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(target, downstream));

    StubSamplerExecutor executor = new StubSamplerExecutor();
    GeometrySamplerService service = new GeometrySamplerService(new PreviewPipelinePruner(), executor);
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();

    GeometryInspectorOptions options =
        new GeometryInspectorOptions(
            200,
            SamplingMode.RANDOM,
            GeometryInspectionSide.AUTO,
            "geom",
            Duration.ofSeconds(30));

    SamplingResult result =
        service.sample(pipelineMeta, new Variables(), metadataProvider, "Target", options);

    assertThat(result.rows()).hasSize(1);
    assertThat(executor.receivedTargetTransformName).isEqualTo("Target");
    assertThat(executor.receivedOptions).isEqualTo(options);

    Set<String> remainingTransforms =
        executor.receivedPreviewPipeline.getTransforms().stream()
            .map(TransformMeta::getName)
            .collect(Collectors.toSet());
    assertThat(remainingTransforms).containsExactlyInAnyOrder("Input", "Branch", "Target");

    assertThat(executor.receivedPreviewPipeline.getPipelineHops())
        .extracting(h -> h.getFromTransform().getName() + "->" + h.getToTransform().getName())
        .containsExactlyInAnyOrder("Input->Target", "Branch->Target");
  }

  @Test
  void prunesAlternativeDownstreamPathsBeforeExecution() throws Exception {
    PipelineMeta pipelineMeta = new PipelineMeta();
    TransformMeta source = dummy("Source");
    TransformMeta target = dummy("Target");
    TransformMeta downA = dummy("DownA");
    TransformMeta downB = dummy("DownB");

    pipelineMeta.addTransform(source);
    pipelineMeta.addTransform(target);
    pipelineMeta.addTransform(downA);
    pipelineMeta.addTransform(downB);

    pipelineMeta.addPipelineHop(new PipelineHopMeta(source, target));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(target, downA));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(target, downB));

    StubSamplerExecutor executor = new StubSamplerExecutor();
    GeometrySamplerService service = new GeometrySamplerService(new PreviewPipelinePruner(), executor);

    GeometryInspectorOptions options =
        new GeometryInspectorOptions(
            50,
            SamplingMode.FIRST,
            GeometryInspectionSide.AUTO,
            "geom",
            Duration.ofSeconds(30));

    service.sample(
        pipelineMeta, new Variables(), new MemoryMetadataProvider(), "Target", options);

    Set<String> transformNames =
        executor.receivedPreviewPipeline.getTransforms().stream()
            .map(TransformMeta::getName)
            .collect(Collectors.toSet());
    assertThat(transformNames).containsExactlyInAnyOrder("Source", "Target");
  }

  private static TransformMeta dummy(String name) {
    return new TransformMeta("Dummy", name, new DummyMeta());
  }

  private static class StubSamplerExecutor implements GeometryPipelineSamplerExecutor {
    PipelineMeta receivedPreviewPipeline;
    String receivedTargetTransformName;
    GeometryInspectorOptions receivedOptions;

    @Override
    public SamplingResult execute(
        PipelineMeta previewPipeline,
        org.apache.hop.core.variables.IVariables variables,
        org.apache.hop.metadata.api.IHopMetadataProvider metadataProvider,
        String targetTransformName,
        GeometryInspectorOptions options) {
      this.receivedPreviewPipeline = previewPipeline;
      this.receivedTargetTransformName = targetTransformName;
      this.receivedOptions = options;

      org.apache.hop.core.row.RowMeta rowMeta = new org.apache.hop.core.row.RowMeta();
      rowMeta.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("geom"));
      return new SamplingResult(
          java.util.List.<Object[]>of(new Object[] {"POINT (0 0)"}),
          rowMeta,
          false,
          "",
          GeometryInspectionSide.AUTO,
          GeometryInspectionSide.OUTPUT,
          false,
          "Inspecting output rows.");
    }
  }
}
