/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.util;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.luisppb16.dbseed.config.DriverInfo;
import com.luisppb16.dbseed.registry.DriverRegistry;
import com.luisppb16.dbseed.ui.DriverSelectionDialog;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Comprehensive JDBC driver management system for the DBSeed plugin ecosystem.
 * <p>
 * This utility class orchestrates the complete lifecycle of JDBC drivers required by the DBSeed
 * plugin, from user selection through download, loading, and registration. It addresses the
 * complex challenge of dynamically acquiring database drivers at runtime, which is essential
 * for supporting diverse database systems without bundling all possible drivers with the plugin.
 * The class implements sophisticated caching mechanisms and integrates seamlessly with IntelliJ's
 * project management system to provide optimal user experience.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Managing user-driven driver selection through intuitive dialog interfaces</li>
 *   <li>Automated download and caching of JDBC drivers from Maven repositories</li>
 *   <li>Dynamic loading of drivers using isolated class loaders to prevent conflicts</li>
 *   <li>Registration of loaded drivers with the JDBC DriverManager via DriverShim wrapper</li>
 *   <li>Maintaining user preferences for driver selection across IDE sessions</li>
 *   <li>Implementing robust error handling and recovery mechanisms for network operations</li>
 * </ul>
 * </p>
 * <p>
 * The implementation follows security-conscious practices by isolating dynamically loaded
 * drivers in separate class loaders, preventing potential conflicts with the IDE's existing
 * classpath. The system also implements intelligent caching to minimize network traffic
 * and improve performance on subsequent accesses.
 * </p>
 */
@Slf4j
@UtilityClass
public class DriverLoader {

  private static final Path DRIVER_DIR =
      Paths.get(System.getProperty("user.home"), ".db-seed-plugin", "drivers");
  private static final String PREF_LAST_DRIVER = "dbseed.last.driver";

  static {
    try {
      Files.createDirectories(DRIVER_DIR);
    } catch (final IOException e) {
      log.error("Could not create driver folder: {}", DRIVER_DIR, e);
      throw new DriverInitializationException("Could not create driver folder: " + DRIVER_DIR, e);
    }
  }

  public static Optional<DriverInfo> selectAndLoadDriver(final Project project) {
    try {
      final List<DriverInfo> drivers = DriverRegistry.getDrivers();
      if (drivers.isEmpty()) {
        log.warn("No drivers found in drivers.json");
        return Optional.empty();
      }

      final PropertiesComponent props = PropertiesComponent.getInstance(project);
      final String lastDriverName = props.getValue(PREF_LAST_DRIVER);

      final DriverSelectionDialog driverDialog =
          new DriverSelectionDialog(project, drivers, lastDriverName);
      if (!driverDialog.showAndGet()) {
        log.debug("Driver selection canceled.");
        return Optional.empty();
      }

      final Optional<DriverInfo> chosenDriverOpt = driverDialog.getSelectedDriver();
      if (chosenDriverOpt.isEmpty()) {
        log.debug("No driver selected.");
        return Optional.empty();
      }
      final DriverInfo chosenDriver = chosenDriverOpt.get();
      props.setValue(PREF_LAST_DRIVER, chosenDriver.name());

      ensureDriverPresent(chosenDriver);
      return Optional.of(chosenDriver);
    } catch (final Exception ex) {
      log.error("Error selecting/loading driver", ex);
      return Optional.empty();
    }
  }

  public static void ensureDriverPresent(final DriverInfo info)
      throws IOException, ReflectiveOperationException, URISyntaxException, SQLException {
    final Path jarPath = DRIVER_DIR.resolve(info.mavenArtifactId() + "-" + info.version() + ".jar");

    if (!Files.exists(jarPath)) {
      downloadDriver(info, jarPath);
    }

    loadDriver(jarPath.toUri().toURL(), info.driverClass());
  }

  private static void downloadDriver(final DriverInfo info, final Path target)
      throws IOException, URISyntaxException {
    final String groupPath = info.mavenGroupId().replace('.', '/');
    final String jarFile = info.mavenArtifactId() + "-" + info.version() + ".jar";
    final String url =
        "https://repo1.maven.org/maven2/%s/%s/%s/%s"
            .formatted(groupPath, info.mavenArtifactId(), info.version(), jarFile);

    log.info("Downloading driver from: {}", url);
    try (final InputStream in = new URI(url).toURL().openStream()) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      log.info("Driver downloaded to: {}", target);
    }
  }

  private static void loadDriver(final URL jarUrl, final String driverClass)
      throws ReflectiveOperationException, SQLException {
    final URLClassLoader cl =
        new URLClassLoader(new URL[] {jarUrl}, DriverLoader.class.getClassLoader());
    final Class<?> clazz = Class.forName(driverClass, true, cl);
    if (clazz.getDeclaredConstructor().newInstance() instanceof Driver driver) {
      DriverManager.registerDriver(new DriverShim(driver));
      log.info("Driver {} loaded successfully from {}", driverClass, jarUrl);
    } else {
      throw new IllegalArgumentException("Class " + driverClass + " is not a Driver");
    }
  }
}
