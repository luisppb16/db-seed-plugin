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
public class NormalizeBenchmark {

  @Test
  public void run() {
    Column charCol =
        Column.builder()
            .name("char_col")
            .jdbcType(Types.CHAR)
            .length(50) // Length 50
            .nullable(false)
            .build();

    Table table =
        Table.builder()
            .name("BENCH_TABLE")
            .columns(List.of(charCol))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

    int rows = 100_000;

    // Warmup
    runGeneration(table, 1000);

    System.out.println("Starting benchmark for " + rows + " rows...");
    Instant start = Instant.now();
    runGeneration(table, rows);
    Instant end = Instant.now();

    System.out.println("Benchmark finished in " + Duration.between(start, end).toMillis() + " ms.");
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
