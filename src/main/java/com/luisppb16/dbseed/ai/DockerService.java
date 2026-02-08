/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Service to orchestrate Docker containers for Ollama and AI models.
 */
@Slf4j
public class DockerService {

  private static final String OLLAMA_IMAGE = "ollama/ollama:latest";
  private static final String CONTAINER_NAME = "db-seed-ollama";
  private static final String OLLAMA_PORT = "11434";

  public boolean isDockerInstalled() {
    try {
      Process process = new ProcessBuilder("docker", "--version").start();
      return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  public void pullOllamaImage(Consumer<String> statusUpdater) throws IOException, InterruptedException {
    runCommand(statusUpdater, "docker", "pull", OLLAMA_IMAGE);
  }

  public void startOllamaContainer(Consumer<String> statusUpdater) throws IOException, InterruptedException {
    if (isContainerRunning()) {
      return;
    }

    String downloadsPath = System.getProperty("user.home") + "/Downloads/db-seed-ollama";
    Path path = Paths.get(downloadsPath);
    if (!Files.exists(path)) {
        Files.createDirectories(path);
    }

    if (containerExists()) {
        runCommand(statusUpdater, "docker", "rm", "-f", CONTAINER_NAME);
    }

    runCommand(statusUpdater, "docker", "run", "-d",
        "--name", CONTAINER_NAME,
        "-p", OLLAMA_PORT + ":" + OLLAMA_PORT,
        "-v", path.toAbsolutePath().toString() + ":/root/.ollama",
        OLLAMA_IMAGE);
  }

  public void pullModel(String modelName, Consumer<String> statusUpdater) throws IOException, InterruptedException {
    runCommand(statusUpdater, "docker", "exec", CONTAINER_NAME, "ollama", "pull", modelName);
  }

  public boolean isAiReady(String modelName) {
    return isDockerInstalled() && isContainerRunning() && isModelPulled(modelName);
  }

  private boolean isModelPulled(String modelName) {
    try {
      Process process = new ProcessBuilder("docker", "exec", CONTAINER_NAME, "ollama", "list").start();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.contains(modelName)) return true;
        }
      }
    } catch (Exception e) {
      return false;
    }
    return false;
  }

  private boolean isContainerRunning() {
    try {
      Process process = new ProcessBuilder("docker", "ps", "-q", "-f", "name=" + CONTAINER_NAME).start();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        return reader.readLine() != null;
      }
    } catch (Exception e) {
      return false;
    }
  }

  private boolean containerExists() {
    try {
      Process process = new ProcessBuilder("docker", "ps", "-a", "-q", "-f", "name=" + CONTAINER_NAME).start();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        return reader.readLine() != null;
      }
    } catch (Exception e) {
      return false;
    }
  }

  private void runCommand(Consumer<String> statusUpdater, String... command) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        statusUpdater.accept(line);
      }
    }

    if (process.waitFor() != 0) {
      throw new IOException("Command failed with exit code " + process.exitValue());
    }
  }
}
