/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.model;

/**
 * Represents SQL keywords that can be used as special values in data generation within the DBSeed
 * plugin.
 *
 * <p>This enum defines reserved SQL keywords that have special meaning during the data seeding
 * process. These keywords allow the data generator to recognize and handle specific SQL constructs,
 * such as using the DEFAULT keyword to let the database engine provide a value instead of
 * generating synthetic data. This enables more flexible and database-aware data generation
 * strategies.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Defining SQL keywords with special semantics in data generation
 *   <li>Enabling database engine delegation for default value handling
 *   <li>Supporting type-safe representation of SQL special values
 *   <li>Facilitating integration with database-specific default behaviors
 * </ul>
 *
 * <p>The implementation provides a foundation for extending support to additional SQL keywords as
 * needed, maintaining type safety and preventing hard-coded string literals throughout the
 * codebase.
 */
public enum SqlKeyword {
  DEFAULT
}
