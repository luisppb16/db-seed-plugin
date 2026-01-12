/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.util;

/**
 * Exception thrown when there is an issue initializing the driver loading mechanism, such as
 * failing to create the driver storage directory.
 */
public class DriverInitializationException extends RuntimeException {

  public DriverInitializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
