package ch.so.agi.hop.geometry.inspector.data;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hop.core.row.IRowMeta;

/** Session-owned Hop binary rows and a fixed-width index for random access. */
final class DiskRowStore implements RowStore {
  static final String DIRECTORY_PREFIX =
      "data-inspector-rows-" + ProcessHandle.current().pid() + "-";
  static final long CAPTURE_BYTES = 10L * 1024 * 1024 * 1024;
  private static final AtomicLong TEMP_BYTES = new AtomicLong();
  private static final int INDEX_BYTES = 2 * Long.BYTES;

  private final IRowMeta meta;
  private final Path directory;
  private final Path dataPath;
  private final Path indexPath;
  private final long byteLimit;
  private final RandomAccessFile data;
  private final RandomAccessFile index;
  private long bytes;
  private long count;
  private boolean closed;

  DiskRowStore(IRowMeta schema, long byteLimit) throws IOException {
    meta = schema.clone();
    this.byteLimit = byteLimit;
    directory = Files.createTempDirectory(DIRECTORY_PREFIX);
    dataPath = directory.resolve("rows.bin");
    indexPath = directory.resolve("offsets.bin");
    try {
      data = new RandomAccessFile(dataPath.toFile(), "rw");
      try {
        index = new RandomAccessFile(indexPath.toFile(), "rw");
      } catch (IOException error) {
        try {
          data.close();
        } catch (IOException closeError) {
          error.addSuppressed(closeError);
        }
        throw error;
      }
    } catch (IOException error) {
      try {
        deleteFiles();
      } catch (IOException cleanup) {
        error.addSuppressed(cleanup);
      }
      throw error;
    }
  }

  @Override
  public IRowMeta rowMeta() {
    return meta;
  }

  @Override
  public long size() {
    return count;
  }

  private void reserve(int length) throws IOException {
    if (bytes + length > byteLimit
        || TEMP_BYTES.getAndUpdate(
                    total -> total + length <= CAPTURE_BYTES ? total + length : total)
                + length
            > CAPTURE_BYTES)
      throw new IOException(
          "Temporary inspection storage limit reached. Close a result or use Sample.");
    bytes += length;
  }

  synchronized void append(Object[] row) throws IOException {
    if (closed) throw new IOException("Inspection rows are closed");
    long previousBytes = bytes;
    long start = data.length();
    long indexStart = count * INDEX_BYTES;
    try {
      reserve(INDEX_BYTES);
      data.seek(start);
      meta.writeData(
          new DataOutputStream(
              new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                  reserve(1);
                  data.write(value);
                }

                @Override
                public void write(byte[] buffer, int offset, int length) throws IOException {
                  reserve(length);
                  data.write(buffer, offset, length);
                }
              }),
          row);
      index.seek(indexStart);
      index.writeLong(start);
      index.writeLong(data.getFilePointer() - start);
      count++;
    } catch (Exception error) {
      try {
        data.setLength(start);
        index.setLength(indexStart);
        TEMP_BYTES.addAndGet(previousBytes - bytes);
        bytes = previousBytes;
      } catch (IOException rollback) {
        error.addSuppressed(rollback);
      }
      throw new IOException("Cannot write inspection row " + count, error);
    }
  }

  @Override
  public synchronized Object[] row(long ordinal) throws IOException {
    if (ordinal < 0 || ordinal >= count) throw new IndexOutOfBoundsException();
    index.seek(ordinal * INDEX_BYTES);
    long offset = index.readLong();
    long length = index.readLong();
    if (offset < 0 || length < 0 || offset > data.length() || length > data.length() - offset)
      throw new IOException("Truncated inspection row " + ordinal);
    data.seek(offset);
    try {
      var input =
          new InputStream() {
            long remaining = length;

            @Override
            public int read() throws IOException {
              if (remaining == 0) return -1;
              int value = data.read();
              if (value >= 0) remaining--;
              return value;
            }

            @Override
            public int read(byte[] buffer, int start, int size) throws IOException {
              if (size == 0) return 0;
              if (remaining == 0) return -1;
              int read = data.read(buffer, start, (int) Math.min(size, remaining));
              if (read > 0) remaining -= read;
              return read;
            }
          };
      Object[] row = meta.readData(new DataInputStream(input));
      if (data.getFilePointer() != offset + length) throw new IOException("Row length mismatch");
      return row;
    } catch (Exception error) {
      throw new IOException("Cannot read inspection row " + ordinal, error);
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    try (var rowFile = data;
        var indexFile = index) {
      // Close both handles before deleting on Windows.
    } finally {
      deleteFiles();
      TEMP_BYTES.addAndGet(-bytes);
      closed = true;
    }
  }

  private void deleteFiles() throws IOException {
    Files.deleteIfExists(indexPath);
    Files.deleteIfExists(dataPath);
    Files.deleteIfExists(directory);
  }
}
