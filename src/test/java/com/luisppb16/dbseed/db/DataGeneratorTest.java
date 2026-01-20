/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DataGenerator Complete Test Suite")
class DataGeneratorTest {

  private static final int DEFAULT_ROWS = 50;

  private DataGenerator.GenerationParameters.GenerationParametersBuilder defaultParams() {
    return DataGenerator.GenerationParameters.builder()
        .rowsPerTable(DEFAULT_ROWS)
        .deferred(false)
        .pkUuidOverrides(Collections.emptyMap())
        .excludedColumns(Collections.emptyMap())
        .repetitionRules(Collections.emptyMap())
        .useEnglishDictionary(false)
        .useSpanishDictionary(false)
        .useLatinDictionary(true) // Default fallback
        .softDeleteColumns(null)
        .softDeleteUseSchemaDefault(false)
        .softDeleteValue(null);
  }

  @Nested
  @DisplayName("1. Basic Type Generation")
  class TypeGenerationTests {

    @Test
    @DisplayName("should_generate_values_for_all_supported_jdbc_types_when_valid")
    void shouldGenerateValuesForAllSupportedJdbcTypes() {
      List<Column> columns = List.of(
          createColumn("int_col", Types.INTEGER),
          createColumn("varchar_col", Types.VARCHAR),
          createColumn("date_col", Types.DATE),
          createColumn("timestamp_col", Types.TIMESTAMP),
          createColumn("bool_col", Types.BOOLEAN),
          createColumn("bigint_col", Types.BIGINT),
          createColumn("decimal_col", Types.DECIMAL),
          createColumn("double_col", Types.DOUBLE),
          createColumn("float_col", Types.FLOAT),
          createColumn("tinyint_col", Types.TINYINT),
          createColumn("smallint_col", Types.SMALLINT)
      );

      Table table = createTable("ALL_TYPES", columns);

      var result = DataGenerator.generate(defaultParams().tables(List.of(table)).build());

      List<Row> rows = result.rows().get(table);
      assertThat(rows).hasSize(DEFAULT_ROWS);

      Row row = rows.get(0);
      assertThat(row.values().get("int_col")).isInstanceOf(Integer.class);
      assertThat(row.values().get("varchar_col")).isInstanceOf(String.class);
      assertThat(row.values().get("date_col")).isInstanceOf(Date.class);
      assertThat(row.values().get("timestamp_col")).isInstanceOf(Timestamp.class);
      assertThat(row.values().get("bool_col")).isInstanceOf(Boolean.class);
      assertThat(row.values().get("bigint_col")).isInstanceOf(Long.class);
      assertThat(row.values().get("decimal_col")).isInstanceOf(BigDecimal.class);
      assertThat(row.values().get("double_col")).isInstanceOf(Double.class);
  }

    @Test
    @DisplayName("should_generate_nulls_when_column_is_nullable")
    void shouldGenerateNullsWhenColumnIsNullable() {
      Column nullableCol = Column.builder()
          .name("nullable")
          .jdbcType(Types.VARCHAR)
          .nullable(true)
          .build();
      Table table = createTable("NULLABLE_TEST", List.of(nullableCol));

      // Generate enough rows to statistically ensure at least one null
      var result = DataGenerator.generate(
          defaultParams().tables(List.of(table)).rowsPerTable(100).build());

      List<Row> rows = result.rows().get(table);
      long nullCount = rows.stream()
          .filter(r -> r.values().get("nullable") == null)
          .count();

      // DataGenerator has hardcoded ~30% null chance for nullable columns
      assertThat(nullCount).isGreaterThan(0);
      assertThat(nullCount).isLessThan(100);
    }
  }

  @Nested
  @DisplayName("2. Constraints & Invariants")
  class ConstraintTests {

    @Test
    @DisplayName("should_respect_check_constraints_when_using_between")
    void shouldRespectCheckConstraintsWhenUsingBetween() {
      Column col = createColumn("age", Types.INTEGER);
      Table table = Table.builder()
          .name("PEOPLE")
          .columns(List.of(col))
          .primaryKey(List.of("age"))
          .foreignKeys(Collections.emptyList())
          .checks(List.of("age BETWEEN 18 AND 25"))
          .uniqueKeys(Collections.emptyList())
          .build();

      var result = DataGenerator.generate(defaultParams().tables(List.of(table)).build());

      List<Row> rows = result.rows().get(table);
      assertThat(rows).isNotEmpty();
      assertThat(rows).allSatisfy(row -> {
        int val = (int) row.values().get("age");
        assertThat(val).isBetween(18, 25);
      });
    }

    @Test
    @DisplayName("should_respect_check_constraints_when_using_in_list")
    void shouldRespectCheckConstraintsWhenUsingInList() {
      Column col = createColumn("status", Types.VARCHAR);
      Table table = Table.builder()
          .name("ORDERS")
          .columns(List.of(col))
          .primaryKey(List.of("status"))
          .foreignKeys(Collections.emptyList())
          .checks(List.of("status IN ('PENDING', 'SHIPPED', 'DELIVERED')"))
          .uniqueKeys(Collections.emptyList())
          .build();

      var result = DataGenerator.generate(defaultParams().tables(List.of(table)).build());

      List<Row> rows = result.rows().get(table);
      assertThat(rows).allSatisfy(row -> {
        String val = (String) row.values().get("status");
        assertThat(val).isIn("PENDING", "SHIPPED", "DELIVERED");
      });
    }

    @ParameterizedTest
    @CsvSource({
        "price > 100, 100.01, 10000.0",
        "price >= 50, 50.0, 10000.0",
        "price < 20, 0.0, 19.999",
        "price <= 10, 0.0, 10.0"
    })
    @DisplayName("should_respect_range_constraints_when_parsed_from_checks")
    void shouldRespectRangeConstraints(String check, double min, double max) {
      Column col = createColumn("price", Types.DECIMAL);
      Table table = Table.builder()
          .name("PRODUCTS")
          .columns(List.of(col))
          .primaryKey(List.of("price"))
          .checks(List.of(check))
          .foreignKeys(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();

      var result = DataGenerator.generate(defaultParams().tables(List.of(table)).build());

      List<Row> rows = result.rows().get(table);
      assertThat(rows).allSatisfy(row -> {
        BigDecimal val = (BigDecimal) row.values().get("price");
        assertThat(val.doubleValue()).isBetween(min, max); // Approximate for float math
      });
    }

    @Test
    @DisplayName("should_enforce_string_length_when_defined_in_column")
    void shouldEnforceStringLengthWhenDefinedInColumn() {
      Column col = Column.builder()
          .name("code")
          .jdbcType(Types.VARCHAR)
          .length(5)
          .nullable(false)
          .build();
      Table table = createTable("CODES", List.of(col));

      var result = DataGenerator.generate(defaultParams().tables(List.of(table)).build());

      List<Row> rows = result.rows().get(table);
      assertThat(rows).allSatisfy(row -> {
        String val = (String) row.values().get("code");
        assertThat(val).hasSizeLessThanOrEqualTo(5);
      });
    }

    @Test
    @DisplayName("should_retry_when_generated_value_violates_constraint")
    void shouldRetryWhenGeneratedValueViolatesConstraint() {
      // Create a scenario where the default random generation is likely to fail
      // e.g., default range is 1-10000, but constraint is 9990-10000
      // The generator loop should find values eventually.
      Column col = createColumn("lucky_num", Types.INTEGER);
      Table table = Table.builder()
          .name("LUCKY")
          .columns(List.of(col))
          .primaryKey(List.of("lucky_num"))
          .checks(List.of("lucky_num > 9900"))
          .foreignKeys(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();

      var result = DataGenerator.generate(defaultParams().tables(List.of(table)).rowsPerTable(10).build());

      assertThat(result.rows().get(table))
          .hasSize(10)
          .allSatisfy(row -> assertThat((Integer)row.values().get("lucky_num")).isGreaterThan(9900));
    }
  }

  @Nested
  @DisplayName("3. Foreign Keys & Relationships")
  class ForeignKeyTests {

    @Test
    @DisplayName("should_resolve_simple_foreign_key_when_parent_exists")
    void shouldResolveSimpleForeignKey() {
      Table parent = createTable("PARENTS", List.of(createColumn("id", Types.INTEGER)));

      Column childId = createColumn("id", Types.INTEGER);
      Column childFk = createColumn("parent_id", Types.INTEGER);
      ForeignKey fk = new ForeignKey("fk_p", "PARENTS", Map.of("parent_id", "id"), false);

      Table child = Table.builder()
          .name("CHILDREN")
          .columns(List.of(childId, childFk))
          .primaryKey(List.of("id"))
          .foreignKeys(List.of(fk))
          .checks(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();

      var result = DataGenerator.generate(
          defaultParams().tables(List.of(parent, child)).build());

      // Use keys from result, as new table instances might be created
      List<Row> parentRows = result.rows().entrySet().stream()
          .filter(e -> e.getKey().name().equals("PARENTS"))
          .findFirst().orElseThrow().getValue();

      List<Row> childRows = result.rows().entrySet().stream()
          .filter(e -> e.getKey().name().equals("CHILDREN"))
          .findFirst().orElseThrow().getValue();

      Set<Object> parentIds = parentRows.stream().map(r -> r.values().get("id")).collect(Collectors.toSet());

      assertThat(childRows).allSatisfy(row -> {
        Object fkVal = row.values().get("parent_id");
        assertThat(parentIds).contains(fkVal);
      });
    }

    @Test
    @DisplayName("should_resolve_composite_foreign_key_when_parent_has_composite_pk")
    void shouldResolveCompositeForeignKey() {
      Column p1 = createColumn("p1", Types.INTEGER);
      Column p2 = createColumn("p2", Types.INTEGER);
      Table parent = Table.builder()
          .name("COMP_PARENT")
          .columns(List.of(p1, p2))
          .primaryKey(List.of("p1", "p2"))
          .foreignKeys(Collections.emptyList())
          .checks(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();

      Column cId = createColumn("id", Types.INTEGER);
      Column cFk1 = createColumn("fk1", Types.INTEGER);
      Column cFk2 = createColumn("fk2", Types.INTEGER);

      ForeignKey fk = new ForeignKey("fk_comp", "COMP_PARENT",
          Map.of("fk1", "p1", "fk2", "p2"), false);

      Table child = Table.builder()
          .name("COMP_CHILD")
          .columns(List.of(cId, cFk1, cFk2))
          .primaryKey(List.of("id"))
          .foreignKeys(List.of(fk))
          .checks(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();

      var result = DataGenerator.generate(
          defaultParams().tables(List.of(parent, child)).build());

      // Fetch rows safely
      List<Row> parentRows = result.rows().entrySet().stream()
          .filter(e -> e.getKey().name().equals("COMP_PARENT"))
          .findFirst().orElseThrow().getValue();

      List<Row> childRows = result.rows().entrySet().stream()
          .filter(e -> e.getKey().name().equals("COMP_CHILD"))
          .findFirst().orElseThrow().getValue();

      Set<String> parentKeys = parentRows.stream()
          .map(r -> r.values().get("p1") + "|" + r.values().get("p2"))
          .collect(Collectors.toSet());

      assertThat(childRows).allSatisfy(row -> {
        String fkKey = row.values().get("fk1") + "|" + row.values().get("fk2");
        assertThat(parentKeys).contains(fkKey);
      });
    }

    @Test
    @DisplayName("should_handle_circular_dependency_via_deferred_updates_when_cycle_exists")
    void shouldHandleCircularDependency() {
      // Table A
      Column aId = createColumn("id", Types.INTEGER);
      Column aFk = createColumn("b_id", Types.INTEGER).toBuilder().nullable(true).build(); // FK to B, nullable to allow break
      Table tableA = Table.builder().name("A").columns(List.of(aId, aFk)).primaryKey(List.of("id")).foreignKeys(Collections.emptyList()).checks(Collections.emptyList()).uniqueKeys(Collections.emptyList()).build();

      // Table B
      Column bId = createColumn("id", Types.INTEGER);
      Column bFk = createColumn("a_id", Types.INTEGER).toBuilder().nullable(true).build();
      Table tableB = Table.builder().name("B").columns(List.of(bId, bFk)).primaryKey(List.of("id")).foreignKeys(Collections.emptyList()).checks(Collections.emptyList()).uniqueKeys(Collections.emptyList()).build();

      // Update FKs after creation (circular)
      ForeignKey fkToB = new ForeignKey("fk_a_b", "B", Map.of("b_id", "id"), false);
      ForeignKey fkToA = new ForeignKey("fk_b_a", "A", Map.of("a_id", "id"), false);

      tableA = tableA.toBuilder().foreignKeys(List.of(fkToB)).build();
      tableB = tableB.toBuilder().foreignKeys(List.of(fkToA)).build();

      // Use deferred = false to force usage of PendingUpdate for nullable FK cycles
      var result = DataGenerator.generate(
          defaultParams()
              .tables(List.of(tableA, tableB))
              .deferred(false)
              .build());

      assertThat(result.updates()).isNotEmpty();

      // Verify updates are correct
      List<PendingUpdate> updates = result.updates();
      PendingUpdate update = updates.get(0);
      assertThat(update.table()).isIn("A", "B");
      assertThat(update.pkValues()).isNotEmpty();
      assertThat(update.fkValues()).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("4. Advanced Features")
  class FeatureTests {

    @Test
    @DisplayName("should_apply_repetition_rules_when_provided")
    void shouldApplyRepetitionRules() {
      // Create table explicitly without PK to allow repeated group_id
      Table table = Table.builder()
          .name("REPEATS")
          .columns(List.of(
              createColumn("group_id", Types.INTEGER),
              createColumn("value", Types.VARCHAR)
          ))
          .primaryKey(Collections.emptyList()) // No PK to allow duplicates
          .foreignKeys(Collections.emptyList())
          .checks(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();

      RepetitionRule rule = new RepetitionRule(
          5, // count
          Map.of("group_id", "100"), // fixed values (String)
          Set.of("value") // random constant columns
      );

      var result = DataGenerator.generate(
          defaultParams()
              .tables(List.of(table))
              .repetitionRules(Map.of("REPEATS", List.of(rule)))
              .rowsPerTable(10) // Will generate 5 from rule + 5 others
              .build());

      List<Row> rows = result.rows().get(table);

      // Check the rule rows - DataGenerator inserts String "100", doesn't cast to Integer automatically
      // Need to handle potential nulls in value check to avoid NPEs if something generated null unexpectedly
      List<Row> ruleRows = rows.stream()
          .filter(r -> {
              Object val = r.values().get("group_id");
              return "100".equals(val) || Integer.valueOf(100).equals(val);
          })
          .toList();

      assertThat(ruleRows).hasSize(5);

      // Check random constant behavior: 'value' should be identical for all 5 rows
      Object firstVal = ruleRows.get(0).values().get("value");
      assertThat(ruleRows).allMatch(r -> r.values().get("value").equals(firstVal));
    }

    @Test
    @DisplayName("should_apply_soft_deletes_with_default_values_when_configured")
    void shouldApplySoftDeletesWithDefault() {
      Column delCol = createColumn("deleted", Types.BOOLEAN);
      Table table = createTable("SOFT_DEL", List.of(delCol));

      var result = DataGenerator.generate(
          defaultParams()
              .tables(List.of(table))
              .softDeleteColumns("deleted")
              .softDeleteUseSchemaDefault(true) // Should set to DEFAULT keyword logic
              .build());

      // In DataGenerator, if useSchemaDefault is true, it returns SqlKeyword.DEFAULT
      // We need to check if that value is present in the row data map
      List<Row> rows = result.rows().get(table);

      // The logic in generateColumnValue: return SqlKeyword.DEFAULT
      // But wait, the column value generation happens.
      // Let's verify type.

      assertThat(rows.get(0).values().get("deleted")).hasToString("DEFAULT");
    }

    @Test
    @DisplayName("should_apply_soft_deletes_with_custom_values_when_configured")
    void shouldApplySoftDeletesWithCustomValue() {
      Column delCol = createColumn("is_active", Types.INTEGER);
      Table table = createTable("CUSTOM_DEL", List.of(delCol));

      var result = DataGenerator.generate(
          defaultParams()
              .tables(List.of(table))
              .softDeleteColumns("is_active")
              .softDeleteUseSchemaDefault(false)
              .softDeleteValue("1")
              .build());

      List<Row> rows = result.rows().get(table);
      assertThat(rows).allMatch(r -> r.values().get("is_active").equals(1));
    }

    @Test
    @DisplayName("should_override_pk_uuids_when_requested")
    void shouldOverridePkUuids() {
      Column pk = createColumn("id", Types.VARCHAR);
      pk = pk.toBuilder().uuid(false).build(); // Initially NOT a UUID

      Table table = createTable("OVERRIDE_TEST", List.of(pk));

      var result = DataGenerator.generate(
          defaultParams()
              .tables(List.of(table))
              .pkUuidOverrides(Map.of("OVERRIDE_TEST", Map.of("id", "true")))
              .build());

      // Fetch the generated table which might be a different instance due to internal reconstruction
      Table generatedTable = result.rows().keySet().stream()
          .filter(t -> t.name().equals("OVERRIDE_TEST"))
          .findFirst()
          .orElseThrow();

      List<Row> rows = result.rows().get(generatedTable);

      assertThat(rows).allSatisfy(r -> {
        Object val = r.values().get("id");
        assertThat(val).isInstanceOf(UUID.class);
      });
    }
  }

  @Nested
  @DisplayName("5. Edge Cases & Resilience")
  class EdgeCaseTests {

    @Test
    @DisplayName("should_handle_empty_table_list_gracefully")
    void shouldHandleEmptyTableList() {
      var result = DataGenerator.generate(
          defaultParams().tables(Collections.emptyList()).build());
      assertThat(result.rows()).isEmpty();
      assertThat(result.updates()).isEmpty();
    }

    @Test
    @DisplayName("should_handle_table_with_no_columns_gracefully")
    void shouldHandleTableWithNoColumns() {
      Table table = Table.builder()
          .name("EMPTY")
          .columns(Collections.emptyList())
          .primaryKey(Collections.emptyList())
          .foreignKeys(Collections.emptyList())
          .checks(Collections.emptyList())
          .uniqueKeys(Collections.emptyList())
          .build();

      var result = DataGenerator.generate(
          defaultParams().tables(List.of(table)).build());

      assertThat(result.rows().get(table)).isEmpty();
    }

    @Test
    @DisplayName("should_fallback_to_null_on_invalid_soft_delete_value")
    void shouldFallbackToNullOnInvalidSoftDeleteValue() {
      Column col = createColumn("num", Types.INTEGER);
      Table table = createTable("BAD_SOFT", List.of(col));

      var result = DataGenerator.generate(
          defaultParams()
              .tables(List.of(table))
              .softDeleteColumns("num")
              .softDeleteValue("NOT_A_NUMBER")
              .build());

      assertThat(result.rows().get(table)).allMatch(r -> r.values().get("num") == null);
    }

    @Test
    @DisplayName("should_respect_exclusion_rules")
    void shouldRespectExclusionRules() {
      Column c1 = createColumn("c1", Types.INTEGER);
      Column c2 = createColumn("c2", Types.INTEGER);
      Table table = createTable("EXCLUSION", List.of(c1, c2));

      var result = DataGenerator.generate(
          defaultParams()
              .tables(List.of(table))
              .excludedColumns(Map.of("EXCLUSION", List.of("c2")))
              .build());

      List<Row> rows = result.rows().get(table);
      assertThat(rows).allSatisfy(r -> {
        assertThat(r.values().get("c1")).isNotNull();
        assertThat(r.values().get("c2")).isNull();
      });
    }
  }

  @Nested
  @DisplayName("6. Concurrency & Thread Safety")
  class ConcurrencyTests {

    @Test
    @DisplayName("should_safely_load_dictionaries_concurrently")
    void shouldSafelyLoadDictionariesConcurrently() throws InterruptedException {
      // DataGenerator uses Double-Checked Locking for dictionary loading.
      // We simulate multiple threads calling generate concurrently.

      Table table = createTable("CONCURRENT", List.of(createColumn("w", Types.VARCHAR)));

      int threads = 10;
      ExecutorService executor = Executors.newFixedThreadPool(threads);
      CountDownLatch latch = new CountDownLatch(1);

      List<String> errors = Collections.synchronizedList(new java.util.ArrayList<>());

      for (int i = 0; i < threads; i++) {
        executor.submit(() -> {
          try {
            latch.await();
            DataGenerator.generate(
               defaultParams()
                   .tables(List.of(table))
                   .useEnglishDictionary(true)
                   .rowsPerTable(5)
                   .build()
            );
          } catch (Exception e) {
            errors.add(e.getMessage());
          }
        });
      }

      latch.countDown();
      executor.shutdown();
      boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);

      assertThat(finished).isTrue();
      assertThat(errors).isEmpty();
    }
  }

  // Helpers

  private Column createColumn(String name, int type) {
    return Column.builder()
        .name(name)
        .jdbcType(type)
        .nullable(false)
        .length(type == Types.VARCHAR ? 255 : 0)
        .build();
  }

  private Table createTable(String name, List<Column> columns) {
    return Table.builder()
        .name(name)
        .columns(columns)
        .primaryKey(columns.isEmpty() ? Collections.emptyList() : List.of(columns.get(0).name()))
        .foreignKeys(Collections.emptyList())
        .checks(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();
  }
}
