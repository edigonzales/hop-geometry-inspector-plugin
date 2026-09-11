package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in desktop smoke test: requires an interactive display (macOS: -XstartOnFirstThread). */
@EnabledIfSystemProperty(named = "geometryInspector.swtSmoke", matches = "true")
class BackgroundMapDialogSmokeTest {
  @Test
  void loadsCapabilitiesThenCancelDoesNotSave() throws Exception {
    WmtsBackgroundMapClientTest fixture = new WmtsBackgroundMapClientTest();
    fixture.start();
    Display display = Display.getCurrent();
    boolean owns = display == null;
    if (owns) display = new Display();
    Shell parent = new Shell(display);
    var initial = fixture.config();
    var service = new ch.so.agi.hop.geometry.inspector.BackgroundMapTestSettings(initial);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Display ui = display;
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    Runnable check =
        new Runnable() {
          @Override
          public void run() {
            try {
              Shell dialog =
                  Arrays.stream(ui.getShells())
                      .filter(s -> s.getText().equals("Background map settings"))
                      .findFirst()
                      .orElse(null);
              if (dialog == null) {
                ui.timerExec(25, this);
                return;
              }
              var controls = descendants(dialog);
              Button save =
                  controls.stream()
                      .filter(c -> c instanceof Button b && b.getText().equals("Save"))
                      .map(c -> (Button) c)
                      .findFirst()
                      .orElseThrow();
              if (!save.isEnabled()) {
                if (System.nanoTime() < deadline) {
                  ui.timerExec(25, this);
                  return;
                }
                throw new AssertionError("Capabilities did not load before deadline");
              }
              assertThat(
                      controls.stream()
                          .filter(c -> c instanceof Combo)
                          .map(c -> ((Combo) c).getText())
                          .toList())
                  .contains(
                      "WMTS", "base", "default", "image/png", "Automatic (match geometry CRS)");
              Text dimension =
                  controls.stream()
                      .filter(c -> c instanceof Text t && t.getText().equals("current"))
                      .map(c -> (Text) c)
                      .findFirst()
                      .orElseThrow();
              assertThat(dimension.getEditable()).isFalse();
              assertThat(dimension.getBounds().height).isPositive();
              Image screenshot = new Image(ui, dialog.getSize().x, dialog.getSize().y);
              GC gc = new GC(dialog);
              try {
                gc.copyArea(screenshot, 0, 0);
                ImageLoader loader = new ImageLoader();
                loader.data = new ImageData[] {screenshot.getImageData()};
                loader.save("target/wmts-dialog-smoke.png", SWT.IMAGE_PNG);
              } finally {
                gc.dispose();
                screenshot.dispose();
              }
              Button cancel =
                  controls.stream()
                      .filter(c -> c instanceof Button b && b.getText().equals("Cancel"))
                      .map(c -> (Button) c)
                      .findFirst()
                      .orElseThrow();
              cancel.notifyListeners(SWT.Selection, new Event());
            } catch (Throwable error) {
              failure.set(error);
              for (Shell shell : ui.getShells()) if (shell != parent) shell.dispose();
            }
          }
        };
    try {
      display.timerExec(25, check);
      assertThat(new GeometryInspectorBackgroundMapSettingsDialog(parent, service).open()).isNull();
      assertThat(failure.get()).isNull();
      assertThat(service.saved).isNull();
    } finally {
      display.timerExec(-1, check);
      parent.dispose();
      if (owns) display.dispose();
      fixture.stop();
    }
  }

  private static java.util.List<Control> descendants(Composite parent) {
    java.util.List<Control> result = new ArrayList<>();
    for (Control child : parent.getChildren()) {
      result.add(child);
      if (child instanceof Composite composite) result.addAll(descendants(composite));
    }
    return result;
  }
}
