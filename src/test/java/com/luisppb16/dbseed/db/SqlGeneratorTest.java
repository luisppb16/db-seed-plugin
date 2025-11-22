/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
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
    Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Column name = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    Table table =
        Table.builder()
            .name("users")
            .columns(List.of(id, name))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> row1Values = new LinkedHashMap<>();
    row1Values.put("id", 1);
    row1Values.put("name", "Alice");
    Row row1 = new Row(row1Values);

    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(table, List.of(row1));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql = "INSERT INTO \"users\" (\"id\", \"name\") VALUES\n(1, 'Alice');\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should handle null values in INSERT statements")
  void shouldHandleNullValues() {
    Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Column name = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    Column email = Column.builder().name("email").jdbcType(Types.VARCHAR).build();
    Table table =
        Table.builder()
            .name("users")
            .columns(List.of(id, name, email))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> row1Values = new LinkedHashMap<>();
    row1Values.put("id", 2);
    row1Values.put("name", "Bob");
    row1Values.put("email", null); // Null value
    Row row1 = new Row(row1Values);

    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(table, List.of(row1));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql =
        "INSERT INTO \"users\" (\"id\", \"name\", \"email\") VALUES\n(2, 'Bob', NULL);\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should generate INSERT statements for multiple rows in a single table")
  void shouldGenerateInsertStatementsMultipleRowsSingleTable() {
    Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Column name = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    Table table =
        Table.builder()
            .name("products")
            .columns(List.of(id, name))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> row1Values = new LinkedHashMap<>();
    row1Values.put("id", 101);
    row1Values.put("name", "Laptop");
    Row row1 = new Row(row1Values);

    Map<String, Object> row2Values = new LinkedHashMap<>();
    row2Values.put("id", 102);
    row2Values.put("name", "Mouse");
    Row row2 = new Row(row2Values);

    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(table, List.of(row1, row2));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql =
        """
        INSERT INTO "products" ("id", "name") VALUES
        (101, 'Laptop'),
        (102, 'Mouse');
        """;
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should generate INSERT statements for multiple tables")
  void shouldGenerateInsertStatementsMultipleTables() {
    Column userId = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Column userName = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    Table usersTable =
        Table.builder()
            .name("users")
            .columns(List.of(userId, userName))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> userRowValues = new LinkedHashMap<>();
    userRowValues.put("id", 1);
    userRowValues.put("name", "Alice");
    Row userRow = new Row(userRowValues);

    Column productId = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Column productName = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    Table productsTable =
        Table.builder()
            .name("products")
            .columns(List.of(productId, productName))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> productRowValues = new LinkedHashMap<>();
    productRowValues.put("id", 101);
    productRowValues.put("name", "Keyboard");
    Row productRow = new Row(productRowValues);

    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(usersTable, List.of(userRow));
    data.put(productsTable, List.of(productRow));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql =
        """
        INSERT INTO "users" ("id", "name") VALUES
        (1, 'Alice');
        INSERT INTO "products" ("id", "name") VALUES
        (101, 'Keyboard');
        """;
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should format different data types correctly")
  void shouldFormatDataTypes() {
    Column colDate = Column.builder().name("dob").jdbcType(Types.DATE).build();
    Column colTs = Column.builder().name("created").jdbcType(Types.TIMESTAMP).build();
    Column colBool = Column.builder().name("active").jdbcType(Types.BOOLEAN).build();
    Column colUuid = Column.builder().name("uid").jdbcType(Types.OTHER).build();
    Column colInt = Column.builder().name("count").jdbcType(Types.INTEGER).build();
    Column colDouble = Column.builder().name("price").jdbcType(Types.DOUBLE).build();

    Table table =
        Table.builder()
            .name("types_table")
            .columns(List.of(colDate, colTs, colBool, colUuid, colInt, colDouble))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Date date = Date.valueOf("2023-01-01");
    Timestamp ts = Timestamp.valueOf("2023-01-01 10:00:00");
    UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    Map<String, Object> values = new LinkedHashMap<>();
    values.put("dob", date);
    values.put("created", ts);
    values.put("active", true);
    values.put("uid", uuid);
    values.put("count", 123);
    values.put("price", 99.99);
    Row row = new Row(values);

    Map<Table, List<Row>> data = Map.of(table, List.of(row));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql =
        "INSERT INTO \"types_table\" (\"dob\", \"created\", \"active\", \"uid\", \"count\", \"price\") VALUES\n('2023-01-01', '2023-01-01 10:00:00.0', TRUE, '550e8400-e29b-41d4-a716-446655440000', 123, 99.99);\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should format more numeric and other basic data types correctly")
  void shouldFormatMoreDataTypesCorrectly() {
    Column colBigInt = Column.builder().name("big_num").jdbcType(Types.BIGINT).build();
    Column colSmallInt = Column.builder().name("small_num").jdbcType(Types.SMALLINT).build();
    Column colDecimal = Column.builder().name("decimal_val").jdbcType(Types.DECIMAL).build();
    Column colFloat = Column.builder().name("float_val").jdbcType(Types.FLOAT).build();
    Column colChar = Column.builder().name("char_val").jdbcType(Types.CHAR).build();
    Column colLongVarchar = Column.builder().name("long_text").jdbcType(Types.LONGVARCHAR).build();

    Table table =
        Table.builder()
            .name("more_types_table")
            .columns(List.of(colBigInt, colSmallInt, colDecimal, colFloat, colChar, colLongVarchar))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> values = new LinkedHashMap<>();
    values.put("big_num", 123456789012345L);
    values.put("small_num", (short) 123);
    values.put("decimal_val", 123.456);
    values.put("float_val", 789.12f);
    values.put("char_val", 'A');
    values.put("long_text", "This is a very long text field.");
    Row row = new Row(values);

    Map<Table, List<Row>> data = Map.of(table, List.of(row));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql =
        "INSERT INTO \"more_types_table\" (\"big_num\", \"small_num\", \"decimal_val\", \"float_val\", \"char_val\", \"long_text\") VALUES\n(123456789012345, 123, 123.456, 789.12, 'A', 'This is a very long text field.');\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should handle special characters in strings correctly")
  void shouldHandleSpecialCharactersInStrings() {
    Column textCol = Column.builder().name("description").jdbcType(Types.VARCHAR).build();
    Table table =
        Table.builder()
            .name("texts")
            .columns(List.of(textCol))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> values = new LinkedHashMap<>();
    values.put("description", "Line1'Quote");
    Row row = new Row(values);

    Map<Table, List<Row>> data = Map.of(table, List.of(row));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql =
        "INSERT INTO \"texts\" (\"description\") VALUES\n('Line1''Quote');\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should handle deferred updates")
  void shouldHandleDeferredUpdates() {
    PendingUpdate update = new PendingUpdate("cycle", Map.of("fk", 100), Map.of("id", 1));

    String sql =
        SqlGenerator.generate(
            Collections.emptyMap(), List.of(update), true // deferred = true
            );

    String expectedSql =
        """
        BEGIN;
        SET CONSTRAINTS ALL DEFERRED;
        UPDATE "cycle" SET "fk"=100 WHERE "id"=1;
        COMMIT;
        """;
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should handle multiple deferred updates")
  void shouldHandleMultipleDeferredUpdates() {
    PendingUpdate update1 = new PendingUpdate("table1", Map.of("col1", 10), Map.of("id", 1));
    PendingUpdate update2 = new PendingUpdate("table2", Map.of("col2", "test"), Map.of("pk", 2));

    String sql =
        SqlGenerator.generate(
            Collections.emptyMap(), List.of(update1, update2), true // deferred = true
            );

    String expectedSql =
        """
        BEGIN;
        SET CONSTRAINTS ALL DEFERRED;
        UPDATE "table1" SET "col1"=10 WHERE "id"=1;
        UPDATE "table2" SET "col2"='test' WHERE "pk"=2;
        COMMIT;
        """;
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should escape single quotes in strings")
  void shouldEscapeQuotes() {
    Column name = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    Table table =
        Table.builder()
            .name("users")
            .columns(List.of(name))
            .primaryKey(List.of("name"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> row1Values = new LinkedHashMap<>();
    row1Values.put("name", "O'Reilly's Pub");
    Row row1 = new Row(row1Values);

    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(table, List.of(row1));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql = "INSERT INTO \"users\" (\"name\") VALUES\n('O''Reilly''s Pub');\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should handle empty data and no updates (non-deferred)")
  void shouldHandleEmptyDataNoUpdatesNonDeferred() {
    String sql = SqlGenerator.generate(Collections.emptyMap(), Collections.emptyList(), false);
    assertEquals("", sql);
  }

  @Test
  @DisplayName("Should handle empty data and no updates (deferred)")
  void shouldHandleEmptyDataNoUpdatesDeferred() {
    String sql = SqlGenerator.generate(Collections.emptyMap(), Collections.emptyList(), true);
    String expectedSql =
        """
        BEGIN;
        SET CONSTRAINTS ALL DEFERRED;
        COMMIT;
        """;
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should generate SQL with both inserts and deferred updates")
  void shouldGenerateSqlWithBothInsertsAndDeferredUpdates() {
    // Insert data
    Column userId = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Column userName = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    Table usersTable =
        Table.builder()
            .name("users")
            .columns(List.of(userId, userName))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> userRowValues = new LinkedHashMap<>();
    userRowValues.put("id", 1);
    userRowValues.put("name", "Alice");
    Row userRow = new Row(userRowValues);

    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(usersTable, List.of(userRow));

    // Deferred updates
    PendingUpdate update1 = new PendingUpdate("table1", Map.of("col1", 10), Map.of("id", 1));
    PendingUpdate update2 = new PendingUpdate("table2", Map.of("col2", "test"), Map.of("pk", 2));
    List<PendingUpdate> pendingUpdates = List.of(update1, update2);

    String sql = SqlGenerator.generate(data, pendingUpdates, true);

    String expectedSql =
        """
        BEGIN;
        INSERT INTO "users" ("id", "name") VALUES
        (1, 'Alice');
        SET CONSTRAINTS ALL DEFERRED;
        UPDATE "table1" SET "col1"=10 WHERE "id"=1;
        UPDATE "table2" SET "col2"='test' WHERE "pk"=2;
        COMMIT;
        """;
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should quote identifiers for reserved keywords and special characters")
  void shouldQuoteIdentifiers() {
    Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Column fromCol =
        Column.builder().name("from").jdbcType(Types.VARCHAR).build(); // Reserved keyword
    Column orderCol =
        Column.builder().name("order by").jdbcType(Types.VARCHAR).build(); // Special characters
    Table table =
        Table.builder()
            .name("user") // Reserved keyword
            .columns(List.of(id, fromCol, orderCol))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> rowValues = new LinkedHashMap<>();
    rowValues.put("id", 1);
    rowValues.put("from", "sourceA");
    rowValues.put("order by", "asc");
    Row row = new Row(rowValues);

    Map<Table, List<Row>> data = Map.of(table, List.of(row));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql =
        "INSERT INTO \"user\" (\"id\", \"from\", \"order by\") VALUES\n(1, 'sourceA', 'asc');\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should handle table with no rows")
  void shouldHandleTableWithNoRows() {
    Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Table table =
        Table.builder()
            .name("empty_table")
            .columns(List.of(id))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(table, Collections.emptyList()); // No rows for this table

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);
    assertEquals("", sql); // Should generate no SQL for this table
  }

  @Test
  @DisplayName("Should escape double quotes in identifiers")
  void shouldEscapeDoubleQuotesInIdentifiers() {
    Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Column quotedCol = Column.builder().name("col\"with\"quotes").jdbcType(Types.VARCHAR).build();
    Table table =
        Table.builder()
            .name("table\"name")
            .columns(List.of(id, quotedCol))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    Map<String, Object> rowValues = new LinkedHashMap<>();
    rowValues.put("id", 1);
    rowValues.put("col\"with\"quotes", "some value");
    Row row = new Row(rowValues);

    Map<Table, List<Row>> data = Map.of(table, List.of(row));

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    String expectedSql =
        "INSERT INTO \"table\"\"name\" (\"id\", \"col\"\"with\"\"quotes\") VALUES\n(1, 'some value');\n";
    assertEquals(expectedSql, sql);
  }

  @Test
  @DisplayName("Should generate batched INSERT statements for more than BATCH_SIZE rows")
  void shouldGenerateBatchedInsertStatements() {
    Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
    Column name = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
    Table table =
        Table.builder()
            .name("large_table")
            .columns(List.of(id, name))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    // Generate 1001 rows to exceed the BATCH_SIZE (1000)
    List<Row> rows = IntStream.rangeClosed(1, 1001)
        .mapToObj(i -> {
          Map<String, Object> rowValues = new LinkedHashMap<>();
          rowValues.put("id", i);
          rowValues.put("name", "User" + i);
          return new Row(rowValues);
        })
        .collect(Collectors.toList());

    Map<Table, List<Row>> data = new LinkedHashMap<>();
    data.put(table, rows);

    String sql = SqlGenerator.generate(data, Collections.emptyList(), false);

    // Construct the expected SQL for two batches
    StringBuilder expectedSqlBuilder = new StringBuilder();
    expectedSqlBuilder.append("INSERT INTO \"large_table\" (\"id\", \"name\") VALUES\n");
    IntStream.rangeClosed(1, 1000).forEach(i -> {
      expectedSqlBuilder.append("(").append(i).append(", 'User").append(i).append("')");
      if (i < 1000) {
        expectedSqlBuilder.append(",\n");
      }
    });
    expectedSqlBuilder.append(";\n");

    expectedSqlBuilder.append("INSERT INTO \"large_table\" (\"id\", \"name\") VALUES\n");
    expectedSqlBuilder.append("(1001, 'User1001');\n");

    assertEquals(expectedSqlBuilder.toString(), sql);
  }
}
