package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryFeatureBuilder;
import ch.so.agi.hop.geometry.inspector.GeometryInspectorClassLoaderSupport;
import ch.so.agi.hop.geometry.inspector.GeometrySelectionService;
import java.awt.Rectangle;
import ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
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
  private final Label inspectionSourceLabel;
  private final Label selectionSummaryLabel;
  private final Table attributeTable;
  private final Text geometryDetailText;
  private final Label statusLabel;

  private GeometryBuildResult currentBuildResult;
  private GeometryInspectorFrame overlayFrame;
  private GeometryInspectorFrame backgroundFrame;
  private Integer selectedRowIndex;
  private String overlayStatus = "idle";
  private String backgroundStatus = "off";
  private String backgroundErrorMessage = "";
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
    GridLayout toolsLayout = new GridLayout(5, false);
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

    selectionSummaryLabel = new Label(infoComposite, SWT.WRAP);
    selectionSummaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    attributeTable =
        new Table(infoComposite, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    attributeTable.setHeaderVisible(true);
    attributeTable.setLinesVisible(true);
    attributeTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    TableColumn nameColumn = new TableColumn(attributeTable, SWT.LEFT);
    nameColumn.setText("Attribute");
    nameColumn.setWidth(180);
    TableColumn valueColumn = new TableColumn(attributeTable, SWT.LEFT);
    valueColumn.setText("Value");
    valueColumn.setWidth(360);

    Label geometryLabel = new Label(infoComposite, SWT.NONE);
    geometryLabel.setText("Geometry (WKT/EWKT)");

    geometryDetailText =
        new Text(infoComposite, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP);
    GridData geometryTextData = new GridData(SWT.FILL, SWT.FILL, true, false);
    geometryTextData.heightHint = 180;
    geometryDetailText.setLayoutData(geometryTextData);
    geometryDetailText.setEditable(false);

    sashForm.setWeights(78, 22);

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
    backgroundToggle.addListener(SWT.Selection, event -> onBackgroundToggleChanged());

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
          if (event.button != 1) {
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
    return viewportModel.updateCanvasMetrics(size.x, size.y, currentDeviceZoom());
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
    selectedRowIndex = null;
    clearSelectionPanel();
    replaceOverlayFrame(null);
    setBackgroundFrame(null);
    backgroundFrameCache.clear();
    backgroundErrorMessage = "";

    if (resetView) {
      setDisplayArea(defaultExtent(buildResult));
    }

    overlayStatus = buildResult != null && buildResult.hasRenderableFeatures() ? "stale" : "idle";
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
                areaSnapshot,
                logicalWidth,
                logicalHeight,
                deviceZoom,
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
            currentBuildResult == null ? null : currentBuildResult.detectedSrid());
    GeometryInspectorFrameKey cacheKey =
        GeometryInspectorFrameKey.forBackground(
            backgroundMapConfig,
            requestParameters.displayArea(),
            requestParameters.pixelWidth(),
            requestParameters.pixelHeight(),
            viewportModel.deviceZoom(),
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
    Integer srid = currentBuildResult.detectedSrid();

    backgroundStatus = immediate ? "loading" : "stale";
    backgroundErrorMessage = "";
    updateStatusLabel();

      backgroundCoordinator.schedule(
          immediate ? 0L : BACKGROUND_DEBOUNCE_MILLIS,
          revision ->
              backgroundMapClient.render(
                  areaSnapshot, logicalWidth, logicalHeight, deviceZoom, srid, revision),
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
      ReferencedEnvelope displayArea,
      int logicalWidth,
      int logicalHeight,
      int deviceZoom,
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
      addFeatureLayers(buildResult.features(), buildResult.featureType(), mapContent);
      SimpleFeature selectedFeature = featureForRow(buildResult, selectedRow);
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
      List<SimpleFeature> features, SimpleFeatureType featureType, MapContent targetMapContent) {
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

    addLayerIfNotEmpty(targetMapContent, pointFeatures, featureType, createPointStyle(), "Points");
    addLayerIfNotEmpty(targetMapContent, lineFeatures, featureType, createLineStyle(), "Lines");
    addLayerIfNotEmpty(
        targetMapContent, polygonFeatures, featureType, createPolygonStyle(), "Polygons");
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

  private Style createPointStyle() {
    return SLD.createPointStyle("circle", new Color(215, 48, 39), Color.BLACK, 1.1f, 11.0f);
  }

  private Style createLineStyle() {
    return SLD.createLineStyle(new Color(69, 117, 180), 2.2f);
  }

  private Style createPolygonStyle() {
    return SLD.createPolygonStyle(new Color(253, 174, 97), new Color(49, 54, 149), 0.48f);
  }

  private Style createHighlightStyle(Geometry geometry) {
    if (geometry instanceof Puntal) {
      return SLD.createPointStyle(
          "circle", new Color(35, 35, 35), new Color(255, 230, 65), 2.2f, 15.0f);
    }
    if (geometry instanceof Lineal) {
      return SLD.createLineStyle(new Color(255, 230, 65), 4.0f);
    }
    return SLD.createPolygonStyle(new Color(255, 230, 65), new Color(35, 35, 35), 0.18f);
  }

  private void identifyFeature(int x, int y) {
    if (currentBuildResult == null || currentBuildResult.features().isEmpty()) {
      updateSelection(null);
      return;
    }

    GeometryInspectorViewTransform viewTransform = currentViewTransform();
    if (viewTransform == null || !viewportModel.isCanvasUsable()) {
      updateSelection(null);
      return;
    }
    if (!viewTransform.containsScreenPoint(x, y)) {
      updateSelection(null);
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

    Optional<SimpleFeature> selection =
        selectionService.selectFeature(currentBuildResult.features(), coordinate, pickEnvelope);
    updateSelection(selection.orElse(null));
  }

  private void updateSelection(SimpleFeature feature) {
    if (feature == null) {
      selectedRowIndex = null;
      clearSelectionPanel();
      requestOverlayRender(true);
      updateStatusLabel();
      return;
    }

    selectedRowIndex = rowIndexOf(feature);
    populateSelectionPanel(feature);
    requestOverlayRender(true);
    updateStatusLabel();
  }

  private void clearSelectionPanel() {
    selectionSummaryLabel.setText("No feature selected");
    attributeTable.removeAll();
    geometryDetailText.setText("");
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

    if (rowMeta != null) {
      for (int index = 0; index < rowMeta.size(); index++) {
        IValueMeta valueMeta = rowMeta.getValueMeta(index);
        Object value = row.length > index ? row[index] : null;
        TableItem item = new TableItem(attributeTable, SWT.NONE);
        item.setText(
            new String[] {
              valueMeta.getName(), abbreviate(formatValue(valueMeta, value), MAX_TABLE_VALUE_LENGTH)
            });
      }
    }

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
    overlayCoordinator.close();
    backgroundCoordinator.close();
    replaceOverlayFrame(null);
    setBackgroundFrame(null);
    backgroundFrameCache.clear();
  }
}
