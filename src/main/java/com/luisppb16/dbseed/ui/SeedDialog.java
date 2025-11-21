/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.config.ConnectionConfigPersistence;
import com.luisppb16.dbseed.config.GenerationConfig;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import org.jetbrains.annotations.NotNull;

public final class SeedDialog extends DialogWrapper {

  private final JTextField urlField = new JTextField("jdbc:postgresql://localhost:5432/");
  private final JTextField databaseField = new JTextField("postgres");
  private final JTextField userField = new JTextField("postgres");
  private final JPasswordField passwordField = new JPasswordField();
  private final JTextField schemaField = new JTextField("public");
  private final JSpinner rowsSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 10_000, 1));
  private final JCheckBox deferredBox = new JCheckBox("Enable deferred constraints");

  public SeedDialog() {
    super(true);
    setTitle("Database Seed Generator");
    loadConfiguration();
    init();
  }

  private static void addRow(
      final JPanel panel,
      final GridBagConstraints base,
      final int rowIndex,
      final String labelText,
      final JComponent field) {

    final GridBagConstraints c = (GridBagConstraints) base.clone();

    c.gridx = 0;
    c.gridy = rowIndex;
    c.gridwidth = 1;
    c.anchor = GridBagConstraints.EAST;
    c.fill = GridBagConstraints.NONE;
    panel.add(new JLabel(labelText), c);

    c.gridx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.WEST;
    panel.add(field, c);
  }

  @Override
  protected void doOKAction() {
    try {
      rowsSpinner.commitEdit();
    } catch (ParseException e) {
      // Invalid number typed, spinner will retain last valid value.
    }
    super.doOKAction();
    saveConfiguration();
  }

  private void loadConfiguration() {
    final Project project = getCurrentProject();
    if (project != null) {
      final GenerationConfig config = ConnectionConfigPersistence.load(project);

      final String url =
          Objects.requireNonNullElse(config.url(), "jdbc:postgresql://localhost:5432/postgres");
      final int lastSlashIndex = url.lastIndexOf('/');
      if (lastSlashIndex > 0 && lastSlashIndex < url.length() - 1) {
        urlField.setText(url.substring(0, lastSlashIndex + 1));
        databaseField.setText(url.substring(lastSlashIndex + 1));
      } else {
        urlField.setText(url);
      }

      userField.setText(Objects.requireNonNullElse(config.user(), "postgres"));
      passwordField.setText(Objects.requireNonNullElse(config.password(), ""));
      schemaField.setText(Objects.requireNonNullElse(config.schema(), "public"));
      rowsSpinner.setValue(config.rowsPerTable());
      deferredBox.setSelected(config.deferred());
    }
  }

  private void saveConfiguration() {
    final Project project = getCurrentProject();
    if (project != null) {
      final GenerationConfig config = getConfiguration();
      ConnectionConfigPersistence.save(project, config);
    }
  }

  private Project getCurrentProject() {
    final Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
    return openProjects.length > 0
        ? openProjects[0]
        : ProjectManager.getInstance().getDefaultProject();
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    return createFormPanel();
  }

  private JPanel createFormPanel() {
    final JPanel panel = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = JBUI.insets(4);
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;

    int row = 0;
    addRow(panel, c, row++, "JDBC URL:", urlField);
    addRow(panel, c, row++, "User:", userField);
    addRow(panel, c, row++, "Password:", passwordField);
    addRow(panel, c, row++, "Database:", databaseField);
    addRow(panel, c, row++, "Schema:", schemaField);
    addRow(panel, c, row++, "Rows per table:", rowsSpinner);

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 2;
    c.anchor = GridBagConstraints.WEST;
    panel.add(deferredBox, c);

    return panel;
  }

  public GenerationConfig getConfiguration() {
    final String url = urlField.getText().trim();
    final String database = databaseField.getText().trim();
    return GenerationConfig.builder()
        .url(url.endsWith("/") ? url + database : url + "/" + database)
        .user(userField.getText().trim())
        .password(new String(passwordField.getPassword()))
        .schema(schemaField.getText().trim())
        .rowsPerTable((Integer) rowsSpinner.getValue())
        .deferred(deferredBox.isSelected())
        .build();
  }

  public Map<String, Map<String, String>> getSelectionByTable() {
    return Collections.emptyMap();
  }

  public Map<String, List<String>> getExcludedColumnsByTable() {
    return Collections.emptyMap();
  }
}
