/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Persistence utility for managing database connection configurations in IntelliJ projects. */
@Slf4j
@UtilityClass
public class ConnectionConfigPersistence {

  public static void saveProfile(
      @NotNull final Project project,
      @NotNull final String profileName,
      @NotNull final GenerationConfig config) {
    final String normalizedProfileName = profileName.trim();
    if (!ConnectionProfile.isValidName(normalizedProfileName)) {
      log.warn(
          "Skipping saveProfile because profile name is blank for project {}.", project.getName());
      return;
    }

    final DbSeedProjectState state = DbSeedProjectState.getInstance(project);
    List<ConnectionProfile> mutableProfiles = new ArrayList<>(state.getProfiles());
    mutableProfiles.removeIf(p -> p == null || !p.hasValidName());

    final ConnectionProfile profile =
        mutableProfiles.stream()
            .filter(p -> normalizedProfileName.equals(p.getName().trim()))
            .findFirst()
            .orElseGet(
                () -> {
                  final ConnectionProfile newProfile = new ConnectionProfile();
                  newProfile.setName(normalizedProfileName);
                  mutableProfiles.add(newProfile);
                  return newProfile;
                });
    state.setProfiles(mutableProfiles);

    profile.setUrl(config.url());
    profile.setUser(config.user());
    profile.setSchema(config.schema());
    profile.setRowsPerTable(config.rowsPerTable());
    profile.setDeferred(config.deferred());
    profile.setSoftDeleteColumns(config.softDeleteColumns());
    profile.setSoftDeleteUseSchemaDefault(config.softDeleteUseSchemaDefault());
    profile.setSoftDeleteValue(config.softDeleteValue());
    profile.setNumericScale(config.numericScale());

    state.setActiveProfileName(normalizedProfileName);

    final CredentialAttributes credAttributes =
        createCredentialAttributes(project, normalizedProfileName);
    PasswordSafe.getInstance()
        .set(credAttributes, new Credentials(config.user(), config.password()));

    log.info(
        "Connection configuration saved for profile {} in project {}.",
        normalizedProfileName,
        project.getName());
  }

  @NotNull
  public static GenerationConfig loadProfile(
      @NotNull final Project project, @Nullable final String profileName) {
    final String normalizedProfileName = profileName == null ? "" : profileName.trim();
    if (!ConnectionProfile.isValidName(normalizedProfileName)) {
      return new GenerationConfig("", "", "", "", 10, false, null, true, null, 2);
    }

    final DbSeedProjectState state = DbSeedProjectState.getInstance(project);
    List<ConnectionProfile> mutableProfiles = new ArrayList<>(state.getProfiles());
    mutableProfiles.removeIf(p -> p == null || !p.hasValidName());
    state.setProfiles(mutableProfiles);

    final Optional<ConnectionProfile> profileOpt =
        mutableProfiles.stream()
            .filter(p -> normalizedProfileName.equals(p.getName().trim()))
            .findFirst();

    if (profileOpt.isEmpty()) {
      return new GenerationConfig("", "", "", "", 10, false, null, true, null, 2);
    }

    final ConnectionProfile profile = profileOpt.get();

    final CredentialAttributes credAttributes =
        createCredentialAttributes(project, normalizedProfileName);
    final Credentials credentials = PasswordSafe.getInstance().get(credAttributes);
    String password = "";
    if (Objects.nonNull(credentials)) {
      password = Objects.requireNonNullElse(credentials.getPasswordAsString(), "");
    }

    return new GenerationConfig(
        profile.getUrl(),
        profile.getUser(),
        password,
        profile.getSchema(),
        profile.getRowsPerTable(),
        profile.isDeferred(),
        profile.getSoftDeleteColumns(),
        profile.isSoftDeleteUseSchemaDefault(),
        profile.getSoftDeleteValue(),
        profile.getNumericScale());
  }

  @NotNull
  public static GenerationConfig load(@NotNull final Project project) {
    final DbSeedProjectState state = DbSeedProjectState.getInstance(project);
    return loadProfile(project, state.getActiveProfileName());
  }

  private static CredentialAttributes createCredentialAttributes(
      @NotNull final Project project, @NotNull final String profileName) {
    return new CredentialAttributes(
        CredentialAttributesKt.generateServiceName(
            "DBSeed", project.getName() + "-" + profileName));
  }
}
