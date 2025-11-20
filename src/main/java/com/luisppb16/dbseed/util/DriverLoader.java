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

  public static void ensureDriverPresent(DriverInfo info) throws Exception {
    Path jarPath = DRIVER_DIR.resolve(info.mavenArtifactId() + "-" + info.version() + ".jar");

    if (!Files.exists(jarPath)) {
      try {
        downloadDriver(info, jarPath);
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }

    loadDriver(jarPath.toUri().toURL(), info.driverClass());
  }

  private static void downloadDriver(DriverInfo info, Path target)
      throws IOException, URISyntaxException {
    String base = "https://repo1.maven.org/maven2/";
    String groupPath = info.mavenGroupId().replace('.', '/');
    String jarFile = info.mavenArtifactId() + "-" + info.version() + ".jar";

    String url =
        base + groupPath + "/" + info.mavenArtifactId() + "/" + info.version() + "/" + jarFile;

    try (InputStream in = new URI(url).toURL().openStream()) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void loadDriver(URL jarUrl, String driverClass) throws Exception {
    URLClassLoader cl = new URLClassLoader(new URL[] {jarUrl}, DriverLoader.class.getClassLoader());
    Class<?> clazz = Class.forName(driverClass, true, cl);
    if (clazz.getDeclaredConstructor().newInstance() instanceof Driver driver) {
      DriverManager.registerDriver(new DriverShim(driver));
    } else {
      throw new IllegalArgumentException("Class " + driverClass + " is not a Driver");
    }
  }
}
