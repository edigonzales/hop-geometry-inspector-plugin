package ch.so.agi.hop.geometry.inspector.parsing;

import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.hop.core.row.IValueMeta;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKTReader;

public class GeometryParser {

  private final WKBReader wkbReader = new WKBReader();
  private final WKTReader wktReader = new WKTReader();

  public Geometry parseGeometry(IValueMeta valueMeta, Object value) throws Exception {
    if (value == null) {
      return null;
    }

    if (value instanceof Geometry geometry) {
      return normalizeGeometry(geometry);
    }

    Geometry geometryFromMeta = parseViaGeometryInterface(valueMeta, value);
    if (geometryFromMeta != null) {
      return normalizeGeometry(geometryFromMeta);
    }

    if (value instanceof byte[] bytes) {
      return normalizeGeometry(parseWkb(bytes));
    }

    if (value instanceof String text) {
      return normalizeGeometry(parseString(text));
    }

    if (value instanceof CharSequence chars) {
      return normalizeGeometry(parseString(chars.toString()));
    }

    Exception deferredFailure = null;

    try {
      Geometry geometryFromStringBridge = parseViaValueMetaString(valueMeta, value);
      if (geometryFromStringBridge != null) {
        return normalizeGeometry(geometryFromStringBridge);
      }
    } catch (Exception e) {
      deferredFailure = e;
    }

    try {
      Geometry foreignGeometry = parseForeignGeometryObject(value);
      if (foreignGeometry != null) {
        return normalizeGeometry(foreignGeometry);
      }
    } catch (Exception e) {
      if (deferredFailure == null) {
        deferredFailure = e;
      }
    }

    if (deferredFailure != null) {
      throw deferredFailure;
    }

    throw new ParseException("Unsupported geometry value type: " + value.getClass().getName());
  }

  private Geometry parseViaGeometryInterface(IValueMeta valueMeta, Object value) throws Exception {
    if (valueMeta == null) {
      return null;
    }
    try {
      Method method = valueMeta.getClass().getMethod("getGeometry", Object.class);
      method.setAccessible(true);
      Object geometry = method.invoke(valueMeta, value);
      return parseGeometryObject(geometry);
    } catch (NoSuchMethodException ignored) {
      return null;
    }
  }

  private Geometry parseViaValueMetaString(IValueMeta valueMeta, Object value) throws Exception {
    if (valueMeta == null) {
      return null;
    }

    String text = valueMeta.getString(value);
    if (text == null || text.isBlank()) {
      return null;
    }

    return parseString(text);
  }

  private Geometry parseGeometryObject(Object value) throws Exception {
    if (value == null) {
      return null;
    }

    if (value instanceof Geometry geometry) {
      return geometry;
    }

    if (value instanceof byte[] bytes) {
      return parseWkb(bytes);
    }

    if (value instanceof String text) {
      return parseString(text);
    }

    if (value instanceof CharSequence chars) {
      return parseString(chars.toString());
    }

    return parseForeignGeometryObject(value);
  }

  private Geometry parseForeignGeometryObject(Object value) throws Exception {
    if (value == null || !isLikelyForeignJtsGeometry(value)) {
      return null;
    }

    String wkt = invokeStringMethod(value, "toText");
    if (wkt == null || wkt.isBlank()) {
      return null;
    }

    Integer srid = invokeIntegerMethod(value, "getSRID");
    if (srid != null && srid > 0) {
      return parseWktOrEwkt("SRID=" + srid + ";" + wkt);
    }

    return parseWktOrEwkt(wkt);
  }

  private Geometry parseString(String text) throws Exception {
    if (text == null || text.isBlank()) {
      return null;
    }

    String trimmed = text.trim();

    if (trimmed.startsWith("0x") || isLikelyHex(trimmed)) {
      try {
        String noPrefix = trimmed.startsWith("0x") ? trimmed.substring(2) : trimmed;
        return parseWkb(decodeHex(noPrefix));
      } catch (Exception ignored) {
        // Fall through to WKT/EWKT parsing.
      }
    }

    return parseWktOrEwkt(trimmed);
  }

  private Geometry parseWktOrEwkt(String text) throws Exception {
    String upper = text.toUpperCase(Locale.ROOT);
    if (upper.startsWith("SRID=") && text.contains(";")) {
      int sep = text.indexOf(';');
      String sridPart = text.substring(5, sep).trim();
      int srid = Integer.parseInt(sridPart);
      Geometry geometry = wktReader.read(text.substring(sep + 1).trim());
      geometry.setSRID(srid);
      return geometry;
    }
    return wktReader.read(text);
  }

  private Geometry parseWkb(byte[] wkb) throws Exception {
    return wkbReader.read(wkb);
  }

  private boolean isLikelyForeignJtsGeometry(Object value) {
    return value.getClass().getName().startsWith("org.locationtech.jts.geom.");
  }

  private boolean isLikelyHex(String value) {
    if (value.length() < 4 || value.length() % 2 != 0) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean hex = (c >= '0' && c <= '9')
          || (c >= 'a' && c <= 'f')
          || (c >= 'A' && c <= 'F');
      if (!hex) {
        return false;
      }
    }
    return true;
  }

  private byte[] decodeHex(String hex) {
    int len = hex.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      int high = Character.digit(hex.charAt(i), 16);
      int low = Character.digit(hex.charAt(i + 1), 16);
      if (high < 0 || low < 0) {
        throw new IllegalArgumentException("Invalid hex string");
      }
      data[i / 2] = (byte) ((high << 4) + low);
    }
    return data;
  }

  private String invokeStringMethod(Object target, String methodName) throws Exception {
    Method method = target.getClass().getMethod(methodName);
    method.setAccessible(true);
    Object value = method.invoke(target);
    return value == null ? null : value.toString();
  }

  private Integer invokeIntegerMethod(Object target, String methodName) throws Exception {
    Method method = target.getClass().getMethod(methodName);
    method.setAccessible(true);
    Object value = method.invoke(target);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.valueOf(value.toString());
  }

  private Geometry normalizeGeometry(Geometry geometry) {
    if (geometry == null || geometry.isEmpty()) {
      return null;
    }
    return geometry;
  }
}
