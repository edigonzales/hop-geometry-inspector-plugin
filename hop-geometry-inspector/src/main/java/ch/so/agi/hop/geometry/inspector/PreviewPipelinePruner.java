package ch.so.agi.hop.geometry.inspector;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;

public class PreviewPipelinePruner {

  public PipelineMeta cloneAndKeepUpstream(PipelineMeta sourcePipeline, String targetTransformName) {
    PipelineMeta clone = (PipelineMeta) sourcePipeline.clone();
    TransformMeta targetClone = clone.findTransform(targetTransformName);
    if (targetClone == null) {
      throw new IllegalArgumentException("Target transform not found in pipeline clone: " + targetTransformName);
    }

    Set<String> keepNames = findUpstreamTransformNames(clone, targetClone);
    prunePipelineHops(clone, keepNames);
    pruneTransforms(clone, keepNames);
    return clone;
  }

  private Set<String> findUpstreamTransformNames(PipelineMeta pipelineMeta, TransformMeta target) {
    Set<String> keep = new HashSet<>();
    ArrayDeque<TransformMeta> queue = new ArrayDeque<>();
    queue.add(target);

    while (!queue.isEmpty()) {
      TransformMeta current = queue.removeFirst();
      if (!keep.add(current.getName())) {
        continue;
      }

      List<TransformMeta> previous = pipelineMeta.findPreviousTransforms(current);
      for (TransformMeta prev : previous) {
        if (prev != null && !keep.contains(prev.getName())) {
          queue.add(prev);
        }
      }
    }

    return keep;
  }

  private void prunePipelineHops(PipelineMeta pipelineMeta, Set<String> keepNames) {
    List<PipelineHopMeta> hops = List.copyOf(pipelineMeta.getPipelineHops());
    for (PipelineHopMeta hopMeta : hops) {
      boolean keepFrom = hopMeta.getFromTransform() != null && keepNames.contains(hopMeta.getFromTransform().getName());
      boolean keepTo = hopMeta.getToTransform() != null && keepNames.contains(hopMeta.getToTransform().getName());
      if (!(keepFrom && keepTo)) {
        pipelineMeta.removePipelineHop(hopMeta);
      }
    }
  }

  private void pruneTransforms(PipelineMeta pipelineMeta, Set<String> keepNames) {
    for (int i = pipelineMeta.nrTransforms() - 1; i >= 0; i--) {
      TransformMeta transformMeta = pipelineMeta.getTransform(i);
      if (!keepNames.contains(transformMeta.getName())) {
        pipelineMeta.removeTransform(i);
      }
    }
  }
}
