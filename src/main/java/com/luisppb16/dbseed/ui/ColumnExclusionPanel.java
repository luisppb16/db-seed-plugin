/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.model.Table;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class ColumnExclusionPanel extends JPanel {

  private final List<Table> tables;
  private final JTextField searchField = new JTextField();
  private final JPanel listPanel = new JPanel(new GridBagLayout());
  private final Map<String, Set<String>> excludedColumns;

  public ColumnExclusionPanel(List<Table> tables, Map<String, Set<String>> excludedColumns) {
    super(new BorderLayout());
    this.tables = tables;
    this.excludedColumns = excludedColumns;

    searchField
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                rebuildList();
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                rebuildList();
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                rebuildList();
              }
            });
    JPanel searchPanel = new JPanel(new BorderLayout());
    searchPanel.add(new JLabel("Search:"), BorderLayout.WEST);
    searchPanel.add(searchField, BorderLayout.CENTER);
    add(searchPanel, BorderLayout.NORTH);

    rebuildList();
    JBScrollPane scroll = new JBScrollPane(listPanel);
    scroll.setPreferredSize(JBUI.size(600, 400));
    add(scroll, BorderLayout.CENTER);
  }

  private void rebuildList() {
    listPanel.removeAll();
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.NORTHWEST;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = JBUI.insets(4);
    c.weightx = 1.0;

    String searchTerm = searchField.getText().toLowerCase();
    List<Table> filteredTables =
        tables.stream()
            .filter(
                table ->
                    table.name().toLowerCase().contains(searchTerm)
                        || table.columns().stream()
                            .anyMatch(col -> col.name().toLowerCase().contains(searchTerm)))
            .toList();

    for (Table table : filteredTables) {
      JLabel tblLabel = new JLabel(table.name());
      tblLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
      listPanel.add(tblLabel, c);
      c.gridy++;

      for (com.luisppb16.dbseed.model.Column column : table.columns()) {
        JCheckBox box = new JCheckBox(column.name());
        box.setSelected(
            excludedColumns.getOrDefault(table.name(), new HashSet<>()).contains(column.name()));
        box.addActionListener(
            e -> {
              if (box.isSelected()) {
                excludedColumns
                    .computeIfAbsent(table.name(), k -> new HashSet<>())
                    .add(column.name());
              } else {
                Set<String> excluded = excludedColumns.get(table.name());
                if (excluded != null) {
                  excluded.remove(column.name());
                }
              }
            });
        listPanel.add(box, c);
        c.gridy++;
      }
    }
    listPanel.revalidate();
    listPanel.repaint();
  }
}
