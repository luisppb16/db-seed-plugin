/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.config;

import lombok.Builder;

/**
 * Immutable configuration record for database generation parameters in the DBSeed plugin ecosystem.
 * <p>
 * This record class encapsulates all the essential parameters required for the database
 * seeding process, including connection details, generation preferences, and advanced
 * configuration options. It serves as the primary data transfer object for passing
 * generation settings between different components of the DBSeed plugin, ensuring
 * consistency and type safety throughout the generation process.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Storing database connection parameters (URL, credentials, schema)</li>
 *   <li>Defining generation parameters (rows per table, deferred constraint processing)</li>
 *   <li>Managing soft-delete column configurations and behaviors</li>
 *   <li>Specifying numeric precision and scale settings for decimal values</li>
 *   <li>Providing immutable configuration state for thread-safe operations</li>
 *   <li>Enabling builder pattern for flexible configuration construction</li>
 * </ul>
 * </p>
 * <p>
 * The implementation uses Java Records to ensure immutability and provides a builder
 * pattern through Lombok annotations for flexible instantiation. The configuration
 * includes support for advanced features such as deferred constraint processing for
 * handling circular foreign key dependencies, soft-delete column specifications for
 * handling logically deleted records, and numeric scale configuration for controlling
 * decimal precision in generated data.
 * </p>
 *
 * @param url The JDBC connection URL for the target database
 * @param user The username for database authentication
 * @param password The password for database authentication
 * @param schema The database schema to operate on
 * @param rowsPerTable The number of rows to generate per table
 * @param deferred Whether to use deferred constraint processing for circular dependencies
 * @param softDeleteColumns Comma-separated list of columns to treat as soft-delete indicators
 * @param softDeleteUseSchemaDefault Whether to use schema defaults for soft-delete values
 * @param softDeleteValue The specific value to use for soft-delete indicators
 * @param numericScale The decimal scale to use for numeric value generation
 */
@Builder(toBuilder = true)
public record GenerationConfig(
    String url,
    String user,
    String password,
    String schema,
    int rowsPerTable,
    boolean deferred,
    String softDeleteColumns,
    boolean softDeleteUseSchemaDefault,
    String softDeleteValue,
    int numericScale) {}
