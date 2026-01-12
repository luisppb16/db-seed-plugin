/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.luisppb16.dbseed.config.DriverInfo;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.util.List;
import java.util.Optional;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

@Slf4j
public class DriverSelectionDialog extends DialogWrapper {

  private final JComboBox<String> comboBox;
  private final List<DriverInfo> drivers;
  private final JPanel mainPanel;
  private final JPanel bigQueryPanel;
  private final JTextField projectIdField;
  private String currentProjectId = "";

  public DriverSelectionDialog(
      @Nullable final Project project,
      final List<DriverInfo> drivers,
      @Nullable final String lastDriverName) {
    super(project);
    this.drivers = drivers;
    setTitle("Select Database Driver");

    comboBox = createDriverComboBox(drivers, lastDriverName);
    projectIdField = createProjectIdField();
    bigQueryPanel = createBigQueryPanel(projectIdField);
    mainPanel = createMainPanel(comboBox, bigQueryPanel);

    final FontMetrics fm = mainPanel.getFontMetrics(UIManager.getFont("Label.font"));
    final int titleWidth = fm.stringWidth(getTitle()) + 120;
    mainPanel.setPreferredSize(new Dimension(titleWidth, mainPanel.getPreferredSize().height));

    comboBox.addActionListener(e -> updateProjectIdVisibility((String) comboBox.getSelectedItem()));
    updateProjectIdVisibility((String) comboBox.getSelectedItem());

    init();
  }

  private JComboBox<String> createDriverComboBox(
      final List<DriverInfo> drivers, @Nullable final String lastDriverName) {
    final var box =
        new ComboBox<>(
            new DefaultComboBoxModel<>(
                drivers.stream().map(DriverInfo::name).toArray(String[]::new)));

    if (lastDriverName != null) {
      for (int i = 0; i < drivers.size(); i++) {
        if (drivers.get(i).name().equals(lastDriverName)) {
          box.setSelectedIndex(i);
          break;
        }
      }
    } else {
      box.setSelectedIndex(0);
    }
    return box;
  }

  private JTextField createProjectIdField() {
    final var field = new JTextField(getTitle().length());
    field
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                updateProjectId();
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                updateProjectId();
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                updateProjectId();
              }
            });
    return field;
  }

  private JPanel createBigQueryPanel(final JTextField projectIdField) {
    final var panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBorder(new TitledBorder("Google BigQuery ProjectId"));
    panel.add(projectIdField);
    panel.setVisible(false);
    return panel;
  }

  private JPanel createMainPanel(final JComboBox<String> comboBox, final JPanel bigQueryPanel) {
    final var panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.add(comboBox);
    panel.add(Box.createVerticalStrut(10));
    panel.add(bigQueryPanel);
    return panel;
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    return mainPanel;
  }

  public Optional<DriverInfo> getSelectedDriver() {
    if (comboBox.getSelectedIndex() < 0) {
      return Optional.empty();
    }

    var selected = drivers.get(comboBox.getSelectedIndex());

    return Optional.of(selected)
        .filter(driver -> "Google BigQuery".equalsIgnoreCase(driver.name()))
        .map(this::configureBigQueryDriver)
        .or(() -> Optional.of(selected));
  }

  private DriverInfo configureBigQueryDriver(final DriverInfo selectedDriver) {
    if (currentProjectId.isEmpty()) {
      JOptionPane.showMessageDialog(
          mainPanel,
          "Please enter a ProjectId for Google BigQuery.",
          "Google BigQuery ProjectId",
          JOptionPane.WARNING_MESSAGE);
      return null; // Return null to indicate configuration failure
    }
    final String url = selectedDriver.urlTemplate().replace("%your_project_id%", currentProjectId);
    log.info("Generated BigQuery URL: {}", url);
    return new DriverInfo(
        selectedDriver.name(),
        selectedDriver.mavenGroupId(),
        selectedDriver.mavenArtifactId(),
        selectedDriver.version(),
        selectedDriver.driverClass(),
        url);
  }

  private void updateProjectIdVisibility(final String selected) {
    final boolean isBigQuery = "Google BigQuery".equalsIgnoreCase(selected);
    bigQueryPanel.setVisible(isBigQuery);
    mainPanel.revalidate();
    mainPanel.repaint();
  }

  private void updateProjectId() {
    currentProjectId = projectIdField.getText().trim();
    log.debug("ProjectId updated: {}", currentProjectId);
  }
}
