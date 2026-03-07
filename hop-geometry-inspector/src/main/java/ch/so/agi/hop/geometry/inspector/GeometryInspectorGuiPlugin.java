package ch.so.agi.hop.geometry.inspector;

import ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult;
import ch.so.agi.hop.geometry.inspector.model.GeometryFieldCandidate;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import ch.so.agi.hop.geometry.inspector.sampling.GeometrySamplerService;
import ch.so.agi.hop.geometry.inspector.ui.GeometryInspectorFallbackDialog;
import ch.so.agi.hop.geometry.inspector.ui.GeometryInspectorOptionsDialog;
import ch.so.agi.hop.geometry.inspector.ui.GeometryInspectorSwtViewer;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import org.apache.hop.core.action.GuiContextAction;
import org.apache.hop.core.action.GuiContextActionFilter;
import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.core.gui.plugin.action.GuiActionType;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.ui.core.dialog.ErrorDialog;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.file.pipeline.context.HopGuiPipelineTransformContext;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

@GuiPlugin
public class GeometryInspectorGuiPlugin {

  static final String ACTION_ID = "pipeline-graph-transform-14500-inspect-geometries";

  private final GeometryFieldDetector geometryFieldDetector = new GeometryFieldDetector();
  private final GeometrySamplerService geometrySamplerService = new GeometrySamplerService();
  private final GeometryFeatureBuilder geometryFeatureBuilder = new GeometryFeatureBuilder();
  private final GeometryInspectorSettingsService settingsService =
      new GeometryInspectorSettingsService();

  @GuiContextAction(
      id = ACTION_ID,
      parentId = HopGuiPipelineTransformContext.CONTEXT_ID,
      type = GuiActionType.Info,
      name = "Inspect geometries...",
      tooltip = "Open a visual inspector for sampled transform geometries",
      image = "ui/images/preview.svg",
      category = "Preview",
      categoryOrder = "3")
  public void inspectGeometries(HopGuiPipelineTransformContext context) {
    try {
      List<GeometryFieldCandidate> outputCandidates = detectOutputCandidates(context);
      List<GeometryFieldCandidate> inputCandidates = detectInputCandidates(context);
      if (outputCandidates.isEmpty() && inputCandidates.isEmpty()) {
        showInfo(
            "No geometry field candidates",
            "No geometry-compatible field was detected on the selected transform input or output.");
        return;
      }

      GeometryInspectorOptionsDialog dialog =
          new GeometryInspectorOptionsDialog(
              HopGui.getInstance().getShell(), outputCandidates, inputCandidates, settingsService);
      GeometryInspectorOptions options = dialog.open();
      if (options == null) {
        return;
      }

      GeometryInspectorBackgroundMapConfig backgroundMapConfig =
          settingsService.loadBackgroundMapConfig();

      ThreadFactory threadFactory =
          GeometryInspectorClassLoaderSupport.newPluginContextThreadFactory();
      Thread worker =
          threadFactory.newThread(
              () -> {
                try {
                  GeometryInspectorClassLoaderSupport.withPluginContextClassLoader(
                      () -> runInspection(context, options, backgroundMapConfig));
                } catch (RuntimeException e) {
                  throw e;
                } catch (Exception e) {
                  throw new RuntimeException("Geometry inspector worker failed", e);
                }
              });
      worker.setName("geometry-inspector-worker-" + context.getTransformMeta().getName());
      worker.setDaemon(true);
      worker.start();
    } catch (Exception e) {
      new ErrorDialog(
          HopGui.getInstance().getShell(),
          "Geometry inspector",
          "Unable to initialize geometry inspection for transform '"
              + context.getTransformMeta().getName()
              + "'.",
          e);
    }
  }

  @GuiContextActionFilter(parentId = HopGuiPipelineTransformContext.CONTEXT_ID)
  public boolean filterInspectAction(String contextActionId, HopGuiPipelineTransformContext context) {
    if (!ACTION_ID.equals(contextActionId)) {
      return true;
    }

    try {
      return !detectOutputCandidates(context).isEmpty() || !detectInputCandidates(context).isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  private void runInspection(
      HopGuiPipelineTransformContext context,
      GeometryInspectorOptions options,
      GeometryInspectorBackgroundMapConfig backgroundMapConfig) {
    SamplingResult samplingResult = null;
    GeometryBuildResult buildResult = null;

    try {
      samplingResult = geometrySamplerService.sample(context, options);

      if (samplingResult.rowMeta() == null || samplingResult.rows().isEmpty()) {
        SamplingResult finalSamplingResult = samplingResult;
        Display.getDefault()
            .asyncExec(
                () ->
                    GeometryInspectorFallbackDialog.showSummary(
                        HopGui.getInstance().getShell(),
                        "No sampled rows observed",
                        finalSamplingResult,
                        new GeometryBuildResult(
                            List.of(), null, null, 0, 0, List.of(), null, null, false, "")));
        return;
      }

      GeometryInspectionFieldSelection fieldSelection =
          new GeometryInspectionFieldSelector()
              .resolve(samplingResult.rowMeta(), options.geometryField());
      if (fieldSelection.selectedField() == null || fieldSelection.selectedField().isBlank()) {
        SamplingResult finalSamplingResult =
            samplingResult.appendSideResolutionMessage(
                "No geometry field candidates were detected on the inspected side.");
        Display.getDefault()
            .asyncExec(
                () ->
                    GeometryInspectorFallbackDialog.showSummary(
                        HopGui.getInstance().getShell(),
                        "No geometry field candidates",
                        finalSamplingResult,
                        new GeometryBuildResult(
                            List.of(), null, null, 0, 0, List.of(), null, null, false, "")));
        return;
      }
      if (!fieldSelection.message().isBlank()) {
        samplingResult = samplingResult.appendSideResolutionMessage(fieldSelection.message());
      }

      buildResult =
          geometryFeatureBuilder.build(
              samplingResult.rowMeta(), samplingResult.rows(), fieldSelection.selectedField());

      if (!buildResult.hasRenderableFeatures()) {
        SamplingResult finalSamplingResult = samplingResult;
        GeometryBuildResult finalBuildResult = buildResult;
        Display.getDefault()
            .asyncExec(
                () ->
                    GeometryInspectorFallbackDialog.showSummary(
                        HopGui.getInstance().getShell(),
                        "No renderable geometries",
                        finalSamplingResult,
                        finalBuildResult));
        return;
      }

      SamplingResult finalSamplingResult = samplingResult;
      GeometryBuildResult finalBuildResult = buildResult;
      Display.getDefault()
          .asyncExec(
              () -> {
                try {
                  GeometryInspectorSwtViewer viewer =
                      new GeometryInspectorSwtViewer(
                          HopGui.getInstance().getShell(),
                          finalSamplingResult,
                          geometryFeatureBuilder,
                          fieldSelection.geometryFields(),
                          fieldSelection.selectedField(),
                          finalBuildResult,
                          backgroundMapConfig);
                  viewer.open();
                } catch (Throwable throwable) {
                  Display.getDefault()
                      .asyncExec(
                          () ->
                              GeometryInspectorFallbackDialog.showError(
                                  HopGui.getInstance().getShell(),
                                  "Geometry inspector fallback",
                                  "SWT viewer initialization failed. Showing summary fallback.",
                                  throwable,
                                  finalSamplingResult,
                                  finalBuildResult));
                }
              });

    } catch (Exception e) {
      SamplingResult finalSamplingResult =
          samplingResult == null
              ? new SamplingResult(
                  List.of(),
                  null,
                  false,
                  "",
                  GeometryInspectionSide.AUTO,
                  null,
                  false,
                  "")
              : samplingResult;
      GeometryBuildResult finalBuildResult =
          buildResult == null
              ? new GeometryBuildResult(
                  List.of(), null, null, 0, 0, List.of(), null, null, false, "")
              : buildResult;

      Display.getDefault()
          .asyncExec(
              () ->
                  GeometryInspectorFallbackDialog.showError(
                      HopGui.getInstance().getShell(),
                      "Geometry inspector",
                      "Geometry inspection failed.",
                      e,
                      finalSamplingResult,
                      finalBuildResult));
    }
  }

  private void showInfo(String title, String message) {
    Shell shell = HopGui.getInstance().getShell();
    MessageBox messageBox = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
    messageBox.setText(title);
    messageBox.setMessage(message);
    messageBox.open();
  }

  private List<GeometryFieldCandidate> detectOutputCandidates(HopGuiPipelineTransformContext context)
      throws Exception {
    IRowMeta outputRowMeta =
        context
            .getPipelineMeta()
            .getTransformFields(context.getPipelineGraph().getVariables(), context.getTransformMeta());
    return geometryFieldDetector.detectCandidates(outputRowMeta);
  }

  private List<GeometryFieldCandidate> detectInputCandidates(HopGuiPipelineTransformContext context)
      throws Exception {
    IRowMeta inputRowMeta =
        context
            .getPipelineMeta()
            .getPrevTransformFields(
                context.getPipelineGraph().getVariables(), context.getTransformMeta());
    return geometryFieldDetector.detectCandidates(inputRowMeta);
  }
}
