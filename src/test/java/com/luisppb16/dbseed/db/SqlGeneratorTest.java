/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlGeneratorTest {

  @Test
  @DisplayName("Should generate INSERT statements for single row and table")
  void shouldGenerateInsertStatementsSingleRowSingleTable() {
    final Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    final Column name = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    final Table table =
        Table.builder()
            .name("users")
            .columns(List.of(id, name))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    final Map<String, Object> row1Values = new LinkedHashMap<>();
    row1Values.put("id", 1);
    row1Values.put("name", "Alice");
    final Row row1 = new Row(row1Values);

    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(table, List.of(row1));

    final String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    final String expectedSql = "INSERT INTO \"users\" (\"id\", \"name\") VALUES\n(1, 'Alice');\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should handle null values in INSERT statements")
  void shouldHandleNullValues() {
    final Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    final Column name = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    final Column email = Column.builder().name("email").jdbcType(Types.VARCHAR).build();
    final Table table =
        Table.builder()
            .name("users")
            .columns(List.of(id, name, email))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    final Map<String, Object> row1Values = new LinkedHashMap<>();
    row1Values.put("id", 2);
    row1Values.put("name", "Bob");
    row1Values.put("email", null);
    final Row row1 = new Row(row1Values);

    final Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(table, List.of(row1));

    final String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    final String expectedSql =
        "INSERT INTO \"users\" (\"id\", \"name\", \"email\") VALUES\n(2, 'Bob', NULL);\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should use MySQL dialect quoting and constraints")
  void shouldUseMySqlDialect() {
    final DriverInfo driver = DriverInfo.builder().driverClass("com.mysql.cj.jdbc.Driver").build();
    final Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    final Table table =
        Table.builder()
            .name("users")
            .columns(List.of(id))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    final Map<String, Object> rowValues = new LinkedHashMap<>();
    rowValues.put("id", 1);
    final Row row = new Row(rowValues);

    final Map<Table, List<Row>> data = Map.of(table, List.of(row));

    final String sql = SqlGenerator.generate(data, Collections.emptyList(), true, driver);

    assertTrue(sql.contains("START TRANSACTION;"));
    assertTrue(sql.contains("SET FOREIGN_KEY_CHECKS = 0;"));
    assertTrue(sql.contains("INSERT INTO `users` (`id`) VALUES"));
    assertTrue(sql.contains("SET FOREIGN_KEY_CHECKS = 1;"));
  }

  @Test
  @DisplayName("Should use SQL Server dialect quoting and constraints")
  void shouldUseSqlServerDialect() {
    final DriverInfo driver =
        DriverInfo.builder().driverClass("com.microsoft.sqlserver.jdbc.SQLServerDriver").build();
    final Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    final Table table =
        Table.builder()
            .name("users")
            .columns(List.of(id))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    final Map<String, Object> rowValues = new LinkedHashMap<>();
    rowValues.put("id", 1);
    final Row row = new Row(rowValues);

    final Map<Table, List<Row>> data = Map.of(table, List.of(row));

    final String sql = SqlGenerator.generate(data, Collections.emptyList(), true, driver);

    assertTrue(sql.contains("BEGIN TRANSACTION;"));
    assertTrue(sql.contains("EXEC sp_msforeachtable 'ALTER TABLE ? NOCHECK CONSTRAINT ALL';"));
    assertTrue(sql.contains("INSERT INTO [users] ([id]) VALUES"));
    assertTrue(sql.contains("EXEC sp_msforeachtable 'ALTER TABLE ? WITH CHECK CHECK CONSTRAINT ALL';"));
  }

  @Test
  @DisplayName("Should use Oracle dialect quoting and PL/SQL blocks")
  void shouldUseOracleDialect() {
    final DriverInfo driver = DriverInfo.builder().driverClass("oracle.jdbc.OracleDriver").build();
    final Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    final Table table =
        Table.builder()
            .name("users")
            .columns(List.of(id))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    final Map<String, Object> rowValues = new LinkedHashMap<>();
    rowValues.put("id", 1);
    final Row row = new Row(rowValues);

    final Map<Table, List<Row>> data = Map.of(table, List.of(row));

    final String sql = SqlGenerator.generate(data, Collections.emptyList(), true, driver);

    assertTrue(sql.contains("SET TRANSACTION READ WRITE;"));
    assertTrue(sql.contains("BEGIN\n  FOR c IN (SELECT table_name, constraint_name FROM user_constraints"));
    assertTrue(sql.contains("INSERT INTO \"USERS\" (\"ID\") VALUES"));
    assertTrue(sql.contains("END;\n/\n"));
  }

  @Test
  @DisplayName("Should use SQLite dialect and PRAGMA commands")
  void shouldUseSqliteDialect() {
    final DriverInfo driver = DriverInfo.builder().urlTemplate("jdbc:sqlite:test.db").build();
    final Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    final Table table =
        Table.builder()
            .name("users")
            .columns(List.of(id))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    final Map<String, Object> rowValues = new LinkedHashMap<>();
    rowValues.put("id", 1);
    final Row row = new Row(rowValues);

    final Map<Table, List<Row>> data = Map.of(table, List.of(row));

    final String sql = SqlGenerator.generate(data, Collections.emptyList(), true, driver);

    assertTrue(sql.contains("BEGIN TRANSACTION;"));
    assertTrue(sql.contains("PRAGMA foreign_keys = OFF;"));
    assertTrue(sql.contains("INSERT INTO \"users\" (\"id\") VALUES"));
    assertTrue(sql.contains("PRAGMA foreign_keys = ON;"));
  }

  @Test
  @DisplayName("Should handle special characters and reserved keywords in identifiers")
  void shouldHandleReservedKeywords() {
    final Column publicCol = Column.builder().name("public").jdbcType(Types.VARCHAR).build();
    final Table table =
        Table.builder()
            .name("order")
            .columns(List.of(publicCol))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    final Map<String, Object> values = new LinkedHashMap<>();
    values.put("public", "value");
    final Row row = new Row(values);

    final String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), false);

    assertTrue(sql.contains("\"order\""));
    assertTrue(sql.contains("\"public\""));
  }

  @Test
  @DisplayName("Should format different data types correctly")
  void shouldFormatDataTypes() {
    final Column colDate = Column.builder().name("dob").jdbcType(Types.DATE).build();
    final Column colBool = Column.builder().name("active").jdbcType(Types.BOOLEAN).build();
    final Column colDecimal = Column.builder().name("price").jdbcType(Types.DECIMAL).build();

    final Table table =
        Table.builder()
            .name("types_table")
            .columns(List.of(colDate, colBool, colDecimal))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    final Map<String, Object> values = new LinkedHashMap<>();
    values.put("dob", Date.valueOf("2023-01-01"));
    values.put("active", true);
    values.put("price", new BigDecimal("123456789.12"));
    final Row row = new Row(values);

    final String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), false);

    assertTrue(sql.contains("'2023-01-01'"));
    assertTrue(sql.contains("TRUE"));
    assertTrue(sql.contains("123456789.12"));
  }

  @Test
  @DisplayName("Should handle batched INSERT statements")
  void shouldGenerateBatchedInsertStatements() {
    final Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    final Table table =
        Table.builder()
            .name("large_table")
            .columns(List.of(id))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    final List<Row> rows =
        IntStream.rangeClosed(1, 1001)
            .mapToObj(i -> {
              final Map<String, Object> v = new LinkedHashMap<>();
              v.put("id", i);
              return new Row(v);
            })
            .collect(Collectors.toList());

    final String sql = SqlGenerator.generate(Map.of(table, rows), Collections.emptyList(), false);

    final int insertCount = (sql.split("INSERT INTO").length) - 1;
    assertEquals(2, insertCount, "Should have 2 INSERT statements for 1001 rows with batch size 1000");
  }
}
