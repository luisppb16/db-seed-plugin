/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * ****************************************************************************
 */

package com.luisppb16.dbseed.db;

import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Analiza el impacto de exclusiones de tablas/columnas para anticipar errores de constraints.
 */
public final class ExclusionImpactAnalyzer {

  private ExclusionImpactAnalyzer() {}

  public static Result analyze(
      final List<Table> tables,
      final Map<String, Set<String>> excludedColumnsByTable,
      final Set<String> excludedTables) {

    final List<Warning> warnings = new ArrayList<>();
    final Set<String> dedupe = new LinkedHashSet<>();

    for (final Table table : tables) {
      if (excludedTables.contains(table.name())) {
        continue;
      }

      analyzeExcludedColumns(table, excludedColumnsByTable, warnings, dedupe);
      analyzeExcludedParentDependencies(table, excludedTables, warnings, dedupe);
      analyzeExcludedReferencedParentColumns(
          table, excludedColumnsByTable, excludedTables, warnings, dedupe);
    }

    return new Result(List.copyOf(warnings));
  }

  private static void analyzeExcludedColumns(
      final Table table,
      final Map<String, Set<String>> excludedColumnsByTable,
      final List<Warning> warnings,
      final Set<String> dedupe) {
    for (final String excludedCol : excludedColumnsByTable.getOrDefault(table.name(), Set.of())) {
      final Column col = table.column(excludedCol);
      if (Objects.isNull(col)) {
        continue;
      }

      if (table.primaryKey().stream().anyMatch(pk -> equalsIgnoreCase(pk, col.name()))) {
        addWarning(
            warnings,
            dedupe,
            "Se excluyo PK " + table.name() + "." + col.name(),
            "Si necesitas omitirla, es mas seguro excluir la tabla completa " + table.name() + ".");
      }

      if (!col.nullable()) {
        addWarning(
            warnings,
            dedupe,
            "Se excluyo columna NOT NULL " + table.name() + "." + col.name(),
            "Manten la columna incluida o excluye la tabla " + table.name() + " para evitar fallos de insercion.");
      }
    }
  }

  private static void analyzeExcludedParentDependencies(
      final Table table,
      final Set<String> excludedTables,
      final List<Warning> warnings,
      final Set<String> dedupe) {
    for (final ForeignKey fk : table.foreignKeys()) {
      final String parentTable = fk.pkTable();
      if (!excludedTables.contains(parentTable)) {
        continue;
      }

      final boolean hasRequiredFkCol =
          fk.columnMapping().keySet().stream()
              .map(table::column)
              .filter(Objects::nonNull)
              .anyMatch(col -> !col.nullable());

      if (hasRequiredFkCol) {
        addWarning(
            warnings,
            dedupe,
            "Tabla "
                + table.name()
                + " depende de "
                + parentTable
                + " con FK NOT NULL, pero "
                + parentTable
                + " esta excluida.",
            "Recomendado: excluye tambien "
                + table.name()
                + " o vuelve a incluir "
                + parentTable
                + ".");
      }
    }
  }

  private static void analyzeExcludedReferencedParentColumns(
      final Table childTable,
      final Map<String, Set<String>> excludedColumnsByTable,
      final Set<String> excludedTables,
      final List<Warning> warnings,
      final Set<String> dedupe) {
    for (final ForeignKey fk : childTable.foreignKeys()) {
      final String parentTable = fk.pkTable();
      if (excludedTables.contains(parentTable)) {
        continue;
      }

      final Set<String> excludedParentColumns =
          excludedColumnsByTable.getOrDefault(parentTable, Set.of());
      if (excludedParentColumns.isEmpty()) {
        continue;
      }

      fk.columnMapping()
          .forEach(
              (childColumnName, parentColumnName) -> {
                if (!excludedParentColumns.contains(parentColumnName)) {
                  return;
                }
                final Column childColumn = childTable.column(childColumnName);
                final boolean requiredChildRef = Objects.nonNull(childColumn) && !childColumn.nullable();

                addWarning(
                    warnings,
                    dedupe,
                    "Se excluyo columna referenciada "
                        + parentTable
                        + "."
                        + parentColumnName
                        + " y "
                        + childTable.name()
                        + "."
                        + childColumnName
                        + " depende de ella por FK"
                        + (requiredChildRef ? " NOT NULL." : "."),
                    requiredChildRef
                        ? "Recomendado: vuelve a incluir "
                            + parentTable
                            + "."
                            + parentColumnName
                            + " o excluye la tabla "
                            + childTable.name()
                            + "."
                        : "Recomendado: incluye "
                            + parentTable
                            + "."
                            + parentColumnName
                            + " para mantener consistencia referencial.");
              });
    }
  }

  private static void addWarning(
      final List<Warning> warnings,
      final Set<String> dedupe,
      final String risk,
      final String recommendation) {
    final String key = (risk + "|" + recommendation).toLowerCase(Locale.ROOT);
    if (dedupe.add(key)) {
      warnings.add(new Warning(risk, recommendation));
    }
  }

  private static boolean equalsIgnoreCase(final String left, final String right) {
    return Objects.nonNull(left) && Objects.nonNull(right) && left.equalsIgnoreCase(right);
  }

  public record Warning(String risk, String recommendation) {
    public Warning {
      Objects.requireNonNull(risk, "risk cannot be null");
      Objects.requireNonNull(recommendation, "recommendation cannot be null");
    }
  }

  public record Result(List<Warning> warnings) {
    public Result {
      warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings cannot be null"));
    }

    public boolean hasWarnings() {
      return !warnings.isEmpty();
    }

    public List<String> risks() {
      return warnings.stream().map(Warning::risk).toList();
    }

    public List<String> recommendations() {
      return warnings.stream().map(Warning::recommendation).distinct().toList();
    }
  }
}


