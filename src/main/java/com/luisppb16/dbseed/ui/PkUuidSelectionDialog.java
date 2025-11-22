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
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jetbrains.annotations.NotNull;

public final class PkUuidSelectionDialog extends DialogWrapper {

  private final List<Table> tables;
  private final Map<String, Set<String>> selectionByTable = new LinkedHashMap<>();
  private final Map<String, Set<String>> excludedColumnsByTable = new LinkedHashMap<>();

  public PkUuidSelectionDialog(@NotNull final List<Table> tables) {
    super(true);
    this.tables = Objects.requireNonNull(tables, "Table list cannot be null.");
    setTitle("PKs UUID Generation Preferences");
    initDefaults();
    init();
  }

  private void initDefaults() {
    tables.forEach(
        table -> {
          final Set<String> defaults =
              table.primaryKey().stream()
                  .filter(
                      pkCol -> {
                        final Column col = table.column(pkCol);
                        final String lower = pkCol.toLowerCase(Locale.ROOT);
                        return (col != null && col.uuid())
                            || lower.contains("uuid")
                            || lower.contains("guid");
                      })
                  .collect(Collectors.toCollection(LinkedHashSet::new));
          selectionByTable.put(table.name(), defaults);
        });
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    final JBTabbedPane tabbedPane = new JBTabbedPane();
    tabbedPane.addTab("PK UUID Selection", createPkSelectionPanel());
    tabbedPane.addTab("Exclude Columns", createColumnExclusionPanel());

    final JPanel content = new JPanel(new BorderLayout());
    content.add(tabbedPane, BorderLayout.CENTER);
    content.setPreferredSize(new Dimension(550, 400));
    return content;
  }

  private JPanel createConfiguredListPanel() {
    final JPanel listPanel = new JPanel(new GridBagLayout());
    listPanel.setBorder(JBUI.Borders.empty(8));
    return listPanel;
  }

  private GridBagConstraints createDefaultGridBagConstraints() {
    final GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.NORTHWEST;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = JBUI.insets(4);
    c.weightx = 1.0;
    return c;
  }

  private JComponent createPkSelectionPanel() {
    final JPanel listPanel = createConfiguredListPanel();
    final GridBagConstraints c = createDefaultGridBagConstraints();

    final List<JCheckBox> checkBoxes = new ArrayList<>();
    tables.forEach(
        table -> {
          final JLabel tblLabel = new JLabel(table.name());
          tblLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
          listPanel.add(tblLabel, c);
          c.gridy++;

          table
              .primaryKey()
              .forEach(
                  pkCol -> {
                    final JCheckBox box = new JCheckBox("Treat as UUID: " + pkCol);
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
    final JPanel listPanel = createConfiguredListPanel();
    final GridBagConstraints c = createDefaultGridBagConstraints();

    final List<JCheckBox> checkBoxes = new ArrayList<>();
    tables.forEach(
        table -> {
          final JLabel tblLabel = new JLabel(table.name());
          tblLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
          listPanel.add(tblLabel, c);
          c.gridy++;

          table
              .columns()
              .forEach(
                  column -> {
                    final JCheckBox box = new JCheckBox("Exclude: " + column.name());
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
      final JPanel listPanel, final List<JCheckBox> checkBoxes, final boolean withSearch) {
    final JButton toggleButton = new JButton();
    final AtomicBoolean isBulkUpdating = new AtomicBoolean(false);

    final Runnable updateButtonState =
        () -> {
          final boolean allSelected = checkBoxes.stream().allMatch(AbstractButton::isSelected);
          toggleButton.setText(allSelected ? "Deselect All" : "Select All");
        };

    checkBoxes.forEach(
        box ->
            box.addActionListener(
                e -> {
                  if (!isBulkUpdating.get()) {
                    updateButtonState.run();
                  }
                }));
    updateButtonState.run();

    toggleButton.addActionListener(
        e -> {
          try {
            isBulkUpdating.set(true);
            final boolean selectAll = "Select All".equals(toggleButton.getText());
            checkBoxes.stream()
                .filter(box -> box.isSelected() != selectAll)
                .forEach(AbstractButton::doClick);
          } finally {
            isBulkUpdating.set(false);
            updateButtonState.run();
          }
        });

    final JPanel topPanel = new JPanel(new BorderLayout(8, 8));
    topPanel.add(toggleButton, BorderLayout.WEST);

    if (withSearch) {
      addSearchFunctionality(topPanel, listPanel);
    }

    final JPanel mainPanel = new JPanel(new BorderLayout());
    mainPanel.add(topPanel, BorderLayout.NORTH);
    mainPanel.add(new JBScrollPane(listPanel), BorderLayout.CENTER);

    return mainPanel;
  }

  private void addSearchFunctionality(final JPanel topPanel, final JPanel listPanel) {
    final JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    final JTextField searchField = new JTextField(20);
    searchField.getDocument().addDocumentListener(createFilterListener(searchField, listPanel));
    searchPanel.add(new JLabel("Search:"));
    searchPanel.add(Box.createHorizontalStrut(4));
    searchPanel.add(searchField);
    topPanel.add(searchPanel, BorderLayout.EAST);
  }

  private DocumentListener createFilterListener(
      final JTextField searchField, final JPanel listPanel) {
    return new DocumentListener() {
      @Override
      public void insertUpdate(final DocumentEvent e) {
        filter();
      }

      @Override
      public void removeUpdate(final DocumentEvent e) {
        filter();
      }

      @Override
      public void changedUpdate(final DocumentEvent e) {
        filter();
      }

      private void filter() {
        filterPanelComponents(listPanel, searchField.getText());
      }
    };
  }

  private void filterPanelComponents(final JPanel listPanel, final String searchText) {
    final String lowerSearchText = searchText.toLowerCase(Locale.ROOT);
    final Map<JLabel, List<JCheckBox>> tableMapping = new LinkedHashMap<>();

    buildTableColumnMapping(listPanel, tableMapping);

    tableMapping.forEach(
        (tableLabel, checkBoxes) -> {
          final boolean isTableLabelVisible =
              tableLabel.getText().toLowerCase(Locale.ROOT).contains(lowerSearchText);

          if (isTableLabelVisible) {
            tableLabel.setVisible(true);
            checkBoxes.forEach(c -> c.setVisible(true));
          } else {
            final boolean anyChildVisible =
                checkBoxes.stream()
                    .anyMatch(
                        checkBox -> {
                          final boolean isCheckBoxVisible =
                              checkBox.getText().toLowerCase(Locale.ROOT).contains(lowerSearchText);
                          checkBox.setVisible(isCheckBoxVisible);
                          return isCheckBoxVisible;
                        });
            tableLabel.setVisible(anyChildVisible);
          }
        });
  }

  private void buildTableColumnMapping(
      final JPanel listPanel, final Map<JLabel, List<JCheckBox>> tableMapping) {
    JLabel currentLabel = null;
    for (final Component component : listPanel.getComponents()) {
      if (component instanceof JLabel label) {
        currentLabel = label;
        tableMapping.put(currentLabel, new ArrayList<>());
      } else if (component instanceof JCheckBox checkBox && currentLabel != null) {
        tableMapping.get(currentLabel).add(checkBox);
      }
    }
  }

  public Map<String, Set<String>> getSelectionByTable() {
    final Map<String, Set<String>> out = new LinkedHashMap<>();
    selectionByTable.forEach((k, v) -> out.put(k, Set.copyOf(v)));
    return out;
  }

  public Map<String, Set<String>> getExcludedColumnsByTable() {
    final Map<String, Set<String>> out = new LinkedHashMap<>();
    excludedColumnsByTable.forEach((k, v) -> out.put(k, Set.copyOf(v)));
    return out;
  }
}
