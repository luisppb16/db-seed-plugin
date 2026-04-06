/*
 * *****************************************************************************
 *  * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 *  * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.DialogWrapper;
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
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.ui.util.ComponentUtils;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
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
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
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
  private static final String LABEL_DISABLED_FOREGROUND = "Label.disabledForeground";

  private final List<Table> tables;
  private final GenerationConfig initialConfig;
  private final Map<String, Set<String>> selectionByTable = new LinkedHashMap<>();
  private final Map<String, Set<String>> excludedColumnsByTable = new LinkedHashMap<>();
  private final Set<String> excludedTables = new LinkedHashSet<>();
  private final Map<String, Map<String, String>> uuidValuesByTable = new LinkedHashMap<>();
  private final RepetitionRulesPanel repetitionRulesPanel;
  private final CircularReferencesPanel circularReferencesPanel;

  private final JBTextField softDeleteColumnsField = new JBTextField();
  private final JBCheckBox softDeleteUseSchemaDefaultBox =
      new JBCheckBox("Use schema default value");
  private final JBTextField softDeleteValueField = new JBTextField();

  private final JSpinner scaleSpinner;

  private final Map<String, Set<String>> aiColumnsByTable = new LinkedHashMap<>();
  private final Map<String, Map<String, JCheckBox>> aiCheckBoxes = new LinkedHashMap<>();

  private final Map<String, Map<String, JCheckBox>> pkCheckBoxes = new LinkedHashMap<>();
  private final Map<String, Map<String, JCheckBox>> excludeCheckBoxes = new LinkedHashMap<>();

  public PkUuidSelectionDialog(
      @NotNull final List<Table> tables, @NotNull final GenerationConfig initialConfig) {
    super(true);
    this.tables = Objects.requireNonNull(tables, "Table list cannot be null.");
    this.initialConfig = Objects.requireNonNull(initialConfig, "Initial config cannot be null.");
    this.repetitionRulesPanel = new RepetitionRulesPanel(tables);
    this.circularReferencesPanel = new CircularReferencesPanel(tables);

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
    return component instanceof final JCheckBox box
        ? box.getText()
        : component instanceof final JLabel label ? label.getText() : null;
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
    super.doOKAction();
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

    // Tab 5: Circular References
    tabbedPane.addTab(
        "Circular References",
        AllIcons.Nodes.Related,
        wrapInScrollPane(circularReferencesPanel),
        "Configure self-referencing circular dependencies");

    // Tab 6: Advanced Settings
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
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    return scrollPane;
  }

  private JPanel createMoreSettingsPanel() {
    final DbSeedSettingsState global = DbSeedSettingsState.getInstance();

    // === Soft Delete Section ===
    final JPanel softDeleteSection = new JPanel();
    softDeleteSection.setLayout(new BoxLayout(softDeleteSection, BoxLayout.Y_AXIS));
    softDeleteSection.setBorder(BorderFactory.createTitledBorder("Soft Delete Settings"));

    // Section header
    final JBLabel softDeleteTitle = new JBLabel("Soft delete configuration");
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
    statusLabel.setForeground(UIManager.getColor(LABEL_DISABLED_FOREGROUND));
    final JButton resetButton = new JButton("Restore Defaults");
    resetButton.putClientProperty("JButton.buttonType", "borderless");
    statusPanel.add(statusLabel, BorderLayout.WEST);
    statusPanel.add(resetButton, BorderLayout.EAST);
    softDeleteSection.add(statusPanel);

    // === Numeric Configuration Section ===
    final JPanel numericSection = new JPanel();
    numericSection.setLayout(new BoxLayout(numericSection, BoxLayout.Y_AXIS));
    numericSection.setBorder(BorderFactory.createTitledBorder("Numeric Scale"));

    // Section header
    final JBLabel numericTitle = new JBLabel("Numeric configuration");
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
    scaleTooltip.setForeground(UIManager.getColor(LABEL_DISABLED_FOREGROUND));
    numericSection.add(scaleTooltip);

    // === Main panel ===
    final JPanel mainPanel = new JPanel(new BorderLayout());
    mainPanel.setBorder(JBUI.Borders.empty());

    final JPanel sectionsWrapper = new JPanel();
    sectionsWrapper.setLayout(new BoxLayout(sectionsWrapper, BoxLayout.Y_AXIS));
    sectionsWrapper.add(softDeleteSection);
    sectionsWrapper.add(numericSection);
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
                  : UIManager.getColor(LABEL_DISABLED_FOREGROUND));
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
    final JBLabel countLabel = new JBLabel();

    final List<JCheckBox> itemBoxes =
        checkBoxes.stream()
            .filter(b -> !Boolean.TRUE.equals(b.getClientProperty(IS_TABLE_PROPERTY)))
            .toList();

    final Runnable updateButtonState =
        () -> {
          final long selectedCount = itemBoxes.stream().filter(AbstractButton::isSelected).count();
          final int totalCount = itemBoxes.size();
          countLabel.setText(String.format("%d/%d selected", selectedCount, totalCount));

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
    actionsPanel.add(Box.createHorizontalStrut(10));
    actionsPanel.add(countLabel);
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
          final List<Column> stringColumns =
              table.columns().stream().filter(PkUuidSelectionDialog::isStringType).toList();

          if (stringColumns.isEmpty()) {
            return;
          }

          final JLabel tblLabel = new JLabel(table.name());
          tblLabel.setFont(tblLabel.getFont().deriveFont(Font.BOLD));
          tblLabel.setBorder(JBUI.Borders.emptyBottom(4));

          final List<JCheckBox> tableBoxes = new ArrayList<>();
          stringColumns.forEach(
              column -> {
                final boolean hasConstraint =
                    column.primaryKey()
                        || fkCols.contains(column.name())
                        || column.hasAllowedValues();
                final boolean preSelected = !hasConstraint && isDefaultAiCandidate(column.name());
                final JCheckBox box = new JCheckBox(column.name());

                if (hasConstraint) {
                  box.setSelected(false);
                  box.setEnabled(false);
                  box.setToolTipText(
                      "Column has constraints (PK, FK, or allowed values). Will be generated by the random algorithm.");
                } else {
                  box.setSelected(preSelected);
                }

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

  public Map<String, Map<String, Integer>> getCircularReferences() {
    return circularReferencesPanel.getCircularReferences();
  }

  public Map<String, Map<String, String>> getCircularReferenceTerminationModes() {
    return circularReferencesPanel.getCircularReferenceTerminationModes();
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

  private final class BackAction extends AbstractAction {
    private BackAction() {
      super("Back");
      putValue(MNEMONIC_KEY, KeyEvent.VK_B);
    }

    @Override
    public void actionPerformed(final ActionEvent e) {
      close(BACK_EXIT_CODE);
    }
  }
}
