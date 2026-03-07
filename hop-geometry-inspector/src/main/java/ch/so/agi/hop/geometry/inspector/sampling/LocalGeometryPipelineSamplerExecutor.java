package ch.so.agi.hop.geometry.inspector.sampling;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.SamplingMode;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.util.List;
import org.apache.commons.lang.StringUtils;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IEngineComponent;
import org.apache.hop.pipeline.engines.local.LocalPipelineEngine;
import org.apache.hop.pipeline.transform.ITransform;
import org.apache.hop.pipeline.transform.RowAdapter;

public class LocalGeometryPipelineSamplerExecutor implements GeometryPipelineSamplerExecutor {

  @Override
  public SamplingResult execute(
      PipelineMeta previewPipeline,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      String targetTransformName,
      GeometryInspectorOptions options)
      throws HopException, InterruptedException {

    LocalPipelineEngine pipeline = new LocalPipelineEngine(previewPipeline, variables, null);
    if (metadataProvider != null) {
      pipeline.setMetadataProvider(metadataProvider);
    }
    pipeline.copyParametersFromDefinitions(previewPipeline);

    for (String parameterName : previewPipeline.listParameters()) {
      String value = variables.getVariable(parameterName);
      if (StringUtils.isEmpty(value)) {
        value = previewPipeline.getParameterDefault(parameterName);
      }
      pipeline.setParameterValue(parameterName, value);
      pipeline.setVariable(parameterName, value);
    }
    pipeline.activateParameters(variables);

    pipeline.prepareExecution();

    GeometrySampleCollector collector =
        new GeometrySampleCollector(options.sampleSize(), options.mode());

    List<IEngineComponent> components = pipeline.getComponentCopies(targetTransformName);
    if (components.isEmpty()) {
      throw new HopException("No transform copies found for: " + targetTransformName);
    }

    for (IEngineComponent component : components) {
      if (!(component instanceof ITransform transform)) {
        continue;
      }

      transform.addRowListener(
          new RowAdapter() {
            @Override
            public void rowWrittenEvent(IRowMeta rowMeta, Object[] row) throws HopTransformException {
              try {
                collector.accept(rowMeta, row);
                if (options.mode() == SamplingMode.FIRST && collector.isFull()) {
                  pipeline.stopAll();
                }
              } catch (Exception e) {
                throw new HopTransformException("Failed to collect sampled row", e);
              }
            }
          });
    }

    pipeline.startThreads();
    boolean timedOut = waitForCompletion(pipeline, options);
    pipeline.waitUntilFinished();

    String reason = "";
    boolean partial = false;
    if (timedOut) {
      reason = "Sampling timeout reached";
      partial = true;
    } else if (pipeline.getErrors() > 0) {
      reason = "Pipeline finished with errors";
      partial = options.mode() != SamplingMode.FIRST;
    }

    return new SamplingResult(collector.snapshotRows(), collector.snapshotRowMeta(), partial, reason);
  }

  private boolean waitForCompletion(LocalPipelineEngine pipeline, GeometryInspectorOptions options)
      throws InterruptedException {
    long timeoutMillis = options.timeout().toMillis();
    long start = System.currentTimeMillis();

    while (pipeline.isRunning() || pipeline.isPreparing()) {
      if (options.mode() == SamplingMode.FIRST) {
        Thread.sleep(50L);
        continue;
      }

      long elapsed = System.currentTimeMillis() - start;
      if (elapsed >= timeoutMillis) {
        pipeline.stopAll();
        return true;
      }

      Thread.sleep(100L);
    }

    return false;
  }
}
