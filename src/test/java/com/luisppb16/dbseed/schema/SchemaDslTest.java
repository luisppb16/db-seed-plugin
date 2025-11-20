package com.luisppb16.dbseed.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.luisppb16.dbseed.schema.SchemaDsl.Schema;
import com.luisppb16.dbseed.schema.SchemaDsl.Table;
import com.luisppb16.dbseed.schema.SchemaDsl.Column;
import com.luisppb16.dbseed.schema.SchemaDsl.SqlType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaDslTest {

    @Test
    @DisplayName("Should build schema with tables and columns")
    void shouldBuildSchema() {
        Schema schema = SchemaDsl.schema(
            SchemaDsl.table("users",
                SchemaDsl.pk("id", SqlType.INT),
                SchemaDsl.column("name", SqlType.VARCHAR)
            ),
            SchemaDsl.table("posts",
                SchemaDsl.pk("id", SqlType.INT),
                SchemaDsl.column("title", SqlType.VARCHAR),
                SchemaDsl.fk("user_id", SqlType.INT, "users", "id")
            )
        );

        assertNotNull(schema);
        assertEquals(2, schema.tables().size());

        Table users = schema.tables().get(0);
        assertEquals("users", users.name());
        assertEquals(2, users.columns().size());
        assertTrue(users.columns().get(0).primaryKey());

        Table posts = schema.tables().get(1);
        Column fkCol = posts.columns().get(2);
        assertTrue(fkCol.isForeignKey());
        assertEquals("users", fkCol.foreignKey().table());
    }

    @Test
    @DisplayName("Should generate SQL for schema")
    void shouldGenerateSql() {
        Schema schema = SchemaDsl.schema(
            SchemaDsl.table("users",
                SchemaDsl.pk("id", SqlType.INT)
            )
        );

        String sql = SchemaDsl.toSql(schema);

        assertTrue(sql.contains("CREATE TABLE users"));
        assertTrue(sql.contains("id INT PRIMARY KEY"));
    }
}
