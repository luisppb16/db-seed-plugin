package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.db.Row;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DataGeneratorTest {

    @Test
    void shouldHandleMultiColumnCheckWithNewlines() {
        // This is the check that was failing according to the user
        String check = """
                ((("missionStatus" = 'Planning'::text) AND ("missionStatusId" = 1)) OR
                       (("missionStatus" = 'Conduction'::text) AND ("missionStatusId" = 2)) OR
                       (("missionStatus" = 'Complete'::text) AND ("missionStatusId" = 3)))""";

        Column statusCol = Column.builder()
                .name("missionStatus")
                .jdbcType(Types.VARCHAR)
                .length(50)
                .nullable(false)
                .build();
        Column statusIdCol = Column.builder()
                .name("missionStatusId")
                .jdbcType(Types.INTEGER)
                .nullable(false)
                .build();

        Table table = Table.builder()
                .name("Mission")
                .columns(List.of(statusCol, statusIdCol))
                .primaryKey(Collections.emptyList())
                .foreignKeys(Collections.emptyList())
                .checks(List.of(check))
                .uniqueKeys(Collections.emptyList())
                .build();

        DataGenerator.GenerationParameters params = DataGenerator.GenerationParameters.builder()
                .tables(List.of(table))
                .rowsPerTable(50) // More rows to increase chance of catching mismatch
                .excludedColumns(Collections.emptyMap())
                .build();

        DataGenerator.GenerationResult result = DataGenerator.generate(params);

        Table generatedTable = result.rows().keySet().stream()
                .filter(t -> t.name().equals("Mission"))
                .findFirst()
                .orElseThrow();
        List<Row> rows = result.rows().get(generatedTable);
        assertThat(rows).isNotNull();
        assertThat(rows).hasSize(50);

        int matchedCombinations = 0;
        for (Row row : rows) {
            String status = (String) row.values().get("missionStatus");
            Object statusIdObj = row.values().get("missionStatusId");
            Integer statusId = (statusIdObj instanceof Number) ? ((Number) statusIdObj).intValue() : null;

            boolean match = ("Planning".equals(status) && Integer.valueOf(1).equals(statusId)) ||
                          ("Conduction".equals(status) && Integer.valueOf(2).equals(statusId)) ||
                          ("Complete".equals(status) && Integer.valueOf(3).equals(statusId));

            if (match) {
                matchedCombinations++;
            } else {
                System.out.println("Mismatch: status=" + status + ", statusId=" + statusId);
            }
        }

        // If the constraint is applied, ALL rows should match (unless they have other values if allowed,
        // but here the code should have picked from the combinations).
        // If the constraint is NOT parsed, the chance of matching exactly these is very low.
        assertThat(matchedCombinations).isEqualTo(50);
    }
}
