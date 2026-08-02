/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.integration;

import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.db.DataGenerator;
import com.luisppb16.dbseed.db.SchemaIntrospector;
import com.luisppb16.dbseed.db.SqlGenerator;
import com.luisppb16.dbseed.db.TopologicalSorter;
import com.luisppb16.dbseed.db.dialect.DialectFactory;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class IntegrationTestSupport {

  static final DriverInfo POSTGRES_DRIVER =
      new DriverInfo(
          "PostgreSQL",
          null,
          null,
          null,
          "org.postgresql.Driver",
          "jdbc:postgresql://localhost/test",
          false,
          true,
          true,
          true,
          "postgresql");

  static final DriverInfo MYSQL_DRIVER =
      new DriverInfo(
          "MySQL",
          null,
          null,
          null,
          "com.mysql.cj.jdbc.Driver",
          "jdbc:mysql://localhost/test",
          false,
          true,
          true,
          false,
          "mysql");

  static final DriverInfo SQLITE_DRIVER =
      new DriverInfo(
          "SQLite",
          "org.xerial",
          "sqlite-jdbc",
          "3.46.1.3",
          "org.sqlite.JDBC",
          "jdbc:sqlite:test.db",
          false,
          false,
          false,
          false,
          "sqlite");

  private IntegrationTestSupport() {}

  static DbSeedSettingsState defaultSettings() {
    final DbSeedSettingsState state = new DbSeedSettingsState();
    state.setUseLatinDictionary(false);
    state.setUseEnglishDictionary(false);
    state.setUseSpanishDictionary(false);
    state.setUseAiGeneration(false);
    state.setOllamaUrl("http://127.0.0.1:11434");
    state.setOllamaModel("test-model");
    state.setAiApplicationContext("");
    state.setAiWordCount(2);
    state.setAiRequestTimeoutSeconds(10);
    return state;
  }

  static WorkflowOptions defaults(final int rowsPerTable) {
    return new WorkflowOptions(
        rowsPerTable,
        false,
        Map.of(),
        Map.of(),
        Map.of(),
        false,
        false,
        false,
        null,
        false,
        null,
        2,
        Map.of(),
        "");
  }

  static WorkflowResult runWorkflow(
      final Connection connection,
      final String schema,
      final DriverInfo driverInfo,
      final WorkflowOptions options)
      throws SQLException {
    final WorkflowResult workflow = prepareWorkflow(connection, schema, driverInfo, options);
    executeScript(connection, workflow.generatedSql());
    return workflow;
  }

  static WorkflowResult prepareWorkflow(
      final Connection connection,
      final String schema,
      final DriverInfo driverInfo,
      final WorkflowOptions options)
      throws SQLException {
    final List<Table> tables =
        SchemaIntrospector.introspect(connection, schema, DialectFactory.resolve(driverInfo));
    final TopologicalSorter.SortResult sortResult = TopologicalSorter.sort(tables);
    final Map<String, Table> tableByName =
        tables.stream().collect(Collectors.toMap(Table::name, table -> table));
    final List<Table> orderedTables =
        sortResult.ordered().stream().map(tableByName::get).filter(Objects::nonNull).toList();

    final boolean effectiveDeferred =
        options.requestedDeferred()
            || TopologicalSorter.requiresDeferredDueToNonNullableCycles(sortResult, tableByName);

    final DataGenerator.GenerationResult generationResult =
        DataGenerator.generate(
            DataGenerator.GenerationParameters.builder()
                .tables(orderedTables)
                .rowsPerTable(options.rowsPerTable())
                .deferred(effectiveDeferred)
                .pkUuidOverrides(options.pkUuidOverrides())
                .excludedColumns(options.excludedColumns())
                .repetitionRules(options.repetitionRules())
                .useLatinDictionary(options.useLatinDictionary())
                .useEnglishDictionary(options.useEnglishDictionary())
                .useSpanishDictionary(options.useSpanishDictionary())
                .softDeleteColumns(options.softDeleteColumns())
                .softDeleteUseSchemaDefault(options.softDeleteUseSchemaDefault())
                .softDeleteValue(options.softDeleteValue())
                .numericScale(options.numericScale())
                .aiColumns(options.aiColumns())
                .applicationContext(options.applicationContext())
                .build());

    final String sql =
        SqlGenerator.generate(
            generationResult.rows(), generationResult.updates(), effectiveDeferred, driverInfo);

    return new WorkflowResult(
        tables, sortResult, orderedTables, effectiveDeferred, generationResult, sql);
  }

  static void applyProjectSqlFile(
      final Connection connection,
      final String relativePath,
      final Predicate<String> statementFilter)
      throws IOException, SQLException {
    final Path path = projectRoot().resolve(relativePath);
    executeStatements(connection, readStatements(path, statementFilter));
  }

  static void applyInlineSql(
      final Connection connection, final String sql, final Predicate<String> statementFilter)
      throws SQLException {
    executeStatements(connection, splitStatements(sql).stream().filter(statementFilter).toList());
  }

  static List<String> readStatements(final Path path, final Predicate<String> statementFilter)
      throws IOException {
    return splitStatements(Files.readString(path)).stream().filter(statementFilter).toList();
  }

  static void executeScript(final Connection connection, final String sql) throws SQLException {
    executeStatements(connection, splitStatements(sql));
  }

  static void executeStatements(final Connection connection, final List<String> statements)
      throws SQLException {
    for (final String statement : statements) {
      try (Statement jdbcStatement = connection.createStatement()) {
        jdbcStatement.execute(statement);
      }
    }
  }

  static List<String> splitStatements(final String script) {
    final List<String> statements = new ArrayList<>();
    final StringBuilder current = new StringBuilder();
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;
    boolean inLineComment = false;
    boolean inBlockComment = false;

    for (int i = 0; i < script.length(); i++) {
      final char currentChar = script.charAt(i);
      final char nextChar = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

      if (inLineComment) {
        if (currentChar == '\n') {
          inLineComment = false;
          current.append(currentChar);
        }
        continue;
      }

      if (inBlockComment) {
        if (currentChar == '*' && nextChar == '/') {
          inBlockComment = false;
          i++;
        }
        continue;
      }

      if (!inSingleQuote && !inDoubleQuote) {
        if (currentChar == '-' && nextChar == '-') {
          inLineComment = true;
          i++;
          continue;
        }
        if (currentChar == '/' && nextChar == '*') {
          inBlockComment = true;
          i++;
          continue;
        }
      }

      if (currentChar == '\'' && !inDoubleQuote) {
        final boolean escaped = i + 1 < script.length() && script.charAt(i + 1) == '\'';
        current.append(currentChar);
        if (escaped) {
          current.append(script.charAt(i + 1));
          i++;
        } else {
          inSingleQuote = !inSingleQuote;
        }
        continue;
      }

      if (currentChar == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
        current.append(currentChar);
        continue;
      }

      if (currentChar == ';' && !inSingleQuote && !inDoubleQuote) {
        final String statement = current.toString().trim();
        if (!statement.isBlank()) {
          statements.add(statement);
        }
        current.setLength(0);
        continue;
      }

      current.append(currentChar);
    }

    final String trailing = current.toString().trim();
    if (!trailing.isBlank()) {
      statements.add(trailing);
    }
    return statements;
  }

  static long queryForLong(final Connection connection, final String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  static Table findTable(final List<Table> tables, final String tableName) {
    return tables.stream()
        .filter(table -> table.name().equalsIgnoreCase(tableName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Table not found: " + tableName));
  }

  static Path newTempSqlitePath(final String prefix) throws IOException {
    final Path tempFile = Files.createTempFile(prefix, ".db");
    Files.deleteIfExists(tempFile);
    return tempFile;
  }

  static Path projectRoot() {
    return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
  }

  static Predicate<String> ddlOnly() {
    return statement -> !statement.trim().toLowerCase(Locale.ROOT).startsWith("insert ");
  }

  static Predicate<String> allStatements() {
    return statement -> true;
  }

  @FunctionalInterface
  interface SqlConnectionSupplier {
    Connection open() throws SQLException;
  }

  record WorkflowOptions(
      int rowsPerTable,
      boolean requestedDeferred,
      Map<String, Map<String, String>> pkUuidOverrides,
      Map<String, List<String>> excludedColumns,
      Map<String, List<RepetitionRule>> repetitionRules,
      boolean useLatinDictionary,
      boolean useEnglishDictionary,
      boolean useSpanishDictionary,
      String softDeleteColumns,
      boolean softDeleteUseSchemaDefault,
      String softDeleteValue,
      int numericScale,
      Map<String, Set<String>> aiColumns,
      String applicationContext) {}

  record WorkflowResult(
      List<Table> tables,
      TopologicalSorter.SortResult sortResult,
      List<Table> orderedTables,
      boolean deferred,
      DataGenerator.GenerationResult generationResult,
      String generatedSql) {}

  record ContainerEngine(
      String displayName,
      DriverInfo driverInfo,
      String schemaName,
      String schemaFile,
      SqlConnectionSupplier connectionSupplier) {
    @Override
    public String toString() {
      return displayName;
    }
  }
}
