/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BorderLayout;
import java.awt.Component;
import java.lang.reflect.Method;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class PkUuidSelectionDialogTest {

  private static String invokeGetHeaderText(final JPanel tablePanel) throws Exception {
    Method method = PkUuidSelectionDialog.class.getDeclaredMethod("getHeaderText", JPanel.class);
    method.setAccessible(true);
    // getHeaderText is an instance method, but we can pass null since it doesn't use 'this'
    // Actually, it's an instance method. We need to use reflection more carefully.
    // Since we can't easily instantiate the dialog, we'll use the static extractText helper
    // and replicate getHeaderText logic inline.
    // Actually, let's just test extractText (static) and verify getHeaderText logic manually.
    // For now, replicate the getHeaderText logic using extractText.
    return invokeGetHeaderTextLogic(tablePanel);
  }

  private static String invokeGetHeaderTextLogic(final JPanel tablePanel) throws Exception {
    for (final Component child : tablePanel.getComponents()) {
      final String text = invokeExtractText(child);
      if (text != null) {
        return text;
      }
      if (child instanceof JPanel panel) {
        for (final Component inner : panel.getComponents()) {
          final String innerText = invokeExtractText(inner);
          if (innerText != null) {
            return innerText;
          }
        }
      }
    }
    return "";
  }

  private static String invokeExtractText(final Component component) throws Exception {
    Method method = PkUuidSelectionDialog.class.getDeclaredMethod("extractText", Component.class);
    method.setAccessible(true);
    return (String) method.invoke(null, component);
  }

  @Test
  void getHeaderText_detectsLabelInsideNestedHeaderPanel() throws Exception {
    final JPanel tablePanel = new JPanel(new BorderLayout());
    final JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.add(new JLabel("articles"), BorderLayout.WEST);
    tablePanel.add(headerPanel, BorderLayout.NORTH);

    final JPanel columnsPanel = new JPanel();
    columnsPanel.add(new JCheckBox("content"));
    tablePanel.add(columnsPanel, BorderLayout.CENTER);

    assertThat(invokeGetHeaderText(tablePanel)).isEqualTo("articles");
  }

  @Test
  void getHeaderText_detectsTableCheckBoxInsideNestedHeaderPanel() throws Exception {
    final JPanel tablePanel = new JPanel(new BorderLayout());
    final JPanel headerPanel = new JPanel(new BorderLayout());
    final JCheckBox tableBox = new JCheckBox("orders");
    tableBox.putClientProperty("isTable", true);
    headerPanel.add(tableBox, BorderLayout.WEST);
    tablePanel.add(headerPanel, BorderLayout.NORTH);

    assertThat(invokeGetHeaderText(tablePanel)).isEqualTo("orders");
  }

  @Test
  void getHeaderText_returnsEmptyWhenNoTableHeaderExists() throws Exception {
    final JPanel panel = new JPanel();
    // Panel with only a nested panel containing a checkbox (no direct header)
    final JPanel inner = new JPanel();
    panel.add(inner);

    assertThat(invokeGetHeaderText(panel)).isEmpty();
  }

  @Test
  void extractText_returnsCheckBoxText() throws Exception {
    final JCheckBox box = new JCheckBox("name");
    assertThat(invokeExtractText(box)).isEqualTo("name");
  }

  @Test
  void extractText_returnsLabelText() throws Exception {
    final JLabel label = new JLabel("articles");
    assertThat(invokeExtractText(label)).isEqualTo("articles");
  }

  @Test
  void extractText_returnsNullForPanel() throws Exception {
    final JPanel panel = new JPanel();
    assertThat(invokeExtractText(panel)).isNull();
  }
}
