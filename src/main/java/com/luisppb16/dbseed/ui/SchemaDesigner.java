/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.luisppb16.dbseed.schema.SchemaDsl;
import com.luisppb16.dbseed.schema.SqlType;
import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import org.jetbrains.annotations.NotNull; // Added this import

public final class SchemaDesigner extends JFrame {

  private final DefaultListModel<UiTable> model = new DefaultListModel<>();
  private final JTextArea sqlArea = new JTextArea();

  public SchemaDesigner() {
    super("Schema Designer");
    setDefaultCloseOperation(EXIT_ON_CLOSE);
    setSize(800, 600);
    initLayout();
  }

  public static void main(final String[] args) {
    EventQueue.invokeLater(() -> new SchemaDesigner().setVisible(true));
  }

  private void initLayout() {
    final JBList<UiTable> tableList = new JBList<>(model);
    final JBScrollPane scroll = new JBScrollPane(tableList);

    final JButton addTableButton = new JButton("Add Table");
    addTableButton.addActionListener(e -> addTable());

    final JButton generateButton = new JButton("Generate SQL");
    generateButton.addActionListener(e -> generateSql());

    final JPanel buttonsPanel = new JPanel();
    buttonsPanel.add(addTableButton);
    buttonsPanel.add(generateButton);

    sqlArea.setEditable(false);
    sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

    final JSplitPane mainSplitPane =
        new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, new JBScrollPane(sqlArea));
    mainSplitPane.setDividerLocation(250);

    add(mainSplitPane, BorderLayout.CENTER);
    add(buttonsPanel, BorderLayout.SOUTH);
  }

  private void addTable() {
    final String tableName = JOptionPane.showInputDialog(this, "Table name:");
    if (tableName == null || tableName.isBlank()) {
      return;
    }
    final List<SchemaDsl.Column> columns = collectColumns();
    if (!columns.isEmpty()) {
      model.addElement(new UiTable(tableName, columns));
    }
  }

  private List<SchemaDsl.Column> collectColumns() {
    final List<SchemaDsl.Column> columns = new ArrayList<>();
    String columnName;
    while ((columnName = JOptionPane.showInputDialog(this, "Column name (blank to finish):"))
            != null
        && !columnName.isBlank()) {
      final Optional<SqlType> type =
          Optional.ofNullable(
              (SqlType)
                  JOptionPane.showInputDialog(
                      this,
                      "SQL type:",
                      "Column Type",
                      JOptionPane.QUESTION_MESSAGE,
                      null,
                      SqlType.values(),
                      SqlType.INT));

      if (type.isPresent()) {
        final boolean isPrimaryKey =
            JOptionPane.showConfirmDialog(this, "Primary key?", "PK", JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION;

        columns.add(
            isPrimaryKey
                ? SchemaDsl.pk(columnName, type.get())
                : SchemaDsl.column(columnName, type.get()));
      }
    }
    return columns;
  }

  private void generateSql() {
    final SchemaDsl.Table[] tables =
        Collections.list(model.elements()).stream()
            .map(t -> SchemaDsl.table(t.name(), t.columns().toArray(SchemaDsl.Column[]::new)))
            .toArray(SchemaDsl.Table[]::new);
    final SchemaDsl.Schema schema = SchemaDsl.schema(tables);
    sqlArea.setText(SchemaDsl.toSql(schema));
  }

  private record UiTable(String name, List<SchemaDsl.Column> columns) {
    public UiTable {
      Objects.requireNonNull(name, "Table name cannot be null.");
      Objects.requireNonNull(columns, "Column list cannot be null.");
      columns = List.copyOf(columns); // Ensure immutability
    }

    @Override
    @NotNull // Added this annotation
    public String toString() {
      return name;
    }
  }
}
