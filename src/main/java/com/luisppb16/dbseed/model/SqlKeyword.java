/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

/**
 * Represents SQL keywords that can be used as special values in data generation.
 * This allows the data generator to recognize and handle specific SQL constructs,
 * such as using the DEFAULT keyword to let the database engine provide a value.
 */
public enum SqlKeyword {
  DEFAULT
}
