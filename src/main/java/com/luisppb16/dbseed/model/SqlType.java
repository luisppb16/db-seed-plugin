/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.sql.Types;

/**
 * Sealed interface representing SQL data types.
 */
public sealed interface SqlType {

    int jdbcType();

    record Numeric(int jdbcType, int precision, int scale) implements SqlType {}
    record Text(int jdbcType, int length) implements SqlType {}
    record DateTime(int jdbcType) implements SqlType {}
    record BooleanType() implements SqlType {
        @Override
        public int jdbcType() {
            return Types.BOOLEAN;
        }
    }
    record Other(int jdbcType, String typeName) implements SqlType {}

    static SqlType fromJdbc(int jdbcType, String typeName, int precision, int scale) {
        return switch (jdbcType) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIGINT,
                 Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.DOUBLE, Types.REAL ->
                 new Numeric(jdbcType, precision, scale);
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGVARCHAR, Types.LONGNVARCHAR ->
                 new Text(jdbcType, precision);
            case Types.DATE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME ->
                 new DateTime(jdbcType);
            case Types.BOOLEAN, Types.BIT ->
                 new BooleanType();
            default ->
                 new Other(jdbcType, typeName);
        };
    }
}
