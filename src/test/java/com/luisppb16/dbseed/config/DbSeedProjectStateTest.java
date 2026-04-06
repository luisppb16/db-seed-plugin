/*
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
    assertThat(state.getActiveProfileName()).isEqualTo("Default");
    assertThat(state.getState()).isSameAs(state);
  }

  @Test
  void loadState_copiesProfilesAndActiveName() {
    DbSeedProjectState newState = new DbSeedProjectState();

    ConnectionProfile profile = new ConnectionProfile();
    profile.setName("MyTestProfile");
    profile.setUrl("jdbc:foo");
    List<ConnectionProfile> profiles = new ArrayList<>();
    profiles.add(profile);

    newState.setProfiles(profiles);
    newState.setActiveProfileName("MyTestProfile");

    state.loadState(newState);

    assertThat(state.getActiveProfileName()).isEqualTo("MyTestProfile");
    assertThat(state.getProfiles()).hasSize(1);
    assertThat(state.getProfiles().get(0).getName()).isEqualTo("MyTestProfile");
    assertThat(state.getProfiles().get(0).getUrl()).isEqualTo("jdbc:foo");
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
