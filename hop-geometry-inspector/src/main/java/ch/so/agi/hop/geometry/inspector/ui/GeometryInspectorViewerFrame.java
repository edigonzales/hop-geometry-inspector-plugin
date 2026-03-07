package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryFeatureBuilder;
import ch.so.agi.hop.geometry.inspector.GeometryInspectorClassLoaderSupport;
import ch.so.agi.hop.geometry.inspector.GeometrySelectionService;
import ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.IValueMeta;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.style.Style;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.geometry.Position2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.FeatureLayer;
import org.geotools.map.Layer;
import org.geotools.map.MapContent;
import org.geotools.ows.wms.WebMapServer;
import org.geotools.ows.wms.map.WMSCoverageReader;
import org.geotools.ows.wms.map.WMSLayer;
import org.geotools.renderer.lite.StreamingRenderer;
import org.geotools.styling.SLD;
import org.geotools.swing.JMapPane;
import org.geotools.swing.event.MapMouseAdapter;
import org.geotools.swing.event.MapMouseEvent;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Lineal;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.Puntal;

public class GeometryInspectorViewerFrame extends JFrame {

  private static final int TOOLBAR_ICON_SIZE = 20;
  private static final double PICK_TOLERANCE_PIXELS = 6.0d;
  private static final int MAX_TABLE_VALUE_LENGTH = 180;

  private final SamplingResult samplingResult;
  private final GeometryFeatureBuilder featureBuilder;
  private final GeometrySelectionService selectionService;
  private final List<String> geometryFields;
  private final GeometryInspectorBackgroundMapConfig backgroundMapConfig;

  private final JMapPane mapPane;
  private final JLabel statusLabel;
  private final JComboBox<String> fieldCombo;
  private final JToggleButton backgroundToggle;
  private final JLabel selectionSummaryLabel;
  private final DefaultTableModel attributeTableModel;
  private final JTextArea geometryDetailTextArea;

  private final AtomicLong backgroundRequestSequence = new AtomicLong();

  private MapContent mapContent;
  private GeometryBuildResult currentBuildResult;
  private Layer backgroundLayer;
  private Layer highlightLayer;
  private Integer selectedRowIndex;
  private ReferencedEnvelope pendingDisplayArea;
  private String backgroundStatusMessage = "off";

  public GeometryInspectorViewerFrame(
      SamplingResult samplingResult,
      GeometryFeatureBuilder featureBuilder,
      List<String> geometryFields,
      String selectedField,
      GeometryBuildResult initialBuildResult,
      GeometryInspectorBackgroundMapConfig backgroundMapConfig) {
    super("Geometry Inspector");
    this.samplingResult = samplingResult;
    this.featureBuilder = featureBuilder;
    this.geometryFields = geometryFields;
    this.backgroundMapConfig =
        backgroundMapConfig == null
            ? GeometryInspectorBackgroundMapConfig.empty()
            : backgroundMapConfig;
    this.selectionService = new GeometrySelectionService();

    setLayout(new BorderLayout());
    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    setPreferredSize(new Dimension(1240, 760));

    mapPane =
        new JMapPane(
            null, GeometryInspectorRenderingExecutorFactory.create(), new StreamingRenderer());
    mapPane.setBackground(Color.WHITE);
    mapPane.setPreferredSize(new Dimension(880, 640));
    mapPane.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentShown(ComponentEvent e) {
            applyPendingDisplayAreaIfPossible();
          }

          @Override
          public void componentResized(ComponentEvent e) {
            applyPendingDisplayAreaIfPossible();
          }
        });
    GeometryInspectorMapInteractionSupport.install(mapPane, new IdentifyMouseListener());

    fieldCombo = new JComboBox<>(geometryFields.toArray(String[]::new));
    fieldCombo.setSelectedItem(selectedField);

    attributeTableModel =
        new DefaultTableModel(new Object[] {"Attribute", "Value"}, 0) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };

    JTable attributeTable = new JTable(attributeTableModel);
    attributeTable.setFillsViewportHeight(true);
    attributeTable.setRowSelectionAllowed(false);
    attributeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    selectionSummaryLabel = new JLabel("No feature selected");
    geometryDetailTextArea = new JTextArea();
    geometryDetailTextArea.setEditable(false);
    geometryDetailTextArea.setLineWrap(true);
    geometryDetailTextArea.setWrapStyleWord(true);

    backgroundToggle = new JToggleButton();
    backgroundToggle.setToolTipText("Toggle background map");
    backgroundToggle.setIcon(GeometryInspectorToolbarIcons.background(TOOLBAR_ICON_SIZE));

    add(buildHeader(), BorderLayout.NORTH);
    add(buildSplitPane(attributeTable), BorderLayout.CENTER);

    statusLabel = new JLabel();
    statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
    add(statusLabel, BorderLayout.SOUTH);

    fieldCombo.addActionListener(event -> refreshForSelectedField());
    backgroundToggle.addActionListener(event -> onBackgroundToggleChanged());

    applyBuildResult(initialBuildResult, true);
    backgroundToggle.setSelected(this.backgroundMapConfig.isValid() && this.backgroundMapConfig.enabledByDefault());
    updateBackgroundAvailability();
    if (backgroundToggle.isEnabled() && backgroundToggle.isSelected()) {
      requestBackgroundLayerLoad();
    }

    pack();
    setLocationRelativeTo(null);
  }

  private JPanel buildHeader() {
    JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
    filterPanel.add(new JLabel("Geometry field:"));
    fieldCombo.setPreferredSize(new Dimension(220, fieldCombo.getPreferredSize().height));
    filterPanel.add(fieldCombo);

    JToolBar toolBar = new JToolBar();
    toolBar.setFloatable(false);
    toolBar.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 8));
    toolBar.add(createToolbarButton("Zoom in", GeometryInspectorToolbarIcons.zoomIn(TOOLBAR_ICON_SIZE), () -> zoomBy(0.5)));
    toolBar.add(createToolbarButton("Zoom out", GeometryInspectorToolbarIcons.zoomOut(TOOLBAR_ICON_SIZE), () -> zoomBy(2.0)));
    toolBar.add(createToolbarButton("Zoom to extent", GeometryInspectorToolbarIcons.zoomExtent(TOOLBAR_ICON_SIZE), this::zoomToExtent));
    toolBar.add(createToolbarButton("Refresh geometry view", GeometryInspectorToolbarIcons.refresh(TOOLBAR_ICON_SIZE), this::refreshForSelectedField));
    toolBar.addSeparator();
    toolBar.add(backgroundToggle);

    JPanel header = new JPanel(new BorderLayout());
    header.add(filterPanel, BorderLayout.WEST);
    header.add(toolBar, BorderLayout.EAST);
    return header;
  }

  private JSplitPane buildSplitPane(JTable attributeTable) {
    JScrollPane tableScrollPane = new JScrollPane(attributeTable);
    tableScrollPane.setBorder(BorderFactory.createTitledBorder("Attributes"));

    JScrollPane geometryScrollPane = new JScrollPane(geometryDetailTextArea);
    geometryScrollPane.setBorder(BorderFactory.createTitledBorder("Geometry (WKT/EWKT)"));
    geometryScrollPane.setPreferredSize(new Dimension(320, 210));

    JPanel infoPanel = new JPanel(new BorderLayout(0, 8));
    infoPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    infoPanel.setPreferredSize(new Dimension(340, 640));
    infoPanel.add(selectionSummaryLabel, BorderLayout.NORTH);
    infoPanel.add(tableScrollPane, BorderLayout.CENTER);
    infoPanel.add(geometryScrollPane, BorderLayout.SOUTH);

    JPanel mapPanel = new JPanel(new BorderLayout());
    mapPanel.add(mapPane, BorderLayout.CENTER);

    JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mapPanel, infoPanel);
    splitPane.setResizeWeight(0.76d);
    splitPane.setDividerLocation(0.76d);
    return splitPane;
  }

  private JButton createToolbarButton(String tooltip, Icon icon, Runnable action) {
    JButton button = new JButton(icon);
    button.setToolTipText(tooltip);
    button.setFocusable(false);
    button.addActionListener(event -> action.run());
    return button;
  }

  private void refreshForSelectedField() {
    String selectedField = (String) fieldCombo.getSelectedItem();
    if (selectedField == null || selectedField.isBlank()) {
      return;
    }

    GeometryBuildResult buildResult =
        featureBuilder.build(samplingResult.rowMeta(), samplingResult.rows(), selectedField);
    applyBuildResult(buildResult, true);
    if (backgroundToggle.isEnabled() && backgroundToggle.isSelected()) {
      requestBackgroundLayerLoad();
    }
  }

  private void applyBuildResult(GeometryBuildResult buildResult, boolean resetView) {
    currentBuildResult = buildResult;
    selectedRowIndex = null;
    backgroundLayer = null;
    highlightLayer = null;
    pendingDisplayArea = null;
    backgroundStatusMessage = backgroundToggle.isSelected() ? "reloading" : "off";
    backgroundRequestSequence.incrementAndGet();
    clearSelectionPanel();

    if (mapContent != null) {
      mapContent.dispose();
    }

    mapContent = new MapContent();
    mapContent.setTitle("Geometry sample");

    if (buildResult.hasRenderableFeatures()) {
      addFeatureLayers(buildResult.features(), buildResult.featureType(), mapContent);
    }

    mapPane.setMapContent(mapContent);
    if (resetView && buildResult.hasRenderableFeatures()) {
      zoomToExtent();
    }

    updateBackgroundAvailability();
    updateStatusLabel(buildResult);
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
    addLayerIfNotEmpty(targetMapContent, polygonFeatures, featureType, createPolygonStyle(), "Polygons");
  }

  private void addLayerIfNotEmpty(
      MapContent targetMapContent,
      List<SimpleFeature> features,
      SimpleFeatureType featureType,
      Style style,
      String title) {
    if (features.isEmpty()) {
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
      return SLD.createPointStyle("circle", new Color(35, 35, 35), new Color(255, 230, 65), 2.2f, 15.0f);
    }
    if (geometry instanceof Lineal) {
      return SLD.createLineStyle(new Color(255, 230, 65), 4.0f);
    }
    return SLD.createPolygonStyle(new Color(255, 230, 65), new Color(35, 35, 35), 0.18f);
  }

  private void zoomBy(double factor) {
    ReferencedEnvelope nextArea =
        GeometryInspectorViewportSupport.scaleAroundCenter(mapPane.getDisplayArea(), factor);
    if (nextArea == null) {
      return;
    }
    applyDisplayArea(nextArea);
  }

  private void zoomToExtent() {
    ReferencedEnvelope extent = null;
    if (currentBuildResult != null && currentBuildResult.extent() != null && !currentBuildResult.extent().isEmpty()) {
      extent = currentBuildResult.extent();
    } else if (mapContent != null) {
      extent = mapContent.getMaxBounds();
    }
    applyDisplayArea(extent);
  }

  private void applyDisplayArea(ReferencedEnvelope requestedArea) {
    ReferencedEnvelope normalized = GeometryInspectorViewportSupport.normalizeEnvelope(requestedArea);
    if (normalized == null) {
      return;
    }

    pendingDisplayArea = normalized;
    if (GeometryInspectorViewportSupport.safeSetDisplayArea(mapPane, normalized)) {
      pendingDisplayArea = null;
    }
  }

  private void applyPendingDisplayAreaIfPossible() {
    if (pendingDisplayArea == null) {
      return;
    }
    if (GeometryInspectorViewportSupport.safeSetDisplayArea(mapPane, pendingDisplayArea)) {
      pendingDisplayArea = null;
    }
  }

  private void onBackgroundToggleChanged() {
    if (!backgroundToggle.isSelected()) {
      removeBackgroundLayer();
      backgroundStatusMessage = "off";
      updateStatusLabel(currentBuildResult);
      return;
    }

    if (!backgroundToggle.isEnabled()) {
      backgroundToggle.setSelected(false);
      return;
    }

    requestBackgroundLayerLoad();
  }

  private void requestBackgroundLayerLoad() {
    if (currentBuildResult == null || !currentBuildResult.hasUsableCrs()) {
      backgroundStatusMessage = "unavailable";
      updateStatusLabel(currentBuildResult);
      return;
    }

    long requestId = backgroundRequestSequence.incrementAndGet();
    GeometryInspectorBackgroundMapConfig config = backgroundMapConfig;
    CoordinateReferenceSystem coordinateReferenceSystem = currentBuildResult.detectedCrs();
    backgroundStatusMessage = "loading";
    updateStatusLabel(currentBuildResult);

    SwingWorker<Layer, Void> worker =
        new SwingWorker<>() {
          @Override
          protected Layer doInBackground() throws Exception {
            return GeometryInspectorClassLoaderSupport.withPluginContextClassLoader(
                () -> buildBackgroundLayer(config, coordinateReferenceSystem));
          }

          @Override
          protected void done() {
            if (requestId != backgroundRequestSequence.get()) {
              return;
            }

            try {
              Layer layer = get();
              if (!backgroundToggle.isSelected()) {
                return;
              }
              attachBackgroundLayer(layer);
              backgroundStatusMessage = "on";
              updateStatusLabel(currentBuildResult);
            } catch (Exception e) {
              backgroundToggle.setSelected(false);
              removeBackgroundLayer();
              backgroundStatusMessage = "error: " + rootCauseMessage(e);
              updateStatusLabel(currentBuildResult);
              JOptionPane.showMessageDialog(
                  thisFrame(),
                  "Unable to load the configured background map.\n" + rootCauseMessage(e),
                  "Background map",
                  JOptionPane.WARNING_MESSAGE);
            }
          }
        };
    worker.execute();
  }

  private GeometryInspectorViewerFrame thisFrame() {
    return this;
  }

  private Layer buildBackgroundLayer(
      GeometryInspectorBackgroundMapConfig config, CoordinateReferenceSystem coordinateReferenceSystem)
      throws Exception {
    URL capabilitiesUrl = buildCapabilitiesUrl(config.serviceUrl(), config.version());
    WebMapServer webMapServer = new WebMapServer(capabilitiesUrl);

    List<org.geotools.ows.wms.Layer> resolvedLayers = new ArrayList<>();
    for (String layerName : config.parsedLayerNames()) {
      resolvedLayers.add(resolveLayer(webMapServer, layerName));
    }

    if (resolvedLayers.isEmpty()) {
      throw new IllegalStateException("No WMS layers could be resolved from the configured profile");
    }

    WMSLayer wmsLayer =
        config.styleName().isBlank()
            ? new WMSLayer(webMapServer, resolvedLayers.get(0))
            : new WMSLayer(webMapServer, resolvedLayers.get(0), config.styleName(), "Background");

    for (int index = 1; index < resolvedLayers.size(); index++) {
      if (config.styleName().isBlank()) {
        wmsLayer.addLayer(resolvedLayers.get(index));
      } else {
        wmsLayer.addLayer(resolvedLayers.get(index), config.styleName());
      }
    }

    applyWmsReaderSettings(wmsLayer, config);
    if (!wmsLayer.isNativelySupported(coordinateReferenceSystem)) {
      throw new IllegalStateException(
          "Configured WMS layer does not support " + currentBuildResult.crsStatusMessage());
    }

    wmsLayer.setTitle("Background");
    return wmsLayer;
  }

  private URL buildCapabilitiesUrl(String serviceUrl, String version) throws MalformedURLException {
    String normalized = serviceUrl == null ? "" : serviceUrl.trim();
    normalized = appendQueryParameterIfMissing(normalized, "service", "WMS");
    normalized = appendQueryParameterIfMissing(normalized, "request", "GetCapabilities");
    normalized = appendQueryParameterIfMissing(normalized, "version", version);
    return new URL(normalized);
  }

  private String appendQueryParameterIfMissing(String url, String key, String value) {
    if (url.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT) + "=")) {
      return url;
    }
    String separator = url.contains("?") ? "&" : "?";
    return url + separator + key + "=" + value;
  }

  private org.geotools.ows.wms.Layer resolveLayer(WebMapServer webMapServer, String layerName) {
    return webMapServer.getCapabilities().getLayerList().stream()
        .filter(layer -> layerName.equals(layer.getName()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("WMS layer not found: " + layerName));
  }

  private void applyWmsReaderSettings(WMSLayer wmsLayer, GeometryInspectorBackgroundMapConfig config) {
    try {
      WMSCoverageReader reader = wmsLayer.getReader();
      Field formatField = WMSCoverageReader.class.getDeclaredField("format");
      formatField.setAccessible(true);
      formatField.set(reader, config.imageFormat());
    } catch (Exception e) {
      // Ignore format customisation failures; rendering can still continue with GeoTools defaults.
    }
  }

  private void attachBackgroundLayer(Layer layer) {
    removeBackgroundLayer();
    backgroundLayer = layer;
    mapContent.addLayer(layer);
    mapContent.moveLayer(mapContent.layers().size() - 1, 0);
    mapPane.repaint();
  }

  private void removeBackgroundLayer() {
    if (backgroundLayer != null && mapContent != null) {
      mapContent.removeLayer(backgroundLayer);
    }
    backgroundLayer = null;
    mapPane.repaint();
  }

  private void updateBackgroundAvailability() {
    if (currentBuildResult == null) {
      backgroundToggle.setEnabled(false);
      backgroundToggle.setToolTipText("No geometry sample loaded");
      backgroundStatusMessage = "off";
      return;
    }

    if (!backgroundMapConfig.isValid()) {
      backgroundToggle.setEnabled(false);
      backgroundToggle.setSelected(false);
      backgroundStatusMessage = "not configured";
      backgroundToggle.setToolTipText("Configure a global WMS background in the options dialog");
      return;
    }

    if (!currentBuildResult.hasUsableCrs()) {
      backgroundToggle.setEnabled(false);
      backgroundToggle.setSelected(false);
      backgroundStatusMessage = "unavailable";
      backgroundToggle.setToolTipText(
          currentBuildResult.crsStatusMessage().isBlank()
              ? "Background map requires a consistent SRID/CRS"
              : currentBuildResult.crsStatusMessage());
      return;
    }

    backgroundToggle.setEnabled(true);
    backgroundToggle.setToolTipText("Toggle background map");
    if (!backgroundToggle.isSelected()) {
      backgroundStatusMessage = "off";
    }
  }

  private void refreshHighlightLayer() {
    if (mapContent == null) {
      return;
    }

    if (highlightLayer != null) {
      mapContent.removeLayer(highlightLayer);
      highlightLayer = null;
    }

    SimpleFeature selectedFeature = getSelectedFeature();
    if (selectedFeature == null) {
      mapPane.repaint();
      return;
    }

    Object defaultGeometry = selectedFeature.getDefaultGeometry();
    if (!(defaultGeometry instanceof Geometry geometry)) {
      mapPane.repaint();
      return;
    }

    highlightLayer =
        new FeatureLayer(
            new ListFeatureCollection(currentBuildResult.featureType(), List.of(selectedFeature)),
            createHighlightStyle(geometry),
            "Selection");
    mapContent.addLayer(highlightLayer);
    mapPane.repaint();
  }

  private SimpleFeature getSelectedFeature() {
    if (selectedRowIndex == null || currentBuildResult == null) {
      return null;
    }

    return currentBuildResult.features().stream()
        .filter(feature -> rowIndexOf(feature) == selectedRowIndex)
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

  private void updateSelection(SimpleFeature feature) {
    if (feature == null) {
      selectedRowIndex = null;
      clearSelectionPanel();
      refreshHighlightLayer();
      updateStatusLabel(currentBuildResult);
      return;
    }

    selectedRowIndex = rowIndexOf(feature);
    populateSelectionPanel(feature);
    refreshHighlightLayer();
    updateStatusLabel(currentBuildResult);
  }

  private void clearSelectionPanel() {
    selectionSummaryLabel.setText("No feature selected");
    attributeTableModel.setRowCount(0);
    geometryDetailTextArea.setText("");
  }

  private void populateSelectionPanel(SimpleFeature feature) {
    int rowIndex = rowIndexOf(feature);
    Geometry geometry = feature.getDefaultGeometry() instanceof Geometry g ? g : null;
    attributeTableModel.setRowCount(0);

    Object[] row =
        rowIndex >= 0 && rowIndex < samplingResult.rows().size()
            ? samplingResult.rows().get(rowIndex)
            : new Object[0];
    IRowMeta rowMeta = samplingResult.rowMeta();

    if (rowMeta != null) {
      for (int index = 0; index < rowMeta.size(); index++) {
        IValueMeta valueMeta = rowMeta.getValueMeta(index);
        Object value = row.length > index ? row[index] : null;
        attributeTableModel.addRow(
            new Object[] {valueMeta.getName(), abbreviate(formatValue(valueMeta, value), MAX_TABLE_VALUE_LENGTH)});
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

    geometryDetailTextArea.setText(extractGeometryDetail(rowIndex, geometry));
    geometryDetailTextArea.setCaretPosition(0);
  }

  private String extractGeometryDetail(int rowIndex, Geometry geometry) {
    IRowMeta rowMeta = samplingResult.rowMeta();
    if (rowMeta != null) {
      int geometryIndex = rowMeta.indexOfValue((String) fieldCombo.getSelectedItem());
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

    return geometry.getSRID() > 0 ? "SRID=" + geometry.getSRID() + ";" + geometry.toText() : geometry.toText();
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
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    }

    return String.valueOf(value);
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

  private void updateStatusLabel(GeometryBuildResult buildResult) {
    if (buildResult == null) {
      statusLabel.setText("No geometry sample loaded");
      return;
    }

    StringBuilder status = new StringBuilder();
    status.append("sampled rows=").append(samplingResult.rows().size());
    status.append(" | parsed features=").append(buildResult.features().size());
    status.append(" | parse errors=").append(buildResult.parseErrors());
    status.append(" | null/empty=").append(buildResult.nullGeometries());
    status.append(" | sample=").append(samplingResult.partial() ? "partial" : "full");
    if (!samplingResult.reason().isBlank()) {
      status.append(" (").append(samplingResult.reason()).append(")");
    }
    if (!buildResult.crsStatusMessage().isBlank()) {
      status.append(" | crs=").append(buildResult.crsStatusMessage());
    }
    status.append(" | background=").append(backgroundStatusMessage);
    if (selectedRowIndex != null) {
      status.append(" | selected row=").append(selectedRowIndex);
    }
    statusLabel.setText(status.toString());
  }

  @Override
  public void dispose() {
    backgroundRequestSequence.incrementAndGet();
    if (mapContent != null) {
      mapContent.dispose();
      mapContent = null;
    }
    super.dispose();
  }

  private final class IdentifyMouseListener extends MapMouseAdapter {
    @Override
    public void onMouseClicked(MapMouseEvent event) {
      if (event.getButton() != MouseEvent.BUTTON1 || currentBuildResult == null) {
        return;
      }
      if (currentBuildResult.features().isEmpty()) {
        return;
      }

      Position2D worldPosition = event.getWorldPos();
      if (worldPosition == null) {
        updateSelection(null);
        return;
      }

      ReferencedEnvelope pickArea = event.getEnvelopeByPixels(PICK_TOLERANCE_PIXELS);
      if (pickArea == null || pickArea.isEmpty()) {
        updateSelection(null);
        return;
      }

      Coordinate coordinate = new Coordinate(worldPosition.getX(), worldPosition.getY());
      Envelope pickEnvelope = new Envelope(pickArea.getMinX(), pickArea.getMaxX(), pickArea.getMinY(), pickArea.getMaxY());
      Optional<SimpleFeature> selection =
          selectionService.selectFeature(currentBuildResult.features(), coordinate, pickEnvelope);
      updateSelection(selection.orElse(null));
    }
  }
}
