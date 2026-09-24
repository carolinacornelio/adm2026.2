package com.erp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cliente simples e seguro para o Ollama local. */
@Service
public class OllamaClient {

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;
    private final String model;
    private final boolean enabled;

    public OllamaClient(
            ObjectMapper mapper,
            @Value("${erp.ia.ollama.url:http://localhost:11434}") String baseUrl,
            @Value("${erp.ia.ollama.model:gemma3:4b}") String model,
            @Value("${erp.ia.ollama.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.enabled = enabled;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Geração normal de texto. */
    public String gerar(String prompt) {
        return enviar(prompt, false);
    }

    /** Geração estruturada para o planejador. O Ollama recebe format=json. */
    public String gerarJson(String prompt) {
        return enviar(prompt, true);
    }

    private String enviar(String prompt, boolean jsonMode) {
        if (!enabled || prompt == null || prompt.isBlank()) return null;

        try {
            Map<String, Object> corpo = new LinkedHashMap<>();
            corpo.put("model", model);
            corpo.put("prompt", prompt);
            corpo.put("stream", false);
            corpo.put("options", Map.of(
                    "temperature", 0.1,
                    "num_predict", 700
            ));
            if (jsonMode) corpo.put("format", "json");

            String body = mapper.writeValueAsString(corpo);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("Ollama retornou HTTP " + response.statusCode());
                return null;
            }

            JsonNode json = mapper.readTree(response.body());
            JsonNode result = json.get("response");
            if (result == null || result.isNull()) return null;

            String resposta = result.asText();
            return resposta == null || resposta.isBlank() ? null : resposta.trim();

        } catch (Exception e) {
            System.err.println("Não foi possível conectar ao Ollama: " + e.getMessage());
            return null;
        }
    }
}
