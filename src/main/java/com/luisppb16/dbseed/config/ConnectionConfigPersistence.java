/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;
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
      @NotNull String profileName,
      @NotNull final GenerationConfig config) {
    final String normalizedProfileName = profileName.trim();
    if (!ConnectionProfile.isValidName(normalizedProfileName)) {
      log.warn(
          "Skipping saveProfile because profile name is blank for project {}.", project.getName());
      return;
    }

    DbSeedProjectState state = DbSeedProjectState.getInstance(project);
    state.getProfiles().removeIf(p -> p == null || !p.hasValidName());

    ConnectionProfile profile =
        state.getProfiles().stream()
            .filter(p -> normalizedProfileName.equals(p.getName().trim()))
            .findFirst()
            .orElseGet(
                () -> {
                  ConnectionProfile newProfile = new ConnectionProfile();
                  newProfile.setName(normalizedProfileName);
                  state.getProfiles().add(newProfile);
                  return newProfile;
                });

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
      @NotNull final Project project, @Nullable String profileName) {
    final String normalizedProfileName = profileName == null ? "" : profileName.trim();
    if (!ConnectionProfile.isValidName(normalizedProfileName)) {
      return GenerationConfig.builder()
          .url("")
          .user("")
          .password("")
          .schema("")
          .rowsPerTable(10)
          .deferred(false)
          .softDeleteUseSchemaDefault(true)
          .numericScale(2)
          .build();
    }

    DbSeedProjectState state = DbSeedProjectState.getInstance(project);
    state.getProfiles().removeIf(p -> p == null || !p.hasValidName());
    Optional<ConnectionProfile> profileOpt =
        state.getProfiles().stream()
            .filter(p -> normalizedProfileName.equals(p.getName().trim()))
            .findFirst();

    if (profileOpt.isEmpty()) {
      return GenerationConfig.builder()
          .url("")
          .user("")
          .password("")
          .schema("")
          .rowsPerTable(10)
          .deferred(false)
          .softDeleteUseSchemaDefault(true)
          .numericScale(2)
          .build();
    }

    ConnectionProfile profile = profileOpt.get();

    final CredentialAttributes credAttributes =
        createCredentialAttributes(project, normalizedProfileName);
    final Credentials credentials = PasswordSafe.getInstance().get(credAttributes);
    String password = "";
    if (Objects.nonNull(credentials)) {
      password = Objects.requireNonNullElse(credentials.getPasswordAsString(), "");
    }

    return GenerationConfig.builder()
        .url(profile.getUrl())
        .user(profile.getUser())
        .password(password)
        .schema(profile.getSchema())
        .rowsPerTable(profile.getRowsPerTable())
        .deferred(profile.isDeferred())
        .softDeleteColumns(profile.getSoftDeleteColumns())
        .softDeleteUseSchemaDefault(profile.isSoftDeleteUseSchemaDefault())
        .softDeleteValue(profile.getSoftDeleteValue())
        .numericScale(profile.getNumericScale())
        .build();
  }

  @NotNull
  public static GenerationConfig load(@NotNull final Project project) {
    DbSeedProjectState state = DbSeedProjectState.getInstance(project);
    return loadProfile(project, state.getActiveProfileName());
  }

  private static CredentialAttributes createCredentialAttributes(
      @NotNull final Project project, @NotNull String profileName) {
    return new CredentialAttributes(
        CredentialAttributesKt.generateServiceName(
            "DBSeed", project.getName() + "-" + profileName));
  }
}
