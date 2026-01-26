/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.GenerationConfig;
import com.luisppb16.dbseed.model.Column;
import com.luisppb16.dbseed.model.RepetitionRule;
import com.luisppb16.dbseed.model.Table;
import com.luisppb16.dbseed.ui.util.ComponentUtils;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jetbrains.annotations.NotNull;

public final class PkUuidSelectionDialog extends DialogWrapper {

  public static final int BACK_EXIT_CODE = NEXT_USER_EXIT_CODE + 1;
  private static final String TREAT_AS_UUID_PREFIX = "Treat as UUID: ";
  private static final String IS_TABLE_PROPERTY = "isTable";

  private final List<Table> tables;
  private final GenerationConfig initialConfig;
  private final Map<String, Set<String>> selectionByTable = new LinkedHashMap<>();
  private final Map<String, Set<String>> excludedColumnsByTable = new LinkedHashMap<>();
  private final Set<String> excludedTables = new LinkedHashSet<>();
  private final Map<String, Map<String, String>> uuidValuesByTable = new LinkedHashMap<>();
  private final RepetitionRulesPanel repetitionRulesPanel;

  // Soft Delete UI Components
  private final JBTextField softDeleteColumnsField = new JBTextField();
  private final JBCheckBox softDeleteUseSchemaDefaultBox = new JBCheckBox("Use schema default value");
  private final JBTextField softDeleteValueField = new JBTextField();

  // Numeric Scale UI Component
  private final JSpinner scaleSpinner;

  // Maps to hold checkbox references for cross-tab synchronization
  private final Map<String, Map<String, JCheckBox>> pkCheckBoxes = new LinkedHashMap<>();
  private final Map<String, Map<String, JCheckBox>> excludeCheckBoxes = new LinkedHashMap<>();

  public PkUuidSelectionDialog(@NotNull final List<Table> tables, @NotNull final GenerationConfig initialConfig) {
    super(true);
    this.tables = Objects.requireNonNull(tables, "Table list cannot be null.");
    this.initialConfig = Objects.requireNonNull(initialConfig, "Initial config cannot be null.");
    this.repetitionRulesPanel = new RepetitionRulesPanel(tables);

    // Initialize scale spinner with value from config or default
    final int initialScale = initialConfig.numericScale() >= 0 ? initialConfig.numericScale() : 2;
    this.scaleSpinner = new JSpinner(new SpinnerNumberModel(initialScale, 0, 10, 1));
    ComponentUtils.configureSpinnerArrowKeyControls(this.scaleSpinner);

    setTitle("Data Configuration - Step 3/3");
    initDefaults();
    setOKButtonText("Generate");
    init();
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
                        return (col != null && col.uuid())
                            || lower.contains("uuid")
                            || lower.contains("guid");
                      })
                  .collect(Collectors.toCollection(LinkedHashSet::new));
          selectionByTable.put(table.name(), defaults);

          final Map<String, String> uuidValues = new LinkedHashMap<>();
          defaults.forEach(pkCol -> uuidValues.put(pkCol, UUID.randomUUID().toString()));
          uuidValuesByTable.put(table.name(), uuidValues);
        });

    // Initialize Soft Delete fields from passed config or global defaults
    String cols = initialConfig.softDeleteColumns();
    if (cols == null) {
      cols = DbSeedSettingsState.getInstance().getSoftDeleteColumns();
    }
    softDeleteColumnsField.setText(cols);

    softDeleteUseSchemaDefaultBox.setSelected(initialConfig.softDeleteUseSchemaDefault());

    String val = initialConfig.softDeleteValue();
    if (val == null) {
      val = DbSeedSettingsState.getInstance().getSoftDeleteValue();
    }
    softDeleteValueField.setText(val);

    softDeleteValueField.setEnabled(!softDeleteUseSchemaDefaultBox.isSelected());
    softDeleteUseSchemaDefaultBox.addActionListener(e ->
        softDeleteValueField.setEnabled(!softDeleteUseSchemaDefaultBox.isSelected())
    );
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {new BackAction(), getOKAction(), getCancelAction()};
  }

  @Override
  protected void doOKAction() {
    try {
      scaleSpinner.commitEdit();
    } catch (final ParseException e) {
      // Invalid number typed, spinner will retain last valid value.
    }
    super.doOKAction();
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    final JBTabbedPane tabbedPane = new JBTabbedPane();
    tabbedPane.addTab("PK UUID Selection", createPkSelectionPanel());
    tabbedPane.addTab("Exclude Columns/Tables", createColumnExclusionPanel());
    tabbedPane.addTab("Repetition Rules", repetitionRulesPanel);
    tabbedPane.addTab("More Settings", createMoreSettingsPanel());

    // Initial synchronization of states
    synchronizeInitialStates();

    final JPanel content = new JPanel(new BorderLayout());
    content.add(tabbedPane, BorderLayout.CENTER);
    content.setPreferredSize(new Dimension(650, 500));
    return content;
  }

  private JPanel createMoreSettingsPanel() {
    final DbSeedSettingsState global = DbSeedSettingsState.getInstance();

    // Status label for visual feedback
    final JBLabel statusLabel = new JBLabel("Using global settings");
    statusLabel.setFont(JBUI.Fonts.smallFont());
    statusLabel.setForeground(UIManager.getColor("Label.infoForeground"));

    final Runnable updateStatus =
        () -> {
          final boolean modified =
              !softDeleteColumnsField.getText().equals(global.getSoftDeleteColumns())
                  || softDeleteUseSchemaDefaultBox.isSelected() != global.isSoftDeleteUseSchemaDefault()
                  || !softDeleteValueField.getText().equals(global.getSoftDeleteValue());
          statusLabel.setText(modified ? "Modified from global settings" : "Using global settings");
          statusLabel.setIcon(modified ? AllIcons.General.Modified : null);
        };

    // Add listeners for real-time feedback
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

    // Initial status check
    updateStatus.run();

    final JButton resetButton = new JButton("Restore Global Defaults");
    resetButton.addActionListener(
        e -> {
          softDeleteColumnsField.setText(global.getSoftDeleteColumns());
          softDeleteUseSchemaDefaultBox.setSelected(global.isSoftDeleteUseSchemaDefault());
          softDeleteValueField.setText(global.getSoftDeleteValue());
          softDeleteValueField.setEnabled(!global.isSoftDeleteUseSchemaDefault());
          updateStatus.run();
        });

    // Use FormBuilder for a clean, consistent layout
    return FormBuilder.createFormBuilder()
        .addComponent(new TitledSeparator("Soft Delete Configuration"))
        .addVerticalGap(5)
        .addLabeledComponent("Columns (comma separated):", softDeleteColumnsField)
        .addComponent(softDeleteUseSchemaDefaultBox)
        .addLabeledComponent("Value (if not default):", softDeleteValueField)
        .addVerticalGap(5)
        .addComponent(statusLabel)
        .addComponent(resetButton)
        .addVerticalGap(15)
        .addComponent(new TitledSeparator("Numeric Configuration"))
        .addVerticalGap(5)
        .addLabeledComponent("Default numeric scale:", scaleSpinner)
        .addTooltip("Applied to DECIMAL/NUMERIC columns without explicit scale defined in schema.")
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
  }

  private void synchronizeInitialStates() {
    syncCheckBoxState(pkCheckBoxes, excludeCheckBoxes);
    syncCheckBoxState(excludeCheckBoxes, pkCheckBoxes);
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
                    if (targetBox != null) {
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
          table.primaryKey().forEach(pkCol -> {
            final JCheckBox box = new JCheckBox(TREAT_AS_UUID_PREFIX + pkCol);
            box.setSelected(selectionByTable.getOrDefault(table.name(), Set.of()).contains(pkCol));
            pkCheckBoxes.computeIfAbsent(table.name(), k -> new LinkedHashMap<>()).put(pkCol, box);
            box.addActionListener(e -> onPkBoxChanged(table.name(), pkCol, box.isSelected()));
            tableBoxes.add(box);
            checkBoxes.add(box);
          });

          listPanel.add(createTablePanel(tblLabel, tableBoxes), c);
          c.gridy++;
        });

    return createTogglableListPanel(
        listPanel,
        checkBoxes,
        (box, isSelected) -> {
          final String tableName = getTableNameForComponent(box);
          final String columnName = getColumnNameForCheckBox(box);
          if (tableName != null) {
            onPkBoxChanged(tableName, columnName, isSelected);
          }
        },
        this::filterPanelComponents);
  }

  private void onPkBoxChanged(String tableName, String pkCol, boolean isSelected) {
    updateSelectionAndSync(tableName, pkCol, isSelected, selectionByTable, excludeCheckBoxes, excludedColumnsByTable);
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
          table.columns().forEach(column -> {
            final JCheckBox box = new JCheckBox(column.name());
            box.setSelected(excludedColumnsByTable.getOrDefault(table.name(), Set.of()).contains(column.name()));
            excludeCheckBoxes.computeIfAbsent(table.name(), k -> new LinkedHashMap<>()).put(column.name(), box);
            box.addActionListener(e -> onExcludeBoxChanged(table.name(), column.name(), box.isSelected()));
            currentTableColumnBoxes.add(box);
            checkBoxes.add(box);
          });

          tblBox.addActionListener(e -> onTableExcludeBoxChanged(table.name(), tblBox.isSelected(), currentTableColumnBoxes));
          checkBoxes.add(tblBox);

          listPanel.add(createTablePanel(tblBox, currentTableColumnBoxes), c);
          c.gridy++;
        });

    return createTogglableListPanel(
        listPanel,
        checkBoxes,
        (box, isSelected) -> {
          if (Boolean.TRUE.equals(box.getClientProperty(IS_TABLE_PROPERTY))) {
            onTableExcludeBoxChanged(box.getText(), isSelected, findColumnBoxesForTable(box));
          } else {
            final String tableName = getTableNameForComponent(box);
            final String columnName = getColumnNameForCheckBox(box);
            if (tableName != null) {
              onExcludeBoxChanged(tableName, columnName, isSelected);
            }
          }
        },
        this::filterPanelComponents);
  }

  private List<JCheckBox> findColumnBoxesForTable(JCheckBox tableBox) {
    // Helper to find sibling checkboxes in the UI hierarchy
    List<JCheckBox> boxes = new ArrayList<>();
    Component parent = tableBox.getParent(); // tablePanel
    if (parent instanceof JPanel panel) {
      for (Component child : panel.getComponents()) {
        if (child instanceof JPanel columnsPanel) {
           for (Component colChild : columnsPanel.getComponents()) {
             if (colChild instanceof JCheckBox cb) {
               boxes.add(cb);
             }
           }
        }
      }
    }
    return boxes;
  }

  private void onExcludeBoxChanged(String tableName, String columnName, boolean isSelected) {
    updateSelectionAndSync(tableName, columnName, isSelected, excludedColumnsByTable, pkCheckBoxes, selectionByTable);
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

    // Sync with other tab
    final JCheckBox otherBox =
        otherCheckBoxesMap.getOrDefault(tableName, Collections.emptyMap()).get(colName);
    if (otherBox != null) {
      otherBox.setEnabled(!isSelected);
      if (isSelected) {
        otherBox.setSelected(false);
        otherSelectionMap.getOrDefault(tableName, Collections.emptySet()).remove(colName);
      }
    }
  }

  private void onTableExcludeBoxChanged(String tableName, boolean isSelected, List<JCheckBox> columnBoxes) {
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
    final JPanel tablePanel = new JPanel(new BorderLayout());
    tablePanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(
                1, 1, 1, 1, UIManager.getColor("Component.borderColor")),
            JBUI.Borders.empty(8)));
    tablePanel.setBackground(UIManager.getColor("Panel.background"));
    tablePanel.add(header, BorderLayout.NORTH);

    final JPanel columnsPanel = new JPanel(new GridBagLayout());
    final GridBagConstraints cc = new GridBagConstraints();
    cc.gridx = 0;
    cc.gridy = 0;
    cc.anchor = GridBagConstraints.NORTHWEST;
    cc.fill = GridBagConstraints.HORIZONTAL;
    cc.weightx = 1.0;

    for (JCheckBox box : columnBoxes) {
      columnsPanel.add(box, cc);
      cc.gridy++;
    }

    columnsPanel.setBorder(JBUI.Borders.emptyLeft(16));
    tablePanel.add(columnsPanel, BorderLayout.CENTER);
    return tablePanel;
  }

  private JComponent createTogglableListPanel(
      final JPanel listPanel,
      final List<JCheckBox> checkBoxes,
      final BiConsumer<JCheckBox, Boolean> modelUpdater,
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
                  if (box.isSelected() != selectAll) {
                    box.setSelected(selectAll);
                    modelUpdater.accept(box, selectAll);
                  }
                });
          } finally {
            isBulkUpdating.set(false);
            updateButtonState.run();
          }
        });

    final JPanel topPanel = new JPanel(new BorderLayout(8, 8));
    topPanel.add(toggleButton, BorderLayout.WEST);

    addSearchFunctionality(topPanel, listPanel, filterLogic);

    final JPanel mainPanel = new JPanel(new BorderLayout());
    mainPanel.add(topPanel, BorderLayout.NORTH);
    mainPanel.add(new JBScrollPane(listPanel), BorderLayout.CENTER);

    return mainPanel;
  }

  private String getTableNameForComponent(final Component component) {
    Component current = component;
    while (current != null) {
      if (current instanceof final JPanel panel) {
        for (final Component child : panel.getComponents()) {
          if (child instanceof final JCheckBox box
              && Boolean.TRUE.equals(box.getClientProperty(IS_TABLE_PROPERTY))) {
            return box.getText();
          }
          if (child instanceof final JLabel label) {
            return label.getText();
          }
        }
      }
      current = current.getParent();
    }
    return null;
  }

  private String getColumnNameForCheckBox(final JCheckBox checkBox) {
    final String text = checkBox.getText();
    if (text.startsWith(TREAT_AS_UUID_PREFIX)) {
      return text.substring(TREAT_AS_UUID_PREFIX.length());
    }
    return text;
  }

  private void addSearchFunctionality(
      final JPanel topPanel, final JPanel listPanel, final BiConsumer<JPanel, String> filterLogic) {
    final JPanel searchPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
    final JTextField searchField = new JTextField(20);
    searchField
        .getDocument()
        .addDocumentListener(createFilterListener(searchField, listPanel, filterLogic));
    searchPanel.add(new JLabel("Search:"));
    searchPanel.add(Box.createHorizontalStrut(4));
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
    for (final Component c : tablePanel.getComponents()) {
      if (c instanceof final JCheckBox box && Boolean.TRUE.equals(box.getClientProperty(IS_TABLE_PROPERTY))) {
        return box.getText();
      } else if (c instanceof final JLabel label) {
        return label.getText();
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

  private final class BackAction extends AbstractAction {
    private BackAction() {
      super("Back");
    }

    @Override
    public void actionPerformed(final ActionEvent e) {
      close(BACK_EXIT_CODE);
    }
  }
}