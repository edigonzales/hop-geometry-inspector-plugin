package ch.so.agi.hop.geometry.inspector.sampling;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;

public interface GeometryPipelineSamplerExecutor {

  SamplingResult execute(
      PipelineMeta previewPipeline,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String targetTransformName,
      GeometryInspectorOptions options)
      throws HopException, InterruptedException;
}
