package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryInspectorSettingsService;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.SamplingMode;
import java.time.Duration;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

public class GeometryInspectorOptionsDialog {

  private final Shell parent;
  private final List<String> geometryFields;
  private final String defaultGeometryField;
  private final GeometryInspectorSettingsService settingsService;

  public GeometryInspectorOptionsDialog(
      Shell parent,
      List<String> geometryFields,
      String defaultGeometryField,
      GeometryInspectorSettingsService settingsService) {
    this.parent = parent;
    this.geometryFields = geometryFields;
    this.defaultGeometryField = defaultGeometryField;
    this.settingsService = settingsService;
  }

  public GeometryInspectorOptions open() {
    Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
    shell.setText("Inspect geometries...");
    shell.setLayout(new GridLayout(2, false));

    Label geometryFieldLabel = new Label(shell, SWT.NONE);
    geometryFieldLabel.setText("Geometry field");

    Combo geometryFieldCombo = new Combo(shell, SWT.DROP_DOWN | SWT.READ_ONLY);
    geometryFieldCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    geometryFieldCombo.setItems(geometryFields.toArray(String[]::new));
    if (defaultGeometryField != null) {
      geometryFieldCombo.setText(defaultGeometryField);
    }

    Label sampleSizeLabel = new Label(shell, SWT.NONE);
    sampleSizeLabel.setText("Sample size");

    Combo sampleSizeCombo = new Combo(shell, SWT.DROP_DOWN);
    sampleSizeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    sampleSizeCombo.setItems(new String[] {"50", "200", "1000"});
    sampleSizeCombo.setText("200");

    Label samplingModeLabel = new Label(shell, SWT.NONE);
    samplingModeLabel.setText("Sampling mode");

    Combo samplingModeCombo = new Combo(shell, SWT.DROP_DOWN | SWT.READ_ONLY);
    samplingModeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    samplingModeCombo.setItems(
        new String[] {SamplingMode.FIRST.name(), SamplingMode.LAST.name(), SamplingMode.RANDOM.name()});
    samplingModeCombo.setText(SamplingMode.FIRST.name());

    Label timeoutLabel = new Label(shell, SWT.NONE);
    timeoutLabel.setText("Timeout (seconds)");

    Combo timeoutCombo = new Combo(shell, SWT.DROP_DOWN);
    timeoutCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    timeoutCombo.setItems(new String[] {"10", "30", "60"});
    timeoutCombo.setText("30");

    Label backgroundSummaryLabel = new Label(shell, SWT.WRAP);
    backgroundSummaryLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    updateBackgroundSummary(backgroundSummaryLabel, settingsService.loadBackgroundMapConfig());

    Button backgroundSettingsButton = new Button(shell, SWT.PUSH);
    backgroundSettingsButton.setText("Background map settings...");
    backgroundSettingsButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

    Label filler = new Label(shell, SWT.NONE);
    filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

    Button cancelButton = new Button(shell, SWT.PUSH);
    cancelButton.setText("Cancel");
    cancelButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));

    Button startButton = new Button(shell, SWT.PUSH);
    startButton.setText("Start");
    startButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

    final GeometryInspectorOptions[] result = new GeometryInspectorOptions[1];

    backgroundSettingsButton.addListener(
        SWT.Selection,
        event -> {
          GeometryInspectorBackgroundMapSettingsDialog settingsDialog =
              new GeometryInspectorBackgroundMapSettingsDialog(shell, settingsService);
          GeometryInspectorBackgroundMapConfig config = settingsDialog.open();
          if (config != null) {
            updateBackgroundSummary(backgroundSummaryLabel, config);
            shell.layout(true, true);
          }
        });

    cancelButton.addListener(SWT.Selection, event -> shell.dispose());
    startButton.addListener(
        SWT.Selection,
        event -> {
          try {
            int sampleSize = Integer.parseInt(sampleSizeCombo.getText().trim());
            int timeoutSeconds = Integer.parseInt(timeoutCombo.getText().trim());
            SamplingMode mode = SamplingMode.fromLabel(samplingModeCombo.getText());
            String geometryField = geometryFieldCombo.getText();

            result[0] =
                new GeometryInspectorOptions(
                    sampleSize, mode, geometryField, Duration.ofSeconds(timeoutSeconds));
            shell.dispose();
          } catch (Exception e) {
            MessageBox messageBox = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            messageBox.setText("Invalid options");
            messageBox.setMessage("Please provide valid numeric values for sample size and timeout.");
            messageBox.open();
          }
        });

    shell.setDefaultButton(startButton);
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

  private void updateBackgroundSummary(
      Label label, GeometryInspectorBackgroundMapConfig backgroundMapConfig) {
    if (backgroundMapConfig == null || !backgroundMapConfig.isValid()) {
      label.setText("Background map: not configured");
      return;
    }

    label.setText(
        "Background map: configured for "
            + String.join(", ", backgroundMapConfig.parsedLayerNames())
            + (backgroundMapConfig.enabledByDefault() ? " (enabled by default)" : ""));
  }
}
