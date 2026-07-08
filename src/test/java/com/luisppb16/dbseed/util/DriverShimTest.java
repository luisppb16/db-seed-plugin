/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DriverShimTest {

  private static final String URL = "jdbc:h2:mem:test";

  private Driver delegate;
  private DriverShim shim;

  @BeforeEach
  void setUp() {
    delegate = mock(Driver.class);
    shim = new DriverShim(delegate);
  }

  @Test
  void driverAccessor_returnsWrappedDriver() {
    assertThat(shim.driver()).isSameAs(delegate);
  }

  @Test
  void connect_delegatesToWrappedDriver() throws SQLException {
    final Connection connection = mock(Connection.class);
    final Properties info = new Properties();
    when(delegate.connect(URL, info)).thenReturn(connection);

    assertThat(shim.connect(URL, info)).isSameAs(connection);
    verify(delegate).connect(URL, info);
  }

  @Test
  void acceptsURL_delegatesToWrappedDriver() throws SQLException {
    when(delegate.acceptsURL(URL)).thenReturn(true);

    assertThat(shim.acceptsURL(URL)).isTrue();
    verify(delegate).acceptsURL(URL);
  }

  @Test
  void getPropertyInfo_delegatesToWrappedDriver() throws SQLException {
    final DriverPropertyInfo[] propertyInfo = {new DriverPropertyInfo("user", "sa")};
    final Properties info = new Properties();
    when(delegate.getPropertyInfo(URL, info)).thenReturn(propertyInfo);

    assertThat(shim.getPropertyInfo(URL, info)).isSameAs(propertyInfo);
    verify(delegate).getPropertyInfo(URL, info);
  }

  @Test
  void getMajorVersion_delegatesToWrappedDriver() {
    when(delegate.getMajorVersion()).thenReturn(4);

    assertThat(shim.getMajorVersion()).isEqualTo(4);
    verify(delegate).getMajorVersion();
  }

  @Test
  void getMinorVersion_delegatesToWrappedDriver() {
    when(delegate.getMinorVersion()).thenReturn(7);

    assertThat(shim.getMinorVersion()).isEqualTo(7);
    verify(delegate).getMinorVersion();
  }

  @Test
  void jdbcCompliant_delegatesToWrappedDriver() {
    when(delegate.jdbcCompliant()).thenReturn(true);

    assertThat(shim.jdbcCompliant()).isTrue();
    verify(delegate).jdbcCompliant();
  }

  @Test
  void getParentLogger_delegatesToWrappedDriver() throws SQLFeatureNotSupportedException {
    final Logger logger = Logger.getLogger("driver-shim-test");
    when(delegate.getParentLogger()).thenReturn(logger);

    assertThat(shim.getParentLogger()).isSameAs(logger);
    verify(delegate).getParentLogger();
  }
}
