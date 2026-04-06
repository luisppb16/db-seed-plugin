/*
 * *****************************************************************************
 *  * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 *  * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.schema.SchemaDsl;
import com.luisppb16.dbseed.schema.SqlType;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import javax.swing.Action;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Visual database schema designer integration for the DBSeed plugin ecosystem.
 *
 * <p>This IntelliJ action provides a comprehensive visual interface for database schema design,
 * enabling users to create and modify database structures through an intuitive graphical interface.
 * The component bridges the gap between visual schema design and programmatic SQL generation,
 * allowing developers to rapidly prototype database structures and instantly generate corresponding
 * DDL statements. The implementation follows modern UI/UX principles with responsive design
 * patterns and native IntelliJ integration via {@link DialogWrapper}.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Providing an intuitive visual interface for database schema creation and modification
 *   <li>Generating syntactically correct SQL DDL statements from visual schema representations
 *   <li>Implementing asynchronous SQL generation to maintain UI responsiveness
 *   <li>Integrating seamlessly with IntelliJ's action system for consistent user experience
 *   <li>Ensuring data integrity through immutable model representations and validation
 * </ul>
 *
 * <p>The implementation utilizes SwingWorker for non-blocking SQL generation and implements proper
 * resource management patterns. The visual design follows IntelliJ's UI guidelines to ensure
 * consistency with the overall development environment.
 */
public final class SchemaDesigner extends AnAction {

  @Override
  public void actionPerformed(@NotNull final AnActionEvent e) {
    new SchemaDesignerDialog(e.getProject()).show();
  }

  private static final class SchemaDesignerDialog extends DialogWrapper {

    private final DefaultListModel<UiTable> model = new DefaultListModel<>();
    private final JTextArea sqlArea = new JTextArea();

    SchemaDesignerDialog(@Nullable final Project project) {
      super(project, true);
      setTitle("Schema Designer");
      setOKButtonText("Close");
      init();
    }

    @Override
    protected Action @NotNull [] createActions() {
      return new Action[] {getOKAction()};
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
      final JBList<UiTable> tableList = new JBList<>(model);
      final JBScrollPane scroll = new JBScrollPane(tableList);

      final JButton addTableButton = new JButton("Add Table", AllIcons.General.Add);
      addTableButton.setMnemonic('A');
      addTableButton.addActionListener(e -> addTable());

      final JButton generateButton = new JButton("Generate SQL", AllIcons.Actions.Execute);
      generateButton.setMnemonic('G');
      generateButton.addActionListener(e -> generateSql());

      final JPanel buttonsPanel = new JPanel();
      buttonsPanel.add(addTableButton);
      buttonsPanel.add(generateButton);

      sqlArea.setEditable(false);
      sqlArea.setFont(JBUI.Fonts.create(Font.MONOSPACED, 12));

      final JSplitPane mainSplitPane =
          new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, new JBScrollPane(sqlArea));
      mainSplitPane.setDividerLocation(250);

      final JPanel content = new JPanel(new BorderLayout());
      content.add(mainSplitPane, BorderLayout.CENTER);
      content.add(buttonsPanel, BorderLayout.SOUTH);
      content.setPreferredSize(JBUI.size(800, 600));
      return content;
    }

    private void addTable() {
      final AddTableDialog dialog = new AddTableDialog(getWindow());
      if (dialog.showAndGet()) {
        final String tableName = dialog.getTableName();
        final List<SchemaDsl.Column> columns = dialog.getColumns();
        if (Objects.nonNull(tableName) && !tableName.isBlank() && !columns.isEmpty()) {
          model.addElement(new UiTable(tableName, columns));
        }
      }
    }

    private void generateSql() {
      final JDialog progressDialog = new JDialog();
      progressDialog.setTitle("Generating SQL");
      progressDialog.setModal(true);
      final JProgressBar progressBar = new JProgressBar();
      progressBar.setIndeterminate(true);
      progressDialog.add(BorderLayout.CENTER, progressBar);
      progressDialog.add(BorderLayout.NORTH, new JPanel());
      progressDialog.setSize(JBUI.size(300, 75));
      progressDialog.setLocationRelativeTo(getWindow());

      final SwingWorker<String, Void> worker =
          new SwingWorker<>() {
            @Override
            protected String doInBackground() {
              final SchemaDsl.Table[] tables =
                  Collections.list(model.elements()).stream()
                      .map(
                          t ->
                              SchemaDsl.table(
                                  t.name(), t.columns().toArray(SchemaDsl.Column[]::new)))
                      .toArray(SchemaDsl.Table[]::new);
              final SchemaDsl.Schema schema = SchemaDsl.schema(tables);
              return SchemaDsl.toSql(schema);
            }

            @Override
            protected void done() {
              progressDialog.dispose();
              try {
                sqlArea.setText(get());
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                Messages.showErrorDialog("Error generating SQL: " + e.getMessage(), "Error");
              } catch (final ExecutionException e) {
                final String errorMsg =
                    Objects.nonNull(e.getCause()) ? e.getCause().getMessage() : e.getMessage();
                Messages.showErrorDialog("Error generating SQL: " + errorMsg, "Error");
              }
            }
          };

      worker.execute();
      progressDialog.setVisible(true);
    }
  }

  /**
   * Dialog for adding a new table with columns via an editable table UI, replacing the old
   * sequential JOptionPane cascade.
   */
  private static final class AddTableDialog extends DialogWrapper {

    private final JTextField tableNameField = new JTextField(20);
    private final DefaultTableModel tableModel;

    AddTableDialog(@Nullable Window parent) {
      super(parent, true);
      setTitle("Add Table");
      tableModel =
          new DefaultTableModel(new Object[] {"Column Name", "SQL Type", "Primary Key"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
              return columnIndex == 2 ? Boolean.class : Object.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
              return true;
            }
          };
      // Start with one empty row
      tableModel.addRow(new Object[] {"", SqlType.INT, Boolean.FALSE});
      init();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
      if (tableNameField.getText().isBlank()) {
        return new ValidationInfo("Table name is required.", tableNameField);
      }
      final boolean hasValidColumn =
          IntStream.range(0, tableModel.getRowCount())
              .anyMatch(
                  i -> {
                    final Object name = tableModel.getValueAt(i, 0);
                    return Objects.nonNull(name) && !name.toString().isBlank();
                  });
      return !hasValidColumn
          ? new ValidationInfo("At least one column with a name is required.")
          : null;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
      final JPanel panel = new JPanel(new BorderLayout(0, 8));

      final JPanel namePanel = new JPanel(new BorderLayout(8, 0));
      namePanel.add(new JBLabel("Table name:"), BorderLayout.WEST);
      namePanel.add(tableNameField, BorderLayout.CENTER);
      panel.add(namePanel, BorderLayout.NORTH);

      final JBTable table = new JBTable(tableModel);
      // Set up the SQL type column with a combo box editor
      final ComboBox<SqlType> typeCombo = new ComboBox<>(SqlType.values());
      table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(typeCombo));
      table.setRowHeight(JBUI.scale(24));

      final JBScrollPane scrollPane = new JBScrollPane(table);
      scrollPane.setPreferredSize(JBUI.size(450, 200));

      final JButton addRowButton = new JButton("Add Column", AllIcons.General.Add);
      addRowButton.addActionListener(
          e -> tableModel.addRow(new Object[] {"", SqlType.INT, Boolean.FALSE}));

      final JButton removeRowButton = new JButton("Remove", AllIcons.General.Remove);
      removeRowButton.addActionListener(
          e -> {
            final int selected = table.getSelectedRow();
            if (selected >= 0) {
              tableModel.removeRow(selected);
            }
          });

      final JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
      buttonsPanel.add(addRowButton);
      buttonsPanel.add(removeRowButton);

      final JPanel tableContainer = new JPanel(new BorderLayout());
      tableContainer.add(new JBLabel("Columns:"), BorderLayout.NORTH);
      tableContainer.add(scrollPane, BorderLayout.CENTER);
      tableContainer.add(buttonsPanel, BorderLayout.SOUTH);

      panel.add(tableContainer, BorderLayout.CENTER);
      return panel;
    }

    String getTableName() {
      return tableNameField.getText().trim();
    }

    List<SchemaDsl.Column> getColumns() {
      return IntStream.range(0, tableModel.getRowCount())
          .mapToObj(
              i -> {
                final Object nameObj = tableModel.getValueAt(i, 0);
                if (Objects.isNull(nameObj) || nameObj.toString().isBlank()) {
                  return null;
                }
                final String name = nameObj.toString().trim();
                final Object typeObj = tableModel.getValueAt(i, 1);
                final SqlType type = (typeObj instanceof SqlType st) ? st : SqlType.INT;
                final boolean isPk = Boolean.TRUE.equals(tableModel.getValueAt(i, 2));
                return isPk ? SchemaDsl.pk(name, type) : SchemaDsl.column(name, type);
              })
          .filter(Objects::nonNull)
          .toList();
    }
  }

  private record UiTable(String name, List<SchemaDsl.Column> columns) {
    public UiTable {
      Objects.requireNonNull(name, "Table name cannot be null.");
      Objects.requireNonNull(columns, "Column list cannot be null.");
      columns = List.copyOf(columns);
    }

    @Override
    @NotNull
    public String toString() {
      return name;
    }
  }
}
