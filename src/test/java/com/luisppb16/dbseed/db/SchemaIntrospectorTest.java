/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SchemaIntrospectorTest {

  @Mock private Connection connection;

  @Mock private DatabaseMetaData metaData;

  @Test
  @DisplayName("Should infer bounds from CHECK constraint using BETWEEN")
  void shouldInferBoundsFromBetweenCheck() throws SQLException {
    String schema = "public";
    String tableName = "USERS";
    String checkDefinition = "CHECK (age BETWEEN 18 AND 65)";

    setupMocksForOneColumn(schema, tableName, "age", checkDefinition, Types.INTEGER);

    List<Table> tables = SchemaIntrospector.introspect(connection, schema);

    assertNotNull(tables);
    Table table = tables.getFirst();
    var col = table.columns().getFirst();

    assertEquals(18, col.minValue());
    assertEquals(65, col.maxValue());
  }

  @Test
  @DisplayName("Should infer bounds from CHECK constraint using >= and <=")
  void shouldInferBoundsFromComparisonCheck() throws SQLException {
    String schema = "public";
    String tableName = "PRODUCTS";
    String checkDefinition = "CHECK (rating >= 1 AND rating <= 5)";

    setupMocksForOneColumn(schema, tableName, "rating", checkDefinition, Types.INTEGER);

    List<Table> tables = SchemaIntrospector.introspect(connection, schema);

    assertNotNull(tables);
    Table table = tables.getFirst();
    var col = table.columns().getFirst();

    assertEquals(1, col.minValue());
    assertEquals(5, col.maxValue());
  }

  @Test
  @DisplayName("Should default bounds if no matching check found")
  void shouldReturnDefaultIfNoMatch() throws SQLException {
    String schema = "public";
    String tableName = "LOGS";
    String checkDefinition = "CHECK (other_col > 10)"; // Irrelevant check

    setupMocksForOneColumn(schema, tableName, "level", checkDefinition, Types.INTEGER);

    List<Table> tables = SchemaIntrospector.introspect(connection, schema);

    assertNotNull(tables);
    Table table = tables.getFirst();
    var col = table.columns().getFirst();

    // Defaults are 0
    assertEquals(0, col.minValue());
    assertEquals(0, col.maxValue());
  }

  @Test
  @DisplayName("Should return empty list if no tables found")
  void shouldReturnEmptyListIfNoTables() throws SQLException {
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("H2");

    ResultSet tableRs = mock(ResultSet.class);
    when(tableRs.next()).thenReturn(false); // No tables
    when(metaData.getTables(null, "public", "%", new String[] {"TABLE"})).thenReturn(tableRs);

    List<Table> tables = SchemaIntrospector.introspect(connection, "public");
    assertTrue(tables.isEmpty());
  }

  @Test
  @DisplayName("Should introspect table with multiple columns and types")
  void shouldIntrospectMultipleColumnsAndTypes() throws SQLException {
    String schema = "public";
    String tableName = "MULTI_TYPE_TABLE";

    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("H2");

    // Mock getTables
    ResultSet tableRs = mock(ResultSet.class);
    when(tableRs.next()).thenReturn(true).thenReturn(false);
    when(tableRs.getString("TABLE_NAME")).thenReturn(tableName);
    when(metaData.getTables(null, schema, "%", new String[] {"TABLE"})).thenReturn(tableRs);

    // Mock getTables for check constraints (no relevant checks for this test)
    ResultSet checkRs = mock(ResultSet.class);
    when(checkRs.next()).thenReturn(false);
    when(metaData.getTables(null, schema, tableName, new String[] {"TABLE"})).thenReturn(checkRs);

    // Mock getColumns for check constraints (no relevant checks for this test)
    ResultSet colRsChecks = mock(ResultSet.class);
    when(colRsChecks.next()).thenReturn(false);

    // Mock getColumns for actual column loading
    ResultSet colRsLoad = mock(ResultSet.class);
    when(colRsLoad.next())
        .thenReturn(true)
        .thenReturn(true)
        .thenReturn(true)
        .thenReturn(false); // Three columns
    when(colRsLoad.getString("COLUMN_NAME"))
        .thenReturn("id")
        .thenReturn("name")
        .thenReturn("created_at");
    when(colRsLoad.getInt("DATA_TYPE"))
        .thenReturn(Types.INTEGER)
        .thenReturn(Types.VARCHAR)
        .thenReturn(Types.TIMESTAMP);
    when(colRsLoad.getString("IS_NULLABLE")).thenReturn("NO").thenReturn("YES").thenReturn("NO");
    when(colRsLoad.getInt("COLUMN_SIZE")).thenReturn(10).thenReturn(255).thenReturn(0);

    when(metaData.getColumns(null, schema, tableName, "%"))
        .thenReturn(colRsChecks)
        .thenReturn(colRsLoad);

    // Mock getPrimaryKeys
    ResultSet pkRs = mock(ResultSet.class);
    when(pkRs.next()).thenReturn(true).thenReturn(false);
    when(pkRs.getString("COLUMN_NAME")).thenReturn("id");
    when(metaData.getPrimaryKeys(null, schema, tableName)).thenReturn(pkRs);

    // Mock getUniqueKeys
    ResultSet ukRs = mock(ResultSet.class);
    when(ukRs.next()).thenReturn(true).thenReturn(false);
    when(ukRs.getString("COLUMN_NAME")).thenReturn("name");
    when(ukRs.getString("INDEX_NAME")).thenReturn("UQ_NAME");
    when(metaData.getIndexInfo(null, schema, tableName, true, false)).thenReturn(ukRs);

    // Mock getImportedKeys (Foreign Keys)
    ResultSet fkRs = mock(ResultSet.class);
    when(fkRs.next()).thenReturn(true).thenReturn(false);
    when(fkRs.getString("FKCOLUMN_NAME")).thenReturn("user_id");
    when(fkRs.getString("PKTABLE_NAME")).thenReturn("users");
    when(fkRs.getString("PKCOLUMN_NAME")).thenReturn("id");
    when(metaData.getImportedKeys(null, schema, tableName)).thenReturn(fkRs);

    List<Table> tables = SchemaIntrospector.introspect(connection, schema);

    assertNotNull(tables);
    assertEquals(1, tables.size());
    Table table = tables.getFirst();
    assertEquals(tableName, table.name());
    assertEquals(3, table.columns().size());

    Column idCol = table.columns().getFirst();
    assertEquals("id", idCol.name());
    assertEquals(Types.INTEGER, idCol.jdbcType());
    assertEquals(10, idCol.length());
    assertFalse(idCol.nullable());
    assertTrue(table.primaryKey().contains("id"));

    Column nameCol = table.columns().get(1);
    assertEquals("name", nameCol.name());
    assertEquals(Types.VARCHAR, nameCol.jdbcType());
    assertEquals(255, nameCol.length());
    assertTrue(nameCol.nullable());
    assertTrue(table.uniqueKeys().stream().anyMatch(uk -> uk.contains("name")));

    Column createdAtCol = table.columns().get(2);
    assertEquals("created_at", createdAtCol.name());
    assertEquals(Types.TIMESTAMP, createdAtCol.jdbcType());
    assertEquals(0, createdAtCol.length());
    assertFalse(createdAtCol.nullable());
  }

  @Test
  @DisplayName("Should correctly introspect a column with VARCHAR type")
  void shouldIntrospectVarcharColumn() throws SQLException {
    String schema = "public";
    String tableName = "MESSAGES";
    String colName = "content";
    String checkDefinition = "CHECK (LENGTH(content) > 0)"; // Example check
    int jdbcType = Types.VARCHAR;

    setupMocksForOneColumn(schema, tableName, colName, checkDefinition, jdbcType);

    List<Table> tables = SchemaIntrospector.introspect(connection, schema);

    assertNotNull(tables);
    Table table = tables.getFirst();
    var col = table.columns().getFirst();

    assertEquals(colName, col.name());
    assertEquals(jdbcType, col.jdbcType());
    assertEquals(10, col.length()); // From mock setup
  }

  private void setupMocksForOneColumn(
      String schema, String tableName, String colName, String checkDefinition, int jdbcType)
      throws SQLException {
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("H2");

    // 1. getTables (main loop)
    ResultSet tableRs = mock(ResultSet.class);
    when(tableRs.next()).thenReturn(true).thenReturn(false);
    when(tableRs.getString("TABLE_NAME")).thenReturn(tableName);
    when(metaData.getTables(null, schema, "%", new String[] {"TABLE"})).thenReturn(tableRs);

    // 2. loadTableCheckConstraints -> getTables (for remarks fallback)
    ResultSet checkRs = mock(ResultSet.class);
    when(checkRs.next()).thenReturn(true).thenReturn(false);
    when(checkRs.getString("REMARKS")).thenReturn(checkDefinition);
    when(metaData.getTables(null, schema, tableName, new String[] {"TABLE"})).thenReturn(checkRs);

    // 3. loadTableCheckConstraints -> getColumns (for remarks fallback)
    ResultSet colRsChecks = mock(ResultSet.class);
    when(colRsChecks.next()).thenReturn(true).thenReturn(false);
    when(colRsChecks.getString("REMARKS"))
        .thenReturn(""); // assume check is on table, not column here

    // 4. loadColumns -> getColumns (actual loading)
    ResultSet colRsLoad = mock(ResultSet.class);
    when(colRsLoad.next()).thenReturn(true).thenReturn(false);
    when(colRsLoad.getString("COLUMN_NAME")).thenReturn(colName);
    when(colRsLoad.getInt("DATA_TYPE")).thenReturn(jdbcType);
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

    when(metaData.getPrimaryKeys(null, schema, tableName)).thenReturn(pkRs1).thenReturn(pkRs2);

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
