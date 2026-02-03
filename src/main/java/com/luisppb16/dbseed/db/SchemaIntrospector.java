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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

@Slf4j
@UtilityClass
public class SchemaIntrospector {

  private static final String COLUMN_NAME = "COLUMN_NAME";
  private static final String TABLE_NAME = "TABLE_NAME";

  private record TableRawData(String name, String schema, String remarks) {}

  private record ColumnRawData(
      String name,
      int type,
      String typeName,
      boolean nullable,
      int length,
      int scale,
      String remarks) {}

  private record TableKey(String schema, String table) {}

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
    final Map<TableKey, List<List<String>>> allUniqueKeys = loadAllUniqueKeys(meta, schema, rawTables);
    final Map<TableKey, List<ForeignKey>> allFks =
        loadAllForeignKeys(meta, schema, rawTables, allUniqueKeys);

    for (final TableRawData tableData : rawTables) {
      final String tableName = tableData.name();
      final String tableSchema = tableData.schema();
      final TableKey key = new TableKey(tableSchema, tableName);

      final List<String> checks = allChecks.getOrDefault(key, Collections.emptyList());
      final List<ColumnRawData> tableCols =
          rawColumns.getOrDefault(key, Collections.emptyList());

      final List<String> pkCols = allPks.getOrDefault(key, Collections.emptyList());

      final List<Column> columns = buildColumns(tableCols, pkCols, checks);
      final List<List<String>> uniqueKeys = allUniqueKeys.getOrDefault(key, Collections.emptyList());
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
      final List<String> pkCols,
      final List<String> checkConstraints) {
    final List<Column> columns = new ArrayList<>();
    final Set<String> pkSet = new LinkedHashSet<>(pkCols);

    for (final ColumnRawData raw : rawColumns) {
      int minValue = 0;
      int maxValue = 0;

      final int[] bounds = inferBoundsFromChecks(checkConstraints, raw.name());
      if (bounds.length == 2) {
        minValue = bounds[0];
        maxValue = bounds[1];
      }

      final Set<String> allowedValues = inferAllowedValuesFromChecks(checkConstraints, raw.name());
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
              minValue,
              maxValue,
              allowedValues));
    }
    return columns;
  }

  private static Map<TableKey, List<String>> loadAllPrimaryKeys(
      final DatabaseMetaData meta, final String schema, final List<TableRawData> rawTables)
      throws SQLException {
    final Map<TableKey, List<String>> map = new LinkedHashMap<>();
    boolean bulkSuccess = false;
    try (final ResultSet rs = meta.getPrimaryKeys(null, schema, null)) {
      while (rs.next()) {
        final String tableName = rs.getString(TABLE_NAME);
        final String tableSchema = rs.getString("TABLE_SCHEM");
        final TableKey key = new TableKey(tableSchema, tableName);
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getString(COLUMN_NAME));
      }
      bulkSuccess = true;
    } catch (final SQLException e) {
      log.debug("Bulk loading of primary keys failed, falling back to N+1", e);
    }

    if (!bulkSuccess) {
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

    boolean bulkSuccess = false;
    try (final ResultSet rs = meta.getImportedKeys(null, schema, null)) {
      while (rs.next()) {
        processFkResultSet(rs, groupedMappings, fkToPkTable);
      }
      bulkSuccess = true;
    } catch (final SQLException e) {
      log.debug("Bulk loading of foreign keys failed, falling back to N+1", e);
    }

    if (!bulkSuccess) {
      for (final TableRawData table : rawTables) {
        try (final ResultSet rs = meta.getImportedKeys(null, schema, table.name())) {
          while (rs.next()) {
            processFkResultSet(rs, groupedMappings, fkToPkTable);
          }
        }
      }
    }

    final Map<TableKey, List<ForeignKey>> result = new LinkedHashMap<>();
    for (Map.Entry<TableKey, Map<String, Map<String, String>>> tableEntry :
        groupedMappings.entrySet()) {
      final TableKey key = tableEntry.getKey();
      final List<ForeignKey> fks = new ArrayList<>();
      final Map<String, Map<String, String>> fksForTable = tableEntry.getValue();
      final List<List<String>> uniqueKeys =
          allUniqueKeys.getOrDefault(key, Collections.emptyList());

      for (Map.Entry<String, Map<String, String>> fkEntry : fksForTable.entrySet()) {
        final String fkName = fkEntry.getKey();
        final Map<String, String> mapping = Map.copyOf(fkEntry.getValue());
        final String pkTableName = fkToPkTable.get(key).get(fkName);
        boolean uniqueOnFk = isUniqueForeignKey(mapping.keySet(), uniqueKeys);
        fks.add(new ForeignKey(fkName, pkTableName, mapping, uniqueOnFk));
      }
      result.put(key, fks);
    }
    return result;
  }

  private static void processFkResultSet(
      final ResultSet rs,
      final Map<TableKey, Map<String, Map<String, String>>> groupedMappings,
      final Map<TableKey, Map<String, String>> fkToPkTable)
      throws SQLException {
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

  private static boolean isUniqueForeignKey(
      final Set<String> fkCols, final List<List<String>> uniqueKeys) {
    for (final List<String> uk : uniqueKeys) {
      if (uk.size() == fkCols.size() && fkCols.containsAll(uk)) {
        return true;
      }
    }
    return false;
  }

  private static Map<TableKey, List<List<String>>> loadAllUniqueKeys(
      final DatabaseMetaData meta, final String schema, final List<TableRawData> rawTables)
      throws SQLException {
    final Map<TableKey, Map<String, List<String>>> idxCols = new LinkedHashMap<>();

    boolean bulkSuccess = false;
    try (final ResultSet rs = meta.getIndexInfo(null, schema, null, true, false)) {
      while (rs.next()) {
        processIndexResultSet(rs, idxCols);
      }
      bulkSuccess = true;
    } catch (final SQLException e) {
      log.debug("Bulk loading of unique keys failed, falling back to N+1", e);
    }

    if (!bulkSuccess || (idxCols.isEmpty() && !rawTables.isEmpty())) {
      idxCols.clear();
      for (final TableRawData tableData : rawTables) {
        try (final ResultSet rs = meta.getIndexInfo(null, schema, tableData.name(), true, false)) {
          while (rs.next()) {
            processIndexResultSet(rs, idxCols);
          }
        }
      }
    }

    final Map<TableKey, List<List<String>>> result = new LinkedHashMap<>();
    for (Map.Entry<TableKey, Map<String, List<String>>> entry : idxCols.entrySet()) {
      final List<List<String>> list = entry.getValue().values().stream().map(List::copyOf).toList();
      result.put(entry.getKey(), list);
    }
    return result;
  }

  private static void processIndexResultSet(
      final ResultSet rs, final Map<TableKey, Map<String, List<String>>> idxCols)
      throws SQLException {
    final String tableName = rs.getString(TABLE_NAME);
    final String tableSchema = rs.getString("TABLE_SCHEM");
    final String idxName = rs.getString("INDEX_NAME");
    final String colName = rs.getString(COLUMN_NAME);
    if (idxName == null || colName == null) return;

    final TableKey key = new TableKey(tableSchema, tableName);
    idxCols
        .computeIfAbsent(key, k -> new LinkedHashMap<>())
        .computeIfAbsent(idxName, k -> new ArrayList<>())
        .add(colName);
  }

  private static Set<String> inferAllowedValuesFromChecks(
      final List<String> checks, final String columnName) {
    final Set<String> allowed = new HashSet<>();
    final String colPattern =
        "(?i)(?:[A-Za-z0-9_]+\\.)*\\s*\"?".concat(Pattern.quote(columnName)).concat("\"?\\s*");
    final String castPattern = "(?:\\s*::[a-zA-Z0-9 ]+)*";

    final Pattern inPattern =
        Pattern.compile(
            colPattern.concat(castPattern).concat("\\s+IN\\s*\\(([^)]+)\\)"),
            Pattern.CASE_INSENSITIVE);
    final Pattern anyArrayPattern =
        Pattern.compile(
            colPattern.concat(castPattern).concat("\\s*=\\s*ANY\\s+ARRAY\\s*\\[(.*?)\\]"),
            Pattern.CASE_INSENSITIVE);
    final Pattern eqPattern =
        Pattern.compile(
            colPattern
                .concat(castPattern)
                .concat("\\s*=\\s*(?!ANY\\b)('.*?'|\".*?\"|[0-9A-Za-z_+-]+)"),
            Pattern.CASE_INSENSITIVE);

    for (final String check : checks) {
      if (check == null || check.isBlank()) continue;

      final Matcher mi = inPattern.matcher(check);
      while (mi.find()) {
        final String inside = mi.group(1);
        Arrays.stream(inside.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(
                s -> {
                  if ((s.startsWith("'") && s.endsWith("'"))
                      || (s.startsWith("\"") && s.endsWith("\""))) {
                    return s.substring(1, s.length() - 1);
                  }
                  return s;
                })
            .forEach(allowed::add);
      }

      final String exprNoParens = check.replaceAll("[()]+", " ");
      final Matcher ma = anyArrayPattern.matcher(exprNoParens);
      while (ma.find()) {
        final String inside = ma.group(1);
        Arrays.stream(inside.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.replaceAll("(?i)::[a-z ]+", ""))
            .map(
                s -> {
                  if ((s.startsWith("'") && s.endsWith("'"))
                      || (s.startsWith("\"") && s.endsWith("\""))) {
                    return s.substring(1, s.length() - 1);
                  }
                  return s;
                })
            .forEach(allowed::add);
      }

      final Matcher me = eqPattern.matcher(exprNoParens);
      while (me.find()) {
        String s = me.group(1).trim();
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
          allowed.add(s.substring(1, s.length() - 1));
        } else if (!s.equalsIgnoreCase("NULL")) {
          allowed.add(s);
        }
      }
    }
    return allowed;
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
      loadAllPostgresCheckConstraints(conn, schema, checks);
    } else if (product.contains("h2")) {
      loadAllH2CheckConstraints(conn, schema, checks);
    } else {
      loadGenericCheckConstraints(tables, columns, checks);
    }
    return checks;
  }

  private static void loadGenericCheckConstraints(
      final List<TableRawData> tables,
      final Map<TableKey, List<ColumnRawData>> columns,
      final Map<TableKey, List<String>> checks) {
    for (final TableRawData table : tables) {
      final TableKey key = new TableKey(table.schema(), table.name());
      final List<String> tableChecks = checks.computeIfAbsent(key, k -> new ArrayList<>());
      if (!table.remarks().isEmpty()) {
        extractChecksFromText(table.remarks(), tableChecks);
      }
      final List<ColumnRawData> cols = columns.get(key);
      if (cols != null) {
        for (final ColumnRawData col : cols) {
          if (!col.remarks().isEmpty()) {
            extractChecksFromText(col.remarks(), tableChecks);
          }
        }
      }
    }
  }

  private static void loadAllH2CheckConstraints(
      final Connection conn, final String schema, final Map<TableKey, List<String>> checks)
      throws SQLException {
    final StringBuilder sql =
        new StringBuilder(
            "SELECT tc.TABLE_SCHEMA, tc.TABLE_NAME, CHECK_CLAUSE "
                + "FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc "
                + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME "
                + "WHERE tc.CONSTRAINT_TYPE = 'CHECK'");

    if (schema != null) {
      sql.append(" AND tc.TABLE_SCHEMA = ?");
    }

    try (final PreparedStatement ps = conn.prepareStatement(sql.toString())) {
      if (schema != null) {
        ps.setString(1, schema.toUpperCase(Locale.ROOT));
      }
      try (final ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          final String tableSchema = rs.getString("TABLE_SCHEMA");
          final String tableName = rs.getString(TABLE_NAME);
          final String clause = rs.getString("CHECK_CLAUSE");
          if (clause != null && !clause.isBlank()) {
            final TableKey key = new TableKey(tableSchema, tableName);
            checks.computeIfAbsent(key, k -> new ArrayList<>()).add(clause);
          }
        }
      }
    } catch (SQLException e) {
      log.warn("Failed to load H2 check constraints for schema {}", schema, e);
      throw e;
    }
  }

  private static void loadAllPostgresCheckConstraints(
      final Connection conn, final String schema, final Map<TableKey, List<String>> checks)
      throws SQLException {
    final StringBuilder sql =
        new StringBuilder(
            "SELECT nsp.nspname, rel.relname, pg_get_constraintdef(con.oid) as condef "
                + "FROM pg_constraint con "
                + "JOIN pg_class rel ON rel.oid = con.conrelid "
                + "JOIN pg_namespace nsp ON nsp.oid = con.connamespace "
                + "WHERE con.contype = 'c'");

    if (schema != null) {
      sql.append(" AND nsp.nspname = ?");
    }

    try (final PreparedStatement ps = conn.prepareStatement(sql.toString())) {
      if (schema != null) {
        ps.setString(1, schema);
      }
      try (final ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          final String tableSchema = rs.getString("nspname");
          final String tableName = rs.getString("relname");
          final String def = rs.getString("condef");
          if (def != null && !def.isBlank()) {
            final TableKey key = new TableKey(tableSchema, tableName);
            final List<String> list = checks.computeIfAbsent(key, k -> new ArrayList<>());
            final Matcher m = Pattern.compile("(?i)CHECK\\s*\\((.*)\\)").matcher(def);
            if (m.find()) {
              list.add(m.group(1));
            } else {
              list.add(def);
            }
          }
        }
      }
    }
  }

  private static void extractChecksFromText(final String text, final List<String> out) {
    final Pattern pattern = Pattern.compile("(?i)CHECK\\s*\\((.*?)\\)");
    final Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      out.add(matcher.group(1));
    }
  }

  private static String safe(final String s) {
    return s == null ? "" : s;
  }

  private static int[] inferBoundsFromChecks(final List<String> checks, final String columnName) {
    final String colPattern =
        "(?i)(?:[A-Za-z0-9_]+\\.)*\\s*\"?".concat(Pattern.quote(columnName)).concat("\"?\\s*");
    final String castPattern = "(?:\\s*::[a-zA-Z0-9 ]+)*";

    for (final String check : checks) {
      if (check == null || check.isBlank()) continue;
      final String exprNoParens = check.replaceAll("[()]+", " ");

      final int[] betweenBounds =
          parseBounds(
              exprNoParens,
              colPattern
                  .concat(castPattern)
                  .concat("\\s+BETWEEN\\s+([-+]?[0-9]+)\\s+AND\\s+([-+]?[0-9]+)"));
      if (betweenBounds.length == 2) return betweenBounds;

      final int[] gteLteBounds =
          parseBounds(
              exprNoParens,
              colPattern
                  .concat(castPattern)
                  .concat("\\s*>=?\\s*([-+]?[0-9]+)\\s*AND\\s*")
                  .concat(colPattern)
                  .concat(castPattern)
                  .concat("\\s*<=?\\s*([-+]?[0-9]+)"));
      if (gteLteBounds.length == 2) return gteLteBounds;
    }
    return new int[0];
  }

  private static int[] parseBounds(final String expr, final String regex) {
    final Pattern pattern = Pattern.compile(regex);
    final Matcher matcher = pattern.matcher(expr);
    if (matcher.find()) {
      final int min = Integer.parseInt(matcher.group(1));
      final int max = Integer.parseInt(matcher.group(2));
      return new int[] {min, max};
    }
    return new int[0];
  }
}
