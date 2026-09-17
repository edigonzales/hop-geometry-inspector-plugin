package ch.so.agi.hop.geometry.inspector.data;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** External merge sort of row ordinals, bounded chunks and two merge cursors. */
public final class QueryIndex implements AutoCloseable {
  private final Path directory;
  private final RandomAccessFile index;
  private final RandomAccessFile positions;

  private QueryIndex(Path directory, Path file) throws IOException {
    this.directory = directory;
    index = new RandomAccessFile(file.toFile(), "r");
    positions = new RandomAccessFile(directory.resolve("positions.idx").toFile(), "r");
  }

  public long size() throws IOException {
    return index.length() / 8;
  }

  public synchronized long ordinal(long position) throws IOException {
    if (position < 0 || position >= size()) throw new IndexOutOfBoundsException();
    index.seek(position * 8);
    return index.readLong();
  }

  public synchronized long position(long ordinal) throws IOException {
    if (ordinal < 0 || ordinal >= positions.length() / 8) return -1;
    positions.seek(ordinal * 8);
    return positions.readLong() - 1;
  }

  public static QueryIndex build(RowStore rows, RowQuery query, BooleanSupplier cancelled)
      throws IOException {
    Path dir = Files.createTempDirectory("data-inspector-query-");
    List<Path> runs = new ArrayList<>();
    Comparator<Long> comparator =
        (a, b) -> {
          try {
            if (query.sort().isEmpty()) return Long.compare(a, b);
            int n = query.compareRows(rows.row(a), rows.row(b), a, b);
            return n == 0 ? Long.compare(a, b) : n;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        };
    try {
      List<Long> chunk = new ArrayList<>(4096);
      for (long i = 0; i < rows.size(); i++) {
        check(cancelled);
        if (query.matches(rows.rowMeta(), rows.row(i))) chunk.add(i);
        if (chunk.size() == 4096) {
          runs.add(run(dir, chunk, comparator));
          chunk.clear();
        }
      }
      if (!chunk.isEmpty() || runs.isEmpty()) runs.add(run(dir, chunk, comparator));
      while (runs.size() > 1) {
        List<Path> next = new ArrayList<>();
        for (int i = 0; i < runs.size(); i += 2) {
          if (i + 1 == runs.size()) {
            next.add(runs.get(i));
            continue;
          }
          Path out = Files.createTempFile(dir, "merge-", ".idx");
          try (var a =
                  new DataInputStream(new BufferedInputStream(Files.newInputStream(runs.get(i))));
              var b =
                  new DataInputStream(
                      new BufferedInputStream(Files.newInputStream(runs.get(i + 1))));
              var o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            Long x = next(a), y = next(b);
            while (x != null || y != null) {
              check(cancelled);
              if (y == null || x != null && comparator.compare(x, y) <= 0) {
                o.writeLong(x);
                x = next(a);
              } else {
                o.writeLong(y);
                y = next(b);
              }
            }
          }
          Files.delete(runs.get(i));
          Files.delete(runs.get(i + 1));
          next.add(out);
        }
        runs = next;
      }
      try (var positions = new RandomAccessFile(dir.resolve("positions.idx").toFile(), "rw");
          var ordered =
              new DataInputStream(new BufferedInputStream(Files.newInputStream(runs.getFirst())))) {
        positions.setLength(Math.multiplyExact(rows.size(), 8));
        Long ordinal;
        long position = 0;
        while ((ordinal = next(ordered)) != null) {
          check(cancelled);
          positions.seek(ordinal * 8);
          positions.writeLong(++position);
        }
      }
      return new QueryIndex(dir, runs.getFirst());
    } catch (Exception e) {
      remove(dir);
      if (e instanceof IOException io) throw io;
      throw new IOException("Query failed", e);
    }
  }

  private static Path run(Path dir, List<Long> values, Comparator<Long> comparator)
      throws IOException {
    values.sort(comparator);
    Path p = Files.createTempFile(dir, "run-", ".idx");
    try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
      for (long value : values) out.writeLong(value);
    }
    return p;
  }

  private static Long next(DataInputStream in) throws IOException {
    try {
      return in.readLong();
    } catch (EOFException e) {
      return null;
    }
  }

  private static void check(BooleanSupplier cancelled) throws IOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new IOException("Query cancelled");
  }

  private static void remove(Path dir) throws IOException {
    try (var files = Files.walk(dir)) {
      for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }

  public void close() throws IOException {
    try {
      index.close();
    } finally {
      try {
        positions.close();
      } finally {
        remove(directory);
      }
    }
  }
}
