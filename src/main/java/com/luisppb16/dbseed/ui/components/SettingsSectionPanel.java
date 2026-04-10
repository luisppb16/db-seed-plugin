/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui.components;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

/**
 * A collapsible settings section panel with clean, minimalist design.
 *
 * <p>Provides a consistent UI component for organizing settings into logical sections with
 * expand/collapse functionality. Features a titled header with toggle button and smooth content
 * visibility management.
 */
public class SettingsSectionPanel extends JPanel {
  private final JPanel headerPanel;
  private final JPanel contentPanel;
  private final JButton toggleButton;
  private boolean isExpanded;

  public SettingsSectionPanel(@NotNull final String title, @NotNull final Component content) {
    super(new BorderLayout(0, 8));
    this.isExpanded = true;

    // Header with title and toggle
    this.headerPanel = createHeaderPanel(title);
    this.contentPanel = new JPanel(new BorderLayout());
    this.contentPanel.add(content, BorderLayout.CENTER);

    // Layout
    this.add(headerPanel, BorderLayout.NORTH);
    this.add(contentPanel, BorderLayout.CENTER);

    // Styling
    this.setBorder(JBUI.Borders.empty(12, 0, 8, 0));
    this.setOpaque(false);

    // Initial toggle setup
    this.toggleButton = findToggleButton();
    if (this.toggleButton != null) {
      this.toggleButton.addActionListener(e -> toggleExpanded());
    }
  }

  private JPanel createHeaderPanel(@NotNull final String title) {
    final JPanel header = new JPanel(new BorderLayout(8, 0));
    header.setOpaque(false);

    // Section title
    final JLabel titleLabel = new JLabel(title);
    titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
    titleLabel.setForeground(UIUtil.getActiveTextColor());

    // Toggle button (initially expanded)
    final JButton toggle = new JButton(AllIcons.General.ChevronDown);
    toggle.setFocusPainted(false);
    toggle.setContentAreaFilled(false);
    toggle.setBorderPainted(false);
    toggle.setCursor(new Cursor(Cursor.HAND_CURSOR));
    toggle.setToolTipText("Collapse/Expand");
    toggle.putClientProperty("button.borderless", true);

    header.add(toggle, BorderLayout.WEST);
    header.add(titleLabel, BorderLayout.CENTER);

    // Separator line
    final JPanel separator = new JPanel();
    separator.setBackground(new JBColor(new Color(0, 0, 0, 30), new Color(255, 255, 255, 30)));
    separator.setPreferredSize(JBUI.size(0, 1));

    final JPanel headerWithSeparator = new JPanel(new BorderLayout(0, 4));
    headerWithSeparator.setOpaque(false);
    headerWithSeparator.add(header, BorderLayout.NORTH);
    headerWithSeparator.add(separator, BorderLayout.SOUTH);

    return headerWithSeparator;
  }

  private JButton findToggleButton() {
    for (final Component c : headerPanel.getComponents()) {
      if (c instanceof JPanel panel) {
        for (final Component child : panel.getComponents()) {
          if (child instanceof JButton btn && btn.getIcon() == AllIcons.General.ChevronDown) {
            return btn;
          }
        }
      }
    }
    return null;
  }

  private void toggleExpanded() {
    isExpanded = !isExpanded;
    contentPanel.setVisible(isExpanded);

    if (toggleButton != null) {
      toggleButton.setIcon(
          isExpanded ? AllIcons.General.ChevronDown : AllIcons.General.ChevronRight);
    }

    revalidate();
    repaint();
  }

  public boolean isExpanded() {
    return isExpanded;
  }

  public void setExpanded(final boolean expanded) {
    if (expanded != isExpanded) {
      toggleExpanded();
    }
  }
}
