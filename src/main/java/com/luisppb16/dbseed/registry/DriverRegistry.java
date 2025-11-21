/*
 *  Copyright (c) 2025 Luis Pepe (@LuisPPB16).
 *  All rights reserved.
 */

package com.luisppb16.dbseed.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luisppb16.dbseed.config.DriverInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class DriverRegistry {

  private static final List<DriverInfo> DRIVERS;
  private static final String DRIVERS_JSON_PATH = "/drivers.json";

  static {
    List<DriverInfo> tempDrivers;
    try (final InputStream in = DriverRegistry.class.getResourceAsStream(DRIVERS_JSON_PATH)) {
      if (in == null) {
        log.error("Driver configuration file not found: {}", DRIVERS_JSON_PATH);
        throw new IllegalStateException("Driver configuration file not found: " + DRIVERS_JSON_PATH);
      }
      final ObjectMapper mapper = new ObjectMapper();
      tempDrivers = mapper.readValue(in, new TypeReference<>() {});
      log.info("Loaded {} drivers from {}", tempDrivers.size(), DRIVERS_JSON_PATH);
    } catch (final JsonProcessingException e) {
      log.error("Error parsing drivers.json: {}", e.getMessage(), e);
      tempDrivers = Collections.emptyList();
    } catch (final IOException e) {
      log.error("Error reading drivers.json: {}", e.getMessage(), e);
      tempDrivers = Collections.emptyList();
    } catch (final IllegalStateException e) {
      log.error(e.getMessage());
      tempDrivers = Collections.emptyList();
    }
    DRIVERS = List.copyOf(tempDrivers);
  }

  public static List<DriverInfo> getDrivers() {
    return DRIVERS;
  }
}
