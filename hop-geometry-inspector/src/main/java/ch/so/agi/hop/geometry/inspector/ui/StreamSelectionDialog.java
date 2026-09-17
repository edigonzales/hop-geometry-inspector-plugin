package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.data.StreamDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

public final class StreamSelectionDialog {
  public static List<StreamDescriptor> open(
      Shell parent,
      List<StreamDescriptor> streams,
      String selected,
      ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide side) {
    Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    shell.setText("Choose streams to capture");
    shell.setLayout(new GridLayout(2, false));
    Label info = new Label(shell, SWT.WRAP);
    info.setText(
        "Select streams from this pipeline. Each stream is kept separately.\n"
            + "Main captures putRow; target captures explicit routing to that destination.");
    info.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    Table table = new Table(shell, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    data.widthHint = 680;
    data.heightHint = 320;
    table.setLayoutData(data);
    for (var stream : streams) {
      TableItem item = new TableItem(table, SWT.NONE);
      item.setText(stream.label());
      item.setChecked(
          stream.transform().equals(selected)
              && stream.direction()
                  == (side == ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide.INPUT
                      ? StreamDescriptor.Direction.INPUT
                      : StreamDescriptor.Direction.OUTPUT)
              && stream.kind() == StreamDescriptor.Kind.MAIN);
    }
    List<StreamDescriptor> result = new ArrayList<>();
    Button ok = new Button(shell, SWT.PUSH);
    ok.setText("Capture selected streams");
    ok.addListener(
        SWT.Selection,
        e -> {
          for (int i = 0; i < table.getItemCount(); i++)
            if (table.getItem(i).getChecked()) result.add(streams.get(i));
          shell.dispose();
        });
    Button cancel = new Button(shell, SWT.PUSH);
    cancel.setText("Cancel");
    cancel.addListener(SWT.Selection, e -> shell.dispose());
    shell.pack();
    shell.open();
    while (!shell.isDisposed()) {
      if (!parent.getDisplay().readAndDispatch()) parent.getDisplay().sleep();
    }
    return result;
  }
}
