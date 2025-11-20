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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
                    JCheckBox box = new JCheckBox("Treat as UUID: ".concat(pkCol));
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

    return createTogglableListPanel(listPanel, checkBoxes, false);
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
                    JCheckBox box = new JCheckBox("Exclude: ".concat(column.name()));
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

    return createTogglableListPanel(listPanel, checkBoxes, true);
  }

  private JComponent createTogglableListPanel(
      JPanel listPanel, List<JCheckBox> checkBoxes, boolean withSearch) {
    final JButton toggleButton = new JButton();
    final boolean[] isBulkUpdating = {false};

    final Runnable updateButtonState =
        () -> {
          boolean allSelected = checkBoxes.stream().allMatch(AbstractButton::isSelected);
          toggleButton.setText(allSelected ? "Deselect All" : "Select All");
        };

    checkBoxes.forEach(
        box ->
            box.addActionListener(
                e -> {
                  if (!isBulkUpdating[0]) {
                    updateButtonState.run();
                  }
                }));
    updateButtonState.run();

    toggleButton.addActionListener(
        e -> {
          try {
            isBulkUpdating[0] = true;
            boolean selectAll = "Select All".equals(toggleButton.getText());
            checkBoxes.forEach(
                box -> {
                  if (box.isSelected() != selectAll) {
                    box.doClick();
                  }
                });
          } finally {
            isBulkUpdating[0] = false;
            updateButtonState.run();
          }
        });

    JPanel topPanel = new JPanel(new BorderLayout(8, 8));
    topPanel.add(toggleButton, BorderLayout.WEST);

    if (withSearch) {
      addSearchFunctionality(topPanel, listPanel);
    }

    JPanel mainPanel = new JPanel(new BorderLayout());
    mainPanel.add(topPanel, BorderLayout.NORTH);
    mainPanel.add(new JBScrollPane(listPanel), BorderLayout.CENTER);

    return mainPanel;
  }

  private void addSearchFunctionality(JPanel topPanel, JPanel listPanel) {
    JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    JTextField searchField = new JTextField(20);
    searchField.getDocument().addDocumentListener(createFilterListener(searchField, listPanel));
    searchPanel.add(new JLabel("Search:"));
    searchPanel.add(Box.createHorizontalStrut(4));
    searchPanel.add(searchField);
    topPanel.add(searchPanel, BorderLayout.EAST);
  }

  private DocumentListener createFilterListener(JTextField searchField, JPanel listPanel) {
    return new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        filter();
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        filter();
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        filter();
      }

      private void filter() {
        filterPanelComponents(listPanel, searchField.getText());
      }
    };
  }

  private void filterPanelComponents(JPanel listPanel, String searchText) {
    final String lowerSearchText = searchText.toLowerCase(Locale.ROOT);
    final Map<JLabel, List<JCheckBox>> tableMapping = new LinkedHashMap<>();

    buildTableColumnMapping(listPanel, tableMapping);

    tableMapping.forEach(
        (tableLabel, checkBoxes) -> {
          boolean isTableLabelVisible =
              tableLabel.getText().toLowerCase(Locale.ROOT).contains(lowerSearchText);

          if (isTableLabelVisible) {
            tableLabel.setVisible(true);
            checkBoxes.forEach(c -> c.setVisible(true));
          } else {
            boolean anyChildVisible = false;
            for (JCheckBox checkBox : checkBoxes) {
              boolean isCheckBoxVisible =
                  checkBox.getText().toLowerCase(Locale.ROOT).contains(lowerSearchText);
              checkBox.setVisible(isCheckBoxVisible);
              if (isCheckBoxVisible) {
                anyChildVisible = true;
              }
            }
            tableLabel.setVisible(anyChildVisible);
          }
        });
  }

  private void buildTableColumnMapping(
      JPanel listPanel, Map<JLabel, List<JCheckBox>> tableMapping) {
    JLabel currentLabel = null;
    for (Component component : listPanel.getComponents()) {
      if (component instanceof JLabel label) {
        currentLabel = label;
        tableMapping.put(currentLabel, new ArrayList<>());
      } else if (component instanceof JCheckBox checkBox && currentLabel != null) {
        tableMapping.get(currentLabel).add(checkBox);
      }
    }
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
