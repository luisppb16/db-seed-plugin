package com.luisppb16.dbseed.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlGeneratorTest {

    @Test
    @DisplayName("Should generate INSERT statements")
    void shouldGenerateInsertStatements() {
        Column id = Column.builder().name("id").jdbcType(Types.INTEGER).build();
        Column name = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
        Table table = Table.builder()
            .name("users")
            .columns(List.of(id, name))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

        Map<String, Object> row1Values = new LinkedHashMap<>();
        row1Values.put("id", 1);
        row1Values.put("name", "Alice");
        Row row1 = new Row(row1Values);

        Map<String, List<Row>> data = new LinkedHashMap<>();
        data.put("users", List.of(row1));

        String sql = SqlGenerator.generate(List.of(table), data, Collections.emptyList(), false);

        // Expected output should quote identifiers by default (based on SqlOptions implementation detail)
        // The implementation hardcodes quoteIdentifiers(true) in generate() method.
        // "INSERT INTO \"users\" (\"id\", \"name\") VALUES (1, 'Alice');"

        assertTrue(sql.contains("INSERT INTO \"users\""));
        assertTrue(sql.contains("VALUES (1, 'Alice');"));
    }

    @Test
    @DisplayName("Should format different data types correctly")
    void shouldFormatDataTypes() {
        Column colDate = Column.builder().name("dob").jdbcType(Types.DATE).build();
        Column colTs = Column.builder().name("created").jdbcType(Types.TIMESTAMP).build();
        Column colBool = Column.builder().name("active").jdbcType(Types.BOOLEAN).build();
        Column colUuid = Column.builder().name("uid").jdbcType(Types.OTHER).build();

        Table table = Table.builder()
            .name("types_table")
            .columns(List.of(colDate, colTs, colBool, colUuid))
            .primaryKey(Collections.emptyList())
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

        Date date = Date.valueOf("2023-01-01");
        Timestamp ts = Timestamp.valueOf("2023-01-01 10:00:00");
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("dob", date);
        values.put("created", ts);
        values.put("active", true);
        values.put("uid", uuid);
        Row row = new Row(values);

        Map<String, List<Row>> data = Map.of("types_table", List.of(row));

        String sql = SqlGenerator.generate(List.of(table), data, Collections.emptyList(), false);

        assertTrue(sql.contains("'2023-01-01'"));
        assertTrue(sql.contains("'2023-01-01 10:00:00.0'"));
        assertTrue(sql.contains("TRUE"));
        assertTrue(sql.contains("'550e8400-e29b-41d4-a716-446655440000'"));
    }

    @Test
    @DisplayName("Should handle deferred updates")
    void shouldHandleDeferredUpdates() {
        Table table = Table.builder()
            .name("cycle")
            .columns(List.of(Column.builder().name("id").jdbcType(Types.INTEGER).build()))
            .primaryKey(List.of("id"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

        PendingUpdate update = new PendingUpdate(
            "cycle",
            Map.of("fk", 100),
            Map.of("id", 1)
        );

        String sql = SqlGenerator.generate(
            List.of(table),
            Collections.emptyMap(),
            List.of(update),
            true // deferred = true
        );

        assertTrue(sql.contains("BEGIN;"));
        assertTrue(sql.contains("SET CONSTRAINTS ALL DEFERRED;"));
        assertTrue(sql.contains("UPDATE \"cycle\" SET \"fk\"=100 WHERE \"id\"=1;"));
        assertTrue(sql.contains("COMMIT;"));
    }

    @Test
    @DisplayName("Should escape single quotes in strings")
    void shouldEscapeQuotes() {
         Column name = Column.builder().name("name").jdbcType(Types.VARCHAR).build();
         Table table = Table.builder()
            .name("users")
            .columns(List.of(name))
            .primaryKey(List.of("name"))
            .foreignKeys(Collections.emptyList())
            .checks(Collections.emptyList())
            .uniqueKeys(Collections.emptyList())
            .build();

        Map<String, Object> row1Values = new LinkedHashMap<>();
        row1Values.put("name", "O'Reilly");
        Row row1 = new Row(row1Values);

        Map<String, List<Row>> data = new LinkedHashMap<>();
        data.put("users", List.of(row1));

        String sql = SqlGenerator.generate(List.of(table), data, Collections.emptyList(), false);

        assertTrue(sql.contains("'O''Reilly'"));
    }
}
