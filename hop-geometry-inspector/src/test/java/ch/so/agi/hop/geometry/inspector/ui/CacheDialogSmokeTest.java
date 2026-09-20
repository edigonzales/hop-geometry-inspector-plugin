package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.data.CacheRepository;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.pipeline.PipelineMeta;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in desktop smoke test: requires an interactive display. */
@EnabledIfSystemProperty(named = "geometryInspector.swtSmoke", matches = "true")
class CacheDialogSmokeTest {
  @TempDir Path directory;

  @Test
  void emptyCacheDialogExplainsRecordingAndOffersCompleteCapture() throws Exception {
    Display display = Display.getCurrent();
    boolean owns = display == null;
    if (owns) display = new Display();
    Shell parent = new Shell(display);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Display ui = display;
    Runnable[] check = new Runnable[1];
    check[0] =
        () -> {
          try {
            Shell dialog =
                Arrays.stream(ui.getShells())
                    .filter(s -> s.getText().startsWith("Data Inspector caches / replay from"))
                    .findFirst()
                    .orElse(null);
            if (dialog == null) {
              ui.timerExec(25, check[0]);
              return;
            }
            List<Control> controls = descendants(dialog);
            assertThat(
                    controls.stream()
                        .filter(c -> c instanceof Label)
                        .map(c -> ((Label) c).getText())
                        .anyMatch(text -> text.contains("No caches have been recorded")))
                .isTrue();
            assertThat(
                    controls.stream()
                        .filter(c -> c instanceof Table)
                        .map(c -> ((Table) c).getItemCount())
                        .findFirst()
                        .orElse(-1))
                .isZero();
            Button record = button(controls, "Record complete cache...");
            assertThat(record.getEnabled()).isTrue();
            assertThat(button(controls, "Open").getEnabled()).isFalse();
            assertThat(button(controls, "Delete").getEnabled()).isFalse();
            assertThat(button(controls, "Record again").getEnabled()).isFalse();
            assertThat(button(controls, "Run TO").getEnabled()).isTrue();
            record.notifyListeners(SWT.Selection, new Event());
          } catch (Throwable error) {
            failure.set(error);
            for (Shell shell : ui.getShells()) if (shell != parent) shell.dispose();
          }
        };
    try {
      ui.timerExec(25, check[0]);
      var repository = new CacheRepository(directory, 1_000_000, Duration.ofDays(7));
      var choice =
          CacheDialog.open(
              parent,
              repository,
              new String[] {"Reader"},
              "Reader",
              new PipelineMeta(),
              new Variables());
      assertThat(failure.get()).isNull();
      assertThat(choice).isNotNull();
      assertThat(choice.action()).isEqualTo("Record");
    } finally {
      ui.timerExec(-1, check[0]);
      parent.dispose();
      if (owns) display.dispose();
    }
  }

  @Test
  void completeCaptureShortcutPreselectsDiskMode() throws Exception {
    Display display = Display.getCurrent();
    boolean owns = display == null;
    if (owns) display = new Display();
    Shell parent = new Shell(display);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Display ui = display;
    Runnable[] check = new Runnable[1];
    check[0] =
        () -> {
          try {
            Shell dialog =
                Arrays.stream(ui.getShells())
                    .filter(s -> s.getText().equals("Inspect data..."))
                    .findFirst()
                    .orElse(null);
            if (dialog == null) {
              ui.timerExec(25, check[0]);
              return;
            }
            List<Control> controls = descendants(dialog);
            Combo captureMode =
                controls.stream()
                    .filter(c -> c instanceof Combo combo && hasItem(combo, "Record complete disk cache"))
                    .map(c -> (Combo) c)
                    .findFirst()
                    .orElseThrow();
            assertThat(captureMode.getText()).isEqualTo("Record complete disk cache");
            Combo sampleSize =
                controls.stream()
                    .filter(c -> c instanceof Combo combo && hasItem(combo, "1000"))
                    .map(c -> (Combo) c)
                    .findFirst()
                    .orElseThrow();
            assertThat(sampleSize.getEnabled()).isFalse();
            button(controls, "Cancel").notifyListeners(SWT.Selection, new Event());
          } catch (Throwable error) {
            failure.set(error);
            for (Shell shell : ui.getShells()) if (shell != parent) shell.dispose();
          }
        };
    try {
      ui.timerExec(25, check[0]);
      var settings =
          new ch.so.agi.hop.geometry.inspector.BackgroundMapTestSettings(
              GeometryInspectorBackgroundMapConfig.empty());
      var result =
          new GeometryInspectorOptionsDialog(parent, List.of(), List.of(), settings).open(true);
      assertThat(failure.get()).isNull();
      assertThat(result).isNull();
    } finally {
      ui.timerExec(-1, check[0]);
      parent.dispose();
      if (owns) display.dispose();
    }
  }

  private static Button button(List<Control> controls, String text) {
    return controls.stream()
        .filter(c -> c instanceof Button b && b.getText().equals(text))
        .map(c -> (Button) c)
        .findFirst()
        .orElseThrow();
  }

  private static boolean hasItem(Combo combo, String item) {
    return Arrays.asList(combo.getItems()).contains(item);
  }

  private static List<Control> descendants(Composite parent) {
    List<Control> result = new ArrayList<>();
    for (Control child : parent.getChildren()) {
      result.add(child);
      if (child instanceof Composite composite) result.addAll(descendants(composite));
    }
    return result;
  }
}
