package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PreviewPipelinePrunerTest {

  @BeforeAll
  static void initializeHop() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void keepsOnlyTargetAndUpstreamTransformsAfterXmlCopy() throws Exception {
    IVariables variables = Variables.getADefaultVariableSpace();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    PipelineMeta pipelineMeta = new PipelineMeta();
    pipelineMeta.setMetadataProvider(metadataProvider);

    TransformMeta input = new TransformMeta("Dummy", "Input", new DummyMeta());
    TransformMeta branch = new TransformMeta("Dummy", "Branch", new DummyMeta());
    TransformMeta target = new TransformMeta("Dummy", "Target", new DummyMeta());
    TransformMeta downstream = new TransformMeta("Dummy", "Downstream", new DummyMeta());

    pipelineMeta.addTransform(input);
    pipelineMeta.addTransform(branch);
    pipelineMeta.addTransform(target);
    pipelineMeta.addTransform(downstream);

    pipelineMeta.addPipelineHop(new PipelineHopMeta(input, target));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(branch, target));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(target, downstream));

    PreviewPipelinePruner pruner = new PreviewPipelinePruner();
    PipelineMeta pruned =
        pruner.cloneAndKeepUpstream(pipelineMeta, variables, metadataProvider, "Target");

    assertThat(pruned).isNotSameAs(pipelineMeta);
    Set<String> remainingTransformNames =
        pruned.getTransforms().stream().map(TransformMeta::getName).collect(Collectors.toSet());

    assertThat(remainingTransformNames).containsExactlyInAnyOrder("Input", "Branch", "Target");
    assertThat(pruned.getPipelineHops())
        .extracting(h -> h.getFromTransform().getName() + "->" + h.getToTransform().getName())
        .containsExactlyInAnyOrder("Input->Target", "Branch->Target");
  }
}
