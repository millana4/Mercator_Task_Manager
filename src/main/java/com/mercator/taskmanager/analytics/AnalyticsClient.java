package com.mercator.taskmanager.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Клиент сервиса аналитики (an-app в сети ядра). Готовность сета:
 * GET {analytics.url}/api/v1/internal/sets/{id}/readiness с межсервисным ключом
 * (Authorization: Bearer PARSER_API_KEY = SERVICE_API_KEY аналитики).
 * Ответ — последний расчёт EWMA и MACD уровня категории; ready — MACD выполнился.
 * Ошибка связи или ответ не 200 — IllegalStateException.
 */
@Component
public class AnalyticsClient {

    /** Последний расчёт алгоритма уровня категории. */
    public record AlgorithmState(Boolean sufficient, Integer nCards, String gridPointAt,
                                 String insufficientReason) {
    }

    /** Готовность сета: по алгоритмам (null — алгоритм по сету ещё не считался). */
    public record Readiness(String setId, Boolean ready, Map<String, AlgorithmState> algorithms) {

        /** MACD выполнился — можно переключать категорию на этот сет. */
        public boolean readyForSwitch() {
            return Boolean.TRUE.equals(ready);
        }

        public AlgorithmState algorithm(String name) {
            return algorithms == null ? null : algorithms.get(name);
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public AnalyticsClient(@Value("${analytics.url:http://an-app:8020}") String baseUrl,
                           @Value("${analytics.api-key:}") String apiKey,
                           @Value("${analytics.timeout-seconds:30}") long timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Готовность аналитики сета на уровне категории. */
    public Readiness setReadiness(UUID setId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Не задан межсервисный ключ (PARSER_API_KEY) — аналитика не опрашивается");
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/internal/sets/" + setId + "/readiness"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Аналитика недоступна: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запрос к аналитике прерван", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Аналитика ответила " + response.statusCode()
                    + " на запрос готовности сета " + setId);
        }
        return parse(response.body());
    }

    /** Разбор ответа готовности (snake_case). */
    public static Readiness parse(String body) {
        return MAPPER.readValue(body, Readiness.class);
    }
}