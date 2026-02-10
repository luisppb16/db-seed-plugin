/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import java.util.Map;

/**
 * Immutable record representing a pending foreign key update operation in the DBSeed plugin ecosystem.
 * <p>
 * This record class represents a deferred foreign key update that needs to be applied after
 * initial data insertion. It captures the essential information required to update foreign
 * key columns with appropriate values once the referenced primary key values become available.
 * This mechanism is essential for handling circular foreign key dependencies and non-nullable
 * foreign key constraints that cannot be satisfied during the initial data generation phase.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Storing table name for the pending update operation</li>
 *   <li>Tracking foreign key column values that need to be updated</li>
 *   <li>Storing primary key values that serve as references for the foreign keys</li>
 *   <li>Providing immutable data structure for thread-safe operations</li>
 *   <li>Enabling deferred constraint resolution in complex schema scenarios</li>
 *   <li>Facilitating proper referential integrity maintenance across table dependencies</li>
 * </ul>
 * </p>
 * <p>
 * The implementation uses Java Records to ensure immutability and follows the principle of
 * least privilege by encapsulating only the essential data needed for deferred updates.
 * The class is designed to work in conjunction with the ForeignKeyResolver to handle
 * complex foreign key scenarios where immediate value assignment is not possible due to
 * circular dependencies or constraint ordering requirements.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
 * @param table The name of the table containing the foreign key columns to be updated
 * @param fkValues A mapping of foreign key column names to their intended values
 * @param pkValues A mapping of primary key column names to their corresponding values that will be referenced
 */
public record PendingUpdate(
    String table, Map<String, Object> fkValues, Map<String, Object> pkValues) {}
