/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test fake: records every {@link GenerationProgressListener} event into synchronized lists, safe
 * for the concurrent AI column threads. Package-private, shared by ProgressTrackerTest and
 * DataGeneratorTest.
 */
final class RecordingProgressListener implements GenerationProgressListener {
  private final AtomicLong generalCount = new AtomicLong(0);
  private final List<Double> generalFractions = Collections.synchronizedList(new ArrayList<>());
  private final List<String> generalPhases = Collections.synchronizedList(new ArrayList<>());
  private final List<String> generalDetails = Collections.synchronizedList(new ArrayList<>());
  private final List<long[]> aiValues = Collections.synchronizedList(new ArrayList<>());
  private final List<String[]> aiColumns = Collections.synchronizedList(new ArrayList<>());
  private final List<String[]> tables = Collections.synchronizedList(new ArrayList<>());
  private final List<long[]> tablesPhaseStarted = Collections.synchronizedList(new ArrayList<>());
  private final List<long[]> aiPhaseStarted = Collections.synchronizedList(new ArrayList<>());
  private final List<Boolean> aiSkipped = Collections.synchronizedList(new ArrayList<>());

  @Override
  public void onGeneral(final double fraction, final String phaseText, final String detailText) {
    generalCount.incrementAndGet();
    generalFractions.add(fraction);
    generalPhases.add(phaseText);
    generalDetails.add(detailText);
  }

  @Override
  public void onAiValue(final long valuesDone, final long valuesTotal) {
    aiValues.add(new long[] {valuesDone, valuesTotal});
  }

  @Override
  public void onAiColumnCompleted(
      final String tableName,
      final String columnName,
      final int columnsDone,
      final int columnsTotal) {
    aiColumns.add(
        new String[] {
          tableName, columnName, String.valueOf(columnsDone), String.valueOf(columnsTotal)
        });
  }

  @Override
  public void onTableCompleted(
      final String tableName, final int tablesDone, final int tablesTotal) {
    tables.add(new String[] {tableName, String.valueOf(tablesDone), String.valueOf(tablesTotal)});
  }

  @Override
  public void onTablesPhaseStarted(final int totalTables) {
    tablesPhaseStarted.add(new long[] {totalTables});
  }

  @Override
  public void onAiPhaseStarted(final long totalValues, final int totalColumns) {
    aiPhaseStarted.add(new long[] {totalValues, totalColumns});
  }

  @Override
  public void onAiPhaseSkipped() {
    aiSkipped.add(Boolean.TRUE);
  }

  long generalEventCount() {
    return generalCount.get();
  }

  List<Double> generalFractions() {
    return List.copyOf(generalFractions);
  }

  List<String> generalPhases() {
    return new ArrayList<>(generalPhases);
  }

  List<String> generalDetails() {
    return new ArrayList<>(generalDetails);
  }

  List<long[]> aiValues() {
    return List.copyOf(aiValues);
  }

  List<String[]> aiColumns() {
    return List.copyOf(aiColumns);
  }

  List<String[]> tables() {
    return List.copyOf(tables);
  }

  List<long[]> phaseStarted() {
    return List.copyOf(tablesPhaseStarted);
  }

  List<long[]> aiPhaseStarted() {
    return List.copyOf(aiPhaseStarted);
  }

  boolean aiSkipped() {
    return !aiSkipped.isEmpty();
  }
}
