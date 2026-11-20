package ch.so.agi.hop.geometry.inspector.data;

import org.apache.hop.core.exception.*;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.transform.*;

/** Internal, in-memory-only replacement; never serialized into the user's pipeline. */
public final class ReplaySourceMeta
    extends BaseTransformMeta<ReplaySourceMeta.Source, ReplaySourceMeta.Data> {
  private final RowStore rows;

  public ReplaySourceMeta(InspectionResult input) {
    rows = input.rows();
  }

  @Override
  public ITransformIOMeta getTransformIOMeta() {
    return new TransformIOMeta(false, true, false, false, false, false);
  }

  @Override
  public void getFields(
      IRowMeta row,
      String name,
      IRowMeta[] info,
      TransformMeta next,
      IVariables variables,
      IHopMetadataProvider provider) {
    row.clear();
    if (rows.rowMeta() != null) row.addRowMeta(rows.rowMeta().clone());
  }

  public static final class Data extends BaseTransformData {}

  public static final class Source extends BaseTransform<ReplaySourceMeta, Data> {
    private long ordinal;

    public Source(
        TransformMeta transform,
        ReplaySourceMeta meta,
        Data data,
        int copy,
        PipelineMeta pipelineMeta,
        Pipeline pipeline) {
      super(transform, meta, data, copy, pipelineMeta, pipeline);
    }

    @Override
    public boolean processRow() throws HopException {
      if (ordinal < meta.rows.size()) {
        try {
          putRow(meta.rows.rowMeta(), meta.rows.row(ordinal++));
          return true;
        } catch (Exception e) {
          throw new HopException("Unable to replay cached row", e);
        }
      }
      setOutputDone();
      return false;
    }
  }
}
