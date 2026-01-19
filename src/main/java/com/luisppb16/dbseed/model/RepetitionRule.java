/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.model;

import java.util.Map;
import java.util.Set;

public record RepetitionRule(
    int count,
    Map<String, String> fixedValues, // Column -> Specific Value
    Set<String> randomConstantColumns // Columns that should be random but constant for this batch
) {}
