package com.luisppb16.dbseed.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelTest {

    @Test
    @DisplayName("Column should validate non-null fields")
    void columnShouldValidate() {
        assertThrows(NullPointerException.class, () ->
            new Column(null, Types.INTEGER, false, false, false, 0, 0, 0, null));
    }

    @Test
    @DisplayName("Column hasAllowedValues should work")
    void columnAllowedValues() {
        Column col = Column.builder()
            .name("status")
            .jdbcType(Types.VARCHAR)
            .allowedValues(Set.of("A", "B"))
            .build();

        assertTrue(col.hasAllowedValues());

        Column colEmpty = Column.builder()
            .name("status")
            .jdbcType(Types.VARCHAR)
            .allowedValues(Collections.emptySet())
            .build();

        assertFalse(colEmpty.hasAllowedValues());
    }

    @Test
    @DisplayName("ForeignKey should validate and generate default name")
    void fkValidationAndDefaultName() {
        assertThrows(NullPointerException.class, () ->
            new ForeignKey("fk", null, Map.of(), false));

        ForeignKey fk = ForeignKey.builder()
            .pkTable("parent")
            .columnMapping(Map.of("child_id", "parent_id"))
            .build();

        assertNotNull(fk.name());
        assertEquals("fk_parent_child_id", fk.name());
    }

    @Test
    @DisplayName("Table should validate inputs")
    void tableValidation() {
        assertThrows(NullPointerException.class, () ->
            new Table(null, List.of(), List.of(), List.of(), List.of(), List.of()));

        Table t = Table.builder()
            .name("t")
            .columns(List.of(Column.builder().name("c").build()))
            .primaryKey(List.of())
            .foreignKeys(List.of())
            .checks(List.of())
            .uniqueKeys(List.of())
            .build();

        assertNotNull(t.column("c"));
        assertEquals("c", t.column("c").name());
    }
}
