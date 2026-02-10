/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import lombok.Builder;

/**
 * Immutable data record representing database driver metadata for the DBSeed plugin ecosystem.
 * <p>
 * This record class encapsulates all the essential information required to identify,
 * download, and configure a database driver for use with the DBSeed plugin. It contains
 * metadata for locating the driver in Maven repositories, connection configuration
 * templates, and behavioral flags that determine UI requirements for different database
 * systems. The class serves as a central data model for driver management operations
 * including dynamic driver loading and connection setup.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Storing Maven coordinates for driver artifact identification and retrieval</li>
 *   <li>Providing connection URL templates for different database systems</li>
 *   <li>Defining UI requirements for database connection parameters</li>
 *   <li>Representing driver-specific configuration needs and behaviors</li>
 *   <li>Enabling dynamic driver loading and configuration in the plugin</li>
 *   <li>Providing immutable data structure for thread-safe operations</li>
 * </ul>
 * </p>
 * <p>
 * The implementation uses Java Records to ensure immutability and provides a builder
 * pattern through Lombok annotations for flexible instantiation. The class includes
 * flags to indicate which connection parameters are required by each specific driver,
 * enabling the UI to show only the necessary input fields for each database system.
 * This allows for a consistent user experience across different database vendors while
 * adapting to their specific connection requirements.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
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
 */
@Builder
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
    boolean requiresSchema) {}
