/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.model.CircularReferenceTerminationMode;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.ForeignKey;
import com.luisppb16.dbseed.model.Table;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

public class CircularReferencesPanel extends JPanel {

  private final transient List<Table> tables;
  // tableName -> fkName -> depth
  private final Map<String, Map<String, Integer>> config = new HashMap<>();
  // tableName -> fkName -> termination mode name
  private final Map<String, Map<String, String>> terminationModes = new HashMap<>();
  private final Map<String, Map<String, Integer>> savedConfig;
  private final Map<String, Map<String, String>> savedTerminationModes;

  public CircularReferencesPanel(List<Table> tables) {
    this.tables = tables;
    final Map<String, Map<String, Integer>> persistedConfig =
        DbSeedSettingsState.getInstance().getCircularReferences();
    final Map<String, Map<String, String>> persistedTerminationModes =
        DbSeedSettingsState.getInstance().getCircularReferenceTerminationModes();
    this.savedConfig = persistedConfig == null ? new HashMap<>() : persistedConfig;
    this.savedTerminationModes =
        persistedTerminationModes == null ? new HashMap<>() : persistedTerminationModes;
    if (persistedConfig == null) {
      DbSeedSettingsState.getInstance().setCircularReferences(new HashMap<>());
    }
    if (persistedTerminationModes == null) {
      DbSeedSettingsState.getInstance().setCircularReferenceTerminationModes(new HashMap<>());
    }
    setLayout(new BorderLayout());
    setOpaque(false);
    initPanel();
  }

  private void initPanel() {
    final JPanel listPanel = new JPanel(new GridBagLayout());
    listPanel.setBorder(JBUI.Borders.empty(8));
    final GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.NORTHWEST;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = JBUI.insets(4);
    c.weightx = 1.0;

    for (Table table : tables) {
      List<ForeignKey> selfReferencingFks = getSelfReferencingFks(table);
      if (selfReferencingFks.isEmpty()) continue;

      final JBLabel tblLabel = new JBLabel(table.name());
      tblLabel.setFont(tblLabel.getFont().deriveFont(Font.BOLD));
      tblLabel.setBorder(JBUI.Borders.emptyBottom(4));

      final JPanel tablePanel = new JPanel(new BorderLayout(0, 8));
      tablePanel.setBorder(
          BorderFactory.createCompoundBorder(
              JBUI.Borders.customLine(UIManager.getColor("Component.borderColor"), 1, 0, 0, 0),
              JBUI.Borders.empty(12, 8)));
      tablePanel.setOpaque(false);
      tablePanel.add(tblLabel, BorderLayout.NORTH);

      final JPanel fksPanel = new JPanel(new GridBagLayout());
      fksPanel.setOpaque(false);
      final GridBagConstraints cc = new GridBagConstraints();
      cc.gridx = 0;
      cc.gridy = 0;
      cc.anchor = GridBagConstraints.NORTHWEST;
      cc.fill = GridBagConstraints.HORIZONTAL;
      cc.weightx = 1.0;
      cc.insets = JBUI.insets(2, 0);

      for (ForeignKey fk : selfReferencingFks) {
        addFkRow(table, fk, fksPanel, cc);
      }

      fksPanel.setBorder(JBUI.Borders.emptyLeft(20));
      tablePanel.add(fksPanel, BorderLayout.CENTER);

      listPanel.add(tablePanel, c);
      c.gridy++;
    }

    if (listPanel.getComponentCount() == 0) {
      JBLabel emptyLabel = new JBLabel("No self-referencing foreign keys found.");
      emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
      add(emptyLabel, BorderLayout.CENTER);
    } else {
      add(listPanel, BorderLayout.NORTH);
    }
  }

  private List<ForeignKey> getSelfReferencingFks(Table table) {
    return table.foreignKeys().stream().filter(fk -> fk.pkTable().equals(table.name())).toList();
  }

  private void addFkRow(Table table, ForeignKey fk, JPanel fksPanel, GridBagConstraints cc) {
    final Map<String, Integer> savedTableConfig =
        savedConfig.getOrDefault(table.name(), new HashMap<>());
    final Map<String, String> savedTableModes =
        savedTerminationModes.getOrDefault(table.name(), new HashMap<>());
    final Integer savedDepth = savedTableConfig.get(fk.name());
    final boolean isEnabled = savedDepth != null;
    final boolean supportsNullTermination = supportsNullTermination(table, fk);
    final CircularReferenceTerminationMode savedMode =
        supportsNullTermination
            ? CircularReferenceTerminationMode.fromPersistedValue(savedTableModes.get(fk.name()))
            : CircularReferenceTerminationMode.CLOSE_CYCLE;

    final JBCheckBox box =
        new JBCheckBox(fk.name() + " (" + String.join(", ", fk.columnMapping().keySet()) + ")");
    box.setSelected(isEnabled);

    final JSpinner spinner =
        new JSpinner(new SpinnerNumberModel(isEnabled ? savedDepth : 3, 1, 100, 1));
    spinner.setEnabled(isEnabled);

    final JComboBox<CircularReferenceTerminationMode> terminationModeBox =
        new JComboBox<>(
            new DefaultComboBoxModel<>(
                supportsNullTermination
                    ? CircularReferenceTerminationMode.values()
                    : new CircularReferenceTerminationMode[] {
                      CircularReferenceTerminationMode.CLOSE_CYCLE
                    }));
    terminationModeBox.setSelectedItem(savedMode);
    terminationModeBox.setEnabled(isEnabled && supportsNullTermination);

    if (!supportsNullTermination) {
      terminationModeBox.setToolTipText(
          "This FK cannot end with NULL because at least one FK column is non-nullable or part of the primary key.");
    }

    if (isEnabled) {
      config.computeIfAbsent(table.name(), k -> new HashMap<>()).put(fk.name(), savedDepth);
      terminationModes
          .computeIfAbsent(table.name(), k -> new HashMap<>())
          .put(fk.name(), savedMode.name());
    }

    box.addActionListener(
        e -> {
          spinner.setEnabled(box.isSelected());
          terminationModeBox.setEnabled(box.isSelected() && supportsNullTermination);
          updateConfig(
              table.name(),
              fk.name(),
              box.isSelected(),
              (Integer) spinner.getValue(),
              (CircularReferenceTerminationMode) terminationModeBox.getSelectedItem());
        });

    spinner.addChangeListener(
        e -> {
          if (box.isSelected()) {
            updateConfig(
                table.name(),
                fk.name(),
                true,
                (Integer) spinner.getValue(),
                (CircularReferenceTerminationMode) terminationModeBox.getSelectedItem());
          }
        });

    terminationModeBox.addActionListener(
        e -> {
          if (box.isSelected()) {
            updateConfig(
                table.name(),
                fk.name(),
                true,
                (Integer) spinner.getValue(),
                (CircularReferenceTerminationMode) terminationModeBox.getSelectedItem());
          }
        });

    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    row.setOpaque(false);
    row.add(box);
    row.add(new JBLabel("Max depth:"));
    row.add(spinner);
    row.add(new JBLabel("Termination:"));
    row.add(terminationModeBox);

    fksPanel.add(row, cc);
    cc.gridy++;
  }

  private boolean supportsNullTermination(Table table, ForeignKey fk) {
    return fk.columnMapping().keySet().stream()
        .map(table::column)
        .allMatch(this::columnSupportsNullTermination);
  }

  private boolean columnSupportsNullTermination(Column column) {
    return column != null && column.nullable() && !column.primaryKey();
  }

  private void updateConfig(
      String tableName,
      String fkName,
      boolean isSelected,
      int depth,
      CircularReferenceTerminationMode terminationMode) {
    Map<String, Integer> tableConfig = config.computeIfAbsent(tableName, k -> new HashMap<>());
    Map<String, String> tableModes =
        terminationModes.computeIfAbsent(tableName, k -> new HashMap<>());
    if (isSelected) {
      tableConfig.put(fkName, depth);
      tableModes.put(
          fkName,
          (terminationMode == null ? CircularReferenceTerminationMode.CLOSE_CYCLE : terminationMode)
              .name());
    } else {
      tableConfig.remove(fkName);
      tableModes.remove(fkName);
      if (tableConfig.isEmpty()) {
        config.remove(tableName);
      }
      if (tableModes.isEmpty()) {
        terminationModes.remove(tableName);
      }
    }

    DbSeedSettingsState.getInstance().setCircularReferences(config);
    DbSeedSettingsState.getInstance().setCircularReferenceTerminationModes(terminationModes);
  }

  public Map<String, Map<String, Integer>> getCircularReferences() {
    return config;
  }

  public Map<String, Map<String, String>> getCircularReferenceTerminationModes() {
    return terminationModes;
  }
}
