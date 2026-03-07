package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryInspectorSettingsService;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.GeometryFieldCandidate;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
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
  private final List<GeometryFieldCandidate> outputCandidates;
  private final List<GeometryFieldCandidate> inputCandidates;
  private final GeometryInspectorSettingsService settingsService;

  public GeometryInspectorOptionsDialog(
      Shell parent,
      List<GeometryFieldCandidate> outputCandidates,
      List<GeometryFieldCandidate> inputCandidates,
      GeometryInspectorSettingsService settingsService) {
    this.parent = parent;
    this.outputCandidates = outputCandidates == null ? List.of() : List.copyOf(outputCandidates);
    this.inputCandidates = inputCandidates == null ? List.of() : List.copyOf(inputCandidates);
    this.settingsService = settingsService;
  }

  public GeometryInspectorOptions open() {
    Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
    shell.setText("Inspect geometries...");
    shell.setLayout(new GridLayout(2, false));

    Label inspectionSideLabel = new Label(shell, SWT.NONE);
    inspectionSideLabel.setText("Geometry source");

    Combo inspectionSideCombo = new Combo(shell, SWT.DROP_DOWN | SWT.READ_ONLY);
    inspectionSideCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    inspectionSideCombo.setItems(
        new String[] {
          GeometryInspectionSide.AUTO.label(),
          GeometryInspectionSide.OUTPUT.label(),
          GeometryInspectionSide.INPUT.label()
        });
    inspectionSideCombo.setText(GeometryInspectionSide.AUTO.label());

    Label geometryFieldLabel = new Label(shell, SWT.NONE);
    geometryFieldLabel.setText("Geometry field");

    Combo geometryFieldCombo = new Combo(shell, SWT.DROP_DOWN | SWT.READ_ONLY);
    geometryFieldCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    updateGeometryFieldChoices(geometryFieldCombo, GeometryInspectionSide.AUTO, null);

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

    inspectionSideCombo.addListener(
        SWT.Selection,
        event ->
            updateGeometryFieldChoices(
                geometryFieldCombo,
                GeometryInspectionSide.fromLabel(inspectionSideCombo.getText()),
                geometryFieldCombo.getText()));

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
            GeometryInspectionSide inspectionSide =
                GeometryInspectionSide.fromLabel(inspectionSideCombo.getText());
            String geometryField = geometryFieldCombo.getText();

            if (geometryField == null || geometryField.isBlank()) {
              throw new IllegalArgumentException("No geometry field selected");
            }

            result[0] =
                new GeometryInspectorOptions(
                    sampleSize,
                    mode,
                    inspectionSide,
                    geometryField,
                    Duration.ofSeconds(timeoutSeconds));
            shell.dispose();
          } catch (Exception e) {
            MessageBox messageBox = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            messageBox.setText("Invalid options");
            messageBox.setMessage(
                "Please provide valid numeric values and select a geometry field.");
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

  private void updateGeometryFieldChoices(
      Combo geometryFieldCombo, GeometryInspectionSide side, String preferredSelection) {
    List<GeometryFieldCandidate> candidates = candidatesForSide(side);
    List<String> fieldNames = candidates.stream().map(GeometryFieldCandidate::fieldName).toList();
    geometryFieldCombo.setItems(fieldNames.toArray(String[]::new));

    String selection = preferredSelection;
    if (selection == null || selection.isBlank() || !fieldNames.contains(selection)) {
      selection = chooseDefaultField(candidates);
    }

    if (selection != null && !selection.isBlank()) {
      geometryFieldCombo.setText(selection);
    } else {
      geometryFieldCombo.deselectAll();
      geometryFieldCombo.clearSelection();
      geometryFieldCombo.setText("");
    }
  }

  private List<GeometryFieldCandidate> candidatesForSide(GeometryInspectionSide side) {
    return switch (side) {
      case OUTPUT -> outputCandidates;
      case INPUT -> inputCandidates;
      case AUTO -> !outputCandidates.isEmpty() ? outputCandidates : inputCandidates;
    };
  }

  private String chooseDefaultField(List<GeometryFieldCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    for (GeometryFieldCandidate candidate : candidates) {
      if (candidate.geometryValueMeta()) {
        return candidate.fieldName();
      }
    }
    return candidates.get(0).fieldName();
  }
}
