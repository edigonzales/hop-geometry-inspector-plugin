package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.data.*;
import java.util.List;
import java.util.UUID;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

public final class CacheDialog {
  public record Choice(List<UUID> caches, String action, String end) {}

  public static Choice open(
      Shell parent,
      CacheRepository repository,
      String[] transforms,
      String start,
      org.apache.hop.pipeline.PipelineMeta pipeline,
      org.apache.hop.core.variables.IVariables variables)
      throws Exception {
    Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    shell.setText("Data Inspector caches / replay from " + start);
    shell.setLayout(new GridLayout(6, false));
    Label info = new Label(shell, SWT.WRAP);
    info.setText(
        "Select caches to open or supply as replay inputs. Replay does not refresh external"
            + " sources.\n"
            + "For Run Between, the context transform is the start; choose the end below.");
    info.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 6, 1));
    Table table = new Table(shell, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    for (String label :
        List.of("Pipeline / stream", "Captured", "State", "Rows", "Bytes", "Freshness")) {
      TableColumn c = new TableColumn(table, SWT.NONE);
      c.setText(label);
      c.setWidth(label.startsWith("Pipeline") ? 300 : 160);
    }
    GridData grid = new GridData(SWT.FILL, SWT.FILL, true, true, 6, 1);
    grid.heightHint = 320;
    table.setLayoutData(grid);
    var entries = repository.entries();
    for (var entry : entries) {
      var p = entry.manifest();
      TableItem item = new TableItem(table, SWT.NONE);
      item.setData(entry.id());
      item.setText(
          new String[] {
            p.getProperty("pipeline", "")
                + " / "
                + p.getProperty("transform")
                + " / "
                + p.getProperty("kind")
                + " / "
                + p.getProperty("peer"),
            p.getProperty("created"),
            p.getProperty("state"),
            p.getProperty("rows", "?"),
            Long.toString(entry.bytes()),
            "Checking..."
          });
    }
    var worker =
        ch.so.agi.hop.geometry.inspector.GeometryInspectorClassLoaderSupport
            .newPluginContextThreadFactory()
            .newThread(
                () -> {
                  java.util.Map<String, String> fingerprints = new java.util.HashMap<>();
                  for (var entry : entries) {
                    String status;
                    try {
                      String name = entry.manifest().getProperty("transform");
                      if (pipeline.findTransform(name) == null) status = "Not in this pipeline";
                      else {
                        String fingerprint = fingerprints.get(name);
                        if (fingerprint == null) {
                          fingerprint =
                              CacheFingerprint.of(
                                  pipeline, name, variables, pipeline.getMetadataProvider());
                          fingerprints.put(name, fingerprint);
                        }
                        status =
                            fingerprint.equals(entry.manifest().getProperty("fingerprint"))
                                ? "Configuration matches"
                                : "Stale configuration";
                      }
                    } catch (Exception error) {
                      status = "Unable to verify";
                    }
                    String label = status;
                    parent
                        .getDisplay()
                        .asyncExec(
                            () -> {
                              if (!table.isDisposed())
                                for (var item : table.getItems())
                                  if (entry.id().equals(item.getData())) item.setText(5, label);
                            });
                  }
                });
    worker.setDaemon(true);
    worker.start();
    Combo end = new Combo(shell, SWT.READ_ONLY);
    end.setItems(transforms);
    end.setText(start);
    end.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 6, 1));
    Choice[] result = {null};
    for (String action : List.of("Open", "Delete", "Record again", "TO", "FROM", "BETWEEN")) {
      Button button = new Button(shell, SWT.PUSH);
      button.setText(action.length() > 4 ? action : "Run " + action);
      if (action.equals("Open") || action.equals("Delete")) button.setText(action);
      button.addListener(
          SWT.Selection,
          e -> {
            var ids =
                java.util.Arrays.stream(table.getItems())
                    .filter(TableItem::getChecked)
                    .map(i -> (UUID) i.getData())
                    .toList();
            if (action.equals("Delete")) {
              for (var id : ids)
                try {
                  repository.delete(id);
                  for (var item : table.getItems()) if (id.equals(item.getData())) item.dispose();
                } catch (Exception ex) {
                  MessageBox box = new MessageBox(shell, SWT.ICON_WARNING);
                  box.setMessage(ex.getMessage());
                  box.open();
                }
              return;
            }
            result[0] = new Choice(ids, action, end.getText());
            shell.dispose();
          });
    }
    shell.pack();
    shell.open();
    while (!shell.isDisposed()) {
      if (!parent.getDisplay().readAndDispatch()) parent.getDisplay().sleep();
    }
    return result[0];
  }
}
