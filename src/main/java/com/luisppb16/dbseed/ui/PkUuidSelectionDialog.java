/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.Table;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;

public final class PkUuidSelectionDialog extends DialogWrapper {

  private final List<Table> tables;
  private final Map<String, Set<String>> selectionByTable = new LinkedHashMap<>();
  private final Map<String, Set<String>> excludedColumnsByTable = new LinkedHashMap<>();

  public PkUuidSelectionDialog(@NotNull List<Table> tables) {
    super(true);
    this.tables = Objects.requireNonNull(tables, "Table list cannot be null.");
    setTitle("Select PKs for UUID Generation");
    initDefaults();
    init();
  }

  private void initDefaults() {
    tables.forEach(
        t -> {
          Set<String> defaults = new LinkedHashSet<>();
          t.primaryKey()
              .forEach(
                  pkCol -> {
                    boolean preselect = false;
                    Column col = t.column(pkCol);
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
    JTabbedPane tabbedPane = new JBTabbedPane();
    tabbedPane.addTab("PK UUID Selection", createPkSelectionPanel());
    tabbedPane.addTab("Exclude Columns", createColumnExclusionPanel());

    JPanel content = new JPanel(new BorderLayout());
    content.add(tabbedPane, BorderLayout.CENTER);
    content.setPreferredSize(new Dimension(550, 400));
    return content;
  }

  private JPanel createConfiguredListPanel() {
    JPanel listPanel = new JPanel(new GridBagLayout());
    listPanel.setBorder(JBUI.Borders.empty(8));
    return listPanel;
  }

  private GridBagConstraints createDefaultGridBagConstraints() {
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.NORTHWEST;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = JBUI.insets(4);
    c.weightx = 1.0;
    return c;
  }

  private JComponent createPkSelectionPanel() {
    JPanel listPanel = createConfiguredListPanel();
    GridBagConstraints c = createDefaultGridBagConstraints();

    List<JCheckBox> checkBoxes = new ArrayList<>();
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
                        selectionByTable.getOrDefault(table.name(), Set.of()).contains(pkCol));
                    box.addActionListener(
                        e -> {
                          selectionByTable.computeIfAbsent(
                              table.name(), k -> new LinkedHashSet<>());
                          if (box.isSelected()) {
                            selectionByTable.get(table.name()).add(pkCol);
                          } else {
                            selectionByTable.get(table.name()).remove(pkCol);
                          }
                        });
                    listPanel.add(box, c);
                    c.gridy++;
                    checkBoxes.add(box);
                  });
        });

    return createTogglableListPanel(listPanel, checkBoxes);
  }

  private JComponent createColumnExclusionPanel() {
    JPanel listPanel = createConfiguredListPanel();
    GridBagConstraints c = createDefaultGridBagConstraints();

    List<JCheckBox> checkBoxes = new ArrayList<>();
    tables.forEach(
        table -> {
          JLabel tblLabel = new JLabel(table.name());
          tblLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
          listPanel.add(tblLabel, c);
          c.gridy++;

          table
              .columns()
              .forEach(
                  column -> {
                    JCheckBox box = new JCheckBox("Exclude: " + column.name());
                    box.setSelected(
                        excludedColumnsByTable
                            .getOrDefault(table.name(), Set.of())
                            .contains(column.name()));
                    box.addActionListener(
                        e -> {
                          excludedColumnsByTable.computeIfAbsent(
                              table.name(), k -> new LinkedHashSet<>());
                          if (box.isSelected()) {
                            excludedColumnsByTable.get(table.name()).add(column.name());
                          } else {
                            excludedColumnsByTable.get(table.name()).remove(column.name());
                          }
                        });
                    listPanel.add(box, c);
                    c.gridy++;
                    checkBoxes.add(box);
                  });
        });

    return createTogglableListPanel(listPanel, checkBoxes);
  }

  private JComponent createTogglableListPanel(JPanel listPanel, List<JCheckBox> checkBoxes) {
    final JButton toggleButton = new JButton();
    // Use a mutable wrapper for the flag to be used in lambda
    final boolean[] isBulkUpdating = {false};

    final Runnable updateButtonState =
        () -> {
          boolean allSelected = checkBoxes.stream().allMatch(AbstractButton::isSelected);
          toggleButton.setText(allSelected ? "Deselect All" : "Select All");
        };

    // Add listener to each checkbox to update the button state, but only if not in bulk mode
    checkBoxes.forEach(
        box ->
            box.addActionListener(
                e -> {
                  if (!isBulkUpdating[0]) {
                    updateButtonState.run();
                  }
                }));
    updateButtonState.run(); // Set initial button text

    toggleButton.addActionListener(
        e -> {
          try {
            isBulkUpdating[0] = true; // Enter bulk update mode
            boolean selectAll = "Select All".equals(toggleButton.getText());
            checkBoxes.forEach(
                box -> {
                  if (box.isSelected() != selectAll) {
                    // doClick triggers the checkbox's own action listener, which updates the model
                    box.doClick();
                  }
                });
          } finally {
            isBulkUpdating[0] = false; // Exit bulk update mode
            updateButtonState.run(); // Update button text once after all changes
          }
        });

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
    buttonPanel.add(toggleButton);

    JPanel mainPanel = new JPanel(new BorderLayout());
    mainPanel.add(buttonPanel, BorderLayout.NORTH);
    mainPanel.add(new JBScrollPane(listPanel), BorderLayout.CENTER);

    return mainPanel;
  }

  public Map<String, Set<String>> getSelectionByTable() {
    Map<String, Set<String>> out = new LinkedHashMap<>();
    selectionByTable.forEach((k, v) -> out.put(k, Set.copyOf(v)));
    return out;
  }

  public Map<String, Set<String>> getExcludedColumnsByTable() {
    Map<String, Set<String>> out = new LinkedHashMap<>();
    excludedColumnsByTable.forEach((k, v) -> out.put(k, Set.copyOf(v)));
    return out;
  }
}
