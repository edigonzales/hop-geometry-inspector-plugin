package ch.so.agi.hop.geometry.inspector.ui;

import static org.assertj.core.api.Assertions.assertThat;

import ch.so.agi.hop.geometry.inspector.GeometryFeatureBuilder;
import ch.so.agi.hop.geometry.inspector.model.*;
import java.util.List;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "geometryInspector.swtSmoke", matches = "true")
class DataInspectorSmokeTest {
  @Test
  void rowsWithoutGeometryAndIndependentLayerSelection() throws Exception {
    Display display = Display.getCurrent();
    boolean owns = display == null;
    if (owns) display = new Display();
    Shell parent = new Shell(display);
    GeometryInspectorSwtViewer viewer = null;
    try {
      var meta = new RowMeta();
      meta.addValueMeta(new ValueMetaInteger("id"));
      meta.addValueMeta(new ValueMetaString("geometry"));
      var sample =
          new SamplingResult(
              List.of(
                  new Object[] {10L, "SRID=2056;POINT (2600000 1200000)"},
                  new Object[] {2L, null},
                  new Object[] {1L, "invalid"}),
              meta,
              false,
              "Complete",
              GeometryInspectionSide.OUTPUT,
              GeometryInspectionSide.OUTPUT,
              false,
              "");
      var builder = new GeometryFeatureBuilder();
      var build = builder.build(meta, sample.rows(), "geometry");
      viewer =
          new GeometryInspectorSwtViewer(
              parent,
              sample,
              builder,
              List.of("geometry"),
              "geometry",
              build,
              GeometryInspectorBackgroundMapConfig.empty());
      viewer.open();
      pump(display);
      Table table = (Table) field(viewer, "featureTable");
      assertThat(table.getItemCount()).isEqualTo(3);
      table.setSelection(1);
      table.notifyListeners(SWT.Selection, new Event());
      pump(display);
      assertThat(field(viewer, "selectedRowIndex")).isEqualTo(1);
      assertThat(((Table) field(viewer, "attributeTable")).getItemCount()).isEqualTo(2);
      table.getColumn(1).notifyListeners(SWT.Selection, new Event());
      pump(display);
      assertThat(field(viewer, "selectedRowIndex")).isEqualTo(1);
      var query = (Text) field(viewer, "searchText");
      query.setText("invalid");
      pump(display);
      assertThat(table.getItemCount()).isEqualTo(1);
      query.setText("");
      pump(display);
      var overlapping =
          new SamplingResult(
              java.util.Collections.singletonList(
                  new Object[] {77L, "SRID=2056;POINT (2600000 1200000)"}),
              meta,
              false,
              "Complete",
              GeometryInspectionSide.OUTPUT,
              GeometryInspectionSide.OUTPUT,
              false,
              "");
      var overlapBuild = builder.build(meta, overlapping.rows(), "geometry");
      viewer.addResult(
          overlapping, List.of("geometry"), "geometry", overlapBuild, "Overlapping result", false);
      var select =
          viewer
              .getClass()
              .getDeclaredMethod(
                  "updateSelection",
                  org.geotools.api.feature.simple.SimpleFeature.class,
                  boolean.class);
      select.setAccessible(true);
      select.invoke(viewer, build.features().getFirst(), false);
      pump(display);
      assertThat(((Table) field(viewer, "attributeTable")).getItem(0).getText(1)).isEqualTo("10");
      var business = new RowMeta();
      business.addValueMeta(new ValueMetaString("name"));
      var other =
          new SamplingResult(
              java.util.Collections.singletonList(new Object[] {"business"}),
              business,
              false,
              "Complete",
              GeometryInspectionSide.OUTPUT,
              GeometryInspectionSide.OUTPUT,
              false,
              "");
      viewer.addResult(
          other, List.of(), "", builder.build(business, other.rows(), ""), "Business rows", false);
      pump(display);
      assertThat(table.getItemCount()).isEqualTo(1);
      Table layers = (Table) field(viewer, "layerTable");
      assertThat(layers.getItemCount()).isEqualTo(3);
      layers.setSelection(0);
      Event event = new Event();
      event.item = layers.getItem(0);
      layers.notifyListeners(SWT.Selection, event);
      pump(display);
      assertThat(table.getItemCount()).isEqualTo(3);
      Shell shell = (Shell) field(viewer, "shell");
      var image = new org.eclipse.swt.graphics.Image(display, shell.getSize().x, shell.getSize().y);
      var gc = new org.eclipse.swt.graphics.GC(shell);
      try {
        gc.copyArea(image, 0, 0);
        var png = new org.eclipse.swt.graphics.ImageLoader();
        png.data = new org.eclipse.swt.graphics.ImageData[] {image.getImageData()};
        png.save("target/data-inspector-smoke.png", SWT.IMAGE_PNG);
      } finally {
        gc.dispose();
        image.dispose();
      }
    } finally {
      if (viewer != null) viewer.disposeWindow();
      parent.dispose();
      if (owns) display.dispose();
    }
  }

  private Object field(Object value, String name) throws Exception {
    var field = value.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(value);
  }

  private void pump(Display display) throws Exception {
    long end = System.nanoTime() + 300_000_000L;
    while (System.nanoTime() < end) {
      if (!display.readAndDispatch()) Thread.sleep(5);
    }
  }
}
