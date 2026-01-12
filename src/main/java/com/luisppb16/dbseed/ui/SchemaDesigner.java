/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.luisppb16.dbseed.schema.SchemaDsl;
import com.luisppb16.dbseed.schema.SqlType;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import org.jetbrains.annotations.NotNull;

public final class SchemaDesigner extends JFrame {

  private static final String PREF_NODE = "com/luisppb16/dbseed/ui";
  private static final String PREF_X = "windowX";
  private static final String PREF_Y = "windowY";
  private static final String PREF_DIVIDER_LOCATION = "dividerLocation";

  private final DefaultListModel<UiTable> model = new DefaultListModel<>();
  private final JTextArea sqlArea = new JTextArea();
  private JSplitPane mainSplitPane;

  public SchemaDesigner() {
    super("Schema Designer");
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setPreferredSize(new Dimension(800, 600));
    pack();
    loadPosition();
    initLayout();

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(final WindowEvent e) {
            savePosition();
          }
        });
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

    mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, new JBScrollPane(sqlArea));

    final Preferences prefs = Preferences.userRoot().node(PREF_NODE);
    final int dividerLocation = prefs.getInt(PREF_DIVIDER_LOCATION, 250);
    mainSplitPane.setDividerLocation(dividerLocation);

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
    final JDialog progressDialog = new JDialog(this, "Generating SQL", true);
    final JProgressBar progressBar = new JProgressBar();
    progressBar.setIndeterminate(true);
    progressDialog.add(BorderLayout.CENTER, progressBar);
    progressDialog.add(BorderLayout.NORTH, new JPanel()); // Margin
    progressDialog.setSize(300, 75);
    progressDialog.setLocationRelativeTo(this);

    final SwingWorker<String, Void> worker =
        new SwingWorker<>() {
          @Override
          protected String doInBackground() throws Exception {
            final SchemaDsl.Table[] tables =
                Collections.list(model.elements()).stream()
                    .map(
                        t ->
                            SchemaDsl.table(t.name(), t.columns().toArray(SchemaDsl.Column[]::new)))
                    .toArray(SchemaDsl.Table[]::new);
            final SchemaDsl.Schema schema = SchemaDsl.schema(tables);
            return SchemaDsl.toSql(schema);
          }

          @Override
          protected void done() {
            progressDialog.dispose();
            try {
              sqlArea.setText(get());
            } catch (final InterruptedException | ExecutionException e) {
              JOptionPane.showMessageDialog(
                  SchemaDesigner.this,
                  "Error generating SQL: " + e.getMessage(),
                  "Error",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        };

    worker.execute();
    progressDialog.setVisible(true);
  }

  private void savePosition() {
    final Preferences prefs = Preferences.userRoot().node(PREF_NODE);
    final Point location = getLocation();
    prefs.putInt(PREF_X, location.x);
    prefs.putInt(PREF_Y, location.y);
    prefs.putInt(PREF_DIVIDER_LOCATION, mainSplitPane.getDividerLocation());
  }

  private void loadPosition() {
    final Preferences prefs = Preferences.userRoot().node(PREF_NODE);
    final int x = prefs.getInt(PREF_X, -1);
    final int y = prefs.getInt(PREF_Y, -1);

    if (x != -1 && y != -1) {
      setLocation(x, y);
    } else {
      // Center and move up
      setLocationRelativeTo(null);
      final Point location = getLocation();
      final int screenHeight = Toolkit.getDefaultToolkit().getScreenSize().height;
      final int newY = (int) (screenHeight * 0.2); // Move to 20% from top
      setLocation(location.x, newY);
    }
  }

  private record UiTable(String name, List<SchemaDsl.Column> columns) {
    public UiTable {
      Objects.requireNonNull(name, "Table name cannot be null.");
      Objects.requireNonNull(columns, "Column list cannot be null.");
      columns = List.copyOf(columns); // Ensure immutability
    }

    @Override
    @NotNull
    public String toString() {
      return name;
    }
  }
}
