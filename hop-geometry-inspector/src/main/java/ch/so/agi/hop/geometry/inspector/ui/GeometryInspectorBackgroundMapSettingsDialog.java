package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryInspectorClassLoaderSupport;
import ch.so.agi.hop.geometry.inspector.GeometryInspectorSettingsService;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig.ServiceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.geotools.ows.wmts.model.WMTSLayer;

public class GeometryInspectorBackgroundMapSettingsDialog {
  private final Shell parent;
  private final GeometryInspectorSettingsService settingsService;
  private Shell shell;
  private Combo type, layer, style, format, matrix;
  private Text url, wmsLayers, wmsStyle, wmsFormat, version;
  private Button transparent, enabled, save, load;
  private Composite wmsPanel, wmtsPanel, dimensionsPanel;
  private Label urlLabel, status;
  private WmtsCatalog catalog;
  private long loadRevision;
  private final Map<String, Text> dimensionInputs = new LinkedHashMap<>();
  private GeometryInspectorBackgroundMapConfig initial;
  private ThreadPoolExecutor executor;
  private Future<?> loadFuture;

  public GeometryInspectorBackgroundMapSettingsDialog(
      Shell parent, GeometryInspectorSettingsService settingsService) {
    this.parent = parent;
    this.settingsService = settingsService;
  }

  public GeometryInspectorBackgroundMapConfig open() {
    initial = settingsService.loadBackgroundMapConfig();
    executor =
        (ThreadPoolExecutor)
            Executors.newFixedThreadPool(
                2,
                GeometryInspectorClassLoaderSupport.newPluginContextThreadFactory(
                    r -> {
                      Thread thread = new Thread(r, "geometry-inspector-wmts-capabilities");
                      thread.setDaemon(true);
                      return thread;
                    }));
    shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
    shell.setText("Background map settings");
    shell.setLayout(new GridLayout(2, false));
    type = combo(shell, "Service type");
    type.setItems("WMS", "WMTS");
    type.select(initial.serviceType() == ServiceType.WMS ? 0 : 1);
    urlLabel = new Label(shell, SWT.NONE);
    url = new Text(shell, SWT.BORDER);
    GridData urlData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    urlData.widthHint = 480;
    url.setLayoutData(urlData);
    url.setText(initial.serviceUrl());

    wmsPanel = panel(shell);
    wmsLayers = text(wmsPanel, "Layer names", initial.layerNames());
    wmsStyle = text(wmsPanel, "Style name", initial.styleName());
    wmsFormat = text(wmsPanel, "Image format", initial.imageFormat());
    version = text(wmsPanel, "WMS version", initial.version());
    transparent = check(wmsPanel, "Transparent", initial.transparent());
    Label help = new Label(wmsPanel, SWT.WRAP);
    help.setText("Layer names can be comma- or semicolon-separated.");
    help.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

    wmtsPanel = panel(shell);
    load = new Button(wmtsPanel, SWT.PUSH);
    load.setText("Load layers");
    status = new Label(wmtsPanel, SWT.WRAP);
    GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    statusData.widthHint = 350;
    status.setLayoutData(statusData);
    layer = combo(wmtsPanel, "Layer");
    style = combo(wmtsPanel, "Style");
    format = combo(wmtsPanel, "Image format");
    matrix = combo(wmtsPanel, "TileMatrixSet");
    dimensionsPanel = panel(wmtsPanel);
    enabled = check(shell, "Enabled by default", initial.enabledByDefault());

    Composite buttons = panel(shell);
    buttons.setLayout(new GridLayout(3, false));
    Button clear = new Button(buttons, SWT.PUSH);
    clear.setText("Clear");
    Button cancel = new Button(buttons, SWT.PUSH);
    cancel.setText("Cancel");
    save = new Button(buttons, SWT.PUSH);
    save.setText("Save");
    final GeometryInspectorBackgroundMapConfig[] result =
        new GeometryInspectorBackgroundMapConfig[1];
    clear.addListener(
        SWT.Selection,
        e -> {
          result[0] = GeometryInspectorBackgroundMapConfig.empty();
          settingsService.saveBackgroundMapConfig(result[0]);
          shell.dispose();
        });
    cancel.addListener(SWT.Selection, e -> shell.dispose());
    save.addListener(
        SWT.Selection,
        e -> {
          try {
            result[0] = collect();
            settingsService.saveBackgroundMapConfig(result[0]);
            shell.dispose();
          } catch (IllegalArgumentException invalid) {
            MessageBox message = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            message.setText("Invalid background map settings");
            message.setMessage(invalid.getMessage());
            message.open();
          }
        });
    type.addListener(
        SWT.Selection,
        e -> {
          invalidate();
          updateMode();
        });
    url.addModifyListener(e -> invalidate());
    layer.addListener(SWT.Selection, e -> populateLayer(false));
    load.addListener(SWT.Selection, e -> loadLayers());
    shell.addListener(
        SWT.Dispose,
        e -> {
          loadRevision++;
          cancelLoad();
          executor.shutdownNow();
        });
    shell.setDefaultButton(save);
    updateMode();
    shell.pack();
    shell.open();
    if (isWmts() && !url.getText().isBlank()) loadLayers();
    Display display = parent.getDisplay();
    while (!shell.isDisposed()) if (!display.readAndDispatch()) display.sleep();
    return result[0];
  }

  private boolean isWmts() {
    return type.getSelectionIndex() == 1;
  }

  private void invalidate() {
    cancelLoad();
    loadRevision++;
    catalog = null;
    if (status != null) status.setText("Load capabilities to select a layer.");
    if (layer != null) {
      layer.removeAll();
      style.removeAll();
      format.removeAll();
      matrix.removeAll();
      for (Control control : dimensionsPanel.getChildren()) control.dispose();
      dimensionInputs.clear();
    }
    if (save != null) save.setEnabled(!isWmts());
  }

  private void updateMode() {
    boolean wmts = isWmts();
    urlLabel.setText(wmts ? "Capabilities URL" : "Service URL");
    show(wmsPanel, !wmts);
    show(wmtsPanel, wmts);
    save.setEnabled(!wmts || catalog != null);
    shell.layout(true, true);
  }

  private void cancelLoad() {
    if (loadFuture != null) loadFuture.cancel(true);
    executor.purge();
  }

  private void loadLayers() {
    cancelLoad();
    final long revision = ++loadRevision;
    final String address = url.getText().trim();
    catalog = null;
    save.setEnabled(false);
    status.setText("Loading capabilities…");
    Display display = shell.getDisplay();
    loadFuture =
        executor.submit(
            () -> {
              WmtsCatalog loaded = null;
              String error = null;
              try {
                loaded = WmtsCatalog.load(address);
              } catch (Exception failure) {
                error = failure.getMessage() == null ? failure.toString() : failure.getMessage();
              }
              final WmtsCatalog value = loaded;
              final String failure = error;
              if (display.isDisposed()) return;
              display.asyncExec(
                  () -> {
                    if (shell.isDisposed() || revision != loadRevision || !isWmts()) return;
                    if (failure != null) {
                      status.setText("Loading failed: " + failure);
                      shell.layout(true, true);
                      return;
                    }
                    catalog = value;
                    layer.setItems(
                        catalog.capabilities.getLayerList().stream()
                            .map(WMTSLayer::getName)
                            .toArray(String[]::new));
                    select(layer, initial.layerNames());
                    status.setText(layer.getItemCount() + " layer(s) available");
                    populateLayer(true);
                  });
            });
  }

  private void populateLayer(boolean restore) {
    for (Control control : dimensionsPanel.getChildren()) control.dispose();
    dimensionInputs.clear();
    save.setEnabled(false);
    if (catalog == null || layer.getSelectionIndex() < 0) return;
    try {
      WMTSLayer selected = catalog.layer(layer.getText());
      boolean same =
          restore
              && initial.layerNames().equals(selected.getName())
              && initial.serviceUrl().equals(url.getText().trim());
      style.setItems(selected.getStyles().stream().map(s -> s.getName()).toArray(String[]::new));
      select(
          style,
          same && !initial.styleName().isBlank()
              ? initial.styleName()
              : WmtsCatalog.defaultStyle(selected));
      format.setItems(WmtsCatalog.formats(selected).toArray(String[]::new));
      select(format, same ? initial.imageFormat() : "image/png");
      List<String> matrices = new ArrayList<>();
      matrices.add("Automatic (match geometry CRS)");
      catalog.matrixSets(selected).forEach(m -> matrices.add(m.getIdentifier()));
      matrix.setItems(matrices.toArray(String[]::new));
      select(matrix, same ? initial.tileMatrixSet() : "");
      for (var entry : WmtsCatalog.defaults(selected).entrySet()) {
        boolean missing = entry.getValue().isBlank();
        Text input =
            text(
                dimensionsPanel,
                entry.getKey() + (missing ? " (required)" : " (default)"),
                missing && same
                    ? initial.dimensions().getOrDefault(entry.getKey(), "")
                    : entry.getValue());
        input.setEditable(missing);
        var dim = selected.getDimension(entry.getKey());
        if (dim != null && dim.getExtent() != null)
          input.setToolTipText(dim.getExtent().getValue());
        if (missing) dimensionInputs.put(entry.getKey(), input);
      }
      save.setEnabled(
          format.getSelectionIndex() >= 0 && style.getSelectionIndex() >= 0 && matrices.size() > 1);
    } catch (IllegalArgumentException error) {
      status.setText(error.getMessage());
    }
    shell.layout(true, true);
    shell.setSize(
        shell.getSize().x,
        Math.max(shell.getSize().y, shell.computeSize(shell.getSize().x, SWT.DEFAULT).y));
  }

  private GeometryInspectorBackgroundMapConfig collect() {
    if (!isWmts()) {
      var config =
          new GeometryInspectorBackgroundMapConfig(
              url.getText(),
              wmsLayers.getText(),
              wmsStyle.getText(),
              wmsFormat.getText(),
              version.getText(),
              transparent.getSelection(),
              enabled.getSelection());
      if (!config.isValid())
        throw new IllegalArgumentException(
            "Please provide a WMS service URL and at least one layer name.");
      return config;
    }
    if (catalog == null
        || layer.getSelectionIndex() < 0
        || style.getSelectionIndex() < 0
        || format.getSelectionIndex() < 0)
      throw new IllegalArgumentException("Load capabilities and select a supported layer first.");
    Map<String, String> dimensions = new LinkedHashMap<>();
    dimensionInputs.forEach(
        (name, input) -> {
          if (input.getText().isBlank())
            throw new IllegalArgumentException("Please provide WMTS dimension: " + name);
          dimensions.put(name, input.getText().trim());
        });
    return new GeometryInspectorBackgroundMapConfig(
        url.getText(),
        layer.getText(),
        style.getText(),
        format.getText(),
        "1.0.0",
        true,
        enabled.getSelection(),
        ServiceType.WMTS,
        matrix.getSelectionIndex() <= 0 ? "" : matrix.getText(),
        dimensions);
  }

  private static void select(Combo combo, String preferred) {
    int index = combo.indexOf(preferred);
    combo.select(index >= 0 ? index : 0);
  }

  private static void show(Control control, boolean visible) {
    ((GridData) control.getLayoutData()).exclude = !visible;
    control.setVisible(visible);
  }

  private static Composite panel(Composite parent) {
    Composite panel = new Composite(parent, SWT.NONE);
    panel.setLayout(new GridLayout(2, false));
    panel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    return panel;
  }

  private static Text text(Composite parent, String label, String value) {
    new Label(parent, SWT.NONE).setText(label);
    Text text = new Text(parent, SWT.BORDER);
    text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    text.setText(value);
    return text;
  }

  private static Combo combo(Composite parent, String label) {
    new Label(parent, SWT.NONE).setText(label);
    Combo combo = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
    combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    return combo;
  }

  private static Button check(Composite parent, String label, boolean value) {
    new Label(parent, SWT.NONE).setText(label);
    Button button = new Button(parent, SWT.CHECK);
    button.setSelection(value);
    return button;
  }
}
