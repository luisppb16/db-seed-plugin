/*
 * *****************************************************************************
 *  * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 *  * All rights reserved.
 *  *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intellij.openapi.project.Project;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DbSeedProjectStateTest {

  private DbSeedProjectState state;

  @BeforeEach
  void setUp() {
    state = new DbSeedProjectState();
  }

  @Test
  void initialState_hasEmptyProfilesAndDefaultActiveProfile() {
    assertThat(state.getProfiles()).isEmpty();
    assertThat(state.getActiveProfileName()).isEmpty();
    assertThat(state.getState()).isSameAs(state);
  }

  @Test
  void loadState_copiesProfilesAndActiveName() {
    DbSeedProjectState other = new DbSeedProjectState();
    ConnectionProfile profile = new ConnectionProfile();
    profile.setName("SavedProfile");
    other.getProfiles().add(profile);
    other.setActiveProfileName("SavedProfile");

    state.loadState(other);

    assertThat(state.getProfiles()).hasSize(1);
    assertThat(state.getProfiles().get(0).getName()).isEqualTo("SavedProfile");
    assertThat(state.getActiveProfileName()).isEqualTo("SavedProfile");
  }

  @Test
  void loadState_removesBlankProfilesAndInvalidActiveName() {
    DbSeedProjectState other = new DbSeedProjectState();

    ConnectionProfile blankProfile = new ConnectionProfile();
    blankProfile.setName("   ");

    ConnectionProfile validProfile = new ConnectionProfile();
    validProfile.setName("  SavedProfile  ");

    other.setProfiles(new ArrayList<>(List.of(blankProfile, validProfile)));
    other.setActiveProfileName("   ");

    state.loadState(other);

    assertThat(state.getProfiles()).hasSize(1);
    assertThat(state.getProfiles().get(0).getName()).isEqualTo("SavedProfile");
    assertThat(state.getActiveProfileName()).isEmpty();
  }

  @Test
  void getInstance_returnsProjectService() {
    Project mockProject = mock(Project.class);
    DbSeedProjectState mockState = new DbSeedProjectState();
    when(mockProject.getService(DbSeedProjectState.class)).thenReturn(mockState);

    DbSeedProjectState result = DbSeedProjectState.getInstance(mockProject);
    assertThat(result).isSameAs(mockState);
  }
}
