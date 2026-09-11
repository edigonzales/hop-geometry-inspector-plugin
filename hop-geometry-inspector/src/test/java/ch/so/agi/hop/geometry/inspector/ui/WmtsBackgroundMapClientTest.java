package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig;
import ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig.ServiceType;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.*;

class WmtsBackgroundMapClientTest {
  HttpServer server;
  ExecutorService httpWorkers;
  String base;
  boolean rest = true;
  String xmlOverride;
  int matrixColumns = 2;
  boolean noDefault;
  volatile boolean failTile;
  volatile CountDownLatch gate;
  volatile CountDownLatch entered;
  final AtomicInteger calls = new AtomicInteger();
  final AtomicInteger active = new AtomicInteger();
  final AtomicInteger maximum = new AtomicInteger();
  final List<String> requests = new CopyOnWriteArrayList<>();
  final List<WmtsBackgroundMapClient> clients = new ArrayList<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpWorkers = Executors.newCachedThreadPool();
    server.setExecutor(httpWorkers);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/caps",
        exchange -> {
          requests.add(exchange.getRequestURI().toString());
          byte[] bytes =
              (xmlOverride == null ? capabilities() : xmlOverride)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/xml");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.createContext(
        "/tiles",
        exchange -> {
          requests.add(exchange.getRequestURI().toString());
          calls.incrementAndGet();
          maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
          try {
            if (entered != null) entered.countDown();
            if (gate != null) gate.await(5, TimeUnit.SECONDS);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            int row, col;
            if (query == null) {
              String[] parts = path.split("/");
              row = Integer.parseInt(parts[parts.length - 2]);
              col = Integer.parseInt(parts[parts.length - 1]);
            } else {
              Map<String, String> params = new HashMap<>();
              for (String item : query.split("&")) {
                String[] pair = item.split("=", 2);
                params.put(
                    pair[0].toLowerCase(Locale.ROOT),
                    java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
              }
              row = Integer.parseInt(params.get("tilerow"));
              col = Integer.parseInt(params.get("tilecol"));
            }
            if (failTile && row == 0 && col == 0) {
              exchange.sendResponseHeaders(404, -1);
              return;
            }
            BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            int[] colors = {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00};
            int color = colors[(row * 2 + col) % colors.length];
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) tile.setRGB(x, y, color);
            var data = new ByteArrayOutputStream();
            ImageIO.write(tile, "png", data);
            byte[] bytes = data.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
              output.write(bytes);
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            active.decrementAndGet();
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    if (gate != null) gate.countDown();
    clients.forEach(WmtsBackgroundMapClient::close);
    server.stop(0);
    httpWorkers.shutdownNow();
  }

  WmtsBackgroundMapClient client() {
    var client = new WmtsBackgroundMapClient(config());
    clients.add(client);
    return client;
  }

  GeometryInspectorBackgroundMapConfig config() {
    return new GeometryInspectorBackgroundMapConfig(
        base + "/caps",
        "base",
        "",
        "image/png",
        "1.0.0",
        true,
        true,
        ServiceType.WMTS,
        "",
        Map.of());
  }

  BackgroundRenderRequest request(
      double minX, double maxX, double minY, double maxY, int width, int height, int zoom)
      throws Exception {
    return BackgroundRenderRequest.create(
        new ReferencedEnvelope(minX, maxX, minY, maxY, CRS.decode("EPSG:3857", true)),
        width,
        height,
        zoom,
        96,
        3857,
        1);
  }

  int pixel(BackgroundRenderResult result, int x, int y) {
    return result.raster().argbPixels()[y * result.raster().pixelWidth() + x];
  }

  @Test
  void restUsesDefaultsAndClipsTilesToViewportWithoutChangingLocation() throws Exception {
    var client = client();
    var result = client.render(request(128, 384, 128, 384, 256, 256, 100));
    assertThat(result.complete()).isTrue();
    assertThat(pixel(result, 20, 20)).isEqualTo(0xffff0000);
    assertThat(pixel(result, 230, 20)).isEqualTo(0xff00ff00);
    assertThat(pixel(result, 20, 230)).isEqualTo(0xff0000ff);
    assertThat(pixel(result, 230, 230)).isEqualTo(0xffffff00);
    assertThat(result.resolvedConfig().tileMatrixSet()).isEqualTo("native-grid");
    assertThat(result.resolvedConfig().dimensions()).containsEntry("Time", "current");
    assertThat(requests).anyMatch(r -> r.equals("/caps"));
    assertThat(requests).anyMatch(r -> r.contains("/tiles/current/fine%3A1/"));
    assertThat(calls).hasValue(4);
    client.render(request(140, 396, 128, 384, 256, 256, 100));
    assertThat(calls).hasValue(4);
  }

  @Test
  void kvpUsesAdvertisedEndpointAndDimensions() throws Exception {
    rest = false;
    var result = client().render(request(0, 256, 256, 512, 256, 256, 100));
    assertThat(result.complete()).isTrue();
    assertThat(requests)
        .anyMatch(
            r ->
                r.contains("Time=current")
                    && r.contains("TileMatrix=fine%3A1")
                    && r.contains("Request=GetTile")
                    && r.contains("token=abc"));
  }

  @Test
  void hidpiAndFractionalZoomHaveNoTransparentSeams() throws Exception {
    var client = client();
    for (int zoom : new int[] {100, 150, 200}) {
      var result = client.render(request(127, 385, 121, 391, 301, 315, zoom));
      assertThat(result.raster().pixelWidth())
          .isEqualTo(GeometryInspectorViewportMath.toPixelSize(301, zoom));
      for (int color : result.raster().argbPixels()) assertThat(color >>> 24).isEqualTo(255);
    }
  }

  @Test
  void missingTileProducesUncachedPartialFrameAndRetries() throws Exception {
    failTile = true;
    var client = client();
    var request = request(0, 512, 0, 512, 512, 512, 100);
    var partial = client.render(request);
    assertThat(partial.complete()).isFalse();
    assertThat(pixel(partial, 20, 20)).isZero();
    assertThat(pixel(partial, 400, 400)).isEqualTo(0xffffff00);
    failTile = false;
    client.resetInitialization();
    assertThat(client.render(request).complete()).isTrue();
    assertThat(calls).hasValue(5);
  }

  @Test
  void rejectsUnmatchedCrsBeforeFetchingTiles() throws Exception {
    var request =
        BackgroundRenderRequest.create(
            new ReferencedEnvelope(
                2600000, 2600100, 1200000, 1200100, CRS.decode("EPSG:2056", true)),
            100,
            100,
            100,
            96,
            2056,
            1);
    assertThatThrownBy(() -> client().render(request))
        .hasMessageContaining("no matching TileMatrixSet");
    assertThat(calls).hasValue(0);
  }

  @Test
  void cancellationReleasesRenderWorkerAndReusesRunningTiles() throws Exception {
    gate = new CountDownLatch(1);
    entered = new CountDownLatch(4);
    var client = client();
    var request = request(0, 512, 0, 512, 512, 512, 100);
    ExecutorService renders = Executors.newSingleThreadExecutor();
    try {
      Future<?> old = renders.submit(() -> client.render(request));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      client.cancelPending();
      assertThatThrownBy(() -> old.get(1, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancellationException.class);
      Future<BackgroundRenderResult> next = renders.submit(() -> client.render(request));
      gate.countDown();
      assertThat(next.get(5, TimeUnit.SECONDS).complete()).isTrue();
      assertThat(calls).hasValue(4);
      assertThat(maximum.get()).isLessThanOrEqualTo(4);
    } finally {
      renders.shutdownNow();
    }
  }

  @Test
  void missingDimensionDefaultRequiresValueAndOverrideIsUsed() throws Exception {
    noDefault = true;
    var request = request(0, 256, 256, 512, 256, 256, 100);
    assertThatThrownBy(() -> client().render(request))
        .hasMessageContaining("dimension requires a value: Time");
    var c = config();
    var client =
        new WmtsBackgroundMapClient(
            new GeometryInspectorBackgroundMapConfig(
                c.serviceUrl(),
                c.layerNames(),
                c.styleName(),
                c.imageFormat(),
                c.version(),
                true,
                true,
                ServiceType.WMTS,
                "",
                Map.of("Time", "current")));
    clients.add(client);
    assertThat(client.render(request).complete()).isTrue();
  }

  @Test
  void invalidCapabilitiesFailWithoutTileRequests() {
    xmlOverride = "<unexpected/>";
    assertThatThrownBy(() -> WmtsCatalog.load(base + "/caps")).isInstanceOf(Exception.class);
    assertThat(calls).hasValue(0);
  }

  @Test
  void enforcesLruByteBudget() throws Exception {
    var client = new WmtsBackgroundMapClient(config(), 2L * 256 * 256 * 4);
    clients.add(client);
    client.render(request(0, 512, 0, 512, 512, 512, 100));
    assertThat(calls).hasValue(4);
    client.render(request(0, 512, 0, 512, 512, 512, 100));
    assertThat(calls.get()).isGreaterThanOrEqualTo(6);
  }

  @Test
  void discardsObsoleteQueuedTilesAndClosesDuringDownloads() throws Exception {
    matrixColumns = 8;
    gate = new CountDownLatch(1);
    entered = new CountDownLatch(4);
    var client = client();
    var wide = request(0, 2048, 256, 512, 2048, 256, 100);
    ExecutorService renders = Executors.newSingleThreadExecutor();
    try {
      Future<?> old = renders.submit(() -> client.render(wide));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      client.cancelPending();
      assertThatThrownBy(() -> old.get(1, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancellationException.class);
      var narrow = request(1792, 2048, 256, 512, 256, 256, 100);
      Future<?> next = renders.submit(() -> client.render(narrow));
      gate.countDown();
      next.get(5, TimeUnit.SECONDS);
      assertThat(calls).hasValue(5);
      assertThat(requests)
          .noneMatch(r -> r.endsWith("/0/4") || r.endsWith("/0/5") || r.endsWith("/0/6"));
      client.close();
      assertThatThrownBy(() -> client.render(narrow)).isInstanceOf(CancellationException.class);
    } finally {
      renders.shutdownNow();
    }
  }

  @Test
  void obsoleteRevisionDoesNotStartEvenAfterCancellationHasAlreadyOccurred() throws Exception {
    var client = client();
    var obsolete = request(0, 256, 256, 512, 256, 256, 100).withValidity(() -> false);
    client.cancelPending();
    assertThatThrownBy(() -> client.render(obsolete)).isInstanceOf(CancellationException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void closeReturnsWhileNetworkIsBlockedAndPreventsPublishing() throws Exception {
    gate = new CountDownLatch(1);
    entered = new CountDownLatch(1);
    var client = client();
    var request = request(0, 256, 256, 512, 256, 256, 100);
    ExecutorService renders = Executors.newSingleThreadExecutor();
    try {
      var pending = renders.submit(() -> client.render(request));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      client.close();
      assertThatThrownBy(() -> pending.get(1, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancellationException.class);
      assertThat(client.cacheConfig(3857)).isNull();
    } finally {
      renders.shutdownNow();
    }
  }

  String capabilities() {
    String resource =
        rest
            ? "<ResourceURL format=\"image/png\" resourceType=\"tile\" template=\""
                + base
                + "/tiles/{Time}/{TileMatrix}/{TileRow}/{TileCol}\"/>"
            : "";
    String document;
    try (var stream = getClass().getResourceAsStream("/wmts/capabilities.xml")) {
      document =
          new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .formatted(base, resource);
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }

    document =
        document.replace(
            "<MatrixWidth>2</MatrixWidth>", "<MatrixWidth>" + matrixColumns + "</MatrixWidth>");
    return noDefault ? document.replace("<Default>current</Default>", "") : document;
  }
}
