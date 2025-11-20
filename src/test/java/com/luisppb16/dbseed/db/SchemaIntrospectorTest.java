package com.luisppb16.dbseed.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luisppb16.dbseed.model.Table;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SchemaIntrospectorTest {

    @Mock
    private Connection connection;

    @Mock
    private DatabaseMetaData metaData;

    @Test
    @DisplayName("Should infer bounds from CHECK constraint using BETWEEN")
    void shouldInferBoundsFromBetweenCheck() throws SQLException {
        String schema = "public";
        String tableName = "USERS";
        String checkDefinition = "CHECK (age BETWEEN 18 AND 65)";

        setupMocksForOneColumn(schema, tableName, "age", checkDefinition);

        List<Table> tables = SchemaIntrospector.introspect(connection, schema);

        assertNotNull(tables);
        Table table = tables.get(0);
        var col = table.columns().get(0);

        assertEquals(18, col.minValue());
        assertEquals(65, col.maxValue());
    }

    @Test
    @DisplayName("Should infer bounds from CHECK constraint using >= and <=")
    void shouldInferBoundsFromComparisonCheck() throws SQLException {
        String schema = "public";
        String tableName = "PRODUCTS";
        String checkDefinition = "CHECK (rating >= 1 AND rating <= 5)";

        setupMocksForOneColumn(schema, tableName, "rating", checkDefinition);

        List<Table> tables = SchemaIntrospector.introspect(connection, schema);

        assertNotNull(tables);
        Table table = tables.get(0);
        var col = table.columns().get(0);

        assertEquals(1, col.minValue());
        assertEquals(5, col.maxValue());
    }

    @Test
    @DisplayName("Should default bounds if no matching check found")
    void shouldReturnDefaultIfNoMatch() throws SQLException {
        String schema = "public";
        String tableName = "LOGS";
        String checkDefinition = "CHECK (other_col > 10)"; // Irrelevant check

        setupMocksForOneColumn(schema, tableName, "level", checkDefinition);

        List<Table> tables = SchemaIntrospector.introspect(connection, schema);

        assertNotNull(tables);
        Table table = tables.get(0);
        var col = table.columns().get(0);

        // Defaults are 0
        assertEquals(0, col.minValue());
        assertEquals(0, col.maxValue());
    }

    private void setupMocksForOneColumn(String schema, String tableName, String colName, String checkDefinition) throws SQLException {
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("H2");

        // 1. getTables (main loop)
        ResultSet tableRs = mock(ResultSet.class);
        when(tableRs.next()).thenReturn(true).thenReturn(false);
        when(tableRs.getString("TABLE_NAME")).thenReturn(tableName);
        when(metaData.getTables(null, schema, "%", new String[]{"TABLE"})).thenReturn(tableRs);

        // 2. loadTableCheckConstraints -> getTables (for remarks fallback)
        ResultSet checkRs = mock(ResultSet.class);
        when(checkRs.next()).thenReturn(true).thenReturn(false);
        when(checkRs.getString("REMARKS")).thenReturn(checkDefinition);
        when(metaData.getTables(null, schema, tableName, new String[]{"TABLE"})).thenReturn(checkRs);

        // 3. loadTableCheckConstraints -> getColumns (for remarks fallback)
        ResultSet colRsChecks = mock(ResultSet.class);
        when(colRsChecks.next()).thenReturn(true).thenReturn(false);
        when(colRsChecks.getString("REMARKS")).thenReturn(""); // assume check is on table, not column here

        // 4. loadColumns -> getColumns (actual loading)
        ResultSet colRsLoad = mock(ResultSet.class);
        when(colRsLoad.next()).thenReturn(true).thenReturn(false);
        when(colRsLoad.getString("COLUMN_NAME")).thenReturn(colName);
        when(colRsLoad.getInt("DATA_TYPE")).thenReturn(java.sql.Types.INTEGER);
        when(colRsLoad.getString("IS_NULLABLE")).thenReturn("NO");
        when(colRsLoad.getInt("COLUMN_SIZE")).thenReturn(10);

        when(metaData.getColumns(null, schema, tableName, "%"))
            .thenReturn(colRsChecks)
            .thenReturn(colRsLoad);

        // 5. loadPrimaryKeys (called by loadColumns AND introspect)
        ResultSet pkRs1 = mock(ResultSet.class); // for loadColumns
        when(pkRs1.next()).thenReturn(false);

        ResultSet pkRs2 = mock(ResultSet.class); // for introspect
        when(pkRs2.next()).thenReturn(false);

        when(metaData.getPrimaryKeys(null, schema, tableName))
            .thenReturn(pkRs1)
            .thenReturn(pkRs2);

        // 6. loadUniqueKeys
        ResultSet ukRs = mock(ResultSet.class);
        when(ukRs.next()).thenReturn(false);
        when(metaData.getIndexInfo(null, schema, tableName, true, false)).thenReturn(ukRs);

        // 7. loadForeignKeys
        ResultSet fkRs = mock(ResultSet.class);
        when(fkRs.next()).thenReturn(false);
        when(metaData.getImportedKeys(null, schema, tableName)).thenReturn(fkRs);
    }
}
