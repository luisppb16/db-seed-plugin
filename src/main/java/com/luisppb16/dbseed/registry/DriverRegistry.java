/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luisppb16.dbseed.config.DriverInfo;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class DriverRegistry {

  private static final List<DriverInfo> DRIVERS;

  static {
    List<DriverInfo> temp;
    try (InputStream in = DriverRegistry.class.getResourceAsStream("/drivers.json")) {
      if (in == null) {
        throw new IllegalStateException("drivers.json not found in resources.");
      }
      ObjectMapper mapper = new ObjectMapper();
      temp = mapper.readValue(in, new TypeReference<>() {});
    } catch (Exception e) {
      log.error(Arrays.toString(e.getStackTrace()));
      temp = Collections.emptyList();
    }
    DRIVERS = List.copyOf(temp);
  }

  public static List<DriverInfo> getDrivers() {
    return DRIVERS;
  }
}
