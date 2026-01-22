/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DataGeneratorTest {

  @Nested
  @DisplayName("Basic Generation Logic")
  class BasicGeneration {

    @Test
    @DisplayName("Should generate specified number of rows")
    void rowCount() {
      Table table = createTable("test_table", Types.INTEGER, "id");

      DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
          .tables(List.of(table))
          .rowsPerTable(10)
          .excludedColumns(Collections.emptyMap())
          .repetitionRules(Collections.emptyMap())
          .build();

      DataGenerator.GenerationResult result = DataGenerator.generate(params);

      assertThat(result.rows()).containsKey(table);
      assertThat(result.rows().get(table)).hasSize(10);
    }

    @Test
    @DisplayName("Should generate data for multiple tables")
    void multipleTables() {
      Table t1 = createTable("t1", Types.INTEGER, "id");
      Table t2 = createTable("t2", Types.VARCHAR, "name");

      DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
          .tables(List.of(t1, t2))
          .rowsPerTable(5)
          .excludedColumns(Collections.emptyMap())
          .build();

      DataGenerator.GenerationResult result = DataGenerator.generate(params);

      assertThat(result.rows()).containsKeys(t1, t2);
      assertThat(result.rows().get(t1)).hasSize(5);
      assertThat(result.rows().get(t2)).hasSize(5);
    }
  }

  @Nested
  @DisplayName("Constraint Handling")
  class ConstraintHandling {

    @ParameterizedTest
    @CsvSource({
        "10, 20",
        "0, 5",
        "-10, -1"
    })
    @DisplayName("Should respect min/max constraints for Integers")
    void integerConstraints(int min, int max) {
      Column col = Column.builder()
          .name("age")
          .jdbcType(Types.INTEGER)
          .minValue(min)
          .maxValue(max)
          .build();

      Table table = Table.builder()
          .name("users")
          .columns(List.of(col))
          .primaryKey(Collections.emptyList())
          .foreignKeys(Collections.emptyList())
          .checks(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();

      DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
          .tables(List.of(table))
          .rowsPerTable(50) // Generate enough to verify
          .excludedColumns(Collections.emptyMap())
          .build();

      DataGenerator.GenerationResult result = DataGenerator.generate(params);
      List<Row> rows = result.rows().get(table);

      assertThat(rows).allSatisfy(row -> {
        Object val = row.values().get("age");
        // Nulls are possible (30% chance by default if nullable, default is false in builder but checked here)
        if (val != null) {
          int v = (Integer) val;
          assertThat(v).isBetween(min, max);
        }
      });
    }

    @Test
    @DisplayName("Should respect allowed values")
    void allowedValues() {
      Set<String> allowed = Set.of("A", "B", "C");
      Column col = Column.builder()
          .name("status")
          .jdbcType(Types.VARCHAR)
          .allowedValues(allowed)
          .build();

      Table table = createTableWithColumn("orders", col);

      DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
          .tables(List.of(table))
          .rowsPerTable(20)
          .excludedColumns(Collections.emptyMap())
          .build();

      DataGenerator.GenerationResult result = DataGenerator.generate(params);

      assertThat(result.rows().get(table)).allSatisfy(row -> {
        Object val = row.values().get("status");
        if (val != null) {
          assertThat(allowed).contains(val.toString());
        }
      });
    }
  }

  @Nested
  @DisplayName("Foreign Key Resolution")
  class ForeignKeyResolution {

    @Test
    @DisplayName("Should resolve simple Foreign Key dependency")
    void resolveFK() {
      // Parent: Users (id)
      Table users = createTable("users", Types.INTEGER, "id");
      // Child: Orders (user_id -> users.id)
      Column userIdCol = Column.builder().name("user_id").jdbcType(Types.INTEGER).build();

      ForeignKey fk = new ForeignKey("fk_orders_users", "users", Map.of("user_id", "id"), false);

      Table orders = Table.builder()
          .name("orders")
          .columns(List.of(userIdCol))
          .primaryKey(Collections.emptyList())
          .foreignKeys(List.of(fk))
          .checks(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();

      DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
          .tables(List.of(users, orders))
          .rowsPerTable(10)
          .excludedColumns(Collections.emptyMap())
          .build();

      DataGenerator.GenerationResult result = DataGenerator.generate(params);

      List<Row> userRows = result.rows().get(users);
      List<Row> orderRows = result.rows().get(orders);

      // Collect generated IDs
      List<Object> userIds = userRows.stream().map(r -> r.values().get("id")).toList();

      // Verify all orders reference valid users
      assertThat(orderRows).allSatisfy(row -> {
        Object fkVal = row.values().get("user_id");
        if (fkVal != null) {
           assertThat(userIds).contains(fkVal);
        }
      });
    }
  }

  @Nested
  @DisplayName("Repetition Rules")
  class RepetitionRules {

      @Test
      @DisplayName("Should respect repetition rule count and fixed values")
      void repetitionRuleFixed() {
          Table table = createTable("items", Types.VARCHAR, "type", "category");

          RepetitionRule rule = new RepetitionRule(
              5,
              Map.of("type", "SpecialItem"),
              Collections.emptySet()
          );

          DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
              .tables(List.of(table))
              .rowsPerTable(10) // 5 from rule + 5 random
              .repetitionRules(Map.of("items", List.of(rule)))
              .excludedColumns(Collections.emptyMap())
              .build();

          DataGenerator.GenerationResult result = DataGenerator.generate(params);
          List<Row> rows = result.rows().get(table);

          assertThat(rows).hasSize(10);

          long specialItems = rows.stream()
              .filter(r -> "SpecialItem".equals(r.values().get("type")))
              .count();

          assertThat(specialItems).isEqualTo(5);
      }
  }

  @Nested
  @DisplayName("Data Types and Formatting")
  class DataTypes {
      @Test
      @DisplayName("Should generate UUIDs")
      void uuids() {
          Column uuidCol = Column.builder()
              .name("uid")
              .jdbcType(Types.OTHER)
              .uuid(true)
              .build();
          Table table = createTableWithColumn("t", uuidCol);

          DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
              .tables(List.of(table))
              .rowsPerTable(5)
              .excludedColumns(Collections.emptyMap())
              .build();

          DataGenerator.GenerationResult result = DataGenerator.generate(params);

          assertThat(result.rows().get(table)).allSatisfy(r -> {
              Object val = r.values().get("uid");
              if (val != null) {
                  assertThat(val).isInstanceOf(UUID.class);
              }
          });
      }

      @Test
      @DisplayName("Should generate Dates")
      void dates() {
          Table table = createTable("t", Types.DATE, "d");

           DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
              .tables(List.of(table))
              .rowsPerTable(5)
              .excludedColumns(Collections.emptyMap())
              .build();

           DataGenerator.GenerationResult result = DataGenerator.generate(params);
           assertThat(result.rows().get(table)).allSatisfy(r -> {
               Object val = r.values().get("d");
               if (val != null) {
                   assertThat(val).isInstanceOf(java.sql.Date.class);
               }
           });
      }
  }

  @Nested
  @DisplayName("Soft Delete Logic")
  class SoftDelete {

      @Test
      @DisplayName("Should use default keyword if softDeleteUseSchemaDefault is true")
      void useDefault() {
          Table table = createTable("users", Types.BOOLEAN, "is_deleted");

          DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
              .tables(List.of(table))
              .rowsPerTable(5)
              .excludedColumns(Collections.emptyMap())
              .softDeleteColumns("is_deleted")
              .softDeleteUseSchemaDefault(true)
              .build();

          DataGenerator.GenerationResult result = DataGenerator.generate(params);

          assertThat(result.rows().get(table)).allSatisfy(r -> {
              Object val = r.values().get("is_deleted");
              assertThat(val).isEqualTo(SqlKeyword.DEFAULT);
          });
      }

       @Test
      @DisplayName("Should use provided value if softDeleteUseSchemaDefault is false")
      void useValue() {
          Table table = createTable("users", Types.BOOLEAN, "is_deleted");

          DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
              .tables(List.of(table))
              .rowsPerTable(5)
              .excludedColumns(Collections.emptyMap())
              .softDeleteColumns("is_deleted")
              .softDeleteUseSchemaDefault(false)
              .softDeleteValue("true")
              .build();

          DataGenerator.GenerationResult result = DataGenerator.generate(params);

          assertThat(result.rows().get(table)).allSatisfy(r -> {
              Object val = r.values().get("is_deleted");
              assertThat(val).isEqualTo(true);
          });
      }
  }

  // --- Helpers ---

  private Table createTable(String name, int type, String... cols) {
    List<Column> columns = java.util.stream.Stream.of(cols)
        .map(c -> Column.builder().name(c).jdbcType(type).build())
        .toList();

    return Table.builder()
        .name(name)
        .columns(columns)
        .primaryKey(Collections.emptyList())
        .foreignKeys(Collections.emptyList())
        .checks(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();
  }

  private Table createTableWithColumn(String name, Column col) {
      return Table.builder()
          .name(name)
          .columns(List.of(col))
          .primaryKey(Collections.emptyList())
          .foreignKeys(Collections.emptyList())
          .checks(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();
  }
}
