/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.luisppb16.dbseed.ai.OllamaClient;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;

/**
 * UI component for configuring global settings of the DBSeed plugin in IntelliJ.
 */
public class DbSeedSettingsComponent {

  private final JPanel myMainPanel;
  private final JSpinner myColumnSpinnerStep = new JSpinner(new SpinnerNumberModel(1, 1, 10, 1));
  private final TextFieldWithBrowseButton myDefaultOutputDirectory =
      new TextFieldWithBrowseButton();

  private final JBCheckBox myUseLatinDictionary =
      new JBCheckBox("Use Latin dictionary (Faker default)");
  private final JBCheckBox myUseEnglishDictionary = new JBCheckBox("Use English dictionary");
  private final JBCheckBox myUseSpanishDictionary = new JBCheckBox("Use Spanish dictionary");

  private final JBTextField mySoftDeleteColumns = new JBTextField();
  private final JBCheckBox mySoftDeleteUseSchemaDefault =
      new JBCheckBox("Use schema default value");
  private final JBTextField mySoftDeleteValue = new JBTextField();

  private final JBCheckBox myUseAiGeneration = new JBCheckBox("Enable AI-based data generation");
  private final JBTextArea myAiApplicationContext = new JBTextArea(3, 20);
  private final JSpinner myAiWordCount = new JSpinner(new SpinnerNumberModel(1, 1, 500, 1));
  private final JBTextField myOllamaUrl = new JBTextField();
  private final ComboBox<String> myOllamaModelDropdown = new ComboBox<>();
  private final JButton myRefreshModelsButton = new JButton("Get models");
  private final AsyncProcessIcon myLoadingIcon = new AsyncProcessIcon("OllamaLoading");

  public DbSeedSettingsComponent() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    myColumnSpinnerStep.setValue(settings.getColumnSpinnerStep());
    myDefaultOutputDirectory.setText(settings.getDefaultOutputDirectory());
    myUseLatinDictionary.setSelected(settings.isUseLatinDictionary());
    myUseEnglishDictionary.setSelected(settings.isUseEnglishDictionary());
    myUseSpanishDictionary.setSelected(settings.isUseSpanishDictionary());

    mySoftDeleteColumns.setText(settings.getSoftDeleteColumns());
    mySoftDeleteUseSchemaDefault.setSelected(settings.isSoftDeleteUseSchemaDefault());
    mySoftDeleteValue.setText(settings.getSoftDeleteValue());
    mySoftDeleteValue.setEnabled(!settings.isSoftDeleteUseSchemaDefault());

    myUseAiGeneration.setSelected(settings.isUseAiGeneration());
    myAiApplicationContext.setText(settings.getAiApplicationContext());
    myAiWordCount.setValue(settings.getAiWordCount());
    myAiApplicationContext.setLineWrap(true);
    myAiApplicationContext.setWrapStyleWord(true);
    myOllamaUrl.setText(settings.getOllamaUrl());
    
    if (settings.getOllamaModel() != null && !settings.getOllamaModel().isEmpty()) {
        myOllamaModelDropdown.addItem(settings.getOllamaModel());
        myOllamaModelDropdown.setSelectedItem(settings.getOllamaModel());
    }

    mySoftDeleteUseSchemaDefault.addActionListener(
        e -> mySoftDeleteValue.setEnabled(!mySoftDeleteUseSchemaDefault.isSelected()));

    myRefreshModelsButton.addActionListener(e -> refreshModels());
    myLoadingIcon.setVisible(false);

    configureFolderChooser(myDefaultOutputDirectory, "Select Default Output Directory");

    JBLabel aiDescription = new JBLabel(
        "<html><div style='width:350px;'>AI generation uses an external Ollama instance to generate "
        + "context-aware data. Ensure Ollama is running and accessible at the specified URL.</div></html>");
    aiDescription.setForeground(UIUtil.getContextHelpForeground());
    aiDescription.setBorder(JBUI.Borders.emptyBottom(5));

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    buttonPanel.add(myRefreshModelsButton);
    buttonPanel.add(myLoadingIcon);

    JPanel urlPanel = new JPanel(new BorderLayout(5, 0));
    urlPanel.add(myOllamaUrl, BorderLayout.CENTER);
    urlPanel.add(buttonPanel, BorderLayout.EAST);

    JBScrollPane contextScrollPane = new JBScrollPane(myAiApplicationContext);
    contextScrollPane.setPreferredSize(new Dimension(0, 80));
    contextScrollPane.setMinimumSize(new Dimension(0, 60));

    JPanel formContent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Column spinner step:"), myColumnSpinnerStep, 1, false)
            .addLabeledComponent(
                new JBLabel("Default output directory:"), myDefaultOutputDirectory, 1, false)
            .addComponent(myUseLatinDictionary, 1)
            .addComponent(myUseEnglishDictionary, 1)
            .addComponent(myUseSpanishDictionary, 1)
            .addComponent(new TitledSeparator("Soft Delete Configuration"))
            .addLabeledComponent(
                new JBLabel("Columns (comma separated):"),
                mySoftDeleteColumns,
                1,
                false)
            .addComponent(mySoftDeleteUseSchemaDefault, 1)
            .addLabeledComponent(
                new JBLabel("Value (if not default):"), mySoftDeleteValue, 1, false)
            .addComponent(new TitledSeparator("AI Contextual Generation (External Ollama)"))
            .addComponent(aiDescription)
            .addComponent(myUseAiGeneration, 1)
            .addLabeledComponent(new JBLabel("Application context:"), contextScrollPane, 1, false)
            .addLabeledComponent(new JBLabel("Words per AI value:"), myAiWordCount, 1, false)
            .addTooltip("Number of words the AI model should generate per column value" +
                    "\n (1 = single word, higher = sentences/paragraphs).")
            .addLabeledComponent(new JBLabel("Ollama URL:"), urlPanel, 1, false)
            .addLabeledComponent(new JBLabel("Model:"), myOllamaModelDropdown, 1, false)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    JBScrollPane scrollPane = new JBScrollPane(formContent);
    scrollPane.setBorder(JBUI.Borders.empty());
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

    myMainPanel = new JPanel(new BorderLayout());
    myMainPanel.add(scrollPane, BorderLayout.CENTER);
    myMainPanel.setPreferredSize(new Dimension(480, 550));
  }

  private void refreshModels() {
    String url = myOllamaUrl.getText().trim();
    if (url.isEmpty()) {
        Messages.showErrorDialog(myMainPanel, "Please enter a valid Ollama URL.", "Invalid URL");
        return;
    }

    myRefreshModelsButton.setEnabled(false);
    myLoadingIcon.setVisible(true);
    myLoadingIcon.resume();
    myOllamaModelDropdown.setEnabled(false);

    ModalityState currentModality = ModalityState.stateForComponent(myMainPanel);

    OllamaClient client = new OllamaClient(url, "");
    client.ping().whenComplete((ignored, pingEx) -> {
        if (pingEx != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                Throwable cause = pingEx.getCause() != null ? pingEx.getCause() : pingEx;
                Messages.showErrorDialog(myMainPanel,
                    "No Ollama server found at " + url + ".\n"
                        + "Ensure Ollama is running and the URL is correct.\n\n"
                        + "Error: " + cause.getMessage(),
                    "Server Not Reachable");
                resetRefreshButton();
            }, currentModality);
            return;
        }
        client.listModels().whenComplete((models, ex) -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (ex != null) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Messages.showErrorDialog(myMainPanel,
                        "Could not fetch models from Ollama at " + url + ".\n"
                            + "Error: " + cause.getMessage(),
                        "Connection Error");
                } else {
                    String currentSelection = (String) myOllamaModelDropdown.getSelectedItem();
                    myOllamaModelDropdown.removeAllItems();
                    if (models.isEmpty()) {
                        Messages.showWarningDialog(myMainPanel,
                            "No models found in Ollama. Ensure you have pulled at least one model.",
                            "No Models Found");
                    } else {
                        for (String model : models) {
                            myOllamaModelDropdown.addItem(model);
                        }
                        if (currentSelection != null && models.contains(currentSelection)) {
                            myOllamaModelDropdown.setSelectedItem(currentSelection);
                        }
                    }
                }
                resetRefreshButton();
            }, currentModality);
        });
    });
  }

  private void resetRefreshButton() {
    myRefreshModelsButton.setEnabled(true);
    myLoadingIcon.suspend();
    myLoadingIcon.setVisible(false);
    myOllamaModelDropdown.setEnabled(true);
  }

  private void configureFolderChooser(TextFieldWithBrowseButton field, String title) {
    final FileChooserDescriptor folderDescriptor =
        FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(title);
    field.addActionListener(
        e -> {
          final String currentPath = field.getText();
          final VirtualFile currentFile =
              currentPath.isEmpty()
                  ? null
                  : LocalFileSystem.getInstance().findFileByPath(currentPath);
          FileChooser.chooseFile(
              folderDescriptor,
              null,
              currentFile,
              file -> {
                if (file != null) {
                  field.setText(file.getPath());
                }
              });
        });
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

  public boolean getUseAiGeneration() {
    return myUseAiGeneration.isSelected();
  }

  public void setUseAiGeneration(boolean use) {
    myUseAiGeneration.setSelected(use);
  }

  public String getAiApplicationContext() {
    return myAiApplicationContext.getText();
  }

  public void setAiApplicationContext(String text) {
    myAiApplicationContext.setText(text);
  }

  public int getAiWordCount() {
    return (int) myAiWordCount.getValue();
  }

  public void setAiWordCount(int value) {
    myAiWordCount.setValue(value);
  }

  public String getOllamaUrl() {
    return myOllamaUrl.getText();
  }

  public void setOllamaUrl(String url) {
    myOllamaUrl.setText(url);
  }

  public String getOllamaModel() {
    return (String) myOllamaModelDropdown.getSelectedItem();
  }

  public void setOllamaModel(String model) {
    if (model != null && !model.isEmpty()) {
        boolean exists = false;
        for (int i = 0; i < myOllamaModelDropdown.getItemCount(); i++) {
            if (model.equals(myOllamaModelDropdown.getItemAt(i))) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            myOllamaModelDropdown.addItem(model);
        }
        myOllamaModelDropdown.setSelectedItem(model);
    }
  }
}
