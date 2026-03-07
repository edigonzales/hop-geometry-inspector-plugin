package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import org.apache.hop.ui.core.dialog.ErrorDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

public final class GeometryInspectorFallbackDialog {

  private GeometryInspectorFallbackDialog() {}

  public static void showSummary(
      Shell shell, String title, SamplingResult samplingResult, GeometryBuildResult buildResult) {
    MessageBox messageBox = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
    messageBox.setText(title);
    messageBox.setMessage(buildSummaryMessage(samplingResult, buildResult));
    messageBox.open();
  }

  public static void showError(
      Shell shell,
      String title,
      String message,
      Throwable error,
      SamplingResult samplingResult,
      GeometryBuildResult buildResult) {
    StringBuilder details = new StringBuilder(message);
    details.append("\n\n").append(buildSummaryMessage(samplingResult, buildResult));
    new ErrorDialog(shell, title, details.toString(), error);
  }

  private static String buildSummaryMessage(
      SamplingResult samplingResult, GeometryBuildResult buildResult) {
    StringBuilder summary = new StringBuilder();
    summary.append("Sampled rows: ").append(samplingResult.rows().size()).append("\n");
    summary.append("Parsed features: ").append(buildResult.features().size()).append("\n");
    summary.append("Parse errors: ").append(buildResult.parseErrors()).append("\n");
    summary.append("Null/empty geometries: ").append(buildResult.nullGeometries()).append("\n");
    summary.append("Sample completeness: ")
        .append(samplingResult.partial() ? "partial" : "full")
        .append("\n");

    if (!buildResult.parseErrorSamples().isEmpty()) {
      summary.append("First parse error: ").append(buildResult.parseErrorSamples().get(0)).append("\n");
    }

    if (!samplingResult.reason().isBlank()) {
      summary.append("Reason: ").append(samplingResult.reason()).append("\n");
    }

    if (!buildResult.crsStatusMessage().isBlank()) {
      summary.append("CRS: ").append(buildResult.crsStatusMessage()).append("\n");
    }

    if (!buildResult.extent().isEmpty()) {
      summary
          .append("Extent: ")
          .append(buildResult.extent().getMinX())
          .append(", ")
          .append(buildResult.extent().getMinY())
          .append(" - ")
          .append(buildResult.extent().getMaxX())
          .append(", ")
          .append(buildResult.extent().getMaxY());
    }

    return summary.toString();
  }
}
