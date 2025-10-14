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

        List<Column> columns = loadColumns(conn, meta, schema, tableName);
        List<String> pkCols = loadPrimaryKeys(meta, schema, tableName);
        List<ForeignKey> fks = loadForeignKeys(meta, schema, tableName);

        tables.add(new Table(tableName, columns, pkCols, fks));
      }
    }
    return tables;
  }

  private static List<Column> loadColumns(
      Connection conn, DatabaseMetaData meta, String schema, String table) throws SQLException {
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

        Set<String> allowedValues = loadAllowedValues(conn, schema, table, name);

        columns.add(
            new Column(
                name,
                type,
                nullable,
                pkCols.contains(name),
                // Heuristic: if the name ends with "guid", we treat the column as a UUID.
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

  private static Set<String> loadAllowedValues(
      Connection conn, String schema, String table, String column) throws SQLException {
    Set<String> result = new LinkedHashSet<>();

    // 1. ENUM types.
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
                         SELECT e.enumlabel
                         FROM pg_type t
                         JOIN pg_enum e ON t.oid = e.enumtypid
                         JOIN pg_attribute a ON a.atttypid = t.oid
                         JOIN pg_class c ON c.oid = a.attrelid
                         JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = ? AND c.relname = ? AND a.attname = ?
                         ORDER BY e.enumsortorder
                         """)) {
      ps.setString(1, schema);
      ps.setString(2, table);
      ps.setString(3, column);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }

    // 2. CHECK constraints with IN (...) translated as ARRAY[...] in PostgreSQL.
    if (result.isEmpty()) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              """
                           SELECT pg_get_expr(co.conbin, co.conrelid) as expr
                           FROM pg_constraint co
                           JOIN pg_class c ON c.oid = co.conrelid
                           JOIN pg_namespace n ON n.oid = c.relnamespace
                           WHERE n.nspname = ? AND c.relname = ?
                             AND array_position(co.conkey, (
                                 SELECT attnum
                                 FROM pg_attribute
                                 WHERE attrelid = c.oid AND attname = ?
                             )) IS NOT NULL
                             AND contype = 'c'
                           """)) {
        ps.setString(1, schema);
        ps.setString(2, table);
        ps.setString(3, column);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String expr = rs.getString("expr");
            if (expr == null) continue;
            var matcher = Pattern.compile("ARRAY\\[(.*?)\\]").matcher(expr);
            if (matcher.find()) {
              String inside = matcher.group(1);
              for (String val : inside.split(",")) {
                result.add(val.replace("::text", "").replace("'", "").trim());
              }
            }
          }
        }
      }
    }

    return result;
  }
}
