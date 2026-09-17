package ch.so.agi.hop.geometry.inspector.data;

import ch.so.agi.hop.geometry.inspector.model.*;
import ch.so.agi.hop.geometry.inspector.sampling.GeometrySampleCollector;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.apache.hop.core.IRowSet;
import org.apache.hop.core.exception.*;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.xml.XmlHandler;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.engines.local.LocalPipelineEngine;
import org.apache.hop.pipeline.transform.*;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;

/** Explicit, isolated capture. Row handlers preserve the engine's original routing. */
public final class CaptureService {
  public static List<StreamDescriptor> streams(PipelineMeta pipeline, IVariables variables) {
    List<StreamDescriptor> result = new ArrayList<>();
    for (TransformMeta transform : pipeline.getTransforms())
      for (int copy = 0; copy < transform.getCopies(variables); copy++) {
        result.add(
            new StreamDescriptor(
                transform.getName(),
                StreamDescriptor.Direction.OUTPUT,
                "",
                StreamDescriptor.Kind.MAIN,
                copy));
        for (PipelineHopMeta hop : pipeline.getPipelineHops())
          if (hop.isEnabled()) {
            if (hop.getToTransform() == transform)
              result.add(
                  new StreamDescriptor(
                      transform.getName(),
                      StreamDescriptor.Direction.INPUT,
                      hop.getFromTransform().getName(),
                      StreamDescriptor.Kind.MAIN,
                      copy));
            if (hop.getFromTransform() == transform
                && (transform.isSendingErrorRowsToTransform(hop.getToTransform())
                    || transform.getTransform().getTransformIOMeta().getTargetStreams().stream()
                        .anyMatch(target -> target.getTransformMeta() == hop.getToTransform())))
              result.add(
                  new StreamDescriptor(
                      transform.getName(),
                      StreamDescriptor.Direction.OUTPUT,
                      hop.getToTransform().getName(),
                      transform.isSendingErrorRowsToTransform(hop.getToTransform())
                          ? StreamDescriptor.Kind.ERROR
                          : StreamDescriptor.Kind.TARGET,
                      copy));
          }
      }
    return result;
  }

  public static PipelineMeta copy(
      PipelineMeta source, IVariables variables, IHopMetadataProvider provider) throws Exception {
    PipelineMeta clone = new PipelineMeta();
    clone.loadXml(
        XmlHandler.loadXmlString(source.getXml(variables), PipelineMeta.XML_TAG),
        source.getFilename(),
        provider,
        variables);
    return clone;
  }

  public static Set<String> ancestors(PipelineMeta pipeline, Collection<String> targets) {
    Set<String> names = new LinkedHashSet<>(targets);
    boolean changed;
    do {
      changed = false;
      for (var hop : pipeline.getPipelineHops())
        if (hop.isEnabled() && names.contains(hop.getToTransform().getName()))
          changed |= names.add(hop.getFromTransform().getName());
    } while (changed);
    return names;
  }

  public static PipelineMeta prune(
      PipelineMeta source,
      IVariables variables,
      IHopMetadataProvider provider,
      Collection<String> targets)
      throws Exception {
    PipelineMeta clone = copy(source, variables, provider);
    Set<String> keep = ancestors(clone, targets);
    Set<String> sinks = new HashSet<>();
    for (var hop : clone.getPipelineHops())
      if (hop.isEnabled()
          && keep.contains(hop.getFromTransform().getName())
          && !keep.contains(hop.getToTransform().getName()))
        sinks.add(hop.getToTransform().getName());
    for (var transform : new ArrayList<>(clone.getTransforms())) {
      if (sinks.contains(transform.getName())) {
        transform.setTransform(new DummyMeta());
        transform.setTransformPluginId("Dummy");
        transform.setTransformErrorMeta(null);
      } else if (!keep.contains(transform.getName()))
        clone.removeTransform(clone.getTransforms().indexOf(transform));
    }
    for (int i = clone.getPipelineHops().size() - 1; i >= 0; i--) {
      var hop = clone.getPipelineHops().get(i);
      if (!keep.contains(hop.getFromTransform().getName())
          || !keep.contains(hop.getToTransform().getName())
              && !sinks.contains(hop.getToTransform().getName())) clone.removePipelineHop(i);
    }
    return clone;
  }

  public List<InspectionResult> capture(
      PipelineMeta source,
      IVariables variables,
      IHopMetadataProvider provider,
      List<StreamDescriptor> selected,
      GeometryInspectorOptions options,
      CacheRepository repository,
      BooleanSupplier cancelled)
      throws Exception {
    if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one stream");
    if (source.getPipelineType() != PipelineMeta.PipelineType.Normal)
      throw new IllegalArgumentException("Capture supports normal local pipelines only");
    for (String name :
        ancestors(source, selected.stream().map(StreamDescriptor::transform).toList()))
      if (source.findTransform(name).isPartitioned())
        throw new IllegalArgumentException("Partitioned capture is unsupported: " + name);
    PipelineMeta clone =
        prune(
            source,
            variables,
            provider,
            selected.stream().map(StreamDescriptor::transform).toList());
    return execute(source, clone, variables, provider, selected, options, repository, cancelled);
  }

  public List<InspectionResult> execute(
      PipelineMeta source,
      PipelineMeta clone,
      IVariables variables,
      IHopMetadataProvider provider,
      List<StreamDescriptor> selected,
      GeometryInspectorOptions options,
      CacheRepository repository,
      BooleanSupplier cancelled)
      throws Exception {
    boolean disk = options.sampleSize() == Integer.MAX_VALUE;
    UUID generation = UUID.randomUUID();
    Map<StreamDescriptor, Collector> collectors = new LinkedHashMap<>();
    LocalPipelineEngine engine = new LocalPipelineEngine(clone, variables, null);
    engine.setMetadataProvider(provider);
    engine.copyParametersFromDefinitions(clone);
    for (String parameter : clone.listParameters()) {
      String value = variables.getVariable(parameter, clone.getParameterDefault(parameter));
      engine.setParameterValue(parameter, value);
      engine.setVariable(parameter, value);
    }
    engine.activateParameters(variables);
    InspectionResult.Completion completion = InspectionResult.Completion.COMPLETE;
    String reason = "Natural completion";
    try {
      for (var stream : selected) {
        String fingerprint = CacheFingerprint.of(source, stream.transform(), variables, provider);
        collectors.put(
            stream,
            new Collector(
                options,
                disk
                    ? repository.begin(source.getFilename(), stream, generation, fingerprint)
                    : null,
                fingerprint));
      }
      for (var entry : collectors.entrySet()) {
        var stream = entry.getKey();
        try {
          var transform = source.findTransform(stream.transform());
          entry.getValue().emptySchema =
              stream.direction() == StreamDescriptor.Direction.INPUT
                  ? source.getTransformFields(
                      variables, source.findTransform(stream.peer()), transform, null)
                  : source.getTransformFields(
                      variables, transform, source.findTransform(stream.peer()), null);
        } catch (Exception ignored) {
          entry.getValue().emptySchema = new org.apache.hop.core.row.RowMeta();
        }
      }
      engine.prepareExecution();
      for (var combi : engine.getTransforms()) {
        if (!(combi.transform instanceof BaseTransform<?, ?> transform))
          throw new IllegalArgumentException("Unsupported row handler: " + combi.transformName);
        IRowHandler delegate = transform.getRowHandler();
        transform.setRowHandler(
            new IRowHandler() {
              private void accept(
                  StreamDescriptor.Direction direction,
                  StreamDescriptor.Kind kind,
                  String peer,
                  IRowMeta meta,
                  Object[] row)
                  throws HopTransformException {
                if (row == null) return;
                Collector collector =
                    collectors.get(
                        new StreamDescriptor(
                            combi.transformName, direction, peer, kind, combi.copy));
                if (collector != null)
                  try {
                    collector.accept(meta, row);
                  } catch (Exception e) {
                    throw new HopTransformException("Capture failed for " + combi.transformName, e);
                  }
              }

              public Object[] getRow() throws HopException {
                return delegate.getRow();
              }

              public Object[] getRowFrom(IRowSet set) throws HopTransformException {
                Object[] row = delegate.getRowFrom(set);
                accept(
                    StreamDescriptor.Direction.INPUT,
                    StreamDescriptor.Kind.MAIN,
                    set.getOriginTransformName(),
                    set.getRowMeta(),
                    row);
                return row;
              }

              public void putRow(IRowMeta meta, Object[] row) throws HopTransformException {
                accept(
                    StreamDescriptor.Direction.OUTPUT, StreamDescriptor.Kind.MAIN, "", meta, row);
                delegate.putRow(meta, row);
              }

              public void putRowTo(IRowMeta meta, Object[] row, IRowSet set)
                  throws HopTransformException {
                accept(
                    StreamDescriptor.Direction.OUTPUT,
                    StreamDescriptor.Kind.TARGET,
                    set.getDestinationTransformName(),
                    meta,
                    row);
                delegate.putRowTo(meta, row, set);
              }

              public void putError(
                  IRowMeta meta,
                  Object[] row,
                  long count,
                  String description,
                  String fields,
                  String codes)
                  throws HopTransformException {
                var errors = transform.getTransformMeta().getTransformErrorMeta();
                if (errors != null && errors.getTargetTransform() != null) {
                  IRowMeta errorMeta = meta.clone();
                  errorMeta.addRowMeta(errors.getErrorRowMeta(variables));
                  Object[] errorRow = Arrays.copyOf(row, errorMeta.size());
                  errors.addErrorRowData(
                      transform, errorRow, meta.size(), count, description, fields, codes);
                  accept(
                      StreamDescriptor.Direction.OUTPUT,
                      StreamDescriptor.Kind.ERROR,
                      errors.getTargetTransform().getName(),
                      errorMeta,
                      errorRow);
                }
                delegate.putError(meta, row, count, description, fields, codes);
              }
            });
      }
      engine.startThreads();
      long deadline = System.nanoTime() + options.timeout().toNanos();
      while (engine.isRunning()) {
        boolean full =
            !disk
                && options.mode() == SamplingMode.FIRST
                && collectors.values().stream().allMatch(c -> c.sample.isFull());
        if (cancelled.getAsBoolean()
            || Thread.currentThread().isInterrupted()
            || System.nanoTime() >= deadline
            || full) {
          completion = InspectionResult.Completion.PARTIAL;
          reason =
              full
                  ? "Sample limit reached"
                  : cancelled.getAsBoolean() ? "Cancelled" : "Timeout or interruption";
          engine.stopAll();
          break;
        }
        Thread.sleep(25);
      }
      engine.waitUntilFinished();
      if (engine.getErrors() > 0) {
        completion = InspectionResult.Completion.FAILED;
        reason = "Pipeline or capture failed; see Hop log";
      }
    } catch (Exception e) {
      completion = InspectionResult.Completion.FAILED;
      reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw e;
    } finally {
      if (engine.isRunning()) {
        engine.stopAll();
        engine.waitUntilFinished();
      }
      if (engine.isStopped() && completion == InspectionResult.Completion.COMPLETE) {
        completion = InspectionResult.Completion.PARTIAL;
        reason = "Pipeline stopped before natural completion";
      }
      if (collectors.values().stream().anyMatch(c -> c.failure != null)) {
        completion = InspectionResult.Completion.FAILED;
        reason = "Capture failed; preserved rows are incomplete";
      }
      Exception closeFailure = null;
      for (var c : collectors.values())
        if (c.cache != null) {
          try {
            if (c.meta == null && c.emptySchema != null) c.cache.initialize(c.emptySchema);
            c.cache.finish(completion, reason);
          } catch (Exception error) {
            if (closeFailure == null) closeFailure = error;
            else closeFailure.addSuppressed(error);
            try {
              c.cache.close();
            } catch (Exception second) {
              error.addSuppressed(second);
            }
          }
        }
      if (closeFailure != null) throw closeFailure;
    }
    List<InspectionResult> results = new ArrayList<>();
    for (var entry : collectors.entrySet()) {
      Collector c = entry.getValue();
      UUID id = c.cache == null ? UUID.randomUUID() : c.cache.id();
      RowStore rows =
          c.cache == null
              ? new MemoryRowStore(
                  c.meta == null ? c.emptySchema : c.sample.snapshotRowMeta(),
                  c.sample.snapshotRows())
              : repository.open(id);
      results.add(
          new InspectionResult(
              id,
              generation,
              Objects.toString(source.getFilename(), source.getName()),
              entry.getKey(),
              Instant.now(),
              rows,
              !disk,
              completion,
              reason,
              c.fingerprint));
    }
    return results;
  }

  private static final class Collector {
    final GeometrySampleCollector sample;
    final CacheRepository.Capture cache;
    final String fingerprint;
    IRowMeta meta;
    IRowMeta emptySchema;
    volatile Exception failure;

    Collector(GeometryInspectorOptions options, CacheRepository.Capture cache, String fingerprint) {
      this.sample = new GeometrySampleCollector(options.sampleSize(), options.mode());
      this.cache = cache;
      this.fingerprint = fingerprint;
    }

    synchronized void accept(IRowMeta schema, Object[] row) throws Exception {
      if (failure != null) throw failure;
      try {
        if (meta != null && !meta.getMetaXml().equals(schema.getMetaXml()))
          throw new IllegalArgumentException("Stream schema changed");
        if (meta == null) meta = schema.clone();
        if (cache == null) sample.accept(schema, row);
        else cache.append(schema, row);
      } catch (Exception error) {
        failure = error;
        throw error;
      }
    }
  }
}
