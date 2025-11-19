/*
 *  Copyright (c) 2025 Luis Pepe.
 *  All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.model.Table;
import java.awt.*;
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

  public PkUuidSelectionDialog(@NotNull List<Table> tables) {
    super(true);
    this.tables = Objects.requireNonNull(tables, "Table list cannot be null.");
    setTitle("Select PKs for UUID generation");
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
                  });
        });

    JScrollPane scroll = new JScrollPane(listPanel);
    JPanel content = new JPanel(new BorderLayout());
    content.add(scroll, BorderLayout.CENTER);
    return content;
  }

  public Map<String, Set<String>> getSelectionByTable() {
    Map<String, Set<String>> out = new LinkedHashMap<>();
    selectionByTable.forEach((k, v) -> out.put(k, Set.copyOf(v)));
    return out;
  }
}
