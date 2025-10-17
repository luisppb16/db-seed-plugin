/*
 *  Copyright (c) 2025 Luis Pepe.
 *  All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.luisppb16.dbseed.schema.SchemaDsl;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.swing.*;

public final class SchemaDesigner extends JFrame {

  private final DefaultListModel<UiTable> model = new DefaultListModel<>();
  private final JTextArea sqlArea = new JTextArea();

  public SchemaDesigner() {
    super("Schema Designer");
    setDefaultCloseOperation(EXIT_ON_CLOSE);
    setSize(800, 600);
    initLayout();
  }

  public static void main(String[] args) {
    EventQueue.invokeLater(() -> new SchemaDesigner().setVisible(true));
  }

  private void initLayout() {
    JList<UiTable> tableList = new JList<>(model);
    JScrollPane scroll = new JScrollPane(tableList);

    JButton addTable = new JButton("Add Table");
    addTable.addActionListener(e -> addTable());

    JButton generate = new JButton("Generate SQL");
    generate.addActionListener(e -> generateSql());

    JPanel buttons = new JPanel();
    buttons.add(addTable);
    buttons.add(generate);

    sqlArea.setEditable(false);
    sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

    JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, new JScrollPane(sqlArea));
    main.setDividerLocation(250);

    add(main, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
  }

  private void addTable() {
    String tableName = JOptionPane.showInputDialog(this, "Table name:");
    if (tableName == null || tableName.isBlank()) {
      return;
    }
    ArrayList<SchemaDsl.Column> columns = new ArrayList<>();
    while (true) {
      String columnName = JOptionPane.showInputDialog(this, "Column name (blank to finish):");
      if (columnName == null || columnName.isBlank()) {
        break;
      }
      SchemaDsl.SqlType type =
          (SchemaDsl.SqlType)
              JOptionPane.showInputDialog(
                  this,
                  "SQL type:",
                  "Column Type",
                  JOptionPane.QUESTION_MESSAGE,
                  null,
                  SchemaDsl.SqlType.values(),
                  SchemaDsl.SqlType.INT);
      boolean pk =
          JOptionPane.showConfirmDialog(this, "Primary key?", "PK", JOptionPane.YES_NO_OPTION)
              == JOptionPane.YES_OPTION;
      columns.add(pk ? SchemaDsl.pk(columnName, type) : SchemaDsl.column(columnName, type));
    }
    if (!columns.isEmpty()) {
      model.addElement(new UiTable(tableName, columns));
    }
  }

  private void generateSql() {
    SchemaDsl.Table[] tables =
        Collections.list(model.elements()).stream()
            .map(t -> SchemaDsl.table(t.name(), t.columns().toArray(SchemaDsl.Column[]::new)))
            .toArray(SchemaDsl.Table[]::new);
    SchemaDsl.Schema schema = SchemaDsl.schema(tables);
    sqlArea.setText(SchemaDsl.toSql(schema));
  }

  private record UiTable(String name, List<SchemaDsl.Column> columns) {
    public UiTable {
      Objects.requireNonNull(name, "Table name cannot be null.");
      Objects.requireNonNull(columns, "Column list cannot be null.");
      columns = List.copyOf(columns);
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
