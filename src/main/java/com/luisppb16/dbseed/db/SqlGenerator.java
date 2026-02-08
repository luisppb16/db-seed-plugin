/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.model.Table;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * Unified SQL generation entry point.
 * Delegates syntax responsibility to AiSqlProvider (Prem 1B SQL model).
 */
@UtilityClass
public class SqlGenerator {

  private static final OllamaService OLLAMA_SERVICE = new OllamaService();
  private static final AiSqlProvider AI_SQL_PROVIDER = new AiSqlProvider(OLLAMA_SERVICE);

  public static String generate(
      Map<Table, List<Row>> data, List<PendingUpdate> updates, boolean deferred, DriverInfo driverInfo) {
    final StringBuilder sb = new StringBuilder();

    if (deferred) {
      sb.append(getAiCommand("BEGIN TRANSACTION", driverInfo));
      sb.append(getAiCommand("DISABLE ALL CONSTRAINTS", driverInfo));
    }

    data.forEach((table, rows) -> {
      if (rows != null) {
        rows.forEach(row -> {
          sb.append(AI_SQL_PROVIDER.generateInsert(table, row, driverInfo)).append(";\n");
        });
      }
    });

    if (updates != null) {
      updates.forEach(update -> {
        sb.append(AI_SQL_PROVIDER.generateUpdate(update, driverInfo)).append(";\n");
      });
    }

    if (deferred) {
      sb.append(getAiCommand("ENABLE ALL CONSTRAINTS", driverInfo));
      sb.append(getAiCommand("COMMIT TRANSACTION", driverInfo));
    }

    return sb.toString();
  }

  private static String getAiCommand(String action, DriverInfo driverInfo) {
    String prompt = "Generate the SQL command to %s for %s dialect. Return ONLY the SQL.".formatted(action, driverInfo.name());
    try {
      return OLLAMA_SERVICE.ask(prompt).trim() + ";\n";
    } catch (Exception e) {
      return "-- Failed to generate " + action + "\n";
    }
  }
}
