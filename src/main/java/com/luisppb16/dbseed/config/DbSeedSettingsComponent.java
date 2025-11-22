/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

public class DbSeedSettingsComponent {

  private final JPanel myMainPanel;
  private final JSpinner myColumnSpinnerStep = new JSpinner(new SpinnerNumberModel(1, 1, 10, 1));
  private final TextFieldWithBrowseButton myDefaultOutputDirectory =
      new TextFieldWithBrowseButton();
  private final JComboBox<DbSeedSettingsState.UuidStrategy> myUuidStrategy =
      new JComboBox<>(DbSeedSettingsState.UuidStrategy.values());

  public DbSeedSettingsComponent() {
    // Initialize components with current settings to prevent mismatches
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    myColumnSpinnerStep.setValue(settings.columnSpinnerStep);

    // Add file chooser for the output directory
    FileChooserDescriptor folderDescriptor =
        FileChooserDescriptorFactory.createSingleFolderDescriptor();
    folderDescriptor.setTitle("Select Default Output Directory");
    myDefaultOutputDirectory.addBrowseFolderListener(
        "Select Directory",
        "Please select the default directory for generated SQL files.",
        null,
        folderDescriptor);

    myMainPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Column Spinner Step:"), myColumnSpinnerStep, 1, false)
            .addLabeledComponent(
                new JBLabel("Default Output Directory:"), myDefaultOutputDirectory, 1, false)
            .addLabeledComponent(new JBLabel("UUID Generation Strategy:"), myUuidStrategy, 1, false)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
  }

  public JPanel getPanel() {
    return myMainPanel;
  }

  public JComponent getPreferredFocusedComponent() {
    return myColumnSpinnerStep;
  }

  public int getColumnSpinnerStep() {
    return (int) myColumnSpinnerStep.getValue();
  }

  public void setColumnSpinnerStep(int value) {
    myColumnSpinnerStep.setValue(value);
  }

  public String getDefaultOutputDirectory() {
    return myDefaultOutputDirectory.getText();
  }

  public void setDefaultOutputDirectory(String text) {
    myDefaultOutputDirectory.setText(text);
  }

  public DbSeedSettingsState.UuidStrategy getUuidStrategy() {
    return (DbSeedSettingsState.UuidStrategy) myUuidStrategy.getSelectedItem();
  }

  public void setUuidStrategy(DbSeedSettingsState.UuidStrategy strategy) {
    myUuidStrategy.setSelectedItem(strategy);
  }
}
