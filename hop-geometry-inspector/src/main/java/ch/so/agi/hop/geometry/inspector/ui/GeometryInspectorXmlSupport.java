package ch.so.agi.hop.geometry.inspector.ui;

import javax.xml.parsers.SAXParserFactory;

/** Scoped XML parser compatibility helpers for GeoTools running inside Hop. */
final class GeometryInspectorXmlSupport {
  private static final String SAX_PARSER_FACTORY_PROPERTY =
      "javax.xml.parsers.SAXParserFactory";
  private static final Object SAX_PARSER_FACTORY_LOCK = new Object();

  private GeometryInspectorXmlSupport() {}

  @FunctionalInterface
  interface ThrowingSupplier<T, E extends Throwable> {
    T get() throws E;
  }

  /**
   * Runs a GeoTools XML operation with the JDK SAX provider selected.
   *
   * <p>Hop's Xerces provider does not recognize the JAXP {@code accessExternalSchema} property
   * used by GeoTools 35.1. The provider selection is a JVM-global property, so calls using this
   * workaround are serialized and the original value is restored even when the operation fails.
   */
  static <T, E extends Throwable> T withJdkSaxParser(ThrowingSupplier<T, E> supplier) throws E {
    synchronized (SAX_PARSER_FACTORY_LOCK) {
      String previous = System.getProperty(SAX_PARSER_FACTORY_PROPERTY);
      String jdkProvider = SAXParserFactory.newDefaultInstance().getClass().getName();
      try {
        System.setProperty(SAX_PARSER_FACTORY_PROPERTY, jdkProvider);
        return supplier.get();
      } finally {
        if (previous == null) {
          System.clearProperty(SAX_PARSER_FACTORY_PROPERTY);
        } else {
          System.setProperty(SAX_PARSER_FACTORY_PROPERTY, previous);
        }
      }
    }
  }
}
