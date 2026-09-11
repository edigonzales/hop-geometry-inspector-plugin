package ch.so.agi.hop.geometry.inspector;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.xml.XmlHandler;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.w3c.dom.Node;

public class PreviewPipelinePruner {

  public PipelineMeta cloneAndKeepUpstream(
      PipelineMeta sourcePipeline,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String targetTransformName)
      throws HopException {
    PipelineMeta clone = copyPipeline(sourcePipeline, variables, metadataProvider);
    TransformMeta targetClone = clone.findTransform(targetTransformName);
    if (targetClone == null) {
      throw new IllegalArgumentException(
          "Target transform not found in pipeline copy: " + targetTransformName);
    }

    Set<String> keepNames = findUpstreamTransformNames(clone, targetClone);
    prunePipelineHops(clone, keepNames);
    pruneTransforms(clone, keepNames);
    return clone;
  }

  private PipelineMeta copyPipeline(
      PipelineMeta sourcePipeline, IVariables variables, IHopMetadataProvider metadataProvider)
      throws HopException {
    String xml = sourcePipeline.getXml(variables);
    Node node = XmlHandler.loadXmlString(xml, PipelineMeta.XML_TAG);
    PipelineMeta copy = new PipelineMeta();
    copy.loadXml(node, sourcePipeline.getFilename(), metadataProvider, variables);
    return copy;
  }

  private Set<String> findUpstreamTransformNames(PipelineMeta pipelineMeta, TransformMeta target) {
    Set<String> keepNames = new HashSet<>();
    ArrayDeque<TransformMeta> queue = new ArrayDeque<>();
    queue.add(target);

    while (!queue.isEmpty()) {
      TransformMeta current = queue.removeFirst();
      if (!keepNames.add(current.getName())) {
        continue;
      }

      List<TransformMeta> previousTransforms = pipelineMeta.findPreviousTransforms(current);
      if (previousTransforms != null) {
        queue.addAll(previousTransforms);
      }
    }

    return keepNames;
  }

  private void prunePipelineHops(PipelineMeta pipelineMeta, Set<String> keepNames) {
    List<PipelineHopMeta> hops = pipelineMeta.getPipelineHops();
    for (int i = hops.size() - 1; i >= 0; i--) {
      PipelineHopMeta hop = hops.get(i);
      if (!keepNames.contains(hop.getFromTransform().getName())
          || !keepNames.contains(hop.getToTransform().getName())) {
        pipelineMeta.removePipelineHop(i);
      }
    }
  }

  private void pruneTransforms(PipelineMeta pipelineMeta, Set<String> keepNames) {
    List<TransformMeta> transforms = pipelineMeta.getTransforms();
    for (int i = transforms.size() - 1; i >= 0; i--) {
      TransformMeta transform = transforms.get(i);
      if (!keepNames.contains(transform.getName())) {
        pipelineMeta.removeTransform(i);
      }
    }
  }
}
