package com.mercator.taskmanager.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Клиент сервиса эмбеддингов (mercator_embedder, POST /embed).
 *
 * Тексты отправляются пачками по BATCH_SIZE с префиксом "query"
 * (симметричное сравнение карточки с карточкой). Векторы нормированы,
 * поэтому близость двух текстов — скалярное произведение (similarity).
 * Авторизация — межсервисный ключ (PARSER_API_KEY).
 */
@Component
public class EmbedderClient {

    private static final Logger log = LoggerFactory.getLogger(EmbedderClient.class);

    public static final int VECTOR_SIZE = 1024;
    private static final int BATCH_SIZE = 128;
    private static final int ERROR_BODY_LIMIT = 300;

    private final RestClient rest;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String apiKey;

    public EmbedderClient(@Value("${embedder.url:http://emb-app:8000}") String url,
                          @Value("${embedder.timeout-seconds:600}") int timeoutSeconds,
                          @Value("${parser.ozon.api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Векторы для текстов, в том же порядке.
     *
     * @throws EmbedderException сервис недоступен, отказал или вернул неверный ответ
     */
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
            result.addAll(embedBatch(batch));
        }
        return result;
    }

    /** Близость нормированных векторов — скалярное произведение. */
    public static double similarity(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private List<float[]> embedBatch(List<String> texts) {
        String json = mapper.writeValueAsString(Map.of("texts", texts, "mode", "query"));

        ResponseEntity<String> response;
        try {
            response = rest.post()
                    .uri("/embed")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .onStatus(status -> true, (request, resp) -> { })  // статус разбираем сами
                    .toEntity(String.class);
        } catch (ResourceAccessException e) {
            throw new EmbedderException("Эмбеддер недоступен: " + e.getMessage(), e);
        }

        int status = response.getStatusCode().value();
        String raw = response.getBody() == null ? "" : response.getBody();
        if (status != 200) {
            throw new EmbedderException("Эмбеддер: HTTP " + status + ": " + abbreviate(raw));
        }

        EmbedResponse parsed;
        try {
            parsed = mapper.readValue(raw, EmbedResponse.class);
        } catch (Exception e) {
            throw new EmbedderException("Эмбеддер: не разобрать ответ: " + e.getMessage(), e);
        }
        if (parsed.vectors() == null || parsed.vectors().size() != texts.size()) {
            throw new EmbedderException("Эмбеддер: векторов "
                    + (parsed.vectors() == null ? 0 : parsed.vectors().size())
                    + ", текстов " + texts.size());
        }

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (List<Double> v : parsed.vectors()) {
            if (v.size() != VECTOR_SIZE) {
                throw new EmbedderException("Эмбеддер: размерность " + v.size()
                        + ", ожидается " + VECTOR_SIZE);
            }
            float[] arr = new float[VECTOR_SIZE];
            for (int i = 0; i < VECTOR_SIZE; i++) {
                arr[i] = v.get(i).floatValue();
            }
            vectors.add(arr);
        }
        log.info("Эмбеддер: {} векторов (model={})", vectors.size(), parsed.model());
        return vectors;
    }

    private static String abbreviate(String s) {
        return s.length() <= ERROR_BODY_LIMIT ? s : s.substring(0, ERROR_BODY_LIMIT) + "…";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponse(String model, Integer dim, List<List<Double>> vectors) {
    }
}