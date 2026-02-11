/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db.dialect;

/**
 * Concrete dialect implementation that loads all behavior from a {@code .properties} file.
 * <p>
 * When no specific resource name is provided, it falls back to {@code standard.properties}
 * which contains sensible ANSI SQL defaults.
 * </p>
 */
public final class StandardDialect extends AbstractDialect {

  public StandardDialect() {
    super("standard.properties");
  }

  public StandardDialect(String resourceName) {
    super(resourceName);
  }
}
