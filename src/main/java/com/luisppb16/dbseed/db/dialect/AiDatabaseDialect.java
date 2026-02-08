/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luisppb16.dbseed.ai.OllamaService;
import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.db.Row;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * AI-driven implementation of DatabaseDialect that uses Prem 1B SQL to determine SQL syntax.
 */
@Slf4j
public class AiDatabaseDialect implements DatabaseDialect {

  private final DriverInfo driverInfo;
  private final OllamaService aiService;
  private DialectRules rules;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public AiDatabaseDialect(DriverInfo driverInfo) {
    this.driverInfo = driverInfo;
    this.aiService = new OllamaService();
    initializeRules();
  }

  private void initializeRules() {
    String prompt = String.format(
        "As a SQL expert, analyze this database driver: %s (URL: %s). " +
        "Provide the SQL dialect rules in the following JSON format ONLY: " +
        "{\"quoteChar\": \"...\", \"trueValue\": \"...\", \"falseValue\": \"...\", " +
        "\"beginTransaction\": \"...\", \"commitTransaction\": \"...\", " +
        "\"disableConstraints\": \"...\", \"enableConstraints\": \"...\", " +
        "\"timestampTemplate\": \"...\", \"batchInsertTemplate\": \"...\"}",
        driverInfo.name(), driverInfo.urlTemplate());

    try {
      String response = aiService.ask(prompt);
      if (response.contains("```json")) {
        response = response.substring(response.indexOf("```json") + 7);
        response = response.substring(0, response.indexOf("```"));
      }
      this.rules = objectMapper.readValue(response, DialectRules.class);
    } catch (Exception e) {
      log.error("Failed to initialize AI dialect rules, using defaults", e);
      this.rules = DialectRules.defaults();
    }
  }

  @Override
  public String quote(String identifier) {
    return rules.quoteChar() + identifier + rules.quoteChar();
  }

  @Override
  public String formatBoolean(boolean b) {
    return b ? rules.trueValue() : rules.falseValue();
  }

  @Override
  public String beginTransaction() {
    return rules.beginTransaction() + ";\n";
  }

  @Override
  public String commitTransaction() {
    return rules.commitTransaction() + ";\n";
  }

  @Override
  public String disableConstraints() {
    return rules.disableConstraints() + ";\n";
  }

  @Override
  public String enableConstraints() {
    return rules.enableConstraints() + ";\n";
  }

  @Override
  public void formatValue(Object value, StringBuilder sb) {
    if (value == null) {
      sb.append("NULL");
    } else if (value instanceof String s) {
      sb.append("'").append(s.replace("'", "''")).append("'");
    } else if (value instanceof Boolean b) {
      sb.append(formatBoolean(b));
    } else if (value instanceof java.sql.Timestamp ts) {
        if (rules.timestampTemplate() != null && !rules.timestampTemplate().isBlank()) {
            sb.append(rules.timestampTemplate().replace("?", "'" + ts.toString() + "'"));
        } else {
            sb.append("'").append(ts.toString()).append("'");
        }
    } else {
      sb.append(value.toString());
    }
  }

  @Override
  public void appendBatch(
      StringBuilder sb,
      String tableName,
      String columnList,
      List<Row> rows,
      List<String> columnOrder) {
    sb.append("INSERT INTO ").append(tableName).append(" (").append(columnList).append(") VALUES \n");
    for (int i = 0; i < rows.size(); i++) {
      sb.append("(");
      Row row = rows.get(i);
      for (int j = 0; j < columnOrder.size(); j++) {
        formatValue(row.values().get(columnOrder.get(j)), sb);
        if (j < columnOrder.size() - 1) sb.append(", ");
      }
      sb.append(")");
      if (i < rows.size() - 1) sb.append(",\n");
      else sb.append(";\n");
    }
  }

  private record DialectRules(
      String quoteChar,
      String trueValue,
      String falseValue,
      String beginTransaction,
      String commitTransaction,
      String disableConstraints,
      String enableConstraints,
      String timestampTemplate,
      String batchInsertTemplate) {

    static DialectRules defaults() {
      return new DialectRules("\"", "TRUE", "FALSE", "BEGIN TRANSACTION", "COMMIT", "", "", null, null);
    }
  }
}
