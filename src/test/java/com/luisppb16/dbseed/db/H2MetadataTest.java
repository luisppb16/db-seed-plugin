package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Table;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class H2MetadataTest {

    @Test
    public void testH2GetPrimaryKeysWithNullTableThrows() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:test1;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE users (id INT PRIMARY KEY)");
            }
            DatabaseMetaData metaData = conn.getMetaData();

            assertThrows(SQLException.class, () -> {
                metaData.getPrimaryKeys(null, "PUBLIC", null);
            }, "Expected getPrimaryKeys(null, schema, null) to throw SQLException in H2 2.4.240");
        }
    }

    @Test
    public void testSchemaIntrospectorWithH2() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:test2;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
                stmt.execute("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, FOREIGN KEY (parent_id) REFERENCES parent(id))");
            }

            List<Table> tables = assertDoesNotThrow(() -> {
                return SchemaIntrospector.introspect(conn, "PUBLIC");
            });

            assertFalse(tables.isEmpty());
            assertTrue(tables.stream().anyMatch(t -> t.name().equalsIgnoreCase("parent")));
            assertTrue(tables.stream().anyMatch(t -> t.name().equalsIgnoreCase("child")));

            Table child = tables.stream().filter(t -> t.name().equalsIgnoreCase("child")).findFirst().get();
            assertFalse(child.foreignKeys().isEmpty());
            assertTrue(child.foreignKeys().get(0).pkTable().equalsIgnoreCase("parent"));
        }
    }
}
