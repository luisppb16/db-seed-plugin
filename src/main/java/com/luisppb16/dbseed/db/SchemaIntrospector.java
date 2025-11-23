/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class SchemaIntrospector {

  private static final String COLUMN_NAME = "COLUMN_NAME";

  public static List<Table> introspect(final Connection conn, final String schema)
      throws SQLException {
    Objects.requireNonNull(conn, "Connection cannot be null");
    final DatabaseMetaData meta = conn.getMetaData();
    final List<Table> tables = new ArrayList<>();

    try (final ResultSet rs = meta.getTables(null, schema, "%", new String[] {"TABLE"})) {
      while (rs.next()) {
        final String tableName = rs.getString("TABLE_NAME");
        final String tableSchema = rs.getString("TABLE_SCHEM");
        final String effectiveSchema = tableSchema != null ? tableSchema : schema;

        final List<String> checks = loadTableCheckConstraints(conn, meta, effectiveSchema, tableName);
        final List<Column> columns = loadColumns(meta, effectiveSchema, tableName, checks);
        final List<String> pkCols = loadPrimaryKeys(meta, effectiveSchema, tableName);
        final List<List<String>> uniqueKeys = loadUniqueKeys(meta, effectiveSchema, tableName);
        final List<ForeignKey> fks = loadForeignKeys(meta, effectiveSchema, tableName, uniqueKeys);

        tables.add(new Table(tableName, columns, pkCols, fks, checks, uniqueKeys));
      }
    }
    return tables;
  }

  private static List<Column> loadColumns(
      final DatabaseMetaData meta,
      final String schema,
      final String table,
      final List<String> checkConstraints)
      throws SQLException {
    final List<Column> columns = new ArrayList<>();
    final Set<String> pkCols = new LinkedHashSet<>(loadPrimaryKeys(meta, schema, table));

    try (final ResultSet rs = meta.getColumns(null, schema, table, "%")) {
      while (rs.next()) {
        final String name = rs.getString(COLUMN_NAME);
        final int type = rs.getInt("DATA_TYPE");
        final String typeName = rs.getString("TYPE_NAME"); // Fetch type name
        final boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
        final int length = rs.getInt("COLUMN_SIZE");

        int minValue = 0;
        int maxValue = 0;

        final int[] bounds = inferBoundsFromChecks(checkConstraints, name);
        if (bounds.length == 2) {
          minValue = bounds[0];
          maxValue = bounds[1];
        }

        final Set<String> allowedValues = loadAllowedValues();
        final boolean isUuid =
            name.toLowerCase(Locale.ROOT).endsWith("guid")
                || name.toLowerCase(Locale.ROOT).endsWith("uuid") // Check for UUID name convention
                || type == java.sql.Types.OTHER // H2 often maps UUID to OTHER
                || (typeName != null && typeName.toLowerCase(Locale.ROOT).contains("uuid"));

        columns.add(
            new Column(
                name,
                type,
                nullable,
                pkCols.contains(name),
                isUuid,
                length,
                minValue,
                maxValue,
                allowedValues));
      }
    }
    return columns;
  }

  private static List<String> loadPrimaryKeys(
      final DatabaseMetaData meta, final String schema, final String table) throws SQLException {
    final List<String> pks = new ArrayList<>();
    try (final ResultSet rs = meta.getPrimaryKeys(null, schema, table)) {
      while (rs.next()) {
        pks.add(rs.getString(COLUMN_NAME));
      }
    }
    return pks;
  }

  private static List<ForeignKey> loadForeignKeys(
      final DatabaseMetaData meta,
      final String schema,
      final String table,
      final List<List<String>> uniqueKeys)
      throws SQLException {
    final List<ForeignKey> fks = new ArrayList<>();
    try (final ResultSet rs = meta.getImportedKeys(null, schema, table)) {
      final Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
      final Map<String, String> fkToPkTable = new HashMap<>();
      while (rs.next()) {
        final String fkName = rs.getString("FK_NAME");
        final String fkCol = rs.getString("FKCOLUMN_NAME");
        final String pkCol = rs.getString("PKCOLUMN_NAME");
        final String pkTableName = rs.getString("PKTABLE_NAME");
        fkToPkTable.put(fkName, pkTableName);
        grouped.computeIfAbsent(fkName, k -> new LinkedHashMap<>()).put(fkCol, pkCol);
      }

      for (final Map.Entry<String, Map<String, String>> e : grouped.entrySet()) {
        final String fkName = e.getKey();
        final Map<String, String> mapping = Map.copyOf(e.getValue());
        final String pkTableName = fkToPkTable.get(fkName);
        boolean uniqueOnFk = isUniqueForeignKey(mapping.keySet(), uniqueKeys);
        fks.add(new ForeignKey(fkName, pkTableName, mapping, uniqueOnFk));
      }
    }
    return fks;
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

  private static List<List<String>> loadUniqueKeys(
      final DatabaseMetaData meta, final String schema, final String table) throws SQLException {
    final Map<String, List<String>> idxCols = new LinkedHashMap<>();
    try (final ResultSet rs = meta.getIndexInfo(null, schema, table, true, false)) {
      while (rs.next()) {
        final String idxName = rs.getString("INDEX_NAME");
        final String colName = rs.getString(COLUMN_NAME);
        if (idxName == null || colName == null) continue;
        idxCols.computeIfAbsent(idxName, k -> new ArrayList<>()).add(colName);
      }
    }
    return idxCols.values().stream().map(List::copyOf).toList();
  }

  private static Set<String> loadAllowedValues() {
    return new LinkedHashSet<>();
  }

  private static List<String> loadTableCheckConstraints(
      final Connection conn, final DatabaseMetaData meta, final String schema, final String table)
      throws SQLException {
    final List<String> checks = new ArrayList<>();

    final String product = safe(meta.getDatabaseProductName()).toLowerCase(Locale.ROOT);
    if (product.contains("postgres")) {
      loadPostgresCheckConstraints(conn, schema, table, checks);
    } else if (product.contains("h2")) {
      loadH2CheckConstraints(conn, schema, table, checks);
    } else {
      loadGenericCheckConstraints(meta, schema, table, checks);
    }

    return checks;
  }

  private static void loadH2CheckConstraints(
      final Connection conn, final String schema, final String table, final List<String> checks)
      throws SQLException {
    final String sql =
        "SELECT CHECK_CLAUSE "
            + "FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc "
            + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME "
            + "WHERE tc.TABLE_SCHEMA = ? AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK'";
    try (final PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, schema == null ? "PUBLIC" : schema.toUpperCase(Locale.ROOT));
      ps.setString(2, table.toUpperCase(Locale.ROOT));
      try (final ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          final String clause = rs.getString("CHECK_CLAUSE");
          if (clause != null && !clause.isBlank()) {
            // H2 returns raw expression like "(AGE >= 18) AND (AGE <= 100)"
            checks.add(clause);
          }
        }
      }
    } catch (SQLException e) {
      log.warn(
          "Failed to load H2 check constraints for table {} in schema {}", table, schema, e);
      // Propagate or just warn? For introspection, missing checks might be better than full failure.
      // But typically introspection should be consistent.
      // We'll throw to be safe, but log first.
      throw e;
    }
  }

  private static void loadPostgresCheckConstraints(
      final Connection conn, final String schema, final String table, final List<String> checks)
      throws SQLException {
    final String sql =
        "SELECT conname, pg_get_constraintdef(con.oid) as condef "
            + "FROM pg_constraint con "
            + "JOIN pg_class rel ON rel.oid = con.conrelid "
            + "JOIN pg_namespace nsp ON nsp.oid = con.connamespace "
            + "WHERE con.contype = 'c' AND nsp.nspname = ? AND rel.relname = ?";
    try (final PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, schema == null ? "public" : schema);
      ps.setString(2, table);
      try (final ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          final String def = rs.getString("condef");
          if (def != null && !def.isBlank()) {
            final Matcher m = Pattern.compile("(?i)CHECK\\s*\\((.*)\\)").matcher(def);
            if (m.find()) {
              checks.add(m.group(1));
            } else {
              checks.add(def);
            }
          }
        }
      }
    }
  }

  private static void loadGenericCheckConstraints(
      final DatabaseMetaData meta,
      final String schema,
      final String table,
      final List<String> checks)
      throws SQLException {
    try (final ResultSet trs = meta.getTables(null, schema, table, new String[] {"TABLE"})) {
      if (trs.next()) {
        final String remarks = safe(trs.getString("REMARKS"));
        if (!remarks.isEmpty()) {
          extractChecksFromText(remarks, checks);
        }
      }
    }

    try (final ResultSet crs = meta.getColumns(null, schema, table, "%")) {
      while (crs.next()) {
        final String remarks = safe(crs.getString("REMARKS"));
        if (!remarks.isEmpty()) {
          extractChecksFromText(remarks, checks);
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
    for (final String expr : checks) {
      if (expr == null || expr.isBlank()) continue;

      final int[] betweenBounds =
          parseBounds(
              expr,
              "(?i)\\b" + Pattern.quote(columnName) + "\\b\\s+BETWEEN\\s+(\\d+)\\s+AND\\s+(\\d+)");
      if (betweenBounds.length == 2) return betweenBounds;

      final int[] gteLteBounds =
          parseBounds(
              expr,
              "(?i)\\b"
                  + Pattern.quote(columnName)
                  + "\\b\\s*>?=\\s*(\\d+)\\s*\\)?\\s*AND\\s*\\(?\\b"
                  + Pattern.quote(columnName)
                  + "\\b\\s*<=\\s*(\\d+)");
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
