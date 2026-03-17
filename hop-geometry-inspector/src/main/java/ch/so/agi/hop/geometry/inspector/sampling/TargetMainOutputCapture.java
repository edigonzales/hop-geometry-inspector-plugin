package ch.so.agi.hop.geometry.inspector.sampling;

import ch.so.agi.hop.geometry.inspector.model.SamplingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hop.core.IRowSet;
import org.apache.hop.core.exception.HopValueException;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.pipeline.engine.IEngineComponent;
import org.apache.hop.pipeline.transform.ITransform;

final class TargetMainOutputCapture {

  private static final String DESTINATION_PREFIX = "__geometry_inspector_main_output__";

  private final GeometrySampleCollector collector;
  private final List<IRowSet> rowSets;

  TargetMainOutputCapture(int sampleSize, SamplingMode mode) {
    this.collector = new GeometrySampleCollector(sampleSize, mode);
    this.rowSets = new ArrayList<>();
  }

  void attach(List<IEngineComponent> components, String targetTransformName) {
    for (IEngineComponent component : components) {
      if (component instanceof ITransform transform) {
        transform.addRowSetToOutputRowSets(createRowSet(targetTransformName, transform.getCopy()));
      }
    }
  }

  IRowSet createRowSet(String targetTransformName, int transformCopy) {
    SamplingRowSet rowSet = new SamplingRowSet(collector);
    rowSet.setThreadNameFromToCopy(
        targetTransformName,
        transformCopy,
        syntheticDestinationName(targetTransformName),
        transformCopy);
    rowSets.add(rowSet);
    return rowSet;
  }

  boolean isFull() {
    return collector.isFull();
  }

  List<Object[]> snapshotRows() {
    return collector.snapshotRows();
  }

  IRowMeta snapshotRowMeta() {
    return collector.snapshotRowMeta();
  }

  int rowSetCount() {
    return rowSets.size();
  }

  static String syntheticDestinationName(String targetTransformName) {
    return DESTINATION_PREFIX + targetTransformName;
  }

  private static final class SamplingRowSet implements IRowSet {

    private final GeometrySampleCollector collector;
    private final AtomicBoolean done;

    private volatile IRowMeta rowMeta;
    private volatile String originTransformName;
    private volatile int originTransformCopy;
    private volatile String destinationTransformName;
    private volatile int destinationTransformCopy;
    private volatile String remoteHopServerName;

    private SamplingRowSet(GeometrySampleCollector collector) {
      this.collector = collector;
      this.done = new AtomicBoolean(false);
    }

    @Override
    public boolean putRow(IRowMeta rowMeta, Object[] rowData) {
      return accept(rowMeta, rowData);
    }

    @Override
    public boolean putRowWait(IRowMeta rowMeta, Object[] rowData, long time, TimeUnit tu) {
      return accept(rowMeta, rowData);
    }

    @Override
    public Object[] getRow() {
      return null;
    }

    @Override
    public Object[] getRowImmediate() {
      return null;
    }

    @Override
    public Object[] getRowWait(long timeout, TimeUnit tu) {
      return null;
    }

    @Override
    public void setDone() {
      done.set(true);
    }

    @Override
    public boolean isDone() {
      return done.get();
    }

    @Override
    public String getOriginTransformName() {
      return originTransformName;
    }

    @Override
    public int getOriginTransformCopy() {
      return originTransformCopy;
    }

    @Override
    public String getDestinationTransformName() {
      return destinationTransformName;
    }

    @Override
    public int getDestinationTransformCopy() {
      return destinationTransformCopy;
    }

    @Override
    public String getName() {
      return originTransformName + "." + originTransformCopy + " - " + destinationTransformName + "." + destinationTransformCopy;
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public void setThreadNameFromToCopy(String from, int fromCopy, String to, int toCopy) {
      originTransformName = from;
      originTransformCopy = fromCopy;
      destinationTransformName = to;
      destinationTransformCopy = toCopy;
    }

    @Override
    public IRowMeta getRowMeta() {
      return rowMeta;
    }

    @Override
    public void setRowMeta(IRowMeta rowMeta) {
      this.rowMeta = rowMeta;
    }

    @Override
    public String getRemoteHopServerName() {
      return remoteHopServerName;
    }

    @Override
    public void setRemoteHopServerName(String remoteHopServerName) {
      this.remoteHopServerName = remoteHopServerName;
    }

    @Override
    public boolean isBlocking() {
      return false;
    }

    @Override
    public void clear() {
      done.set(false);
      rowMeta = null;
    }

    private boolean accept(IRowMeta incomingRowMeta, Object[] rowData) {
      rowMeta = incomingRowMeta;
      try {
        collector.accept(incomingRowMeta, rowData);
        return true;
      } catch (HopValueException e) {
        throw new IllegalStateException("Failed to capture a sampled output row", e);
      }
    }
  }
}
