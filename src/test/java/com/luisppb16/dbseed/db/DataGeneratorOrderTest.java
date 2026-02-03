package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class DataGeneratorOrderTest {

    @Test
    public void testIncorrectOrdering() {
        // Table A: id
        Table tableA = new Table("zzz_A",
            List.of(new Column("id", Types.INTEGER, false, true, false, 0, 0, 0, 0, Collections.emptySet())),
            List.of("id"), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        // Table B: id, a_id -> A.id
        Table tableB = new Table("zzz_B",
            List.of(
                new Column("id", Types.INTEGER, false, true, false, 0, 0, 0, 0, Collections.emptySet()),
                new Column("a_id", Types.INTEGER, false, false, false, 0, 0, 0, 0, Collections.emptySet())
            ),
            List.of("id"),
            List.of(new ForeignKey("fk_b_a", "zzz_A", Map.of("a_id", "id"), false)),
            Collections.emptyList(), Collections.emptyList());

        // Table C: id, b_id -> B.id
        Table tableC = new Table("aaa_C",
            List.of(
                new Column("id", Types.INTEGER, false, true, false, 0, 0, 0, 0, Collections.emptySet()),
                new Column("b_id", Types.INTEGER, false, false, false, 0, 0, 0, 0, Collections.emptySet())
            ),
            List.of("id"),
            List.of(new ForeignKey("fk_c_b", "zzz_B", Map.of("b_id", "id"), false)),
            Collections.emptyList(), Collections.emptyList());

        List<Table> tables = Arrays.asList(tableA, tableB, tableC);

        // Use TopologicalSorter to get the correct order
        TopologicalSorter.SortResult sortResult = TopologicalSorter.sort(tables);
        List<Table> topologicallyOrdered = sortResult.ordered().stream()
            .map(name -> tables.stream().filter(t -> t.name().equals(name)).findFirst().get())
            .toList();

        assertThat(topologicallyOrdered.stream().map(Table::name)).containsExactly("zzz_A", "zzz_B", "aaa_C");

        DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
            .tables(topologicallyOrdered)
            .rowsPerTable(1)
            .deferred(false)
            .excludedColumns(Collections.emptyMap())
            .pkUuidOverrides(Collections.emptyMap())
            .repetitionRules(Collections.emptyMap())
            .build();

        // This should not throw IllegalStateException because order is correct
        DataGenerator.GenerationResult result = DataGenerator.generate(params);

        assertThat(result.rows()).containsKeys(tableA, tableB, tableC);
        assertThat(result.rows().get(tableA)).hasSize(1);
        assertThat(result.rows().get(tableB)).hasSize(1);
        assertThat(result.rows().get(tableC)).hasSize(1);
    }
}
