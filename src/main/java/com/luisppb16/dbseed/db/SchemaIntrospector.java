/*
 *  Copyright (c) 2025 Luis Pepe.
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SchemaIntrospector {

  public static List<Table> introspect(Connection conn, String schema) throws SQLException {
    DatabaseMetaData meta = conn.getMetaData();
    List<Table> tables = new ArrayList<>();

    try (ResultSet rs = meta.getTables(null, schema, "%", new String[] {"TABLE"})) {
      while (rs.next()) {
        String tableName = rs.getString("TABLE_NAME");

        // load checks once and pass them to column loader and Table constructor
        List<String> checks = loadTableCheckConstraints(conn, meta, schema, tableName);
        List<Column> columns = loadColumns(meta, schema, tableName, checks);
        List<String> pkCols = loadPrimaryKeys(meta, schema, tableName);
        List<ForeignKey> fks = loadForeignKeys(meta, schema, tableName);

        tables.add(new Table(tableName, columns, pkCols, fks, checks));
      }
    }
    return tables;
  }

  private static List<Column> loadColumns(
      DatabaseMetaData meta, String schema, String table, List<String> checkConstraints) throws SQLException {
    List<Column> columns = new ArrayList<>();
    Set<String> pkCols = new LinkedHashSet<>(loadPrimaryKeys(meta, schema, table));

    try (ResultSet rs = meta.getColumns(null, schema, table, "%")) {
      while (rs.next()) {
        String name = rs.getString("COLUMN_NAME");
        int type = rs.getInt("DATA_TYPE");
        boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
        int length = rs.getInt("COLUMN_SIZE");

        int minValue = 0;
        int maxValue = 0;

        int[] bounds = inferBoundsFromChecks(checkConstraints, name);
        if (bounds.length == 2) {
          minValue = bounds[0];
          maxValue = bounds[1];
        }

        Set<String> allowedValues = loadAllowedValues();

        columns.add(
            new Column(
                name,
                type,
                nullable,
                pkCols.contains(name),
                name.toLowerCase(Locale.ROOT).endsWith("guid"),
                length,
                minValue,
                maxValue,
                allowedValues));
      }
    }
    return columns;
  }

  private static List<String> loadPrimaryKeys(DatabaseMetaData meta, String schema, String table)
      throws SQLException {
    List<String> pks = new ArrayList<>();
    try (ResultSet rs = meta.getPrimaryKeys(null, schema, table)) {
      while (rs.next()) {
        pks.add(rs.getString("COLUMN_NAME"));
      }
    }
    return pks;
  }

  private static List<ForeignKey> loadForeignKeys(
      DatabaseMetaData meta, String schema, String table) throws SQLException {
    List<ForeignKey> fks = new ArrayList<>();
    try (ResultSet rs = meta.getImportedKeys(null, schema, table)) {
      while (rs.next()) {
        String fkName = rs.getString("FK_NAME");
        String pkTableName = rs.getString("PKTABLE_NAME");
        Map<String, String> mapping =
            Map.of(rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME"));

        fks.add(new ForeignKey(fkName, pkTableName, mapping, false));
      }
    }
    return fks;
  }

  private static Set<String> loadAllowedValues() {
    return new LinkedHashSet<>();
  }

  private static List<String> loadTableCheckConstraints(
      Connection conn, DatabaseMetaData meta, String schema, String table) throws SQLException {
    List<String> checks = new ArrayList<>();

    String product = safe(meta.getDatabaseProductName()).toLowerCase(Locale.ROOT);
    if (product.contains("postgres")) {
      // Query pg_constraint for check constraints (Postgres-specific)
      String sql =
          "SELECT conname, pg_get_constraintdef(con.oid) as condef "
              + "FROM pg_constraint con "
              + "JOIN pg_class rel ON rel.oid = con.conrelid "
              + "JOIN pg_namespace nsp ON nsp.oid = con.connamespace "
              + "WHERE con.contype = 'c' AND nsp.nspname = ? AND rel.relname = ?";
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, schema == null ? "public" : schema);
        ps.setString(2, table);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String def = rs.getString("condef");
            if (def != null && !def.isBlank()) {
              // pg_get_constraintdef returns strings like "CHECK ((rating >= 1) AND (rating <= 5))"
              // extract expression inside first CHECK(...)
              Matcher m = Pattern.compile("(?i)CHECK\\s*\\((.*)\\)").matcher(def);
              if (m.find()) {
                checks.add(m.group(1));
              } else {
                checks.add(def);
              }
            }
          }
        }
      }
      return checks;
    }

    // Fallback: try to extract from REMARKS (JDBC metadata)
    try (ResultSet trs = meta.getTables(null, schema, table, new String[] {"TABLE"})) {
      while (trs.next()) {
        String remarks = safe(trs.getString("REMARKS"));
        if (!remarks.isEmpty()) {
          extractChecksFromText(remarks, checks);
        }
      }
    }

    try (ResultSet crs = meta.getColumns(null, schema, table, "%")) {
      while (crs.next()) {
        String remarks = safe(crs.getString("REMARKS"));
        if (!remarks.isEmpty()) {
          extractChecksFromText(remarks, checks);
        }
      }
    }

    return checks;
  }

  private static void extractChecksFromText(String text, List<String> out) {
    Pattern pattern = Pattern.compile("(?i)CHECK\\s*\\((.*?)\\)");
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      out.add(matcher.group(1));
    }
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static int[] inferBoundsFromChecks(List<String> checks, String columnName) {
    for (String expr : checks) {
      if (expr == null || expr.isBlank()) continue;

      Pattern pBetween =
          Pattern.compile(
              "(?i)\\b" + Pattern.quote(columnName) + "\\b\\s+BETWEEN\\s+(\\d+)\\s+AND\\s+(\\d+)");
      Matcher mBetween = pBetween.matcher(expr);
      if (mBetween.find()) {
        int min = Integer.parseInt(mBetween.group(1));
        int max = Integer.parseInt(mBetween.group(2));
        return new int[] {min, max};
      }

      Pattern pGteLte =
          Pattern.compile(
              "(?i)\\b"
                  + Pattern.quote(columnName)
                  + "\\b\\s*>?=\\s*(\\d+)\\s*\\)?\\s*AND\\s*\\(?\\b"
                  + Pattern.quote(columnName)
                  + "\\b\\s*<=\\s*(\\d+)");
      Matcher mGteLte = pGteLte.matcher(expr);
      if (mGteLte.find()) {
        int min = Integer.parseInt(mGteLte.group(1));
        int max = Integer.parseInt(mGteLte.group(2));
        return new int[] {min, max};
      }
    }
    return new int[0];
  }
}
