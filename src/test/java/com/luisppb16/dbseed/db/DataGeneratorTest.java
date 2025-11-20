package com.luisppb16.dbseed.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DataGeneratorTest {

    @Test
    @DisplayName("Should generate rows respecting numeric bounds")
    void shouldGenerateRowsRespectingBounds() {
        Column ageCol = Column.builder()
            .name("age")
            .jdbcType(Types.INTEGER)
            .minValue(18)
            .maxValue(65)
            .nullable(false)
            .build();

        Table table = Table.builder()
            .name("USERS")
            .columns(List.of(ageCol))
            .primaryKey(List.of("age")) // dummy PK
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

        var result = DataGenerator.generate(
            List.of(table),
            100,
            false,
            Collections.emptyMap(),
            Collections.emptyMap()
        );

        assertNotNull(result);
        List<Row> rows = result.rows().get("USERS");
        assertNotNull(rows);
        // We requested 100 rows, but the domain [18, 65] only has 48 unique values.
        // The generator drops duplicates for PKs. So we expect <= 48 rows.
        assertTrue(rows.size() <= 48, "Should not exceed max unique values (48) but got " + rows.size());
        assertTrue(rows.size() > 0, "Should generate some rows");

        for (Row row : rows) {
            int age = (int) row.values().get("age");
            assertTrue(age >= 18 && age <= 65, "Age " + age + " should be between 18 and 65");
        }
    }

    @Test
    @DisplayName("Should generate unique UUIDs")
    void shouldGenerateUniqueUuids() {
        Column idCol = Column.builder()
            .name("id")
            .jdbcType(Types.VARCHAR) // or OTHER
            .uuid(true)
            .nullable(false)
            .primaryKey(true)
            .build();

        Table table = Table.builder()
            .name("ITEMS")
            .columns(List.of(idCol))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

        var result = DataGenerator.generate(
            List.of(table),
            50,
            false,
            Collections.emptyMap(),
            Collections.emptyMap()
        );

        List<Row> rows = result.rows().get("ITEMS");
        assertEquals(50, rows.size());

        long uniqueCount = rows.stream()
            .map(r -> r.values().get("id"))
            .distinct()
            .count();

        assertEquals(50, uniqueCount, "All UUIDs should be unique");
    }
}
