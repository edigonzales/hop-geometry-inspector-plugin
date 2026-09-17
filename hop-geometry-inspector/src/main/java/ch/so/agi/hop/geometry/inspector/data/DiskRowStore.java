package ch.so.agi.hop.geometry.inspector.data;

import java.io.*;
import java.nio.file.*;
import java.util.zip.CRC32;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowMeta;

/** Hop binary rows in bounded blocks with an ordinal index and per-row integrity checks. */
public final class DiskRowStore implements RowStore {
  private static final int INDEX_BYTES = 24;
  private static final long BLOCK_BYTES = 8L * 1024 * 1024;
  private final byte[] checksumBuffer = new byte[8192];
  private final Path directory;
  private final RandomAccessFile index;
  private RandomAccessFile block;
  private int blockNumber = -1;
  private final IRowMeta meta;
  private final long count;

  public DiskRowStore(Path directory) throws IOException {
    this.directory = directory;
    try (var in = new DataInputStream(Files.newInputStream(directory.resolve("schema.bin")))) {
      meta = new RowMeta(in);
    } catch (Exception e) {
      throw new IOException("Cannot read cache schema", e);
    }
    index = new RandomAccessFile(directory.resolve("offsets.bin").toFile(), "r");
    if (index.length() % INDEX_BYTES != 0) {
      index.close();
      throw new IOException("Truncated cache index");
    }
    count = index.length() / INDEX_BYTES;
  }

  private static Path blockPath(Path directory, int number) {
    return directory.resolve(String.format(java.util.Locale.ROOT, "rows-%08d.bin", number));
  }

  public IRowMeta rowMeta() {
    return meta;
  }

  public long size() {
    return count;
  }

  public synchronized Object[] row(long ordinal) throws IOException {
    if (ordinal < 0 || ordinal >= count) throw new IndexOutOfBoundsException();
    index.seek(Math.multiplyExact(ordinal, INDEX_BYTES));
    int number = index.readInt();
    long offset = index.readLong();
    long length = index.readLong();
    int checksum = index.readInt();
    if (number < 0 || offset < 0 || length < 0) throw new IOException("Invalid row index");
    if (number != blockNumber) {
      if (block != null) block.close();
      block = new RandomAccessFile(blockPath(directory, number).toFile(), "r");
      blockNumber = number;
    }
    if (offset > block.length() || length > block.length() - offset)
      throw new IOException("Truncated row block");
    if (crc(block, offset, length, checksumBuffer) != checksum)
      throw new IOException("Corrupt cache row " + ordinal);
    block.seek(offset);
    try {
      var input =
          new InputStream() {
            long remaining = length;

            public int read() throws IOException {
              if (remaining == 0) return -1;
              int value = block.read();
              if (value >= 0) remaining--;
              return value;
            }

            public int read(byte[] bytes, int start, int size) throws IOException {
              if (remaining == 0) return -1;
              int n = block.read(bytes, start, (int) Math.min(size, remaining));
              if (n > 0) remaining -= n;
              return n;
            }
          };
      Object[] row = meta.readData(new DataInputStream(input));
      if (block.getFilePointer() != offset + length)
        throw new IOException("Cache row length mismatch");
      return row;
    } catch (Exception e) {
      throw new IOException("Cannot read row " + ordinal, e);
    }
  }

  private static int crc(RandomAccessFile file, long offset, long length, byte[] buffer)
      throws IOException {
    file.seek(offset);
    CRC32 crc = new CRC32();
    while (length > 0) {
      int n = file.read(buffer, 0, (int) Math.min(buffer.length, length));
      if (n < 0) throw new EOFException();
      crc.update(buffer, 0, n);
      length -= n;
    }
    return (int) crc.getValue();
  }

  public synchronized void close() throws IOException {
    try {
      if (block != null) block.close();
    } finally {
      index.close();
    }
  }

  public static final class Writer implements AutoCloseable {
    private final byte[] checksumBuffer = new byte[8192];
    private final Path directory;
    private final RandomAccessFile index;
    private RandomAccessFile data;
    private int blockNumber;
    private long closedBlockBytes, count, byteLimit;
    private IRowMeta meta;

    public Writer(Path directory, long byteLimit) throws IOException {
      this.directory = directory;
      this.byteLimit = byteLimit;
      Files.createDirectories(directory);
      if (Files.exists(directory.resolve("offsets.bin")))
        throw new IOException("Cache already exists");
      index = new RandomAccessFile(directory.resolve("offsets.bin").toFile(), "rw");
      try {
        data = new RandomAccessFile(blockPath(directory, 0).toFile(), "rw");
      } catch (IOException e) {
        index.close();
        throw e;
      }
    }

    public synchronized long storageSize() throws IOException {
      return closedBlockBytes
          + data.length()
          + index.length()
          + (Files.exists(directory.resolve("schema.bin"))
              ? Files.size(directory.resolve("schema.bin"))
              : 0);
    }

    public synchronized void limit(long bytes) {
      byteLimit = bytes;
    }

    public synchronized void initialize(IRowMeta schema) throws IOException {
      if (meta != null) return;
      IRowMeta candidate = schema.clone();
      try (var out = new DataOutputStream(Files.newOutputStream(directory.resolve("schema.bin")))) {
        candidate.writeMeta(out);
      } catch (Exception e) {
        throw new IOException("Cannot write cache schema", e);
      }
      meta = candidate;
    }

    public synchronized void append(IRowMeta schema, Object[] row) throws IOException {
      long start = -1, indexStart = -1;
      try {
        if (meta == null) initialize(schema);
        else if (!meta.getMetaXml().equals(schema.getMetaXml()))
          throw new IOException("Stream schema changed during capture");
        if (data.length() >= BLOCK_BYTES) {
          closedBlockBytes += data.length();
          data.close();
          data = new RandomAccessFile(blockPath(directory, ++blockNumber).toFile(), "rw");
        }
        start = data.length();
        indexStart = index.length();
        data.seek(start);
        meta.writeData(
            new DataOutputStream(
                new OutputStream() {
                  private void reserve(long size) throws IOException {
                    if (storageSize() + size + INDEX_BYTES > byteLimit)
                      throw new IOException("Cache storage budget exceeded");
                  }

                  public void write(int value) throws IOException {
                    reserve(1);
                    data.write(value);
                  }

                  public void write(byte[] bytes, int offset, int length) throws IOException {
                    reserve(length);
                    data.write(bytes, offset, length);
                  }
                }),
            row);
        long length = data.length() - start;
        int checksum = crc(data, start, length, checksumBuffer);
        index.seek(indexStart);
        index.writeInt(blockNumber);
        index.writeLong(start);
        index.writeLong(length);
        index.writeInt(checksum);
        count++;
      } catch (Exception e) {
        if (start >= 0)
          try {
            data.setLength(start);
            if (indexStart >= 0) index.setLength(indexStart);
          } catch (IOException rollback) {
            e.addSuppressed(rollback);
          }
        throw new IOException("Cannot cache row " + count, e);
      }
    }

    public long size() {
      return count;
    }

    public void close() throws IOException {
      try {
        data.close();
      } finally {
        index.close();
      }
    }
  }
}
