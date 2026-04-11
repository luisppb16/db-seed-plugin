/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.util;

/**
 * Exception thrown when there is an issue initializing the driver loading mechanism in the DBSeed
 * plugin.
 *
 * <p>This runtime exception is raised during the setup phase of the JDBC driver management system,
 * typically when the plugin fails to create necessary directories, load configuration files, or
 * establish the required infrastructure for dynamic driver loading. It serves as a specific error
 * type to distinguish driver initialization failures from other runtime exceptions in the plugin.
 *
 * <p>Key responsibilities include:
 *
 * <ul>
 *   <li>Signaling failures in driver infrastructure setup
 *   <li>Providing detailed error messages for troubleshooting
 *   <li>Supporting exception chaining for root cause analysis
 *   <li>Enabling specific error handling for driver initialization issues
 * </ul>
 *
 * <p>The exception is typically thrown during plugin startup or when attempting to initialize the
 * driver storage system. Common causes include insufficient file system permissions, disk space
 * issues, or configuration problems that prevent the creation of the driver directory structure.
 */
public class DriverInitializationException extends RuntimeException {

  public DriverInitializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
