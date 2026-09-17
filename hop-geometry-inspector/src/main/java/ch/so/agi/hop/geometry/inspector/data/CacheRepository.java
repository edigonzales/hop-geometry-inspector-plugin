package ch.so.agi.hop.geometry.inspector.data;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** One repository per process; leases protect viewers and writers from eviction. */
public final class CacheRepository {
  public static final long DEFAULT_BUDGET = 10L * 1024 * 1024 * 1024;
  private final Path root;
  private final long budget;
  private final Duration maxAge;
  private long usedBytes;
  private final Map<UUID, Integer> leased = new HashMap<>();

  public CacheRepository(Path root, long budget, Duration maxAge) throws IOException {
    if (budget <= 0 || maxAge.isNegative())
      throw new IllegalArgumentException("Invalid cache limits");
    this.root = root;
    this.budget = budget;
    this.maxAge = maxAge;
    Files.createDirectories(root);
    for (Entry entry : entries())
      if ("RUNNING".equals(entry.manifest().getProperty("state"))) {
        var manifest = entry.manifest();
        manifest.setProperty("state", "PARTIAL");
        manifest.setProperty("reason", "Interrupted recording recovered after restart");
        Path offsets = root.resolve(entry.id().toString()).resolve("offsets.bin");
        if (Files.exists(offsets)) {
          try (var file = new RandomAccessFile(offsets.toFile(), "rw")) {
            long rows = file.length() / 24;
            file.setLength(rows * 24);
            manifest.setProperty("rows", Long.toString(rows));
          }
        }
        write(root.resolve(entry.id().toString()), manifest);
      }
    usedBytes = bytes(root);
  }

  public static CacheRepository defaults() throws IOException {
    return new CacheRepository(
        Path.of(
            System.getProperty(
                "geometryInspector.cache.path",
                Path.of(System.getProperty("user.home"), ".hop", "data-inspector", "cache")
                    .toString())),
        Long.getLong("geometryInspector.cache.bytes", DEFAULT_BUDGET),
        Duration.ofDays(Long.getLong("geometryInspector.cache.days", 7)));
  }

  public record Entry(UUID id, Properties manifest, long bytes) {}

  public synchronized List<Entry> entries() throws IOException {
    List<Entry> result = new ArrayList<>();
    try (var paths = Files.list(root)) {
      for (Path path : paths.filter(Files::isDirectory).toList()) {
        try {
          UUID id = UUID.fromString(path.getFileName().toString());
          Properties manifest = read(path);
          if (!"1".equals(manifest.getProperty("version"))) continue;
          result.add(new Entry(id, manifest, bytes(path)));
        } catch (IllegalArgumentException | IOException e) {
          /* Ignore foreign/corrupt directories. */
        }
      }
    }
    return result;
  }

  public synchronized Capture begin(
      String pipeline, StreamDescriptor stream, UUID generation, String fingerprint)
      throws IOException {
    cleanup();
    long used = bytes(root);
    if (used >= budget)
      throw new IOException("Cache budget exhausted; close or delete existing caches");
    UUID id = UUID.randomUUID();
    Path directory = root.resolve(id.toString());
    Files.createDirectory(directory);
    Properties p = new Properties();
    p.setProperty("hopVersion", "2.19.0");
    p.setProperty("javaVersion", System.getProperty("java.version"));
    p.setProperty("formatVersion", "1");
    p.setProperty("version", "1");
    p.setProperty("pipeline", pipeline == null ? "" : pipeline);
    p.setProperty("generation", generation.toString());
    p.setProperty("fingerprint", fingerprint);
    p.setProperty("created", Instant.now().toString());
    p.setProperty("accessed", Instant.now().toString());
    p.setProperty("state", "RUNNING");
    p.setProperty("transform", stream.transform());
    p.setProperty("direction", stream.direction().name());
    p.setProperty("peer", stream.peer());
    p.setProperty("kind", stream.kind().name());
    p.setProperty("copy", Integer.toString(stream.copy()));
    write(directory, p);
    usedBytes = bytes(root);
    leased.merge(id, 1, Integer::sum);
    return new Capture(id, directory, p, new DiskRowStore.Writer(directory, budget - used));
  }

  public synchronized RowStore open(UUID id) throws IOException {
    Path directory = root.resolve(id.toString());
    Properties p = read(directory);
    if (!"1".equals(p.getProperty("version"))) throw new IOException("Unsupported cache version");
    DiskRowStore disk = new DiskRowStore(directory);
    leased.merge(id, 1, Integer::sum);
    p.setProperty("accessed", Instant.now().toString());
    write(directory, p);
    return new RowStore() {
      public org.apache.hop.core.row.IRowMeta rowMeta() {
        return disk.rowMeta();
      }

      public long size() {
        return disk.size();
      }

      public Object[] row(long ordinal) throws IOException {
        return disk.row(ordinal);
      }

      private boolean closed;

      public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
          disk.close();
        } finally {
          synchronized (CacheRepository.this) {
            leased.computeIfPresent(id, (key, count) -> count <= 1 ? null : count - 1);
          }
        }
      }
    };
  }

  public synchronized InspectionResult result(UUID id) throws IOException {
    Properties p = read(root.resolve(id.toString()));
    try {
      var stream =
          new StreamDescriptor(
              p.getProperty("transform"),
              StreamDescriptor.Direction.valueOf(p.getProperty("direction")),
              p.getProperty("peer"),
              StreamDescriptor.Kind.valueOf(p.getProperty("kind")),
              Integer.parseInt(p.getProperty("copy")));
      var state = InspectionResult.Completion.valueOf(p.getProperty("state"));
      if (state == InspectionResult.Completion.RUNNING) state = InspectionResult.Completion.PARTIAL;
      return new InspectionResult(
          id,
          UUID.fromString(p.getProperty("generation")),
          p.getProperty("pipeline"),
          stream,
          Instant.parse(p.getProperty("created")),
          open(id),
          false,
          state,
          p.getProperty("reason", "Interrupted recording"),
          p.getProperty("fingerprint"));
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid cache manifest", e);
    }
  }

  public synchronized void recordParents(UUID id, List<InspectionResult> parents)
      throws IOException {
    Path directory = root.resolve(id.toString());
    Properties manifest = read(directory);
    manifest.setProperty(
        "parentCaches",
        parents.stream()
            .map(result -> result.id().toString())
            .collect(java.util.stream.Collectors.joining(",")));
    manifest.setProperty(
        "parentGenerations",
        parents.stream()
            .map(result -> result.generation().toString())
            .distinct()
            .collect(java.util.stream.Collectors.joining(",")));
    write(directory, manifest);
  }

  public synchronized void delete(UUID id) throws IOException {
    if (leased.containsKey(id)) throw new IOException("Cache is in use");
    Path path = root.resolve(id.toString());
    if (!Files.exists(path)) return;
    try (var paths = Files.walk(path)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  public synchronized void cleanup() throws IOException {
    List<Entry> entries = new ArrayList<>(entries());
    entries.sort(Comparator.comparing(e -> e.manifest().getProperty("accessed", "")));
    long total = bytes(root);
    for (Entry e : entries) {
      if (leased.containsKey(e.id())) continue;
      Instant accessed;
      try {
        accessed = Instant.parse(e.manifest().getProperty("accessed"));
      } catch (Exception invalid) {
        accessed = Instant.EPOCH;
      }
      if (total >= budget || accessed.plus(maxAge).isBefore(Instant.now())) {
        delete(e.id());
        total -= e.bytes();
      }
    }
    usedBytes = bytes(root);
  }

  private static long bytes(Path path) throws IOException {
    try (var paths = Files.walk(path)) {
      long count = 0;
      for (Path p : paths.filter(Files::isRegularFile).toList()) count += Files.size(p);
      return count;
    }
  }

  private static Properties read(Path directory) throws IOException {
    Properties p = new Properties();
    try (var in = Files.newInputStream(directory.resolve("manifest.properties"))) {
      p.load(in);
    }
    return p;
  }

  private static void write(Path directory, Properties p) throws IOException {
    Path pending = directory.resolve("manifest.pending");
    try (var out = Files.newOutputStream(pending)) {
      p.store(out, "Data Inspector cache v1");
    }
    try {
      Files.move(
          pending,
          directory.resolve("manifest.properties"),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(
          pending, directory.resolve("manifest.properties"), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public final class Capture implements AutoCloseable {
    private final UUID id;
    private final Path directory;
    private final Properties manifest;
    private final DiskRowStore.Writer writer;
    private boolean finished;

    Capture(UUID id, Path directory, Properties manifest, DiskRowStore.Writer writer) {
      this.id = id;
      this.directory = directory;
      this.manifest = manifest;
      this.writer = writer;
    }

    public UUID id() {
      return id;
    }

    public void initialize(org.apache.hop.core.row.IRowMeta schema) throws IOException {
      synchronized (CacheRepository.this) {
        long before = writer.storageSize();
        try {
          writer.initialize(schema);
        } finally {
          usedBytes += writer.storageSize() - before;
        }
      }
    }

    public synchronized void append(org.apache.hop.core.row.IRowMeta schema, Object[] row)
        throws IOException {
      if (finished) throw new IOException("Capture closed");
      synchronized (CacheRepository.this) {
        while (true) {
          long before = writer.storageSize();
          writer.limit(before + Math.max(0, budget - usedBytes));
          try {
            writer.append(schema, row);
            return;
          } catch (IOException error) {
            boolean quota = false;
            for (Throwable cause = error; cause != null; cause = cause.getCause())
              if (Objects.toString(cause.getMessage(), "").contains("budget")) quota = true;
            if (!quota) throw error;
            var removable =
                entries().stream()
                    .filter(entry -> !leased.containsKey(entry.id()))
                    .min(
                        Comparator.comparing(
                            entry -> entry.manifest().getProperty("accessed", "")));
            if (removable.isEmpty()) throw error;
            delete(removable.get().id());
            usedBytes = bytes(root) - (writer.storageSize() - before);
          } finally {
            usedBytes += writer.storageSize() - before;
          }
        }
      }
    }

    public synchronized void finish(InspectionResult.Completion completion, String reason)
        throws IOException {
      if (finished) return;
      try {
        writer.initialize(new org.apache.hop.core.row.RowMeta());
        writer.close();
        manifest.setProperty("state", completion.name());
        manifest.setProperty("reason", reason);
        manifest.setProperty("rows", Long.toString(writer.size()));
        write(directory, manifest);
      } finally {
        finished = true;
        synchronized (CacheRepository.this) {
          leased.computeIfPresent(id, (key, count) -> count <= 1 ? null : count - 1);
        }
      }
    }

    public void close() throws IOException {
      finish(InspectionResult.Completion.PARTIAL, "Capture interrupted");
    }
  }
}
