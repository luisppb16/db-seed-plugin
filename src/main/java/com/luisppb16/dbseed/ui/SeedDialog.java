/*
 *
 *  * Copyright (c) 2025 Luis Pepe.
 *  * All rights reserved.
 *
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.config.ConnectionConfigPersistence;
import com.luisppb16.dbseed.config.GenerationConfig;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public final class SeedDialog extends DialogWrapper {

  private final JTextField urlField = new JTextField("jdbc:postgresql://localhost:5432/postgres");
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
      JPanel panel,
      GridBagConstraints base,
      int rowIndex,
      String labelText,
      JComponent field,
      int labelAnchor) {

    GridBagConstraints c = (GridBagConstraints) base.clone();

    c.gridx = 0;
    c.gridy = rowIndex;
    c.gridwidth = 1;
    c.anchor = labelAnchor;
    c.fill = GridBagConstraints.NONE;
    panel.add(new JLabel(labelText), c);

    c.gridx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.WEST;
    panel.add(field, c);
  }

  @Override
  protected void doOKAction() {
    super.doOKAction();
    saveConfiguration();
  }

  private void loadConfiguration() {
    Project project = getCurrentProject();
    if (project != null) {
      GenerationConfig config = ConnectionConfigPersistence.load(project);

      urlField.setText(config.url());
      userField.setText(config.user());
      passwordField.setText(config.password());
      schemaField.setText(config.schema());
      rowsSpinner.setValue(config.rowsPerTable());
      deferredBox.setSelected(config.deferred());
    }
  }

  private void saveConfiguration() {
    Project project = getCurrentProject();
    if (project != null) {
      GenerationConfig config = getConfiguration();
      ConnectionConfigPersistence.save(project, config);
    }
  }

  private Project getCurrentProject() {
    Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
    return openProjects.length > 0 ? openProjects[0] : null;
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.insets = JBUI.insets(4);
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;

    int row = 0;
    addRow(panel, c, row++, "JDBC URL:", urlField, GridBagConstraints.EAST);
    addRow(panel, c, row++, "User:", userField, GridBagConstraints.EAST);
    addRow(panel, c, row++, "Password:", passwordField, GridBagConstraints.EAST);
    addRow(panel, c, row++, "Schema:", schemaField, GridBagConstraints.EAST);
    addRow(panel, c, row++, "Rows per table:", rowsSpinner, GridBagConstraints.EAST);

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 2;
    c.anchor = GridBagConstraints.WEST;
    panel.add(deferredBox, c);

    return panel;
  }

  public GenerationConfig getConfiguration() {
    return GenerationConfig.builder()
        .url(urlField.getText().trim())
        .user(userField.getText().trim())
        .password(new String(passwordField.getPassword()))
        .schema(schemaField.getText().trim())
        .rowsPerTable((Integer) rowsSpinner.getValue())
        .deferred(deferredBox.isSelected())
        .build();
  }
}
