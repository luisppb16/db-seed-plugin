/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jetbrains.annotations.NotNull;

/**
 * Advanced configuration panel for defining data repetition patterns in the database seeding
 * process.
 *
 * <p>This sophisticated UI component enables users to define complex repetition rules that govern
 * how data patterns are duplicated during the seeding operation. The panel provides granular
 * control over repetition counts and allows for column-specific overrides, enabling precise control
 * over data generation patterns. The interface supports hierarchical organization of rules by table
 * and column, with intuitive visual indicators and responsive interaction patterns.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Managing hierarchical organization of repetition rules by table and column
 *   <li>Providing intuitive interfaces for defining repetition counts and patterns
 *   <li>Allowing column-specific overrides with different value assignment strategies
 *   <li>Implementing dynamic UI updates for real-time rule modification
 *   <li>Ensuring data integrity through validation of repetition parameters
 *   <li>Providing visual feedback for complex rule hierarchies
 * </ul>
 *
 * <p>The implementation follows modern UI design principles with card-based layouts for rule
 * organization and responsive component interactions. The panel maintains internal state
 * consistency and provides conversion mechanisms between UI representation and domain models. It
 * implements proper memory management patterns to prevent memory leaks during dynamic component
 * addition and removal.
 */
public class RepetitionRulesPanel extends JPanel {

  private static final String STRATEGY_CONSTANT_RANDOM = "Constant (Random)";
  private static final String STRATEGY_CONSTANT_VALUE = "Constant (Value)";
  private static final String REGEX_VALID_TITLE = "Regex Validation";
  private static final String REGEX_INVALID_TITLE = "Regex Validation Error";
  private static final int MIN_DIALOG_WIDTH_WITH_OVERRIDE = 980;

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
              rightPanelLayout.show(rightPanelContainer, parentTable.name());
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

  private static void updateRegexPresentation(
      final JButton regexButton, final String regexPattern) {
    final boolean configured = Objects.nonNull(regexPattern) && !regexPattern.isBlank();
    regexButton.setText(configured ? "Regex*" : "Regex");
    regexButton.setToolTipText(
        configured
            ? "Regex configured for this column. Click to edit or clear it."
            : "Generate values from a regex pattern for this string column.");
  }

  private static boolean isStringType(final Column column) {
    if (Objects.isNull(column)) {
      return false;
    }
    return column.isStringType();
  }

  private Table findParentTable(final List<Table> tables, final Column column) {
    return tables.stream().filter(t -> t.columns().contains(column)).findFirst().orElse(null);
  }

  private JPanel createTableRulesPanel(final Table table) {
    final JPanel panel = new JPanel(new BorderLayout());
    final JPanel rulesContainer = new JPanel();
    rulesContainer.setLayout(new BoxLayout(rulesContainer, BoxLayout.Y_AXIS));

    final JLabel emptyLabel = new JLabel("Select a column to add repetition rules");
    emptyLabel.setForeground(UIManager.getColor("Label.infoForeground"));
    emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.ITALIC));
    emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
    rulesContainer.add(emptyLabel, "EMPTY");

    final JScrollPane scrollPane = new JBScrollPane(rulesContainer);
    panel.add(scrollPane, BorderLayout.CENTER);

    final JButton addRuleButton = new JButton("Add Repetition Rule", AllIcons.General.Add);
    addRuleButton.addActionListener(
        e -> {
          // Remove the empty-state label on first rule addition
          if (rulesContainer.getComponentCount() == 1
              && rulesContainer.getComponent(0) instanceof JLabel) {
            rulesContainer.remove(0);
          }
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
            BorderFactory.createEmptyBorder(5, 5, 5, 5),
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"))));

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
          // Also remove the trailing vertical strut
          if (container.getComponentCount() > 0) {
            final Component last = container.getComponent(container.getComponentCount() - 1);
            if (last instanceof Box.Filler) {
              container.remove(last);
            }
          }
          // Restore empty-state label if no rules remain
          if (rulesByTable.get(table.name()).isEmpty()) {
            final JLabel emptyLabel = new JLabel("No repetition rules defined");
            emptyLabel.setForeground(UIManager.getColor("Label.infoForeground"));
            emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.ITALIC));
            emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
            container.add(emptyLabel);
          }
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
          ensureDialogWidthForOverride();
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
    final Map<String, Column> columnsByName = new HashMap<>();
    table.columns().forEach(col -> columnsByName.put(col.name(), col));

    final String[] colNames =
        table.columns().stream().map(Column::name).sorted().toArray(String[]::new);
    final ComboBox<String> colCombo = new ComboBox<>(colNames);

    final String[] strategies = {STRATEGY_CONSTANT_VALUE, STRATEGY_CONSTANT_RANDOM};
    final ComboBox<String> strategyCombo = new ComboBox<>(strategies);

    final JTextField valueField = new JTextField(15);
    valueField.setEnabled(false);
    final JButton regexButton = new JButton("Regex");
    regexButton.setToolTipText("Generate values from a regex pattern for this string column.");
    final AtomicBoolean syncingValueField = new AtomicBoolean(false);

    final JButton removeBtn = new JButton(AllIcons.General.Remove);

    final ColumnOverrideModel overrideModel = new ColumnOverrideModel();
    overrideModel.columnName = (String) colCombo.getSelectedItem();
    overrideModel.strategy = (String) strategyCombo.getSelectedItem();
    overrideModel.value = valueField.getText();

    ruleModel.overrides.add(overrideModel);

    final Runnable syncRowState =
        () -> {
          final boolean isConstantValueStrategy =
              STRATEGY_CONSTANT_VALUE.equals(strategyCombo.getSelectedItem());
          final String selectedColumnName = (String) colCombo.getSelectedItem();
          final Column selectedColumn = columnsByName.get(selectedColumnName);
          final boolean isStringColumn = isStringType(selectedColumn);
          final boolean hasRegex = !overrideModel.regexPattern.isBlank();

          valueField.setEnabled(isConstantValueStrategy && (!isStringColumn || !hasRegex));
          regexButton.setVisible(isStringColumn);
          regexButton.setEnabled(isConstantValueStrategy && isStringColumn);
          valueField.setToolTipText(
              hasRegex && isStringColumn ? "Generated automatically from regex pattern." : null);

          syncingValueField.set(true);
          try {
            if (hasRegex && isStringColumn) {
              overrideModel.value = "";
              final String regexPattern = overrideModel.regexPattern;
              if (!regexPattern.equals(valueField.getText())) {
                valueField.setText(regexPattern);
              }
            } else if (!Objects.equals(valueField.getText(), overrideModel.value)) {
              valueField.setText(overrideModel.value);
            }
          } finally {
            syncingValueField.set(false);
          }

          if (!isStringColumn && !overrideModel.regexPattern.isBlank()) {
            overrideModel.regexPattern = "";
            updateRegexPresentation(regexButton, overrideModel.regexPattern);
          }

          updateRegexPresentation(regexButton, overrideModel.regexPattern);
        };

    colCombo.addActionListener(
        e -> {
          overrideModel.columnName = (String) colCombo.getSelectedItem();
          syncRowState.run();
        });
    strategyCombo.addActionListener(
        e -> {
          overrideModel.strategy = (String) strategyCombo.getSelectedItem();
          syncRowState.run();
        });

    regexButton.addActionListener(
        e -> {
          final RegexPatternDialog dialog = new RegexPatternDialog(overrideModel.regexPattern);
          if (!dialog.showAndGet()) {
            return;
          }

          final String trimmedPattern = dialog.getPattern().trim();
          if (trimmedPattern.isEmpty()) {
            overrideModel.regexPattern = "";
            updateRegexPresentation(regexButton, overrideModel.regexPattern);
            syncRowState.run();
            return;
          }

          try {
            Pattern.compile(trimmedPattern);
            overrideModel.regexPattern = trimmedPattern;
            updateRegexPresentation(regexButton, overrideModel.regexPattern);
            syncRowState.run();
          } catch (final PatternSyntaxException ex) {
            Messages.showErrorDialog(
                this, "Invalid regex pattern: " + ex.getDescription(), "Regex Validation Error");
          }
        });

    updateRegexPresentation(regexButton, overrideModel.regexPattern);
    syncRowState.run();

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
                if (syncingValueField.get()) {
                  return;
                }

                final String selectedColumnName = (String) colCombo.getSelectedItem();
                final Column selectedColumn = columnsByName.get(selectedColumnName);
                final boolean isRegexDrivenValue =
                    STRATEGY_CONSTANT_VALUE.equals(strategyCombo.getSelectedItem())
                        && isStringType(selectedColumn)
                        && !overrideModel.regexPattern.isBlank();

                overrideModel.value = isRegexDrivenValue ? "" : valueField.getText();
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
    row.add(regexButton);
    row.add(removeBtn);

    container.add(row);
  }

  private void ensureDialogWidthForOverride() {
    final Window window = SwingUtilities.getWindowAncestor(this);
    if (Objects.isNull(window)) {
      return;
    }

    final Dimension currentSize = window.getSize();
    if (currentSize.width < MIN_DIALOG_WIDTH_WITH_OVERRIDE) {
      window.setSize(MIN_DIALOG_WIDTH_WITH_OVERRIDE, currentSize.height);
      window.validate();
    }
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
    final Map<String, String> regexPatterns = new HashMap<>();

    for (final ColumnOverrideModel override : uiModel.overrides) {
      switch (override.strategy) {
        case STRATEGY_CONSTANT_VALUE -> {
          if (Objects.nonNull(override.regexPattern) && !override.regexPattern.isBlank()) {
            regexPatterns.put(override.columnName, override.regexPattern);
            continue;
          }
          fixedValues.put(override.columnName, override.value);
        }
        case STRATEGY_CONSTANT_RANDOM -> randomConstant.add(override.columnName);
        default -> {}
      }
    }

    return uiModel.count > 0
        ? new RepetitionRule(uiModel.count, fixedValues, randomConstant, regexPatterns)
        : null;
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
    String regexPattern = "";
  }

  private static class RegexPatternDialog extends DialogWrapper {

    private final JTextField patternField = new JTextField(28);

    RegexPatternDialog(final String initialPattern) {
      super(true);
      setTitle("Confirm Regex Pattern");

      String pattern = Objects.nonNull(initialPattern) ? initialPattern : "";

      patternField.setText(pattern);

      init();
    }

    @Override
    protected JComponent createCenterPanel() {
      final JPanel panel = new JPanel(new GridBagLayout());
      final GridBagConstraints gbc = new GridBagConstraints();
      gbc.insets = JBUI.insets(4);
      gbc.anchor = GridBagConstraints.WEST;

      gbc.gridx = 0;
      gbc.gridy = 0;
      gbc.gridwidth = 3;
      panel.add(
          new JLabel(
              "Enter a Java regex pattern used to generate values for this column (e.g. #[0-9A-F]{6})."),
          gbc);

      gbc.gridy = 1;
      gbc.gridwidth = 1;
      gbc.weightx = 0;
      gbc.fill = GridBagConstraints.NONE;

      gbc.gridx = 0;
      panel.add(new JLabel("  Regex:"), gbc);

      gbc.gridx = 1;
      gbc.weightx = 1;
      gbc.fill = GridBagConstraints.HORIZONTAL;
      panel.add(patternField, gbc);

      final JButton validateButton = new JButton("Validate Regex");
      validateButton.addActionListener(e -> validatePattern());

      gbc.gridx = 2;
      gbc.weightx = 0;
      gbc.fill = GridBagConstraints.NONE;
      panel.add(validateButton, gbc);

      return panel;
    }

    @Override
    protected void doOKAction() {
      final String candidate = getPattern().trim();
      if (!candidate.isEmpty()) {
        try {
          Pattern.compile(candidate);
        } catch (final PatternSyntaxException ex) {
          Messages.showErrorDialog(
              getContentPane(),
              "Invalid regex pattern: " + ex.getDescription(),
              REGEX_INVALID_TITLE);
          return;
        }
      }
      super.doOKAction();
    }

    String getPattern() {
      final String pt = patternField.getText().trim();
      if (pt.isEmpty()) {
        return "";
      }
      return pt;
    }

    private void validatePattern() {
      final String candidate = getPattern().trim();
      if (candidate.isEmpty()) {
        Messages.showInfoMessage(
            getContentPane(),
            "Regex is empty (it will clear the current regex).",
            REGEX_VALID_TITLE);
        return;
      }

      try {
        Pattern.compile(candidate);
        Messages.showInfoMessage(getContentPane(), "Regex is valid.", REGEX_VALID_TITLE);
      } catch (final PatternSyntaxException ex) {
        Messages.showErrorDialog(
            getContentPane(), "Invalid regex pattern: " + ex.getDescription(), REGEX_INVALID_TITLE);
      }
    }
  }
}
