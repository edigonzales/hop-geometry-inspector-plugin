package ch.so.agi.hop.geometry.inspector;

import ch.so.agi.hop.geometry.inspector.data.CaptureService;
import ch.so.agi.hop.geometry.inspector.data.InspectionResult;
import ch.so.agi.hop.geometry.inspector.data.MemoryRowStore;
import ch.so.agi.hop.geometry.inspector.data.StoreRowList;
import ch.so.agi.hop.geometry.inspector.data.StreamDescriptor;
import ch.so.agi.hop.geometry.inspector.model.GeometryFieldCandidate;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import ch.so.agi.hop.geometry.inspector.ui.GeometryInspectorOptionsDialog;
import ch.so.agi.hop.geometry.inspector.ui.GeometryInspectorSwtViewer;
import ch.so.agi.hop.geometry.inspector.ui.StreamSelectionDialog;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.hop.core.action.GuiContextAction;
import org.apache.hop.core.action.GuiContextActionFilter;
import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.core.gui.plugin.action.GuiActionType;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.ui.core.dialog.ErrorDialog;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.file.pipeline.HopGuiPipelineGraph;
import org.apache.hop.ui.hopgui.file.pipeline.context.HopGuiPipelineTransformContext;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

@GuiPlugin
public class GeometryInspectorGuiPlugin {

  static final String ACTION_ID = "pipeline-graph-transform-14500-inspect-geometries";

  private final Map<HopGuiPipelineGraph, GeometryInspectorSwtViewer> workspaces = new HashMap<>();

  private final GeometryFieldDetector geometryFieldDetector = new GeometryFieldDetector();
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

    try {
      List<GeometryFieldCandidate> outputCandidates = detectOutputCandidates(context);
      List<GeometryFieldCandidate> inputCandidates = detectInputCandidates(context);
      GeometryInspectorOptionsDialog dialog =
          new GeometryInspectorOptionsDialog(
              HopGui.getInstance().getShell(), outputCandidates, inputCandidates, settingsService);
      GeometryInspectorOptions options = dialog.open();
      if (options == null) {
        return;
      }

      var selectedStreams =
          StreamSelectionDialog.open(
              HopGui.getInstance().getShell(),
              CaptureService.streams(
                  context.getPipelineMeta(), context.getPipelineGraph().getVariables()),
              context.getTransformMeta().getName(),
              options.inspectionSide());
      if (selectedStreams.isEmpty()) return;
      var variables = snapshotVariables(context);
      var source =
          CaptureService.copy(
              context.getPipelineMeta(),
              variables,
              context.getPipelineMeta().getMetadataProvider());
      var cancelled = new AtomicBoolean();
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

      Display display = progress.getDisplay();
      Thread worker =
          GeometryInspectorClassLoaderSupport.newPluginContextThreadFactory()
              .newThread(
                  () -> {
                    runInspection(
                        context,
                        source,
                        variables,
                        options,
                        backgroundMapConfig,
                        selectedStreams,
                        cancelled,
                        progress,
                        display);
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

  @GuiContextActionFilter(parentId = HopGuiPipelineTransformContext.CONTEXT_ID)
  public boolean filterInspectAction(
      String contextActionId, HopGuiPipelineTransformContext context) {
    if (!ACTION_ID.equals(contextActionId)) {
      return true;
    }

    return context != null && context.getTransformMeta() != null;
  }

  private IVariables snapshotVariables(HopGuiPipelineTransformContext context) {
    var source = context.getPipelineGraph().getVariables();
    var snapshot = new Variables();
    for (String name : source.getVariableNames())
      snapshot.setVariable(name, source.getVariable(name));
    return snapshot;
  }

  private void runInspection(
      HopGuiPipelineTransformContext context,
      PipelineMeta source,
      IVariables variables,
      GeometryInspectorOptions options,
      GeometryInspectorBackgroundMapConfig config,
      List<StreamDescriptor> streams,
      AtomicBoolean cancelled,
      Shell progress,
      Display display) {
    BooleanSupplier stopped = () -> cancelled.get() || context.getPipelineGraph().isDisposed();
    List<InspectionResult> results = List.of();
    int transferred = 0;
    try {
      results =
          new CaptureService()
              .capture(source, variables, source.getMetadataProvider(), streams, options, stopped);
      for (var result : results) {
        if (stopped.getAsBoolean()
            || !showResult(context, result, config, options.geometryField(), stopped, display))
          break;
        transferred++;
      }
    } catch (CancellationException ignored) {
      // The cancel action closes the capture without opening a viewer.
    } catch (Exception error) {
      if (!display.isDisposed())
        display.asyncExec(
            () -> {
              if (!context.getPipelineGraph().isDisposed())
                new ErrorDialog(
                    HopGui.getInstance().getShell(), "Data Inspector", "Capture failed", error);
            });
    } finally {
      for (int i = transferred; i < results.size(); i++) {
        try {
          results.get(i).rows().close();
        } catch (IOException error) {
          System.err.println("Unable to close inspector result: " + error.getMessage());
        }
      }
      if (!display.isDisposed())
        display.asyncExec(
            () -> {
              if (!progress.isDisposed()) progress.dispose();
            });
    }
  }

  private boolean showResult(
      HopGuiPipelineTransformContext context,
      InspectionResult result,
      GeometryInspectorBackgroundMapConfig config,
      String requestedField,
      BooleanSupplier stopped,
      Display display) {
    var storedRows = new StoreRowList(result.rows());
    List<Object[]> rows =
        result.rows() instanceof MemoryRowStore ? List.copyOf(storedRows) : storedRows;
    var sample =
        new SamplingResult(
            rows,
            result.rows().rowMeta(),
            result.completion() != InspectionResult.Completion.COMPLETE,
            result.reason(),
            result.stream().label());
    var selection =
        new GeometryInspectionFieldSelector()
            .resolve(
                sample.rowMeta(),
                sample.rowMeta().indexOfValue(requestedField) >= 0 ? requestedField : "");
    var build =
        geometryFeatureBuilder.buildLimited(
            sample.rowMeta(), rows, selection.selectedField(), 50_000, row -> true, stopped);
    String label =
        result.pipeline()
            + " / "
            + result.stream().label()
            + " / "
            + result.capturedAt()
            + " / "
            + (result.sample() ? "Sample" : "All rows")
            + " / "
            + result.completion();

    return display.syncCall(
        () -> {
          var graph = context.getPipelineGraph();
          if (stopped.getAsBoolean()) return false;
          var viewer = workspaces.get(graph);
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
            var openedViewer = viewer;
            DisposeListener closeViewer = event -> openedViewer.disposeWindow();
            graph.addDisposeListener(closeViewer);
            viewer.addDisposeListener(
                event -> {
                  workspaces.remove(graph, openedViewer);
                  if (!graph.isDisposed()) graph.removeDisposeListener(closeViewer);
                });
            workspaces.put(graph, viewer);
            viewer.setActiveResultLabel(label);
          } else {
            viewer.addResult(
                sample, selection.geometryFields(), selection.selectedField(), build, label);
          }
          viewer.open();
          return true;
        });
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
