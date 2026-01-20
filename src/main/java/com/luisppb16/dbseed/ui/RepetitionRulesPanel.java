/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class RepetitionRulesPanel extends JPanel {

  private final Map<String, List<RuleUiModel>> rulesByTable = new HashMap<>();
  private final JPanel rightPanelContainer;
  private final CardLayout rightPanelLayout;
  private final JBList<Object> tableList; // Changed to Object to support Table and Column items

  public RepetitionRulesPanel(List<Table> tables) {
    setLayout(new BorderLayout());

    rightPanelLayout = new CardLayout();
    rightPanelContainer = new JPanel(rightPanelLayout);

    DefaultListModel<Object> listModel = new DefaultListModel<>();
    
    // Populate list with Table objects and their Columns
    tables.stream()
        .sorted((t1, t2) -> t1.name().compareToIgnoreCase(t2.name()))
        .forEach(table -> {
            listModel.addElement(table); // Add Table object
            table.columns().stream()
                .sorted((c1, c2) -> c1.name().compareToIgnoreCase(c2.name()))
                .forEach(column -> listModel.addElement(new ColumnItem(column))); // Add Column wrapper
        });

    tableList = new JBList<>(listModel);
    tableList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    
    // Custom Renderer for Tree-like structure
    tableList.setCellRenderer(new DefaultListCellRenderer() {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            
            if (value instanceof Table table) {
                label.setText(table.name());
                label.setFont(label.getFont().deriveFont(Font.BOLD));
                label.setIcon(AllIcons.Nodes.DataTables);
            } else if (value instanceof ColumnItem colItem) {
                label.setText(colItem.column.name());
                label.setBorder(JBUI.Borders.emptyLeft(20)); // Indentation
                label.setIcon(AllIcons.Nodes.DataColumn);
            }
            return label;
        }
    });

    tableList.addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting()) {
        Object selectedValue = tableList.getSelectedValue();
        if (selectedValue instanceof Table table) {
            rightPanelLayout.show(rightPanelContainer, table.name());
        } else if (selectedValue instanceof ColumnItem colItem) {
             Table parentTable = findParentTable(tables, colItem.column);
             if (parentTable != null) {
                 rightPanelLayout.show(rightPanelContainer, parentTable.name());
             }
        }
      }
    });

    for (Table table : tables) {
      rulesByTable.put(table.name(), new ArrayList<>());
      rightPanelContainer.add(createTableRulesPanel(table), table.name());
    }

    // Split Pane
    JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JBScrollPane(tableList), rightPanelContainer);
    splitPane.setDividerLocation(200); // Increased slightly for indentation
    add(splitPane, BorderLayout.CENTER);

    // Select first table by default
    if (!listModel.isEmpty()) {
      tableList.setSelectedIndex(0);
    }
  }
  
  private Table findParentTable(List<Table> tables, Column column) {
      for (Table t : tables) {
          if (t.columns().contains(column)) return t;
      }
      return null;
  }

  // Wrapper for Column to distinguish in List
  private static class ColumnItem {
      final Column column;
      ColumnItem(Column column) { this.column = column; }
      @Override public String toString() { return column.name(); }
  }

  private JPanel createTableRulesPanel(Table table) {
    JPanel panel = new JPanel(new BorderLayout());
    JPanel rulesContainer = new JPanel();
    rulesContainer.setLayout(new BoxLayout(rulesContainer, BoxLayout.Y_AXIS));
    
    // Add a glue at the bottom to push components up if they don't fill the space
    // But since we are adding components dynamically, we need to handle the glue.
    // A simpler way is to put rulesContainer inside another panel with BorderLayout.NORTH
    // But we want scrolling.
    // BoxLayout usually packs tightly.
    
    JScrollPane scrollPane = new JBScrollPane(rulesContainer);
    panel.add(scrollPane, BorderLayout.CENTER);

    JButton addRuleButton = new JButton("Add Repetition Rule", AllIcons.General.Add);
    addRuleButton.addActionListener(e -> {
      RuleUiModel ruleModel = new RuleUiModel();
      rulesByTable.get(table.name()).add(ruleModel);
      addRuleUi(rulesContainer, table, ruleModel);
      rulesContainer.revalidate();
      rulesContainer.repaint();
    });

    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    topPanel.add(addRuleButton);
    panel.add(topPanel, BorderLayout.NORTH);

    return panel;
  }

  private void addRuleUi(JPanel container, Table table, RuleUiModel ruleModel) {
    // Use an anonymous subclass to override getMaximumSize for height
    JPanel rulePanel = new JPanel(new BorderLayout()) {
        @Override
        public Dimension getMaximumSize() {
            Dimension pref = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, pref.height);
        }
    };
    
    rulePanel.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createEmptyBorder(5, 5, 5, 5),
        BorderFactory.createEtchedBorder()
    ));
    // Removed fixed maximum height to allow expansion

    // Header: Count and Remove
    JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    headerPanel.add(new JLabel("Repeat Count:"));
    JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 10000, 1));
    countSpinner.setValue(ruleModel.count);
    countSpinner.addChangeListener(e -> ruleModel.count = (Integer) countSpinner.getValue());
    headerPanel.add(countSpinner);
    
    JButton removeRuleButton = new JButton(AllIcons.Actions.Cancel);
    removeRuleButton.setToolTipText("Remove Rule");
    removeRuleButton.addActionListener(e -> {
      rulesByTable.get(table.name()).remove(ruleModel);
      container.remove(rulePanel);
      // Also remove the strut that follows it?
      // It's hard to find the strut.
      // A better way is to wrap rulePanel and strut in a single container, or just repaint.
      // For simplicity, we just remove rulePanel. The strut remains but it's just 10px.
      container.revalidate();
      container.repaint();
    });
    
    JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    headerRight.add(removeRuleButton);
    
    JPanel headerContainer = new JPanel(new BorderLayout());
    headerContainer.add(headerPanel, BorderLayout.WEST);
    headerContainer.add(headerRight, BorderLayout.EAST);
    
    rulePanel.add(headerContainer, BorderLayout.NORTH);

    JPanel overridesListPanel = new JPanel();
    overridesListPanel.setLayout(new BoxLayout(overridesListPanel, BoxLayout.Y_AXIS));
    
    JButton addOverrideButton = new JButton("Add Column Override");
    addOverrideButton.addActionListener(e -> {
        addOverrideRow(overridesListPanel, table, ruleModel);
        overridesListPanel.revalidate();
        overridesListPanel.repaint();
    });

    // Removed nested JBScrollPane. Added overridesListPanel directly.
    rulePanel.add(overridesListPanel, BorderLayout.CENTER);
    rulePanel.add(addOverrideButton, BorderLayout.SOUTH);

    container.add(rulePanel);
    container.add(Box.createVerticalStrut(10));
  }

  private void addOverrideRow(JPanel container, Table table, RuleUiModel ruleModel) {
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
      
      // Column Selector
      String[] colNames = table.columns().stream().map(Column::name).sorted().toArray(String[]::new);
      ComboBox<String> colCombo = new ComboBox<>(colNames);
      
      // Strategy Selector
      String[] strategies = {"Constant (Random)", "Constant (Value)"};
      ComboBox<String> strategyCombo = new ComboBox<>(strategies);
      
      // Value Field
      JTextField valueField = new JTextField(15);
      valueField.setEnabled(false); // Disabled for Constant (Random) initially
      
      strategyCombo.addActionListener(e -> {
          boolean isValue = "Constant (Value)".equals(strategyCombo.getSelectedItem());
          valueField.setEnabled(isValue);
      });
      
      JButton removeBtn = new JButton(AllIcons.Actions.Cancel);
      
      // Model Update Logic
      ColumnOverrideModel overrideModel = new ColumnOverrideModel();
      overrideModel.columnName = (String) colCombo.getSelectedItem();
      overrideModel.strategy = (String) strategyCombo.getSelectedItem();
      overrideModel.value = valueField.getText();
      
      ruleModel.overrides.add(overrideModel);
      
      colCombo.addActionListener(e -> overrideModel.columnName = (String) colCombo.getSelectedItem());
      strategyCombo.addActionListener(e -> {
          overrideModel.strategy = (String) strategyCombo.getSelectedItem();
          // Clear value if switching to Random? Maybe not.
      });
      valueField.getDocument().addDocumentListener(new DocumentListener() {
          public void insertUpdate(DocumentEvent e) { update(); }
          public void removeUpdate(DocumentEvent e) { update(); }
          public void changedUpdate(DocumentEvent e) { update(); }
          void update() { overrideModel.value = valueField.getText(); }
      });
      
      removeBtn.addActionListener(e -> {
          ruleModel.overrides.remove(overrideModel);
          container.remove(row);
          container.revalidate();
          container.repaint();
      });
      
      row.add(colCombo);
      row.add(strategyCombo);
      row.add(valueField);
      row.add(removeBtn);
      
      container.add(row);
  }

  public Map<String, List<RepetitionRule>> getRules() {
      Map<String, List<RepetitionRule>> result = new HashMap<>();
      
      for (Map.Entry<String, List<RuleUiModel>> entry : rulesByTable.entrySet()) {
          List<RepetitionRule> rules = new ArrayList<>();
          for (RuleUiModel uiModel : entry.getValue()) {
              Map<String, String> fixedValues = new HashMap<>();
              Set<String> randomConstant = new HashSet<>();
              
              for (ColumnOverrideModel override : uiModel.overrides) {
                  if ("Constant (Value)".equals(override.strategy)) {
                      fixedValues.put(override.columnName, override.value);
                  } else if ("Constant (Random)".equals(override.strategy)) {
                      randomConstant.add(override.columnName);
                  }
              }
              
              if (uiModel.count > 0) {
                  rules.add(new RepetitionRule(uiModel.count, fixedValues, randomConstant));
              }
          }
          if (!rules.isEmpty()) {
              result.put(entry.getKey(), rules);
          }
      }
      return result;
  }

  private static class RuleUiModel {
      int count = 1;
      List<ColumnOverrideModel> overrides = new ArrayList<>();
  }
  
  private static class ColumnOverrideModel {
      String columnName;
      String strategy;
      String value;
  }
}
