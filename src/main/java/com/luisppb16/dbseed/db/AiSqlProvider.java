/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service that interacts with Prem 1B SQL via Ollama to generate dialect-specific SQL.
 * Decouples the plugin from manual dialect implementations.
 */
@Slf4j
@RequiredArgsConstructor
public class AiSqlProvider {

    private final OllamaService ollamaService;

    public String generateInsert(Table table, Row row, DriverInfo driverInfo) {
        String dialect = driverInfo.name();
        String columns = table.columns().stream().map(Column::name).collect(Collectors.joining(", "));
        String values = row.values().entrySet().stream()
                .map(e -> e.getValue() == null ? "NULL" : String.valueOf(e.getValue()))
                .collect(Collectors.joining(", "));

        String prompt = """
                Generate a single SQL INSERT statement for %s dialect.
                Table: %s
                Columns: (%s)
                Values: (%s)
                Ensure proper quoting for identifiers and literals (strings, dates, booleans).
                Return ONLY the SQL statement.
                """.formatted(dialect, table.name(), columns, values);

        try {
            return ollamaService.ask(prompt).trim();
        } catch (Exception e) {
            log.error("AI SQL generation failed for table {}", table.name(), e);
            return "-- AI Generation failed for " + table.name();
        }
    }

    public String generateUpdate(PendingUpdate update, DriverInfo driverInfo) {
        String dialect = driverInfo.name();
        String setClause = update.fkValues().entrySet().stream()
                .map(e -> e.getKey() + " = " + (e.getValue() == null ? "NULL" : e.getValue()))
                .collect(Collectors.joining(", "));
        String whereClause = update.pkValues().entrySet().stream()
                .map(e -> e.getKey() + " = " + (e.getValue() == null ? "NULL" : e.getValue()))
                .collect(Collectors.joining(" AND "));

        String prompt = """
                Generate a single SQL UPDATE statement for %s dialect.
                Table: %s
                Set: %s
                Where: %s
                Ensure proper quoting for identifiers and literals.
                Return ONLY the SQL statement.
                """.formatted(dialect, update.table(), setClause, whereClause);

        try {
            return ollamaService.ask(prompt).trim();
        } catch (Exception e) {
            log.error("AI SQL update generation failed for table {}", update.table(), e);
            return "-- AI Generation failed for update on " + update.table();
        }
    }
}
