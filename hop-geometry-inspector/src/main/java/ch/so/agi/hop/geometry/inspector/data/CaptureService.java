package ch.so.agi.hop.geometry.inspector.data;

import ch.so.agi.hop.geometry.inspector.model.*;
import ch.so.agi.hop.geometry.inspector.sampling.GeometrySampleCollector;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.hop.core.IRowSet;
import org.apache.hop.core.exception.*;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowMeta;
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

  private static Set<String> ancestors(PipelineMeta pipeline, Collection<String> targets) {
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

  private static PipelineMeta prune(
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
      BooleanSupplier cancelled)
      throws Exception {
    if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one stream");
    if (source.getPipelineType() != PipelineMeta.PipelineType.Normal)
      throw new IllegalArgumentException("Capture supports normal local pipelines only");
    checkCancelled(cancelled);
    var targets = selected.stream().map(StreamDescriptor::transform).toList();
    for (String name : ancestors(source, targets))
      if (source.findTransform(name).isPartitioned())
        throw new IllegalArgumentException("Partitioned capture is unsupported: " + name);

    PipelineMeta clone = prune(source, variables, provider, targets);
    LocalPipelineEngine engine = new LocalPipelineEngine(clone, variables, null);
    engine.setMetadataProvider(provider);
    engine.copyParametersFromDefinitions(clone);
    for (String parameter : clone.listParameters()) {
      String value = variables.getVariable(parameter, clone.getParameterDefault(parameter));
      engine.setParameterValue(parameter, value);
      engine.setVariable(parameter, value);
    }
    engine.activateParameters(variables);

    Map<StreamDescriptor, Collector> collectors = new LinkedHashMap<>();
    for (var stream : selected) collectors.put(stream, new Collector(options));

    Instant capturedAt = Instant.now();
    var accepting = new AtomicBoolean(true);
    try {
      String stopReason = null;
      boolean prepared = false;
      boolean starting = false;
      try {
        engine.prepareExecution();
        prepared = true;
        for (var combi : engine.getTransforms())
          if (collectors.keySet().stream()
              .anyMatch(s -> s.transform().equals(combi.transformName) && s.copy() == combi.copy))
            captureRows(combi, collectors, accepting);
        checkCancelled(cancelled);
        long deadline = System.nanoTime() + options.timeout().toNanos();
        starting = true;
        engine.startThreads();
        while (engine.isRunning()) {
          checkCancelled(cancelled);
          if (System.nanoTime() >= deadline) {
            stopReason = "Timeout";
            break;
          }
          if (!options.allRows()
              && options.mode() == SamplingMode.FIRST
              && collectors.values().stream().allMatch(c -> c.sample.isFull())) {
            stopReason = "Sample limit reached";
            break;
          }
          Thread.sleep(25);
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new CancellationException("Capture interrupted");
      } finally {
        accepting.set(false);
        if (starting) {
          // Hop's wait reacts to interruption. Finish stopping before releasing the stores.
          boolean interrupted = Thread.interrupted();
          try {
            if (engine.isRunning()) engine.stopAll();
            engine.waitUntilFinished();
          } finally {
            if (interrupted) Thread.currentThread().interrupt();
          }
        } else if (prepared) {
          engine.disposeInitializedTransforms();
        }
      }
      checkCancelled(cancelled);
      for (var collector : collectors.values())
        if (collector.failure != null) throw collector.failure;
      if (engine.getErrors() > 0) throw new HopException("Pipeline failed; see Hop log");
      if (stopReason == null && engine.isStopped())
        stopReason = "Pipeline stopped before natural completion";

      List<InspectionResult> results = new ArrayList<>();
      for (var entry : collectors.entrySet()) {
        var stream = entry.getKey();
        var collector = entry.getValue();
        results.add(
            new InspectionResult(
                Objects.toString(source.getFilename(), source.getName()),
                stream,
                capturedAt,
                collector.rows(source, variables, stream),
                !options.allRows(),
                stopReason == null
                    ? InspectionResult.Completion.COMPLETE
                    : InspectionResult.Completion.PARTIAL,
                stopReason == null ? "Natural completion" : stopReason));
      }
      return results;
    } catch (Exception | Error error) {
      for (var collector : collectors.values())
        if (collector.disk != null)
          try {
            collector.disk.close();
          } catch (IOException cleanup) {
            error.addSuppressed(cleanup);
          }
      throw error;
    }
  }

  private static void checkCancelled(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new CancellationException("Capture cancelled");
  }

  private static void captureRows(
      TransformMetaDataCombi combi,
      Map<StreamDescriptor, Collector> collectors,
      AtomicBoolean accepting) {
    if (!(combi.transform instanceof BaseTransform<?, ?> transform))
      throw new IllegalArgumentException("Unsupported row handler: " + combi.transformName);
    boolean captureInput =
        collectors.keySet().stream()
            .anyMatch(
                s ->
                    s.transform().equals(combi.transformName)
                        && s.copy() == combi.copy
                        && s.direction() == StreamDescriptor.Direction.INPUT);
    var errors = transform.getTransformMeta().getTransformErrorMeta();
    Collector errorCollector =
        errors == null || errors.getTargetTransform() == null
            ? null
            : collectors.get(
                new StreamDescriptor(
                    combi.transformName,
                    StreamDescriptor.Direction.OUTPUT,
                    errors.getTargetTransform().getName(),
                    StreamDescriptor.Kind.ERROR,
                    combi.copy));
    IRowHandler delegate = transform.getRowHandler();
    transform.setRowHandler(
        new IRowHandler() {
          private boolean readingMainInput;

          private void accept(
              StreamDescriptor.Direction direction,
              StreamDescriptor.Kind kind,
              String peer,
              IRowMeta meta,
              Object[] row)
              throws HopTransformException {
            if (row == null || !accepting.get()) return;
            Collector collector =
                collectors.get(
                    new StreamDescriptor(combi.transformName, direction, peer, kind, combi.copy));
            if (collector != null)
              try {
                collector.accept(meta, row);
              } catch (Exception e) {
                throw new HopTransformException("Capture failed for " + combi.transformName, e);
              }
          }

          @Override
          public Object[] getRow() throws HopException {
            if (!captureInput) return delegate.getRow();
            readingMainInput = true;
            try {
              var row = delegate.getRow();
              // Hop's normal read can bypass getRowFrom. Its current rowset identifies the peer
              // of a successful read, including the fallback through getRowFrom.
              if (row != null) {
                var sets = transform.getInputRowSets();
                if (!sets.isEmpty())
                  acceptInput(sets.get(transform.getCurrentInputRowSetNr()), row);
              }
              return row;
            } finally {
              readingMainInput = false;
            }
          }

          @Override
          public Object[] getRowFrom(IRowSet set) throws HopTransformException {
            Object[] row = delegate.getRowFrom(set);
            if (captureInput && !readingMainInput) acceptInput(set, row);
            return row;
          }

          private void acceptInput(IRowSet set, Object[] row) throws HopTransformException {
            accept(
                StreamDescriptor.Direction.INPUT,
                StreamDescriptor.Kind.MAIN,
                set.getOriginTransformName(),
                set.getRowMeta(),
                row);
          }

          @Override
          public void putRow(IRowMeta meta, Object[] row) throws HopTransformException {
            accept(StreamDescriptor.Direction.OUTPUT, StreamDescriptor.Kind.MAIN, "", meta, row);
            delegate.putRow(meta, row);
          }

          @Override
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

          @Override
          public void putError(
              IRowMeta meta,
              Object[] row,
              long count,
              String description,
              String fields,
              String codes)
              throws HopTransformException {
            if (errorCollector != null && accepting.get()) {
              // Hop's error listener receives the input row, without the fields sent to the
              // error target. Build the same error row with the transform's resolved variables.
              IRowMeta errorMeta = meta.clone();
              errorMeta.addRowMeta(errors.getErrorRowMeta(transform));
              Object[] errorRow = Arrays.copyOf(row, errorMeta.size());
              errors.addErrorRowData(
                  transform, errorRow, meta.size(), count, description, fields, codes);
              captureRow(errorCollector, errorMeta, errorRow, accepting);
            }
            delegate.putError(meta, row, count, description, fields, codes);
          }
        });
  }

  private static void captureRow(
      Collector collector, IRowMeta meta, Object[] row, AtomicBoolean accepting)
      throws HopTransformException {
    if (collector == null || row == null || !accepting.get()) return;
    try {
      collector.accept(meta, row);
    } catch (Exception error) {
      throw new HopTransformException("Capture failed", error);
    }
  }

  private static IRowMeta emptySchema(
      PipelineMeta source, IVariables variables, StreamDescriptor stream) {
    try {
      var transform = source.findTransform(stream.transform());
      var peer = source.findTransform(stream.peer());
      return stream.direction() == StreamDescriptor.Direction.INPUT
          ? source.getTransformFields(variables, peer, transform, null)
          : source.getTransformFields(variables, transform, peer, null);
    } catch (Exception ignored) {
      return new RowMeta();
    }
  }

  private static final class Collector {
    final GeometrySampleCollector sample;
    DiskRowStore disk;
    String schemaXml;
    volatile Exception failure;

    Collector(GeometryInspectorOptions options) {
      sample =
          options.allRows()
              ? null
              : new GeometrySampleCollector(options.sampleSize(), options.mode());
    }

    synchronized void accept(IRowMeta schema, Object[] row) throws Exception {
      if (failure != null) throw failure;
      try {
        String xml = schema.getMetaXml();
        if (schemaXml != null && !schemaXml.equals(xml))
          throw new IllegalArgumentException("Stream schema changed during capture");
        schemaXml = xml;
        if (sample != null) {
          sample.accept(schema, row);
        } else {
          if (disk == null) disk = new DiskRowStore(schema, DiskRowStore.CAPTURE_BYTES);
          disk.append(row);
        }
      } catch (Exception error) {
        failure = error;
        throw error;
      }
    }

    RowStore rows(PipelineMeta source, IVariables variables, StreamDescriptor stream) {
      if (disk != null) return disk;
      IRowMeta meta = sample == null ? null : sample.snapshotRowMeta();
      return new MemoryRowStore(
          meta == null ? emptySchema(source, variables, stream) : meta,
          sample == null ? List.of() : sample.snapshotRows());
    }
  }
}
