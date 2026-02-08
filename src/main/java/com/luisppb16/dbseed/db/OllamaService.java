/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.db;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intellij.openapi.application.ApplicationManager;
import com.luisppb16.dbseed.ui.ModelDownloadDialog;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Architectural service for interacting with local Ollama instance.
 * Handles auto-provisioning with a real-time UI for Docker and model pulling.
 */
@Slf4j
public class OllamaService {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434/api";
    private static final String MODEL_NAME = "prem-1b-sql";
    private static final String HF_GGUF_URL = "https://huggingface.co/mradermacher/prem-1B-SQL-GGUF/resolve/main/prem-1B-SQL.Q4_K_M.gguf";
    private static final String CONTAINER_NAME = "db-seed-ollama";
    
    private static final Path LOCAL_MODEL_DIR = Paths.get(System.getProperty("user.home"), "Documents", "DBSeed4SQL", "models");
    private static final Path GGUF_PATH = LOCAL_MODEL_DIR.resolve("prem-1B-SQL.Q4_K_M.gguf");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());

    private final HttpClient httpClient;
    private final AtomicBoolean modelVerified = new AtomicBoolean(false);

    public OllamaService() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void ensureModelExists() throws IOException, InterruptedException {
        if (modelVerified.get()) return;

        CompletableFuture<Void> provisioningFuture = new CompletableFuture<>();
        
        ApplicationManager.getApplication().invokeLater(() -> {
            ModelDownloadDialog dialog = new ModelDownloadDialog();
            
            CompletableFuture.runAsync(() -> {
                try {
                    performProvisioning(dialog);
                    provisioningFuture.complete(null);
                    ApplicationManager.getApplication().invokeLater(dialog::closeOk);
                } catch (Exception e) {
                    provisioningFuture.completeExceptionally(e);
                    ApplicationManager.getApplication().invokeLater(dialog::closeCancel);
                }
            });
            
            if (!dialog.showAndGet()) {
                provisioningFuture.completeExceptionally(new IOException("Provisioning cancelled by user"));
            }
        });

        try {
            provisioningFuture.join();
            modelVerified.set(true);
        } catch (Exception e) {
            throw new IOException("AI Engine provisioning failed", e);
        }
    }

    private void performProvisioning(ModelDownloadDialog dialog) throws IOException, InterruptedException {
        dialog.appendLog("Checking Ollama availability...");
        if (!isOllamaReachable()) {
            dialog.appendLog("Ollama not found. Starting Docker container...");
            dialog.setIndeterminate(true);
            startOllamaContainer(dialog);
            
            dialog.appendLog("Waiting for Ollama to initialize...");
            waitForOllama(dialog);
        }

        dialog.setIndeterminate(false);
        dialog.appendLog("Verifying model file: " + GGUF_PATH);
        if (!Files.exists(GGUF_PATH)) {
            dialog.appendLog("Model file not found. Downloading from Hugging Face...");
            downloadGgufWithProgress(dialog);
        }

        dialog.appendLog("Verifying model registration in Ollama...");
        if (!isModelRegistered()) {
            dialog.appendLog("Model not registered. Creating model in Ollama...");
            registerModelInOllama(dialog);
        }
        dialog.appendLog("AI Engine is ready!");
    }

    private boolean isOllamaReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_BASE_URL + "/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void startOllamaContainer(ModelDownloadDialog dialog) throws IOException, InterruptedException {
        Process checkProcess = new ProcessBuilder("docker", "ps", "-a", "--filter", "name=" + CONTAINER_NAME, "--format", "{{.Names}}").start();
        String output = new String(checkProcess.getInputStream().readAllBytes()).trim();
        
        if (output.equals(CONTAINER_NAME)) {
            dialog.appendLog("Starting existing container...");
            new ProcessBuilder("docker", "start", CONTAINER_NAME).start().waitFor();
        } else {
            dialog.appendLog("Creating new container 'ollama/ollama'...");
            new ProcessBuilder("docker", "run", "-d", 
                    "-v", "ollama:/root/.ollama", 
                    "-p", "11434:11434", 
                    "--name", CONTAINER_NAME, 
                    "ollama/ollama").start().waitFor();
        }
    }

    private void waitForOllama(ModelDownloadDialog dialog) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (isOllamaReachable()) return;
            Thread.sleep(1000);
        }
        throw new RuntimeException("Ollama failed to start in Docker.");
    }

    private boolean isModelRegistered() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE_URL + "/tags"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 && response.body().contains("\"name\":\"" + MODEL_NAME + ":latest\"");
    }

    private void downloadGgufWithProgress(ModelDownloadDialog dialog) throws IOException {
        Files.createDirectories(LOCAL_MODEL_DIR);

        HttpURLConnection connection = (HttpURLConnection) URI.create(HF_GGUF_URL).toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        
        int responseCode = connection.getResponseCode();
        if (responseCode >= 300 && responseCode <= 308) {
            String newUrl = connection.getHeaderField("Location");
            connection = (HttpURLConnection) URI.create(newUrl).toURL().openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        }

        long fileSize = connection.getContentLengthLong();

        try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
             FileOutputStream fileOutputStream = new FileOutputStream(GGUF_PATH.toFile())) {
            
            byte[] dataBuffer = new byte[16384];
            int bytesRead;
            long totalRead = 0;
            long startTime = System.currentTimeMillis();

            while ((bytesRead = in.read(dataBuffer)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
                totalRead += bytesRead;
                
                long currentTime = System.currentTimeMillis();
                if (currentTime - startTime > 500) {
                    int percent = (fileSize > 0) ? (int) ((totalRead * 100) / fileSize) : 0;
                    String status = String.format("Downloaded %d MB / %d MB", totalRead / (1024 * 1024), fileSize / (1024 * 1024));
                    ApplicationManager.getApplication().invokeLater(() -> dialog.updateProgress(percent, status));
                    startTime = currentTime;
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void registerModelInOllama(ModelDownloadDialog dialog) throws IOException, InterruptedException {
        String modelfile = "FROM " + GGUF_PATH.toAbsolutePath().toString();
        Map<String, Object> payload = Map.of(
                "name", MODEL_NAME,
                "modelfile", modelfile,
                "stream", false
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE_URL + "/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to register model in Ollama: " + response.body());
        }
    }

    public String ask(String prompt) throws IOException, InterruptedException {
        ensureModelExists();

        OllamaRequest request = new OllamaRequest(MODEL_NAME, prompt, false, Map.of("temperature", 0.0));
        String jsonBody = MAPPER.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE_URL + "/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Ollama error: " + response.statusCode() + " - " + response.body());
        }

        OllamaResponse ollamaResponse = MAPPER.readValue(response.body(), OllamaResponse.class);
        return ollamaResponse.response();
    }

    public CompletableFuture<String> askAsync(String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ask(prompt);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new RuntimeException("Failed to call Ollama", e);
            }
        });
    }

    private record OllamaRequest(
            String model,
            String prompt,
            boolean stream,
            Map<String, Object> options
    ) {}

    private record OllamaResponse(
            String response,
            @JsonProperty("done") boolean done
    ) {}
}
