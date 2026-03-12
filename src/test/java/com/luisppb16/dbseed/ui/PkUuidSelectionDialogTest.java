/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BorderLayout;
import java.lang.reflect.Method;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class PkUuidSelectionDialogTest {

  @Test
  void findTableHeaderText_detectsLabelInsideNestedHeaderPanel() throws Exception {
    JPanel tablePanel = new JPanel(new BorderLayout());
    JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.add(new JLabel("articles"), BorderLayout.WEST);
    tablePanel.add(headerPanel, BorderLayout.NORTH);

    JPanel columnsPanel = new JPanel();
    columnsPanel.add(new JCheckBox("content"));
    tablePanel.add(columnsPanel, BorderLayout.CENTER);

    assertThat(invokeFindTableHeaderText(tablePanel)).isEqualTo("articles");
  }

  @Test
  void findTableHeaderText_detectsTableCheckBoxInsideNestedHeaderPanel() throws Exception {
    JPanel tablePanel = new JPanel(new BorderLayout());
    JPanel headerPanel = new JPanel(new BorderLayout());
    JCheckBox tableBox = new JCheckBox("orders");
    tableBox.putClientProperty("isTable", true);
    headerPanel.add(tableBox, BorderLayout.WEST);
    tablePanel.add(headerPanel, BorderLayout.NORTH);

    assertThat(invokeFindTableHeaderText(tablePanel)).isEqualTo("orders");
  }

  @Test
  void findTableHeaderText_returnsNullWhenNoTableHeaderExists() throws Exception {
    JPanel panel = new JPanel();
    panel.add(new JCheckBox("name"));

    assertThat(invokeFindTableHeaderText(panel)).isNull();
  }

  private static String invokeFindTableHeaderText(java.awt.Component component) throws Exception {
    Method method =
        PkUuidSelectionDialog.class.getDeclaredMethod(
            "findTableHeaderText", java.awt.Component.class);
    method.setAccessible(true);
    return (String) method.invoke(null, component);
  }
}

