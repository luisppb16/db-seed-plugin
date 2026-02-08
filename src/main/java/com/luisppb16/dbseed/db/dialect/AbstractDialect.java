/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

import com.luisppb16.dbseed.db.Row;
import com.luisppb16.dbseed.model.SqlKeyword;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

public abstract class AbstractDialect implements DatabaseDialect {

    protected final Properties props = new Properties();
    private static final String NULL_STR = "NULL";

    protected AbstractDialect(String resourceName) {
        if (resourceName != null) {
            try (InputStream is = getClass().getResourceAsStream("/dialects/" + resourceName)) {
                if (is != null) {
                    props.load(is);
                }
            } catch (IOException ignored) {}
        }
    }

    @Override
    public String quote(String identifier) {
        String quoteChar = props.getProperty("quoteChar", "\"");
        String quoteEscape = props.getProperty("quoteEscape", "\"\"");
        return quoteChar + identifier.replace(quoteChar, quoteEscape) + quoteChar;
    }

    @Override
    public String formatBoolean(boolean b) {
        return b ? props.getProperty("booleanTrue", "TRUE") : props.getProperty("booleanFalse", "FALSE");
    }

    @Override
    public String beginTransaction() {
        return props.getProperty("beginTransaction", "BEGIN;\n");
    }

    @Override
    public String commitTransaction() {
        return props.getProperty("commitTransaction", "COMMIT;\n");
    }

    @Override
    public String disableConstraints() {
        return props.getProperty("disableConstraints", "");
    }

    @Override
    public String enableConstraints() {
        return props.getProperty("enableConstraints", "");
    }

    @Override
    public void formatValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append(NULL_STR);
        } else {
            switch (value) {
                case SqlKeyword k -> sb.append(k.name());
                case String s -> sb.append("'").append(escapeSql(s)).append("'");
                case Character c -> sb.append("'").append(escapeSql(c.toString())).append("'");
                case UUID u -> sb.append("'").append(u).append("'");
                case Date d -> sb.append("'").append(d).append("'");
                case Timestamp t -> sb.append("'").append(t).append("'");
                case Boolean b -> sb.append(formatBoolean(b));
                case BigDecimal bd -> sb.append(bd.toPlainString());
                case Double d -> sb.append(formatDouble(d));
                case Float f -> sb.append(formatDouble(f.doubleValue()));
                default -> sb.append(Objects.toString(value, NULL_STR));
            }
        }
    }

    protected String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return NULL_STR;
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    protected String escapeSql(String s) {
        return s.replace("'", "''");
    }

    @Override
    public void appendBatch(StringBuilder sb, String tableName, String columnList, List<Row> rows, List<String> columnOrder) {
        String header = props.getProperty("batchHeader", "INSERT INTO ${table} (${columns}) VALUES\n");
        String prefix = props.getProperty("batchRowPrefix", "(");
        String suffix = props.getProperty("batchRowSuffix", ")");
        String separator = props.getProperty("batchRowSeparator", ",\n");
        String footer = props.getProperty("batchFooter", ";\n");

        sb.append(header.replace("${table}", tableName).replace("${columns}", columnList));

        for (int i = 0; i < rows.size(); i++) {
            sb.append(prefix.replace("${table}", tableName).replace("${columns}", columnList));
            appendRowValues(sb, rows.get(i), columnOrder);
            sb.append(suffix);
            if (i < rows.size() - 1) {
                sb.append(separator);
            }
        }
        sb.append(footer);
    }

    protected void appendRowValues(StringBuilder sb, Row row, List<String> columnOrder) {
        for (int k = 0; k < columnOrder.size(); k++) {
            formatValue(row.values().get(columnOrder.get(k)), sb);
            if (k < columnOrder.size() - 1) {
                sb.append(", ");
            }
        }
    }
}
