/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.service;

import dev.langchain4j.model.ollama.OllamaChatModel;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Cliente de IA optimizado para ejecución local.
 * Utiliza Virtual Threads (gestionados por el llamador) para manejar la latencia del modelo DeepSeek.
 */
@Slf4j
public final class LocalAIService {

    private final OllamaChatModel model;

    public LocalAIService(String endpoint, String modelName) {
        this.model = OllamaChatModel.builder()
                .baseUrl(endpoint)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Generates a realistic value for a given column and type.
     */
    public String generateSemanticValue(String columnName, String columnType) {
        String systemPrompt = "You are a data generator. Column: %s, Type: %s. Task: Generate 1 realistic value. Output only the value.";
        String prompt = String.format(systemPrompt, columnName, columnType);

        try {
            String response = model.generate(prompt);
            return response != null ? response.trim() : null;
        } catch (Exception e) {
            log.warn("AI semantic generation failed for column {}: {}", columnName, e.getMessage());
            return null;
        }
    }

    /**
     * Converts a generic SQL INSERT statement to a specific database dialect.
     */
    public String convertDialect(String sql, String targetDialect) {
        String systemPrompt = "You are a SQL expert. Task: Convert the following generic INSERT to %s dialect. Output only the SQL code, no explanations.";
        String userPrompt = "Translate this standard ANSI SQL INSERT into %s dialect. Maintain all values exactly as provided, only adjust syntax, quotes, and date literals.\n\n%s";

        String fullPrompt = String.format("System: " + systemPrompt + "\n\n" + userPrompt, targetDialect, targetDialect, sql);

        try {
            String response = model.generate(fullPrompt);
            if (response != null) {
                return extractSql(response.trim());
            }
            return null;
        } catch (Exception e) {
            log.warn("AI dialect conversion failed: {}", e.getMessage());
            return null;
        }
    }

    private String extractSql(String response) {
        if (response.contains("```sql")) {
            int start = response.indexOf("```sql") + 6;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        } else if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }
        return response;
    }
}
