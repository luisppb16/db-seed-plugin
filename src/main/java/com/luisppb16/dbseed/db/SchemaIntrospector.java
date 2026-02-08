/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Refactored SchemaIntrospector to capture raw metadata and constraints.
 * Manual parsing is deprecated; raw definitions are preserved for AI context.
 */
@Slf4j
@UtilityClass
public class SchemaIntrospector {

  private static final String COLUMN_NAME = "COLUMN_NAME";
  private static final String TABLE_NAME = "TABLE_NAME";
  private static final Pattern POSTGRES_CHECK_PATTERN = Pattern.compile("(?i)CHECK\\s*\\((.*)\\)");

  public static List<Table> introspect(final Connection conn, final String schema)
      throws SQLException {
    Objects.requireNonNull(conn, "Connection cannot be null");
    final DatabaseMetaData meta = conn.getMetaData();
    final List<Table> tables = new ArrayList<>();

    final List<TableRawData> rawTables = loadAllTables(meta, schema);
    final Map<TableKey, List<ColumnRawData>> rawColumns = loadAllColumns(meta, schema);
    final Map<TableKey, List<String>> allChecks =
        loadAllCheckConstraints(conn, meta, schema, rawTables, rawColumns);
    final Map<TableKey, List<String>> allPks = loadAllPrimaryKeys(meta, schema, rawTables);
    final Map<TableKey, List<List<String>>> allUniqueKeys =
        loadAllUniqueKeys(meta, schema, rawTables);
    final Map<TableKey, List<ForeignKey>> allFks =
        loadAllForeignKeys(meta, schema, rawTables, allUniqueKeys);

    for (final TableRawData tableData : rawTables) {
      final String tableName = tableData.name();
      final String tableSchema = tableData.schema();
      final TableKey key = new TableKey(tableSchema, tableName);

      final List<String> checks = allChecks.getOrDefault(key, Collections.emptyList());
      final List<ColumnRawData> tableCols = rawColumns.getOrDefault(key, Collections.emptyList());
      final List<String> pkCols = allPks.getOrDefault(key, Collections.emptyList());

      final List<Column> columns = buildColumns(tableCols, pkCols);
      final List<List<String>> uniqueKeys =
          allUniqueKeys.getOrDefault(key, Collections.emptyList());
      final List<ForeignKey> fks = allFks.getOrDefault(key, Collections.emptyList());

      tables.add(new Table(tableName, columns, pkCols, fks, checks, uniqueKeys));
    }
    return tables;
  }

  private static List<TableRawData> loadAllTables(final DatabaseMetaData meta, final String schema)
      throws SQLException {
    final List<TableRawData> list = new ArrayList<>();
    try (final ResultSet rs = meta.getTables(null, schema, "%", new String[] {"TABLE"})) {
      while (rs.next()) {
        list.add(
            new TableRawData(
                rs.getString(TABLE_NAME),
                rs.getString("TABLE_SCHEM"),
                safe(rs.getString("REMARKS"))));
      }
    }
    return list;
  }

  private static Map<TableKey, List<ColumnRawData>> loadAllColumns(
      final DatabaseMetaData meta, final String schema) throws SQLException {
    final Map<TableKey, List<ColumnRawData>> map = new LinkedHashMap<>();
    try (final ResultSet rs = meta.getColumns(null, schema, "%", "%")) {
      while (rs.next()) {
        final String tableName = rs.getString(TABLE_NAME);
        final String tableSchema = rs.getString("TABLE_SCHEM");
        final TableKey key = new TableKey(tableSchema, tableName);
        final ColumnRawData col =
            new ColumnRawData(
                rs.getString(COLUMN_NAME),
                rs.getInt("DATA_TYPE"),
                rs.getString("TYPE_NAME"),
                "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                rs.getInt("COLUMN_SIZE"),
                rs.getInt("DECIMAL_DIGITS"),
                safe(rs.getString("REMARKS")));
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(col);
      }
    }
    return map;
  }

  private static List<Column> buildColumns(
      final List<ColumnRawData> rawColumns,
      final List<String> pkCols) {
    final List<Column> columns = new ArrayList<>();
    final Set<String> pkSet = new LinkedHashSet<>(pkCols);

    for (final ColumnRawData raw : rawColumns) {
      final boolean isUuid =
          raw.name().toLowerCase(Locale.ROOT).endsWith("guid")
              || raw.name().toLowerCase(Locale.ROOT).endsWith("uuid")
              || raw.type() == java.sql.Types.OTHER
              || (raw.typeName() != null
                  && raw.typeName().toLowerCase(Locale.ROOT).contains("uuid"));

      columns.add(
          new Column(
              raw.name(),
              raw.type(),
              raw.nullable(),
              pkSet.contains(raw.name()),
              isUuid,
              raw.length(),
              raw.scale(),
              0, // Deprecated manual bounds
              0,
              Collections.emptySet()));
    }
    return columns;
  }

  private static Map<TableKey, List<String>> loadAllPrimaryKeys(
      final DatabaseMetaData meta, final String schema, final List<TableRawData> rawTables)
      throws SQLException {
    final Map<TableKey, List<String>> map = new LinkedHashMap<>();
    for (final TableRawData table : rawTables) {
      try (final ResultSet rs = meta.getPrimaryKeys(null, schema, table.name())) {
        while (rs.next()) {
          final String tableName = rs.getString(TABLE_NAME);
          final String tableSchema = rs.getString("TABLE_SCHEM");
          final TableKey key = new TableKey(tableSchema, tableName);
          map.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getString(COLUMN_NAME));
        }
      }
    }
    return map;
  }

  private static Map<TableKey, List<ForeignKey>> loadAllForeignKeys(
      final DatabaseMetaData meta,
      final String schema,
      final List<TableRawData> rawTables,
      final Map<TableKey, List<List<String>>> allUniqueKeys)
      throws SQLException {

    final Map<TableKey, Map<String, Map<String, String>>> groupedMappings = new LinkedHashMap<>();
    final Map<TableKey, Map<String, String>> fkToPkTable = new HashMap<>();

    for (final TableRawData table : rawTables) {
      try (final ResultSet rs = meta.getImportedKeys(null, schema, table.name())) {
        while (rs.next()) {
          final String fkSchema = rs.getString("FKTABLE_SCHEM");
          final String fkTable = rs.getString("FKTABLE_NAME");
          final TableKey key = new TableKey(fkSchema, fkTable);

          final String fkName = rs.getString("FK_NAME");
          final String fkCol = rs.getString("FKCOLUMN_NAME");
          final String pkCol = rs.getString("PKCOLUMN_NAME");
          final String pkTableName = rs.getString("PKTABLE_NAME");

          groupedMappings
              .computeIfAbsent(key, k -> new LinkedHashMap<>())
              .computeIfAbsent(fkName, k -> new LinkedHashMap<>())
              .put(fkCol, pkCol);

          fkToPkTable.computeIfAbsent(key, k -> new HashMap<>()).put(fkName, pkTableName);
        }
      }
    }

    final Map<TableKey, List<ForeignKey>> result = new LinkedHashMap<>();
    for (var tableEntry : groupedMappings.entrySet()) {
      final TableKey key = tableEntry.getKey();
      final List<ForeignKey> fks = new ArrayList<>();
      final List<List<String>> uniqueKeys = allUniqueKeys.getOrDefault(key, Collections.emptyList());

      for (var fkEntry : tableEntry.getValue().entrySet()) {
        final String fkName = fkEntry.getKey();
        final Map<String, String> mapping = Map.copyOf(fkEntry.getValue());
        final String pkTableName = fkToPkTable.get(key).get(fkName);
        final boolean uniqueOnFk = uniqueKeys.stream().anyMatch(uk -> uk.size() == mapping.size() && mapping.keySet().containsAll(uk));
        fks.add(new ForeignKey(fkName, pkTableName, mapping, uniqueOnFk));
      }
      result.put(key, fks);
    }
    return result;
  }

  private static Map<TableKey, List<List<String>>> loadAllUniqueKeys(
      final DatabaseMetaData meta, final String schema, final List<TableRawData> rawTables)
      throws SQLException {
    final Map<TableKey, Map<String, List<String>>> idxCols = new LinkedHashMap<>();
    for (final TableRawData tableData : rawTables) {
      try (final ResultSet rs = meta.getIndexInfo(null, schema, tableData.name(), true, false)) {
        while (rs.next()) {
          final String idxName = rs.getString("INDEX_NAME");
          final String colName = rs.getString(COLUMN_NAME);
          if (idxName == null || colName == null) continue;

          final TableKey key = new TableKey(rs.getString("TABLE_SCHEM"), rs.getString(TABLE_NAME));
          idxCols.computeIfAbsent(key, k -> new LinkedHashMap<>())
              .computeIfAbsent(idxName, k -> new ArrayList<>())
              .add(colName);
        }
      }
    }
    final Map<TableKey, List<List<String>>> result = new LinkedHashMap<>();
    idxCols.forEach((key, val) -> result.put(key, val.values().stream().map(List::copyOf).toList()));
    return result;
  }

  private static Map<TableKey, List<String>> loadAllCheckConstraints(
      final Connection conn,
      final DatabaseMetaData meta,
      final String schema,
      final List<TableRawData> tables,
      final Map<TableKey, List<ColumnRawData>> columns)
      throws SQLException {

    final Map<TableKey, List<String>> checks = new HashMap<>();
    final String product = safe(meta.getDatabaseProductName()).toLowerCase(Locale.ROOT);

    if (product.contains("postgres")) {
      loadPostgresChecks(conn, schema, checks);
    } else if (product.contains("h2")) {
      loadH2Checks(conn, schema, checks);
    }
    return checks;
  }

  private static void loadPostgresChecks(Connection conn, String schema, Map<TableKey, List<String>> checks) throws SQLException {
    String sql = "SELECT nsp.nspname, rel.relname, pg_get_constraintdef(con.oid) as condef " +
                 "FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid " +
                 "JOIN pg_namespace nsp ON nsp.oid = con.connamespace WHERE con.contype = 'c'";
    if (schema != null) sql += " AND nsp.nspname = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      if (schema != null) ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String def = rs.getString("condef");
          Matcher m = POSTGRES_CHECK_PATTERN.matcher(def);
          String clause = m.find() ? m.group(1) : def;
          checks.computeIfAbsent(new TableKey(rs.getString("nspname"), rs.getString("relname")), k -> new ArrayList<>()).add(clause);
        }
      }
    }
  }

  private static void loadH2Checks(Connection conn, String schema, Map<TableKey, List<String>> checks) throws SQLException {
    String sql = "SELECT tc.TABLE_SCHEMA, tc.TABLE_NAME, CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc " +
                 "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME WHERE tc.CONSTRAINT_TYPE = 'CHECK'";
    if (schema != null) sql += " AND tc.TABLE_SCHEMA = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      if (schema != null) ps.setString(1, schema.toUpperCase(Locale.ROOT));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          checks.computeIfAbsent(new TableKey(rs.getString("TABLE_SCHEMA"), rs.getString("TABLE_NAME")), k -> new ArrayList<>()).add(rs.getString("CHECK_CLAUSE"));
        }
      }
    }
  }

  private static String safe(final String s) { return s == null ? "" : s; }

  private record TableRawData(String name, String schema, String remarks) {}
  private record ColumnRawData(String name, int type, String typeName, boolean nullable, int length, int scale, String remarks) {}
  private record TableKey(String schema, String table) {}
}
