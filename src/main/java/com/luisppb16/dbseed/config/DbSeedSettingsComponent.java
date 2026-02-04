/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.LocalFileSystem;
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
      new JBCheckBox("Use Latin dictionary (Faker default)");
  private final JBCheckBox myUseEnglishDictionary = new JBCheckBox("Use English dictionary");
  private final JBCheckBox myUseSpanishDictionary = new JBCheckBox("Use Spanish dictionary");

  // Soft Delete Components
  private final JBTextField mySoftDeleteColumns = new JBTextField();
  private final JBCheckBox mySoftDeleteUseSchemaDefault = new JBCheckBox("Use schema default value");
  private final JBTextField mySoftDeleteValue = new JBTextField();

  // AI Components
  private final JBCheckBox myEnableAiDialect = new JBCheckBox("Enable AI Dialect Adapter");
  private final JBCheckBox myEnableContextAwareGeneration = new JBCheckBox("Enable AI Context-Aware Generation");
  private final JBTextField myAiLocalEndpoint = new JBTextField();
  private final JBTextField myAiModelName = new JBTextField();

  public DbSeedSettingsComponent() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    myColumnSpinnerStep.setValue(settings.getColumnSpinnerStep());
    myDefaultOutputDirectory.setText(settings.getDefaultOutputDirectory());
    myUseLatinDictionary.setSelected(settings.isUseLatinDictionary());
    myUseEnglishDictionary.setSelected(settings.isUseEnglishDictionary());
    myUseSpanishDictionary.setSelected(settings.isUseSpanishDictionary());

    // Soft Delete Init
    mySoftDeleteColumns.setText(settings.getSoftDeleteColumns());
    mySoftDeleteUseSchemaDefault.setSelected(settings.isSoftDeleteUseSchemaDefault());
    mySoftDeleteValue.setText(settings.getSoftDeleteValue());
    mySoftDeleteValue.setEnabled(!settings.isSoftDeleteUseSchemaDefault());

    // AI Init
    myEnableAiDialect.setSelected(settings.isEnableAiDialect());
    myEnableContextAwareGeneration.setSelected(settings.isEnableContextAwareGeneration());
    myAiLocalEndpoint.setText(settings.getAiLocalEndpoint());
    myAiModelName.setText(settings.getAiModelName());

    mySoftDeleteUseSchemaDefault.addActionListener(e -> 
        mySoftDeleteValue.setEnabled(!mySoftDeleteUseSchemaDefault.isSelected())
    );

    final FileChooserDescriptor folderDescriptor = FileChooserDescriptorFactory
        .createSingleFolderDescriptor()
        .withTitle("Select Default Output Directory");
    myDefaultOutputDirectory.addActionListener(e -> {
      final String currentPath = myDefaultOutputDirectory.getText();
      final var currentFile = currentPath.isEmpty() ? null : LocalFileSystem.getInstance().findFileByPath(currentPath);
      FileChooser.chooseFile(folderDescriptor, null, currentFile, file -> {
        if (file != null) {
          myDefaultOutputDirectory.setText(file.getPath());
        }
      });
    });

    myMainPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Column spinner step:"), myColumnSpinnerStep, 1, false)
            .addLabeledComponent(
                new JBLabel("Default output directory:"), myDefaultOutputDirectory, 1, false)
            .addComponent(myUseLatinDictionary, 1)
            .addComponent(myUseEnglishDictionary, 1)
            .addComponent(myUseSpanishDictionary, 1)
            
            .addComponent(new TitledSeparator("Soft Delete Configuration"))
            .addLabeledComponent(new JBLabel("Soft delete columns (comma separated):"), mySoftDeleteColumns, 1, false)
            .addComponent(mySoftDeleteUseSchemaDefault, 1)
            .addLabeledComponent(new JBLabel("Soft delete value (if not default):"), mySoftDeleteValue, 1, false)
            
            .addComponent(new TitledSeparator("AI Integration (Local DeepSeek)"))
            .addComponent(myEnableAiDialect, 1)
            .addComponent(myEnableContextAwareGeneration, 1)
            .addLabeledComponent(new JBLabel("AI local endpoint:"), myAiLocalEndpoint, 1, false)
            .addLabeledComponent(new JBLabel("AI model name:"), myAiModelName, 1, false)

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

  // AI Getters/Setters
  public boolean getEnableAiDialect() {
    return myEnableAiDialect.isSelected();
  }

  public void setEnableAiDialect(boolean enable) {
    myEnableAiDialect.setSelected(enable);
  }

  public boolean getEnableContextAwareGeneration() {
    return myEnableContextAwareGeneration.isSelected();
  }

  public void setEnableContextAwareGeneration(boolean enable) {
    myEnableContextAwareGeneration.setSelected(enable);
  }

  public String getAiLocalEndpoint() {
    return myAiLocalEndpoint.getText();
  }

  public void setAiLocalEndpoint(String endpoint) {
    myAiLocalEndpoint.setText(endpoint);
  }

  public String getAiModelName() {
    return myAiModelName.getText();
  }

  public void setAiModelName(String name) {
    myAiModelName.setText(name);
  }
}
