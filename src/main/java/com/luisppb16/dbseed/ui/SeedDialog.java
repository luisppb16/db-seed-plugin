/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import com.luisppb16.dbseed.config.ConnectionConfigPersistence;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.GenerationConfig;
import java.awt.Cursor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JSpinner.DefaultEditor;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SeedDialog extends DialogWrapper {

  public static final int BACK_EXIT_CODE = NEXT_USER_EXIT_CODE + 1;
  private static final String DEFAULT_POSTGRES_USER = "postgres";
  private static final String DEFAULT_POSTGRES_URL = "jdbc:postgresql://localhost:5432/postgres";
  private final JTextField urlField;
  private final JTextField databaseField = new JTextField(DEFAULT_POSTGRES_USER);
  private final JTextField userField = new JTextField(DEFAULT_POSTGRES_USER);
  private final JPasswordField passwordField = new JPasswordField();
  private final JTextField schemaField = new JTextField("public");
  private final JSpinner rowsSpinner;
  private final JCheckBox deferredBox = new JCheckBox("Enable deferred constraints");
  
  // Soft Delete UI Components - Removed from here, moved to PkUuidSelectionDialog
  // But we still need to capture the values if they were passed in config to pass them forward?
  // Actually, SeedDialog is Step 2. PkUuidSelectionDialog is Step 3.
  // The user requested to move Soft Delete config to Step 3.
  // So we remove it from here.
  
  // However, we need to preserve the values if they exist in the loaded config so we can pass them to the next step?
  // Or simply let the next step load them from global config / defaults?
  // The GenerateSeedAction orchestrates this. It gets config from SeedDialog.
  // Then it introspects schema.
  // Then it shows PkUuidSelectionDialog.
  // So SeedDialog needs to return a GenerationConfig.
  // If we remove the UI fields from here, we can't let the user edit them here.
  // But we should still probably carry over the values if we want to persist them in the same GenerationConfig object?
  // Or we can add fields to PkUuidSelectionDialog and have THAT dialog return the final config or updated values.
  
  // Let's remove the UI components from here first.
  // We will store the soft delete values temporarily if needed, or just ignore them here and let Step 3 handle it.
  // Since GenerationConfig is immutable, we build it at the end of this dialog.
  // We can just set null/defaults here for soft delete, and let Step 3 fill them in?
  // Wait, GenerateSeedAction uses `seedDialog.getConfiguration()` to get the config.
  // Then it passes that config to `SchemaIntrospector`.
  // Then it creates `PkUuidSelectionDialog`.
  // If we move the config to Step 3, `PkUuidSelectionDialog` needs to expose these settings.
  // And `GenerateSeedAction` needs to merge the results.
  
  // So for this file: Remove Soft Delete UI.
  // We will keep the fields in GenerationConfig, but here we can just pass null or defaults.
  // Actually, better to pass what we loaded from persistence, so we don't lose it if the user cancels step 3?
  // No, if user cancels step 3, nothing is saved.
  // But we want to pre-fill Step 3 with saved values.
  // So we should probably read the saved config in Step 3 as well, or pass it from here.
  
  private String loadedSoftDeleteColumns;
  private boolean loadedSoftDeleteUseSchemaDefault;
  private String loadedSoftDeleteValue;

  public SeedDialog(@Nullable final String urlTemplate) {
    super(true);
    setTitle("Connection Settings - Step 2/3");

    urlField = new JTextField(urlTemplate != null ? urlTemplate : DEFAULT_POSTGRES_URL);

    final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    rowsSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 100_000, settings.columnSpinnerStep));

    loadConfiguration(urlTemplate);

    final JComponent editor = rowsSpinner.getEditor();
    if (editor instanceof final DefaultEditor defaultEditor) {
      final JFormattedTextField textField = defaultEditor.getTextField();
      final InputMap inputMap = textField.getInputMap(JComponent.WHEN_FOCUSED);

      final String incrementAction = "increment";
      inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), incrementAction);
      final String decrementAction = "decrement";
      inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), decrementAction);

      final ActionMap spinnerActionMap = rowsSpinner.getActionMap();
      final ActionMap textFieldActionMap = textField.getActionMap();
      textFieldActionMap.put(incrementAction, spinnerActionMap.get(incrementAction));
      textFieldActionMap.put(decrementAction, spinnerActionMap.get(decrementAction));
    }

    setOKButtonText("Next");
    init();
  }

  private static void addRow(
      final JPanel panel,
      final GridBagConstraints base,
      final int rowIndex,
      final String labelText,
      final JComponent field) {

    final GridBagConstraints c = (GridBagConstraints) base.clone();

    c.gridx = 0;
    c.gridy = rowIndex;
    c.gridwidth = 1;
    c.anchor = GridBagConstraints.EAST;
    c.fill = GridBagConstraints.NONE;
    panel.add(new JLabel(labelText), c);

    c.gridx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.WEST;
    panel.add(field, c);
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {new BackAction(), getOKAction(), getCancelAction()};
  }

  @Override
  protected void doOKAction() {
    try {
      rowsSpinner.commitEdit();
    } catch (ParseException e) {
      // Invalid number typed, spinner will retain last valid value.
    }
    super.doOKAction();
    saveConfiguration();
  }

  private void loadConfiguration(@Nullable final String urlTemplate) {
    final Project project = getCurrentProject();
    if (project != null) {
      final GenerationConfig config = ConnectionConfigPersistence.load(project);

      String urlToUse = urlTemplate;
      boolean usingSavedConfig = false;

      if (urlToUse == null) {
        urlToUse = Objects.requireNonNullElse(config.url(), DEFAULT_POSTGRES_URL);
        usingSavedConfig = true; 
      } else {
        if (config.url() != null && isSameDriverType(urlTemplate, config.url())) {
          urlToUse = config.url();
          usingSavedConfig = true;
        }
      }

      if (urlToUse.startsWith("jdbc:sqlserver")) {
        String dbName = "";
        String urlWithoutDb = urlToUse;

        final Pattern p = Pattern.compile("databaseName=([^;]+)");
        final Matcher m = p.matcher(urlToUse);
        if (m.find()) {
          dbName = m.group(1);
          urlWithoutDb = urlToUse.replace(m.group(0), "");
          urlWithoutDb = urlWithoutDb.replace(";;", ";");
          if (urlWithoutDb.endsWith(";")) {
            urlWithoutDb = urlWithoutDb.substring(0, urlWithoutDb.length() - 1);
          }
        }
        urlField.setText(urlWithoutDb);
        databaseField.setText(dbName);
      } else {
        final int lastSlashIndex = urlToUse.lastIndexOf('/');
        
        if (lastSlashIndex > 0 && lastSlashIndex < urlToUse.length() - 1) {
           urlField.setText(urlToUse.substring(0, lastSlashIndex + 1));
           databaseField.setText(urlToUse.substring(lastSlashIndex + 1));
        } else {
           urlField.setText(urlToUse);
           if (!usingSavedConfig) {
               if (urlToUse.endsWith("/")) {
                   databaseField.setText("");
               }
           }
        }
      }

      if (usingSavedConfig) {
        userField.setText(Objects.requireNonNullElse(config.user(), DEFAULT_POSTGRES_USER));
        passwordField.setText(Objects.requireNonNullElse(config.password(), ""));
        schemaField.setText(Objects.requireNonNullElse(config.schema(), "public"));
        rowsSpinner.setValue(config.rowsPerTable());
        deferredBox.setSelected(config.deferred());
        
        // Capture loaded values to pass them through, even if not edited here
        loadedSoftDeleteColumns = config.softDeleteColumns();
        loadedSoftDeleteUseSchemaDefault = config.softDeleteUseSchemaDefault();
        loadedSoftDeleteValue = config.softDeleteValue();
        
      } else {
        userField.setText(DEFAULT_POSTGRES_USER);
        passwordField.setText("");
        schemaField.setText("public");
        rowsSpinner.setValue(config.rowsPerTable() > 0 ? config.rowsPerTable() : 10);
        deferredBox.setSelected(config.deferred());
        
        // Defaults
        DbSeedSettingsState globalSettings = DbSeedSettingsState.getInstance();
        loadedSoftDeleteColumns = globalSettings.softDeleteColumns;
        loadedSoftDeleteUseSchemaDefault = globalSettings.softDeleteUseSchemaDefault;
        loadedSoftDeleteValue = globalSettings.softDeleteValue;
      }
    }
  }

  private boolean isSameDriverType(String template, String savedUrl) {
    if (template == null || savedUrl == null) return false;
    int firstColon = template.indexOf(':');
    int secondColon = template.indexOf(':', firstColon + 1);
    if (secondColon > 0) {
      String prefix = template.substring(0, secondColon + 1);
      return savedUrl.startsWith(prefix);
    }
    return false;
  }

  private void saveConfiguration() {
    final Project project = getCurrentProject();
    if (project != null) {
      final GenerationConfig config = getConfiguration();
      ConnectionConfigPersistence.save(project, config);
    }
  }

  private Project getCurrentProject() {
    final Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
    return openProjects.length > 0
        ? openProjects[0]
        : ProjectManager.getInstance().getDefaultProject();
  }

  @Override
  protected @NotNull JComponent createCenterPanel() {
    return createFormPanel();
  }

  private JPanel createFormPanel() {
    final JPanel panel = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.insets = JBUI.insets(4);
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;

    int row = 0;
    addRow(panel, c, row++, "JDBC URL:", urlField);
    addRow(panel, c, row++, "User:", userField);
    addRow(
        panel, c, row++, "Password:", createPasswordFieldWithToggle());
    addRow(panel, c, row++, "Database:", databaseField);
    addRow(panel, c, row++, "Schema:", schemaField);
    addRow(panel, c, row++, "Rows per table:", rowsSpinner);

    c.gridx = 0;
    c.gridy = row++;
    c.gridwidth = 2;
    c.anchor = GridBagConstraints.WEST;
    panel.add(deferredBox, c);

    return panel;
  }

  private JLayeredPane createPasswordFieldWithToggle() {
    final JLayeredPane passwordLayeredPane = new JLayeredPane();
    passwordLayeredPane.setPreferredSize(passwordField.getPreferredSize());

    final Border originalBorder = passwordField.getBorder();
    passwordField.setBorder(new CompoundBorder(originalBorder, JBUI.Borders.emptyRight(30)));
    passwordLayeredPane.add(passwordField, JLayeredPane.DEFAULT_LAYER);

    final JToggleButton showPasswordButton = new JToggleButton(AllIcons.Actions.Show);
    showPasswordButton.setFocusPainted(false);
    showPasswordButton.setContentAreaFilled(false);
    showPasswordButton.setBorderPainted(false);
    showPasswordButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
    showPasswordButton.addActionListener(
        e -> {
          if (showPasswordButton.isSelected()) {
            passwordField.setEchoChar((char) 0);
          } else {
            passwordField.setEchoChar('•');
          }
        });
    passwordLayeredPane.add(
        showPasswordButton, JLayeredPane.PALETTE_LAYER);

    passwordLayeredPane.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent e) {
            passwordField.setBounds(
                0, 0, passwordLayeredPane.getWidth(), passwordLayeredPane.getHeight());
            final int buttonWidth = 24;
            final int buttonHeight = passwordLayeredPane.getHeight();
            showPasswordButton.setBounds(
                passwordLayeredPane.getWidth() - buttonWidth - 2, 0, buttonWidth, buttonHeight);
          }
        });
    return passwordLayeredPane;
  }

  public GenerationConfig getConfiguration() {
    String url = urlField.getText().trim();
    final String database = databaseField.getText().trim();

    if (url.startsWith("jdbc:sqlserver")) {
      url = url.replaceAll("databaseName=[^;]+;?", "");
      if (!url.endsWith(";")) {
        url += ";";
      }
      url += "databaseName=" + database;
    } else {
      url = url.endsWith("/") ? url + database : url + "/" + database;
    }

    return GenerationConfig.builder()
        .url(url)
        .user(userField.getText().trim())
        .password(new String(passwordField.getPassword()))
        .schema(schemaField.getText().trim())
        .rowsPerTable((Integer) rowsSpinner.getValue())
        .deferred(deferredBox.isSelected())
        // Pass through loaded values
        .softDeleteColumns(loadedSoftDeleteColumns)
        .softDeleteUseSchemaDefault(loadedSoftDeleteUseSchemaDefault)
        .softDeleteValue(loadedSoftDeleteValue)
        .build();
  }

  public Map<String, Map<String, String>> getSelectionByTable() {
    return Collections.emptyMap();
  }

  public Map<String, List<String>> getExcludedColumnsByTable() {
    return Collections.emptyMap();
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
