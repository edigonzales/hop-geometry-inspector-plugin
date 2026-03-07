package ch.so.agi.hop.geometry.inspector.sampling;

import ch.so.agi.hop.geometry.inspector.PreviewPipelinePruner;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.ui.hopgui.file.pipeline.context.HopGuiPipelineTransformContext;

public class GeometrySamplerService {

  private final PreviewPipelinePruner previewPipelinePruner;
  private final GeometryPipelineSamplerExecutor samplerExecutor;

  public GeometrySamplerService() {
    this(new PreviewPipelinePruner(), new LocalGeometryPipelineSamplerExecutor());
  }

  GeometrySamplerService(
      PreviewPipelinePruner previewPipelinePruner, GeometryPipelineSamplerExecutor samplerExecutor) {
    this.previewPipelinePruner = previewPipelinePruner;
    this.samplerExecutor = samplerExecutor;
  }

  public SamplingResult sample(
      HopGuiPipelineTransformContext context, GeometryInspectorOptions options)
      throws HopException, InterruptedException {

    return sample(
        context.getPipelineMeta(),
        context.getPipelineGraph().getVariables(),
        context.getPipelineMeta().getMetadataProvider(),
        context.getTransformMeta().getName(),
        options);
  }

  public SamplingResult sample(
      PipelineMeta sourcePipeline,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String targetTransformName,
      GeometryInspectorOptions options)
      throws HopException, InterruptedException {
    PipelineMeta previewPipeline =
        previewPipelinePruner.cloneAndKeepUpstream(sourcePipeline, targetTransformName);
    return samplerExecutor.execute(
        previewPipeline, variables, metadataProvider, targetTransformName, options);
  }
}
