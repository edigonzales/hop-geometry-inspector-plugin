package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GeometryInspectorOptionsDialogTest {

  @Test
  void parseSampleSizeAcceptsPositiveNumbers() {
    assertThat(GeometryInspectorOptionsDialog.parseSampleSize("50")).isEqualTo(50);
    assertThat(GeometryInspectorOptionsDialog.parseSampleSize(" 200 ")).isEqualTo(200);
  }

  @Test
  void parseSampleSizeRejectsBlankAndInvalidValues() {
    assertThatThrownBy(() -> GeometryInspectorOptionsDialog.parseSampleSize(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeometryInspectorOptionsDialog.parseSampleSize(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeometryInspectorOptionsDialog.parseSampleSize("0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeometryInspectorOptionsDialog.parseSampleSize("-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeometryInspectorOptionsDialog.parseSampleSize("ALL"))
        .isInstanceOf(NumberFormatException.class);
    assertThatThrownBy(() -> GeometryInspectorOptionsDialog.parseSampleSize("abc"))
        .isInstanceOf(NumberFormatException.class);
  }
}
