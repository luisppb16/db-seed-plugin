/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.util;

import com.luisppb16.dbseed.ai.OllamaClient;
import com.luisppb16.dbseed.db.DataGenerator;
import lombok.extern.slf4j.Slf4j;

/** Listener that cleans up plugin resources when the IDE is closing. */
@Slf4j
public class PluginLifecycleListener implements Runnable {

  @Override
  public void run() {
    log.info("DBSeed4SQL plugin shutting down — releasing resources.");
    try {
      DriverLoader.deregisterAll();
    } catch (final Exception e) {
      log.warn("Error deregistering drivers during shutdown", e);
    }
    try {
      OllamaClient.shutdown();
    } catch (final Exception e) {
      log.warn("Error shutting down Ollama client during shutdown", e);
    }
    try {
      DataGenerator.shutdown();
    } catch (final Exception e) {
      log.warn("Error shutting down DataGenerator during shutdown", e);
    }
  }
}
