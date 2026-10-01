/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.luisppb16.dbseed.ai.AiClient;
import com.luisppb16.dbseed.ai.AiClientFactory;
import com.luisppb16.dbseed.ai.AiProvider;
import com.luisppb16.dbseed.ui.util.ComponentUtils;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;

/**
 * Redesigned UI component for configuring global settings of the DBSeed plugin.
 *
 * <p>Organized with a tabbed interface grouping related settings: General, Dictionaries, Soft
 * Delete, AI, and Advanced. Clean, minimalist design with clear visual hierarchy.
 */
public class DbSeedSettingsComponent {

  private final JPanel myMainPanel;
  private final JSpinner myColumnSpinnerStep = new JSpinner(new SpinnerNumberModel(1, 1, 10, 1));
  private final TextFieldWithBrowseButton myDefaultOutputDirectory =
      new TextFieldWithBrowseButton();

  private final JBCheckBox myUseLatinDictionary =
      new JBCheckBox("Use Latin dictionary (Faker default)");
  private final JBCheckBox myUseEnglishDictionary = new JBCheckBox("Use English dictionary");
  private final JBCheckBox myUseSpanishDictionary = new JBCheckBox("Use Spanish dictionary");

  private final JBTextField mySoftDeleteColumns = new JBTextField();
  private final JBCheckBox mySoftDeleteUseSchemaDefault =
      new JBCheckBox("Use schema default value");
  private final JBTextField mySoftDeleteValue = new JBTextField();

  private final JBCheckBox myUseAiGeneration = new JBCheckBox("Enable AI-based data generation");
  private final JBTextArea myAiApplicationContext = new JBTextArea(3, 20);
  private final JSpinner myAiWordCount = new JSpinner(new SpinnerNumberModel(1, 1, 500, 1));
  private final JSpinner myAiRequestTimeout =
      new JSpinner(new SpinnerNumberModel(120, 10, 600, 10));
  private final JBCheckBox myAiParallelGeneration =
      new JBCheckBox("Generate AI columns in parallel");
  private final JSpinner myAiGenerationThreads = new JSpinner(new SpinnerNumberModel(2, 1, 16, 1));
  private final JBTextField myAiUrl = new JBTextField();
  private final ComboBox<String> myAiModelDropdown = new ComboBox<>();
  private final JButton myRefreshModelsButton = new JButton("Get models");
  private final AsyncProcessIcon myLoadingIcon = new AsyncProcessIcon("AiLoading");
  private final Map<AiProvider, JRadioButton> myProviderButtons = new LinkedHashMap<>();
  private final JPanel myAiProviderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
  private final JBLabel myAiApiKeyLabel = new JBLabel("Password / API key:");
  private final JBPasswordField myAiApiKey = new JBPasswordField();
  private final JBLabel myAiApiKeyHint = hintLabel();
  private final Project myProject;
  private final CollectionListModel<String> myProfilesModel = new CollectionListModel<>();
  private final JBList<String> myProfilesList = new JBList<>(myProfilesModel);
  private final List<String> originalProfiles = new ArrayList<>();
  private volatile boolean disposed = false;

  public DbSeedSettingsComponent(final Project project) {
    this.myProject = project;
    final DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    myColumnSpinnerStep.setValue(settings.getColumnSpinnerStep());
    myDefaultOutputDirectory.setText(settings.getDefaultOutputDirectory());
    myUseLatinDictionary.setSelected(settings.isUseLatinDictionary());
    myUseEnglishDictionary.setSelected(settings.isUseEnglishDictionary());
    myUseSpanishDictionary.setSelected(settings.isUseSpanishDictionary());

    mySoftDeleteColumns.setText(settings.getSoftDeleteColumns());
    mySoftDeleteUseSchemaDefault.setSelected(settings.isSoftDeleteUseSchemaDefault());
    mySoftDeleteValue.setText(settings.getSoftDeleteValue());
    mySoftDeleteValue.setEnabled(!settings.isSoftDeleteUseSchemaDefault());

    myUseAiGeneration.setSelected(settings.isUseAiGeneration());
    myAiApplicationContext.setText(settings.getAiApplicationContext());
    myAiWordCount.setValue(settings.getAiWordCount());
    myAiRequestTimeout.setValue(settings.getAiRequestTimeoutSeconds());
    myAiParallelGeneration.setSelected(settings.isAiParallelGeneration());
    myAiGenerationThreads.setValue(settings.getAiGenerationThreads());
    myAiApplicationContext.setLineWrap(true);
    myAiApplicationContext.setWrapStyleWord(true);
    myAiApplicationContext.setBorder(JBUI.Borders.empty(4));
    configureAiProviderButtons();
    setAiProvider(settings.getAiProvider());
    setAiUrl(settings.getAiUrl());
    myAiApiKey.setText(AiApiKeyStore.load(getAiProvider()));

    if (Objects.nonNull(settings.getAiModel()) && !settings.getAiModel().isEmpty()) {
      myAiModelDropdown.addItem(settings.getAiModel());
      myAiModelDropdown.setSelectedItem(settings.getAiModel());
    }

    mySoftDeleteUseSchemaDefault.addActionListener(
        e -> mySoftDeleteValue.setEnabled(!mySoftDeleteUseSchemaDefault.isSelected()));

    myRefreshModelsButton.addActionListener(e -> refreshModels());
    myLoadingIcon.setVisible(false);

    updateAiFieldsEnabled(settings.isUseAiGeneration());
    myUseAiGeneration.addActionListener(e -> updateAiFieldsEnabled(myUseAiGeneration.isSelected()));
    myAiParallelGeneration.addActionListener(
        e ->
            updateAiParallelFieldsEnabled(
                myAiParallelGeneration.isSelected() && myUseAiGeneration.isSelected()));

    configureFolderChooser(myDefaultOutputDirectory);

    // Create tabbed interface
    final JBTabbedPane tabbedPane = new JBTabbedPane();
    tabbedPane.addTab("General", createGeneralTab());
    tabbedPane.addTab("Profiles", createProfilesTab());
    tabbedPane.addTab("Dictionaries", createDictionariesTab());
    tabbedPane.addTab("Soft Delete", createSoftDeleteTab());
    tabbedPane.addTab("AI", createAiTab());
    tabbedPane.addTab("Advanced", createAdvancedTab());

    myMainPanel = new JPanel(new BorderLayout());
    myMainPanel.add(tabbedPane, BorderLayout.CENTER);
    myMainPanel.add(ComponentUtils.createVersionLabel(), BorderLayout.SOUTH);
    myMainPanel.setPreferredSize(JBUI.size(600, 600));
  }

  /** Context help line under a field: the muted style the other descriptions in this tab use. */
  private static JBLabel hintLabel() {
    final JBLabel label = new JBLabel();
    label.setForeground(UIUtil.getContextHelpForeground());
    label.setFont(JBUI.Fonts.smallFont());
    label.setBorder(JBUI.Borders.emptyLeft(16));
    return label;
  }

  /**
   * What the credential field expects from the selected engine, and where that engine hands it out.
   * The Ollama branch is empty because that engine has no credential at all and its row is hidden;
   * the branch stays so the switch keeps covering every engine.
   */
  private static String apiKeyHintFor(final AiProvider provider) {
    return switch (provider) {
      case OLLAMA -> "";
      case UNSLOTH_STUDIO ->
          "<html>Paste either the API key Unsloth Studio hands out under <i>Settings → API</i> "
              + "(shown only once), or the password you sign in to Unsloth Studio with: a password is "
              + "exchanged for a session token, which is what Unsloth Studio accepts. Leave this empty "
              + "only if <i>Keyless API access</i> is enabled there.</html>";
      case OPENAI_COMPATIBLE ->
          "<html>Only if the server asks for one; LM Studio and llama.cpp usually accept an empty "
              + "key.</html>";
    };
  }

  private JComponent createGeneralTab() {
    final JPanel panel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Column spinner step:"), myColumnSpinnerStep, 1, false)
            .addTooltip("Step value for spinner controls in data configuration dialogs.")
            .addVerticalGap(8)
            .addLabeledComponent(
                new JBLabel("Default output directory:"), myDefaultOutputDirectory, 1, false)
            .addTooltip("Location where generated SQL scripts are saved by default.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    final JBScrollPane scrollPane = new JBScrollPane(panel);
    scrollPane.setBorder(JBUI.Borders.empty());
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    return scrollPane;
  }

  private JComponent createProfilesTab() {
    myProfilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    originalProfiles.clear();
    myProfilesModel.removeAll();

    // Read-only view of the project state: mutations must only happen in applyProfileSettings().
    if (myProject != null) {
      final DbSeedProjectState projectState = DbSeedProjectState.getInstance(myProject);
      if (projectState != null) {
        projectState.getProfiles().stream()
            .filter(Objects::nonNull)
            .filter(ConnectionProfile::hasValidName)
            .map(p -> p.getName().trim())
            .forEach(
                name -> {
                  originalProfiles.add(name);
                  myProfilesModel.add(name);
                });
      }
    }

    final JPanel listPanel =
        ToolbarDecorator.createDecorator(myProfilesList)
            .disableAddAction()
            .setRemoveAction(
                button -> {
                  final int selectedIndex = myProfilesList.getSelectedIndex();
                  if (selectedIndex >= 0) {
                    myProfilesModel.remove(selectedIndex);
                  }
                })
            .createPanel();

    final JBLabel description =
        new JBLabel(
            "<html>Manage your saved database connection profiles for this project."
                + "<br/>You can remove obsolete profiles here. Create new ones from the generator dialog.</html>");
    description.setForeground(UIUtil.getContextHelpForeground());
    description.setFont(JBUI.Fonts.smallFont());
    description.setBorder(JBUI.Borders.emptyBottom(12));

    final JPanel panel =
        FormBuilder.createFormBuilder()
            .addComponent(description)
            .addComponentFillVertically(listPanel, 0)
            .getPanel();

    final JBScrollPane scrollPane = new JBScrollPane(panel);
    scrollPane.setBorder(JBUI.Borders.empty());
    return scrollPane;
  }

  public boolean isProfileModified() {
    if (myProject == null) return false;
    final List<String> currentList = myProfilesModel.getItems();
    if (currentList.size() != originalProfiles.size()) return true;
    for (int i = 0; i < currentList.size(); i++) {
      if (!currentList.get(i).equals(originalProfiles.get(i))) return true;
    }
    return false;
  }

  public void applyProfileSettings() {
    if (myProject == null) return;
    final DbSeedProjectState projectState = DbSeedProjectState.getInstance(myProject);
    if (projectState == null) return;

    final List<String> currentList =
        myProfilesModel.getItems().stream()
            .filter(ConnectionProfile::isValidName)
            .map(String::trim)
            .toList();
    final List<ConnectionProfile> toKeep =
        new ArrayList<>(
            projectState.getProfiles().stream()
                .filter(Objects::nonNull)
                .filter(ConnectionProfile::hasValidName)
                .filter(p -> currentList.contains(p.getName().trim()))
                .peek(p -> p.setName(p.getName().trim()))
                .toList());
    projectState.setProfiles(toKeep);
    if (toKeep.isEmpty()) {
      projectState.setActiveProfileName("");
    } else if (toKeep.stream()
        .noneMatch(p -> p.getName().equals(projectState.getActiveProfileName()))) {
      projectState.setActiveProfileName(toKeep.get(0).getName());
    }

    originalProfiles.clear();
    originalProfiles.addAll(currentList);
  }

  public void resetProfileSettings() {
    if (myProject == null) return;
    myProfilesModel.removeAll();
    final DbSeedProjectState projectState = DbSeedProjectState.getInstance(myProject);
    if (projectState != null) {
      for (final ConnectionProfile profile : projectState.getProfiles()) {
        if (profile != null && profile.hasValidName()) {
          profile.setName(profile.getName().trim());
          myProfilesModel.add(profile.getName());
        }
      }
    }
  }

  private JComponent createDictionariesTab() {
    final JBLabel description =
        new JBLabel(
            "<html>Select which dictionaries to use for generating realistic string values."
                + "<br/>Mix and match for contextually appropriate data.</html>");
    description.setForeground(UIUtil.getContextHelpForeground());
    description.setFont(JBUI.Fonts.smallFont());
    description.setBorder(JBUI.Borders.emptyBottom(12));

    final JPanel panel =
        FormBuilder.createFormBuilder()
            .addComponent(description)
            .addComponent(myUseLatinDictionary, 1)
            .addComponent(myUseEnglishDictionary, 1)
            .addComponent(myUseSpanishDictionary, 1)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    final JBScrollPane scrollPane = new JBScrollPane(panel);
    scrollPane.setBorder(JBUI.Borders.empty());
    return scrollPane;
  }

  private JComponent createSoftDeleteTab() {
    final JBLabel description =
        new JBLabel(
            "<html>Configure how soft-deleted records are marked in the generated data."
                + "<br/>Typically uses a column like 'deleted_at' or 'is_deleted'.</html>");
    description.setForeground(UIUtil.getContextHelpForeground());
    description.setFont(JBUI.Fonts.smallFont());
    description.setBorder(JBUI.Borders.emptyBottom(12));

    final JPanel panel =
        FormBuilder.createFormBuilder()
            .addComponent(description)
            .addLabeledComponent(
                new JBLabel("Columns (comma separated):"), mySoftDeleteColumns, 1, false)
            .addVerticalGap(8)
            .addComponent(mySoftDeleteUseSchemaDefault, 1)
            .addLabeledComponent(
                new JBLabel("Value (if not default):"), mySoftDeleteValue, 1, false)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    final JBScrollPane scrollPane = new JBScrollPane(panel);
    scrollPane.setBorder(JBUI.Borders.empty());
    return scrollPane;
  }

  private JComponent createAiTab() {
    final JBLabel description =
        new JBLabel(
            "<html>Use a local or cloud AI engine to generate context-aware data."
                + "<br/>Ensure the selected server is running and accessible at the specified"
                + " URL.</html>");
    description.setForeground(UIUtil.getContextHelpForeground());
    description.setFont(JBUI.Fonts.smallFont());
    description.setBorder(JBUI.Borders.emptyBottom(12));

    final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    buttonPanel.add(myRefreshModelsButton);
    buttonPanel.add(myLoadingIcon);

    final JPanel urlPanel = new JPanel(new BorderLayout(5, 0));
    urlPanel.add(myAiUrl, BorderLayout.CENTER);
    urlPanel.add(buttonPanel, BorderLayout.EAST);

    final JBScrollPane contextScrollPane = new JBScrollPane(myAiApplicationContext);
    contextScrollPane.setPreferredSize(JBUI.size(0, 80));
    contextScrollPane.setMinimumSize(JBUI.size(0, 60));

    final JBLabel wordCountDesc =
        new JBLabel(
            "<html>Number of words the AI generates per value (1 = word, higher = sentences).</html>");
    wordCountDesc.setForeground(UIUtil.getContextHelpForeground());
    wordCountDesc.setFont(JBUI.Fonts.smallFont());
    wordCountDesc.setBorder(JBUI.Borders.emptyLeft(16));

    final JBLabel timeoutDesc =
        new JBLabel(
            "<html>Max wait per AI request. Increase for slow hardware. If exceeded, "
                + "fallback to random values.</html>");
    timeoutDesc.setForeground(UIUtil.getContextHelpForeground());
    timeoutDesc.setFont(JBUI.Fonts.smallFont());
    timeoutDesc.setBorder(JBUI.Borders.emptyLeft(16));

    final JBLabel parallelDesc =
        new JBLabel(
            "<html>Generate several AI columns at once. Only worth it when the AI server "
                + "answers more than one request at a time; otherwise the requests just queue "
                + "up.</html>");
    parallelDesc.setForeground(UIUtil.getContextHelpForeground());
    parallelDesc.setFont(JBUI.Fonts.smallFont());
    parallelDesc.setBorder(JBUI.Borders.emptyLeft(16));

    final JPanel serverConfigPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Engine:"), myAiProviderPanel, 1, false)
            .addLabeledComponent(new JBLabel("Server URL:"), urlPanel, 1, false)
            .addLabeledComponent(new JBLabel("Model:"), myAiModelDropdown, 1, false)
            .addLabeledComponent(myAiApiKeyLabel, myAiApiKey, 1, false)
            .addComponent(myAiApiKeyHint, 0)
            .getPanel();

    final JPanel aiBehaviorPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Words per value:"), myAiWordCount, 1, false)
            .addComponent(wordCountDesc, 0)
            .addVerticalGap(8)
            .addLabeledComponent(
                new JBLabel("Request timeout (seconds):"), myAiRequestTimeout, 1, false)
            .addComponent(timeoutDesc, 0)
            .addVerticalGap(8)
            .addComponent(myAiParallelGeneration, 1)
            .addComponent(parallelDesc, 0)
            .addLabeledComponent(new JBLabel("Parallel threads:"), myAiGenerationThreads, 1, false)
            .getPanel();

    final JPanel contextPanel =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Domain description:"), contextScrollPane, 1, false)
            .getPanel();

    final JPanel panel =
        FormBuilder.createFormBuilder()
            .addComponent(description)
            .addComponent(myUseAiGeneration, 1)
            .addVerticalGap(12)
            .addComponent(new TitledSeparator("Server Configuration"))
            .addVerticalGap(4)
            .addComponent(serverConfigPanel)
            .addVerticalGap(12)
            .addComponent(new TitledSeparator("AI Behavior"))
            .addVerticalGap(4)
            .addComponent(aiBehaviorPanel)
            .addVerticalGap(12)
            .addComponent(new TitledSeparator("Application Context"))
            .addVerticalGap(4)
            .addComponent(contextPanel)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    final JBScrollPane scrollPane = new JBScrollPane(panel);
    scrollPane.setBorder(JBUI.Borders.empty());
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    return scrollPane;
  }

  private JComponent createAdvancedTab() {
    final JBLabel placeholder =
        new JBLabel(
            "<html><b>Reserved for future advanced features.</b><br/>"
                + "This section will contain experimental options and power-user settings.</html>");
    placeholder.setForeground(UIUtil.getContextHelpForeground());

    final JPanel panel =
        FormBuilder.createFormBuilder()
            .addComponent(placeholder)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

    final JBScrollPane scrollPane = new JBScrollPane(panel);
    scrollPane.setBorder(JBUI.Borders.empty());
    return scrollPane;
  }

  private void updateAiFieldsEnabled(final boolean enabled) {
    myAiApplicationContext.setEnabled(enabled);
    myAiWordCount.setEnabled(enabled);
    myAiRequestTimeout.setEnabled(enabled);
    myAiUrl.setEnabled(enabled);
    myAiModelDropdown.setEnabled(enabled);
    myRefreshModelsButton.setEnabled(enabled);
    myAiParallelGeneration.setEnabled(enabled);
    myProviderButtons.values().forEach(button -> button.setEnabled(enabled));
    // Ollama has no authentication at all, so its credential row is taken out of the form
    // instead of being left there disabled; the other engines keep it, because Unsloth Studio
    // requires a credential and an OpenAI-compatible server may or may not ask for one.
    final boolean apiKeyVisible = getAiProvider() != AiProvider.OLLAMA;
    myAiApiKeyLabel.setVisible(apiKeyVisible);
    myAiApiKey.setVisible(apiKeyVisible);
    myAiApiKeyHint.setVisible(apiKeyVisible);
    myAiApiKeyLabel.setEnabled(enabled);
    myAiApiKey.setEnabled(enabled);
    myAiApiKeyHint.setText(apiKeyHintFor(getAiProvider()));
    // Hiding only invalidates, which does not schedule a relayout by itself: this is what
    // collapses the row. It does nothing while the tab is still being built, when the field has
    // no parent yet.
    myAiApiKey.revalidate();
    updateAiParallelFieldsEnabled(enabled && myAiParallelGeneration.isSelected());
  }

  /**
   * Builds one radio button per engine, all in the same group so only one can be selected. The
   * listener is an item listener rather than an action listener because an action also fires when
   * the engine already selected is clicked again, and that would discard a credential the user
   * typed but has not applied yet.
   */
  private void configureAiProviderButtons() {
    final ButtonGroup group = new ButtonGroup();
    for (final AiProvider provider : AiProvider.values()) {
      final JRadioButton button = new JRadioButton(provider.getDisplayName());
      button.addItemListener(
          e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
              onAiProviderChanged(provider);
            }
          });
      group.add(button);
      myAiProviderPanel.add(button);
      myProviderButtons.put(provider, button);
    }
  }

  /**
   * Applies the newly selected engine's configuration: the URL is replaced by that engine's default
   * when the field is empty or still holds another engine's default, so switching away from Ollama
   * does not leave a pointed-at-the-wrong-port URL behind, and the credential field shows what that
   * engine has stored — empty when it has none — so one engine's secret is never left on screen for
   * the next one. With Ollama, which carries no credential, the whole credential row is hidden
   * instead of shown empty.
   */
  private void onAiProviderChanged(final AiProvider provider) {
    final String currentUrl = myAiUrl.getText().trim();
    final boolean holdsAnotherDefault =
        Arrays.stream(AiProvider.values())
            .anyMatch(other -> other != provider && other.getDefaultUrl().equals(currentUrl));
    if (currentUrl.isEmpty() || holdsAnotherDefault) {
      myAiUrl.setText(provider.getDefaultUrl());
    }
    myAiApiKey.setText(AiApiKeyStore.load(provider));
    updateAiFieldsEnabled(myUseAiGeneration.isSelected());
  }

  private void updateAiParallelFieldsEnabled(final boolean enabled) {
    myAiGenerationThreads.setEnabled(enabled);
  }

  private void refreshModels() {
    final String url = myAiUrl.getText().trim();
    final AiProvider provider = getAiProvider();
    if (url.isEmpty()) {
      Messages.showErrorDialog(myMainPanel, "Please enter a valid server URL.", "Invalid URL");
      return;
    }

    final AiClient client =
        AiClientFactory.create(provider, url, getAiModel(), getAiApiKey(), getAiRequestTimeout());
    if (Objects.isNull(client)) {
      Messages.showErrorDialog(myMainPanel, "Please enter a valid server URL.", "Invalid URL");
      return;
    }

    myRefreshModelsButton.setEnabled(false);
    myLoadingIcon.setVisible(true);
    myLoadingIcon.resume();
    myAiModelDropdown.setEnabled(false);

    final ModalityState currentModality = ModalityState.stateForComponent(myMainPanel);

    // Unsloth Studio signs a password in while the request is being built, so this has to run off
    // the
    // EDT: ping() and listModels() both assemble their request before returning.
    ApplicationManager.getApplication()
        .executeOnPooledThread(() -> pingAndListModels(client, provider, url, currentModality));
  }

  private void pingAndListModels(
      final AiClient client,
      final AiProvider provider,
      final String url,
      final ModalityState currentModality) {
    client
        .ping()
        .whenComplete(
            (ignored, pingEx) -> {
              if (disposed) return;
              if (Objects.nonNull(pingEx)) {
                ApplicationManager.getApplication()
                    .invokeLater(
                        () -> {
                          if (disposed) return;
                          final Throwable cause =
                              Objects.nonNull(pingEx.getCause()) ? pingEx.getCause() : pingEx;
                          Messages.showErrorDialog(
                              myMainPanel,
                              "No "
                                  + provider.getDisplayName()
                                  + " server found at "
                                  + url
                                  + ".\n"
                                  + "Ensure the server is running and the URL is correct.\n\n"
                                  + "Error: "
                                  + cause.getMessage(),
                              "Server Not Reachable");
                          resetRefreshButton();
                        },
                        currentModality);
                return;
              }
              client
                  .listModels()
                  .whenComplete(
                      (models, ex) -> {
                        if (disposed) return;
                        ApplicationManager.getApplication()
                            .invokeLater(
                                () -> {
                                  if (disposed) return;
                                  if (Objects.nonNull(ex)) {
                                    final Throwable cause =
                                        Objects.nonNull(ex.getCause()) ? ex.getCause() : ex;
                                    Messages.showErrorDialog(
                                        myMainPanel,
                                        "Could not fetch models from "
                                            + provider.getDisplayName()
                                            + " at "
                                            + url
                                            + ".\n"
                                            + "Error: "
                                            + cause.getMessage(),
                                        "Connection Error");
                                  } else {
                                    final String currentSelection =
                                        (String) myAiModelDropdown.getSelectedItem();
                                    myAiModelDropdown.removeAllItems();
                                    if (models.isEmpty()) {
                                      Messages.showWarningDialog(
                                          myMainPanel,
                                          "No models found on "
                                              + provider.getDisplayName()
                                              + ". Load or pull at least one model first.",
                                          "No Models Found");
                                    } else {
                                      models.forEach(myAiModelDropdown::addItem);
                                      if (Objects.nonNull(currentSelection)
                                          && models.contains(currentSelection)) {
                                        myAiModelDropdown.setSelectedItem(currentSelection);
                                      }
                                    }
                                  }
                                  resetRefreshButton();
                                },
                                currentModality);
                      });
            });
  }

  private void resetRefreshButton() {
    myRefreshModelsButton.setEnabled(true);
    myLoadingIcon.suspend();
    myLoadingIcon.setVisible(false);
    myAiModelDropdown.setEnabled(true);
  }

  private void configureFolderChooser(final TextFieldWithBrowseButton field) {
    final FileChooserDescriptor folderDescriptor =
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Default Output Directory");
    field.addActionListener(
        e -> {
          final String currentPath = field.getText();
          final VirtualFile currentFile =
              currentPath.isEmpty()
                  ? null
                  : LocalFileSystem.getInstance().findFileByPath(currentPath);
          FileChooser.chooseFile(
              folderDescriptor,
              myProject,
              currentFile,
              file -> {
                if (Objects.nonNull(file)) {
                  field.setText(file.getPath());
                }
              });
        });
  }

  public JPanel getPanel() {
    return myMainPanel;
  }

  public JComponent getPreferredFocusedComponent() {
    return myColumnSpinnerStep;
  }

  // ... Getter and Setter methods remain the same as original ...
  public int getColumnSpinnerStep() {
    return (Integer) myColumnSpinnerStep.getValue();
  }

  public void setColumnSpinnerStep(final int value) {
    myColumnSpinnerStep.setValue(value);
  }

  public String getDefaultOutputDirectory() {
    return myDefaultOutputDirectory.getText();
  }

  public void setDefaultOutputDirectory(final String text) {
    myDefaultOutputDirectory.setText(text);
  }

  public boolean getUseLatinDictionary() {
    return myUseLatinDictionary.isSelected();
  }

  public void setUseLatinDictionary(final boolean use) {
    myUseLatinDictionary.setSelected(use);
  }

  public boolean getUseEnglishDictionary() {
    return myUseEnglishDictionary.isSelected();
  }

  public void setUseEnglishDictionary(final boolean use) {
    myUseEnglishDictionary.setSelected(use);
  }

  public boolean getUseSpanishDictionary() {
    return myUseSpanishDictionary.isSelected();
  }

  public void setUseSpanishDictionary(final boolean use) {
    myUseSpanishDictionary.setSelected(use);
  }

  public String getSoftDeleteColumns() {
    return mySoftDeleteColumns.getText();
  }

  public void setSoftDeleteColumns(final String columns) {
    mySoftDeleteColumns.setText(columns);
  }

  public boolean getSoftDeleteUseSchemaDefault() {
    return mySoftDeleteUseSchemaDefault.isSelected();
  }

  public void setSoftDeleteUseSchemaDefault(final boolean useDefault) {
    mySoftDeleteUseSchemaDefault.setSelected(useDefault);
  }

  public String getSoftDeleteValue() {
    return mySoftDeleteValue.getText();
  }

  public void setSoftDeleteValue(final String value) {
    mySoftDeleteValue.setText(value);
  }

  public boolean getUseAiGeneration() {
    return myUseAiGeneration.isSelected();
  }

  public void setUseAiGeneration(final boolean use) {
    myUseAiGeneration.setSelected(use);
  }

  public String getAiApplicationContext() {
    return myAiApplicationContext.getText();
  }

  public void setAiApplicationContext(final String context) {
    myAiApplicationContext.setText(context);
  }

  public int getAiWordCount() {
    return (Integer) myAiWordCount.getValue();
  }

  public void setAiWordCount(final int words) {
    myAiWordCount.setValue(words);
  }

  public int getAiRequestTimeout() {
    return (Integer) myAiRequestTimeout.getValue();
  }

  public void setAiRequestTimeout(final int seconds) {
    myAiRequestTimeout.setValue(seconds);
  }

  public boolean getAiParallelGeneration() {
    return myAiParallelGeneration.isSelected();
  }

  public void setAiParallelGeneration(final boolean parallel) {
    myAiParallelGeneration.setSelected(parallel);
  }

  public int getAiGenerationThreads() {
    return (Integer) myAiGenerationThreads.getValue();
  }

  public void setAiGenerationThreads(final int threads) {
    myAiGenerationThreads.setValue(threads);
  }

  public AiProvider getAiProvider() {
    return myProviderButtons.entrySet().stream()
        .filter(entry -> entry.getValue().isSelected())
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(AiProvider.OLLAMA);
  }

  public void setAiProvider(final AiProvider provider) {
    myProviderButtons
        .get(Objects.requireNonNullElse(provider, AiProvider.OLLAMA))
        .setSelected(true);
  }

  public String getAiUrl() {
    return myAiUrl.getText();
  }

  public void setAiUrl(final String url) {
    myAiUrl.setText(url);
  }

  public String getAiModel() {
    final Object selected = myAiModelDropdown.getSelectedItem();
    return selected instanceof String ? (String) selected : "";
  }

  public void setAiModel(final String model) {
    myAiModelDropdown.removeAllItems();
    myAiModelDropdown.addItem(model);
    myAiModelDropdown.setSelectedItem(model);
  }

  public String getAiApiKey() {
    return new String(myAiApiKey.getPassword());
  }

  public void setAiApiKey(final String apiKey) {
    myAiApiKey.setText(Objects.requireNonNullElse(apiKey, ""));
  }

  public void dispose() {
    disposed = true;
    Disposer.dispose(myLoadingIcon);
  }
}
