package ch.so.agi.hop.geometry.inspector.sampling;

import ch.so.agi.hop.geometry.inspector.model.SamplingMode;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.apache.hop.core.exception.HopValueException;
import org.apache.hop.core.row.IRowMeta;

public class GeometrySampleCollector {

  private final int maxRows;
  private final SamplingMode mode;
  private final Random random;

  private final List<Object[]> rows;
  private int seenRows;
  private IRowMeta rowMeta;

  public GeometrySampleCollector(int maxRows, SamplingMode mode) {
    this(maxRows, mode, new Random());
  }

  public GeometrySampleCollector(int maxRows, SamplingMode mode, Random random) {
    this.maxRows = maxRows;
    this.mode = mode;
    this.random = random;
    this.rows = mode == SamplingMode.LAST ? new LinkedList<>() : new ArrayList<>();
  }

  public synchronized void accept(IRowMeta incomingRowMeta, Object[] row) throws HopValueException {
    if (incomingRowMeta == null || row == null || maxRows <= 0) {
      return;
    }

    if (rowMeta == null) {
      rowMeta = incomingRowMeta.clone();
    }

    switch (mode) {
      case FIRST -> collectFirst(rowMeta, row);
      case LAST -> collectLast(rowMeta, row);
      case RANDOM -> collectRandom(rowMeta, row);
      default -> throw new IllegalStateException("Unexpected mode: " + mode);
    }
  }

  public synchronized boolean isFull() {
    return rows.size() >= maxRows;
  }

  public synchronized List<Object[]> snapshotRows() {
    return new ArrayList<>(rows);
  }

  public synchronized IRowMeta snapshotRowMeta() {
    return rowMeta == null ? null : rowMeta.clone();
  }

  private void collectFirst(IRowMeta incomingRowMeta, Object[] row) throws HopValueException {
    if (rows.size() >= maxRows) {
      return;
    }
    rows.add(incomingRowMeta.cloneRow(row));
  }

  private void collectLast(IRowMeta incomingRowMeta, Object[] row) throws HopValueException {
    rows.add(0, incomingRowMeta.cloneRow(row));
    if (rows.size() > maxRows) {
      rows.remove(rows.size() - 1);
    }
  }

  private void collectRandom(IRowMeta incomingRowMeta, Object[] row) throws HopValueException {
    seenRows++;
    if (rows.size() < maxRows) {
      rows.add(incomingRowMeta.cloneRow(row));
    } else {
      int randomIndex = random.nextInt(seenRows);
      if (randomIndex < maxRows) {
        rows.set(randomIndex, incomingRowMeta.cloneRow(row));
      }
    }
  }
}
