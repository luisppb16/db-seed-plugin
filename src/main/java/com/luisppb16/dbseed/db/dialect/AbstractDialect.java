/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
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
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Property-driven foundation for database dialect implementations.
 *
 * <p>All SQL dialect behavior is configured via {@code .properties} files loaded from the classpath
 * ({@code /dialects/<name>.properties}). This eliminates the need for separate Java classes per
 * database — each database's syntax is fully described by its properties file.
 *
 * <p>Supported properties:
 *
 * <ul>
 *   <li>{@code quoteChar} / {@code quoteEscape} — identifier quoting
 *   <li>{@code uppercaseIdentifiers} — whether to uppercase identifiers before quoting (e.g.
 *       Oracle)
 *   <li>{@code booleanTrue} / {@code booleanFalse} — boolean literals
 *   <li>{@code beginTransaction} / {@code commitTransaction} — transaction control
 *   <li>{@code disableConstraints} / {@code enableConstraints} — FK constraint management
 *   <li>{@code dateFormat} / {@code timestampFormat} / {@code uuidFormat} — type-specific
 *       formatting
 *   <li>{@code batchHeader} / {@code batchRowPrefix} / {@code batchRowSuffix} / {@code
 *       batchRowSeparator} / {@code batchFooter} — INSERT batch templates
 *   <li>{@code maxBatchSize} — maximum rows per batch INSERT (default 1000)
 *   <li>{@code supportsMultiRowInsert} — whether multi-row VALUES is supported (default true)
 * </ul>
 */
@Slf4j
public sealed class AbstractDialect permits StandardDialect {

  private static final String NULL_STR = "NULL";
  protected final Properties props = new Properties();

  private final boolean escapeBackslash;
  private final String booleanTrue;
  private final String booleanFalse;
  private final String quoteOpen;
  private final String quoteClose;
  private final String quoteEscape;
  private final boolean uppercaseIdentifiers;
  private final int maxBatchSize;
  private final boolean supportsMultiRowInsert;
  private final String batchHeader;
  private final String batchRowPrefix;
  private final String batchRowSuffix;
  private final String batchRowSeparator;
  private final String batchFooter;
  private final String statementSeparator;

  protected AbstractDialect(String resourceName) {
    if (Objects.nonNull(resourceName)) {
      try (InputStream is = getClass().getResourceAsStream("/dialects/" + resourceName)) {
        if (Objects.nonNull(is)) {
          props.load(is);
        } else {
          log.error("Dialect properties file not found: /dialects/{}", resourceName);
        }
      } catch (IOException e) {
        log.error("Failed to load dialect properties: /dialects/{}", resourceName, e);
      }
    }
    escapeBackslash = Boolean.parseBoolean(props.getProperty("escapeBackslash", "false"));
    booleanTrue = props.getProperty("booleanTrue", "TRUE");
    booleanFalse = props.getProperty("booleanFalse", "FALSE");
    quoteOpen = props.getProperty("quoteChar", "\"");
    quoteClose = props.getProperty("closeQuoteChar", quoteOpen);
    quoteEscape = props.getProperty("quoteEscape", "");
    uppercaseIdentifiers = Boolean.parseBoolean(props.getProperty("uppercaseIdentifiers", "false"));
    final int parsedBatchSize = Integer.parseInt(props.getProperty("maxBatchSize", "1000"));
    maxBatchSize = parsedBatchSize <= 0 ? 1000 : parsedBatchSize;
    supportsMultiRowInsert =
        Boolean.parseBoolean(props.getProperty("supportsMultiRowInsert", "true"));
    batchHeader = props.getProperty("batchHeader", "INSERT INTO ${table} (${columns}) VALUES\n");
    batchRowPrefix = props.getProperty("batchRowPrefix", "(");
    batchRowSuffix = props.getProperty("batchRowSuffix", ")");
    batchRowSeparator = props.getProperty("batchRowSeparator", ",\n");
    batchFooter = props.getProperty("batchFooter", ";\n");
    statementSeparator = props.getProperty("statementSeparator", ";\n");
  }

  public String getProperty(String key, String defaultValue) {
    return props.getProperty(key, defaultValue);
  }

  public String quote(String identifier) {
    String id = uppercaseIdentifiers ? identifier.toUpperCase(Locale.ROOT) : identifier;
    // For symmetric quotes (e.g. "), escape the quote char inside the identifier
    // For asymmetric quotes (e.g. [ ]), escape the close char inside the identifier
    String charToEscape = quoteClose;
    String escapeWith = quoteEscape.isEmpty() ? quoteClose + quoteClose : quoteEscape;
    if (quoteOpen.equals(quoteClose)) {
      charToEscape = quoteOpen;
      escapeWith = quoteEscape.isEmpty() ? quoteOpen + quoteOpen : quoteEscape;
    }
    return quoteOpen + id.replace(charToEscape, escapeWith) + quoteClose;
  }

  public String formatBoolean(boolean b) {
    return b ? booleanTrue : booleanFalse;
  }

  public String beginTransaction() {
    return props.getProperty("beginTransaction", "BEGIN;\n");
  }

  public String commitTransaction() {
    return props.getProperty("commitTransaction", "COMMIT;\n");
  }

  public String disableConstraints() {
    return props.getProperty("disableConstraints", "");
  }

  public String enableConstraints() {
    return props.getProperty("enableConstraints", "");
  }

  public void formatValue(Object value, StringBuilder sb) {
    if (Objects.isNull(value)) {
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
        case Object[] arr -> formatArray(arr, sb);
        case List<?> list -> formatArray(list.toArray(), sb);
        default -> sb.append(Objects.toString(value, NULL_STR));
      }
    }
  }

  protected void formatArray(Object[] array, StringBuilder sb) {
    String arrayFormat = props.getProperty("arrayFormat", "ARRAY[{values}]");
    String valueSeparator = props.getProperty("arrayValueSeparator", ", ");

    StringBuilder valuesSb = new StringBuilder();
    IntStream.range(0, array.length)
        .forEach(
            i -> {
              formatValue(array[i], valuesSb);
              if (i < array.length - 1) valuesSb.append(valueSeparator);
            });
    sb.append(arrayFormat.replace("{values}", valuesSb.toString()));
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
    return Double.isNaN(d) || Double.isInfinite(d)
        ? NULL_STR
        : BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
  }

  protected String escapeSql(String s) {
    String result = s;
    if (escapeBackslash) {
      result = result.replace("\\", "\\\\");
    }
    result = result.replace("\0", "");
    result = result.replace("\r\n", " ");
    result = result.replace("\n", " ");
    result = result.replace("\r", " ");
    return result.replace("'", "''");
  }

  public void appendBatch(
      StringBuilder sb,
      String tableName,
      String columnList,
      List<Row> rows,
      List<String> columnOrder) {

    if (!supportsMultiRowInsert) {
      appendSingleRowInserts(sb, tableName, columnList, rows, columnOrder);
      return;
    }

    for (int i = 0; i < rows.size(); i += maxBatchSize) {
      List<Row> batch = rows.subList(i, Math.min(i + maxBatchSize, rows.size()));

      sb.append(batchHeader.replace("${table}", tableName).replace("${columns}", columnList));

      for (int j = 0; j < batch.size(); j++) {
        sb.append(batchRowPrefix.replace("${table}", tableName).replace("${columns}", columnList));
        appendRowValues(sb, batch.get(j), columnOrder);
        sb.append(batchRowSuffix);
        if (j < batch.size() - 1) {
          sb.append(batchRowSeparator);
        }
      }
      sb.append(batchFooter);
    }
  }

  private void appendSingleRowInserts(
      StringBuilder sb,
      String tableName,
      String columnList,
      List<Row> rows,
      List<String> columnOrder) {

    for (Row row : rows) {
      sb.append(batchHeader.replace("${table}", tableName).replace("${columns}", columnList));
      sb.append(batchRowPrefix.replace("${table}", tableName).replace("${columns}", columnList));
      appendRowValues(sb, row, columnOrder);
      sb.append(batchRowSuffix);
      sb.append(
          batchFooter.isEmpty()
              ? statementSeparator
              : batchFooter.replace("${table}", tableName).replace("${columns}", columnList));
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
