package ch.so.agi.hop.geometry.inspector.data;

import java.util.List;
import org.apache.hop.core.exception.*;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.transform.*;

/** Internal, in-memory-only replacement; never serialized into the user's pipeline. */
public final class ReplaySourceMeta
    extends BaseTransformMeta<ReplaySourceMeta.Source, ReplaySourceMeta.Data> {
  final List<InspectionResult> inputs;

  public ReplaySourceMeta(List<InspectionResult> inputs) {
    this.inputs = List.copyOf(inputs);
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
    if (!inputs.isEmpty() && inputs.getFirst().rows().rowMeta() != null)
      row.addRowMeta(inputs.getFirst().rows().rowMeta().clone());
  }

  public static final class Data extends BaseTransformData {}

  public static final class Source extends BaseTransform<ReplaySourceMeta, Data> {
    private long ordinal;
    private int input;

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
      while (input < meta.inputs.size()) {
        var result = meta.inputs.get(input);
        if (ordinal >= result.rows().size()) {
          input++;
          ordinal = 0;
          continue;
        }
        try {
          putRow(result.rows().rowMeta(), result.rows().row(ordinal++));
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
