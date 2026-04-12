/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.config;

/**
 * Immutable data record representing database driver metadata for the DBSeed plugin ecosystem.
 *
 * <p>This record class encapsulates all the essential information required to identify, download,
 * and configure a database driver for use with the DBSeed plugin. It contains metadata for locating
 * the driver in Maven repositories, connection configuration templates, and behavioral flags that
 * determine UI requirements for different database systems. The class serves as a central data
 * model for driver management operations including dynamic driver loading and connection setup.
 *
 * @param name The human-readable name of the database driver
 * @param mavenGroupId The Maven group ID for the driver artifact
 * @param mavenArtifactId The Maven artifact ID for the driver
 * @param version The version of the driver to download
 * @param driverClass The fully qualified class name of the JDBC driver
 * @param urlTemplate The connection URL template for this database type
 * @param requiresDatabaseName Whether this driver requires a database name parameter
 * @param requiresUser Whether this driver requires a username parameter
 * @param requiresPassword Whether this driver requires a password parameter
 * @param requiresSchema Whether this driver requires a schema parameter
 * @param dialect The name of the dialect properties file (without extension) for SQL generation
 */
public record DriverInfo(
    String name,
    String mavenGroupId,
    String mavenArtifactId,
    String version,
    String driverClass,
    String urlTemplate,
    boolean requiresDatabaseName,
    boolean requiresUser,
    boolean requiresPassword,
    boolean requiresSchema,
    String dialect) {

  public static DriverInfo forDialect(final String dialect) {
    return new DriverInfo(null, null, null, null, null, null, false, false, false, false, dialect);
  }

  public static DriverInfo withDriverMeta(final String driverClass, final String urlTemplate) {
    return new DriverInfo(
        null, null, null, null, driverClass, urlTemplate, false, false, false, false, null);
  }
}
