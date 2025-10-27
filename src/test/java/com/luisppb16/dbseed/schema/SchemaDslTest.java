/*
 *  Copyright (c) 2025 Luis Pepe.
 *  All rights reserved.
 */

package com.luisppb16.dbseed.schema;

import static com.luisppb16.dbseed.schema.SchemaDsl.column;
import static com.luisppb16.dbseed.schema.SchemaDsl.schema;
import static com.luisppb16.dbseed.schema.SchemaDsl.table;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.luisppb16.dbseed.schema.SchemaDsl.CheckConstraint;
import com.luisppb16.dbseed.schema.SchemaDsl.SqlType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaDslTest {

  @Test
  void testToSqlWithCheckConstraints() {
    SchemaDsl.Schema schema =
        schema(
            table(
                "users",
                column("id", SqlType.INT),
                column(
                    "status",
                    SqlType.VARCHAR,
                    new CheckConstraint.In(List.of("active", "inactive"))),
                column("age", SqlType.INT, new CheckConstraint.Between("18", "99"))));

    String expectedSql =
        "CREATE TABLE users (\n"
            + "  id INT,\n"
            + "  status VARCHAR(255) CHECK (value IN ('active', 'inactive')),\n"
            + "  age INT CHECK (value BETWEEN '18' AND '99')\n"
            + ");\n\n";

    assertEquals(expectedSql, SchemaDsl.toSql(schema));
  }
}
