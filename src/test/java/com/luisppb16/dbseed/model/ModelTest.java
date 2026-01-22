/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelTest {

  @Test
  @DisplayName("Table record validation")
  void tableValidation() {
    assertThatThrownBy(() -> Table.builder().name(null).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Table name cannot be null");

    // Should verify non-null lists
    Table.TableBuilder builder = Table.builder().name("test");
    // Leaving others null
    assertThatThrownBy(() -> builder.build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Column record validation")
  void columnValidation() {
    assertThatThrownBy(() -> Column.builder().name(null).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Column name cannot be null");
  }

  @Test
  @DisplayName("Column helpers")
  void columnHelpers() {
    Column c = Column.builder().name("c").allowedValues(Set.of("A")).build();
    assertThat(c.hasAllowedValues()).isTrue();

    Column c2 = Column.builder().name("c").build();
    assertThat(c2.hasAllowedValues()).isFalse();
  }
}
