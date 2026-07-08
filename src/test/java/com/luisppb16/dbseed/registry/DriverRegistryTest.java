/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luisppb16.dbseed.config.DriverInfo;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class DriverRegistryTest {

  @Test
  void getDrivers_isNotEmpty() {
    assertThat(DriverRegistry.getDrivers()).isNotEmpty();
  }

  @Test
  void getDrivers_returnsSameInstanceOnEveryCall() {
    assertThat(DriverRegistry.getDrivers()).isSameAs(DriverRegistry.getDrivers());
  }

  @Test
  void getDrivers_returnsImmutableList() {
    final List<DriverInfo> drivers = DriverRegistry.getDrivers();
    // El IDE recomienda que la lambda tenga solo una invocación que pueda lanzar excepción
    assertThatThrownBy(
            () -> {
              drivers.add(DriverInfo.forDialect("fake"));
            })
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void everyDriver_hasMandatoryMetadata() {
    assertThat(DriverRegistry.getDrivers())
        .allSatisfy(
            driver -> {
              assertThat(driver.name()).as("name of %s", driver).isNotNull().isNotEmpty();
              assertThat(driver.mavenGroupId())
                  .as("mavenGroupId of driver '%s'", driver.name())
                  .isNotNull()
                  .isNotEmpty();
              assertThat(driver.mavenArtifactId())
                  .as("mavenArtifactId of driver '%s'", driver.name())
                  .isNotNull()
                  .isNotEmpty();
              assertThat(driver.version())
                  .as("version of driver '%s'", driver.name())
                  .isNotNull()
                  .isNotEmpty();
              assertThat(driver.driverClass())
                  .as("driverClass of driver '%s'", driver.name())
                  .isNotNull()
                  .isNotEmpty();
              assertThat(driver.urlTemplate())
                  .as("urlTemplate of driver '%s'", driver.name())
                  .isNotNull()
                  .isNotEmpty();
            });
  }

  @Test
  void driverNames_areUnique() {
    assertThat(DriverRegistry.getDrivers()).extracting(DriverInfo::name).doesNotHaveDuplicates();
  }

  @Test
  void everyUrlTemplate_startsWithJdbcPrefix() {
    assertThat(DriverRegistry.getDrivers())
        .allSatisfy(
            driver ->
                assertThat(driver.urlTemplate())
                    .as("urlTemplate of driver '%s'", driver.name())
                    .startsWith("jdbc:"));
  }

  @Test
  void everyDeclaredDialect_matchesAnExistingDialectResource() {
    for (final DriverInfo driver : DriverRegistry.getDrivers()) {
      final String dialect = driver.dialect();
      if (Objects.isNull(dialect) || dialect.isEmpty()) {
        continue;
      }
      final URL resource =
          DriverRegistryTest.class.getResource("/dialects/" + dialect + ".properties");
      assertThat(resource)
          .as("dialect resource '/dialects/%s.properties' for driver '%s'", dialect, driver.name())
          .isNotNull();
    }
  }
}
