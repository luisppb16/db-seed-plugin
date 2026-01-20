/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ModelTest {

  @Nested
  @DisplayName("Column Tests")
  class ColumnTests {

    @Test
    @DisplayName("Column should validate non-null name")
    void columnShouldValidateName() {
      assertThrows(
          NullPointerException.class,
          () -> new Column(null, Types.INTEGER, false, false, false, 0, 0, 0, 0, null),
          "Column name cannot be null.");
    }

    @Test
    @DisplayName("Column constructor should create a valid column")
    void columnConstructorValid() {
      Column column =
          new Column("id", Types.INTEGER, true, true, false, 10, 0, 0, 100, Set.of("1", "2"));
      assertNotNull(column);
      assertEquals("id", column.name());
      assertEquals(Types.INTEGER, column.jdbcType());
      assertTrue(column.nullable());
      assertTrue(column.primaryKey());
      assertFalse(column.uuid());
      assertEquals(10, column.length());
      assertEquals(0, column.minValue());
      assertEquals(100, column.maxValue());
      assertTrue(column.hasAllowedValues());
      assertEquals(Set.of("1", "2"), column.allowedValues());
    }

    @Test
    @DisplayName("Column hasAllowedValues should return true when allowedValues is not empty")
    void columnHasAllowedValuesTrue() {
      Column col =
          Column.builder()
              .name("status")
              .jdbcType(Types.VARCHAR)
              .allowedValues(Set.of("A", "B"))
              .build();

      assertTrue(col.hasAllowedValues());
    }

    @Test
    @DisplayName("Column hasAllowedValues should return false when allowedValues is empty")
    void columnHasAllowedValuesFalseEmpty() {
      Column colEmpty =
          Column.builder()
              .name("status")
              .jdbcType(Types.VARCHAR)
              .allowedValues(Collections.emptySet())
              .build();

      assertFalse(colEmpty.hasAllowedValues());
    }

    @Test
    @DisplayName("Column hasAllowedValues should return false when allowedValues is null")
    void columnHasAllowedValuesFalseNull() {
      Column colNull =
          Column.builder().name("status").jdbcType(Types.VARCHAR).allowedValues(null).build();

      assertFalse(colNull.hasAllowedValues());
    }

    @Test
    @DisplayName("Column builder should create a column with minimal fields")
    void columnBuilderMinimalFields() {
      Column column = Column.builder().name("minimal").jdbcType(Types.VARCHAR).build();
      assertNotNull(column);
      assertEquals("minimal", column.name());
      assertEquals(Types.VARCHAR, column.jdbcType());
      assertFalse(column.nullable());
      assertFalse(column.primaryKey());
      assertFalse(column.uuid());
      assertEquals(0, column.length());
      assertEquals(0, column.minValue());
      assertEquals(0, column.maxValue());
      assertFalse(column.hasAllowedValues());
      assertNull(column.allowedValues());
    }
  }

  @Nested
  @DisplayName("ForeignKey Tests")
  class ForeignKeyTests {

    @Test
    @DisplayName("ForeignKey should validate non-null pkTable")
    void fkValidationPkTable() {
      assertThrows(
          NullPointerException.class,
          () -> new ForeignKey("fk", null, Map.of(), false),
          "The primary table name (pkTable) cannot be null.");
    }

    @Test
    @DisplayName("ForeignKey should validate non-null columnMapping")
    void fkValidationColumnMapping() {
      assertThrows(
          NullPointerException.class,
          () -> new ForeignKey("fk", "parent", null, false),
          "The column mapping cannot be null.");
    }

    @Test
    @DisplayName("ForeignKey constructor should create a valid foreign key with provided name")
    void fkConstructorValidWithName() {
      ForeignKey fk = new ForeignKey("my_fk", "parent", Map.of("child_id", "parent_id"), true);
      assertNotNull(fk);
      assertEquals("my_fk", fk.name());
      assertEquals("parent", fk.pkTable());
      assertEquals(Map.of("child_id", "parent_id"), fk.columnMapping());
      assertTrue(fk.uniqueOnFk());
    }

    @Test
    @DisplayName("ForeignKey should generate default name when name is null")
    void fkGenerateDefaultNameNull() {
      ForeignKey fk =
          ForeignKey.builder()
              .pkTable("parent")
              .columnMapping(Map.of("child_id", "parent_id"))
              .build();

      assertNotNull(fk.name());
      assertEquals("fk_parent_child_id", fk.name());
    }

    @Test
    @DisplayName("ForeignKey should generate default name when name is blank")
    void fkGenerateDefaultNameBlank() {
      ForeignKey fk =
          ForeignKey.builder()
              .name("   ")
              .pkTable("parent")
              .columnMapping(Map.of("child_id", "parent_id"))
              .build();

      assertNotNull(fk.name());
      assertEquals("fk_parent_child_id", fk.name());
    }

    @Test
    @DisplayName("ForeignKey should generate default name with empty column mapping")
    void fkGenerateDefaultNameEmptyColumnMapping() {
      ForeignKey fk = ForeignKey.builder().pkTable("parent").columnMapping(Map.of()).build();

      assertNotNull(fk.name());
      assertEquals("fk_parent", fk.name());
    }

    @Test
    @DisplayName("ForeignKey should generate default name with multiple columns in mapping")
    void fkGenerateDefaultNameMultipleColumns() {
      ForeignKey fk =
          ForeignKey.builder()
              .pkTable("parent")
              .columnMapping(Map.of("child_id_1", "parent_id_1", "child_id_2", "parent_id_2"))
              .build();

      assertNotNull(fk.name());
      assertEquals("fk_parent_child_id_1__child_id_2", fk.name());
    }
  }

  @Nested
  @DisplayName("Table Tests")
  class TableTests {

    @Test
    @DisplayName("Table should validate non-null name")
    void tableValidationName() {
      assertThrows(
          NullPointerException.class,
          () -> new Table(null, List.of(), List.of(), List.of(), List.of(), List.of()),
          "Table name cannot be null.");
    }

    @Test
    @DisplayName("Table should validate non-null columns list")
    void tableValidationColumns() {
      assertThrows(
          NullPointerException.class,
          () -> new Table("t", null, List.of(), List.of(), List.of(), List.of()),
          "Column list cannot be null.");
    }

    @Test
    @DisplayName("Table should validate non-null primaryKey list")
    void tableValidationPrimaryKey() {
      assertThrows(
          NullPointerException.class,
          () -> new Table("t", List.of(), null, List.of(), List.of(), List.of()),
          "The list of PK columns cannot be null.");
    }

    @Test
    @DisplayName("Table should validate non-null foreignKeys list")
    void tableValidationForeignKeys() {
      assertThrows(
          NullPointerException.class,
          () -> new Table("t", List.of(), List.of(), null, List.of(), List.of()),
          "The list of foreign keys cannot be null.");
    }

    @Test
    @DisplayName("Table should validate non-null checks list")
    void tableValidationChecks() {
      assertThrows(
          NullPointerException.class,
          () -> new Table("t", List.of(), List.of(), List.of(), null, List.of()),
          "The list of checks cannot be null.");
    }

    @Test
    @DisplayName("Table should validate non-null uniqueKeys list")
    void tableValidationUniqueKeys() {
      assertThrows(
          NullPointerException.class,
          () -> new Table("t", List.of(), List.of(), List.of(), List.of(), null),
          "The list of unique keys cannot be null.");
    }

    @Test
    @DisplayName("Table constructor should create a valid table")
    void tableConstructorValid() {
      Column col1 = Column.builder().name("id").jdbcType(Types.INTEGER).build();
      Column col2 = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
      ForeignKey fk =
          ForeignKey.builder().pkTable("other").columnMapping(Map.of("other_id", "id")).build();
      Table table =
          new Table(
              "my_table",
              List.of(col1, col2),
              List.of("id"),
              List.of(fk),
              List.of("id > 0"),
              List.of(List.of("name")));

      assertNotNull(table);
      assertEquals("my_table", table.name());
      assertEquals(2, table.columns().size());
      assertEquals(List.of("id"), table.primaryKey());
      assertEquals(1, table.foreignKeys().size());
      assertEquals(List.of("id > 0"), table.checks());
      assertEquals(List.of(List.of("name")), table.uniqueKeys());
    }

    @Test
    @DisplayName("Table column method should return the correct column")
    void tableColumnFound() {
      Column col = Column.builder().name("c").jdbcType(Types.VARCHAR).build();
      Table t =
          Table.builder()
              .name("t")
              .columns(List.of(col))
              .primaryKey(List.of()) // Added
              .foreignKeys(List.of()) // Added
              .checks(List.of()) // Added
              .uniqueKeys(List.of()) // Added
              .build();

      assertNotNull(t.column("c"));
      assertEquals("c", t.column("c").name());
    }

    @Test
    @DisplayName("Table column method should return null if column not found")
    void tableColumnNotFound() {
      Column col = Column.builder().name("c").jdbcType(Types.VARCHAR).build();
      Table t =
          Table.builder()
              .name("t")
              .columns(List.of(col))
              .primaryKey(List.of()) // Added
              .foreignKeys(List.of()) // Added
              .checks(List.of()) // Added
              .uniqueKeys(List.of()) // Added
              .build();

      assertNull(t.column("non_existent_column"));
    }

    @Test
    @DisplayName("Table fkColumnNames should return all foreign key column names")
    void tableFkColumnNamesMultipleFks() {
      ForeignKey fk1 =
          ForeignKey.builder()
              .pkTable("parent1")
              .columnMapping(Map.of("child_id_1", "parent_id_1"))
              .build();
      ForeignKey fk2 =
          ForeignKey.builder()
              .pkTable("parent2")
              .columnMapping(Map.of("child_id_2", "parent_id_2", "child_id_3", "parent_id_3"))
              .build();
      Table t =
          Table.builder()
              .name("t")
              .columns(List.of())
              .primaryKey(List.of()) // Added
              .foreignKeys(List.of(fk1, fk2))
              .checks(List.of()) // Added
              .uniqueKeys(List.of()) // Added
              .build();

      Set<String> expectedFkColumns = Set.of("child_id_1", "child_id_2", "child_id_3");
      assertEquals(expectedFkColumns, t.fkColumnNames());
    }

    @Test
    @DisplayName("Table fkColumnNames should return an empty set if no foreign keys")
    void tableFkColumnNamesNoFks() {
      Table t =
          Table.builder()
              .name("t")
              .columns(List.of())
              .primaryKey(List.of()) // Added
              .foreignKeys(List.of())
              .checks(List.of()) // Added
              .uniqueKeys(List.of()) // Added
              .build();

      assertTrue(t.fkColumnNames().isEmpty());
    }

    @Test
    @DisplayName(
        "Table fkColumnNames should return an empty set if foreign keys have empty mappings")
    void tableFkColumnNamesEmptyMappingFks() {
      ForeignKey fk = ForeignKey.builder().pkTable("parent").columnMapping(Map.of()).build();
      Table t =
          Table.builder()
              .name("t")
              .columns(List.of())
              .primaryKey(List.of()) // Added
              .foreignKeys(List.of(fk))
              .checks(List.of()) // Added
              .uniqueKeys(List.of()) // Added
              .build();

      assertTrue(t.fkColumnNames().isEmpty());
    }
  }
}
