/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.util;

import com.luisppb16.dbseed.config.DriverInfo;
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
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class DriverLoader {

  private static final Path DRIVER_DIR =
      Paths.get(System.getProperty("user.home"), ".db-seed-plugin", "drivers");

  static {
    try {
      Files.createDirectories(DRIVER_DIR);
    } catch (final IOException e) {
      log.error("Could not create driver folder: {}", DRIVER_DIR, e);
      throw new DriverInitializationException("Could not create driver folder: " + DRIVER_DIR, e);
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
    try (final URLClassLoader cl =
        new URLClassLoader(new URL[] {jarUrl}, DriverLoader.class.getClassLoader())) {
      final Class<?> clazz = Class.forName(driverClass, true, cl);
      if (clazz.getDeclaredConstructor().newInstance() instanceof Driver driver) {
        DriverManager.registerDriver(new DriverShim(driver));
        log.info("Driver {} loaded successfully from {}", driverClass, jarUrl);
      } else {
        throw new IllegalArgumentException("Class " + driverClass + " is not a Driver");
      }
    } catch (final IOException e) {
      log.error("Error closing URLClassLoader for driver {}: {}", driverClass, e.getMessage(), e);
      throw new DriverLoadingException("Error closing URLClassLoader for driver " + driverClass, e);
    }
  }
}
