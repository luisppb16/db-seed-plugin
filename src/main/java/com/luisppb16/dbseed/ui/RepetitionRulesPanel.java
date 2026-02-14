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
import java.util.Objects;
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
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jetbrains.annotations.NotNull;

/**
 * Advanced configuration panel for defining data repetition patterns in the database seeding process.
 * <p>
 * This sophisticated UI component enables users to define complex repetition rules that govern
 * how data patterns are duplicated during the seeding operation. The panel provides granular
 * control over repetition counts and allows for column-specific overrides, enabling precise
 * control over data generation patterns. The interface supports hierarchical organization
 * of rules by table and column, with intuitive visual indicators and responsive interaction
 * patterns.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Managing hierarchical organization of repetition rules by table and column</li>
 *   <li>Providing intuitive interfaces for defining repetition counts and patterns</li>
 *   <li>Allowing column-specific overrides with different value assignment strategies</li>
 *   <li>Implementing dynamic UI updates for real-time rule modification</li>
 *   <li>Ensuring data integrity through validation of repetition parameters</li>
 *   <li>Providing visual feedback for complex rule hierarchies</li>
 * </ul>
 * </p>
 * <p>
 * The implementation follows modern UI design principles with card-based layouts for rule
 * organization and responsive component interactions. The panel maintains internal state
 * consistency and provides conversion mechanisms between UI representation and domain models.
 * It implements proper memory management patterns to prevent memory leaks during dynamic
 * component addition and removal.
 * </p>
 */
public class RepetitionRulesPanel extends JPanel {

  private static final String STRATEGY_CONSTANT_RANDOM = "Constant (Random)";
  private static final String STRATEGY_CONSTANT_VALUE = "Constant (Value)";

  private final Map<String, List<RuleUiModel>> rulesByTable = new HashMap<>();
  private final JPanel rightPanelContainer;
  private final CardLayout rightPanelLayout;
  private final JBList<Object> tableList;

  public RepetitionRulesPanel(final List<Table> tables) {
    setLayout(new BorderLayout());

    rightPanelLayout = new CardLayout();
    rightPanelContainer = new JPanel(rightPanelLayout);

    final DefaultListModel<Object> listModel = new DefaultListModel<>();

    tables.stream()
        .sorted((t1, t2) -> t1.name().compareToIgnoreCase(t2.name()))
        .forEach(
            table -> {
              listModel.addElement(table);
              table.columns().stream()
                  .sorted((c1, c2) -> c1.name().compareToIgnoreCase(c2.name()))
                  .forEach(column -> listModel.addElement(new ColumnItem(column)));
            });

    tableList = new JBList<>(listModel);
    tableList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    tableList.setCellRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              final JList<?> list,
              final Object value,
              final int index,
              final boolean isSelected,
              final boolean cellHasFocus) {
            final JLabel label =
                (JLabel)
                    super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);

            if (value instanceof final Table table) {
              label.setText(table.name());
              label.setFont(label.getFont().deriveFont(Font.BOLD));
              label.setIcon(AllIcons.Nodes.DataTables);
            } else if (value instanceof ColumnItem(Column column)) {
              label.setText(column.name());
              label.setBorder(JBUI.Borders.emptyLeft(20));
              label.setIcon(AllIcons.Nodes.DataColumn);
            }
            return label;
          }
        });

    tableList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) {
            final Object selectedValue = tableList.getSelectedValue();
            if (selectedValue instanceof final Table table) {
              rightPanelLayout.show(rightPanelContainer, table.name());
            } else if (selectedValue instanceof ColumnItem(Column column)) {
              final Table parentTable = findParentTable(tables, column);
              if (parentTable != null) {
                rightPanelLayout.show(rightPanelContainer, parentTable.name());
              }
            }
          }
        });

    for (final Table table : tables) {
      rulesByTable.put(table.name(), new ArrayList<>());
      rightPanelContainer.add(createTableRulesPanel(table), table.name());
    }

    final JSplitPane splitPane =
        new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, new JBScrollPane(tableList), rightPanelContainer);
    splitPane.setDividerLocation(200);
    add(splitPane, BorderLayout.CENTER);

    if (!listModel.isEmpty()) {
      tableList.setSelectedIndex(0);
    }
  }

  private Table findParentTable(final List<Table> tables, final Column column) {
    return tables.stream().filter(t -> t.columns().contains(column)).findFirst().orElse(null);
  }

  private JPanel createTableRulesPanel(final Table table) {
    final JPanel panel = new JPanel(new BorderLayout());
    final JPanel rulesContainer = new JPanel();
    rulesContainer.setLayout(new BoxLayout(rulesContainer, BoxLayout.Y_AXIS));

    final JScrollPane scrollPane = new JBScrollPane(rulesContainer);
    panel.add(scrollPane, BorderLayout.CENTER);

    final JButton addRuleButton = new JButton("Add Repetition Rule", AllIcons.General.Add);
    addRuleButton.addActionListener(
        e -> {
          final RuleUiModel ruleModel = new RuleUiModel();
          rulesByTable.get(table.name()).add(ruleModel);
          addRuleUi(rulesContainer, table, ruleModel);
          rulesContainer.revalidate();
          rulesContainer.repaint();
        });

    final JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    topPanel.add(addRuleButton);
    panel.add(topPanel, BorderLayout.NORTH);

    return panel;
  }

  private void addRuleUi(final JPanel container, final Table table, final RuleUiModel ruleModel) {
    final JPanel rulePanel =
        new JPanel(new BorderLayout()) {
          @Override
          public Dimension getMaximumSize() {
            final Dimension pref = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, pref.height);
          }
        };

    rulePanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(5, 5, 5, 5), BorderFactory.createEtchedBorder()));

    final JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    headerPanel.add(new JLabel("Repeat Count:"));
    final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 10000, 1));
    countSpinner.setValue(ruleModel.count);
    countSpinner.addChangeListener(e -> ruleModel.count = (Integer) countSpinner.getValue());
    headerPanel.add(countSpinner);

    final JButton removeRuleButton = new JButton(AllIcons.Actions.Cancel);
    removeRuleButton.setToolTipText("Remove Rule");
    removeRuleButton.addActionListener(
        e -> {
          rulesByTable.get(table.name()).remove(ruleModel);
          container.remove(rulePanel);
          container.revalidate();
          container.repaint();
        });

    final JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    headerRight.add(removeRuleButton);

    final JPanel headerContainer = new JPanel(new BorderLayout());
    headerContainer.add(headerPanel, BorderLayout.WEST);
    headerContainer.add(headerRight, BorderLayout.EAST);

    rulePanel.add(headerContainer, BorderLayout.NORTH);

    final JPanel overridesListPanel = new JPanel();
    overridesListPanel.setLayout(new BoxLayout(overridesListPanel, BoxLayout.Y_AXIS));

    final JButton addOverrideButton = new JButton("Add Column Override");
    addOverrideButton.addActionListener(
        e -> {
          addOverrideRow(overridesListPanel, table, ruleModel);
          overridesListPanel.revalidate();
          overridesListPanel.repaint();
        });

    rulePanel.add(overridesListPanel, BorderLayout.CENTER);
    rulePanel.add(addOverrideButton, BorderLayout.SOUTH);

    container.add(rulePanel);
    container.add(Box.createVerticalStrut(10));
  }

  private void addOverrideRow(
      final JPanel container, final Table table, final RuleUiModel ruleModel) {
    final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));

    final String[] colNames =
        table.columns().stream().map(Column::name).sorted().toArray(String[]::new);
    final ComboBox<String> colCombo = new ComboBox<>(colNames);

    final String[] strategies = {STRATEGY_CONSTANT_RANDOM, STRATEGY_CONSTANT_VALUE};
    final ComboBox<String> strategyCombo = new ComboBox<>(strategies);

    final JTextField valueField = new JTextField(15);
    valueField.setEnabled(false);

    strategyCombo.addActionListener(
        e ->
            valueField.setEnabled(STRATEGY_CONSTANT_VALUE.equals(strategyCombo.getSelectedItem())));

    final JButton removeBtn = new JButton(AllIcons.Actions.Cancel);

    final ColumnOverrideModel overrideModel = new ColumnOverrideModel();
    overrideModel.columnName = (String) colCombo.getSelectedItem();
    overrideModel.strategy = (String) strategyCombo.getSelectedItem();
    overrideModel.value = valueField.getText();

    ruleModel.overrides.add(overrideModel);

    colCombo.addActionListener(e -> overrideModel.columnName = (String) colCombo.getSelectedItem());
    strategyCombo.addActionListener(
        e -> overrideModel.strategy = (String) strategyCombo.getSelectedItem());
    valueField
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(final DocumentEvent e) {
                update();
              }

              public void removeUpdate(final DocumentEvent e) {
                update();
              }

              public void changedUpdate(final DocumentEvent e) {
                update();
              }

              void update() {
                overrideModel.value = valueField.getText();
              }
            });

    removeBtn.addActionListener(
        e -> {
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
    final Map<String, List<RepetitionRule>> result = new HashMap<>();

    for (final Map.Entry<String, List<RuleUiModel>> entry : rulesByTable.entrySet()) {
      final List<RepetitionRule> rules =
          entry.getValue().stream().map(this::convertToRule).filter(Objects::nonNull).toList();

      if (!rules.isEmpty()) {
        result.put(entry.getKey(), rules);
      }
    }
    return result;
  }

  private RepetitionRule convertToRule(final RuleUiModel uiModel) {
    final Map<String, Object> fixedValues = new HashMap<>();
    final Set<String> randomConstant = new HashSet<>();

    for (final ColumnOverrideModel override : uiModel.overrides) {
      if (STRATEGY_CONSTANT_VALUE.equals(override.strategy)) {
        fixedValues.put(override.columnName, override.value);
      } else if (STRATEGY_CONSTANT_RANDOM.equals(override.strategy)) {
        randomConstant.add(override.columnName);
      }
    }

    if (uiModel.count > 0) {
      return new RepetitionRule(uiModel.count, fixedValues, randomConstant);
    }
    return null;
  }

  private record ColumnItem(Column column) {
    @Override
    public @NotNull String toString() {
      return column.name();
    }
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
