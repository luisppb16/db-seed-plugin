/*
 */

package com.luisppb16.dbseed.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

class DbSeedProjectConfigurableTest {

  @Test
  void testConfigurable() {
    Project mockProject = mock(Project.class);
    DbSeedProjectConfigurable configurable = new DbSeedProjectConfigurable(mockProject);

    assertThat(configurable.getDisplayName()).isEqualTo("DBSeed Profiles");
    assertThat(configurable.createComponent()).isNotNull();
    assertThat(configurable.isModified()).isFalse();

    // Ensure no exception thrown
    configurable.apply();
  }
}
