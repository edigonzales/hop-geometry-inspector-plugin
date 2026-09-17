package ch.so.agi.hop.geometry.inspector.data;

import java.util.*;
import org.apache.hop.core.row.IRowMeta;

/** Typed query shared by in-memory and disk-backed results. */
public record RowQuery(String search, List<Condition> conditions, List<Sort> sort) {
  public enum Operator {
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    CONTAINS,
    IS_NULL,
    NOT_NULL
  }

  public record Condition(int field, Operator operator, Object value) {}

  public record Sort(int field, boolean ascending) {}

  public RowQuery {
    search = search == null ? "" : search.toLowerCase(Locale.ROOT);
    conditions = List.copyOf(conditions);
    sort = List.copyOf(sort);
  }

  public boolean matches(IRowMeta meta, Object[] row) {
    boolean found = search.isBlank();
    for (int i = 0; i < row.length && !found; i++) {
      try {
        String s = meta.getValueMeta(i).getString(row[i]);
        found = s != null && s.toLowerCase(Locale.ROOT).contains(search);
      } catch (Exception e) {
        found = String.valueOf(row[i]).toLowerCase(Locale.ROOT).contains(search);
      }
    }
    if (!found) return false;
    for (Condition c : conditions) {
      Object actual = c.field() < row.length ? row[c.field()] : null;
      int n = compare(actual, c.value());
      boolean match =
          switch (c.operator()) {
            case IS_NULL -> actual == null;
            case NOT_NULL -> actual != null;
            case EQ ->
                Objects.equals(actual, c.value()) || actual != null && c.value() != null && n == 0;
            case NE ->
                !(Objects.equals(actual, c.value())
                    || actual != null && c.value() != null && n == 0);
            case LT -> actual != null && n < 0;
            case LE -> actual != null && n <= 0;
            case GT -> actual != null && n > 0;
            case GE -> actual != null && n >= 0;
            case CONTAINS ->
                actual != null
                    && actual
                        .toString()
                        .toLowerCase(Locale.ROOT)
                        .contains(String.valueOf(c.value()).toLowerCase(Locale.ROOT));
          };
      if (!match) return false;
    }
    return true;
  }

  public int compareRows(Object[] a, Object[] b) {
    return compareRows(a, b, 0, 0);
  }

  public int compareRows(Object[] a, Object[] b, long ordinalA, long ordinalB) {
    for (Sort s : sort) {
      if (s.field() == -1) {
        int n = Long.compare(ordinalA, ordinalB);
        if (n != 0) return s.ascending() ? n : -n;
        continue;
      }
      Object x = s.field() < a.length ? a[s.field()] : null,
          y = s.field() < b.length ? b[s.field()] : null;
      int n = compare(x, y);
      if (n != 0) return x == null || y == null || s.ascending() ? n : -n;
    }
    return 0;
  }

  public static int compare(Object a, Object b) {
    if (a == b) return 0;
    if (a == null) return 1;
    if (b == null) return -1;
    if (a instanceof Number && b instanceof Number) {
      try {
        return new java.math.BigDecimal(a.toString())
            .compareTo(new java.math.BigDecimal(b.toString()));
      } catch (NumberFormatException e) {
        return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
      }
    }
    if (a instanceof Date x && b instanceof Date y) return x.compareTo(y);
    if (a instanceof Boolean x && b instanceof Boolean y) return x.compareTo(y);
    return a.toString().compareToIgnoreCase(b.toString());
  }
}
