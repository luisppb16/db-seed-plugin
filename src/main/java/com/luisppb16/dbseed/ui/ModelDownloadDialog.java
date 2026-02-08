/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Dialog to show the progress of the AI model provisioning (Docker & Pull).
 */
public class ModelDownloadDialog extends DialogWrapper {

    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final JTextArea logArea;

    public ModelDownloadDialog() {
        super((Project) null, false);
        setTitle("AI Engine Provisioning");
        setModal(true);
        
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        
        statusLabel = new JLabel("Starting...");
        
        logArea = new JTextArea(8, 50);
        logArea.setEditable(false);
        logArea.setFont(JBUI.Fonts.label().deriveFont(11f));
        
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(JBUI.Borders.empty(15));

        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(statusLabel, BorderLayout.NORTH);
        topPanel.add(progressBar, BorderLayout.CENTER);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        return panel;
    }

    public void updateProgress(int percent, String status) {
        progressBar.setValue(percent);
        statusLabel.setText(status);
    }

    public void appendLog(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public void setIndeterminate(boolean indeterminate) {
        progressBar.setIndeterminate(indeterminate);
    }

    public void closeOk() {
        close(OK_EXIT_CODE);
    }

    public void closeCancel() {
        close(CANCEL_EXIT_CODE);
    }
    
    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getCancelAction()};
    }
}
