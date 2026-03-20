package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryFeatureBuilder;
import ch.so.agi.hop.geometry.inspector.GeometryInspectorClassLoaderSupport;
import ch.so.agi.hop.geometry.inspector.GeometrySelectionService;
import ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.style.Style;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.FeatureLayer;
import org.geotools.map.Layer;
import org.geotools.map.MapContent;
import org.geotools.renderer.lite.StreamingRenderer;
import org.geotools.styling.SLD;
import org.geotools.styling.StyleBuilder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Lineal;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.Puntal;

public final class GeometryInspectorSwtViewer implements AutoCloseable {

  private static final int PICK_TOLERANCE_PIXELS = 6;
  private static final int MAX_TABLE_VALUE_LENGTH = 180;
  private static final long OVERLAY_DEBOUNCE_MILLIS = 40L;
  private static final long BACKGROUND_DEBOUNCE_MILLIS = 220L;
  private static final int BACKGROUND_CACHE_SIZE = 8;
  private static final float POINT_FILL_OPACITY = 1.0f;
  private static final float DEFAULT_POINT_SIZE = 11.0f;
  private static final float EMPHASIZED_POINT_SIZE = 22.0f;
  private static final float HIGHLIGHT_POINT_SIZE = 24.0f;
  private static final float DEFAULT_LINE_WIDTH = 2.2f;
  private static final float EMPHASIZED_LINE_WIDTH = 4.5f;
  private static final float HIGHLIGHT_LINE_WIDTH = 5.5f;
  private static final float DEFAULT_POLYGON_STROKE_WIDTH = 2.2f;
  private static final float EMPHASIZED_POLYGON_STROKE_WIDTH = 4.5f;

  private enum ViewportRefreshMode {
    PREVIEW_ONLY,
    DEBOUNCED,
    COMMIT
  }

  private final SamplingResult samplingResult;
  private final GeometryFeatureBuilder featureBuilder;
  private final GeometrySelectionService selectionService;
  private final List<String> geometryFields;
  private final GeometryInspectorBackgroundMapConfig backgroundMapConfig;
  private final GeometryInspectorBackgroundMapClient backgroundMapClient;
  private final GeometryInspectorViewportModel viewportModel = new GeometryInspectorViewportModel();
  private final GeometryInspectorRenderCoordinator<GeometryInspectorRasterData> overlayCoordinator;
  private final GeometryInspectorRenderCoordinator<GeometryInspectorRasterData> backgroundCoordinator;
  private final GeometryInspectorFrameCache<GeometryInspectorFrameKey, GeometryInspectorFrame>
      backgroundFrameCache = new GeometryInspectorFrameCache<>(BACKGROUND_CACHE_SIZE);

  private final Shell shell;
  private final Canvas mapCanvas;
  private final Combo fieldCombo;
  private final GeometryInspectorToolbarButton backgroundToggle;
  private final GeometryInspectorToolbarButton zoomInButton;
  private final GeometryInspectorToolbarButton zoomOutButton;
  private final GeometryInspectorToolbarButton zoomExtentButton;
  private final GeometryInspectorToolbarButton refreshButton;
  private final GeometryInspectorToolbarButton emphasizeSmallFeaturesToggle;
  private final Label inspectionSourceLabel;
  private final Table featureTable;
  private final Label selectionSummaryLabel;
  private final Table attributeTable;
  private final Text geometryDetailText;
  private final Label statusLabel;

  private GeometryBuildResult currentBuildResult;
  private GeometryInspectorFeatureTableModel featureTableModel;
  private GeometryInspectorFrame overlayFrame;
  private GeometryInspectorFrame backgroundFrame;
  private Menu hitCandidateMenu;
  private Integer selectedRowIndex;
  private Integer hoverPreviewRowIndex;
  private String overlayStatus = "idle";
  private String backgroundStatus = "off";
  private String backgroundErrorMessage = "";
  private List<String[]> attributeClipboardRows = List.of();
  private Integer activeSortColumnIndex;
  private int activeSortDirection = SWT.UP;
  private boolean updatingFeatureTableSelection;
  private boolean closed;

  public GeometryInspectorSwtViewer(
      Shell parent,
      SamplingResult samplingResult,
      GeometryFeatureBuilder featureBuilder,
      List<String> geometryFields,
      String selectedField,
      GeometryBuildResult initialBuildResult,
      GeometryInspectorBackgroundMapConfig backgroundMapConfig) {
    this.samplingResult = samplingResult;
    this.featureBuilder = featureBuilder;
    this.geometryFields = List.copyOf(geometryFields);
    this.backgroundMapConfig =
        backgroundMapConfig == null
            ? GeometryInspectorBackgroundMapConfig.empty()
            : backgroundMapConfig;
    this.backgroundMapClient = new GeometryInspectorBackgroundMapClient(this.backgroundMapConfig);
    this.selectionService = new GeometrySelectionService();

    Display display = parent.getDisplay();
    overlayCoordinator = newRenderCoordinator(display, "geometry-inspector-overlay");
    backgroundCoordinator = newRenderCoordinator(display, "geometry-inspector-background");

    shell = new Shell(parent, SWT.SHELL_TRIM | SWT.RESIZE);
    shell.setText("Geometry Inspector");
    shell.setLayout(new GridLayout(1, false));
    shell.setSize(1320, 840);

    Composite header = new Composite(shell, SWT.NONE);
    header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    GridLayout headerLayout = new GridLayout(4, false);
    headerLayout.marginHeight = 8;
    headerLayout.marginWidth = 8;
    headerLayout.horizontalSpacing = 10;
    header.setLayout(headerLayout);

    Label fieldLabel = new Label(header, SWT.NONE);
    fieldLabel.setText("Geometry field:");

    fieldCombo = new Combo(header, SWT.DROP_DOWN | SWT.READ_ONLY);
    fieldCombo.setLayoutData(new GridData(220, SWT.DEFAULT));
    fieldCombo.setItems(this.geometryFields.toArray(String[]::new));
    if (selectedField != null && !selectedField.isBlank()) {
      fieldCombo.setText(selectedField);
    }

    inspectionSourceLabel = new Label(header, SWT.WRAP);
    inspectionSourceLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    inspectionSourceLabel.setText(buildInspectionSourceLabel());

    Composite tools = new Composite(header, SWT.NONE);
    tools.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
    GridLayout toolsLayout = new GridLayout(6, false);
    toolsLayout.marginWidth = 0;
    toolsLayout.marginHeight = 0;
    toolsLayout.horizontalSpacing = 8;
    tools.setLayout(toolsLayout);

    zoomInButton =
        createToolbarButton(
            tools, "Zoom in", GeometryInspectorToolbarIcons.Symbol.ZOOM_IN, false);
    zoomOutButton =
        createToolbarButton(
            tools, "Zoom out", GeometryInspectorToolbarIcons.Symbol.ZOOM_OUT, false);
    zoomExtentButton =
        createToolbarButton(
            tools, "Zoom to extent", GeometryInspectorToolbarIcons.Symbol.ZOOM_EXTENT, false);
    refreshButton =
        createToolbarButton(
            tools, "Refresh geometry view", GeometryInspectorToolbarIcons.Symbol.REFRESH, false);
    emphasizeSmallFeaturesToggle =
        createToolbarButton(
            tools,
            "Emphasize small features",
            GeometryInspectorToolbarIcons.Symbol.EMPHASIZE,
            true);
    emphasizeSmallFeaturesToggle.setToolTipText("Emphasize small features");
    backgroundToggle =
        createToolbarButton(
            tools, "Toggle background map", GeometryInspectorToolbarIcons.Symbol.BACKGROUND, true);
    backgroundToggle.setToolTipText("Toggle background map");

    SashForm sashForm = new SashForm(shell, SWT.HORIZONTAL);
    sashForm.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    Composite mapComposite = new Composite(sashForm, SWT.NONE);
    mapComposite.setLayout(new GridLayout(1, false));
    mapComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    mapCanvas = new Canvas(mapComposite, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND | SWT.BORDER);
    mapCanvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    mapCanvas.setBackground(display.getSystemColor(SWT.COLOR_WHITE));

    Composite infoComposite = new Composite(sashForm, SWT.NONE);
    GridLayout infoLayout = new GridLayout(1, false);
    infoLayout.marginHeight = 8;
    infoLayout.marginWidth = 8;
    infoComposite.setLayout(infoLayout);

    SashForm infoSash = new SashForm(infoComposite, SWT.VERTICAL);
    infoSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    Composite featureComposite = new Composite(infoSash, SWT.NONE);
    featureComposite.setLayout(new GridLayout(1, false));

    Label featureLabel = new Label(featureComposite, SWT.NONE);
    featureLabel.setText("Features");

    featureTable =
        new Table(
            featureComposite,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL | SWT.VIRTUAL);
    featureTable.setHeaderVisible(true);
    featureTable.setLinesVisible(true);
    featureTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    Composite detailComposite = new Composite(infoSash, SWT.NONE);
    detailComposite.setLayout(new GridLayout(1, false));

    selectionSummaryLabel = new Label(detailComposite, SWT.WRAP);
    selectionSummaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    attributeTable =
        new Table(detailComposite, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    attributeTable.setHeaderVisible(true);
    attributeTable.setLinesVisible(true);
    attributeTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    TableColumn nameColumn = new TableColumn(attributeTable, SWT.LEFT);
    nameColumn.setText("Attribute");
    nameColumn.setWidth(180);
    TableColumn valueColumn = new TableColumn(attributeTable, SWT.LEFT);
    valueColumn.setText("Value");
    valueColumn.setWidth(360);

    Label geometryLabel = new Label(detailComposite, SWT.NONE);
    geometryLabel.setText("Geometry (WKT/EWKT)");

    geometryDetailText =
        new Text(detailComposite, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP);
    GridData geometryTextData = new GridData(SWT.FILL, SWT.FILL, true, false);
    geometryTextData.heightHint = 180;
    geometryDetailText.setLayoutData(geometryTextData);
    geometryDetailText.setEditable(false);

    sashForm.setWeights(74, 26);
    infoSash.setWeights(42, 58);

    statusLabel = new Label(shell, SWT.WRAP);
    statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    bindUi();
    clearSelectionPanel();
    applyBuildResult(initialBuildResult, true);
    backgroundToggle.setSelected(
        this.backgroundMapConfig.isValid() && this.backgroundMapConfig.enabledByDefault());
    updateBackgroundAvailability();
    updateInspectionSourceLabel();
    updateStatusLabel();

    shell.addListener(SWT.Dispose, event -> close());
  }

  public void open() {
    if (shell.isDisposed()) {
      return;
    }
    shell.open();
    syncCanvasMetrics(true);
    requestOverlayRender(true);
    if (backgroundToggle.isSelected() && backgroundToggle.isEnabled()) {
      requestBackgroundRender(false);
    }
  }

  private GeometryInspectorRenderCoordinator<GeometryInspectorRasterData> newRenderCoordinator(
      Display display, String threadNamePrefix) {
    return new GeometryInspectorRenderCoordinator<>(
        threadNamePrefix,
        runnable -> {
          if (!display.isDisposed()) {
            display.asyncExec(runnable);
          }
        });
  }

  private void bindUi() {
    fieldCombo.addListener(SWT.Selection, event -> refreshForSelectedField(false));
    zoomInButton.addListener(SWT.Selection, event -> zoomBy(1.0d / 1.2d));
    zoomOutButton.addListener(SWT.Selection, event -> zoomBy(1.2d));
    zoomExtentButton.addListener(SWT.Selection, event -> zoomToExtent());
    refreshButton.addListener(SWT.Selection, event -> refreshForSelectedField(true));
    emphasizeSmallFeaturesToggle.addListener(
        SWT.Selection, event -> requestOverlayRender(true));
    backgroundToggle.addListener(SWT.Selection, event -> onBackgroundToggleChanged());
    featureTable.addListener(SWT.SetData, this::populateFeatureTableItem);
    featureTable.addListener(
        SWT.Selection,
        event -> {
          if (updatingFeatureTableSelection || featureTable.isDisposed()) {
            return;
          }
          int selectionIndex = featureTable.getSelectionIndex();
          if (selectionIndex < 0 || featureTableModel == null || selectionIndex >= featureTableModel.size()) {
            return;
          }
          updateSelection(featureTableModel.entryAt(selectionIndex).feature(), true);
        });
    installTableCopySupport(
        featureTable, this::copyFeatureTableSelectionToClipboard, "Copy selected row(s)");
    installTableCopySupport(
        attributeTable, this::copyAttributeTableSelectionToClipboard, "Copy selected row(s)");
    installGeometryTextCopySupport();

    mapCanvas.addListener(SWT.Paint, this::paintMapCanvas);
    mapCanvas.addListener(
        SWT.Resize,
        event -> {
          if (syncCanvasMetrics(false)) {
            if (viewportModel.displayArea() == null && currentBuildResult != null) {
              setDisplayArea(defaultExtent(currentBuildResult));
            }
            refreshViewport(true, true, ViewportRefreshMode.DEBOUNCED);
          }
        });
    mapCanvas.addListener(
        SWT.MouseVerticalWheel,
        event -> {
          syncCanvasMetrics(false);
          GeometryInspectorViewTransform viewTransform = currentViewTransform();
          if (!viewportModel.isCanvasUsable()
              || viewportModel.displayArea() == null
              || viewTransform == null
              || !viewTransform.containsScreenPoint(event.x, event.y)) {
            return;
          }
          double steps = event.count == 0 ? 0.0d : Math.abs(event.count) / 3.0d;
          if (steps <= 0.0d) {
            steps = 1.0d;
          }
          double factor = Math.pow(1.2d, steps);
          if (event.count > 0) {
            factor = 1.0d / factor;
          }
          ReferencedEnvelope nextDisplayArea =
              GeometryInspectorViewportMath.zoomAt(
                  viewportModel.displayArea(),
                  viewportModel.canvasWidth(),
                  viewportModel.canvasHeight(),
                  event.x,
                  event.y,
                  factor);
          if (nextDisplayArea == null) {
            return;
          }
          setDisplayArea(nextDisplayArea);
          refreshViewport(true, true, ViewportRefreshMode.DEBOUNCED);
        });
    mapCanvas.addListener(
        SWT.MouseDown,
        event -> {
          if (event.button == 1) {
            syncCanvasMetrics(false);
            GeometryInspectorViewTransform viewTransform = currentViewTransform();
            if (viewTransform == null || !viewTransform.containsScreenPoint(event.x, event.y)) {
              return;
            }
            viewportModel.beginDrag(event.x, event.y);
          }
        });
    mapCanvas.addListener(
        SWT.MouseMove,
        event -> {
          if (!viewportModel.isDragging()) {
            return;
          }
          ReferencedEnvelope startDisplayArea = viewportModel.dragStartDisplayArea();
          if (startDisplayArea == null) {
            return;
          }
          int deltaX = event.x - viewportModel.dragStartX();
          int deltaY = event.y - viewportModel.dragStartY();
          if (deltaX == 0 && deltaY == 0) {
            return;
          }
          viewportModel.markDragMoved();
          setDisplayArea(
              GeometryInspectorViewportMath.pan(
                  startDisplayArea,
                  viewportModel.canvasWidth(),
                  viewportModel.canvasHeight(),
                  deltaX,
                  deltaY));
          refreshViewport(true, true, ViewportRefreshMode.PREVIEW_ONLY);
        });
    mapCanvas.addListener(
        SWT.MouseUp,
        event -> {
          if (!shouldProcessMapMouseUp(event.button, viewportModel.isDragging())) {
            return;
          }
          boolean dragged = viewportModel.dragMoved();
          viewportModel.endDrag();
          if (dragged) {
            refreshViewport(true, true, ViewportRefreshMode.COMMIT);
          } else {
            identifyFeature(event.x, event.y);
          }
        });
    shell.addListener(
        SWT.Move,
        event -> {
          if (syncCanvasMetrics(false)) {
            refreshViewport(true, true, ViewportRefreshMode.DEBOUNCED);
          }
        });
  }

  private GeometryInspectorToolbarButton createToolbarButton(
      Composite parent,
      String toolTip,
      GeometryInspectorToolbarIcons.Symbol symbol,
      boolean toggle) {
    GeometryInspectorToolbarButton button =
        new GeometryInspectorToolbarButton(parent, symbol, toggle);
    button.setToolTipText(toolTip);
    button.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    return button;
  }

  private int currentDeviceZoom() {
    return Math.max(100, shell.getMonitor() == null ? 100 : shell.getMonitor().getZoom());
  }

  private int currentOutputDpi() {
    Point dpi = shell.getDisplay().getDPI();
    double baseDpi = (Math.max(0, dpi.x) + Math.max(0, dpi.y)) / 2.0d;
    return Math.max(1, (int) Math.round(baseDpi * (currentDeviceZoom() / 100.0d)));
  }

  private GeometryInspectorViewTransform currentViewTransform() {
    return GeometryInspectorViewportMath.createViewTransform(
        viewportModel.displayArea(), viewportModel.canvasWidth(), viewportModel.canvasHeight());
  }

  private void setDisplayArea(ReferencedEnvelope displayArea) {
    viewportModel.setDisplayArea(GeometryInspectorViewportMath.normalizeExtent(displayArea));
  }

  private boolean syncCanvasMetrics(boolean force) {
    if (shell.isDisposed() || mapCanvas.isDisposed()) {
      return false;
    }
    Point size = mapCanvas.getSize();
    return viewportModel.updateCanvasMetrics(
        size.x, size.y, currentDeviceZoom(), currentOutputDpi());
  }

  private void refreshForSelectedField(boolean resetBackgroundInitialization) {
    String selectedField = fieldCombo.getText();
    if (selectedField == null || selectedField.isBlank()) {
      return;
    }

    try {
      GeometryBuildResult buildResult =
          GeometryInspectorClassLoaderSupport.withPluginContextClassLoader(
              () -> featureBuilder.build(samplingResult.rowMeta(), samplingResult.rows(), selectedField));
      if (resetBackgroundInitialization) {
        backgroundMapClient.resetInitialization();
      }
      applyBuildResult(buildResult, true);
      requestOverlayRender(true);
      requestBackgroundRender(false);
    } catch (Exception e) {
      showWarning(
          "Geometry inspector",
          "Unable to rebuild the selected geometry field.\n" + rootCauseMessage(e));
    }
  }

  private void applyBuildResult(GeometryBuildResult buildResult, boolean resetView) {
    currentBuildResult = buildResult;
    featureTableModel = new GeometryInspectorFeatureTableModel(samplingResult, buildResult, fieldCombo.getText());
    applyActiveFeatureTableSortToModel();
    selectedRowIndex = null;
    hoverPreviewRowIndex = null;
    requestCloseHitCandidateMenu();
    clearSelectionPanel();
    replaceOverlayFrame(null);
    setBackgroundFrame(null);
    backgroundFrameCache.clear();
    backgroundErrorMessage = "";

    if (resetView) {
      setDisplayArea(defaultExtent(buildResult));
    }

    overlayStatus = buildResult != null && buildResult.hasRenderableFeatures() ? "stale" : "idle";
    refreshFeatureTable();
    updateBackgroundAvailability();
    updateStatusLabel();
    redrawMapCanvas();
  }

  private ReferencedEnvelope defaultExtent(GeometryBuildResult buildResult) {
    if (buildResult == null || buildResult.extent() == null || buildResult.extent().isEmpty()) {
      return null;
    }
    return GeometryInspectorViewportMath.paddedInitialExtent(buildResult.extent());
  }

  private void zoomBy(double factor) {
    if (viewportModel.displayArea() == null) {
      return;
    }
    setDisplayArea(
        GeometryInspectorViewportMath.zoomByFactor(
            viewportModel.displayArea(),
            viewportModel.canvasWidth(),
            viewportModel.canvasHeight(),
            factor));
    refreshViewport(true, true, ViewportRefreshMode.DEBOUNCED);
  }

  private void zoomToExtent() {
    ReferencedEnvelope extent = defaultExtent(currentBuildResult);
    if (extent == null) {
      return;
    }
    setDisplayArea(extent);
    refreshViewport(true, true, ViewportRefreshMode.DEBOUNCED);
  }

  private void refreshViewport(
      boolean refreshOverlay, boolean refreshBackground, ViewportRefreshMode mode) {
    boolean renderOverlay =
        refreshOverlay && currentBuildResult != null && currentBuildResult.hasRenderableFeatures();
    boolean renderBackground =
        refreshBackground && backgroundToggle.isSelected() && backgroundToggle.isEnabled();

    if (renderOverlay) {
      overlayStatus = mode == ViewportRefreshMode.COMMIT ? "rendering" : "stale";
    } else if (refreshOverlay) {
      overlayStatus = "idle";
    }
    if (renderBackground) {
      backgroundStatus = mode == ViewportRefreshMode.COMMIT ? "loading" : "stale";
    }

    updateStatusLabel();
    redrawMapCanvas();

    if (mode == ViewportRefreshMode.PREVIEW_ONLY) {
      return;
    }
    if (renderOverlay) {
      requestOverlayRender(mode == ViewportRefreshMode.COMMIT);
    }
    if (renderBackground) {
      requestBackgroundRender(false);
    }
  }

  private void requestOverlayRender(boolean immediate) {
    if (closed || currentBuildResult == null || !currentBuildResult.hasRenderableFeatures()) {
      overlayStatus = "idle";
      updateStatusLabel();
      return;
    }

    syncCanvasMetrics(false);
    ReferencedEnvelope displayArea = GeometryInspectorViewportMath.normalizeExtent(viewportModel.displayArea());
    if (!viewportModel.isCanvasUsable() || displayArea == null) {
      overlayStatus = "idle";
      updateStatusLabel();
      return;
    }

    GeometryBuildResult buildSnapshot = currentBuildResult;
    Integer selectedRowSnapshot = selectedRowIndex;
    Integer hoverPreviewRowSnapshot = hoverPreviewRowIndex;
    boolean emphasizeSmallFeaturesSnapshot = emphasizeSmallFeaturesToggle.isSelected();
    int logicalWidth = viewportModel.canvasWidth();
    int logicalHeight = viewportModel.canvasHeight();
    int deviceZoom = viewportModel.deviceZoom();
    ReferencedEnvelope areaSnapshot = displayArea;

    overlayStatus = immediate ? "rendering" : "stale";
    updateStatusLabel();

    overlayCoordinator.schedule(
        immediate ? 0L : OVERLAY_DEBOUNCE_MILLIS,
        revision ->
            renderOverlay(
                buildSnapshot,
                selectedRowSnapshot,
                hoverPreviewRowSnapshot,
                areaSnapshot,
                logicalWidth,
                logicalHeight,
                deviceZoom,
                emphasizeSmallFeaturesSnapshot,
                revision),
        (revision, rasterData) -> {
          if (shell.isDisposed()) {
            return;
          }
          replaceOverlayFrame(createFrame(rasterData));
          overlayStatus = "idle";
          updateStatusLabel();
          redrawMapCanvas();
        },
        (revision, error) -> {
          if (shell.isDisposed()) {
            return;
          }
          overlayStatus = "error";
          updateStatusLabel();
        });
  }

  private void requestBackgroundRender(boolean immediate) {
    if (closed) {
      return;
    }

    if (!backgroundToggle.isSelected() || !backgroundToggle.isEnabled()) {
      backgroundStatus = backgroundToggle.isSelected() ? backgroundStatus : "off";
      setBackgroundFrame(null);
      redrawMapCanvas();
      updateStatusLabel();
      return;
    }

    syncCanvasMetrics(false);
    ReferencedEnvelope displayArea = GeometryInspectorViewportMath.normalizeExtent(viewportModel.displayArea());
    if (!viewportModel.isCanvasUsable() || displayArea == null) {
      backgroundStatus = "off";
      updateStatusLabel();
      return;
    }

    GeometryInspectorBackgroundMapClient.RequestParameters requestParameters =
        backgroundMapClient.buildRequestParameters(
            displayArea,
            viewportModel.canvasWidth(),
            viewportModel.canvasHeight(),
            viewportModel.deviceZoom(),
            viewportModel.outputDpi(),
            currentBuildResult == null ? null : currentBuildResult.detectedSrid());
    GeometryInspectorFrameKey cacheKey =
        GeometryInspectorFrameKey.forBackground(
            backgroundMapConfig,
            requestParameters.displayArea(),
            requestParameters.pixelWidth(),
            requestParameters.pixelHeight(),
            viewportModel.deviceZoom(),
            requestParameters.outputDpi(),
            currentBuildResult == null ? null : currentBuildResult.detectedSrid(),
            true);
    GeometryInspectorFrame cachedFrame = backgroundFrameCache.get(cacheKey);
    if (cachedFrame != null) {
      setBackgroundFrame(cachedFrame);
      backgroundStatus = "ready";
      backgroundErrorMessage = "";
      updateStatusLabel();
      redrawMapCanvas();
      return;
    }

    if (currentBuildResult == null || !currentBuildResult.hasUsableCrs()) {
      backgroundStatus = "unavailable";
      updateStatusLabel();
      return;
    }
    if (backgroundMapClient.hasInitializationFailure() && !immediate) {
      backgroundStatus = "error";
      backgroundErrorMessage = backgroundMapClient.initializationFailureMessage();
      updateStatusLabel();
      return;
    }

    ReferencedEnvelope areaSnapshot = displayArea;
    int logicalWidth = viewportModel.canvasWidth();
    int logicalHeight = viewportModel.canvasHeight();
    int deviceZoom = viewportModel.deviceZoom();
    int outputDpi = viewportModel.outputDpi();
    Integer srid = currentBuildResult.detectedSrid();

    backgroundStatus = immediate ? "loading" : "stale";
    backgroundErrorMessage = "";
    updateStatusLabel();

      backgroundCoordinator.schedule(
          immediate ? 0L : BACKGROUND_DEBOUNCE_MILLIS,
          revision ->
              backgroundMapClient.render(
                  areaSnapshot, logicalWidth, logicalHeight, deviceZoom, outputDpi, srid, revision),
        (revision, rasterData) -> {
          if (shell.isDisposed()) {
            return;
          }
          GeometryInspectorFrame frame = createFrame(rasterData);
          backgroundFrameCache.put(cacheKey, frame);
          setBackgroundFrame(frame);
          backgroundStatus = "ready";
          backgroundErrorMessage = "";
          updateStatusLabel();
          redrawMapCanvas();
        },
        (revision, error) -> {
          if (shell.isDisposed()) {
            return;
          }
          backgroundStatus = "error";
          backgroundErrorMessage =
              backgroundMapClient.hasInitializationFailure()
                  ? backgroundMapClient.initializationFailureMessage()
                  : rootCauseMessage(error);
          updateStatusLabel();
          redrawMapCanvas();
        });
  }

  private GeometryInspectorFrame createFrame(GeometryInspectorRasterData rasterData) {
    Image image = SwtBufferedImageConverter.toImage(shell.getDisplay(), rasterData);
    return new GeometryInspectorFrame(
        rasterData.displayArea(),
        rasterData.logicalWidth(),
        rasterData.logicalHeight(),
        rasterData.deviceZoom(),
        rasterData.revision(),
        image);
  }

  private GeometryInspectorRasterData renderOverlay(
      GeometryBuildResult buildResult,
      Integer selectedRow,
      Integer hoverPreviewRow,
      ReferencedEnvelope displayArea,
      int logicalWidth,
      int logicalHeight,
      int deviceZoom,
      boolean emphasizeSmallFeatures,
      long revision) {
    ReferencedEnvelope renderArea =
        GeometryInspectorViewportMath.fitToCanvasAspect(displayArea, logicalWidth, logicalHeight);
    if (renderArea == null) {
      throw new IllegalStateException("Unable to create render area for overlay rendering");
    }
    int pixelWidth = GeometryInspectorViewportMath.toPixelSize(logicalWidth, deviceZoom);
    int pixelHeight = GeometryInspectorViewportMath.toPixelSize(logicalHeight, deviceZoom);
    BufferedImage image =
        new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB_PRE);
    Graphics2D graphics = image.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

    MapContent mapContent = new MapContent();
    try {
      mapContent.setTitle("Geometry sample overlay");
      addFeatureLayers(
          buildResult.features(), buildResult.featureType(), mapContent, emphasizeSmallFeatures);
      Integer highlightRow = hoverPreviewRow == null ? selectedRow : hoverPreviewRow;
      SimpleFeature selectedFeature = featureForRow(buildResult, highlightRow);
      if (selectedFeature != null && selectedFeature.getDefaultGeometry() instanceof Geometry geometry) {
        Layer highlightLayer =
            new FeatureLayer(
                new ListFeatureCollection(buildResult.featureType(), List.of(selectedFeature)),
                createHighlightStyle(geometry),
                "Selection");
        mapContent.addLayer(highlightLayer);
      }

      StreamingRenderer renderer = new StreamingRenderer();
      renderer.setMapContent(mapContent);
      renderer.paint(
          graphics,
          new java.awt.Rectangle(0, 0, pixelWidth, pixelHeight),
          renderArea);
    } finally {
      mapContent.dispose();
      graphics.dispose();
    }

    return GeometryInspectorRasterData.fromBufferedImage(
        renderArea,
        logicalWidth,
        logicalHeight,
        deviceZoom,
        revision,
        image);
  }

  private void addFeatureLayers(
      List<SimpleFeature> features,
      SimpleFeatureType featureType,
      MapContent targetMapContent,
      boolean emphasizeSmallFeatures) {
    List<SimpleFeature> pointFeatures = new ArrayList<>();
    List<SimpleFeature> lineFeatures = new ArrayList<>();
    List<SimpleFeature> polygonFeatures = new ArrayList<>();

    for (SimpleFeature feature : features) {
      Object defaultGeometry = feature.getDefaultGeometry();
      if (!(defaultGeometry instanceof Geometry geometry)) {
        continue;
      }

      if (geometry instanceof Puntal) {
        pointFeatures.add(feature);
      } else if (geometry instanceof Lineal) {
        lineFeatures.add(feature);
      } else if (geometry instanceof Polygonal) {
        polygonFeatures.add(feature);
      } else {
        polygonFeatures.add(feature);
      }
    }

    addLayerIfNotEmpty(
        targetMapContent,
        polygonFeatures,
        featureType,
        createPolygonStyle(emphasizeSmallFeatures),
        "Polygons");
    addLayerIfNotEmpty(
        targetMapContent,
        lineFeatures,
        featureType,
        createLineStyle(emphasizeSmallFeatures),
        "Lines");
    addLayerIfNotEmpty(
        targetMapContent,
        pointFeatures,
        featureType,
        createPointStyle(emphasizeSmallFeatures),
        "Points");
  }

  private void addLayerIfNotEmpty(
      MapContent targetMapContent,
      List<SimpleFeature> features,
      SimpleFeatureType featureType,
      Style style,
      String title) {
    if (features.isEmpty() || featureType == null) {
      return;
    }
    Layer layer = new FeatureLayer(new ListFeatureCollection(featureType, features), style, title);
    layer.setTitle(title);
    targetMapContent.addLayer(layer);
  }

  static Style createPointStyle(boolean emphasizeSmallFeatures) {
    return SLD.createPointStyle(
        "circle",
        new Color(215, 48, 39),
        Color.BLACK,
        POINT_FILL_OPACITY,
        emphasizeSmallFeatures ? EMPHASIZED_POINT_SIZE : DEFAULT_POINT_SIZE);
  }

  static Style createLineStyle(boolean emphasizeSmallFeatures) {
    return SLD.createLineStyle(
        new Color(69, 117, 180),
        emphasizeSmallFeatures ? EMPHASIZED_LINE_WIDTH : DEFAULT_LINE_WIDTH);
  }

  static Style createPolygonStyle(boolean emphasizeSmallFeatures) {
    StyleBuilder styleBuilder = new StyleBuilder();
    return styleBuilder.createStyle(
        styleBuilder.createPolygonSymbolizer(
            styleBuilder.createStroke(
                new Color(49, 54, 149),
                emphasizeSmallFeatures
                    ? EMPHASIZED_POLYGON_STROKE_WIDTH
                    : DEFAULT_POLYGON_STROKE_WIDTH),
            styleBuilder.createFill(new Color(253, 174, 97), 0.48d)));
  }

  static Style createHighlightStyle(Geometry geometry) {
    if (geometry instanceof Puntal) {
      return SLD.createPointStyle(
          "circle",
          new Color(35, 35, 35),
          new Color(255, 230, 65),
          POINT_FILL_OPACITY,
          HIGHLIGHT_POINT_SIZE);
    }
    if (geometry instanceof Lineal) {
      return SLD.createLineStyle(new Color(255, 230, 65), HIGHLIGHT_LINE_WIDTH);
    }
    return SLD.createPolygonStyle(new Color(255, 230, 65), new Color(35, 35, 35), 0.18f);
  }

  private void identifyFeature(int x, int y) {
    if (currentBuildResult == null || currentBuildResult.features().isEmpty()) {
      requestCloseHitCandidateMenu();
      updateSelection(null, false);
      return;
    }

    GeometryInspectorViewTransform viewTransform = currentViewTransform();
    if (viewTransform == null || !viewportModel.isCanvasUsable()) {
      requestCloseHitCandidateMenu();
      updateSelection(null, false);
      return;
    }
    if (!viewTransform.containsScreenPoint(x, y)) {
      requestCloseHitCandidateMenu();
      updateSelection(null, false);
      return;
    }

    double tolerance = PICK_TOLERANCE_PIXELS * (viewportModel.deviceZoom() / 100.0d);
    Coordinate coordinate =
        GeometryInspectorViewportMath.screenToWorld(
            viewportModel.displayArea(), viewportModel.canvasWidth(), viewportModel.canvasHeight(), x, y);
    Envelope pickEnvelope =
        GeometryInspectorViewportMath.pickEnvelope(
            viewportModel.displayArea(),
            viewportModel.canvasWidth(),
            viewportModel.canvasHeight(),
            x,
            y,
            tolerance);

    List<GeometrySelectionService.SelectionCandidate> candidates =
        selectionService.rankHits(currentBuildResult.features(), coordinate, pickEnvelope);
    if (candidates.isEmpty()) {
      requestCloseHitCandidateMenu();
      updateSelection(null, false);
      return;
    }
    if (selectionService.hasAmbiguousTopHit(candidates)) {
      showHitCandidateMenu(x, y, ambiguousTopCandidates(candidates));
      return;
    }
    requestCloseHitCandidateMenu();
    updateSelection(candidates.get(0).feature(), false);
  }

  private void updateSelection(SimpleFeature feature, boolean focusSelection) {
    hoverPreviewRowIndex = null;
    if (feature == null) {
      selectedRowIndex = null;
      clearSelectionPanel();
      syncFeatureTableSelection(-1, null);
      requestOverlayRender(true);
      updateStatusLabel();
      return;
    }

    selectedRowIndex = rowIndexOf(feature);
    populateSelectionPanel(feature);
    syncFeatureTableSelection(selectedRowIndex, feature);
    if (focusSelection && focusSelectionOnMap(feature)) {
      refreshViewport(true, true, ViewportRefreshMode.COMMIT);
    } else {
      requestOverlayRender(true);
    }
    updateStatusLabel();
  }

  private void clearSelectionPanel() {
    selectionSummaryLabel.setText("No feature selected");
    attributeTable.removeAll();
    attributeClipboardRows = List.of();
    geometryDetailText.setText("");
  }

  private void refreshFeatureTable() {
    if (featureTable.isDisposed()) {
      return;
    }
    updatingFeatureTableSelection = true;
    try {
      rebuildFeatureTableColumns();
      updateFeatureTableSortIndicator();
      featureTable.deselectAll();
      featureTable.clearAll();
      featureTable.setItemCount(featureTableModel == null ? 0 : featureTableModel.size());
    } finally {
      updatingFeatureTableSelection = false;
    }
  }

  private void rebuildFeatureTableColumns() {
    for (TableColumn column : featureTable.getColumns()) {
      column.dispose();
    }
    if (featureTableModel == null) {
      return;
    }
    List<GeometryInspectorFeatureTableModel.Column> columns = featureTableModel.columns();
    for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
      GeometryInspectorFeatureTableModel.Column column = columns.get(columnIndex);
      TableColumn tableColumn = new TableColumn(featureTable, SWT.LEFT);
      tableColumn.setText(column.label());
      tableColumn.setWidth(preferredFeatureTableColumnWidth(column));
      int selectedColumnIndex = columnIndex;
      tableColumn.addListener(SWT.Selection, event -> onFeatureTableColumnSelected(selectedColumnIndex));
    }
  }

  private void onFeatureTableColumnSelected(int columnIndex) {
    if (featureTableModel == null || featureTableModel.columnCount() <= 0) {
      return;
    }
    int normalizedColumnIndex =
        normalizeFeatureTableSortColumnIndex(columnIndex, featureTableModel.columnCount());
    if (normalizedColumnIndex < 0) {
      return;
    }
    int nextSortDirection =
        resolveNextFeatureTableSortDirection(
            activeSortColumnIndex, normalizedColumnIndex, activeSortDirection);
    activeSortColumnIndex = normalizedColumnIndex;
    activeSortDirection = nextSortDirection;
    featureTableModel = featureTableModel.sortedByColumn(normalizedColumnIndex, nextSortDirection != SWT.DOWN);

    Integer currentSelectedRowIndex = selectedRowIndex;
    SimpleFeature selectedFeature =
        currentSelectedRowIndex == null
            ? null
            : featureForRow(currentBuildResult, currentSelectedRowIndex);
    refreshFeatureTable();
    syncFeatureTableSelection(
        currentSelectedRowIndex == null ? -1 : currentSelectedRowIndex, selectedFeature);
  }

  private void updateFeatureTableSortIndicator() {
    if (featureTable == null || featureTable.isDisposed()) {
      return;
    }
    if (featureTableModel == null || featureTableModel.columnCount() <= 0) {
      featureTable.setSortColumn(null);
      featureTable.setSortDirection(SWT.NONE);
      return;
    }
    int normalizedColumnIndex =
        normalizeFeatureTableSortColumnIndex(
            activeSortColumnIndex == null ? 0 : activeSortColumnIndex, featureTableModel.columnCount());
    int normalizedSortDirection = normalizeFeatureTableSortDirection(activeSortDirection);
    TableColumn[] columns = featureTable.getColumns();
    if (normalizedColumnIndex < 0 || normalizedColumnIndex >= columns.length) {
      featureTable.setSortColumn(null);
      featureTable.setSortDirection(SWT.NONE);
      return;
    }
    featureTable.setSortColumn(columns[normalizedColumnIndex]);
    featureTable.setSortDirection(normalizedSortDirection);
  }

  private void applyActiveFeatureTableSortToModel() {
    if (featureTableModel == null || featureTableModel.columnCount() <= 0) {
      activeSortColumnIndex = null;
      activeSortDirection = SWT.UP;
      return;
    }
    int normalizedColumnIndex =
        normalizeFeatureTableSortColumnIndex(
            activeSortColumnIndex == null ? 0 : activeSortColumnIndex, featureTableModel.columnCount());
    int normalizedSortDirection = normalizeFeatureTableSortDirection(activeSortDirection);
    featureTableModel =
        featureTableModel.sortedByColumn(normalizedColumnIndex, normalizedSortDirection != SWT.DOWN);
    activeSortColumnIndex = normalizedColumnIndex;
    activeSortDirection = normalizedSortDirection;
  }

  static int normalizeFeatureTableSortColumnIndex(int columnIndex, int columnCount) {
    if (columnCount <= 0) {
      return -1;
    }
    if (columnIndex < 0 || columnIndex >= columnCount) {
      return 0;
    }
    return columnIndex;
  }

  static int normalizeFeatureTableSortDirection(int sortDirection) {
    return sortDirection == SWT.DOWN ? SWT.DOWN : SWT.UP;
  }

  static int resolveNextFeatureTableSortDirection(
      Integer activeColumnIndex, int clickedColumnIndex, int currentSortDirection) {
    int normalizedCurrentSortDirection = normalizeFeatureTableSortDirection(currentSortDirection);
    if (activeColumnIndex != null && activeColumnIndex == clickedColumnIndex) {
      return normalizedCurrentSortDirection == SWT.UP ? SWT.DOWN : SWT.UP;
    }
    return SWT.UP;
  }

  private int preferredFeatureTableColumnWidth(GeometryInspectorFeatureTableModel.Column column) {
    if (column == null) {
      return 160;
    }
    if (column.isRowIndex()) {
      return 72;
    }
    String geometryField = fieldCombo.getText();
    if (geometryField != null && geometryField.equals(column.label())) {
      return 320;
    }
    return 180;
  }

  private void populateFeatureTableItem(Event event) {
    if (!(event.item instanceof TableItem item) || featureTableModel == null) {
      return;
    }
    int tableIndex = featureTable.indexOf(item);
    if (tableIndex < 0 || tableIndex >= featureTableModel.size()) {
      item.setText(new String[featureTableModel == null ? 0 : featureTableModel.columnCount()]);
      return;
    }
    GeometryInspectorFeatureTableModel.Entry entry = featureTableModel.entryAt(tableIndex);
    String[] values = new String[featureTableModel.columnCount()];
    for (int index = 0; index < values.length; index++) {
      values[index] = entry.cellValueAt(index);
    }
    item.setText(values);
  }

  private void syncFeatureTableSelection(int rowIndex, SimpleFeature selectedFeature) {
    if (featureTable.isDisposed()) {
      return;
    }
    updatingFeatureTableSelection = true;
    try {
      int tableIndex = resolveFeatureTableSelectionIndex(featureTableModel, rowIndex, selectedFeature);
      if (tableIndex < 0) {
        featureTable.deselectAll();
        return;
      }
      featureTable.setSelection(tableIndex);
      featureTable.showSelection();
    } finally {
      updatingFeatureTableSelection = false;
    }
  }

  static int resolveFeatureTableSelectionIndex(
      GeometryInspectorFeatureTableModel tableModel, int rowIndex, SimpleFeature selectedFeature) {
    if (tableModel == null) {
      return -1;
    }
    int tableIndex = tableModel.indexOfRow(rowIndex);
    if (tableIndex >= 0) {
      return tableIndex;
    }
    return tableModel.indexOfFeature(selectedFeature);
  }

  private boolean focusSelectionOnMap(SimpleFeature feature) {
    if (!(feature.getDefaultGeometry() instanceof Geometry geometry)) {
      return false;
    }
    ReferencedEnvelope selectionExtent =
        GeometryInspectorViewportMath.paddedFeatureExtent(
            geometry, currentBuildResult == null ? null : currentBuildResult.detectedCrs());
    if (selectionExtent == null) {
      return false;
    }
    setDisplayArea(selectionExtent);
    return true;
  }

  private List<GeometrySelectionService.SelectionCandidate> ambiguousTopCandidates(
      List<GeometrySelectionService.SelectionCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    GeometrySelectionService.SelectionCandidate first = candidates.get(0);
    List<GeometrySelectionService.SelectionCandidate> ambiguous = new ArrayList<>();
    for (GeometrySelectionService.SelectionCandidate candidate : candidates) {
      if (candidate.renderPriority() != first.renderPriority()
          || Double.compare(candidate.distance(), first.distance()) != 0) {
        break;
      }
      ambiguous.add(candidate);
    }
    return ambiguous;
  }

  private void previewHitCandidate(SimpleFeature feature) {
    int rowIndex = rowIndexOf(feature);
    if (shouldIgnorePreviewRow(rowIndex, hoverPreviewRowIndex)) {
      return;
    }
    hoverPreviewRowIndex = rowIndex;
    requestOverlayRender(true);
  }

  static boolean shouldIgnorePreviewRow(int rowIndex, Integer hoverPreviewRow) {
    return rowIndex < 0 || Objects.equals(hoverPreviewRow, rowIndex);
  }

  static boolean shouldProcessMapMouseUp(int button, boolean dragging) {
    return button == 1 && dragging;
  }

  static boolean shouldRequestPopupMenuVisibilityClose(boolean menuVisible) {
    return menuVisible;
  }

  static String formatFeatureTableSelectionForClipboard(
      GeometryInspectorFeatureTableModel tableModel, int[] selectionIndices) {
    if (tableModel == null || selectionIndices == null || selectionIndices.length == 0) {
      return "";
    }
    int[] sortedDistinctSelection = Arrays.stream(selectionIndices).sorted().distinct().toArray();
    StringBuilder builder = new StringBuilder();
    for (int tableIndex : sortedDistinctSelection) {
      if (tableIndex < 0 || tableIndex >= tableModel.size()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(System.lineSeparator());
      }
      GeometryInspectorFeatureTableModel.Entry entry = tableModel.entryAt(tableIndex);
      builder.append(formatFeatureEntryForClipboard(entry, tableModel.columnCount()));
    }
    return builder.toString();
  }

  private static String formatFeatureEntryForClipboard(
      GeometryInspectorFeatureTableModel.Entry entry, int columnCount) {
    String[] values = new String[Math.max(0, columnCount)];
    for (int columnIndex = 0; columnIndex < values.length; columnIndex++) {
      values[columnIndex] = entry.clipboardCellValueAt(columnIndex);
    }
    return joinCellsWithTabs(values);
  }

  static String formatAttributeSelectionForClipboard(
      List<String[]> clipboardRows, int[] selectionIndices) {
    if (clipboardRows == null
        || clipboardRows.isEmpty()
        || selectionIndices == null
        || selectionIndices.length == 0) {
      return "";
    }
    int[] sortedDistinctSelection = Arrays.stream(selectionIndices).sorted().distinct().toArray();
    StringBuilder builder = new StringBuilder();
    for (int rowIndex : sortedDistinctSelection) {
      if (rowIndex < 0 || rowIndex >= clipboardRows.size()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(System.lineSeparator());
      }
      String[] rowValues = clipboardRows.get(rowIndex);
      builder.append(joinCellsWithTabs(rowValues == null ? new String[0] : rowValues));
    }
    return builder.toString();
  }

  private static String joinCellsWithTabs(String[] values) {
    StringBuilder builder = new StringBuilder();
    for (int columnIndex = 0; columnIndex < values.length; columnIndex++) {
      if (columnIndex > 0) {
        builder.append('\t');
      }
      String value = values[columnIndex];
      builder.append(value == null ? "" : value);
    }
    return builder.toString();
  }

  private static boolean isCopyShortcut(Event event) {
    if (event == null) {
      return false;
    }
    boolean commandOrControl = (event.stateMask & SWT.MOD1) != 0;
    if (!commandOrControl) {
      return false;
    }
    return event.keyCode == 'c'
        || event.keyCode == 'C'
        || event.character == 'c'
        || event.character == 'C';
  }

  private void installTableCopySupport(Table table, Runnable copyAction, String menuLabel) {
    table.addListener(
        SWT.KeyDown,
        event -> {
          if (!isCopyShortcut(event)) {
            return;
          }
          copyAction.run();
          event.doit = false;
        });

    Menu copyMenu = new Menu(table);
    MenuItem copyItem = new MenuItem(copyMenu, SWT.PUSH);
    copyItem.setText(menuLabel);
    copyItem.addListener(SWT.Selection, event -> copyAction.run());
    copyMenu.addListener(SWT.Show, event -> copyItem.setEnabled(table.getSelectionCount() > 0));
    table.setMenu(copyMenu);
  }

  private void installGeometryTextCopySupport() {
    geometryDetailText.addListener(
        SWT.KeyDown,
        event -> {
          if (!isCopyShortcut(event)) {
            return;
          }
          copyGeometryDetailToClipboard();
          event.doit = false;
        });

    Menu textMenu = new Menu(geometryDetailText);
    MenuItem copyItem = new MenuItem(textMenu, SWT.PUSH);
    copyItem.setText("Copy");
    copyItem.addListener(SWT.Selection, event -> copyGeometryDetailToClipboard());
    MenuItem selectAllItem = new MenuItem(textMenu, SWT.PUSH);
    selectAllItem.setText("Select all");
    selectAllItem.addListener(SWT.Selection, event -> geometryDetailText.selectAll());
    textMenu.addListener(
        SWT.Show,
        event -> {
          boolean hasText = !geometryDetailText.getText().isBlank();
          copyItem.setEnabled(hasText);
          selectAllItem.setEnabled(hasText);
        });
    geometryDetailText.setMenu(textMenu);
  }

  private void copyFeatureTableSelectionToClipboard() {
    String content =
        formatFeatureTableSelectionForClipboard(
            featureTableModel, featureTable == null ? new int[0] : featureTable.getSelectionIndices());
    copyTextToClipboard(content);
  }

  private void copyAttributeTableSelectionToClipboard() {
    int[] selectionIndices = attributeTable == null ? new int[0] : attributeTable.getSelectionIndices();
    copyTextToClipboard(formatAttributeSelectionForClipboard(attributeClipboardRows, selectionIndices));
  }

  private void copyGeometryDetailToClipboard() {
    if (geometryDetailText == null || geometryDetailText.isDisposed()) {
      return;
    }
    String selectedText = geometryDetailText.getSelectionText();
    if (selectedText == null || selectedText.isBlank()) {
      selectedText = geometryDetailText.getText();
    }
    copyTextToClipboard(selectedText);
  }

  private void copyTextToClipboard(String value) {
    if (value == null || value.isBlank() || shell.isDisposed()) {
      return;
    }
    Clipboard clipboard = new Clipboard(shell.getDisplay());
    try {
      clipboard.setContents(new Object[] {value}, new Transfer[] {TextTransfer.getInstance()});
    } finally {
      clipboard.dispose();
    }
  }

  private void commitHitCandidate(SimpleFeature feature) {
    hoverPreviewRowIndex = null;
    updateSelection(feature, false);
    syncFeatureTableSelection(selectedRowIndex == null ? -1 : selectedRowIndex, feature);
  }

  private void showHitCandidateMenu(
      int canvasX, int canvasY, List<GeometrySelectionService.SelectionCandidate> candidates) {
    requestCloseHitCandidateMenu();
    if (candidates == null || candidates.isEmpty()) {
      return;
    }

    hoverPreviewRowIndex = null;
    Menu menu = new Menu(mapCanvas);
    for (GeometrySelectionService.SelectionCandidate candidate : candidates) {
      MenuItem item = new MenuItem(menu, SWT.PUSH);
      item.setText(hitCandidateLabel(candidate.feature()));
      item.addListener(SWT.Arm, event -> previewHitCandidate(candidate.feature()));
      item.addListener(
          SWT.Selection,
          event -> commitHitCandidate(candidate.feature()));
    }
    menu.addListener(SWT.Hide, event -> closeHitCandidateMenu(menu));
    hitCandidateMenu = menu;
    Point location = mapCanvas.toDisplay(canvasX, canvasY);
    menu.setLocation(location);
    menu.setVisible(true);
  }

  private String hitCandidateLabel(SimpleFeature feature) {
    GeometryInspectorFeatureTableModel.Entry entry =
        featureTableModel == null ? null : featureTableModel.entryForFeature(feature);
    if (entry == null) {
      return "Row " + rowIndexOf(feature);
    }
    return entry.hitLabel();
  }

  private void requestCloseHitCandidateMenu() {
    Menu menu = hitCandidateMenu;
    boolean hadHoverPreview = hoverPreviewRowIndex != null;
    clearHitCandidateMenuState();
    if (hadHoverPreview) {
      requestOverlayRender(true);
    }
    if (menu == null || menu.isDisposed()) {
      return;
    }
    if (shouldRequestPopupMenuVisibilityClose(menu.getVisible())) {
      menu.setVisible(false);
    }
    scheduleHitCandidateMenuDispose(menu);
  }

  private void closeHitCandidateMenu(Menu menu) {
    if (menu == null || menu != hitCandidateMenu) {
      return;
    }

    boolean hadHoverPreview = hoverPreviewRowIndex != null;
    clearHitCandidateMenuState();

    if (hadHoverPreview) {
      requestOverlayRender(true);
    }
    scheduleHitCandidateMenuDispose(menu);
  }

  private void clearHitCandidateMenuState() {
    hitCandidateMenu = null;
    hoverPreviewRowIndex = null;
  }

  private void scheduleHitCandidateMenuDispose(Menu menu) {
    if (menu == null || menu.isDisposed()) {
      return;
    }
    Display display = menu.getDisplay();
    if (display == null || display.isDisposed()) {
      menu.dispose();
      return;
    }
    display.asyncExec(
        () -> {
          if (!menu.isDisposed()) {
            menu.dispose();
          }
        });
  }

  private void populateSelectionPanel(SimpleFeature feature) {
    int rowIndex = rowIndexOf(feature);
    Geometry geometry = feature.getDefaultGeometry() instanceof Geometry current ? current : null;
    attributeTable.removeAll();

    Object[] row =
        rowIndex >= 0 && rowIndex < samplingResult.rows().size()
            ? samplingResult.rows().get(rowIndex)
            : new Object[0];
    IRowMeta rowMeta = samplingResult.rowMeta();
    List<String[]> nextAttributeClipboardRows = new ArrayList<>();

    if (rowMeta != null) {
      for (int index = 0; index < rowMeta.size(); index++) {
        IValueMeta valueMeta = rowMeta.getValueMeta(index);
        Object value = row.length > index ? row[index] : null;
        String formatted = formatValue(valueMeta, value);
        nextAttributeClipboardRows.add(new String[] {valueMeta.getName(), formatted});
        TableItem item = new TableItem(attributeTable, SWT.NONE);
        item.setText(
            new String[] {valueMeta.getName(), abbreviate(formatted, MAX_TABLE_VALUE_LENGTH)});
      }
    }
    attributeClipboardRows = List.copyOf(nextAttributeClipboardRows);

    String geometryType = geometry == null ? "n/a" : geometry.getGeometryType();
    int srid = geometry == null ? 0 : geometry.getSRID();
    String crsLabel =
        srid > 0
            ? "EPSG:" + srid
            : (currentBuildResult.hasUsableCrs() ? currentBuildResult.crsStatusMessage() : "No SRID");
    selectionSummaryLabel.setText(
        "Row " + rowIndex + " | " + geometryType + " | CRS: " + crsLabel);
    geometryDetailText.setText(extractGeometryDetail(rowIndex, geometry));
    geometryDetailText.setSelection(0);
  }

  private String extractGeometryDetail(int rowIndex, Geometry geometry) {
    IRowMeta rowMeta = samplingResult.rowMeta();
    if (rowMeta != null) {
      int geometryIndex = rowMeta.indexOfValue(fieldCombo.getText());
      if (geometryIndex >= 0 && rowIndex >= 0 && rowIndex < samplingResult.rows().size()) {
        Object[] row = samplingResult.rows().get(rowIndex);
        Object value = row.length > geometryIndex ? row[geometryIndex] : null;
        String formatted = formatValue(rowMeta.getValueMeta(geometryIndex), value);
        if (!formatted.isBlank() && !"null".equals(formatted)) {
          return formatted;
        }
      }
    }

    if (geometry == null) {
      return "";
    }
    return geometry.getSRID() > 0
        ? "SRID=" + geometry.getSRID() + ";" + geometry.toText()
        : geometry.toText();
  }

  private String formatValue(IValueMeta valueMeta, Object value) {
    if (value == null) {
      return "null";
    }

    try {
      String formatted = valueMeta == null ? null : valueMeta.getString(value);
      if (formatted != null) {
        return formatted;
      }
    } catch (Exception e) {
      // Fall through to generic formatting.
    }

    if (value instanceof byte[] bytes) {
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format(Locale.ROOT, "%02x", current));
      }
      return builder.toString();
    }
    return String.valueOf(value);
  }

  private void paintMapCanvas(Event event) {
    if (closed || mapCanvas.isDisposed()) {
      return;
    }

    GC gc = event.gc;
    gc.setAntialias(SWT.ON);
    gc.setInterpolation(SWT.HIGH);
    org.eclipse.swt.graphics.Rectangle clientArea = mapCanvas.getClientArea();
    gc.setBackground(shell.getDisplay().getSystemColor(SWT.COLOR_WHITE));
    gc.fillRectangle(clientArea);

    drawFrame(gc, backgroundToggle.isSelected() ? backgroundFrame : null, clientArea);
    drawFrame(gc, overlayFrame, clientArea);
  }

  private void drawFrame(
      GC gc, GeometryInspectorFrame frame, org.eclipse.swt.graphics.Rectangle clientArea) {
    if (frame == null || frame.image() == null || frame.image().isDisposed()) {
      return;
    }
    ReferencedEnvelope currentRenderArea =
        GeometryInspectorViewportMath.fitToCanvasAspect(
            viewportModel.displayArea(), clientArea.width, clientArea.height);
    ReferencedEnvelope frameRenderArea = frame.displayArea();
    ReferencedEnvelope visibleArea =
        GeometryInspectorViewportMath.intersectAreas(currentRenderArea, frameRenderArea);
    if (currentRenderArea == null || frameRenderArea == null || visibleArea == null) {
      return;
    }

    org.eclipse.swt.graphics.Rectangle imageBounds = frame.image().getBounds();
    Rectangle sourceRect =
        GeometryInspectorViewportMath.worldToPixelRect(
            frameRenderArea, imageBounds.width, imageBounds.height, visibleArea);
    Rectangle destinationRect =
        GeometryInspectorViewportMath.worldToPixelRect(
            currentRenderArea, clientArea.width, clientArea.height, visibleArea);
    if (sourceRect.width <= 0
        || sourceRect.height <= 0
        || destinationRect.width <= 0
        || destinationRect.height <= 0) {
      return;
    }

    gc.drawImage(
        frame.image(),
        sourceRect.x,
        sourceRect.y,
        sourceRect.width,
        sourceRect.height,
        destinationRect.x,
        destinationRect.y,
        destinationRect.width,
        destinationRect.height);
  }

  private SimpleFeature featureForRow(GeometryBuildResult buildResult, Integer rowIndex) {
    if (buildResult == null || rowIndex == null) {
      return null;
    }
    return buildResult.features().stream()
        .filter(feature -> rowIndex.equals(rowIndexOf(feature)))
        .findFirst()
        .orElse(null);
  }

  private int rowIndexOf(SimpleFeature feature) {
    Object value = feature.getAttribute("row_index");
    if (value instanceof Number number) {
      return number.intValue();
    }
    return -1;
  }

  private void onBackgroundToggleChanged() {
    if (!backgroundToggle.isSelected()) {
      backgroundCoordinator.cancelPending();
      backgroundStatus = "off";
      backgroundErrorMessage = "";
      setBackgroundFrame(null);
      updateStatusLabel();
      redrawMapCanvas();
      return;
    }

    if (!backgroundToggle.isEnabled()) {
      backgroundToggle.setSelected(false);
      return;
    }

    backgroundMapClient.resetInitialization();
    requestBackgroundRender(true);
  }

  private void updateBackgroundAvailability() {
    if (currentBuildResult == null) {
      backgroundToggle.setEnabled(false);
      backgroundToggle.setToolTipText("No geometry sample loaded");
      backgroundStatus = "off";
      return;
    }

    if (!backgroundMapConfig.isValid()) {
      backgroundToggle.setEnabled(false);
      backgroundToggle.setSelected(false);
      backgroundStatus = "not configured";
      backgroundToggle.setToolTipText("Configure a global WMS background in the options dialog");
      return;
    }

    if (!currentBuildResult.hasUsableCrs()) {
      backgroundToggle.setEnabled(false);
      backgroundToggle.setSelected(false);
      backgroundStatus = "unavailable";
      backgroundToggle.setToolTipText(
          currentBuildResult.crsStatusMessage().isBlank()
              ? "Background map requires a consistent SRID/CRS"
              : currentBuildResult.crsStatusMessage());
      return;
    }

    backgroundToggle.setEnabled(true);
    backgroundToggle.setToolTipText("Toggle background map");
    if (!backgroundToggle.isSelected()) {
      backgroundStatus = "off";
    }
  }

  private void replaceOverlayFrame(GeometryInspectorFrame nextFrame) {
    if (overlayFrame != null && overlayFrame != nextFrame) {
      overlayFrame.dispose();
    }
    overlayFrame = nextFrame;
  }

  private void setBackgroundFrame(GeometryInspectorFrame nextFrame) {
    backgroundFrame = nextFrame;
  }

  private void redrawMapCanvas() {
    if (!mapCanvas.isDisposed()) {
      mapCanvas.redraw();
    }
  }

  private void updateStatusLabel() {
    if (statusLabel.isDisposed()) {
      return;
    }

    StringBuilder status = new StringBuilder();
    status.append("source=").append(describeEffectiveSide());
    if (samplingResult.autoSwitched()) {
      status.append(" (auto-switched)");
    }
    status.append(" | sampled rows=").append(samplingResult.rows().size());
    status.append(" | parsed features=")
        .append(currentBuildResult == null ? 0 : currentBuildResult.features().size());
    status.append(" | parse errors=")
        .append(currentBuildResult == null ? 0 : currentBuildResult.parseErrors());
    status.append(" | null/empty=")
        .append(currentBuildResult == null ? 0 : currentBuildResult.nullGeometries());
    status.append(" | sample=").append(samplingResult.partial() ? "partial" : "full");
    if (samplingResult.reason() != null && !samplingResult.reason().isBlank()) {
      status.append(" (").append(samplingResult.reason()).append(')');
    }
    if (!samplingResult.sideResolutionMessage().isBlank()) {
      status.append(" | ").append(samplingResult.sideResolutionMessage());
    }
    if (currentBuildResult != null && !currentBuildResult.crsStatusMessage().isBlank()) {
      status.append(" | crs=").append(currentBuildResult.crsStatusMessage());
    }
    status.append(" | overlay=").append(overlayStatus);
    status.append(" | emphasize=").append(emphasizeSmallFeaturesToggle.isSelected() ? "on" : "off");
    status.append(" | background=").append(backgroundStatus);
    if (!backgroundErrorMessage.isBlank()) {
      status.append(" (").append(backgroundErrorMessage).append(')');
    }
    if (selectedRowIndex != null) {
      status.append(" | selected row=").append(selectedRowIndex);
    }
    statusLabel.setText(status.toString());
    statusLabel.getParent().layout();
  }

  private void updateInspectionSourceLabel() {
    if (inspectionSourceLabel.isDisposed()) {
      return;
    }
    inspectionSourceLabel.setText(buildInspectionSourceLabel());
    inspectionSourceLabel.getParent().layout();
  }

  private String buildInspectionSourceLabel() {
    StringBuilder label = new StringBuilder("Inspecting ");
    label.append(describeEffectiveSide());
    if (samplingResult.autoSwitched()) {
      label.append(" (auto-switched)");
    }
    return label.toString();
  }

  private String describeEffectiveSide() {
    GeometryInspectionSide effectiveSide = samplingResult.effectiveSide();
    if (effectiveSide == null) {
      return samplingResult.requestedSide() == null
          ? "unresolved rows"
          : samplingResult.requestedSide().rowsLabel();
    }
    return effectiveSide.rowsLabel();
  }

  private String abbreviate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, Math.max(0, maxLength - 3)) + "...";
  }

  private String rootCauseMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    if (current.getMessage() == null || current.getMessage().isBlank()) {
      return current.getClass().getSimpleName();
    }
    return current.getClass().getSimpleName() + ": " + current.getMessage();
  }

  private void showWarning(String title, String message) {
    MessageBox messageBox = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
    messageBox.setText(title);
    messageBox.setMessage(message);
    messageBox.open();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    requestCloseHitCandidateMenu();
    overlayCoordinator.close();
    backgroundCoordinator.close();
    replaceOverlayFrame(null);
    setBackgroundFrame(null);
    backgroundFrameCache.clear();
  }
}
