/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Getter
@Setter
@State(name = "DbSeedProjectState", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public class DbSeedProjectState implements PersistentStateComponent<DbSeedProjectState> {

  private List<ConnectionProfile> profiles = new ArrayList<>();
  private String activeProfileName = "";

  public static DbSeedProjectState getInstance(@NotNull Project project) {
    return project.getService(DbSeedProjectState.class);
  }

  @Nullable
  @Override
  public DbSeedProjectState getState() {
    return this;
  }

  @Override
  public void loadState(@NotNull DbSeedProjectState state) {
    XmlSerializerUtil.copyBean(state, this);
    sanitizeProfiles();
  }

  public void sanitizeProfiles() {
    profiles.removeIf(profile -> profile == null || !profile.hasValidName());
    for (ConnectionProfile profile : profiles) {
      profile.setName(profile.getName().trim());
    }

    if (!ConnectionProfile.isValidName(activeProfileName)) {
      activeProfileName = "";
      return;
    }

    String normalizedActiveName = activeProfileName.trim();
    boolean activeExists =
        profiles.stream()
            .map(ConnectionProfile::getName)
            .filter(Objects::nonNull)
            .anyMatch(normalizedActiveName::equals);
    activeProfileName = activeExists ? normalizedActiveName : "";
  }
}
