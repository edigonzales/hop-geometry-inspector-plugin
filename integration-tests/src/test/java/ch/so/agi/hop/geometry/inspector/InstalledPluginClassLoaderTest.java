package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.gui.plugin.GuiPluginType;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.JarCache;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.row.value.ValueMetaPluginType;
import org.apache.hop.ui.hopgui.HopGuiEnvironment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class InstalledPluginClassLoaderTest {

  private static final String PLUGINS_DIR_PROPERTY = "geometry.inspector.plugins.dir";
  private static final String GEOMETRY_VALUE_META_PLUGIN_ID = "43663879";
  private static final String SHARED_GROUP = "sogeo-geometry";
  private static final String INSPECTOR_GUI_CLASS =
      "ch.so.agi.hop.geometry.inspector.GeometryInspectorGuiPlugin";
  private static final String VALUE_META_GEOMETRY_CLASS =
      "com.atolcd.hop.core.row.value.ValueMetaGeometry";
  private static final String JTS_GEOMETRY_CLASS = "org.locationtech.jts.geom.Geometry";
  private static final String CIRCULAR_STRING_CLASS =
      "com.atolcd.hop.gis.geometry.curve.CircularString";

  @Test
  void installedPluginsShareTheGeometryRuntimeClassLoader() throws Exception {
    String configuredPluginsDir = System.getProperty(PLUGINS_DIR_PROPERTY);
    Assumptions.assumeTrue(
        configuredPluginsDir != null && !configuredPluginsDir.isBlank(),
        () -> "Set -D" + PLUGINS_DIR_PROPERTY + " to run the installed-plugin integration test");

    Path pluginsDir = Path.of(configuredPluginsDir).toAbsolutePath().normalize();
    Path geometryTypeDir = pluginsDir.resolve("misc/hop-geometry-type");
    Path inspectorDir = pluginsDir.resolve("misc/hop-geometry-inspector");

    assertThat(Files.isDirectory(geometryTypeDir)).as("installed geometry-type plugin").isTrue();
    assertThat(Files.isDirectory(inspectorDir)).as("installed inspector plugin").isTrue();

    // Prove the test harness itself cannot see either plugin. The only path to these classes must
    // be Hop's plugin scan of the installed directory tree.
    ClassLoader harnessLoader = InstalledPluginClassLoaderTest.class.getClassLoader();
    assertThatThrownBy(() -> Class.forName(INSPECTOR_GUI_CLASS, false, harnessLoader))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() -> Class.forName(VALUE_META_GEOMETRY_CLASS, false, harnessLoader))
        .isInstanceOf(ClassNotFoundException.class);

    String previousPluginFolders = System.getProperty(Const.HOP_PLUGIN_BASE_FOLDERS);
    try {
      System.setProperty(Const.HOP_PLUGIN_BASE_FOLDERS, pluginsDir.toString());

      HopEnvironment.reset();
      JarCache.getInstance().clear();

      // This scans ValueMeta and ExtensionPoint plugins from the installed tree. The Inspector
      // bootstrap is invoked by HopEnvironmentAfterInit and installs the GuiPluginType listener.
      HopEnvironment.init();

      // Match normal GUI startup order, but only initialize the GUI plugin type needed here.
      // This scans the installed Inspector and then asks PluginRegistry for its runtime loader.
      HopGuiEnvironment.init(List.of(GuiPluginType.getInstance()));

      PluginRegistry registry = PluginRegistry.getInstance();
      IPlugin geometryPlugin =
          registry.findPluginWithId(ValueMetaPluginType.class, GEOMETRY_VALUE_META_PLUGIN_ID);
      IPlugin inspectorPlugin = findInspectorPlugin(registry);

      assertThat(geometryPlugin).as("ValueMetaGeometry plugin").isNotNull();
      assertThat(inspectorPlugin).as("Geometry Inspector GUI plugin").isNotNull();
      assertThat(geometryPlugin.getClassLoaderGroup()).isEqualTo(SHARED_GROUP);
      assertThat(inspectorPlugin.getClassLoaderGroup()).isEqualTo(SHARED_GROUP);

      ClassLoader geometryLoader = registry.getClassLoader(geometryPlugin);
      ClassLoader inspectorLoader = registry.getClassLoader(inspectorPlugin);

      assertThat(inspectorLoader).isSameAs(geometryLoader);

      Class<?> wmtsServer = inspectorLoader.loadClass("org.geotools.ows.wmts.WebMapTileServer");
      Class<?> wmtsParser =
          inspectorLoader.loadClass("org.geotools.ows.wmts.response.WMTSGetCapabilitiesResponse");
      assertThat(wmtsServer.getClassLoader()).isSameAs(inspectorLoader);
      assertThat(wmtsParser.getClassLoader()).isSameAs(inspectorLoader);
      assertWmtsCapabilitiesParse(inspectorLoader, wmtsParser);

      Class<?> valueMetaGeometry = geometryLoader.loadClass(VALUE_META_GEOMETRY_CLASS);
      Class<?> inspectorGui = inspectorLoader.loadClass(INSPECTOR_GUI_CLASS);
      Class<?> geometryFromGeometryLoader = geometryLoader.loadClass(JTS_GEOMETRY_CLASS);
      Class<?> geometryFromInspectorLoader = inspectorLoader.loadClass(JTS_GEOMETRY_CLASS);
      Class<?> curveFromGeometryLoader = geometryLoader.loadClass(CIRCULAR_STRING_CLASS);
      Class<?> curveFromInspectorLoader = inspectorLoader.loadClass(CIRCULAR_STRING_CLASS);

      assertThat(valueMetaGeometry.getClassLoader()).isSameAs(geometryLoader);
      assertThat(inspectorGui.getClassLoader()).isSameAs(geometryLoader);
      assertThat(geometryFromInspectorLoader).isSameAs(geometryFromGeometryLoader);
      assertThat(curveFromInspectorLoader).isSameAs(curveFromGeometryLoader);
      verifyGeometryCacheRoundTrip(inspectorLoader);
      verifyExamples(inspectorLoader);
      assertThat(geometryFromGeometryLoader.isAssignableFrom(curveFromGeometryLoader)).isTrue();
    } finally {
      HopEnvironment.reset();
      JarCache.getInstance().clear();
      if (previousPluginFolders == null) {
        System.clearProperty(Const.HOP_PLUGIN_BASE_FOLDERS);
      } else {
        System.setProperty(Const.HOP_PLUGIN_BASE_FOLDERS, previousPluginFolders);
      }
    }
  }

  private void verifyGeometryCacheRoundTrip(ClassLoader loader) throws Exception {
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(loader);
    Path directory = Files.createTempDirectory("inspector-geometry-roundtrip-");
    try {
      var meta = new org.apache.hop.core.row.RowMeta();
      meta.addValueMeta(
          (org.apache.hop.core.row.IValueMeta)
              loader
                  .loadClass(VALUE_META_GEOMETRY_CLASS)
                  .getConstructor(String.class)
                  .newInstance("geometry"));
      Object reader =
          loader.loadClass("org.locationtech.jts.io.WKTReader").getConstructor().newInstance();
      Object geometry =
          reader
              .getClass()
              .getMethod("read", String.class)
              .invoke(reader, "POINT Z (2600000 1200000 42)");
      geometry.getClass().getMethod("setSRID", int.class).invoke(geometry, 2056);
      Class<?> writerType =
          loader.loadClass("ch.so.agi.hop.geometry.inspector.data.DiskRowStore$Writer");
      try (var writer =
          (AutoCloseable)
              writerType
                  .getConstructor(Path.class, long.class)
                  .newInstance(directory, 1_000_000L)) {
        writerType
            .getMethod("append", org.apache.hop.core.row.IRowMeta.class, Object[].class)
            .invoke(writer, meta, new Object[] {geometry});
      }
      Class<?> storeType = loader.loadClass("ch.so.agi.hop.geometry.inspector.data.DiskRowStore");
      try (var store =
          (AutoCloseable) storeType.getConstructor(Path.class).newInstance(directory)) {
        Object restored = ((Object[]) storeType.getMethod("row", long.class).invoke(store, 0L))[0];
        assertThat(restored.getClass().getClassLoader()).isSameAs(loader);
        assertThat(restored.getClass().getMethod("getSRID").invoke(restored)).isEqualTo(2056);
        Object coordinate = restored.getClass().getMethod("getCoordinate").invoke(restored);
        assertThat(coordinate.getClass().getMethod("getZ").invoke(coordinate)).isEqualTo(42d);
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
      try (var files = Files.walk(directory)) {
        for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.delete(path);
      }
    }
  }

  private void verifyExamples(ClassLoader loader) throws Exception {
    String configured = System.getProperty("geometry.inspector.examples.dir");
    if (configured == null) return;
    Path examples = Path.of(configured);
    Path expected = examples.getParent().resolve("e2e/expected/examples.json");
    var counts =
        (org.json.simple.JSONObject)
            new org.json.simple.parser.JSONParser().parse(Files.readString(expected));
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(loader);
    try {
      Class<?> serviceType =
          loader.loadClass("ch.so.agi.hop.geometry.inspector.data.CaptureService");
      Object service = serviceType.getConstructor().newInstance();
      Class<?> optionsType =
          loader.loadClass("ch.so.agi.hop.geometry.inspector.model.GeometryInspectorOptions");
      Class<?> modeType = loader.loadClass("ch.so.agi.hop.geometry.inspector.model.SamplingMode");
      Class<?> sideType =
          loader.loadClass("ch.so.agi.hop.geometry.inspector.model.GeometryInspectionSide");
      Class<?> repositoryType =
          loader.loadClass("ch.so.agi.hop.geometry.inspector.data.CacheRepository");
      Object options =
          optionsType.getConstructors()[0].newInstance(
              1000,
              modeType.getField("FIRST").get(null),
              sideType.getField("OUTPUT").get(null),
              "",
              java.time.Duration.ofSeconds(15));
      var variables = new org.apache.hop.core.variables.Variables();
      var provider = new org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider();
      try (var paths = Files.walk(examples)) {
        for (Path path : paths.filter(p -> p.toString().endsWith(".hpl")).sorted().toList()) {
          var pipeline = new org.apache.hop.pipeline.PipelineMeta();
          pipeline.loadXml(
              org.apache.hop.core.xml.XmlHandler.loadXmlString(
                  Files.readString(path), org.apache.hop.pipeline.PipelineMeta.XML_TAG),
              path.toString(),
              provider,
              variables);
          var expectedCounts =
              (org.json.simple.JSONObject) counts.get(path.getParent().getFileName().toString());
          List<?> available =
              (List<?>)
                  serviceType
                      .getMethod(
                          "streams",
                          org.apache.hop.pipeline.PipelineMeta.class,
                          org.apache.hop.core.variables.IVariables.class)
                      .invoke(null, pipeline, variables);
          java.util.List<Object> selected = new java.util.ArrayList<>();
          for (Object stream : available) {
            Class<?> type = stream.getClass();
            if (expectedCounts.containsKey(type.getMethod("transform").invoke(stream))
                && type.getMethod("direction").invoke(stream).toString().equals("OUTPUT")
                && type.getMethod("kind").invoke(stream).toString().equals("MAIN"))
              selected.add(stream);
          }
          for (Object stream : available) {
            var type = stream.getClass();
            String kind = type.getMethod("kind").invoke(stream).toString();
            if (type.getMethod("direction").invoke(stream).toString().equals("OUTPUT")
                && !kind.equals("MAIN")
                && expectedCounts.containsKey(type.getMethod("peer").invoke(stream)))
              selected.add(stream);
          }
          var reference = referenceRows(pipeline, variables, provider, expectedCounts.keySet());
          List<?> results =
              (List<?>)
                  serviceType
                      .getMethod(
                          "capture",
                          org.apache.hop.pipeline.PipelineMeta.class,
                          org.apache.hop.core.variables.IVariables.class,
                          org.apache.hop.metadata.api.IHopMetadataProvider.class,
                          List.class,
                          optionsType,
                          repositoryType,
                          java.util.function.BooleanSupplier.class)
                      .invoke(
                          service,
                          pipeline,
                          variables,
                          provider,
                          selected,
                          options,
                          null,
                          (java.util.function.BooleanSupplier) () -> false);
          assertThat(results).as(path.toString()).hasSize(selected.size());
          if (path.getParent().getFileName().toString().startsWith("replay"))
            verifyInstalledReplay(
                loader,
                serviceType,
                service,
                optionsType,
                modeType,
                sideType,
                repositoryType,
                pipeline,
                variables,
                provider,
                selected,
                ((Number) expectedCounts.get("Result")).longValue());
          for (Object result : results) {
            Object stream = result.getClass().getMethod("stream").invoke(result);
            String name = (String) stream.getClass().getMethod("transform").invoke(stream);
            if (!stream.getClass().getMethod("kind").invoke(stream).toString().equals("MAIN"))
              name = (String) stream.getClass().getMethod("peer").invoke(stream);
            Object rows = result.getClass().getMethod("rows").invoke(result);
            Class<?> storeType = loader.loadClass("ch.so.agi.hop.geometry.inspector.data.RowStore");
            assertThat(storeType.getMethod("size").invoke(rows))
                .as(path + " / " + name)
                .isEqualTo(((Number) expectedCounts.get(name)).longValue());
            assertThat(result.getClass().getMethod("completion").invoke(result).toString())
                .isEqualTo("COMPLETE");
            java.util.List<String> observed = new java.util.ArrayList<>();
            for (long ordinal = 0;
                ordinal < ((Number) storeType.getMethod("size").invoke(rows)).longValue();
                ordinal++)
              observed.add(
                  java.util.Arrays.deepToString(
                      java.util.Arrays.copyOf(
                          (Object[]) storeType.getMethod("row", long.class).invoke(rows, ordinal),
                          ((org.apache.hop.core.row.IRowMeta)
                                  storeType.getMethod("rowMeta").invoke(rows))
                              .size())));
            assertThat(observed)
                .as("Uninstrumented reference for " + name)
                .containsExactlyInAnyOrderElementsOf(reference.get(name));
            ((AutoCloseable) rows).close();
          }
        }
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private java.util.Map<String, java.util.List<String>> referenceRows(
      org.apache.hop.pipeline.PipelineMeta pipeline,
      org.apache.hop.core.variables.IVariables variables,
      org.apache.hop.metadata.api.IHopMetadataProvider provider,
      java.util.Set<?> names)
      throws Exception {
    var engine =
        new org.apache.hop.pipeline.engines.local.LocalPipelineEngine(pipeline, variables, null);
    engine.setMetadataProvider(provider);
    engine.prepareExecution();
    java.util.Map<String, java.util.List<String>> reference =
        new java.util.concurrent.ConcurrentHashMap<>();
    for (var combi : engine.getTransforms())
      if (names.contains(combi.transformName)) {
        var values = new java.util.concurrent.CopyOnWriteArrayList<String>();
        reference.put(combi.transformName, values);
        combi.transform.addRowListener(
            new org.apache.hop.pipeline.transform.RowAdapter() {
              @Override
              public void rowWrittenEvent(org.apache.hop.core.row.IRowMeta meta, Object[] row) {
                values.add(
                    java.util.Arrays.deepToString(java.util.Arrays.copyOf(row, meta.size())));
              }
            });
      }
    engine.startThreads();
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
    while (engine.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
    if (engine.isRunning()) {
      engine.stopAll();
      engine.waitUntilFinished();
      throw new AssertionError("Reference timeout");
    }
    engine.waitUntilFinished();
    assertThat(engine.getErrors()).isZero();
    return reference;
  }

  private void verifyInstalledReplay(
      ClassLoader loader,
      Class<?> serviceType,
      Object service,
      Class<?> optionsType,
      Class<?> modeType,
      Class<?> sideType,
      Class<?> repositoryType,
      org.apache.hop.pipeline.PipelineMeta pipeline,
      org.apache.hop.core.variables.IVariables variables,
      org.apache.hop.metadata.api.IHopMetadataProvider provider,
      List<?> selected,
      long expectedRows)
      throws Exception {
    Path directory = Files.createTempDirectory("inspector-replay-");
    java.util.List<AutoCloseable> stores = new java.util.ArrayList<>();
    try {
      Object repository =
          repositoryType
              .getConstructor(Path.class, long.class, java.time.Duration.class)
              .newInstance(directory, 10_000_000L, java.time.Duration.ofDays(7));
      Object options =
          optionsType.getConstructors()[0].newInstance(
              Integer.MAX_VALUE,
              modeType.getField("FIRST").get(null),
              sideType.getField("OUTPUT").get(null),
              "",
              java.time.Duration.ofSeconds(15));
      var capture =
          serviceType.getMethod(
              "capture",
              org.apache.hop.pipeline.PipelineMeta.class,
              org.apache.hop.core.variables.IVariables.class,
              org.apache.hop.metadata.api.IHopMetadataProvider.class,
              List.class,
              optionsType,
              repositoryType,
              java.util.function.BooleanSupplier.class);
      List<?> recorded =
          (List<?>)
              capture.invoke(
                  service,
                  pipeline,
                  variables,
                  provider,
                  selected,
                  options,
                  repository,
                  (java.util.function.BooleanSupplier) () -> false);
      for (Object result : recorded)
        stores.add((AutoCloseable) result.getClass().getMethod("rows").invoke(result));
      Class<?> plannerType =
          loader.loadClass("ch.so.agi.hop.geometry.inspector.data.ReplayPlanner");
      Class<?> replayMode =
          loader.loadClass("ch.so.agi.hop.geometry.inspector.data.ReplayPlanner$Mode");
      Object planner = plannerType.getConstructor().newInstance();
      Object plan =
          plannerType
              .getMethod(
                  "plan",
                  org.apache.hop.pipeline.PipelineMeta.class,
                  org.apache.hop.core.variables.IVariables.class,
                  org.apache.hop.metadata.api.IHopMetadataProvider.class,
                  replayMode,
                  String.class,
                  String.class,
                  List.class)
              .invoke(
                  planner,
                  pipeline,
                  variables,
                  provider,
                  replayMode.getField("FROM").get(null),
                  "Result",
                  null,
                  recorded);
      var clone =
          (org.apache.hop.pipeline.PipelineMeta) plan.getClass().getMethod("pipeline").invoke(plan);
      List<?> cacheInputs = (List<?>) plan.getClass().getMethod("inputs").invoke(plan);
      for (Object input : cacheInputs) {
        Object stream = input.getClass().getMethod("stream").invoke(input);
        String name = (String) stream.getClass().getMethod("transform").invoke(stream);
        assertThat(clone.findTransform(name).getTransform().getClass().getSimpleName())
            .isEqualTo("ReplaySourceMeta");
      }
      assertThat(pipeline.findTransform("Source").getTransformPluginId()).isEqualTo("DataGrid");
      List<?> target =
          selected.stream()
              .filter(
                  stream -> {
                    try {
                      return stream
                          .getClass()
                          .getMethod("transform")
                          .invoke(stream)
                          .equals("Result");
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .toList();
      List<?> replayed =
          (List<?>)
              serviceType
                  .getMethod(
                      "execute",
                      org.apache.hop.pipeline.PipelineMeta.class,
                      org.apache.hop.pipeline.PipelineMeta.class,
                      org.apache.hop.core.variables.IVariables.class,
                      org.apache.hop.metadata.api.IHopMetadataProvider.class,
                      List.class,
                      optionsType,
                      repositoryType,
                      java.util.function.BooleanSupplier.class)
                  .invoke(
                      service,
                      pipeline,
                      clone,
                      variables,
                      provider,
                      target,
                      options,
                      repository,
                      (java.util.function.BooleanSupplier) () -> false);
      Class<?> storeType = loader.loadClass("ch.so.agi.hop.geometry.inspector.data.RowStore");
      Object rows = replayed.getFirst().getClass().getMethod("rows").invoke(replayed.getFirst());
      stores.add((AutoCloseable) rows);
      assertThat(storeType.getMethod("size").invoke(rows)).isEqualTo(expectedRows);
      Object originalResult =
          recorded.stream()
              .filter(
                  r -> {
                    try {
                      Object stream = r.getClass().getMethod("stream").invoke(r);
                      return stream
                          .getClass()
                          .getMethod("transform")
                          .invoke(stream)
                          .equals("Result");
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .findFirst()
              .orElseThrow();
      Object originalRows = originalResult.getClass().getMethod("rows").invoke(originalResult);
      java.util.List<String> actual = new java.util.ArrayList<>(),
          expected = new java.util.ArrayList<>();
      for (long i = 0; i < expectedRows; i++) {
        actual.add(
            java.util.Arrays.deepToString(
                (Object[]) storeType.getMethod("row", long.class).invoke(rows, i)));
        expected.add(
            java.util.Arrays.deepToString(
                (Object[]) storeType.getMethod("row", long.class).invoke(originalRows, i)));
      }
      assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    } finally {
      for (var store : stores) store.close();
      try (var files = Files.walk(directory)) {
        for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.delete(path);
      }
    }
  }

  private void assertWmtsCapabilitiesParse(ClassLoader loader, Class<?> parser) throws Exception {
    byte[] xml;
    try (var stream = getClass().getResourceAsStream("/wmts/capabilities.xml")) {
      xml =
          new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .formatted("http://127.0.0.1", "")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    Class<?> responseType = loader.loadClass("org.geotools.http.HTTPResponse");
    Object response =
        java.lang.reflect.Proxy.newProxyInstance(
            loader,
            new Class<?>[] {responseType},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getResponseStream" -> new java.io.ByteArrayInputStream(xml);
                  case "getContentType" -> "application/xml";
                  case "getResponseCharset" -> "UTF-8";
                  default -> null;
                });
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    try {
      thread.setContextClassLoader(loader);
      Object parsed = parser.getConstructor(responseType).newInstance(response);
      Object capabilities = parser.getMethod("getCapabilities").invoke(parsed);
      List<?> layers =
          (List<?>) capabilities.getClass().getMethod("getLayerList").invoke(capabilities);
      assertThat(layers).hasSize(1);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  private IPlugin findInspectorPlugin(PluginRegistry registry) {
    return registry.getPlugins(GuiPluginType.class).stream()
        .filter(plugin -> plugin.getClassMap().containsValue(INSPECTOR_GUI_CLASS))
        .findFirst()
        .orElse(null);
  }
}
