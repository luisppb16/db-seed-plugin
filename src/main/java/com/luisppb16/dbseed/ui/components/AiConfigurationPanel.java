/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui.components;

import com.intellij.icons.AllIcons;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import java.awt.event.ActionListener;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import org.jetbrains.annotations.NotNull;

/**
 * Reusable AI (Ollama) configuration panel for global and session-specific settings.
 *
 * <p>Provides a clean interface for configuring Ollama integration with fields for enable/disable,
 * URL, model selection, application context, and word count per value.
 */
public class AiConfigurationPanel extends JPanel {
  private final JBCheckBox enableAiGenerationBox;
  private final JBTextField ollamaUrlField;
  private final JBTextField ollamaModelField;
  private final JBTextField applicationContextField;
  private final JSpinner wordsPerValueSpinner;
  private final JButton getModelsButton;

  public AiConfigurationPanel() {
    super();
    this.enableAiGenerationBox = new JBCheckBox("Enable AI data generation (Ollama)");
    this.ollamaUrlField = new JBTextField();
    this.ollamaModelField = new JBTextField();
    this.applicationContextField = new JBTextField();
    this.wordsPerValueSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
    this.getModelsButton = new JButton("Get Available Models", AllIcons.Actions.Refresh);
    this.getModelsButton.setFocusPainted(false);

    initializeLayout();
  }

  private void initializeLayout() {
    final JPanel contentPanel =
        FormBuilder.createFormBuilder()
            .addComponent(enableAiGenerationBox)
            .addVerticalGap(8)
            .addLabeledComponent(new JBLabel("Ollama Server URL:"), ollamaUrlField, 1, false)
            .addLabeledComponent(new JBLabel("Model:"), ollamaModelField, 1, false)
            .addComponent(getModelsButton)
            .addVerticalGap(8)
            .addLabeledComponent(
                new JBLabel("Application Context:"), applicationContextField, 1, true)
            .addTooltip("Brief description of your application domain to guide AI generation.")
            .addVerticalGap(8)
            .addLabeledComponent(new JBLabel("Words per Value:"), wordsPerValueSpinner)
            .addTooltip("Number of words to generate for each AI value (1-100).")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    contentPanel.setBorder(JBUI.Borders.empty(8));
    add(contentPanel);
  }

  public boolean isAiGenerationEnabled() {
    return enableAiGenerationBox.isSelected();
  }

  public void setAiGenerationEnabled(final boolean enabled) {
    enableAiGenerationBox.setSelected(enabled);
  }

  public String getOllamaUrl() {
    return ollamaUrlField.getText().trim();
  }

  public void setOllamaUrl(@NotNull final String url) {
    ollamaUrlField.setText(url);
  }

  public String getOllamaModel() {
    return ollamaModelField.getText().trim();
  }

  public void setOllamaModel(@NotNull final String model) {
    ollamaModelField.setText(model);
  }

  public String getApplicationContext() {
    return applicationContextField.getText().trim();
  }

  public void setApplicationContext(@NotNull final String context) {
    applicationContextField.setText(context);
  }

  public int getWordsPerValue() {
    final Object value = wordsPerValueSpinner.getValue();
    return value instanceof Integer ? (Integer) value : 10;
  }

  public void setWordsPerValue(final int words) {
    wordsPerValueSpinner.setValue(Math.max(1, Math.min(100, words)));
  }

  public void addGetModelsButtonListener(final ActionListener listener) {
    getModelsButton.addActionListener(listener);
  }

  public void addEnableAiCheckboxListener(final ActionListener listener) {
    enableAiGenerationBox.addActionListener(listener);
  }

  public void setGetModelsButtonEnabled(final boolean enabled) {
    getModelsButton.setEnabled(enabled);
  }

  public void setAllFieldsEnabled(final boolean enabled) {
    ollamaUrlField.setEnabled(enabled);
    ollamaModelField.setEnabled(enabled);
    applicationContextField.setEnabled(enabled);
    wordsPerValueSpinner.setEnabled(enabled);
    getModelsButton.setEnabled(enabled);
  }
}
