package ch.so.agi.hop.geometry.inspector;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopRuntimeException;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;

public class PreviewPipelinePruner {

  public PipelineMeta cloneAndKeepUpstream(PipelineMeta sourcePipeline, String targetTransformName) {
    return cloneAndKeepUpstream(sourcePipeline, new Variables(), null, targetTransformName);
  }

  public PipelineMeta cloneAndKeepUpstream(
      PipelineMeta sourcePipeline,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String targetTransformName) {
    PipelineMeta clone = clonePipeline(sourcePipeline, variables, metadataProvider);

    TransformMeta targetClone = clone.findTransform(targetTransformName);
    if (targetClone == null) {
      throw new IllegalArgumentException("Target transform not found in pipeline clone: " + targetTransformName);
    }

    Set<String> keepNames = findUpstreamTransformNames(clone, targetClone);
    prunePipelineHops(clone, keepNames);
    pruneTransforms(clone, keepNames);
    return clone;
  }

  private PipelineMeta clonePipeline(
      PipelineMeta sourcePipeline,
      IVariables variables,
      IHopMetadataProvider metadataProvider) {
    // Hop 2.19 deliberately disables PipelineMeta.clone(). Serialization is the
    // correct production path because it recreates metadata references. Minimal
    // test pipelines can contain transforms without a plugin id and therefore
    // cannot be loaded from XML; keep those structural fixtures cloneable too.
    boolean hasUnserializableTransform =
        sourcePipeline.getTransforms().stream()
            .anyMatch(
                transform ->
                    transform.getTransformPluginId() == null
                        || transform.getTransformPluginId().isBlank());
    if (hasUnserializableTransform) {
      return structuralClone(sourcePipeline);
    }

    try {
      IVariables effectiveVariables = variables == null ? new Variables() : variables;
      IHopMetadataProvider effectiveMetadataProvider =
          metadataProvider == null ? new MemoryMetadataProvider() : metadataProvider;
      String xml = sourcePipeline.getXml(effectiveVariables);
      return new PipelineMeta(xml, effectiveMetadataProvider, effectiveVariables);
    } catch (HopException e) {
      throw new HopRuntimeException("Unable to serialize and clone the preview pipeline", e);
    }
  }

  private PipelineMeta structuralClone(PipelineMeta sourcePipeline) {
    PipelineMeta clone = new PipelineMeta();
    clone.setName(sourcePipeline.getName());
    clone.setDescription(sourcePipeline.getDescription());

    Map<String, TransformMeta> transformCopies = new HashMap<>();
    for (TransformMeta sourceTransform : sourcePipeline.getTransforms()) {
      TransformMeta transformCopy = (TransformMeta) sourceTransform.clone();
      transformCopy.setParentPipelineMeta(clone);
      clone.addTransform(transformCopy);
      transformCopies.put(sourceTransform.getName(), transformCopy);
    }

    for (PipelineHopMeta sourceHop : sourcePipeline.getPipelineHops()) {
      TransformMeta from = transformCopies.get(sourceHop.getFromTransform().getName());
      TransformMeta to = transformCopies.get(sourceHop.getToTransform().getName());
      if (from != null && to != null) {
        clone.addPipelineHop(new PipelineHopMeta(from, to, sourceHop.isEnabled()));
      }
    }
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
