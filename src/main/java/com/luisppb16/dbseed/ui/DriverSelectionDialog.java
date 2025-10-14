package com.luisppb16.dbseed.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.luisppb16.dbseed.config.DriverInfo;
import java.awt.*;
import java.util.List;
import java.util.Optional;
import javax.swing.*;
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

  public DriverSelectionDialog(@Nullable Project project, List<DriverInfo> drivers) {
    super(project);
    this.drivers = drivers;
    setTitle("Select Database Driver");

    comboBox =
        new ComboBox<>(
            new DefaultComboBoxModel<>(
                drivers.stream().map(DriverInfo::name).toArray(String[]::new)));
    comboBox.setSelectedIndex(0);

    projectIdField = new JTextField(getTitle().length());

    // DocumentListener to update the value on the fly
    projectIdField
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

    bigQueryPanel = new JPanel();
    bigQueryPanel.setLayout(new BoxLayout(bigQueryPanel, BoxLayout.Y_AXIS));
    bigQueryPanel.setBorder(new TitledBorder("Google BigQuery ProjectId"));
    bigQueryPanel.add(projectIdField);
    bigQueryPanel.setVisible(false);

    mainPanel = new JPanel();
    mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
    mainPanel.add(comboBox);
    mainPanel.add(Box.createVerticalStrut(10));
    mainPanel.add(bigQueryPanel);

    FontMetrics fm = mainPanel.getFontMetrics(UIManager.getFont("Label.font"));
    int titleWidth = fm.stringWidth(getTitle()) + 120;
    mainPanel.setPreferredSize(new Dimension(titleWidth, mainPanel.getPreferredSize().height));

    comboBox.addActionListener(e -> updateProjectIdVisibility((String) comboBox.getSelectedItem()));
    updateProjectIdVisibility((String) comboBox.getSelectedItem());

    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    return mainPanel;
  }

  public Optional<DriverInfo> getSelectedDriver() {
    if (comboBox.getSelectedIndex() < 0) {
      return Optional.empty();
    }

    DriverInfo selected = drivers.get(comboBox.getSelectedIndex());
    log.info("getSelectedDriver: {}", selected);

    if ("Google BigQuery".equalsIgnoreCase(selected.name())) {
      if (currentProjectId.isEmpty()) {
        JOptionPane.showMessageDialog(
            mainPanel,
            "Please enter a ProjectId for Google BigQuery.",
            "Google BigQuery ProjectId",
            JOptionPane.WARNING_MESSAGE);
        return Optional.empty();
      }
      String url = selected.urlTemplate().replace("%your_project_id%", currentProjectId);
      selected =
          new DriverInfo(
              selected.name(),
              selected.mavenGroupId(),
              selected.mavenArtifactId(),
              selected.version(),
              selected.driverClass(),
              url);
      log.info("Generated BigQuery URL: {}", url);
    }

    return Optional.of(selected);
  }

  private void updateProjectIdVisibility(String selected) {
    boolean isBigQuery = "Google BigQuery".equalsIgnoreCase(selected);
    bigQueryPanel.setVisible(isBigQuery);
    mainPanel.revalidate();
    mainPanel.repaint();
  }

  private void updateProjectId() {
    currentProjectId = projectIdField.getText().trim();
    log.debug("ProjectId updated: {}", currentProjectId);
  }
}
