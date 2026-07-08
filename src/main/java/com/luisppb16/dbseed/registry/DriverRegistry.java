/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.registry;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.luisppb16.dbseed.config.DriverInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
      if (Objects.isNull(in)) {
        log.error("Driver configuration file not found: {}", DRIVERS_JSON_PATH);
        throw new IllegalStateException(
            "Driver configuration file not found: " + DRIVERS_JSON_PATH);
      }
      try (final Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        final List<DriverInfo> parsed =
            new Gson().fromJson(reader, new TypeToken<List<DriverInfo>>() {}.getType());
        tempDrivers = Objects.requireNonNullElse(parsed, Collections.emptyList());
      }
      log.info("Loaded {} drivers from {}", tempDrivers.size(), DRIVERS_JSON_PATH);
    } catch (final JsonParseException e) {
      log.error(
          "Error parsing drivers.json: {}. The driver selection dialog will be empty.",
          e.getMessage(),
          e);
      tempDrivers = Collections.emptyList();
    } catch (final IOException e) {
      log.error(
          "Error reading drivers.json: {}. The driver selection dialog will be empty.",
          e.getMessage(),
          e);
      tempDrivers = Collections.emptyList();
    } catch (final IllegalStateException e) {
      log.error("{}. The driver selection dialog will be empty.", e.getMessage());
      tempDrivers = Collections.emptyList();
    }
    DRIVERS = List.copyOf(tempDrivers);
  }

  public static List<DriverInfo> getDrivers() {
    return DRIVERS;
  }
}
