package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.Test;

class PreviewPipelinePrunerTest {

  @Test
  void keepsOnlyTargetAndUpstreamTransforms() {
    PipelineMeta pipelineMeta = new PipelineMeta();
    TransformMeta input = new TransformMeta("Input", null);
    TransformMeta branch = new TransformMeta("Branch", null);
    TransformMeta target = new TransformMeta("Target", null);
    TransformMeta downstream = new TransformMeta("Downstream", null);

    pipelineMeta.addTransform(input);
    pipelineMeta.addTransform(branch);
    pipelineMeta.addTransform(target);
    pipelineMeta.addTransform(downstream);

    pipelineMeta.addPipelineHop(new PipelineHopMeta(input, target));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(branch, target));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(target, downstream));

    PreviewPipelinePruner pruner = new PreviewPipelinePruner();
    PipelineMeta pruned = pruner.cloneAndKeepUpstream(pipelineMeta, "Target");

    Set<String> remainingTransformNames =
        pruned.getTransforms().stream().map(TransformMeta::getName).collect(Collectors.toSet());

    assertThat(remainingTransformNames).containsExactlyInAnyOrder("Input", "Branch", "Target");
    assertThat(pruned.getPipelineHops())
        .extracting(h -> h.getFromTransform().getName() + "->" + h.getToTransform().getName())
        .containsExactlyInAnyOrder("Input->Target", "Branch->Target");
  }
}
