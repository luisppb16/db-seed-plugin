/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ai;

import com.luisppb16.dbseed.config.DbSeedSettingsState;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

/**
 * Service to manage AI interactions with Ollama using Prem 1B SQL model.
 */
@Slf4j
public class OllamaService {

  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR =
      Executors.newVirtualThreadPerTaskExecutor();

  private final OllamaClient client;
  private final String model;

  public OllamaService() {
    DbSeedSettingsState settings = DbSeedSettingsState.getInstance();
    this.client = new OllamaClient(settings.getOllamaUrl());
    this.model = settings.getAiModel();
  }

  public String ask(String prompt) {
    try {
      OllamaClient.OllamaRequest request = OllamaClient.OllamaRequest.builder()
          .model(model)
          .prompt(prompt)
          .stream(false)
          .options(OllamaClient.Options.builder()
              .temperature(0.1f)
              .numPredict(512)
              .build())
          .build();
      return client.generate(request);
    } catch (IOException | InterruptedException e) {
      log.error("Error communicating with Ollama", e);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return "";
    }
  }

  public CompletableFuture<String> askAsync(String prompt) {
    return CompletableFuture.supplyAsync(() -> ask(prompt), VIRTUAL_THREAD_EXECUTOR);
  }
}
