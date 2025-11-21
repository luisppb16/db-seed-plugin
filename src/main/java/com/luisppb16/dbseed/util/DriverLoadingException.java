/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.util;

/**
 * Exception thrown when there is an issue loading a JDBC driver,
 * such as problems with the URLClassLoader or driver registration.
 */
public class DriverLoadingException extends RuntimeException {

  public DriverLoadingException(String message, Throwable cause) {
    super(message, cause);
  }
}
