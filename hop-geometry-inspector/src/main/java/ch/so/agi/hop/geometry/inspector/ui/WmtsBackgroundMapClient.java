package ch.so.agi.hop.geometry.inspector.ui;

import ch.so.agi.hop.geometry.inspector.GeometryInspectorClassLoaderSupport;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;

/** A viewer-owned WMTS session. Only the render worker assembles frames. */
final class WmtsBackgroundMapClient implements BackgroundMapClient {
  static final long CACHE_BYTES = 64L * 1024 * 1024;
  private final GeometryInspectorBackgroundMapConfig config;
  private final ThreadPoolExecutor downloads;
  private final AtomicLong generation = new AtomicLong();
  private final Map<TileKey, Fetch> pending = new HashMap<>();
  private final LinkedHashMap<TileKey, BufferedImage> cache = new LinkedHashMap<>(32, .75f, true);
  private long cacheBytes;
  private final long cacheLimit;
  private volatile WmtsCatalog catalog;

  private record Resolved(Integer srid, GeometryInspectorBackgroundMapConfig config) {}

  private volatile Resolved resolved;
  private volatile boolean closed;

  record TileKey(GeometryInspectorBackgroundMapConfig config, String matrix, int row, int col) {}

  WmtsBackgroundMapClient(GeometryInspectorBackgroundMapConfig config) {
    this(config, CACHE_BYTES);
  }

  WmtsBackgroundMapClient(GeometryInspectorBackgroundMapConfig config, long cacheLimit) {
    this.config = config;
    this.cacheLimit = cacheLimit;
    downloads =
        new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            GeometryInspectorClassLoaderSupport.newPluginContextThreadFactory(
                r -> {
                  Thread thread = new Thread(r, "geometry-inspector-wmts");
                  thread.setDaemon(true);
                  return thread;
                }));
  }

  @Override
  public GeometryInspectorBackgroundMapConfig cacheConfig(Integer srid) {
    Resolved current = resolved;
    return current != null && Objects.equals(current.srid(), srid) ? current.config() : null;
  }

  @Override
  public synchronized void resetInitialization() {
    cancelPending();
    catalog = null;
    resolved = null;
  }

  @Override
  public synchronized void cancelPending() {
    generation.incrementAndGet();
    for (Runnable queued : downloads.getQueue().toArray(new Runnable[0])) {
      if (downloads.remove(queued)) ((Fetch) queued).cancel(false);
    }
  }

  private void checkCurrent(long token) {
    if (closed || token != generation.get() || Thread.currentThread().isInterrupted())
      throw new CancellationException("Superseded WMTS viewport");
  }

  private void checkCurrent(long token, BackgroundRenderRequest request) {
    checkCurrent(token);
    if (!request.current().getAsBoolean())
      throw new CancellationException("Superseded WMTS revision");
  }

  @Override
  public BackgroundRenderResult render(BackgroundRenderRequest request) throws Exception {
    long token = generation.get();
    checkCurrent(token, request);
    WmtsCatalog current = catalog;
    if (current == null) {
      current = WmtsCatalog.load(config.serviceUrl());
      synchronized (this) {
        checkCurrent(token, request);
        catalog = current;
      }
    }
    var effective = current.resolve(config, request.srid());
    synchronized (this) {
      checkCurrent(token, request);
      resolved = new Resolved(request.srid(), effective);
    }
    var set = current.capabilities.getMatrixSet(effective.tileMatrixSet());
    var tiles =
        WmtsTilePlanner.plan(
            set,
            current.layer(effective.layerNames()).getTileMatrixLinks().get(set.getIdentifier()),
            request);
    List<Future<BufferedImage>> images = new ArrayList<>();
    for (var tile : tiles) {
      checkCurrent(token, request);
      images.add(fetch(current, effective, tile, token));
    }
    BufferedImage output =
        new BufferedImage(request.pixelWidth(), request.pixelHeight(), BufferedImage.TYPE_INT_ARGB);
    var graphics = output.createGraphics();
    int missing = 0;
    try {
      graphics.setComposite(AlphaComposite.Src);
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      double scaleX = request.pixelWidth() / request.displayArea().getWidth();
      double scaleY = request.pixelHeight() / request.displayArea().getHeight();
      for (int i = 0; i < tiles.size(); i++) {
        BufferedImage image;
        try {
          while (true) {
            checkCurrent(token, request);
            try {
              image = images.get(i).get(25, TimeUnit.MILLISECONDS);
              break;
            } catch (TimeoutException waiting) {
              /* Recheck viewport even while old I/O is running. */
            }
          }
        } catch (ExecutionException failed) {
          missing++;
          continue;
        }
        var tile = tiles.get(i);
        AffineTransform transform = new AffineTransform();
        transform.translate(
            (tile.minX() - request.displayArea().getMinX()) * scaleX,
            (request.displayArea().getMaxY() - tile.maxY()) * scaleY);
        transform.scale(tile.resolution() * scaleX, tile.resolution() * scaleY);
        graphics.drawImage(image, transform, null);
      }
    } finally {
      graphics.dispose();
    }
    checkCurrent(token, request);
    return new BackgroundRenderResult(
        GeometryInspectorRasterData.fromBufferedImage(
            request.displayArea(),
            request.logicalWidth(),
            request.logicalHeight(),
            request.deviceZoom(),
            request.revision(),
            output),
        effective,
        missing == 0 ? "" : "WMTS background incomplete: " + missing + " tile(s) unavailable");
  }

  private synchronized Future<BufferedImage> fetch(
      WmtsCatalog current,
      GeometryInspectorBackgroundMapConfig effective,
      WmtsTilePlanner.Tile tile,
      long token)
      throws Exception {
    checkCurrent(token);
    TileKey key = new TileKey(effective, tile.matrix(), tile.row(), tile.col());
    BufferedImage hit = cache.get(key);
    if (hit != null) return CompletableFuture.completedFuture(hit);
    Fetch existing = pending.get(key);
    if (existing != null) return existing;
    var url = current.tileUrl(effective, tile.matrix(), tile.row(), tile.col());
    Fetch task =
        new Fetch(
            key,
            () -> {
              var response = WmtsCatalog.httpClient().get(url);
              try (var stream = response.getResponseStream()) {
                BufferedImage decoded = ImageIO.read(stream);
                if (decoded == null
                    || decoded.getWidth() != tile.width()
                    || decoded.getHeight() != tile.height())
                  throw new IllegalArgumentException("WMTS returned an invalid tile image");
                // Normalize to four bytes per pixel so the memory limit is predictable.
                BufferedImage image =
                    new BufferedImage(
                        decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
                var g = image.createGraphics();
                try {
                  g.setComposite(AlphaComposite.Src);
                  g.drawImage(decoded, 0, 0, null);
                } finally {
                  g.dispose();
                }
                remember(key, image);
                return image;
              } finally {
                response.dispose();
              }
            });
    pending.put(key, task);
    downloads.execute(task);
    return task;
  }

  private synchronized void remember(TileKey key, BufferedImage image) {
    if (closed) return;
    long size = 4L * image.getWidth() * image.getHeight();
    if (size > cacheLimit) return;
    BufferedImage previous = cache.put(key, image);
    if (previous != null) cacheBytes -= 4L * previous.getWidth() * previous.getHeight();
    cacheBytes += size;
    var iterator = cache.entrySet().iterator();
    while (cacheBytes > cacheLimit && iterator.hasNext()) {
      var evicted = iterator.next().getValue();
      cacheBytes -= 4L * evicted.getWidth() * evicted.getHeight();
      iterator.remove();
    }
  }

  private final class Fetch extends FutureTask<BufferedImage> {
    private final TileKey key;

    Fetch(TileKey key, Callable<BufferedImage> callable) {
      super(callable);
      this.key = key;
    }

    @Override
    protected void done() {
      synchronized (WmtsBackgroundMapClient.this) {
        pending.remove(key, this);
      }
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    cancelPending();
    downloads.shutdownNow();
    for (var task : List.copyOf(pending.values())) task.cancel(true);
    cache.clear();
    cacheBytes = 0;
    catalog = null;
    resolved = null;
  }
}
