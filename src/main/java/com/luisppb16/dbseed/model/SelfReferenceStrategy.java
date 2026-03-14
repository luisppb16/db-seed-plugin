/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

/**
 * Strategy enum for handling self-referencing and circular foreign key relationships during data
 * generation in the DBSeed plugin.
 *
 * <ul>
 *   <li>{@link #NONE} – Default behaviour: no special handling applied. The existing deferred /
 *       pending-update mechanism is used.
 *   <li>{@link #CIRCULAR} – Every generated row points to a <em>different</em> row in the same
 *       table, forming a valid cycle graph with no self-loops. Requires ≥ 2 rows per table;
 *       validated at generation time.
 *   <li>{@link #HIERARCHY} – Rows are distributed across {@code hierarchyDepth} levels. Level-0
 *       rows are roots (parent FK = {@code null} or self-pointing if the column is NOT NULL). Each
 *       subsequent level points to a random row from the preceding level.
 * </ul>
 *
 * @author Luis Paolo Pepe Barra (@LuisPPB16)
 * @since 1.4.0
 */
public enum SelfReferenceStrategy {

  /** No special self-reference handling. Uses the existing deferred / pending-update mechanism. */
  NONE,

  /**
   * Assigns each row a random parent from the same table, guaranteeing no self-loops. All rows
   * form a single connected cycle (Sattolo derangement). Requires ≥ 2 rows per table.
   */
  CIRCULAR,

  /**
   * Distributes rows into configurable depth levels. Roots (level 0) receive a {@code null} parent
   * (or a self-pointing update for NOT-NULL columns). Each deeper level points to a random row from
   * the level above.
   */
  HIERARCHY;

  /** Returns {@code true} when the strategy uses a hierarchy depth parameter. */
  public boolean requiresDepth() {
    return this == HIERARCHY;
  }
}

