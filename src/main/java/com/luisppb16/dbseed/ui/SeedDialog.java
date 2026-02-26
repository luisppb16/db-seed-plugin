/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.luisppb16.dbseed.config.ConnectionConfigPersistence;
import com.luisppb16.dbseed.config.DbSeedSettingsState;
import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.config.GenerationConfig;
import com.luisppb16.dbseed.ui.util.ComponentUtils;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.text.ParseException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Interactive database connection configuration wizard for the DBSeed plugin. */
public final class SeedDialog extends DialogWrapper {

  public static final int BACK_EXIT_CODE = NEXT_USER_EXIT_CODE + 1;
  private static final String DEFAULT_POSTGRES_USER = "postgres";
  private static final String DEFAULT_POSTGRES_URL = "jdbc:postgresql://localhost:5432/postgres";
  private static final String DEFAULT_SCHEMA = "public";
  private static final String JDBC_SQLSERVER_PREFIX = "jdbc:sqlserver";

  private final DriverInfo driverInfo;

  private final JBTextField urlField;
  private final JBTextField databaseField = new JBTextField();
  private final JBTextField userField = new JBTextField();
  private final JPasswordField passwordField = new JPasswordField();
  private final JBTextField schemaField = new JBTextField();
  private final JSpinner rowsSpinner;
  private final JCheckBox deferredBox = new JCheckBox("Enable deferred constraints");

  private String loadedSoftDeleteColumns;
  private boolean loadedSoftDeleteUseSchemaDefault;
  private String loadedSoftDeleteValue;
  private int loadedNumericScale;

  public SeedDialog(@NotNull final DriverInfo driverInfo) {
    super(true);
    this.driverInfo = driverInfo;
    setTitle("Connection Settings - Step 2/3");

    String template =
        Objects.nonNull(driverInfo.urlTemplate()) ? driverInfo.urlTemplate() : DEFAULT_POSTGRES_URL;
    urlField = new JBTextField(template);
    urlField.getEmptyText().setText(extractBaseUrl(template));
    urlField.setToolTipText("JDBC connection URL, e.g.: jdbc:postgresql://localhost:5432/");

    databaseField.getEmptyText().setText("Database name");
    databaseField.setToolTipText("Name of the target database.");

    userField.getEmptyText().setText(DEFAULT_POSTGRES_USER);
    userField.setToolTipText("Database user for authentication.");

    passwordField.setToolTipText("Database password for authentication.");

    schemaField.getEmptyText().setText(DEFAULT_SCHEMA);
    schemaField.setToolTipText("Database schema to introspect (e.g. 'public').");

    final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    rowsSpinner =
        new JSpinner(new SpinnerNumberModel(10, 1, 100_000, settings.getColumnSpinnerStep()));

    deferredBox.setToolTipText(
        "When enabled, foreign key constraints are deferred to avoid insertion order issues.");

    loadConfiguration(driverInfo.urlTemplate());

    ComponentUtils.configureSpinnerArrowKeyControls(rowsSpinner);

    setOKButtonText("Next");
    init();
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {new BackAction(), getOKAction(), getCancelAction()};
  }

  @Override
  protected void doOKAction() {
    try {
      rowsSpinner.commitEdit();
    } catch (ParseException ignored) {
      // Invalid number typed; spinner retains its last valid value.
    }
    super.doOKAction();
    saveConfiguration();
  }

  private void loadConfiguration(@Nullable final String urlTemplate) {
    final Project project = getCurrentProject();
    if (Objects.isNull(project)) {
      return;
    }

    final GenerationConfig savedConfig = ConnectionConfigPersistence.load(project);
    final UrlResolution urlResolution = resolveUrl(urlTemplate, savedConfig);

    prefillUrlFields(urlResolution.urlToUse(), urlResolution.usingSavedConfig());
    populateFields(savedConfig, urlResolution.usingSavedConfig());
  }

  private void prefillUrlFields(final String urlToUse, final boolean usingSavedConfig) {
    if (urlToUse.startsWith(JDBC_SQLSERVER_PREFIX)) {
      prefillSqlServer(urlToUse);
    } else if (urlToUse.startsWith("jdbc:sqlite")) {
      urlField.setText(urlToUse);
      databaseField.setText("");
    } else {
      prefillGenericUrl(urlToUse, usingSavedConfig);
    }
  }

  private void prefillSqlServer(final String urlToUse) {
    String dbName = "";
    String urlWithoutDb = urlToUse;

    final Pattern p = Pattern.compile("databaseName=([^;]+)");
    final Matcher m = p.matcher(urlToUse);
    if (m.find()) {
      dbName = m.group(1);
      urlWithoutDb = urlToUse.replace(m.group(0), "").replace(";;", ";");
      if (urlWithoutDb.endsWith(";")) {
        urlWithoutDb = urlWithoutDb.substring(0, urlWithoutDb.length() - 1);
      }
    }
    urlField.setText(urlWithoutDb);
    databaseField.setText(dbName);
  }

  private void prefillGenericUrl(final String urlToUse, final boolean usingSavedConfig) {
    final int lastSlashIndex = urlToUse.lastIndexOf('/');
    if (lastSlashIndex > 0 && lastSlashIndex < urlToUse.length() - 1) {
      urlField.setText(urlToUse.substring(0, lastSlashIndex + 1));
      databaseField.setText(urlToUse.substring(lastSlashIndex + 1));
    } else {
      urlField.setText(urlToUse);
      if (!usingSavedConfig && urlToUse.endsWith("/")) {
        databaseField.setText("");
      }
    }
  }

  private void populateFields(final GenerationConfig savedConfig, final boolean useSavedConfig) {
    if (useSavedConfig) {
      populateFieldsFromConfig(savedConfig);
    } else {
      populateFieldsWithDefaults(savedConfig);
    }
  }

  private void populateFieldsFromConfig(final GenerationConfig config) {
    userField.setText(Objects.requireNonNullElse(config.user(), DEFAULT_POSTGRES_USER));
    passwordField.setText(Objects.requireNonNullElse(config.password(), ""));
    schemaField.setText(Objects.requireNonNullElse(config.schema(), DEFAULT_SCHEMA));
    rowsSpinner.setValue(config.rowsPerTable());
    deferredBox.setSelected(config.deferred());

    loadedSoftDeleteColumns = config.softDeleteColumns();
    loadedSoftDeleteUseSchemaDefault = config.softDeleteUseSchemaDefault();
    loadedSoftDeleteValue = config.softDeleteValue();
    loadedNumericScale = config.numericScale() >= 0 ? config.numericScale() : 2;
  }

  private void populateFieldsWithDefaults(final GenerationConfig config) {
    userField.setText(DEFAULT_POSTGRES_USER);
    passwordField.setText("");
    schemaField.setText(DEFAULT_SCHEMA);
    rowsSpinner.setValue(config.rowsPerTable() > 0 ? config.rowsPerTable() : 10);
    deferredBox.setSelected(config.deferred());

    final DbSeedSettingsState globalSettings = DbSeedSettingsState.getInstance();
    loadedSoftDeleteColumns = globalSettings.getSoftDeleteColumns();
    loadedSoftDeleteUseSchemaDefault = globalSettings.isSoftDeleteUseSchemaDefault();
    loadedSoftDeleteValue = globalSettings.getSoftDeleteValue();
    loadedNumericScale = 2;
  }

  private UrlResolution resolveUrl(
      @Nullable final String urlTemplate, final GenerationConfig savedConfig) {
    String urlToUse = urlTemplate;
    boolean usingSavedConfig = false;

    if (Objects.isNull(urlToUse)) {
      urlToUse = Objects.requireNonNullElse(savedConfig.url(), DEFAULT_POSTGRES_URL);
      usingSavedConfig = true;
    } else if (Objects.nonNull(savedConfig.url())
        && isSameDriverType(urlTemplate, savedConfig.url())) {
      urlToUse = savedConfig.url();
      usingSavedConfig = true;
    }
    return new UrlResolution(urlToUse, usingSavedConfig);
  }

  private String extractBaseUrl(String url) {
    if (url.startsWith(JDBC_SQLSERVER_PREFIX)) {
      String stripped = url.replaceAll("databaseName=[^;]+;?", "").replace(";;", ";");
      return stripped.endsWith(";") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }
    int lastSlash = url.lastIndexOf('/');
    if (lastSlash > 0 && lastSlash < url.length() - 1) {
      return url.substring(0, lastSlash + 1);
    }
    return url;
  }

  private boolean isSameDriverType(String template, String savedUrl) {
    if (Objects.isNull(template) || Objects.isNull(savedUrl)) return false;
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
    if (Objects.nonNull(project)) {
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
    final FormBuilder builder = FormBuilder.createFormBuilder();

    builder.addLabeledComponent("JDBC URL:", urlField);

    if (driverInfo.requiresUser()) {
      builder.addLabeledComponent("User:", userField);
    }

    if (driverInfo.requiresPassword()) {
      builder.addLabeledComponent("Password:", createPasswordFieldWithToggle());
    }

    if (driverInfo.requiresDatabaseName()) {
      builder.addLabeledComponent("Database:", databaseField);
    }

    if (driverInfo.requiresSchema()) {
      builder.addLabeledComponent("Schema:", schemaField);
    }

    builder.addLabeledComponent("Rows per table:", rowsSpinner);
    builder.addComponent(deferredBox);

    return builder.getPanel();
  }

  private JPanel createPasswordFieldWithToggle() {
    final JPanel panel = new JPanel(new BorderLayout(0, 0));
    panel.add(passwordField, BorderLayout.CENTER);

    final JToggleButton showPasswordButton = createShowPasswordButton();
    panel.add(showPasswordButton, BorderLayout.EAST);

    return panel;
  }

  private JToggleButton createShowPasswordButton() {
    final JToggleButton showPasswordButton = new JToggleButton(AllIcons.Actions.Show);
    showPasswordButton.setFocusPainted(false);
    showPasswordButton.setContentAreaFilled(false);
    showPasswordButton.setBorderPainted(false);
    showPasswordButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
    showPasswordButton.setToolTipText("Toggle password visibility");
    showPasswordButton.addActionListener(
        e -> {
          if (showPasswordButton.isSelected()) {
            passwordField.setEchoChar((char) 0);
          } else {
            passwordField.setEchoChar('•');
          }
        });
    return showPasswordButton;
  }

  public GenerationConfig getConfiguration() {
    String url = urlField.getText().trim();
    final String database = databaseField.getText().trim();

    if (driverInfo.requiresDatabaseName()) {
      if (url.startsWith(JDBC_SQLSERVER_PREFIX)) {
        url = url.replaceAll("databaseName=[^;]+;?", "");
        if (!url.endsWith(";")) {
          url += ";";
        }
        url += "databaseName=" + database;
      } else {
        url = url.endsWith("/") ? url + database : url + "/" + database;
      }
    }

    return GenerationConfig.builder()
        .url(url)
        .user(driverInfo.requiresUser() ? userField.getText().trim() : null)
        .password(driverInfo.requiresPassword() ? new String(passwordField.getPassword()) : null)
        .schema(driverInfo.requiresSchema() ? schemaField.getText().trim() : null)
        .rowsPerTable((Integer) rowsSpinner.getValue())
        .deferred(deferredBox.isSelected())
        .softDeleteColumns(loadedSoftDeleteColumns)
        .softDeleteUseSchemaDefault(loadedSoftDeleteUseSchemaDefault)
        .softDeleteValue(loadedSoftDeleteValue)
        .numericScale(loadedNumericScale)
        .build();
  }

  private record UrlResolution(String urlToUse, boolean usingSavedConfig) {}

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
