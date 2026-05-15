/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui.util;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import lombok.experimental.UtilityClass;

/**
 * Utility suite for configuring and enhancing UI component behavior in the DBSeed plugin.
 *
 * <p>Centralizes common UI component customization patterns used throughout the plugin's interface,
 * providing reusable solutions for component configuration.
 */
@UtilityClass
public class ComponentUtils {

  private static final String PLUGIN_ID = "com.luisppb16.dbseed";

  public static void configureSpinnerArrowKeyControls(final JSpinner spinner) {
    final JComponent editor = spinner.getEditor();
    if (editor instanceof final JSpinner.DefaultEditor defaultEditor) {
      final JFormattedTextField textField = defaultEditor.getTextField();
      final InputMap inputMap = textField.getInputMap(JComponent.WHEN_FOCUSED);

      final String incrementAction = "increment";
      inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), incrementAction);
      final String decrementAction = "decrement";
      inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), decrementAction);

      final ActionMap spinnerActionMap = spinner.getActionMap();
      final ActionMap textFieldActionMap = textField.getActionMap();
      textFieldActionMap.put(incrementAction, spinnerActionMap.get(incrementAction));
      textFieldActionMap.put(decrementAction, spinnerActionMap.get(decrementAction));
    }
  }

  public static JPanel createVersionLabel() {
    final IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
    final String version = descriptor != null ? descriptor.getVersion() : "?";

    final JLabel label = new JLabel("v" + version);
    label.setFont(label.getFont().deriveFont(10f));
    label.setForeground(UIManager.getColor("Label.disabledForeground"));

    final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    panel.setOpaque(false);
    panel.add(label);
    return panel;
  }
}
