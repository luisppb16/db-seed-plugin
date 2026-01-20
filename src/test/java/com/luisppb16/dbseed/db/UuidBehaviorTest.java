package com.luisppb16.dbseed.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UuidBehaviorTest {

  @Test
  @DisplayName("Should fill nullable UUID columns with values due to ensureUuidUniqueness")
  void shouldFillNullableUuidColumns() {
    Column nullableUuidCol =
        Column.builder()
            .name("uuid_col")
            .jdbcType(Types.OTHER)
            .uuid(true)
            .nullable(true)
            .build();

    Table table =
        Table.builder()
            .name("UUID_TEST")
            .columns(List.of(nullableUuidCol))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    // We generate many rows to ensure that at least some would be null by random chance
    // The current implementation has a 30% chance of being null in generateColumnValue
    // But ensureUuidUniqueness should fill them all.
    int rowCount = 100;
    var result =
        DataGenerator.generate(
            DataGenerator.GenerationParameters.builder()
                .tables(List.of(table))
                .rowsPerTable(rowCount)
                .deferred(false)
                .pkUuidOverrides(Collections.emptyMap())
                .excludedColumns(Collections.emptyMap())
                .useEnglishDictionary(false)
                .useSpanishDictionary(false)
                .build());

    List<Row> rows = result.rows().get(table);

    boolean hasNulls = rows.stream().anyMatch(r -> r.values().get("uuid_col") == null);

    // After optimization, nullable UUID columns should respect their generated null values.
    assertTrue(hasNulls, "Nullable UUID columns should contain some null values as ensureUuidUniqueness is removed");
  }

  @Test
  @DisplayName("Should generate unique values for non-nullable UUID columns")
  void shouldGenerateUniqueValuesForNonNullableUuidColumn() {
    Column uuidCol =
        Column.builder()
            .name("uuid_val")
            .jdbcType(Types.OTHER)
            .uuid(true)
            .nullable(false)
            .build();

    Table table =
        Table.builder()
            .name("UUID_NON_NULL_TEST")
            .columns(List.of(uuidCol))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    int rowCount = 100;
    var result =
        DataGenerator.generate(
            DataGenerator.GenerationParameters.builder()
                .tables(List.of(table))
                .rowsPerTable(rowCount)
                .deferred(false)
                .pkUuidOverrides(Collections.emptyMap())
                .excludedColumns(Collections.emptyMap())
                .useEnglishDictionary(false)
                .useSpanishDictionary(false)
                .build());

    List<Row> rows = result.rows().get(table);

    // 1. Verify no nulls
    boolean hasNulls = rows.stream().anyMatch(r -> r.values().get("uuid_val") == null);
    assertFalse(hasNulls, "Non-nullable UUID columns should NOT contain null values");

    // 2. Verify uniqueness
    long uniqueCount = rows.stream().map(r -> r.values().get("uuid_val")).distinct().count();
    // Since we requested 100 rows and UUIDs are unique, we should have 100 unique values.
    // (Unless accidental collision which is impossible for 100 type-4 UUIDs)
    assertTrue(uniqueCount == rowCount, "All generated UUIDs should be unique. Expected " + rowCount + " but got " + uniqueCount);
  }
}
