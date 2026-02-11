/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.SqlKeyword;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * Property-driven foundation for database dialect implementations.
 * <p>
 * All SQL dialect behavior is configured via {@code .properties} files loaded from
 * the classpath ({@code /dialects/<name>.properties}). This eliminates the need for
 * separate Java classes per database — each database's syntax is fully described by
 * its properties file.
 * </p>
 * <p>
 * Supported properties:
 * <ul>
 *   <li>{@code quoteChar} / {@code quoteEscape} — identifier quoting</li>
 *   <li>{@code uppercaseIdentifiers} — whether to uppercase identifiers before quoting (e.g. Oracle)</li>
 *   <li>{@code booleanTrue} / {@code booleanFalse} — boolean literals</li>
 *   <li>{@code beginTransaction} / {@code commitTransaction} — transaction control</li>
 *   <li>{@code disableConstraints} / {@code enableConstraints} — FK constraint management</li>
 *   <li>{@code dateFormat} / {@code timestampFormat} / {@code uuidFormat} — type-specific formatting</li>
 *   <li>{@code batchHeader} / {@code batchRowPrefix} / {@code batchRowSuffix} / {@code batchRowSeparator} / {@code batchFooter} — INSERT batch templates</li>
 *   <li>{@code maxBatchSize} — maximum rows per batch INSERT (default 1000)</li>
 *   <li>{@code supportsMultiRowInsert} — whether multi-row VALUES is supported (default true)</li>
 * </ul>
 * </p>
 */
public class AbstractDialect implements DatabaseDialect {

  private static final String NULL_STR = "NULL";
  protected final Properties props = new Properties();

  protected AbstractDialect(String resourceName) {
    if (resourceName != null) {
      try (InputStream is = getClass().getResourceAsStream("/dialects/" + resourceName)) {
        if (is != null) {
          props.load(is);
        }
      } catch (IOException ignored) {
      }
    }
  }

  @Override
  public String quote(String identifier) {
    String quoteChar = props.getProperty("quoteChar", "\"");
    String quoteEscape = props.getProperty("quoteEscape", "\"\"");
    boolean uppercase =
        Boolean.parseBoolean(props.getProperty("uppercaseIdentifiers", "false"));
    String id = uppercase ? identifier.toUpperCase(Locale.ROOT) : identifier;
    return quoteChar + id.replace(quoteChar, quoteEscape) + quoteChar;
  }

  @Override
  public String formatBoolean(boolean b) {
    return b
        ? props.getProperty("booleanTrue", "TRUE")
        : props.getProperty("booleanFalse", "FALSE");
  }

  @Override
  public String beginTransaction() {
    return props.getProperty("beginTransaction", "BEGIN;\n");
  }

  @Override
  public String commitTransaction() {
    return props.getProperty("commitTransaction", "COMMIT;\n");
  }

  @Override
  public String disableConstraints() {
    return props.getProperty("disableConstraints", "");
  }

  @Override
  public String enableConstraints() {
    return props.getProperty("enableConstraints", "");
  }

  @Override
  public void formatValue(Object value, StringBuilder sb) {
    if (value == null) {
      sb.append(NULL_STR);
    } else {
      switch (value) {
        case SqlKeyword k -> sb.append(k.name());
        case Boolean b -> sb.append(formatBoolean(b));
        case Date d -> formatDate(d, sb);
        case Timestamp t -> formatTimestamp(t, sb);
        case UUID u -> formatUuid(u, sb);
        case String s -> sb.append("'").append(escapeSql(s)).append("'");
        case Character c -> sb.append("'").append(escapeSql(c.toString())).append("'");
        case BigDecimal bd -> sb.append(bd.toPlainString());
        case Double d -> sb.append(formatDouble(d));
        case Float f -> sb.append(formatDouble(f.doubleValue()));
        default -> sb.append(Objects.toString(value, NULL_STR));
      }
    }
  }

  protected void formatDate(Date d, StringBuilder sb) {
    String fmt = props.getProperty("dateFormat", "'${value}'");
    sb.append(fmt.replace("${value}", d.toString()));
  }

  protected void formatTimestamp(Timestamp t, StringBuilder sb) {
    String fmt = props.getProperty("timestampFormat", "'${value}'");
    sb.append(fmt.replace("${value}", t.toString()));
  }

  protected void formatUuid(UUID u, StringBuilder sb) {
    String fmt = props.getProperty("uuidFormat", "'${value}'");
    sb.append(fmt.replace("${value}", u.toString()));
  }

  protected String formatDouble(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return NULL_STR;
    }
    return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
  }

  protected String escapeSql(String s) {
    return s.replace("'", "''");
  }

  @Override
  public void appendBatch(
      StringBuilder sb,
      String tableName,
      String columnList,
      List<Row> rows,
      List<String> columnOrder) {

    int maxBatch = Integer.parseInt(props.getProperty("maxBatchSize", "1000"));
    boolean multiRow =
        Boolean.parseBoolean(props.getProperty("supportsMultiRowInsert", "true"));

    String header = props.getProperty("batchHeader", "INSERT INTO ${table} (${columns}) VALUES\n");
    String prefix = props.getProperty("batchRowPrefix", "(");
    String suffix = props.getProperty("batchRowSuffix", ")");
    String separator = props.getProperty("batchRowSeparator", ",\n");
    String footer = props.getProperty("batchFooter", ";\n");

    if (!multiRow) {
      appendSingleRowInserts(sb, tableName, columnList, rows, columnOrder);
      return;
    }

    for (int i = 0; i < rows.size(); i += maxBatch) {
      List<Row> batch = rows.subList(i, Math.min(i + maxBatch, rows.size()));

      sb.append(header.replace("${table}", tableName).replace("${columns}", columnList));

      for (int j = 0; j < batch.size(); j++) {
        sb.append(prefix.replace("${table}", tableName).replace("${columns}", columnList));
        appendRowValues(sb, batch.get(j), columnOrder);
        sb.append(suffix);
        if (j < batch.size() - 1) {
          sb.append(separator);
        }
      }
      sb.append(footer);
    }
  }

  private void appendSingleRowInserts(
      StringBuilder sb,
      String tableName,
      String columnList,
      List<Row> rows,
      List<String> columnOrder) {

    String stmtSep = props.getProperty("statementSeparator", ";\n");

    for (Row row : rows) {
      sb.append("INSERT INTO ")
          .append(tableName)
          .append(" (")
          .append(columnList)
          .append(") VALUES (");
      appendRowValues(sb, row, columnOrder);
      sb.append(")").append(stmtSep);
    }
  }

  protected void appendRowValues(StringBuilder sb, Row row, List<String> columnOrder) {
    for (int k = 0; k < columnOrder.size(); k++) {
      formatValue(row.values().get(columnOrder.get(k)), sb);
      if (k < columnOrder.size() - 1) {
        sb.append(", ");
      }
    }
  }
}
