/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OllamaClient {

  private final String baseUrl;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public OllamaClient(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public String generate(OllamaRequest request) throws IOException, InterruptedException {
    String jsonRequest = objectMapper.writeValueAsString(request);

    HttpRequest httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/api/generate"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
        .timeout(Duration.ofMinutes(2))
        .build();

    HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      log.error("Ollama error: {} - {}", response.statusCode(), response.body());
      throw new IOException("Ollama returned status code " + response.statusCode());
    }

    OllamaResponse ollamaResponse = objectMapper.readValue(response.body(), OllamaResponse.class);
    return ollamaResponse.response();
  }

  @Builder
  public record OllamaRequest(
      String model,
      String prompt,
      @JsonProperty("stream") boolean stream,
      Options options
  ) {}

  @Builder
  public record Options(
      float temperature,
      @JsonProperty("num_predict") int numPredict,
      @JsonProperty("top_k") int topK,
      @JsonProperty("top_p") float topP
  ) {}

  public record OllamaResponse(
      String model,
      @JsonProperty("created_at") String createdAt,
      String response,
      boolean done
  ) {}
}
