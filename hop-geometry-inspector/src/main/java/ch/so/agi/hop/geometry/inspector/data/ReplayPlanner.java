package ch.so.agi.hop.geometry.inspector.data;

import java.util.*;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.transform.TransformMeta;

/** Conservative replay planning: every boundary is validated before engine initialization. */
public final class ReplayPlanner {
  public enum Mode {
    TO,
    FROM,
    BETWEEN
  }

  public record Plan(
      PipelineMeta pipeline, Set<String> executing, List<InspectionResult> inputs, Mode mode) {
    public String description() {
      return mode
          + "\nExecute (including any writers): "
          + String.join(", ", executing)
          + "\nCaches: "
          + inputs.stream().map(r -> r.stream().label() + " @ " + r.capturedAt()).toList();
    }
  }

  // Stateful/dynamic transforms are deliberately not inferred safe from their interface alone.
  private static final Set<String> SUPPORTED =
      Set.of(
          "Dummy",
          "RowGenerator",
          "DataGrid",
          "CsvInput",
          "TextFileInput",
          "SelectValues",
          "FilterRows",
          "SortRows",
          "MergeJoin",
          "MergeRows",
          "Calculator",
          "StringOperations",
          "IfNull",
          "Constant",
          "TextFileOutput",
          "Unique",
          "UniqueRowsByHashSet",
          "Append",
          "SwitchCase",
          "GEOMETRY_CALCULATOR_TRANSFORM");

  public Plan plan(
      PipelineMeta source,
      IVariables variables,
      IHopMetadataProvider provider,
      Mode mode,
      String start,
      String end,
      List<InspectionResult> caches)
      throws Exception {
    if (source.getPipelineType() != PipelineMeta.PipelineType.Normal)
      throw new IllegalArgumentException("Replay requires a normal local pipeline");
    if (source.findTransform(start) == null
        || mode == Mode.BETWEEN && source.findTransform(end) == null)
      throw new IllegalArgumentException("Unknown replay boundary");
    checkCycles(source);
    Set<String> execute =
        mode == Mode.TO
            ? CaptureService.ancestors(source, List.of(start))
            : descendants(source, start);
    if (mode == Mode.BETWEEN) {
      execute.retainAll(CaptureService.ancestors(source, List.of(end)));
      if (!execute.contains(start) || !execute.contains(end))
        throw new IllegalArgumentException("No path between selected transforms");
    }
    Map<String, List<InspectionResult>> replacements = new LinkedHashMap<>();
    if (mode == Mode.TO) {
      for (String name : new ArrayList<>(execute))
        if (!name.equals(start)) {
          List<InspectionResult> valid = validCaches(source, name, caches, variables, provider);
          if (!valid.isEmpty()) {
            replacements.put(name, valid);
            execute.remove(name);
          }
        }
      // Remove ancestors made unnecessary by a cached boundary.
      Set<String> needed = new LinkedHashSet<>();
      Deque<String> queue = new ArrayDeque<>();
      queue.add(start);
      while (!queue.isEmpty()) {
        String name = queue.remove();
        if (!needed.add(name) || replacements.containsKey(name)) continue;
        for (var h : source.getPipelineHops())
          if (h.isEnabled() && h.getToTransform().getName().equals(name))
            queue.add(h.getFromTransform().getName());
      }
      execute.retainAll(needed);
      replacements.keySet().retainAll(needed);
    }
    Set<String> boundary = new LinkedHashSet<>();
    for (var hop : source.getPipelineHops())
      if (hop.isEnabled()
          && execute.contains(hop.getToTransform().getName())
          && !execute.contains(hop.getFromTransform().getName()))
        boundary.add(hop.getFromTransform().getName());
    for (String name : boundary) {
      var valid = validCaches(source, name, caches, variables, provider);
      if (valid.isEmpty())
        throw new IllegalArgumentException("Missing complete current cache for " + name);
      replacements.put(name, valid);
    }
    for (String name : execute) {
      TransformMeta transform = source.findTransform(name);
      if (transform.isPartitioned()
          || transform.getCopies(variables) != 1
          || !SUPPORTED.contains(transform.getTransformPluginId()))
        throw new IllegalArgumentException(
            "Replay does not support transform "
                + name
                + " ("
                + transform.getTransformPluginId()
                + ")");
    }
    List<InspectionResult> inputs = replacements.values().stream().flatMap(List::stream).toList();
    if (inputs.stream().map(InspectionResult::generation).distinct().count() > 1)
      throw new IllegalArgumentException(
          "All replay inputs must belong to the same capture generation");
    for (var input : inputs)
      for (long ordinal = 0; ordinal < input.rows().size(); ordinal++) input.rows().row(ordinal);
    PipelineMeta clone = CaptureService.prune(source, variables, provider, execute);
    for (var transform : new ArrayList<>(clone.getTransforms()))
      if (!execute.contains(transform.getName())
          && !replacements.containsKey(transform.getName())) {
        // Retain outgoing dummy sinks, remove excluded ancestors.
        if (CaptureService.ancestors(source, execute).contains(transform.getName()))
          clone.removeTransform(clone.getTransforms().indexOf(transform));
      }
    for (var entry : replacements.entrySet()) {
      TransformMeta transform = clone.findTransform(entry.getKey());
      if (transform == null) {
        transform = new TransformMeta(entry.getKey(), new ReplaySourceMeta(entry.getValue()));
        clone.addTransform(transform);
      } else transform.setTransform(new ReplaySourceMeta(entry.getValue()));
      transform.setTransformPluginId("Dummy");
      transform.setTransformErrorMeta(null);
      transform.setCopies(1);
    }
    for (int i = clone.getPipelineHops().size() - 1; i >= 0; i--) {
      var h = clone.getPipelineHops().get(i);
      if (replacements.containsKey(h.getToTransform().getName())
          || clone.findTransform(h.getFromTransform().getName()) == null
          || clone.findTransform(h.getToTransform().getName()) == null) clone.removePipelineHop(i);
    }
    return new Plan(clone, Collections.unmodifiableSet(execute), inputs, mode);
  }

  private List<InspectionResult> validCaches(
      PipelineMeta source,
      String name,
      List<InspectionResult> candidates,
      IVariables variables,
      IHopMetadataProvider provider)
      throws Exception {
    String fingerprint = CacheFingerprint.of(source, name, variables, provider);
    var valid =
        candidates.stream()
            .filter(
                r ->
                    r.stream().transform().equals(name)
                        && r.stream().direction() == StreamDescriptor.Direction.OUTPUT
                        && r.stream().copy() == 0
                        && r.replayable(fingerprint))
            .toList();
    // First replay version requires a complete logical main stream. Directed/error boundaries
    // remain inspectable, but cannot silently substitute an incomplete set of source outputs.
    var main =
        valid.stream().filter(r -> r.stream().kind() == StreamDescriptor.Kind.MAIN).findFirst();
    if (main.isEmpty()) return List.of();
    var transform = source.findTransform(name);
    if (transform.isPartitioned()
        || transform.getCopies(variables) != 1
        || transform.isDoingErrorHandling()
        || !transform.getTransform().getTransformIOMeta().getTargetStreams().isEmpty())
      return List.of();
    var expected = source.getTransformFields(variables, transform);
    var actual = main.get().rows().rowMeta();
    if (expected != null && expected.size() > 0) {
      if (actual == null || expected.size() != actual.size())
        throw new IllegalArgumentException("Incompatible cache schema for " + name);
      for (int i = 0; i < expected.size(); i++)
        if (!expected.getValueMeta(i).getName().equals(actual.getValueMeta(i).getName())
            || expected.getValueMeta(i).getType() != actual.getValueMeta(i).getType()
            || expected.getValueMeta(i).getStorageType() != actual.getValueMeta(i).getStorageType())
          throw new IllegalArgumentException("Incompatible cache field for " + name);
    }
    return List.of(main.get());
  }

  private static Set<String> descendants(PipelineMeta p, String start) {
    Set<String> s = new LinkedHashSet<>(List.of(start));
    boolean changed;
    do {
      changed = false;
      for (var h : p.getPipelineHops())
        if (h.isEnabled() && s.contains(h.getFromTransform().getName()))
          changed |= s.add(h.getToTransform().getName());
    } while (changed);
    return s;
  }

  private static void checkCycles(PipelineMeta pipeline) {
    Map<String, Integer> degree = new HashMap<>();
    for (var t : pipeline.getTransforms()) degree.put(t.getName(), 0);
    for (var h : pipeline.getPipelineHops())
      if (h.isEnabled()) degree.merge(h.getToTransform().getName(), 1, Integer::sum);
    Deque<String> queue = new ArrayDeque<>();
    degree.forEach(
        (n, d) -> {
          if (d == 0) queue.add(n);
        });
    int seen = 0;
    while (!queue.isEmpty()) {
      String n = queue.remove();
      seen++;
      for (var h : pipeline.getPipelineHops())
        if (h.isEnabled()
            && h.getFromTransform().getName().equals(n)
            && degree.merge(h.getToTransform().getName(), -1, Integer::sum) == 0)
          queue.add(h.getToTransform().getName());
    }
    if (seen != degree.size()) throw new IllegalArgumentException("Replay does not support cycles");
  }
}
