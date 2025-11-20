/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.util;

import com.luisppb16.dbseed.config.DriverInfo;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DriverLoader {

  private static final Path DRIVER_DIR =
      Paths.get(System.getProperty("user.home"), ".db-seed-plugin", "drivers");

  static {
    try {
      Files.createDirectories(DRIVER_DIR);
    } catch (IOException e) {
      throw new RuntimeException("Could not create driver folder", e);
    }
  }

  public static void ensureDriverPresent(DriverInfo info)
      throws IOException, ReflectiveOperationException, URISyntaxException, SQLException {
    Path jarPath = DRIVER_DIR.resolve(info.mavenArtifactId().concat("-").concat(info.version()).concat(".jar"));

    if (!Files.exists(jarPath)) {
      downloadDriver(info, jarPath);
    }

    loadDriver(jarPath.toUri().toURL(), info.driverClass());
  }

  private static void downloadDriver(DriverInfo info, Path target)
      throws IOException, URISyntaxException {
    String base = "https://repo1.maven.org/maven2/";
    String groupPath = info.mavenGroupId().replace('.', '/');
    String jarFile = info.mavenArtifactId().concat("-").concat(info.version()).concat(".jar");

    String url =
        base.concat(groupPath).concat("/").concat(info.mavenArtifactId()).concat("/").concat(info.version()).concat("/").concat(jarFile);

    try (InputStream in = new URI(url).toURL().openStream()) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void loadDriver(URL jarUrl, String driverClass)
      throws ReflectiveOperationException, java.sql.SQLException {
    URLClassLoader cl = new URLClassLoader(new URL[] {jarUrl}, DriverLoader.class.getClassLoader());
    Class<?> clazz = Class.forName(driverClass, true, cl);
    if (clazz.getDeclaredConstructor().newInstance() instanceof Driver driver) {
      DriverManager.registerDriver(new DriverShim(driver));
    } else {
      throw new IllegalArgumentException("Class ".concat(driverClass).concat(" is not a Driver"));
    }
  }
}
