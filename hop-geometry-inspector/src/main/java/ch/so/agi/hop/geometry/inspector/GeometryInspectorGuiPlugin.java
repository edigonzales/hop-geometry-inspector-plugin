package ch.so.agi.hop.geometry.inspector;

import ch.so.agi.hop.geometry.inspector.model.GeometryFieldCandidate;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import ch.so.agi.hop.geometry.inspector.sampling.GeometrySamplerService;
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

  private final java.util.Map<Object, GeometryInspectorSwtViewer> workspaces =
      new java.util.WeakHashMap<>();

  private ch.so.agi.hop.geometry.inspector.data.CacheRepository caches;

  private final GeometryFieldDetector geometryFieldDetector = new GeometryFieldDetector();
  private final GeometrySamplerService geometrySamplerService = new GeometrySamplerService();
  private final GeometryFeatureBuilder geometryFeatureBuilder = new GeometryFeatureBuilder();
  private final GeometryInspectorSettingsService settingsService =
      new GeometryInspectorSettingsService();

  @GuiContextAction(
      id = ACTION_ID,
      parentId = HopGuiPipelineTransformContext.CONTEXT_ID,
      type = GuiActionType.Info,
      name = "Inspect data...",
      tooltip = "Inspect transform rows and geometries",
      image = "ui/images/preview.svg",
      category = "Preview",
      categoryOrder = "3")
  public void inspectGeometries(HopGuiPipelineTransformContext context) {
    startInspection(context, false);
  }

  private void startInspection(
      HopGuiPipelineTransformContext context, boolean completeCacheDefault) {
    try {
      List<GeometryFieldCandidate> outputCandidates = detectOutputCandidates(context);
      List<GeometryFieldCandidate> inputCandidates = detectInputCandidates(context);
      GeometryInspectorOptionsDialog dialog =
          new GeometryInspectorOptionsDialog(
              HopGui.getInstance().getShell(), outputCandidates, inputCandidates, settingsService);
      GeometryInspectorOptions options = dialog.open(completeCacheDefault);
      if (options == null) {
        return;
      }

      if (caches == null) caches = ch.so.agi.hop.geometry.inspector.data.CacheRepository.defaults();
      var selectedStreams =
          ch.so.agi.hop.geometry.inspector.ui.StreamSelectionDialog.open(
              HopGui.getInstance().getShell(),
              ch.so.agi.hop.geometry.inspector.data.CaptureService.streams(
                  context.getPipelineMeta(), context.getPipelineGraph().getVariables()),
              context.getTransformMeta().getName(),
              options.inspectionSide());
      if (selectedStreams.isEmpty()) return;
      var variables = snapshotVariables(context);
      var source =
          ch.so.agi.hop.geometry.inspector.data.CaptureService.copy(
              context.getPipelineMeta(),
              variables,
              context.getPipelineMeta().getMetadataProvider());
      java.util.concurrent.atomic.AtomicBoolean cancelled =
          new java.util.concurrent.atomic.AtomicBoolean();
      Shell progress = new Shell(HopGui.getInstance().getShell(), SWT.DIALOG_TRIM);
      progress.setText("Capturing data");
      progress.setLayout(new org.eclipse.swt.layout.GridLayout());
      var progressLabel = new org.eclipse.swt.widgets.Label(progress, SWT.NONE);
      progressLabel.setText("Running selected pipeline section...");
      var cancel = new org.eclipse.swt.widgets.Button(progress, SWT.PUSH);
      cancel.setText("Cancel capture");
      cancel.addListener(SWT.Selection, e -> cancelled.set(true));
      progress.addListener(SWT.Close, e -> cancelled.set(true));
      progress.pack();
      progress.open();
      GeometryInspectorBackgroundMapConfig backgroundMapConfig =
          settingsService.loadBackgroundMapConfig();

      ThreadFactory threadFactory =
          GeometryInspectorClassLoaderSupport.newPluginContextThreadFactory();
      Thread worker =
          threadFactory.newThread(
              () -> {
                try {
                  GeometryInspectorClassLoaderSupport.withPluginContextClassLoader(
                      () ->
                          runInspection(
                              context,
                              source,
                              variables,
                              options,
                              backgroundMapConfig,
                              selectedStreams,
                              cancelled,
                              progress));
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
          "Data Inspector",
          "Unable to initialize geometry inspection for transform '"
              + context.getTransformMeta().getName()
              + "'.",
          e);
    }
  }

  @GuiContextAction(
      id = "pipeline-graph-transform-14501-inspector-caches",
      parentId = HopGuiPipelineTransformContext.CONTEXT_ID,
      type = GuiActionType.Info,
      name = "Data Inspector caches / replay...",
      tooltip = "Open cached rows or replay a pipeline section",
      image = "ui/images/preview.svg",
      category = "Preview",
      categoryOrder = "3")
  public void manageCaches(HopGuiPipelineTransformContext context) {
    try {
      if (caches == null) caches = ch.so.agi.hop.geometry.inspector.data.CacheRepository.defaults();
      caches.cleanup();
      var variables = snapshotVariables(context);
      var source =
          ch.so.agi.hop.geometry.inspector.data.CaptureService.copy(
              context.getPipelineMeta(),
              variables,
              context.getPipelineMeta().getMetadataProvider());
      var choice =
          ch.so.agi.hop.geometry.inspector.ui.CacheDialog.open(
              HopGui.getInstance().getShell(),
              caches,
              context.getPipelineMeta().getTransforms().stream()
                  .map(t -> t.getName())
                  .toArray(String[]::new),
              context.getTransformMeta().getName(),
              source,
              variables);
      if (choice == null) return;
      if (choice.action().equals("Record")) {
        startInspection(context, true);
        return;
      }
      java.util.concurrent.atomic.AtomicBoolean cancelled =
          new java.util.concurrent.atomic.AtomicBoolean();
      Shell[] progress = {null};
      var worker =
          GeometryInspectorClassLoaderSupport.newPluginContextThreadFactory()
              .newThread(
                  () -> {
                    java.util.List<ch.so.agi.hop.geometry.inspector.data.InspectionResult> inputs =
                        new java.util.ArrayList<>();
                    boolean transferred = false;
                    try {
                      for (var id : choice.caches()) inputs.add(caches.result(id));
                      if (choice.action().equals("Open")) {
                        for (var result : inputs)
                          showResult(context, result, settingsService.loadBackgroundMapConfig());
                        transferred = true;
                        return;
                      }
                      if (choice.action().equals("Record again")) {
                        if (inputs.isEmpty())
                          throw new IllegalArgumentException("Select streams to record again");
                        var selected =
                            inputs.stream()
                                .map(ch.so.agi.hop.geometry.inspector.data.InspectionResult::stream)
                                .distinct()
                                .toList();
                        var available =
                            ch.so.agi.hop.geometry.inspector.data.CaptureService.streams(
                                source, variables);
                        if (!available.containsAll(selected))
                          throw new IllegalArgumentException(
                              "The selected cache streams do not exist in this pipeline");
                        Display.getDefault()
                            .syncExec(() -> progress[0] = openCaptureProgress(cancelled));
                        var options =
                            new GeometryInspectorOptions(
                                Integer.MAX_VALUE,
                                ch.so.agi.hop.geometry.inspector.model.SamplingMode.FIRST,
                                GeometryInspectionSide.OUTPUT,
                                "",
                                java.time.Duration.ofMinutes(5));
                        var results =
                            new ch.so.agi.hop.geometry.inspector.data.CaptureService()
                                .capture(
                                    source,
                                    variables,
                                    source.getMetadataProvider(),
                                    selected,
                                    options,
                                    caches,
                                    cancelled::get);
                        for (var result : results)
                          showResult(context, result, settingsService.loadBackgroundMapConfig());
                        return;
                      }
                      var plan =
                          new ch.so.agi.hop.geometry.inspector.data.ReplayPlanner()
                              .plan(
                                  source,
                                  variables,
                                  source.getMetadataProvider(),
                                  ch.so.agi.hop.geometry.inspector.data.ReplayPlanner.Mode.valueOf(
                                      choice.action()),
                                  context.getTransformMeta().getName(),
                                  choice.end(),
                                  inputs);
                      boolean[] execute = {false};
                      Display.getDefault()
                          .syncExec(
                              () -> {
                                MessageBox box =
                                    new MessageBox(
                                        HopGui.getInstance().getShell(),
                                        SWT.ICON_INFORMATION | SWT.OK | SWT.CANCEL);
                                box.setText("Execute replay");
                                box.setMessage(plan.description());
                                execute[0] = box.open() == SWT.OK;
                              });
                      if (!execute[0]) return;
                      Display.getDefault()
                          .syncExec(
                              () -> {
                                progress[0] =
                                    new Shell(HopGui.getInstance().getShell(), SWT.DIALOG_TRIM);
                                progress[0].setText("Replaying cached data");
                                progress[0].setLayout(new org.eclipse.swt.layout.GridLayout());
                                var cancel =
                                    new org.eclipse.swt.widgets.Button(progress[0], SWT.PUSH);
                                cancel.setText("Cancel replay");
                                cancel.addListener(SWT.Selection, e -> cancelled.set(true));
                                progress[0].addListener(SWT.Close, e -> cancelled.set(true));
                                progress[0].pack();
                                progress[0].open();
                              });
                      var streams =
                          ch.so.agi.hop.geometry.inspector.data.CaptureService.streams(
                                  plan.pipeline(), variables)
                              .stream()
                              .filter(
                                  stream ->
                                      plan.executing().contains(stream.transform())
                                          && stream.direction()
                                              == ch.so.agi.hop.geometry.inspector.data
                                                  .StreamDescriptor.Direction.OUTPUT
                                          && stream.kind()
                                              == ch.so.agi.hop.geometry.inspector.data
                                                  .StreamDescriptor.Kind.MAIN)
                              .toList();
                      var options =
                          new GeometryInspectorOptions(
                              Integer.MAX_VALUE,
                              ch.so.agi.hop.geometry.inspector.model.SamplingMode.FIRST,
                              GeometryInspectionSide.OUTPUT,
                              "",
                              java.time.Duration.ofMinutes(5));
                      var results =
                          new ch.so.agi.hop.geometry.inspector.data.CaptureService()
                              .execute(
                                  source,
                                  plan.pipeline(),
                                  variables,
                                  source.getMetadataProvider(),
                                  streams,
                                  options,
                                  caches,
                                  cancelled::get);
                      for (var result : results) {
                        caches.recordParents(result.id(), plan.inputs());
                        showResult(context, result, settingsService.loadBackgroundMapConfig());
                      }
                    } catch (Exception error) {
                      Display.getDefault()
                          .asyncExec(
                              () ->
                                  new ErrorDialog(
                                      HopGui.getInstance().getShell(),
                                      "Data Inspector",
                                      "Cache / replay failed",
                                      error));
                    } finally {
                      Display.getDefault()
                          .asyncExec(
                              () -> {
                                if (progress[0] != null && !progress[0].isDisposed())
                                  progress[0].dispose();
                              });
                      if (!transferred)
                        for (var result : inputs)
                          try {
                            result.rows().close();
                          } catch (Exception ignored) {
                          }
                    }
                  });
      worker.setDaemon(true);
      worker.start();
    } catch (Exception error) {
      new ErrorDialog(
          HopGui.getInstance().getShell(), "Data Inspector", "Unable to open caches", error);
    }
  }

  @GuiContextActionFilter(parentId = HopGuiPipelineTransformContext.CONTEXT_ID)
  public boolean filterInspectAction(
      String contextActionId, HopGuiPipelineTransformContext context) {
    if (!ACTION_ID.equals(contextActionId)) {
      return true;
    }

    return context != null && context.getTransformMeta() != null;
  }

  private Shell openCaptureProgress(java.util.concurrent.atomic.AtomicBoolean cancelled) {
    Shell progress = new Shell(HopGui.getInstance().getShell(), SWT.DIALOG_TRIM);
    progress.setText("Recording complete cache");
    progress.setLayout(new org.eclipse.swt.layout.GridLayout());
    var cancel = new org.eclipse.swt.widgets.Button(progress, SWT.PUSH);
    cancel.setText("Cancel capture");
    cancel.addListener(SWT.Selection, e -> cancelled.set(true));
    progress.addListener(SWT.Close, e -> cancelled.set(true));
    progress.pack();
    progress.open();
    return progress;
  }

  private org.apache.hop.core.variables.IVariables snapshotVariables(
      HopGuiPipelineTransformContext context) {
    var source = context.getPipelineGraph().getVariables();
    var snapshot = new org.apache.hop.core.variables.Variables();
    for (String name : source.getVariableNames())
      snapshot.setVariable(name, source.getVariable(name));
    return snapshot;
  }

  private void runInspection(
      HopGuiPipelineTransformContext context,
      org.apache.hop.pipeline.PipelineMeta source,
      org.apache.hop.core.variables.IVariables variables,
      GeometryInspectorOptions options,
      GeometryInspectorBackgroundMapConfig config,
      java.util.List<ch.so.agi.hop.geometry.inspector.data.StreamDescriptor> streams,
      java.util.concurrent.atomic.AtomicBoolean cancelled,
      Shell progress) {
    try {
      var results =
          new ch.so.agi.hop.geometry.inspector.data.CaptureService()
              .capture(
                  source,
                  variables,
                  source.getMetadataProvider(),
                  streams,
                  options,
                  caches,
                  cancelled::get);
      for (var result : results) showResult(context, result, config, options.geometryField());
    } catch (Exception error) {
      Display.getDefault()
          .asyncExec(
              () ->
                  new ErrorDialog(
                      HopGui.getInstance().getShell(), "Data Inspector", "Capture failed", error));
    } finally {
      Display.getDefault()
          .asyncExec(
              () -> {
                if (!progress.isDisposed()) progress.dispose();
              });
    }
  }

  private void showResult(
      HopGuiPipelineTransformContext context,
      ch.so.agi.hop.geometry.inspector.data.InspectionResult result,
      GeometryInspectorBackgroundMapConfig config) {
    showResult(context, result, config, "");
  }

  private void showResult(
      HopGuiPipelineTransformContext context,
      ch.so.agi.hop.geometry.inspector.data.InspectionResult result,
      GeometryInspectorBackgroundMapConfig config,
      String requestedField) {
    var rows = new ch.so.agi.hop.geometry.inspector.data.StoreRowList(result.rows());
    var side =
        result.stream().direction()
                == ch.so.agi.hop.geometry.inspector.data.StreamDescriptor.Direction.INPUT
            ? GeometryInspectionSide.INPUT
            : GeometryInspectionSide.OUTPUT;
    var sample =
        new SamplingResult(
            rows,
            result.rows().rowMeta(),
            result.completion()
                != ch.so.agi.hop.geometry.inspector.data.InspectionResult.Completion.COMPLETE,
            (result.sample() ? "Sample: " : "Cache: ") + result.reason(),
            side,
            side,
            false,
            result.stream().label());
    var selection =
        new GeometryInspectionFieldSelector()
            .resolve(
                sample.rowMeta(),
                sample.rowMeta().indexOfValue(requestedField) >= 0 ? requestedField : "");
    var build =
        geometryFeatureBuilder.buildLimited(
            sample.rowMeta(), rows, selection.selectedField(), 50_000, row -> true, () -> false);
    String label =
        result.pipeline()
            + " / "
            + result.stream().label()
            + " / "
            + result.capturedAt()
            + " / "
            + (result.sample() ? "sample" : "cache")
            + " / "
            + result.completion();
    Display.getDefault()
        .asyncExec(
            () -> {
              try {
                if (context.getPipelineGraph().isDisposed()) {
                  result.rows().close();
                  return;
                }
                var viewer = workspaces.get(context.getPipelineGraph());
                if (viewer == null || viewer.isDisposed()) {
                  viewer =
                      new GeometryInspectorSwtViewer(
                          HopGui.getInstance().getShell(),
                          sample,
                          geometryFeatureBuilder,
                          selection.geometryFields(),
                          selection.selectedField(),
                          build,
                          config);
                  workspaces.put(context.getPipelineGraph(), viewer);
                  var ownedViewer = viewer;
                  context
                      .getPipelineGraph()
                      .addDisposeListener(
                          event -> {
                            ownedViewer.disposeWindow();
                            workspaces.remove(context.getPipelineGraph());
                          });
                  viewer.setActiveResultLabel(label);
                } else
                  viewer.addResult(
                      sample,
                      selection.geometryFields(),
                      selection.selectedField(),
                      build,
                      label,
                      false);
                viewer.own(result.rows());
                viewer.open();
              } catch (Throwable error) {
                try {
                  result.rows().close();
                } catch (Exception ignored) {
                }
                new ErrorDialog(
                    HopGui.getInstance().getShell(),
                    "Data Inspector",
                    "Unable to open results",
                    error);
              }
            });
  }

  private void showInfo(String title, String message) {
    Shell shell = HopGui.getInstance().getShell();
    MessageBox messageBox = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
    messageBox.setText(title);
    messageBox.setMessage(message);
    messageBox.open();
  }

  private List<GeometryFieldCandidate> detectOutputCandidates(
      HopGuiPipelineTransformContext context) throws Exception {
    IRowMeta outputRowMeta =
        context
            .getPipelineMeta()
            .getTransformFields(
                context.getPipelineGraph().getVariables(), context.getTransformMeta());
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
