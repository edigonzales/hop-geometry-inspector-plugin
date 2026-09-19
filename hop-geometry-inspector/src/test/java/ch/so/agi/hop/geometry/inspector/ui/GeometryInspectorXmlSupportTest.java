package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import javax.xml.parsers.SAXParserFactory;
import org.junit.jupiter.api.Test;

class GeometryInspectorXmlSupportTest {
  private static final String PROPERTY = "javax.xml.parsers.SAXParserFactory";
  private static final String XERCES_PROVIDER = "org.apache.xerces.jaxp.SAXParserFactoryImpl";

  @Test
  void selectsJdkProviderAndRestoresExistingProvider() throws Exception {
    String previous = System.getProperty(PROPERTY);
    try {
      System.setProperty(PROPERTY, XERCES_PROVIDER);
      String inside =
          GeometryInspectorXmlSupport.withJdkSaxParser(
              () -> SAXParserFactory.newInstance().getClass().getName());

      assertThat(inside).isEqualTo(SAXParserFactory.newDefaultInstance().getClass().getName());
      assertThat(System.getProperty(PROPERTY)).isEqualTo(XERCES_PROVIDER);
    } finally {
      restore(previous);
    }
  }

  @Test
  void restoresProviderWhenOperationFails() {
    String previous = System.getProperty(PROPERTY);
    try {
      System.setProperty(PROPERTY, XERCES_PROVIDER);

      assertThatThrownBy(
              () ->
                  GeometryInspectorXmlSupport.withJdkSaxParser(
                      () -> {
                        assertThat(SAXParserFactory.newInstance().getClass().getName())
                            .isEqualTo(SAXParserFactory.newDefaultInstance().getClass().getName());
                        throw new IllegalStateException("expected failure");
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("expected failure");
      assertThat(System.getProperty(PROPERTY)).isEqualTo(XERCES_PROVIDER);
    } finally {
      restore(previous);
    }
  }

  @Test
  void restoresProviderAfterNestedOperation() throws Exception {
    String previous = System.getProperty(PROPERTY);
    try {
      System.setProperty(PROPERTY, XERCES_PROVIDER);
      AtomicReference<String> nestedProvider = new AtomicReference<>();

      String outerProvider =
          GeometryInspectorXmlSupport.withJdkSaxParser(
              () ->
                  GeometryInspectorXmlSupport.withJdkSaxParser(
                      () -> {
                        nestedProvider.set(SAXParserFactory.newInstance().getClass().getName());
                        return SAXParserFactory.newInstance().getClass().getName();
                      }));

      String jdkProvider = SAXParserFactory.newDefaultInstance().getClass().getName();
      assertThat(outerProvider).isEqualTo(jdkProvider);
      assertThat(nestedProvider).hasValue(jdkProvider);
      assertThat(System.getProperty(PROPERTY)).isEqualTo(XERCES_PROVIDER);
    } finally {
      restore(previous);
    }
  }

  private static void restore(String previous) {
    if (previous == null) {
      System.clearProperty(PROPERTY);
    } else {
      System.setProperty(PROPERTY, previous);
    }
  }
}
