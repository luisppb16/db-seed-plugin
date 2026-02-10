/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.Map;
import java.util.Set;

/**
 * Immutable representation of a data repetition rule for database seeding operations in the DBSeed plugin.
 * <p>
 * This record class defines rules for repeating data patterns during the seeding process,
 * allowing for controlled duplication of records with specific value constraints. It enables
 * users to specify how many times certain data patterns should be repeated and which columns
 * should maintain fixed values or constant random values across the repetition batch.
 * The rule provides fine-grained control over data generation patterns for complex scenarios.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Defining the number of times a data pattern should be repeated</li>
 *   <li>Specifying fixed values for specific columns during repetition</li>
 *   <li>Identifying columns that should maintain constant random values across repetitions</li>
 *   <li>Providing structured configuration for complex data generation scenarios</li>
 *   <li>Ensuring immutability and thread safety for concurrent access</li>
 * </ul>
 * </p>
 * <p>
 * The implementation uses immutable collections to ensure data integrity and follows
 * defensive programming practices. The class represents a single repetition rule that
 * can be applied to a specific table during the data generation process, allowing for
 * sophisticated data pattern control while maintaining consistency across repeated records.
 * </p>
 *
 * @author Luis Pepe
 * @version 1.0
 * @since 2024
 * @param count The number of times to repeat the data pattern
 * @param fixedValues A mapping of column names to specific values that should remain fixed during repetition
 * @param randomConstantColumns A set of column names that should have randomly generated values that remain constant across repetitions
 */
public record RepetitionRule(
    int count,
    Map<String, Object> fixedValues, // Column -> Specific Value
    Set<String> randomConstantColumns // Columns that should be random but constant for this batch
    ) {}
