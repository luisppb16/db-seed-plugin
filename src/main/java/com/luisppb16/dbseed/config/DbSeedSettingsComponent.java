/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

public class DbSeedSettingsComponent {

  private final JPanel myMainPanel;
  private final JSpinner myColumnSpinnerStep = new JSpinner(new SpinnerNumberModel(1, 1, 10, 1));
  private final TextFieldWithBrowseButton myDefaultOutputDirectory =
      new TextFieldWithBrowseButton();

  // New checkboxes for dictionary usage
  private final JBCheckBox myUseLatinDictionary =
      new JBCheckBox("Use Latin Dictionary (Faker default)");
  private final JBCheckBox myUseEnglishDictionary = new JBCheckBox("Use English Dictionary");
  private final JBCheckBox myUseSpanishDictionary = new JBCheckBox("Use Spanish Dictionary");

  public DbSeedSettingsComponent() {
    // Initialize components with current settings to prevent mismatches
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    myColumnSpinnerStep.setValue(settings.columnSpinnerStep);
    myDefaultOutputDirectory.setText(settings.defaultOutputDirectory);
    myUseLatinDictionary.setSelected(settings.useLatinDictionary);
    myUseEnglishDictionary.setSelected(settings.useEnglishDictionary);
    myUseSpanishDictionary.setSelected(settings.useSpanishDictionary);

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
            .addComponent(myUseLatinDictionary, 1)
            .addComponent(myUseEnglishDictionary, 1)
            .addComponent(myUseSpanishDictionary, 1)
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

  // New getters and setters for dictionary checkboxes
  public boolean getUseLatinDictionary() {
    return myUseLatinDictionary.isSelected();
  }

  public void setUseLatinDictionary(boolean use) {
    myUseLatinDictionary.setSelected(use);
  }

  public boolean getUseEnglishDictionary() {
    return myUseEnglishDictionary.isSelected();
  }

  public void setUseEnglishDictionary(boolean use) {
    myUseEnglishDictionary.setSelected(use);
  }

  public boolean getUseSpanishDictionary() {
    return myUseSpanishDictionary.isSelected();
  }

  public void setUseSpanishDictionary(boolean use) {
    myUseSpanishDictionary.setSelected(use);
  }
}
