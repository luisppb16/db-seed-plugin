/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.util;

import com.intellij.ide.AppLifecycleListener;
import com.luisppb16.dbseed.ai.AbstractAiClient;
import lombok.extern.slf4j.Slf4j;

/** Listener that cleans up plugin resources when the IDE is closing. */
@Slf4j
public class PluginLifecycleListener implements AppLifecycleListener {

  @Override
  public void appWillBeClosed(final boolean isRestart) {
    log.info("DBSeed4SQL plugin shutting down — releasing resources.");
    try {
      DriverLoader.deregisterAll();
    } catch (final Exception e) {
      log.warn("Error deregistering drivers during shutdown", e);
    }
    try {
      AbstractAiClient.shutdown();
    } catch (final Exception e) {
      log.warn("Error shutting down the AI client during shutdown", e);
    }
  }
}
