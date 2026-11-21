package ch.so.agi.hop.geometry.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.action.GuiContextAction;
import org.apache.hop.core.gui.plugin.GuiPluginType;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.JarCache;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaPluginType;
import org.apache.hop.ui.hopgui.HopGuiEnvironment;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
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
  private static final String PLANAR_IMAGE_CLASS = "org.eclipse.imagen.PlanarImage";
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
    assertThatThrownBy(() -> Class.forName(PLANAR_IMAGE_CLASS, false, harnessLoader))
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
      assertImagenRegistriesAreUnique(inspectorLoader);

      Class<?> valueMetaGeometry = geometryLoader.loadClass(VALUE_META_GEOMETRY_CLASS);
      Class<?> inspectorGui = inspectorLoader.loadClass(INSPECTOR_GUI_CLASS);
      Class<?> geometryFromGeometryLoader = geometryLoader.loadClass(JTS_GEOMETRY_CLASS);
      Class<?> geometryFromInspectorLoader = inspectorLoader.loadClass(JTS_GEOMETRY_CLASS);
      Class<?> planarImageFromGeometryLoader = geometryLoader.loadClass(PLANAR_IMAGE_CLASS);
      Class<?> planarImageFromInspectorLoader = inspectorLoader.loadClass(PLANAR_IMAGE_CLASS);
      Class<?> curveFromGeometryLoader = geometryLoader.loadClass(CIRCULAR_STRING_CLASS);
      Class<?> curveFromInspectorLoader = inspectorLoader.loadClass(CIRCULAR_STRING_CLASS);

      assertThat(valueMetaGeometry.getClassLoader()).isSameAs(geometryLoader);
      assertThat(inspectorGui.getClassLoader()).isSameAs(geometryLoader);
      assertThat(geometryFromInspectorLoader).isSameAs(geometryFromGeometryLoader);
      assertThat(planarImageFromInspectorLoader).isSameAs(planarImageFromGeometryLoader);
      assertThat(planarImageFromGeometryLoader.getClassLoader()).isSameAs(geometryLoader);
      assertThat(curveFromInspectorLoader).isSameAs(curveFromGeometryLoader);
      assertThat(inspectorGui.getDeclaredMethods())
          .filteredOn(method -> method.isAnnotationPresent(GuiContextAction.class))
          .extracting(method -> method.getAnnotation(GuiContextAction.class).name())
          .containsExactly("Inspect data...");
      verifyTemporaryGeometryRows(inspectorLoader);
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

  private void assertImagenRegistriesAreUnique(ClassLoader loader) throws Exception {
    List<URL> resources =
        java.util.Collections.list(loader.getResources("META-INF/registryFile.imagen"));
    assertThat(resources).as("shared Imagen registry resources").isNotEmpty();
    Set<String> registrations = new HashSet<>();
    for (URL resource : resources) {
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
        for (String line : reader.lines().collect(Collectors.toList())) {
          String trimmed = line.trim();
          if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
          String[] fields = trimmed.split("\\s+");
          if ((fields[0].equals("descriptor") || fields[0].equals("rendered"))
              && !registrations.add(trimmed)) {
            throw new AssertionError(
                "Duplicate Imagen registration: " + trimmed + " from " + resource);
          }
        }
      }
    }
  }

  private void verifyTemporaryGeometryRows(ClassLoader loader) throws Exception {
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(loader);
    try {
      var meta = new RowMeta();
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

      Class<?> storeType = loader.loadClass("ch.so.agi.hop.geometry.inspector.data.DiskRowStore");
      Class<?> rowStore = loader.loadClass("ch.so.agi.hop.geometry.inspector.data.RowStore");
      var create = storeType.getDeclaredConstructor(IRowMeta.class, long.class);
      create.setAccessible(true);
      var append = storeType.getDeclaredMethod("append", Object[].class);
      append.setAccessible(true);
      try (var rows = (AutoCloseable) create.newInstance(meta, 1_000_000L)) {
        append.invoke(rows, (Object) new Object[] {geometry});
        Object restored = ((Object[]) rowStore.getMethod("row", long.class).invoke(rows, 0L))[0];
        assertThat(restored.getClass().getClassLoader()).isSameAs(loader);
        assertThat(restored.getClass().getMethod("getSRID").invoke(restored)).isEqualTo(2056);
        Object coordinate = restored.getClass().getMethod("getCoordinate").invoke(restored);
        assertThat(coordinate.getClass().getMethod("getZ").invoke(coordinate)).isEqualTo(42d);

        if (Boolean.getBoolean("geometryInspector.swtSmoke")) {
          append.invoke(rows, (Object) new Object[] {geometry});
          try (var comparison = (AutoCloseable) create.newInstance(meta, 1_000_000L)) {
            append.invoke(comparison, (Object) new Object[] {geometry});
            verifyInstalledViewer(loader, rowStore, rows, comparison, meta);
          }
        }
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  private void verifyInstalledViewer(
      ClassLoader loader, Class<?> rowStore, Object rows, Object comparison, IRowMeta meta)
      throws Exception {
    Class<?> rowList = loader.loadClass("ch.so.agi.hop.geometry.inspector.data.StoreRowList");
    Class<?> sample = loader.loadClass("ch.so.agi.hop.geometry.inspector.model.SamplingResult");
    Class<?> buildType =
        loader.loadClass("ch.so.agi.hop.geometry.inspector.model.GeometryBuildResult");
    Class<?> configType =
        loader.loadClass(
            "ch.so.agi.hop.geometry.inspector.model.GeometryInspectorBackgroundMapConfig");
    Class<?> builderType =
        loader.loadClass("ch.so.agi.hop.geometry.inspector.GeometryFeatureBuilder");
    Class<?> viewerType =
        loader.loadClass("ch.so.agi.hop.geometry.inspector.ui.GeometryInspectorSwtViewer");
    Object builder = builderType.getConstructor().newInstance();
    var sampleConstructor =
        sample.getConstructor(
            List.class, IRowMeta.class, boolean.class, String.class, String.class);
    var build =
        builderType.getMethod(
            "buildLimited",
            IRowMeta.class,
            List.class,
            String.class,
            int.class,
            Predicate.class,
            BooleanSupplier.class);
    Object firstRows = rowList.getConstructor(rowStore).newInstance(rows);
    Object comparisonRows = rowList.getConstructor(rowStore).newInstance(comparison);
    Object firstSample = sampleConstructor.newInstance(firstRows, meta, false, "", "Source");
    Object comparisonSample =
        sampleConstructor.newInstance(comparisonRows, meta, false, "", "Comparison");
    Predicate<Object[]> all = row -> true;
    BooleanSupplier running = () -> false;
    Object firstBuild = build.invoke(builder, meta, firstRows, "geometry", 1, all, running);
    Object comparisonBuild =
        build.invoke(builder, meta, comparisonRows, "geometry", 1, all, running);
    Path firstDirectory = (Path) field(rows, "directory");
    Path comparisonDirectory = (Path) field(comparison, "directory");
    Display display = Display.getCurrent();
    boolean ownsDisplay = display == null;
    if (ownsDisplay) display = new Display();
    Shell parent = new Shell(display);
    try {
      Object viewer =
          viewerType
              .getConstructor(
                  Shell.class, sample, builderType, List.class, String.class, buildType, configType)
              .newInstance(
                  parent,
                  firstSample,
                  builder,
                  List.of("geometry"),
                  "geometry",
                  firstBuild,
                  configType.getMethod("empty").invoke(null));
      viewerType.getMethod("open").invoke(viewer);
      Table table = (Table) field(viewer, "featureTable");
      assertThat(table.getItemCount()).isEqualTo(2);
      table.setSelection(1);
      table.notifyListeners(SWT.Selection, new Event());
      Label selection = (Label) field(viewer, "selectionSummaryLabel");
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
      while (!selection.getText().contains("Valid geometry") && System.nanoTime() < deadline) {
        if (!display.readAndDispatch()) Thread.sleep(5);
      }
      assertThat(selection.getText()).contains("Row 1", "Valid geometry");
      assertThat(field(viewer, "selectedExtraBuild")).isNotNull();

      viewerType
          .getMethod("addResult", sample, List.class, String.class, buildType, String.class)
          .invoke(
              viewer,
              comparisonSample,
              List.of("geometry"),
              "geometry",
              comparisonBuild,
              "Comparison");
      Table layers = (Table) field(viewer, "layerTable");
      assertThat(layers.getItemCount()).isEqualTo(2);
      findButton(layers.getParent(), "Remove").notifyListeners(SWT.Selection, new Event());
      assertThat(layers.getItemCount()).isEqualTo(1);
      assertThat(comparisonDirectory).doesNotExist();
      viewerType.getMethod("disposeWindow").invoke(viewer);
      assertThat(firstDirectory).doesNotExist();
      System.out.println("Installed SWT viewer verified temporary layers and row selection");
    } finally {
      parent.dispose();
      if (ownsDisplay) display.dispose();
    }
  }

  private static Object field(Object owner, String name) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static Button findButton(Composite parent, String text) {
    for (var child : parent.getChildren())
      if (child instanceof Button button && button.getText().equals(text)) return button;
    throw new AssertionError("Missing button " + text);
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
      Object options =
          optionsType.getConstructors()[0].newInstance(
              0,
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
            String direction = type.getMethod("direction").invoke(stream).toString();
            String peer = (String) type.getMethod("peer").invoke(stream);
            if (direction.equals("OUTPUT")
                    && !kind.equals("MAIN")
                    && expectedCounts.containsKey(peer)
                || direction.equals("INPUT")
                    && path.getParent().getFileName().toString().equals("rows")
                    && peer.equals("Source")) selected.add(stream);
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
                          BooleanSupplier.class)
                      .invoke(
                          service,
                          pipeline,
                          variables,
                          provider,
                          selected,
                          options,
                          (BooleanSupplier) () -> false);
          try {
            assertThat(results).as(path.toString()).hasSize(selected.size());
            for (Object result : results) {
              Object stream = result.getClass().getMethod("stream").invoke(result);
              String name = (String) stream.getClass().getMethod("transform").invoke(stream);
              if (!stream.getClass().getMethod("kind").invoke(stream).toString().equals("MAIN")
                  || stream
                      .getClass()
                      .getMethod("direction")
                      .invoke(stream)
                      .toString()
                      .equals("INPUT"))
                name = (String) stream.getClass().getMethod("peer").invoke(stream);
              Object rows = result.getClass().getMethod("rows").invoke(result);
              Class<?> storeType =
                  loader.loadClass("ch.so.agi.hop.geometry.inspector.data.RowStore");
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
            }
          } finally {
            for (Object result : results)
              ((AutoCloseable) result.getClass().getMethod("rows").invoke(result)).close();
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
