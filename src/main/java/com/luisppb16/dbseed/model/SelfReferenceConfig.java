/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.Objects;
import lombok.Builder;

/**
 * Immutable configuration for self-referencing and circular foreign key generation strategies in
 * the DBSeed plugin.
 *
 * <p>Pairs a {@link SelfReferenceStrategy} with an optional {@code hierarchyDepth} parameter used
 * exclusively by {@link SelfReferenceStrategy#HIERARCHY}. A canonical {@link #NONE_CONFIG}
 * constant is provided to avoid repeated allocations when no special handling is required.
 *
 * @param strategy      The generation strategy to apply to a self-referencing or cyclic table.
 * @param hierarchyDepth Number of levels when strategy is {@link SelfReferenceStrategy#HIERARCHY}.
 *                       Must be ≥ 1 for HIERARCHY; ignored for other strategies.
 * @author Luis Paolo Pepe Barra (@LuisPPB16)
 * @since 1.4.0
 */
@Builder
public record SelfReferenceConfig(SelfReferenceStrategy strategy, int hierarchyDepth) {

  /** Canonical no-op config; equivalent to strategy = {@link SelfReferenceStrategy#NONE}. */
  public static final SelfReferenceConfig NONE_CONFIG =
      new SelfReferenceConfig(SelfReferenceStrategy.NONE, 0);

  public SelfReferenceConfig {
    Objects.requireNonNull(strategy, "Strategy cannot be null");
    if (strategy == SelfReferenceStrategy.HIERARCHY && hierarchyDepth < 1) {
      throw new IllegalArgumentException(
          "hierarchyDepth must be >= 1 for HIERARCHY strategy, got: " + hierarchyDepth);
    }
  }
}

