package ch.so.agi.hop.geometry.inspector.sampling;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.PreviewPipelinePruner;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions;
import ch.so.agi.hop.geometry.inspector.model.SamplingMode;
import ch.so.agi.hop.geometry.inspector.model.SamplingResult;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.IRowSet;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.pipeline.Pipeline;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.BaseTransform;
import org.apache.hop.pipeline.transform.BaseTransformData;
import org.apache.hop.pipeline.transform.BaseTransformMeta;
import org.apache.hop.pipeline.transform.ITransformIOMeta;
import org.apache.hop.pipeline.transform.TransformIOMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LocalGeometryPipelineSamplerExecutorTest {

  @BeforeAll
  static void initHopEnvironment() throws Exception {
    if (!HopEnvironment.isInitialized()) {
      HopEnvironment.init();
    }
  }

  @Test
  void samplesTargetedMainOutputWhenManualOutputIsSelected() throws Exception {
    SamplingResult result = sample(GeometryInspectionSide.OUTPUT);

    assertThat(result.effectiveSide()).isEqualTo(GeometryInspectionSide.OUTPUT);
    assertThat(result.autoSwitched()).isFalse();
    assertThat(result.rows()).extracting(row -> row[0]).containsExactly("POINT (1 1)");
  }

  @Test
  void keepsInputSamplingUnchangedForManualInputSelection() throws Exception {
    SamplingResult result = sample(GeometryInspectionSide.INPUT);

    assertThat(result.effectiveSide()).isEqualTo(GeometryInspectionSide.INPUT);
    assertThat(result.autoSwitched()).isFalse();
    assertThat(result.rows()).extracting(row -> row[0]).containsExactly("POINT (0 0)");
  }

  @Test
  void autoModePrefersMainOutputAndIgnoresRejectRows() throws Exception {
    SamplingResult result = sample(GeometryInspectionSide.AUTO);

    assertThat(result.effectiveSide()).isEqualTo(GeometryInspectionSide.OUTPUT);
    assertThat(result.autoSwitched()).isFalse();
    assertThat(result.rows()).extracting(row -> row[0]).containsExactly("POINT (1 1)");
  }

  private SamplingResult sample(GeometryInspectionSide side) throws Exception {
    GeometrySamplerService service =
        new GeometrySamplerService(
            new PreviewPipelinePruner(), new LocalGeometryPipelineSamplerExecutor());

    return service.sample(
        createPipeline(),
        new Variables(),
        null,
        "Target",
        new GeometryInspectorOptions(1, SamplingMode.FIRST, side, "geometry", Duration.ofSeconds(5)));
  }

  private PipelineMeta createPipeline() {
    PipelineMeta pipelineMeta = new PipelineMeta();
    TransformMeta source = new TransformMeta("Source", new TestSourceMeta());
    TransformMeta target = new TransformMeta("Target", new RoutedTargetMeta("RejectSink"));
    TransformMeta mainSink = new TransformMeta("MainSink", null);
    TransformMeta rejectSink = new TransformMeta("RejectSink", null);

    pipelineMeta.addTransform(source);
    pipelineMeta.addTransform(target);
    pipelineMeta.addTransform(mainSink);
    pipelineMeta.addTransform(rejectSink);

    pipelineMeta.addPipelineHop(new PipelineHopMeta(source, target));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(target, mainSink));
    pipelineMeta.addPipelineHop(new PipelineHopMeta(target, rejectSink));

    return pipelineMeta;
  }

  public static class TestSourceMeta extends BaseTransformMeta<TestSourceTransform, TestTransformData> {

    @Override
    public ITransformIOMeta getTransformIOMeta() {
      ITransformIOMeta ioMeta = super.getTransformIOMeta(false);
      if (ioMeta == null) {
        ioMeta = new TransformIOMeta(false, true, false, false, false, false);
        setTransformIOMeta(ioMeta);
      }
      return ioMeta;
    }
  }

  public static class RoutedTargetMeta extends BaseTransformMeta<RoutedTargetTransform, TestTransformData> {

    private String rejectTransformName;

    public RoutedTargetMeta() {
      this("");
    }

    public RoutedTargetMeta(String rejectTransformName) {
      this.rejectTransformName = rejectTransformName;
    }

    boolean isRejectRowSet(IRowSet rowSet) {
      return rejectTransformName != null
          && !rejectTransformName.isBlank()
          && rejectTransformName.equalsIgnoreCase(rowSet.getDestinationTransformName());
    }
  }

  public static class TestTransformData extends BaseTransformData {
    boolean emitted;
    boolean initialized;
    IRowMeta outputRowMeta;
    List<IRowSet> mainOutputRowSets = List.of();
    List<IRowSet> rejectOutputRowSets = List.of();
  }

  public static class TestSourceTransform extends BaseTransform<TestSourceMeta, TestTransformData> {

    public TestSourceTransform(
        TransformMeta transformMeta,
        TestSourceMeta meta,
        TestTransformData data,
        int copyNr,
        PipelineMeta pipelineMeta,
        Pipeline pipeline) {
      super(transformMeta, meta, data, copyNr, pipelineMeta, pipeline);
    }

    @Override
    public boolean processRow() throws HopException {
      if (data.emitted) {
        setOutputDone();
        return false;
      }

      if (data.outputRowMeta == null) {
        RowMeta rowMeta = new RowMeta();
        rowMeta.addValueMeta(new ValueMetaString("geometry"));
        data.outputRowMeta = rowMeta;
      }

      putRow(data.outputRowMeta, new Object[] {"POINT (0 0)"});
      data.emitted = true;
      return true;
    }
  }

  public static class RoutedTargetTransform
      extends BaseTransform<RoutedTargetMeta, TestTransformData> {

    public RoutedTargetTransform(
        TransformMeta transformMeta,
        RoutedTargetMeta meta,
        TestTransformData data,
        int copyNr,
        PipelineMeta pipelineMeta,
        Pipeline pipeline) {
      super(transformMeta, meta, data, copyNr, pipelineMeta, pipeline);
    }

    @Override
    public boolean processRow() throws HopException {
      Object[] row = getRow();
      if (row == null) {
        setOutputDone();
        return false;
      }

      if (!data.initialized) {
        data.outputRowMeta = (IRowMeta) getInputRowMeta().clone();
        data.mainOutputRowSets =
            getOutputRowSets().stream()
                .filter(rowSet -> !meta.isRejectRowSet(rowSet))
                .collect(Collectors.toList());
        data.rejectOutputRowSets =
            getOutputRowSets().stream()
                .filter(meta::isRejectRowSet)
                .collect(Collectors.toList());
        data.initialized = true;
      }

      Object[] mainRow = data.outputRowMeta.cloneRow(row);
      mainRow[0] = "POINT (1 1)";
      for (int index = 0; index < data.mainOutputRowSets.size(); index++) {
        putRowTo(
            data.outputRowMeta,
            index < data.mainOutputRowSets.size() - 1 ? data.outputRowMeta.cloneRow(mainRow) : mainRow,
            data.mainOutputRowSets.get(index));
      }

      Object[] rejectRow = data.outputRowMeta.cloneRow(row);
      rejectRow[0] = "POINT (2 2)";
      for (int index = 0; index < data.rejectOutputRowSets.size(); index++) {
        putRowTo(
            data.outputRowMeta,
            index < data.rejectOutputRowSets.size() - 1 ? data.outputRowMeta.cloneRow(rejectRow) : rejectRow,
            data.rejectOutputRowSets.get(index));
      }

      return true;
    }
  }
}
