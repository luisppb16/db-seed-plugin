/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.util;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A dynamic JDBC driver wrapper facilitating runtime driver registration in the DBSeed plugin.
 *
 * <p>This record-based implementation serves as a transparent proxy that wraps dynamically loaded
 * JDBC drivers, enabling their registration with the DriverManager despite potential class loader
 * isolation issues. The DriverShim addresses the challenge of integrating third-party JDBC drivers
 * that are loaded at runtime rather than being available in the application's classpath during
 * initialization.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Providing a bridge between the application's DriverManager and dynamically loaded drivers
 *   <li>Delegating all JDBC interface method calls to the wrapped driver instance
 *   <li>Enabling seamless integration of external database drivers without compile-time
 *       dependencies
 *   <li>Maintaining full JDBC specification compliance through transparent delegation
 *   <li>Facilitating database connectivity for diverse database systems through dynamic loading
 * </ul>
 *
 * <p>The implementation leverages Java's record feature for immutability and concise syntax,
 * ensuring thread-safe operation and preventing accidental state modification. All method
 * implementations perform direct delegation to the underlying driver, preserving the original
 * driver's behavior and capabilities.
 *
 * @param driver The underlying JDBC driver instance to wrap and delegate to
 */
record DriverShim(Driver driver) implements Driver {
  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    return driver.connect(url, info);
  }

  @Override
  public boolean acceptsURL(String url) throws SQLException {
    return driver.acceptsURL(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
    return driver.getPropertyInfo(url, info);
  }

  @Override
  public int getMajorVersion() {
    return driver.getMajorVersion();
  }

  @Override
  public int getMinorVersion() {
    return driver.getMinorVersion();
  }

  @Override
  public boolean jdbcCompliant() {
    return driver.jdbcCompliant();
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return driver.getParentLogger();
  }
}
