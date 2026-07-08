/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GenerationConfigTest {

  private static GenerationConfig baseConfig(final int rowsPerTable, final int numericScale) {
    return new GenerationConfig(
        "jdbc:h2:mem:test",
        "sa",
        "secret",
        "public",
        rowsPerTable,
        true,
        "deleted_at",
        false,
        "NULL",
        numericScale);
  }

  @Test
  void constructor_keepsValidValues() {
    final GenerationConfig config = baseConfig(10, 4);

    assertThat(config.url()).isEqualTo("jdbc:h2:mem:test");
    assertThat(config.user()).isEqualTo("sa");
    assertThat(config.password()).isEqualTo("secret");
    assertThat(config.schema()).isEqualTo("public");
    assertThat(config.rowsPerTable()).isEqualTo(10);
    assertThat(config.deferred()).isTrue();
    assertThat(config.softDeleteColumns()).isEqualTo("deleted_at");
    assertThat(config.softDeleteUseSchemaDefault()).isFalse();
    assertThat(config.softDeleteValue()).isEqualTo("NULL");
    assertThat(config.numericScale()).isEqualTo(4);
  }

  @Test
  void constructor_canonicalizesZeroRowsPerTableToOne() {
    assertThat(baseConfig(0, 2).rowsPerTable()).isEqualTo(1);
  }

  @Test
  void constructor_canonicalizesNegativeRowsPerTableToOne() {
    assertThat(baseConfig(-5, 2).rowsPerTable()).isEqualTo(1);
  }

  @Test
  void constructor_canonicalizesNegativeNumericScaleToTwo() {
    assertThat(baseConfig(10, -1).numericScale()).isEqualTo(2);
  }

  @Test
  void constructor_keepsZeroNumericScale() {
    assertThat(baseConfig(10, 0).numericScale()).isZero();
  }

  @Test
  void withSoftDeleteSettings_preservesConnectionAndGenerationFields() {
    final GenerationConfig original = baseConfig(25, 3);

    final GenerationConfig updated = original.withSoftDeleteSettings("is_deleted", true, "0", 6);

    assertThat(updated.url()).isEqualTo(original.url());
    assertThat(updated.user()).isEqualTo(original.user());
    assertThat(updated.password()).isEqualTo(original.password());
    assertThat(updated.schema()).isEqualTo(original.schema());
    assertThat(updated.rowsPerTable()).isEqualTo(original.rowsPerTable());
    assertThat(updated.deferred()).isEqualTo(original.deferred());
  }

  @Test
  void withSoftDeleteSettings_replacesSoftDeleteFieldsAndNumericScale() {
    final GenerationConfig original = baseConfig(25, 3);

    final GenerationConfig updated = original.withSoftDeleteSettings("is_deleted", true, "0", 6);

    assertThat(updated.softDeleteColumns()).isEqualTo("is_deleted");
    assertThat(updated.softDeleteUseSchemaDefault()).isTrue();
    assertThat(updated.softDeleteValue()).isEqualTo("0");
    assertThat(updated.numericScale()).isEqualTo(6);
  }

  @Test
  void withSoftDeleteSettings_doesNotMutateOriginal() {
    final GenerationConfig original = baseConfig(25, 3);

    original.withSoftDeleteSettings("is_deleted", true, "0", 6);

    assertThat(original.softDeleteColumns()).isEqualTo("deleted_at");
    assertThat(original.softDeleteUseSchemaDefault()).isFalse();
    assertThat(original.softDeleteValue()).isEqualTo("NULL");
    assertThat(original.numericScale()).isEqualTo(3);
  }

  @Test
  void withSoftDeleteSettings_appliesNumericScaleCanonicalization() {
    final GenerationConfig updated =
        baseConfig(25, 3).withSoftDeleteSettings("is_deleted", true, "0", -4);

    assertThat(updated.numericScale()).isEqualTo(2);
  }
}
