/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui.util;

import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
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

  /**
   * Version baked into a plugin resource at build time (see processResources in build.gradle).
   * Reading it avoids PluginManager lookups, which are internal API since IntelliJ 2026.2.
   */
  private static final String PLUGIN_VERSION = loadPluginVersion();

  private static String loadPluginVersion() {
    try (final InputStream is =
        ComponentUtils.class.getResourceAsStream("/dbseed/plugin-version.properties")) {
      if (is != null) {
        final Properties props = new Properties();
        props.load(is);
        final String version = props.getProperty("version", "").trim();
        // An unexpanded template token means we are running from raw sources.
        if (!version.isEmpty() && !version.contains("${")) {
          return version;
        }
      }
    } catch (final IOException ignored) {
      // Fall through to the placeholder below.
    }
    return "?";
  }

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
    final JLabel label = new JLabel("v" + PLUGIN_VERSION);
    label.setFont(label.getFont().deriveFont(10f));
    label.setForeground(UIManager.getColor("Label.disabledForeground"));

    final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    panel.setOpaque(false);
    panel.add(label);
    return panel;
  }
}
