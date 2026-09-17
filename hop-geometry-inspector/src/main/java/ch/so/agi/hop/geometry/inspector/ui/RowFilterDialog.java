package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.data.RowQuery;
import java.util.*;
import java.util.List;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

final class RowFilterDialog {
  static List<RowQuery.Condition> open(
      Shell parent, IRowMeta meta, List<RowQuery.Condition> previous) {
    if (meta == null || meta.size() == 0) return previous;
    Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
    dialog.setText("Filter captured rows (AND)");
    dialog.setLayout(new GridLayout(3, false));
    Combo field = new Combo(dialog, SWT.READ_ONLY);
    field.setItems(meta.getFieldNames());
    field.select(0);
    Combo operator = new Combo(dialog, SWT.READ_ONLY);
    operator.setItems(
        Arrays.stream(RowQuery.Operator.values()).map(Enum::name).toArray(String[]::new));
    operator.select(0);
    Runnable updateOperators =
        () -> {
          var type = meta.getValueMeta(field.getSelectionIndex());
          var operators =
              new ArrayList<RowQuery.Operator>(
                  List.of(
                      RowQuery.Operator.EQ,
                      RowQuery.Operator.NE,
                      RowQuery.Operator.IS_NULL,
                      RowQuery.Operator.NOT_NULL));
          if (type.isString()) operators.add(RowQuery.Operator.CONTAINS);
          if (type.isNumeric() || type.isDate())
            operators.addAll(
                List.of(
                    RowQuery.Operator.LT,
                    RowQuery.Operator.LE,
                    RowQuery.Operator.GT,
                    RowQuery.Operator.GE));
          operator.setItems(operators.stream().map(Enum::name).toArray(String[]::new));
          operator.select(0);
        };
    field.addListener(SWT.Selection, event -> updateOperators.run());
    updateOperators.run();
    Text value = new Text(dialog, SWT.BORDER);
    value.setLayoutData(new GridData(220, SWT.DEFAULT));
    org.eclipse.swt.widgets.List list =
        new org.eclipse.swt.widgets.List(dialog, SWT.BORDER | SWT.V_SCROLL);
    GridData listData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1);
    listData.heightHint = 150;
    list.setLayoutData(listData);
    List<RowQuery.Condition> conditions = new ArrayList<>(previous);
    Runnable refresh =
        () ->
            list.setItems(
                conditions.stream()
                    .map(
                        c ->
                            meta.getValueMeta(c.field()).getName()
                                + " "
                                + c.operator()
                                + " "
                                + Objects.toString(c.value(), ""))
                    .toArray(String[]::new));
    refresh.run();
    Button add = new Button(dialog, SWT.PUSH);
    add.setText("Add condition");
    add.addListener(
        SWT.Selection,
        e -> {
          try {
            var op = RowQuery.Operator.valueOf(operator.getText());
            int index = field.getSelectionIndex();
            Object typed =
                op == RowQuery.Operator.IS_NULL || op == RowQuery.Operator.NOT_NULL
                    ? null
                    : op == RowQuery.Operator.CONTAINS
                        ? value.getText()
                        : meta.getValueMeta(index)
                            .convertData(new ValueMetaString("value"), value.getText());
            conditions.add(new RowQuery.Condition(index, op, typed));
            refresh.run();
          } catch (Exception ex) {
            MessageBox box = new MessageBox(dialog, SWT.ICON_WARNING);
            box.setMessage("Invalid value for selected field: " + ex.getMessage());
            box.open();
          }
        });
    Button remove = new Button(dialog, SWT.PUSH);
    remove.setText("Remove selected");
    remove.addListener(
        SWT.Selection,
        e -> {
          int i = list.getSelectionIndex();
          if (i >= 0) {
            conditions.remove(i);
            refresh.run();
          }
        });
    Button clear = new Button(dialog, SWT.PUSH);
    clear.setText("Clear");
    clear.addListener(
        SWT.Selection,
        e -> {
          conditions.clear();
          refresh.run();
        });
    boolean[] accepted = {false};
    Button ok = new Button(dialog, SWT.PUSH);
    ok.setText("Apply");
    ok.addListener(
        SWT.Selection,
        e -> {
          accepted[0] = true;
          dialog.dispose();
        });
    Button cancel = new Button(dialog, SWT.PUSH);
    cancel.setText("Cancel");
    cancel.addListener(SWT.Selection, e -> dialog.dispose());
    dialog.setDefaultButton(ok);
    dialog.pack();
    dialog.open();
    while (!dialog.isDisposed()) {
      if (!parent.getDisplay().readAndDispatch()) parent.getDisplay().sleep();
    }
    return accepted[0] ? List.copyOf(conditions) : previous;
  }
}
