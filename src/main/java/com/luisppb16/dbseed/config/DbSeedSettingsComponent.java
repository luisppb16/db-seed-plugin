/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
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

  private final JBCheckBox myUseLatinDictionary =
      new JBCheckBox("Use Latin Dictionary (Faker default)");
  private final JBCheckBox myUseEnglishDictionary = new JBCheckBox("Use English Dictionary");
  private final JBCheckBox myUseSpanishDictionary = new JBCheckBox("Use Spanish Dictionary");

  // Soft Delete Components
  private final JBTextField mySoftDeleteColumns = new JBTextField();
  private final JBCheckBox mySoftDeleteUseSchemaDefault = new JBCheckBox("Use Schema Default Value");
  private final JBTextField mySoftDeleteValue = new JBTextField();

  public DbSeedSettingsComponent() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    myColumnSpinnerStep.setValue(settings.columnSpinnerStep);
    myDefaultOutputDirectory.setText(settings.defaultOutputDirectory);
    myUseLatinDictionary.setSelected(settings.useLatinDictionary);
    myUseEnglishDictionary.setSelected(settings.useEnglishDictionary);
    myUseSpanishDictionary.setSelected(settings.useSpanishDictionary);

    // Soft Delete Init
    mySoftDeleteColumns.setText(settings.softDeleteColumns);
    mySoftDeleteUseSchemaDefault.setSelected(settings.softDeleteUseSchemaDefault);
    mySoftDeleteValue.setText(settings.softDeleteValue);
    mySoftDeleteValue.setEnabled(!settings.softDeleteUseSchemaDefault);

    mySoftDeleteUseSchemaDefault.addActionListener(e -> 
        mySoftDeleteValue.setEnabled(!mySoftDeleteUseSchemaDefault.isSelected())
    );

    FileChooserDescriptor folderDescriptor =
        FileChooserDescriptorFactory.createSingleFolderDescriptor();
    folderDescriptor.setTitle("Select Default Output Directory");
    myDefaultOutputDirectory.addBrowseFolderListener(
        new TextBrowseFolderListener(folderDescriptor));

    myMainPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Column Spinner Step:"), myColumnSpinnerStep, 1, false)
            .addLabeledComponent(
                new JBLabel("Default Output Directory:"), myDefaultOutputDirectory, 1, false)
            .addComponent(myUseLatinDictionary, 1)
            .addComponent(myUseEnglishDictionary, 1)
            .addComponent(myUseSpanishDictionary, 1)
            
            .addComponent(new TitledSeparator("Soft Delete Configuration"))
            .addLabeledComponent(new JBLabel("Soft Delete Columns (comma separated):"), mySoftDeleteColumns, 1, false)
            .addComponent(mySoftDeleteUseSchemaDefault, 1)
            .addLabeledComponent(new JBLabel("Soft Delete Value (if not default):"), mySoftDeleteValue, 1, false)
            
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

  // Soft Delete Getters/Setters
  public String getSoftDeleteColumns() {
    return mySoftDeleteColumns.getText();
  }

  public void setSoftDeleteColumns(String columns) {
    mySoftDeleteColumns.setText(columns);
  }

  public boolean getSoftDeleteUseSchemaDefault() {
    return mySoftDeleteUseSchemaDefault.isSelected();
  }

  public void setSoftDeleteUseSchemaDefault(boolean useDefault) {
    mySoftDeleteUseSchemaDefault.setSelected(useDefault);
    mySoftDeleteValue.setEnabled(!useDefault);
  }

  public String getSoftDeleteValue() {
    return mySoftDeleteValue.getText();
  }

  public void setSoftDeleteValue(String value) {
    mySoftDeleteValue.setText(value);
  }
}
