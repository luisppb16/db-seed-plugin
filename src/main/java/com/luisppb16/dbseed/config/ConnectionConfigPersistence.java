/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/** Persistence utility for managing database connection configurations in IntelliJ projects. */
@Slf4j
@UtilityClass
public class ConnectionConfigPersistence {

  public static void saveProfile(@NotNull final Project project, @NotNull String profileName, @NotNull final GenerationConfig config) {
    DbSeedProjectState state = DbSeedProjectState.getInstance(project);
    ConnectionProfile profile = state.getProfiles().stream()
        .filter(p -> p.getName().equals(profileName))
        .findFirst()
        .orElseGet(() -> {
          ConnectionProfile newProfile = new ConnectionProfile();
          newProfile.setName(profileName);
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

    state.setActiveProfileName(profileName);

    final CredentialAttributes credAttributes = createCredentialAttributes(project, profileName);
    PasswordSafe.getInstance()
        .set(credAttributes, new Credentials(config.user(), config.password()));

    log.info("Connection configuration saved for profile {} in project {}.", profileName, project.getName());
  }

  public static void save(@NotNull final Project project, @NotNull final GenerationConfig config) {
    saveProfile(project, "Default", config);
  }

  @NotNull
  public static GenerationConfig loadProfile(@NotNull final Project project, @NotNull String profileName) {
    DbSeedProjectState state = DbSeedProjectState.getInstance(project);
    Optional<ConnectionProfile> profileOpt = state.getProfiles().stream()
        .filter(p -> p.getName().equals(profileName))
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

    final CredentialAttributes credAttributes = createCredentialAttributes(project, profileName);
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

  private static CredentialAttributes createCredentialAttributes(@NotNull final Project project, @NotNull String profileName) {
    return new CredentialAttributes(
        CredentialAttributesKt.generateServiceName("DBSeed", project.getName() + "-" + profileName));
  }

  // To keep backward compatibility or avoid modifying other method signatures if they expect simply `project`
  private static CredentialAttributes createCredentialAttributes(@NotNull final Project project) {
    return createCredentialAttributes(project, "Default");
  }
}
