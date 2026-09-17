package ch.so.agi.hop.geometry.inspector.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;

public final class CacheFingerprint {
  private CacheFingerprint() {}

  public static String of(
      PipelineMeta pipeline, String target, IVariables variables, IHopMetadataProvider provider)
      throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (Class<?> runtime :
        List.of(
            CacheFingerprint.class,
            org.apache.hop.pipeline.PipelineMeta.class,
            org.locationtech.jts.geom.Geometry.class)) addRuntime(digest, runtime);
    try {
      addRuntime(
          digest,
          Class.forName(
              "com.atolcd.hop.core.row.value.ValueMetaGeometry",
              false,
              Thread.currentThread().getContextClassLoader()));
    } catch (ClassNotFoundException ignored) {
    }
    Set<String> references = new HashSet<>();
    var ancestors = CaptureService.ancestors(pipeline, List.of(target));
    for (var transform :
        pipeline.getTransforms().stream()
            .filter(t -> ancestors.contains(t.getName()))
            .sorted(Comparator.comparing(t -> t.getName()))
            .toList()) {
      add(digest, transform.getName());
      var origin = transform.getTransform().getClass().getProtectionDomain().getCodeSource();
      if (origin != null) {
        var path = java.nio.file.Path.of(origin.getLocation().toURI());
        if (java.nio.file.Files.isRegularFile(path)) {
          try (var in = java.nio.file.Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
          }
        }
      }
      if (transform.getTransformErrorMeta() != null)
        add(digest, transform.getTransformErrorMeta().getXml());
      add(digest, transform.getTransformPluginId());
      String xml = transform.getXml().replaceAll("(?s)<GUI>.*?</GUI>", "");
      add(digest, xml);
      collectXmlReferences(
          org.apache.hop.core.xml.XmlHandler.loadXmlString(
                  "<configuration>" + xml + "</configuration>")
              .getDocumentElement(),
          references,
          variables);
    }
    for (var hop : pipeline.getPipelineHops())
      if (ancestors.contains(hop.getFromTransform().getName())
          && ancestors.contains(hop.getToTransform().getName())) add(digest, hop.getXml());
    String[] names = variables.getVariableNames();
    Arrays.sort(names);
    for (String name : names) {
      add(digest, name);
      add(digest, variables.getVariable(name));
    }
    for (String name : pipeline.listParameters()) {
      add(digest, name);
      add(digest, pipeline.getParameterDefault(name));
    }
    if (provider != null) {
      Set<String> visited = new HashSet<>();
      Map<String, String> dependencies = new TreeMap<>();
      boolean changed;
      do {
        changed = false;
        for (var type :
            provider.getMetadataClasses().stream()
                .sorted(Comparator.comparing(Class::getName))
                .toList()) {
          var serializer = provider.getSerializer(type);
          for (String name : serializer.listObjectNames().stream().sorted().toList()) {
            String key = type.getName() + "/" + name;
            if (!references.contains(name) || !visited.add(key)) continue;
            var parser =
                new org.apache.hop.metadata.serializer.json.JsonMetadataParser(type, provider);
            var json = parser.getJsonObject(serializer.load(name));
            dependencies.put(key, json.toJSONString());
            collectJsonReferences(json, references, variables);
            changed = true;
          }
        }
      } while (changed);
      for (var entry : dependencies.entrySet()) {
        add(digest, entry.getKey());
        add(digest, entry.getValue());
      }
    }
    add(digest, "hop-2.19.0/data-inspector-cache-1/java-" + Runtime.version().feature());
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void collectXmlReferences(
      org.w3c.dom.Node node, Set<String> names, IVariables variables) {
    if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
      String text = node.getNodeValue().trim();
      names.add(text);
      names.add(variables.resolve(text));
    }
    for (var child = node.getFirstChild(); child != null; child = child.getNextSibling())
      collectXmlReferences(child, names, variables);
  }

  private static void collectJsonReferences(Object value, Set<String> names, IVariables variables) {
    if (value instanceof String text) {
      names.add(text);
      names.add(variables.resolve(text));
    } else if (value instanceof Map<?, ?> map)
      for (Object child : map.values()) collectJsonReferences(child, names, variables);
    else if (value instanceof Iterable<?> values)
      for (Object child : values) collectJsonReferences(child, names, variables);
  }

  private static void addRuntime(MessageDigest digest, Class<?> type) throws Exception {
    add(digest, type.getName());
    add(digest, type.getPackage().getImplementationVersion());
    var origin = type.getProtectionDomain().getCodeSource();
    if (origin != null) {
      var path = java.nio.file.Path.of(origin.getLocation().toURI());
      if (java.nio.file.Files.isRegularFile(path))
        try (var in = java.nio.file.Files.newInputStream(path)) {
          byte[] bytes = new byte[65536];
          int n;
          while ((n = in.read(bytes)) >= 0) digest.update(bytes, 0, n);
        }
    }
  }

  private static void add(MessageDigest digest, String text) {
    byte[] bytes = Objects.toString(text, "").getBytes(StandardCharsets.UTF_8);
    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
