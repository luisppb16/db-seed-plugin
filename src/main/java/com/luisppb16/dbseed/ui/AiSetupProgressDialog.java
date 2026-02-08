/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.ai.DockerService;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.concurrent.CompletableFuture;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Dialog to show the progress of AI environment setup (Docker, Ollama, Models).
 */
public class AiSetupProgressDialog extends DialogWrapper {

  private final JProgressBar progressBar = new JProgressBar(0, 100);
  private final JTextArea logArea = new JTextArea();
  private final JBLabel statusLabel = new JBLabel("Initializing setup...");
  private final DockerService dockerService = new DockerService();

  public AiSetupProgressDialog(@Nullable Project project) {
    super(project, false);
    setTitle("AI Environment Setup - db-seed");
    setModal(true);
    setOKActionEnabled(false);
    setCancelButtonText("Run in Background");
    init();
    startSetup();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel panel = new JPanel(new BorderLayout(10, 10));
    panel.setBorder(JBUI.Borders.empty(10));
    panel.setPreferredSize(new Dimension(500, 300));

    JPanel topPanel = new JPanel(new BorderLayout(5, 5));
    topPanel.add(statusLabel, BorderLayout.NORTH);
    progressBar.setIndeterminate(true);
    topPanel.add(progressBar, BorderLayout.CENTER);

    panel.add(topPanel, BorderLayout.NORTH);

    logArea.setEditable(false);
    logArea.setFont(JBUI.Fonts.label().deriveFont(11f));
    panel.add(new JBScrollPane(logArea), BorderLayout.CENTER);

    return panel;
  }

  private void startSetup() {
    CompletableFuture.runAsync(() -> {
      try {
        updateStatus("Checking Docker installation...", 5);
        if (!dockerService.isDockerInstalled()) {
          throw new RuntimeException("Docker is not installed or not in PATH. Please install Docker and restart.");
        }

        updateStatus("Pulling Ollama image...", 20);
        dockerService.pullOllamaImage(this::appendLog);

        updateStatus("Starting Ollama container...", 40);
        dockerService.startOllamaContainer(this::appendLog);

        String model = DbSeedSettingsState.getInstance().getAiModel();
        updateStatus("Pulling AI model: " + model + "...", 60);
        dockerService.pullModel(model, this::appendLog);

        updateStatus("AI Environment is ready!", 100);
        progressBar.setIndeterminate(false);
        progressBar.setValue(100);

        ApplicationManager.getApplication().invokeLater(() -> {
          setOKActionEnabled(true);
          setOKButtonText("Finish");
        });

      } catch (Exception e) {
        updateStatus("Error: " + e.getMessage(), 0);
        appendLog("FATAL ERROR: " + e.getMessage());
        progressBar.setIndeterminate(false);
      }
    }, Thread.ofVirtual().factory());
  }

  private void updateStatus(String status, int progress) {
    ApplicationManager.getApplication().invokeLater(() -> {
      statusLabel.setText(status);
      if (progress > 0) {
        progressBar.setIndeterminate(false);
        progressBar.setValue(progress);
      } else {
        progressBar.setIndeterminate(true);
      }
    });
  }

  private void appendLog(String message) {
    ApplicationManager.getApplication().invokeLater(() -> {
      logArea.append(message + "\n");
      logArea.setCaretPosition(logArea.getDocument().getLength());
    });
  }
}
