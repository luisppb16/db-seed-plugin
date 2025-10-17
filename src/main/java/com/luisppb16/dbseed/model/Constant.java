/*
 *
 *  * Copyright (c) 2025 Luis Pepe.
 *  * All rights reserved.
 *
 */

package com.luisppb16.dbseed.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Constant {
  APP_NAME("Seed4SQL"),
  NOTIFICATION_ID("DB Seed Generator");

  private final String value;
}
