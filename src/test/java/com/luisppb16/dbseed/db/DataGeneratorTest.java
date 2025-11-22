/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataGeneratorTest {

  @Test
  @DisplayName("Should generate rows respecting numeric bounds")
  void shouldGenerateRowsRespectingBounds() {
    Column ageCol =
        Column.builder()
            .name("age")
            .jdbcType(Types.INTEGER)
            .minValue(18)
            .maxValue(65)
            .nullable(false)
            .build();

    Table table =
        Table.builder()
            .name("USERS")
            .columns(List.of(ageCol))
            .primaryKey(List.of("age")) // dummy PK
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    var result =
        DataGenerator.generate(
            List.of(table),
            100,
            false,
            Collections.emptyMap(),
            Collections.emptyMap(),
            true, false, false); // Use Latin dictionary

    assertNotNull(result);
    List<Row> rows = result.rows().get(table); // Use the actual Table object as key
    assertNotNull(rows);
    // We requested 100 rows, but the domain [18, 65] only has 48 unique values.
    // The generator drops duplicates for PKs. So we expect <= 48 rows.
    assertTrue(
        rows.size() <= 48, "Should not exceed max unique values (48) but got " + rows.size());
    assertFalse(rows.isEmpty(), "Should generate some rows"); // Simplified assertion

    for (Row row : rows) {
      int age = (int) row.values().get("age");
      assertTrue(age >= 18 && age <= 65, "Age " + age + " should be between 18 and 65");
    }
  }

  @Test
  @DisplayName("Should generate unique UUIDs")
  void shouldGenerateUniqueUuids() {
    Column idCol =
        Column.builder()
            .name("id")
            .jdbcType(Types.VARCHAR) // or OTHER
            .uuid(true)
            .nullable(false)
            .primaryKey(true)
            .build();

    Table table =
        Table.builder()
            .name("ITEMS")
            .columns(List.of(idCol))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    var result =
        DataGenerator.generate(
            List.of(table),
            50,
            false,
            Collections.emptyMap(),
            Collections.emptyMap(),
            true, false, false); // Use Latin dictionary

    List<Row> rows = result.rows().get(table); // Use the actual Table object as key
    assertNotNull(rows);
    assertEquals(50, rows.size());

    long uniqueCount = rows.stream().map(r -> r.values().get("id")).distinct().count();

    assertEquals(50, uniqueCount, "All UUIDs should be unique");
  }

  @Test
  @DisplayName("Should generate string values respecting length and allowed values")
  void shouldGenerateStringsWithConstraints() {
    Column statusCol =
        Column.builder()
            .name("status")
            .jdbcType(Types.VARCHAR)
            .length(10)
            .nullable(false)
            .allowedValues(Set.of("ACTIVE", "INACTIVE", "PENDING"))
            .build();

    Table table =
        Table.builder()
            .name("ORDERS")
            .columns(List.of(statusCol))
            .primaryKey(List.of("status")) // dummy PK
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    var result =
        DataGenerator.generate(
            List.of(table),
            10,
            false,
            Collections.emptyMap(),
            Collections.emptyMap(),
            true, false, false); // Use Latin dictionary

    assertNotNull(result);
    List<Row> rows = result.rows().get(table);
    assertNotNull(rows);
    assertFalse(rows.isEmpty(), "Should generate some rows");

    for (Row row : rows) {
      String status = (String) row.values().get("status");
      assertNotNull(status);
      assertTrue(status.length() <= 10, "Status length should not exceed 10");
      assertTrue(
          statusCol.allowedValues().contains(status), "Status should be one of the allowed values");
    }
  }

  @Test
  @DisplayName("Should generate nullable column values")
  void shouldGenerateNullableColumnValues() {
    Column nullableCol =
        Column.builder()
            .name("description")
            .jdbcType(Types.VARCHAR)
            .nullable(true)
            .length(50)
            .build();
    Column idCol =
        Column.builder()
            .name("id")
            .jdbcType(Types.INTEGER)
            .nullable(false)
            .primaryKey(true)
            .build();

    Table table =
        Table.builder()
            .name("PRODUCTS")
            .columns(List.of(idCol, nullableCol))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    var result =
        DataGenerator.generate(
            List.of(table),
            20,
            false,
            Collections.emptyMap(),
            Collections.emptyMap(),
            true, false, false); // Use Latin dictionary

    assertNotNull(result);
    List<Row> rows = result.rows().get(table);
    assertNotNull(rows);
    assertEquals(20, rows.size());

    long nullCount = rows.stream().filter(row -> row.values().get("description") == null).count();
    assertTrue(nullCount > 0 && nullCount < 20, "Some descriptions should be null, some not");
  }

  @Test
  @DisplayName("Should handle tables with no columns")
  void shouldHandleTableWithNoColumns() {
    Table table =
        Table.builder()
            .name("EMPTY_TABLE")
            .columns(Collections.emptyList())
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    var result =
        DataGenerator.generate(
            List.of(table),
            5,
            false,
            Collections.emptyMap(),
            Collections.emptyMap(),
            true, false, false); // Use Latin dictionary

    assertNotNull(result);
    List<Row> rows = result.rows().get(table);
    assertNotNull(rows);
    assertTrue(rows.isEmpty(), "Should generate no rows for a table with no columns");
  }

  @Test
  @DisplayName("Should handle empty list of tables")
  void shouldHandleEmptyListOfTables() {
    var result =
        DataGenerator.generate(
            Collections.emptyList(),
            10,
            false,
            Collections.emptyMap(),
            Collections.emptyMap(),
            true, false, false); // Use Latin dictionary

    assertNotNull(result);
    assertTrue(result.rows().isEmpty(), "Should generate no rows for an empty list of tables");
  }

  @Test
  @DisplayName("Should generate different values for unique columns")
  void shouldGenerateUniqueValuesForUniqueColumns() {
    Column uniqueNameCol =
        Column.builder().name("name").jdbcType(Types.VARCHAR).length(20).nullable(false).build();
    Column idCol =
        Column.builder()
            .name("id")
            .jdbcType(Types.INTEGER)
            .nullable(false)
            .primaryKey(true)
            .build();

    Table table =
        Table.builder()
            .name("CATEGORIES")
            .columns(List.of(idCol, uniqueNameCol))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    var result =
        DataGenerator.generate(
            List.of(table),
            50,
            false,
            Collections.emptyMap(),
            Collections.emptyMap(),
            true, false, false); // Use Latin dictionary

    assertNotNull(result);
    List<Row> rows = result.rows().get(table);
    assertNotNull(rows);
    assertEquals(50, rows.size());

    long uniqueNamesCount =
        rows.stream().map(row -> (String) row.values().get("name")).distinct().count();
    assertEquals(50, uniqueNamesCount, "All 'name' values should be unique");
  }

  @Test
  @DisplayName("Should generate foreign key relationships")
  void shouldGenerateForeignKeys() {
    // Parent Table
    Column userIdCol =
        Column.builder()
            .name("id")
            .jdbcType(Types.INTEGER)
            .minValue(1)
            .maxValue(10)
            .nullable(false)
            .primaryKey(true)
            .build();
    Column userNameCol =
        Column.builder().name("name").jdbcType(Types.VARCHAR).length(50).nullable(false).build();
    Table usersTable =
        Table.builder()
            .name("USERS")
            .columns(List.of(userIdCol, userNameCol))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    // Child Table
    Column orderIdCol =
        Column.builder()
            .name("order_id")
            .jdbcType(Types.INTEGER)
            .nullable(false)
            .primaryKey(true)
            .build();
    Column fkUserIdCol =
        Column.builder()
            .name("user_id")
            .jdbcType(Types.INTEGER)
            .nullable(false)
            .build(); // This will be the FK

    Table ordersTable =
        Table.builder()
            .name("ORDERS")
            .columns(List.of(orderIdCol, fkUserIdCol))
            .primaryKey(List.of("order_id"))
            .foreignKeys(
                List.of(
                    new ForeignKey(
                        "fk_user",
                        "USERS", // pkTable is USERS
                        Map.of("user_id", "id"),
                        false))) // uniqueOnFk is false
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    var result =
        DataGenerator.generate(
            List.of(usersTable, ordersTable),
            10,
            false,
            Collections.emptyMap(),
            Collections.emptyMap(),
            true, false, false); // Use Latin dictionary

    assertNotNull(result);
    List<Row> userRows = result.rows().get(usersTable);
    List<Row> orderRows = result.rows().get(ordersTable);

    assertNotNull(userRows);
    assertNotNull(orderRows);
    assertEquals(10, userRows.size());
    assertEquals(10, orderRows.size());

    Set<Object> userIds =
        userRows.stream().map(row -> row.values().get("id")).collect(Collectors.toSet());
    Set<Object> orderUserIds =
        orderRows.stream().map(row -> row.values().get("user_id")).collect(Collectors.toSet());

    assertTrue(userIds.containsAll(orderUserIds), "All FK user_ids should exist in USERS table");
  }
}
