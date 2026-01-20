package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("benchmark")
class DataGeneratorBenchmarkTest {

  @Test
  void benchmarkUuidGeneration() {
    Column uuidCol =
        Column.builder()
            .name("uuid_col")
            .jdbcType(Types.OTHER)
            .uuid(true)
            .nullable(false)
            .build();

    Table table =
        Table.builder()
            .name("BENCH_TABLE")
            .columns(List.of(uuidCol))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    // Warmup
    runGeneration(table, 100);

    // Benchmark
    int rows = 100_000;
    Instant start = Instant.now();
    runGeneration(table, rows);
    Instant end = Instant.now();

    System.out.println("Benchmark finished in " + Duration.between(start, end).toMillis() + " ms for " + rows + " rows.");
  }

  private void runGeneration(Table table, int rows) {
      DataGenerator.generate(
          DataGenerator.GenerationParameters.builder()
              .tables(List.of(table))
              .rowsPerTable(rows)
              .deferred(false)
              .pkUuidOverrides(Collections.emptyMap())
              .excludedColumns(Collections.emptyMap())
              .useEnglishDictionary(false)
              .useSpanishDictionary(false)
              .build());
  }
}
