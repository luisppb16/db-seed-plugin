/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luisppb16.dbseed.model.Table;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SchemaIntrospectorTest {

  @Mock private Connection connection;
  @Mock private DatabaseMetaData metaData;
  @Mock private ResultSet tableResultSet;
  @Mock private ResultSet columnResultSet;
  @Mock private ResultSet pkResultSet;
  @Mock private ResultSet fkResultSet;
  @Mock private ResultSet uniqueKeyResultSet;
  @Mock private PreparedStatement preparedStatement;
  @Mock private ResultSet checkConstraintsResultSet;

  @Nested
  @DisplayName("Basic Introspection")
  class BasicIntrospection {

    @Test
    @DisplayName("Should introspect tables and columns correctly")
    void introspectTablesAndColumns() throws SQLException {
      // Mocks
      when(connection.getMetaData()).thenReturn(metaData);

      // Tables
      when(metaData.getTables(any(), eq("public"), any(), any())).thenReturn(tableResultSet);
      when(tableResultSet.next()).thenReturn(true).thenReturn(false);
      when(tableResultSet.getString("TABLE_NAME")).thenReturn("users");
      when(tableResultSet.getString("TABLE_SCHEM")).thenReturn("public");
      when(tableResultSet.getString("REMARKS")).thenReturn("");
      when(metaData.getDatabaseProductName()).thenReturn("H2");

      // Columns
      when(metaData.getColumns(any(), eq("public"), any(), any())).thenReturn(columnResultSet);
      when(columnResultSet.next()).thenReturn(true).thenReturn(false);
      when(columnResultSet.getString("TABLE_NAME")).thenReturn("users");
      when(columnResultSet.getString("TABLE_SCHEM")).thenReturn("public");
      when(columnResultSet.getString("COLUMN_NAME")).thenReturn("id");
      when(columnResultSet.getInt("DATA_TYPE")).thenReturn(Types.INTEGER);
      when(columnResultSet.getString("TYPE_NAME")).thenReturn("INTEGER");
      when(columnResultSet.getString("IS_NULLABLE")).thenReturn("NO");
      when(columnResultSet.getInt("COLUMN_SIZE")).thenReturn(10);
      when(columnResultSet.getInt("DECIMAL_DIGITS")).thenReturn(0);
      when(columnResultSet.getString("REMARKS")).thenReturn("");

      // PKs
      when(metaData.getPrimaryKeys(any(), eq("public"), isNull())).thenReturn(pkResultSet);
      when(pkResultSet.next()).thenReturn(true).thenReturn(false);
      when(pkResultSet.getString("TABLE_NAME")).thenReturn("users");
      when(pkResultSet.getString("TABLE_SCHEM")).thenReturn("public");
      when(pkResultSet.getString("COLUMN_NAME")).thenReturn("id");

      // FKs
      when(metaData.getImportedKeys(any(), eq("public"), isNull())).thenReturn(fkResultSet);
      when(fkResultSet.next()).thenReturn(false);

      // Unique Keys
      lenient().when(metaData.getIndexInfo(any(), eq("public"), isNull(), eq(true), eq(false))).thenReturn(uniqueKeyResultSet);
      lenient().when(metaData.getIndexInfo(any(), eq("public"), eq("users"), eq(true), eq(false))).thenReturn(uniqueKeyResultSet);
      when(uniqueKeyResultSet.next()).thenReturn(false);

      // Checks (H2)
      when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
      when(preparedStatement.executeQuery()).thenReturn(checkConstraintsResultSet);
      when(checkConstraintsResultSet.next()).thenReturn(false);

      List<Table> tables = SchemaIntrospector.introspect(connection, "public");

      assertThat(tables).hasSize(1);
      Table users = tables.get(0);
      assertThat(users.name()).isEqualTo("users");
      assertThat(users.columns()).hasSize(1);
      assertThat(users.columns().get(0).name()).isEqualTo("id");
      assertThat(users.columns().get(0).primaryKey()).isTrue();
    }

    @Test
    @DisplayName("Should handle null connection")
    void nullConnection() {
      assertThatThrownBy(() -> SchemaIntrospector.introspect(null, "public"))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("Connection cannot be null");
    }
  }

  @Nested
  @DisplayName("Constraint Parsing")
  class ConstraintParsing {

      // Since parsing logic is private static inside SchemaIntrospector and tested via introspect integration,
      // we can verify it by mocking check constraints retrieval.

      @Test
      @DisplayName("Should parse CHECK constraint bounds")
      void checkConstraints() throws SQLException {
          // Setup basic table mock
          when(connection.getMetaData()).thenReturn(metaData);
          when(metaData.getTables(any(), any(), any(), any())).thenReturn(tableResultSet);
          when(tableResultSet.next()).thenReturn(true).thenReturn(false);
          when(tableResultSet.getString("TABLE_NAME")).thenReturn("items");
          when(tableResultSet.getString("TABLE_SCHEM")).thenReturn("public");
          when(tableResultSet.getString("REMARKS")).thenReturn("");
          when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL"); // Use Postgres logic

          when(metaData.getColumns(any(), any(), any(), any())).thenReturn(columnResultSet);
          when(columnResultSet.next()).thenReturn(true).thenReturn(false);
          when(columnResultSet.getString("TABLE_NAME")).thenReturn("items");
          when(columnResultSet.getString("TABLE_SCHEM")).thenReturn("public");
          when(columnResultSet.getString("COLUMN_NAME")).thenReturn("price");
          when(columnResultSet.getInt("DATA_TYPE")).thenReturn(Types.INTEGER);

          lenient().when(metaData.getPrimaryKeys(any(), any(), isNull())).thenReturn(pkResultSet);
          lenient().when(metaData.getPrimaryKeys(any(), any(), eq("items"))).thenReturn(pkResultSet);
          lenient().when(metaData.getImportedKeys(any(), any(), isNull())).thenReturn(fkResultSet);
          lenient().when(metaData.getImportedKeys(any(), any(), eq("items"))).thenReturn(fkResultSet);
          lenient().when(metaData.getIndexInfo(any(), any(), isNull(), anyBoolean(), anyBoolean())).thenReturn(uniqueKeyResultSet);
          lenient().when(metaData.getIndexInfo(any(), any(), eq("items"), anyBoolean(), anyBoolean())).thenReturn(uniqueKeyResultSet);

          // Mock Postgres check constraint query
          when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
          when(preparedStatement.executeQuery()).thenReturn(checkConstraintsResultSet);
          when(checkConstraintsResultSet.next()).thenReturn(true).thenReturn(false);
          when(checkConstraintsResultSet.getString("nspname")).thenReturn("public");
          when(checkConstraintsResultSet.getString("relname")).thenReturn("items");
          // CHECK (price BETWEEN 10 AND 100)
          when(checkConstraintsResultSet.getString("condef")).thenReturn("CHECK (price BETWEEN 10 AND 100)");

          List<Table> tables = SchemaIntrospector.introspect(connection, "public");

          assertThat(tables).hasSize(1);
          Table items = tables.get(0);
          assertThat(items.columns()).hasSize(1);
          assertThat(items.columns().get(0).minValue()).isEqualTo(10);
          assertThat(items.columns().get(0).maxValue()).isEqualTo(100);
      }
  }
}
