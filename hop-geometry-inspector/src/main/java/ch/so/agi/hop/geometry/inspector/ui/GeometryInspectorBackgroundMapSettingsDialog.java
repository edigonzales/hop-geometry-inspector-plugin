package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryInspectorSettingsService;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

public class GeometryInspectorBackgroundMapSettingsDialog {

  private final Shell parent;
  private final GeometryInspectorSettingsService settingsService;

  public GeometryInspectorBackgroundMapSettingsDialog(
      Shell parent, GeometryInspectorSettingsService settingsService) {
    this.parent = parent;
    this.settingsService = settingsService;
  }

  public GeometryInspectorBackgroundMapConfig open() {
    GeometryInspectorBackgroundMapConfig currentConfig = settingsService.loadBackgroundMapConfig();

    Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
    shell.setText("Background map settings");
    shell.setLayout(new GridLayout(2, false));

    Text serviceUrlText = createTextField(shell, "Service URL", currentConfig.serviceUrl());
    Text layerNamesText = createTextField(shell, "Layer names", currentConfig.layerNames());
    Text styleNameText = createTextField(shell, "Style name", currentConfig.styleName());
    Text imageFormatText = createTextField(shell, "Image format", currentConfig.imageFormat());
    Text versionText = createTextField(shell, "WMS version", currentConfig.version());

    Label transparentLabel = new Label(shell, SWT.NONE);
    transparentLabel.setText("Transparent");
    Button transparentButton = new Button(shell, SWT.CHECK);
    transparentButton.setSelection(currentConfig.transparent());

    Label enabledLabel = new Label(shell, SWT.NONE);
    enabledLabel.setText("Enabled by default");
    Button enabledButton = new Button(shell, SWT.CHECK);
    enabledButton.setSelection(currentConfig.enabledByDefault());

    Label helpLabel = new Label(shell, SWT.WRAP);
    helpLabel.setText("Layer names can be comma- or semicolon-separated.");
    helpLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

    Label filler = new Label(shell, SWT.NONE);
    filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

    Button clearButton = new Button(shell, SWT.PUSH);
    clearButton.setText("Clear");

    Button cancelButton = new Button(shell, SWT.PUSH);
    cancelButton.setText("Cancel");

    Button saveButton = new Button(shell, SWT.PUSH);
    saveButton.setText("Save");

    GridData clearData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
    clearData.horizontalSpan = 2;
    clearButton.setLayoutData(clearData);
    cancelButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
    saveButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

    final GeometryInspectorBackgroundMapConfig[] result = new GeometryInspectorBackgroundMapConfig[1];

    clearButton.addListener(
        SWT.Selection,
        event -> {
          GeometryInspectorBackgroundMapConfig emptyConfig = GeometryInspectorBackgroundMapConfig.empty();
          settingsService.saveBackgroundMapConfig(emptyConfig);
          result[0] = emptyConfig;
          shell.dispose();
        });

    cancelButton.addListener(SWT.Selection, event -> shell.dispose());

    saveButton.addListener(
        SWT.Selection,
        event -> {
          GeometryInspectorBackgroundMapConfig config =
              new GeometryInspectorBackgroundMapConfig(
                  serviceUrlText.getText(),
                  layerNamesText.getText(),
                  styleNameText.getText(),
                  imageFormatText.getText(),
                  versionText.getText(),
                  transparentButton.getSelection(),
                  enabledButton.getSelection());

          if (!config.isValid()) {
            MessageBox messageBox = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            messageBox.setText("Invalid background map settings");
            messageBox.setMessage("Please provide a WMS service URL and at least one layer name.");
            messageBox.open();
            return;
          }

          settingsService.saveBackgroundMapConfig(config);
          result[0] = config;
          shell.dispose();
        });

    shell.setDefaultButton(saveButton);
    shell.pack();
    shell.open();

    Display display = parent.getDisplay();
    while (!shell.isDisposed()) {
      if (!display.readAndDispatch()) {
        display.sleep();
      }
    }

    return result[0];
  }

  private Text createTextField(Shell shell, String label, String value) {
    Label fieldLabel = new Label(shell, SWT.NONE);
    fieldLabel.setText(label);

    Text text = new Text(shell, SWT.BORDER);
    text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    text.setText(value == null ? "" : value);
    return text;
  }
}
