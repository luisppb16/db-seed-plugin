/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.model.Table;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import org.jetbrains.annotations.NotNull;

public final class ColumnCustomizationDialog extends DialogWrapper {

  private final List<Table> tables;

  // Final state - only updated on OK
  private final Map<String, Set<String>> selectionByTable = new LinkedHashMap<>();
  private final Map<String, Set<String>> excludedColumns = new LinkedHashMap<>();

  // Working copies for the UI
  private final Map<String, Set<String>> workingSelectionByTable;
  private final Map<String, Set<String>> workingExcludedColumns;

  private final ColumnExclusionPanel columnExclusionPanel;

  public ColumnCustomizationDialog(@NotNull List<Table> tables) {
    super(true);
    this.tables = Objects.requireNonNull(tables, "Table list cannot be null.");

    // 1. Initialize the base state
    initDefaults(); // This populates the final selectionByTable

    // 2. Create deep copies for the UI to work with
    this.workingSelectionByTable = deepCopyMap(this.selectionByTable);
    this.workingExcludedColumns = deepCopyMap(this.excludedColumns);

    // 3. Pass the working copies to the UI panels
    this.columnExclusionPanel = new ColumnExclusionPanel(tables, workingExcludedColumns);

    setTitle("Select PKs for UUID Generation");
    init(); // This calls createCenterPanel which creates PkUuidPanel
  }

  private void initDefaults() {
    tables.forEach(
        t -> {
          Set<String> defaults = new LinkedHashSet<>();
          t.primaryKey()
              .forEach(
                  pkCol -> {
                    boolean preselect = false;
                    var col = t.column(pkCol);
                    if (col != null && col.uuid()) {
                      preselect = true;
                    }
                    String lower = pkCol.toLowerCase(Locale.ROOT);
                    if (!preselect && (lower.contains("uuid") || lower.contains("guid"))) {
                      preselect = true;
                    }
                    if (preselect) {
                      defaults.add(pkCol);
                    }
                  });
          selectionByTable.put(t.name(), defaults);
        });
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    JTabbedPane tabbedPane = new JTabbedPane();
    tabbedPane.addTab("PK UUID Selection", new PkUuidPanel());
    tabbedPane.addTab("Column Exclusion", columnExclusionPanel);
    return tabbedPane;
  }

  @Override
  protected void doOKAction() {
    // Commit the changes from the working copies to the final state
    this.selectionByTable.clear();
    this.selectionByTable.putAll(deepCopyMap(this.workingSelectionByTable));

    this.excludedColumns.clear();
    this.excludedColumns.putAll(deepCopyMap(this.workingExcludedColumns));

    super.doOKAction();
  }

  public Map<String, Set<String>> getSelectionByTable() {
    return deepCopyMap(selectionByTable);
  }

  public Map<String, Set<String>> getExcludedColumns() {
    return deepCopyMap(excludedColumns);
  }

  private Map<String, Set<String>> deepCopyMap(Map<String, Set<String>> original) {
    Map<String, Set<String>> copy = new LinkedHashMap<>();
    original.forEach((key, value) -> copy.put(key, new LinkedHashSet<>(value)));
    return copy;
  }

  private class PkUuidPanel extends JPanel {
    private final List<JCheckBox> checkBoxes = new ArrayList<>();

    PkUuidPanel() {
      super(new BorderLayout());

      JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
      buttonPanel.setBorder(JBUI.Borders.empty(0, 8));
      JButton selectAllButton = new JButton("Select All");
      JButton deselectAllButton = new JButton("Deselect All");
      buttonPanel.add(selectAllButton);
      buttonPanel.add(deselectAllButton);
      add(buttonPanel, BorderLayout.NORTH);

      JPanel listPanel = new JPanel(new GridBagLayout());
      listPanel.setBorder(JBUI.Borders.empty(8));

      GridBagConstraints c = new GridBagConstraints();
      c.gridx = 0;
      c.gridy = 0;
      c.anchor = GridBagConstraints.NORTHWEST;
      c.fill = GridBagConstraints.HORIZONTAL;
      c.insets = JBUI.insets(4);
      c.weightx = 1.0;

      tables.forEach(
          table -> {
            JLabel tblLabel = new JLabel(table.name());
            tblLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
            listPanel.add(tblLabel, c);
            c.gridy++;

            table
                .primaryKey()
                .forEach(
                    pkCol -> {
                      JCheckBox box = new JCheckBox("Treat as UUID: " + pkCol);
                      box.setSelected(
                          workingSelectionByTable
                              .getOrDefault(table.name(), Set.of())
                              .contains(pkCol));
                      box.addActionListener(
                          e -> {
                            workingSelectionByTable.computeIfAbsent(
                                table.name(), k -> new LinkedHashSet<>());
                            if (box.isSelected()) {
                              workingSelectionByTable.get(table.name()).add(pkCol);
                            } else {
                              workingSelectionByTable.get(table.name()).remove(pkCol);
                            }
                          });
                      listPanel.add(box, c);
                      c.gridy++;
                      checkBoxes.add(box);
                    });
          });

      selectAllButton.addActionListener(
          e -> {
            tables.forEach(
                table -> {
                  Set<String> pkCols = new LinkedHashSet<>(table.primaryKey());
                  workingSelectionByTable.put(table.name(), pkCols);
                });
            checkBoxes.forEach(box -> box.setSelected(true));
          });

      deselectAllButton.addActionListener(
          e -> {
            workingSelectionByTable.values().forEach(Set::clear);
            checkBoxes.forEach(box -> box.setSelected(false));
          });

      JBScrollPane scroll = new JBScrollPane(listPanel);
      scroll.setPreferredSize(JBUI.size(600, 400));
      add(scroll, BorderLayout.CENTER);
    }
  }
}
