/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.db;

import java.util.Map;

public record PendingUpdate(
    String table, Map<String, Object> fkValues, Map<String, Object> pkValues) {}
