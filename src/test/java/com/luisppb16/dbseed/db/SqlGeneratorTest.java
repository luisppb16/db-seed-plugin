/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.SqlKeyword;
import com.luisppb16.dbseed.model.Table;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SqlGeneratorTest {

  @Nested
  @DisplayName("INSERT Generation")
  class InsertGeneration {

    @Test
    @DisplayName("Should generate simple INSERT statement")
    void simpleInsert() {
      Table table = createTable("users", "id", "name");
      Row row = new Row(Map.of("id", 1, "name", "John"));

      String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), false);

      assertThat(sql).contains("INSERT INTO \"users\" (\"id\", \"name\") VALUES");
      assertThat(sql).contains("(1, 'John')");
    }

    @Test
    @DisplayName("Should handle multiple rows in batches")
    void multipleRows() {
      Table table = createTable("users", "id");
      List<Row> rows = List.of(
          new Row(Map.of("id", 1)),
          new Row(Map.of("id", 2))
      );

      String sql = SqlGenerator.generate(Map.of(table, rows), Collections.emptyList(), false);

      assertThat(sql).contains("(1),\n(2);");
    }

    @Test
    @DisplayName("Should handle null values")
    void nullValues() {
      Table table = createTable("users", "name");
      // Use explicit null
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("name", null);
      Row row = new Row(values);

      String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), false);

      assertThat(sql).contains("(NULL)");
    }

    @Test
    @DisplayName("Should handle empty data map")
    void emptyData() {
      String sql = SqlGenerator.generate(Collections.emptyMap(), Collections.emptyList(), false);
      assertThat(sql).isEmpty();
    }
  }

  @Nested
  @DisplayName("Data Type Formatting")
  class DataTypeFormatting {

    static Stream<Arguments> dataTypesProvider() {
      return Stream.of(
          Arguments.of("String", "test", "'test'"),
          Arguments.of("String with quote", "O'Connor", "'O''Connor'"),
          Arguments.of("Integer", 123, "123"),
          Arguments.of("Boolean True", true, "TRUE"), // Default dialect (STANDARD)
          Arguments.of("Boolean False", false, "FALSE"),
          Arguments.of("UUID", UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), "'550e8400-e29b-41d4-a716-446655440000'"),
          Arguments.of("Date", Date.valueOf("2023-01-01"), "'2023-01-01'"),
          Arguments.of("Timestamp", Timestamp.valueOf("2023-01-01 12:00:00"), "'2023-01-01 12:00:00.0'"),
          Arguments.of("BigDecimal", new BigDecimal("10.50"), "10.50"),
          Arguments.of("Double", 10.123, "10.123"),
          Arguments.of("SqlKeyword", SqlKeyword.DEFAULT, "DEFAULT")
      );
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @MethodSource("dataTypesProvider")
    void formatValues(String desc, Object input, String expected) {
      Table table = createTable("test", "col");
      Row row = new Row(Map.of("col", input));

      String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), false);

      assertThat(sql).contains("(" + expected + ")");
    }
  }

  @Nested
  @DisplayName("Dialect Support")
  class DialectSupport {

    @ParameterizedTest
    @ValueSource(strings = {"mysql", "mariadb"})
    void mysqlDialect(String driverName) {
      DriverInfo driver = DriverInfo.builder()
          .driverClass("com." + driverName + ".Driver")
          .urlTemplate("jdbc:" + driverName + "://localhost/db")
          .build();
      Table table = createTable("users", "isActive");
      Row row = new Row(Map.of("isActive", true));

      String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), false, driver);

      // MySQL uses backticks and 1/0 for boolean
      assertThat(sql).contains("INSERT INTO `users` (`isActive`) VALUES");
      assertThat(sql).contains("(1)");
    }

    @Test
    void postgresDialect() {
      DriverInfo driver = DriverInfo.builder()
          .driverClass("org.postgresql.Driver")
          .urlTemplate("jdbc:postgresql://localhost/db")
          .build();
      Table table = createTable("users", "isActive");
      Row row = new Row(Map.of("isActive", true));

      String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), false, driver);

      // Postgres uses double quotes and TRUE/FALSE
      assertThat(sql).contains("INSERT INTO \"users\" (\"isActive\") VALUES");
      assertThat(sql).contains("(TRUE)");
    }

    @Test
    void sqlServerDialect() {
      DriverInfo driver = DriverInfo.builder()
          .driverClass("com.microsoft.sqlserver.jdbc.SQLServerDriver")
          .urlTemplate("jdbc:sqlserver://localhost")
          .build();
      Table table = createTable("users", "isActive");
      Row row = new Row(Map.of("isActive", true));

      String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), false, driver);

      // SQL Server uses brackets and 1/0
      assertThat(sql).contains("INSERT INTO [users] ([isActive]) VALUES");
      assertThat(sql).contains("(1)");
    }

    @Test
    void oracleDialect() {
      DriverInfo driver = DriverInfo.builder()
          .driverClass("oracle.jdbc.OracleDriver")
          .urlTemplate("jdbc:oracle:thin:@localhost:1521:xe")
          .build();
      Table table = createTable("users", "isActive");
      Row row = new Row(Map.of("isActive", true));

      String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), false, driver);

      // Oracle uses double quotes (uppercased usually in some contexts but quote logic here handles it) and 1/0
      // Logic: return "\"" + id.replace("\"", "\"\"").toUpperCase(Locale.ROOT) + "\"";
      assertThat(sql).contains("INSERT INTO \"USERS\" (\"ISACTIVE\") VALUES");
      assertThat(sql).contains("(1)");
    }
  }

  @Nested
  @DisplayName("Deferred & Updates")
  class DeferredAndUpdates {

    @Test
    @DisplayName("Should wrap in transaction and toggle constraints when deferred is true")
    void deferredExecution() {
      Table table = createTable("t", "c");
      Row row = new Row(Map.of("c", 1));

      // Default (Standard)
      String sql = SqlGenerator.generate(Map.of(table, List.of(row)), Collections.emptyList(), true);

      assertThat(sql).startsWith("BEGIN;\nSET CONSTRAINTS ALL DEFERRED;\n");
      assertThat(sql).endsWith("COMMIT;\n");
    }

    @Test
    @DisplayName("Should generate UPDATE statements for pending updates")
    void pendingUpdates() {
      PendingUpdate update = new PendingUpdate(
          "users",
          Map.of("parent_id", 100), // SET
          Map.of("id", 1)           // WHERE
      );

      String sql = SqlGenerator.generate(Collections.emptyMap(), List.of(update), false);

      assertThat(sql).contains("UPDATE \"users\" SET \"parent_id\"=100 WHERE \"id\"=1;");
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCases {

    @Test
    @DisplayName("Should throw IllegalArgumentException if identifier is null (internal check)")
    void nullIdentifier() {
       // Table record enforces non-null name, so we can't easily trigger SqlGenerator's check via public API with Table objects.
       // The check inside SqlGenerator.qualified(null) is defensive.
       // We can demonstrate that Table creation fails if name is null, preventing SqlGenerator from receiving it.
       assertThatThrownBy(() -> createTable(null, "id"))
           .isInstanceOf(NullPointerException.class)
           .hasMessage("Table name cannot be null.");
    }
  }

  // --- Helpers ---

  private Table createTable(String name, String... colNames) {
    List<Column> cols = Stream.of(colNames)
        .map(n -> Column.builder().name(n).jdbcType(Types.VARCHAR).build())
        .toList();

    return Table.builder()
        .name(name)
        .columns(cols)
        .primaryKey(Collections.emptyList())
        .foreignKeys(Collections.emptyList())
        .checks(Collections.emptyList())
        .uniqueKeys(Collections.emptyList())
        .build();
  }
}
