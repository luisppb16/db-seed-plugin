/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConnectionProfileTest {

  @Test
  void testDefaultValues() {
    ConnectionProfile profile = new ConnectionProfile();

    assertThat(profile.getName()).isEqualTo("");
    assertThat(profile.getUrl()).isEmpty();
    assertThat(profile.getUser()).isEmpty();
    assertThat(profile.getSchema()).isEmpty();
    assertThat(profile.getRowsPerTable()).isEqualTo(10);
    assertThat(profile.isDeferred()).isFalse();
    assertThat(profile.getSoftDeleteColumns()).isEmpty();
    assertThat(profile.isSoftDeleteUseSchemaDefault()).isTrue();
    assertThat(profile.getSoftDeleteValue()).isEmpty();
    assertThat(profile.getNumericScale()).isEqualTo(2);
  }

  @Test
  void testSettersAndGetters() {
    ConnectionProfile profile = new ConnectionProfile();

    profile.setName("TestProfile");
    profile.setUrl("jdbc:sqlite:test.db");
    profile.setUser("testUser");
    profile.setSchema("testSchema");
    profile.setRowsPerTable(50);
    profile.setDeferred(true);
    profile.setSoftDeleteColumns("deleted_at");
    profile.setSoftDeleteUseSchemaDefault(false);
    profile.setSoftDeleteValue("current_timestamp");
    profile.setNumericScale(5);

    assertThat(profile.getName()).isEqualTo("TestProfile");
    assertThat(profile.getUrl()).isEqualTo("jdbc:sqlite:test.db");
    assertThat(profile.getUser()).isEqualTo("testUser");
    assertThat(profile.getSchema()).isEqualTo("testSchema");
    assertThat(profile.getRowsPerTable()).isEqualTo(50);
    assertThat(profile.isDeferred()).isTrue();
    assertThat(profile.getSoftDeleteColumns()).isEqualTo("deleted_at");
    assertThat(profile.isSoftDeleteUseSchemaDefault()).isFalse();
    assertThat(profile.getSoftDeleteValue()).isEqualTo("current_timestamp");
    assertThat(profile.getNumericScale()).isEqualTo(5);
  }
}
