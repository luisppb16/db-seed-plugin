/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.GenerationConfig;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.SelfReferenceConfig;
import com.luisppb16.dbseed.model.SelfReferenceStrategy;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.ui.util.ComponentUtils;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.sql.Types;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jetbrains.annotations.NotNull;

/**
 * Advanced configuration dialog for fine-tuning primary key UUID handling and data generation
 * exclusions.
 *
 * <p>This comprehensive UI component provides users with granular control over the data generation
 * process, specifically focusing on UUID primary key management and selective exclusion of tables
 * and columns from the seeding operation. The dialog implements a multi-tab interface that
 * facilitates complex configuration scenarios while maintaining usability across different
 * configuration domains.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Managing primary key UUID identification and configuration across multiple tables
 *   <li>Providing intuitive interfaces for excluding specific tables or columns from generation
 *   <li>Offering advanced configuration options for soft-delete columns and numeric precision
 *   <li>Implementing sophisticated cross-tab synchronization to prevent conflicting selections
 *   <li>Providing real-time filtering and bulk selection capabilities for enhanced UX
 *   <li>Integrating repetition rules configuration for complex data relationships
 * </ul>
 *
 * <p>The implementation follows IntelliJ's UI guidelines and leverages the platform's component
 * toolkit to ensure consistency with the IDE's visual design language. The dialog maintains state
 * synchronization between related configuration elements and provides immediate visual feedback for
 * user actions. Advanced features include search functionality, bulk operations, and real-time
 * validation to enhance the user experience.
 */
public final class PkUuidSelectionDialog extends DialogWrapper {

  public static final int BACK_EXIT_CODE = NEXT_USER_EXIT_CODE + 1;
  private static final String TREAT_AS_UUID_PREFIX = "Treat as UUID: ";
  private static final String IS_TABLE_PROPERTY = "isTable";
  private static final int DEFAULT_GLOBAL_HIERARCHY_DEPTH = 2;
  private final List<Table> tables;
  private final GenerationConfig initialConfig;
  private final Map<String, Set<String>> selectionByTable = new LinkedHashMap<>();
  private final Map<String, Set<String>> excludedColumnsByTable = new LinkedHashMap<>();
  private final Set<String> excludedTables = new LinkedHashSet<>();
  private final Map<String, Map<String, String>> uuidValuesByTable = new LinkedHashMap<>();
  private final RepetitionRulesPanel repetitionRulesPanel;
  private final JBTextField softDeleteColumnsField = new JBTextField();
  private final JBCheckBox softDeleteUseSchemaDefaultBox =
      new JBCheckBox("Use schema default value");
  private final JBTextField softDeleteValueField = new JBTextField();
  private final JSpinner scaleSpinner;
  private final Map<String, Set<String>> aiColumnsByTable = new LinkedHashMap<>();
  private final Map<String, Map<String, JCheckBox>> aiCheckBoxes = new LinkedHashMap<>();
  private final Map<String, Map<String, JCheckBox>> pkCheckBoxes = new LinkedHashMap<>();
  private final Map<String, Map<String, JCheckBox>> excludeCheckBoxes = new LinkedHashMap<>();
  // Self-reference strategy configuration
  private final Map<String, SelfReferenceConfig> selfReferenceConfigs = new LinkedHashMap<>();
  private final Map<String, ComboBox<SelfReferenceStrategy>> selfRefStrategyBoxes =
      new LinkedHashMap<>();
  private final Map<String, JSpinner> selfRefDepthSpinners = new LinkedHashMap<>();

  public PkUuidSelectionDialog(
      @NotNull final List<Table> tables, @NotNull final GenerationConfig initialConfig) {
    super(true);
    this.tables = Objects.requireNonNull(tables, "Table list cannot be null.");
    this.initialConfig = Objects.requireNonNull(initialConfig, "Initial config cannot be null.");
    this.repetitionRulesPanel = new RepetitionRulesPanel(tables);

    final int initialScale = initialConfig.numericScale() >= 0 ? initialConfig.numericScale() : 2;
    this.scaleSpinner = new JSpinner(new SpinnerNumberModel(initialScale, 0, 10, 1));
    ComponentUtils.configureSpinnerArrowKeyControls(this.scaleSpinner);

    setTitle("Data Configuration - Step 3/3");
    initDefaults();
    setOKButtonText("Generate");
    init();
  }

  private static boolean isStringType(Column column) {
    final int jdbcType = column.jdbcType();
    final boolean isBasicStringType =
        jdbcType == Types.VARCHAR
            || jdbcType == Types.CHAR
            || jdbcType == Types.LONGVARCHAR
            || jdbcType == Types.CLOB
            || jdbcType == Types.ARRAY;

    final boolean isArrayType =
        Objects.nonNull(column.typeName())
            && column.typeName().toLowerCase(Locale.ROOT).endsWith("[]");

    return isBasicStringType || isArrayType;
  }

  private static boolean isDefaultAiCandidate(String columnName) {
    String name = columnName.toLowerCase(Locale.ROOT);
    return name.contains("description")
        || name.contains("bio")
        || name.contains("comment")
        || name.equals("product_name")
        || name.contains("title")
        || name.contains("summary")
        || name.contains("notes")
        || name.contains("content")
        || name.equals("full_name")
        || name.equals("role_name")
        || name.equals("username")
        || name.equals("email")
        || name.equals("status")
        || name.equals("carrier")
        || name.equals("method")
        || name.equals("country");
  }

  private static String extractText(Component component) {
    if (component instanceof final JCheckBox box) {
      return box.getText();
    }
    if (component instanceof final JLabel label) {
      return label.getText();
    }
    return null;
  }

  private void initDefaults() {
    tables.forEach(
        table -> {
          final Set<String> defaults =
              table.primaryKey().stream()
                  .filter(
                      pkCol -> {
                        final Column col = table.column(pkCol);
                        final String lower = pkCol.toLowerCase(Locale.ROOT);
                        return (Objects.nonNull(col) && col.uuid())
                            || lower.contains("uuid")
                            || lower.contains("guid");
                      })
                  .collect(Collectors.toCollection(LinkedHashSet::new));
          selectionByTable.put(table.name(), defaults);

          final Map<String, String> uuidValues = new LinkedHashMap<>();
          defaults.forEach(pkCol -> uuidValues.put(pkCol, UUID.randomUUID().toString()));
          uuidValuesByTable.put(table.name(), uuidValues);
        });

    String cols = initialConfig.softDeleteColumns();
    if (Objects.isNull(cols)) {
      cols = DbSeedSettingsState.getInstance().getSoftDeleteColumns();
    }
    softDeleteColumnsField.setText(cols);

    softDeleteUseSchemaDefaultBox.setSelected(initialConfig.softDeleteUseSchemaDefault());

    String val = initialConfig.softDeleteValue();
    if (Objects.isNull(val)) {
      val = DbSeedSettingsState.getInstance().getSoftDeleteValue();
    }
    softDeleteValueField.setText(val);

    softDeleteValueField.setEnabled(!softDeleteUseSchemaDefaultBox.isSelected());
    softDeleteUseSchemaDefaultBox.addActionListener(
        e -> softDeleteValueField.setEnabled(!softDeleteUseSchemaDefaultBox.isSelected()));
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {new BackAction(), getOKAction(), getCancelAction()};
  }

  @Override
  protected void doOKAction() {
    try {
      scaleSpinner.commitEdit();
    } catch (final ParseException ignored) {
      // Invalid number typed, spinner will retain last valid value.
    }

    // Commit depth spinners and sync selfReferenceConfigs from current UI state
    selfRefDepthSpinners.forEach(
        (tableName, spinner) -> {
          try {
            spinner.commitEdit();
          } catch (final ParseException ignored) {
            // retain last valid
          }
          final ComboBox<SelfReferenceStrategy> box = selfRefStrategyBoxes.get(tableName);
          if (box != null) {
            updateSelfRefConfig(
                tableName,
                (SelfReferenceStrategy) box.getSelectedItem(),
                (Integer) spinner.getValue());
          }
        });

    // Validate self-ref configs and warn the user when necessary
    final List<String> warnings = buildSelfRefWarnings();
    if (!warnings.isEmpty()) {
      final int choice =
          Messages.showOkCancelDialog(
              "Self-reference strategy configuration issues:\n\n"
                  + String.join("\n", warnings)
                  + "\n\nContinue anyway?",
              "Self-Reference Configuration Warning",
              "Continue",
              "Cancel",
              Messages.getWarningIcon());
      if (choice != Messages.OK) {
        return;
      }
    }

    super.doOKAction();
  }

  private List<String> buildSelfRefWarnings() {
    final List<String> warnings = new ArrayList<>();
    final int rowsPerTable = initialConfig.rowsPerTable();
    selfReferenceConfigs.forEach(
        (tableName, config) -> {
          if (config.strategy() == SelfReferenceStrategy.CIRCULAR && rowsPerTable < 2) {
            warnings.add(
                "• Table '"
                    + tableName
                    + "': CIRCULAR requires ≥ 2 rows per table (currently "
                    + rowsPerTable
                    + "). Generation will fail.");
          }
          if (config.strategy() == SelfReferenceStrategy.HIERARCHY
              && config.hierarchyDepth() > rowsPerTable) {
            warnings.add(
                "• Table '"
                    + tableName
                    + "': HIERARCHY depth="
                    + config.hierarchyDepth()
                    + " exceeds rows per table ("
                    + rowsPerTable
                    + "). Extra levels will be collapsed automatically.");
          }
        });
    return warnings;
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    synchronizeInitialStates();

    // Create modern tabbed interface
    final JBTabbedPane tabbedPane = new JBTabbedPane();

    // Tab 1: Primary Keys & UUIDs
    tabbedPane.addTab(
        "Primary Keys",
        AllIcons.Nodes.DataTables,
        wrapInScrollPane(createPkSelectionPanel()),
        "Configure UUID primary keys");

    // Tab 2: AI Columns (only if AI is enabled)
    if (DbSeedSettingsState.getInstance().isUseAiGeneration()) {
      tabbedPane.addTab(
          "🤖 AI Columns",
          AllIcons.General.ProjectConfigurable,
          wrapInScrollPane(createAiColumnSelectionPanel()),
          "Select columns for AI-generated content");
    }

    // Tab 3: Exclusions
    tabbedPane.addTab(
        "Exclusions",
        AllIcons.General.Remove,
        wrapInScrollPane(createColumnExclusionPanel()),
        "Exclude tables and columns from generation");

    // Tab 4: Repetition Rules
    tabbedPane.addTab(
        "Repetition Rules",
        AllIcons.Actions.Refresh,
        wrapInScrollPane(repetitionRulesPanel),
        "Configure data repetition rules");

    // Tab 5: Advanced Settings
    tabbedPane.addTab(
        "Advanced",
        AllIcons.General.Settings,
        wrapInScrollPane(createMoreSettingsPanel()),
        "Additional configuration options");

    // Apply modern styling
    tabbedPane.setBorder(JBUI.Borders.empty());

    final JPanel content = new JPanel(new BorderLayout());
    content.add(tabbedPane, BorderLayout.CENTER);
    content.setPreferredSize(JBUI.size(750, 600));
    content.setMinimumSize(JBUI.size(600, 450));
    return content;
  }

  /** Wraps a component in a scroll pane with consistent styling. */
  private JBScrollPane wrapInScrollPane(JComponent component) {
    final JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.setOpaque(false);
    wrapper.setBorder(JBUI.Borders.empty(12));
    wrapper.add(component, BorderLayout.CENTER);

    final JBScrollPane scrollPane = new JBScrollPane(wrapper);
    scrollPane.setBorder(JBUI.Borders.empty());
    scrollPane.setHorizontalScrollBarPolicy(
        javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    return scrollPane;
  }

  private JComponent createAdvancedSectionDivider() {
    final JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.setOpaque(false);
    wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
    wrapper.setBorder(JBUI.Borders.empty(10, 0));

    final JSeparator separator = new JSeparator();
    separator.setForeground(UIManager.getColor("Component.borderColor"));
    separator.setAlignmentX(Component.LEFT_ALIGNMENT);
    wrapper.add(separator, BorderLayout.CENTER);
    return wrapper;
  }

  private JPanel createMoreSettingsPanel() {
    final DbSeedSettingsState global = DbSeedSettingsState.getInstance();

    // === Soft Delete Section ===
    final JPanel softDeleteSection = new JPanel();
    softDeleteSection.setLayout(
        new javax.swing.BoxLayout(softDeleteSection, javax.swing.BoxLayout.Y_AXIS));
    softDeleteSection.setBorder(JBUI.Borders.emptyBottom(20));

    // Section header
    final JBLabel softDeleteTitle = new JBLabel("Soft Delete Configuration");
    softDeleteTitle.setFont(JBUI.Fonts.label().deriveFont(Font.BOLD, 13f));
    softDeleteSection.add(softDeleteTitle);
    softDeleteSection.add(Box.createVerticalStrut(12));

    // Columns field
    final JPanel columnsPanel = new JPanel(new BorderLayout(8, 0));
    columnsPanel.setOpaque(false);
    final JBLabel columnsLabel = new JBLabel("Columns:");
    columnsLabel.setPreferredSize(JBUI.size(140, -1));
    columnsPanel.add(columnsLabel, BorderLayout.WEST);
    columnsPanel.add(softDeleteColumnsField, BorderLayout.CENTER);
    softDeleteSection.add(columnsPanel);
    softDeleteSection.add(Box.createVerticalStrut(8));

    // Schema default checkbox
    softDeleteSection.add(softDeleteUseSchemaDefaultBox);
    softDeleteSection.add(Box.createVerticalStrut(8));

    // Value field
    final JPanel valuePanel = new JPanel(new BorderLayout(8, 0));
    valuePanel.setOpaque(false);
    final JBLabel valueLabel = new JBLabel("Value:");
    valueLabel.setPreferredSize(JBUI.size(140, -1));
    valuePanel.add(valueLabel, BorderLayout.WEST);
    valuePanel.add(softDeleteValueField, BorderLayout.CENTER);
    softDeleteSection.add(valuePanel);
    softDeleteSection.add(Box.createVerticalStrut(12));

    // Status indicator with restore button
    final JPanel statusPanel = new JPanel(new BorderLayout());
    statusPanel.setOpaque(false);
    final JBLabel statusLabel = new JBLabel("Using global settings");
    statusLabel.setFont(JBUI.Fonts.smallFont());
    statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
    final JButton resetButton = new JButton("Restore Defaults");
    resetButton.putClientProperty("JButton.buttonType", "borderless");
    statusPanel.add(statusLabel, BorderLayout.WEST);
    statusPanel.add(resetButton, BorderLayout.EAST);
    softDeleteSection.add(statusPanel);

    // === Numeric Configuration Section ===
    final JPanel numericSection = new JPanel();
    numericSection.setLayout(
        new javax.swing.BoxLayout(numericSection, javax.swing.BoxLayout.Y_AXIS));
    numericSection.setBorder(JBUI.Borders.emptyTop(20));

    // Section header
    final JBLabel numericTitle = new JBLabel("Numeric Configuration");
    numericTitle.setFont(JBUI.Fonts.label().deriveFont(Font.BOLD, 13f));
    numericSection.add(numericTitle);
    numericSection.add(Box.createVerticalStrut(12));

    // Scale spinner
    final JPanel scalePanel = new JPanel(new BorderLayout(8, 0));
    scalePanel.setOpaque(false);
    final JBLabel scaleLabel = new JBLabel("Decimal scale:");
    scaleLabel.setPreferredSize(JBUI.size(140, -1));
    scalePanel.add(scaleLabel, BorderLayout.WEST);
    final JPanel spinnerWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    spinnerWrapper.setOpaque(false);
    spinnerWrapper.add(scaleSpinner);
    scalePanel.add(spinnerWrapper, BorderLayout.CENTER);
    numericSection.add(scalePanel);
    numericSection.add(Box.createVerticalStrut(6));

    // Tooltip
    final JBLabel scaleTooltip =
        new JBLabel("Applied to DECIMAL/NUMERIC columns without explicit scale");
    scaleTooltip.setFont(JBUI.Fonts.smallFont());
    scaleTooltip.setForeground(UIManager.getColor("Label.disabledForeground"));
    numericSection.add(scaleTooltip);

    // === Main panel ===
    final JPanel mainPanel = new JPanel(new BorderLayout());
    mainPanel.setBorder(JBUI.Borders.empty());

    final JPanel sectionsWrapper = new JPanel();
    sectionsWrapper.setLayout(
        new javax.swing.BoxLayout(sectionsWrapper, javax.swing.BoxLayout.Y_AXIS));
    sectionsWrapper.setOpaque(false);
    sectionsWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
    sectionsWrapper.add(softDeleteSection);
    sectionsWrapper.add(createAdvancedSectionDivider());
    sectionsWrapper.add(numericSection);
    sectionsWrapper.add(createAdvancedSectionDivider());
    final JBLabel selfRefDescription =
        new JBLabel("Define how to generate self-references and cycles per table/column.");
    selfRefDescription.setFont(JBUI.Fonts.smallFont());
    selfRefDescription.setForeground(UIManager.getColor("Label.disabledForeground"));
    selfRefDescription.setAlignmentX(Component.LEFT_ALIGNMENT);
    sectionsWrapper.add(Box.createVerticalStrut(8));
    sectionsWrapper.add(selfRefDescription);
    sectionsWrapper.add(Box.createVerticalStrut(6));
    sectionsWrapper.add(createSelfRefSection());
    sectionsWrapper.add(createAdvancedSectionDivider());
    sectionsWrapper.add(Box.createVerticalGlue());

    mainPanel.add(sectionsWrapper, BorderLayout.NORTH);

    // === Event handlers ===
    final Runnable updateStatus =
        () -> {
          final boolean modified =
              !softDeleteColumnsField.getText().equals(global.getSoftDeleteColumns())
                  || softDeleteUseSchemaDefaultBox.isSelected()
                      != global.isSoftDeleteUseSchemaDefault()
                  || !softDeleteValueField.getText().equals(global.getSoftDeleteValue());
          statusLabel.setText(modified ? "Modified from global settings" : "Using global settings");
          statusLabel.setIcon(modified ? AllIcons.General.Modified : null);
          statusLabel.setForeground(
              modified
                  ? UIManager.getColor("Label.infoForeground")
                  : UIManager.getColor("Label.disabledForeground"));
        };

    final DocumentListener listener =
        new DocumentAdapter() {
          @Override
          protected void textChanged(@NotNull final DocumentEvent e) {
            updateStatus.run();
          }
        };

    softDeleteColumnsField.getDocument().addDocumentListener(listener);
    softDeleteValueField.getDocument().addDocumentListener(listener);
    softDeleteUseSchemaDefaultBox.addActionListener(e -> updateStatus.run());

    resetButton.addActionListener(
        e -> {
          softDeleteColumnsField.setText(global.getSoftDeleteColumns());
          softDeleteUseSchemaDefaultBox.setSelected(global.isSoftDeleteUseSchemaDefault());
          softDeleteValueField.setText(global.getSoftDeleteValue());
          softDeleteValueField.setEnabled(!global.isSoftDeleteUseSchemaDefault());
          updateStatus.run();
        });

    updateStatus.run();

    return mainPanel;
  }

  private void synchronizeInitialStates() {
    syncCheckBoxState(pkCheckBoxes, excludeCheckBoxes);
    syncCheckBoxState(excludeCheckBoxes, pkCheckBoxes);
    syncCheckBoxState(excludeCheckBoxes, aiCheckBoxes);
  }

  private void syncCheckBoxState(
      Map<String, Map<String, JCheckBox>> sourceMap,
      Map<String, Map<String, JCheckBox>> targetMap) {
    sourceMap.forEach(
        (tableName, cols) ->
            cols.forEach(
                (colName, sourceBox) -> {
                  if (sourceBox.isSelected()) {
                    final JCheckBox targetBox =
                        targetMap.getOrDefault(tableName, Collections.emptyMap()).get(colName);
                    if (Objects.nonNull(targetBox)) {
                      targetBox.setEnabled(false);
                    }
                  }
                }));
  }

  private JPanel createConfiguredListPanel() {
    final JPanel listPanel = new JPanel(new GridBagLayout());
    listPanel.setBorder(JBUI.Borders.empty(8));
    return listPanel;
  }

  private GridBagConstraints createDefaultGridBagConstraints() {
    final GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.NORTHWEST;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = JBUI.insets(4);
    c.weightx = 1.0;
    return c;
  }

  private JComponent createPkSelectionPanel() {
    final JPanel listPanel = createConfiguredListPanel();
    final GridBagConstraints c = createDefaultGridBagConstraints();
    final List<JCheckBox> checkBoxes = new ArrayList<>();

    tables.forEach(
        table -> {
          final JLabel tblLabel = new JLabel(table.name());
          tblLabel.setFont(tblLabel.getFont().deriveFont(Font.BOLD));
          tblLabel.setBorder(JBUI.Borders.emptyBottom(4));

          final List<JCheckBox> tableBoxes = new ArrayList<>();
          table
              .primaryKey()
              .forEach(
                  pkCol -> {
                    final JCheckBox box = new JCheckBox(TREAT_AS_UUID_PREFIX + pkCol);
                    box.setSelected(
                        selectionByTable.getOrDefault(table.name(), Set.of()).contains(pkCol));
                    pkCheckBoxes
                        .computeIfAbsent(table.name(), k -> new LinkedHashMap<>())
                        .put(pkCol, box);
                    box.addActionListener(
                        e -> onPkBoxChanged(table.name(), pkCol, box.isSelected()));
                    tableBoxes.add(box);
                    checkBoxes.add(box);
                  });

          listPanel.add(createTablePanel(tblLabel, tableBoxes), c);
          c.gridy++;
        });

    return createTogglableListPanel(listPanel, checkBoxes, this::filterPanelComponents);
  }

  private void onPkBoxChanged(String tableName, String pkCol, boolean isSelected) {
    updateSelectionAndSync(
        tableName, pkCol, isSelected, selectionByTable, excludeCheckBoxes, excludedColumnsByTable);
  }

  private JComponent createColumnExclusionPanel() {
    final JPanel listPanel = createConfiguredListPanel();
    final GridBagConstraints c = createDefaultGridBagConstraints();
    final List<JCheckBox> checkBoxes = new ArrayList<>();

    tables.forEach(
        table -> {
          final JCheckBox tblBox = new JCheckBox(table.name());
          tblBox.putClientProperty(IS_TABLE_PROPERTY, true);
          tblBox.setFont(tblBox.getFont().deriveFont(Font.BOLD));
          tblBox.setSelected(excludedTables.contains(table.name()));

          final List<JCheckBox> currentTableColumnBoxes = new ArrayList<>();
          table
              .columns()
              .forEach(
                  column -> {
                    final JCheckBox box = new JCheckBox(column.name());
                    box.setSelected(
                        excludedColumnsByTable
                            .getOrDefault(table.name(), Set.of())
                            .contains(column.name()));
                    excludeCheckBoxes
                        .computeIfAbsent(table.name(), k -> new LinkedHashMap<>())
                        .put(column.name(), box);
                    box.addActionListener(
                        e -> onExcludeBoxChanged(table.name(), column.name(), box.isSelected()));
                    currentTableColumnBoxes.add(box);
                    checkBoxes.add(box);
                  });

          tblBox.addActionListener(
              e ->
                  onTableExcludeBoxChanged(
                      table.name(), tblBox.isSelected(), currentTableColumnBoxes));
          checkBoxes.add(tblBox);

          listPanel.add(createTablePanel(tblBox, currentTableColumnBoxes), c);
          c.gridy++;
        });

    return createTogglableListPanel(listPanel, checkBoxes, this::filterPanelComponents);
  }

  private void onExcludeBoxChanged(String tableName, String columnName, boolean isSelected) {
    updateSelectionAndSync(
        tableName, columnName, isSelected, excludedColumnsByTable, pkCheckBoxes, selectionByTable);

    final JCheckBox aiBox =
        aiCheckBoxes.getOrDefault(tableName, Collections.emptyMap()).get(columnName);
    if (Objects.nonNull(aiBox)) {
      aiBox.setEnabled(!isSelected);
      if (isSelected) {
        aiBox.setSelected(false);
        Set<String> aiCols = aiColumnsByTable.get(tableName);
        if (Objects.nonNull(aiCols)) {
          aiCols.remove(columnName);
        }
      }
    }
  }

  private void updateSelectionAndSync(
      String tableName,
      String colName,
      boolean isSelected,
      Map<String, Set<String>> currentSelectionMap,
      Map<String, Map<String, JCheckBox>> otherCheckBoxesMap,
      Map<String, Set<String>> otherSelectionMap) {

    currentSelectionMap.computeIfAbsent(tableName, k -> new LinkedHashSet<>());
    if (isSelected) {
      currentSelectionMap.get(tableName).add(colName);
    } else {
      currentSelectionMap.get(tableName).remove(colName);
    }

    final JCheckBox otherBox =
        otherCheckBoxesMap.getOrDefault(tableName, Collections.emptyMap()).get(colName);
    if (Objects.nonNull(otherBox)) {
      otherBox.setEnabled(!isSelected);
      if (isSelected) {
        otherBox.setSelected(false);
        Set<String> otherCols = otherSelectionMap.get(tableName);
        if (Objects.nonNull(otherCols)) {
          otherCols.remove(colName);
        }
      }
    }
  }

  private void onTableExcludeBoxChanged(
      String tableName, boolean isSelected, List<JCheckBox> columnBoxes) {
    if (isSelected) {
      excludedTables.add(tableName);
    } else {
      excludedTables.remove(tableName);
    }

    for (final JCheckBox colBox : columnBoxes) {
      colBox.setSelected(isSelected);
      onExcludeBoxChanged(tableName, colBox.getText(), isSelected);
    }
  }

  private JPanel createTablePanel(JComponent header, List<JCheckBox> columnBoxes) {
    final JPanel tablePanel = new JPanel(new BorderLayout(0, 8));
    tablePanel.setBorder(
        BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(UIManager.getColor("Component.borderColor"), 1, 0, 0, 0),
            JBUI.Borders.empty(12, 8)));
    tablePanel.setOpaque(false);

    // Header panel with modern styling
    final JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.setOpaque(false);
    headerPanel.add(header, BorderLayout.WEST);
    tablePanel.add(headerPanel, BorderLayout.NORTH);

    // Columns panel with better spacing
    final JPanel columnsPanel = new JPanel(new GridBagLayout());
    columnsPanel.setOpaque(false);
    final GridBagConstraints cc = new GridBagConstraints();
    cc.gridx = 0;
    cc.gridy = 0;
    cc.anchor = GridBagConstraints.NORTHWEST;
    cc.fill = GridBagConstraints.HORIZONTAL;
    cc.weightx = 1.0;
    cc.insets = JBUI.insets(2, 0);

    for (JCheckBox box : columnBoxes) {
      columnsPanel.add(box, cc);
      cc.gridy++;
    }

    columnsPanel.setBorder(JBUI.Borders.emptyLeft(20));
    tablePanel.add(columnsPanel, BorderLayout.CENTER);
    return tablePanel;
  }

  private JComponent createTogglableListPanel(
      final JPanel listPanel,
      final List<JCheckBox> checkBoxes,
      final BiConsumer<JPanel, String> filterLogic) {
    final JButton toggleButton = new JButton();
    final AtomicBoolean isBulkUpdating = new AtomicBoolean(false);

    final Runnable updateButtonState =
        () -> {
          final boolean allSelected = checkBoxes.stream().allMatch(AbstractButton::isSelected);
          toggleButton.setText(allSelected ? "Deselect All" : "Select All");
        };

    checkBoxes.forEach(
        box ->
            box.addActionListener(
                e -> {
                  if (!isBulkUpdating.get()) {
                    updateButtonState.run();
                  }
                }));
    updateButtonState.run();

    toggleButton.addActionListener(
        e -> {
          try {
            isBulkUpdating.set(true);
            final boolean selectAll = "Select All".equals(toggleButton.getText());
            checkBoxes.forEach(
                box -> {
                  if (box.isEnabled() && box.isSelected() != selectAll) {
                    box.doClick(0);
                  }
                });
          } finally {
            isBulkUpdating.set(false);
            updateButtonState.run();
          }
        });

    final JPanel topPanel = new JPanel(new BorderLayout(12, 0));
    topPanel.setBorder(JBUI.Borders.emptyBottom(12));
    topPanel.setOpaque(false);

    // Bulk action buttons on the left
    final JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    actionsPanel.setOpaque(false);
    toggleButton.putClientProperty("JButton.buttonType", "borderless");
    actionsPanel.add(toggleButton);
    topPanel.add(actionsPanel, BorderLayout.WEST);

    addSearchFunctionality(topPanel, listPanel, filterLogic);

    final JPanel mainPanel = new JPanel(new BorderLayout());
    mainPanel.setBorder(JBUI.Borders.empty());
    mainPanel.add(topPanel, BorderLayout.NORTH);

    final JBScrollPane scrollPane = new JBScrollPane(listPanel);
    scrollPane.setBorder(JBUI.Borders.empty());
    mainPanel.add(scrollPane, BorderLayout.CENTER);

    return mainPanel;
  }

  private void addSearchFunctionality(
      final JPanel topPanel, final JPanel listPanel, final BiConsumer<JPanel, String> filterLogic) {
    final JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    searchPanel.setOpaque(false);

    final JBTextField searchField = new JBTextField(20);
    searchField.getEmptyText().setText("Search tables and columns...");
    searchField
        .getDocument()
        .addDocumentListener(createFilterListener(searchField, listPanel, filterLogic));

    searchPanel.add(searchField);
    topPanel.add(searchPanel, BorderLayout.EAST);
  }

  private DocumentListener createFilterListener(
      final JTextField searchField,
      final JPanel listPanel,
      final BiConsumer<JPanel, String> filterLogic) {
    return new DocumentListener() {
      @Override
      public void insertUpdate(final DocumentEvent e) {
        filter();
      }

      @Override
      public void removeUpdate(final DocumentEvent e) {
        filter();
      }

      @Override
      public void changedUpdate(final DocumentEvent e) {
        filter();
      }

      private void filter() {
        filterLogic.accept(listPanel, searchField.getText());
      }
    };
  }

  private void filterPanelComponents(final JPanel listPanel, final String searchText) {
    final String lowerSearchText = searchText.toLowerCase(Locale.ROOT);

    for (final Component component : listPanel.getComponents()) {
      if (component instanceof final JPanel tablePanel) {
        filterTablePanel(tablePanel, lowerSearchText);
      }
    }
  }

  private void filterTablePanel(final JPanel tablePanel, final String lowerSearchText) {
    final String headerText = getHeaderText(tablePanel);
    final List<JCheckBox> columnBoxes = getColumnBoxes(tablePanel);

    final boolean isTableVisible = headerText.toLowerCase(Locale.ROOT).contains(lowerSearchText);
    boolean anyColumnVisible = false;

    for (final JCheckBox colBox : columnBoxes) {
      final boolean isColVisible =
          colBox.getText().toLowerCase(Locale.ROOT).contains(lowerSearchText);
      colBox.setVisible(isColVisible);
      if (isColVisible) anyColumnVisible = true;
    }

    tablePanel.setVisible(isTableVisible || anyColumnVisible);
    if (isTableVisible) {
      columnBoxes.forEach(c -> c.setVisible(true));
    }
  }

  private String getHeaderText(JPanel tablePanel) {
    for (final Component child : tablePanel.getComponents()) {
      final String text = extractText(child);
      if (Objects.nonNull(text)) {
        return text;
      }
      if (child instanceof final JPanel panel) {
        for (final Component inner : panel.getComponents()) {
          final String innerText = extractText(inner);
          if (Objects.nonNull(innerText)) {
            return innerText;
          }
        }
      }
    }
    return "";
  }

  private List<JCheckBox> getColumnBoxes(JPanel tablePanel) {
    final List<JCheckBox> columnBoxes = new ArrayList<>();
    for (final Component c : tablePanel.getComponents()) {
      if (c instanceof final JPanel colsPanel) {
        for (final Component colComp : colsPanel.getComponents()) {
          if (colComp instanceof final JCheckBox colBox) {
            columnBoxes.add(colBox);
          }
        }
      }
    }
    return columnBoxes;
  }

  private JComponent createAiColumnSelectionPanel() {
    final JPanel listPanel = createConfiguredListPanel();
    final GridBagConstraints c = createDefaultGridBagConstraints();
    final List<JCheckBox> checkBoxes = new ArrayList<>();

    tables.forEach(
        table -> {
          final Set<String> fkCols = table.fkColumnNames();
          final List<Column> eligibleColumns =
              table.columns().stream()
                  .filter(
                      col -> isStringType(col) && !col.primaryKey() && !fkCols.contains(col.name()))
                  .toList();

          if (eligibleColumns.isEmpty()) {
            return;
          }

          final JLabel tblLabel = new JLabel(table.name());
          tblLabel.setFont(tblLabel.getFont().deriveFont(Font.BOLD));
          tblLabel.setBorder(JBUI.Borders.emptyBottom(4));

          final List<JCheckBox> tableBoxes = new ArrayList<>();
          eligibleColumns.forEach(
              column -> {
                final boolean preSelected = isDefaultAiCandidate(column.name());
                final JCheckBox box = new JCheckBox(column.name());
                box.setSelected(preSelected);
                if (preSelected) {
                  aiColumnsByTable
                      .computeIfAbsent(table.name(), k -> new LinkedHashSet<>())
                      .add(column.name());
                }
                aiCheckBoxes
                    .computeIfAbsent(table.name(), k -> new LinkedHashMap<>())
                    .put(column.name(), box);
                box.addActionListener(
                    e -> onAiBoxChanged(table.name(), column.name(), box.isSelected()));
                tableBoxes.add(box);
                checkBoxes.add(box);
              });

          listPanel.add(createTablePanel(tblLabel, tableBoxes), c);
          c.gridy++;
        });

    return createTogglableListPanel(listPanel, checkBoxes, this::filterPanelComponents);
  }

  private void onAiBoxChanged(String tableName, String columnName, boolean isSelected) {
    aiColumnsByTable.computeIfAbsent(tableName, k -> new LinkedHashSet<>());
    if (isSelected) {
      aiColumnsByTable.get(tableName).add(columnName);
    } else {
      aiColumnsByTable.get(tableName).remove(columnName);
    }
  }

  public Map<String, Set<String>> getAiColumnsByTable() {
    final Map<String, Set<String>> out = new LinkedHashMap<>();
    aiColumnsByTable.forEach((k, v) -> out.put(k, Set.copyOf(v)));
    return out;
  }

  public Map<String, Set<String>> getSelectionByTable() {
    final Map<String, Set<String>> out = new LinkedHashMap<>();
    selectionByTable.forEach((k, v) -> out.put(k, Set.copyOf(v)));
    return out;
  }

  @SuppressWarnings("unused")
  public Map<String, Map<String, String>> getUuidValuesByTable() {
    return uuidValuesByTable;
  }

  public Map<String, Set<String>> getExcludedColumnsByTable() {
    final Map<String, Set<String>> out = new LinkedHashMap<>();
    excludedColumnsByTable.forEach((k, v) -> out.put(k, Set.copyOf(v)));
    return out;
  }

  @SuppressWarnings("unused")
  public Set<String> getExcludedTables() {
    return Set.copyOf(excludedTables);
  }

  public Map<String, List<RepetitionRule>> getRepetitionRules() {
    return repetitionRulesPanel.getRules();
  }

  public String getSoftDeleteColumns() {
    return softDeleteColumnsField.getText().trim();
  }

  public boolean getSoftDeleteUseSchemaDefault() {
    return softDeleteUseSchemaDefaultBox.isSelected();
  }

  public String getSoftDeleteValue() {
    return softDeleteValueField.getText().trim();
  }

  public int getNumericScale() {
    return (Integer) scaleSpinner.getValue();
  }

  public Map<String, SelfReferenceConfig> getSelfReferenceConfigs() {
    return Map.copyOf(selfReferenceConfigs);
  }

  /**
   * Builds the "Self-Referencing FK Strategy" panel for the Advanced tab.
   *
   * <p>Detected FK relations that are self-referencing or part of a multi-table cycle are shown as
   * simple rows with the exact source and target column.
   */
  private JPanel createSelfRefSection() {
    selfRefStrategyBoxes.clear();
    selfRefDepthSpinners.clear();

    final List<SelfRefRelation> relations = detectSelfRefRelations();

    // ── Section container ─────────────────────────────────────────────────
    final JPanel section = new JPanel();
    section.setLayout(new javax.swing.BoxLayout(section, javax.swing.BoxLayout.Y_AXIS));
    section.setBorder(JBUI.Borders.emptyTop(20));

    final JBLabel title = new JBLabel("Self-Referencing / Cyclic FK Strategy");
    title.setFont(JBUI.Fonts.label().deriveFont(Font.BOLD, 13f));
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    section.add(title);
    section.add(Box.createVerticalStrut(6));

    if (relations.isEmpty()) {
      final JBLabel noTables =
          new JBLabel("No self-referencing or cyclic FK columns detected in this schema.");
      noTables.setFont(JBUI.Fonts.smallFont());
      noTables.setForeground(UIManager.getColor("Label.disabledForeground"));
      noTables.setAlignmentX(Component.LEFT_ALIGNMENT);
      section.add(noTables);
      return section;
    }

    final JPanel descriptionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    descriptionPanel.setOpaque(false);
    descriptionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
    final JBLabel descriptionLabel =
        new JBLabel("Configure strategy and depth directly on each FK column.");
    descriptionLabel.setFont(JBUI.Fonts.smallFont());
    descriptionLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
    descriptionPanel.add(descriptionLabel);
    section.add(descriptionPanel);
    section.add(Box.createVerticalStrut(8));

    final Map<String, List<SelfRefRowControls>> rowsByTable = new LinkedHashMap<>();
    final AtomicBoolean syncingRows = new AtomicBoolean(false);

    // ── Global controls ───────────────────────────────────────────────────
    final JPanel globalRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    globalRow.setOpaque(false);
    globalRow.setAlignmentX(Component.LEFT_ALIGNMENT);

    final ComboBox<SelfReferenceStrategy> globalStrategyBox =
        new ComboBox<>(SelfReferenceStrategy.values());
    globalStrategyBox.setSelectedItem(SelfReferenceStrategy.NONE);

    final JSpinner globalDepthSpinner =
        new JSpinner(new SpinnerNumberModel(DEFAULT_GLOBAL_HIERARCHY_DEPTH, 1, 9999, 1));
    configureEditableDepthSpinner(globalDepthSpinner, 96);

    final JButton applyAllButton = new JButton("Apply to all");

    globalRow.add(new JBLabel("Global strategy:"));
    globalRow.add(globalStrategyBox);
    globalRow.add(Box.createHorizontalStrut(4));
    globalRow.add(new JBLabel("Depth:"));
    globalRow.add(globalDepthSpinner);
    globalRow.add(Box.createHorizontalStrut(2));
    globalRow.add(applyAllButton);

    section.add(globalRow);
    section.add(Box.createVerticalStrut(12));

    applyAllButton.addActionListener(
        e -> {
          try {
            globalDepthSpinner.commitEdit();
          } catch (final ParseException ignored) {
            // keep last valid value
          }
          final SelfReferenceStrategy selected =
              (SelfReferenceStrategy) globalStrategyBox.getSelectedItem();
          final int depth = (Integer) globalDepthSpinner.getValue();
          rowsByTable
              .keySet()
              .forEach(
                  tableName ->
                      syncTableSelfRefControls(
                          tableName, selected, depth, rowsByTable, syncingRows));
        });

    // Header row
    final JPanel headerRow = new JPanel(new GridBagLayout());
    headerRow.setOpaque(false);
    headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
    final GridBagConstraints hc = new GridBagConstraints();
    hc.gridy = 0;
    hc.insets = JBUI.insets(0, 0, 6, 8);
    hc.anchor = GridBagConstraints.WEST;

    hc.gridx = 0;
    hc.weightx = 1.0;
    hc.fill = GridBagConstraints.HORIZONTAL;
    final JBLabel relationHeader = new JBLabel("Table.Column -> Target");
    relationHeader.setFont(JBUI.Fonts.smallFont().deriveFont(Font.BOLD));
    headerRow.add(relationHeader, hc);

    hc.gridx = 1;
    hc.weightx = 0;
    hc.fill = GridBagConstraints.NONE;
    final JBLabel strategyHeader = new JBLabel("Strategy");
    strategyHeader.setFont(JBUI.Fonts.smallFont().deriveFont(Font.BOLD));
    headerRow.add(strategyHeader, hc);

    hc.gridx = 2;
    final JBLabel depthHeader = new JBLabel("Depth");
    depthHeader.setFont(JBUI.Fonts.smallFont().deriveFont(Font.BOLD));
    headerRow.add(depthHeader, hc);

    section.add(headerRow);

    for (final SelfRefRelation relation : relations) {
      final JPanel row = new JPanel(new GridBagLayout());
      row.setOpaque(false);
      row.setAlignmentX(Component.LEFT_ALIGNMENT);
      final GridBagConstraints c = new GridBagConstraints();
      c.gridy = 0;
      c.insets = JBUI.insets(2, 0, 2, 8);
      c.anchor = GridBagConstraints.WEST;

      c.gridx = 0;
      c.weightx = 1.0;
      c.fill = GridBagConstraints.HORIZONTAL;
      row.add(new JBLabel(relation.toDisplayText()), c);

      final ComboBox<SelfReferenceStrategy> strategyBox =
          new ComboBox<>(SelfReferenceStrategy.values());
      strategyBox.setSelectedItem(SelfReferenceStrategy.NONE);
      c.gridx = 1;
      c.weightx = 0;
      c.fill = GridBagConstraints.NONE;
      row.add(strategyBox, c);

      final JSpinner depthSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 9999, 1));
      configureEditableDepthSpinner(depthSpinner, 84);
      depthSpinner.setEnabled(false);
      c.gridx = 2;
      row.add(depthSpinner, c);

      rowsByTable
          .computeIfAbsent(relation.tableName(), key -> new ArrayList<>())
          .add(new SelfRefRowControls(strategyBox, depthSpinner));
      selfRefStrategyBoxes.putIfAbsent(relation.tableName(), strategyBox);
      selfRefDepthSpinners.putIfAbsent(relation.tableName(), depthSpinner);

      strategyBox.addActionListener(
          e ->
              syncTableSelfRefControls(
                  relation.tableName(),
                  (SelfReferenceStrategy) strategyBox.getSelectedItem(),
                  (Integer) depthSpinner.getValue(),
                  rowsByTable,
                  syncingRows));

      depthSpinner.addChangeListener(
          e ->
              syncTableSelfRefControls(
                  relation.tableName(),
                  (SelfReferenceStrategy) strategyBox.getSelectedItem(),
                  (Integer) depthSpinner.getValue(),
                  rowsByTable,
                  syncingRows));

      section.add(row);
    }

    return section;
  }

  private List<SelfRefRelation> detectSelfRefRelations() {
    final List<SelfRefRelation> relations = new ArrayList<>();
    final List<Set<String>> multiCycles =
        com.luisppb16.dbseed.db.TopologicalSorter.sort(tables).cycles().stream()
            .filter(cycle -> cycle.size() > 1)
            .toList();

    for (final Table table : tables) {
      for (final var fk : table.foreignKeys()) {
        final boolean isSelfReference = table.name().equals(fk.pkTable());
        final boolean isMultiCycleRelation =
            multiCycles.stream()
                .anyMatch(c -> c.contains(table.name()) && c.contains(fk.pkTable()));
        if (!isSelfReference && !isMultiCycleRelation) {
          continue;
        }
        fk.columnMapping()
            .forEach(
                (fkColumn, targetColumn) ->
                    relations.add(
                        new SelfRefRelation(table.name(), fkColumn, fk.pkTable(), targetColumn)));
      }
    }

    relations.sort(
        java.util.Comparator.comparing(SelfRefRelation::tableName)
            .thenComparing(SelfRefRelation::fkColumn)
            .thenComparing(SelfRefRelation::targetTable)
            .thenComparing(SelfRefRelation::targetColumn));
    return relations;
  }

  // ── Self-reference section ──────────────────────────────────────────────────────────────────

  private void syncTableSelfRefControls(
      final String tableName,
      final SelfReferenceStrategy strategy,
      final int depth,
      final Map<String, List<SelfRefRowControls>> rowsByTable,
      final AtomicBoolean syncingRows) {
    if (strategy == null || syncingRows.get()) {
      return;
    }

    syncingRows.set(true);
    try {
      final List<SelfRefRowControls> controls = rowsByTable.getOrDefault(tableName, List.of());
      for (final SelfRefRowControls control : controls) {
        if (control.strategyBox().getSelectedItem() != strategy) {
          control.strategyBox().setSelectedItem(strategy);
        }
        if (!Objects.equals(control.depthSpinner().getValue(), depth)) {
          control.depthSpinner().setValue(depth);
        }
        control.depthSpinner().setEnabled(strategy == SelfReferenceStrategy.HIERARCHY);
      }
      updateSelfRefConfig(tableName, strategy, depth);
    } finally {
      syncingRows.set(false);
    }
  }

  private void updateSelfRefConfig(
      final String tableName, final SelfReferenceStrategy strategy, final int depth) {
    if (strategy == null || strategy == SelfReferenceStrategy.NONE) {
      selfReferenceConfigs.remove(tableName);
    } else if (strategy == SelfReferenceStrategy.HIERARCHY) {
      selfReferenceConfigs.put(
          tableName,
          SelfReferenceConfig.builder().strategy(strategy).hierarchyDepth(depth).build());
    } else {
      // CIRCULAR — hierarchyDepth is not used but the record requires a value
      selfReferenceConfigs.put(
          tableName, SelfReferenceConfig.builder().strategy(strategy).hierarchyDepth(0).build());
    }
  }

  private void configureEditableDepthSpinner(final JSpinner spinner, final int width) {
    spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
    if (spinner.getEditor() instanceof final JSpinner.DefaultEditor defaultEditor) {
      final JFormattedTextField textField = defaultEditor.getTextField();
      textField.setEditable(true);
      textField.setColumns(5);
    }

    // Use a concrete height so the editor stays visible in Darcula/IntelliJ themes.
    spinner.setPreferredSize(JBUI.size(width, 26));
    ComponentUtils.configureSpinnerArrowKeyControls(spinner);
  }

  private record SelfRefRelation(
      String tableName, String fkColumn, String targetTable, String targetColumn) {
    private String toDisplayText() {
      return "%s.%s -> %s.%s".formatted(tableName, fkColumn, targetTable, targetColumn);
    }
  }

  private record SelfRefRowControls(
      ComboBox<SelfReferenceStrategy> strategyBox, JSpinner depthSpinner) {}

  private final class BackAction extends AbstractAction {
    private BackAction() {
      super("Back");
      putValue(MNEMONIC_KEY, java.awt.event.KeyEvent.VK_B);
    }

    @Override
    public void actionPerformed(final ActionEvent e) {
      close(BACK_EXIT_CODE);
    }
  }
}
