package com.mercator.taskmanager.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Клиент Yandex AI Studio (OpenAI-совместимый Chat Completions API).
 *
 * POST {api-url}/chat/completions
 * - авторизация: Api-Key сервисного аккаунта + x-folder-id;
 * - модель: gpt://<folder_id>/<model>;
 * - x-data-logging-enabled: false — запросы не сохраняются на стороне Яндекса;
 * - deepseek и qwen: reasoning_effort = none (рассуждения тратят токены и время, не нужны);
 * - картинка передаётся ссылкой (image_url) — модель принимает адрес, скачивать файл не нужно.
 *
 * chat — текстовый запрос с моделью из настроек; complete — общий вызов: модель, лимит
 * токенов, температура и картинка задаются в запросе.
 *
 * Ключи проверяются при вызове, а не при старте: ядро запускается и без них.
 */
@Component
public class YandexLlmClient {

    private static final Logger log = LoggerFactory.getLogger(YandexLlmClient.class);
    private static final int ERROR_BODY_LIMIT = 300;

    private final RestClient rest;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String apiKey;
    private final String folderId;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;

    public YandexLlmClient(
            @Value("${yandex.ai.api-url:https://llm.api.cloud.yandex.net/v1}") String apiUrl,
            @Value("${yandex.ai.api-key:}") String apiKey,
            @Value("${yandex.ai.folder-id:}") String folderId,
            @Value("${yandex.ai.model:deepseek-v4.1-flash}") String model,
            @Value("${yandex.ai.temperature:0.2}") double temperature,
            @Value("${yandex.ai.max-tokens:500}") int maxTokens,
            @Value("${yandex.ai.timeout-seconds:60}") int timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.folderId = folderId == null ? "" : folderId.trim();
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** Модель из настроек (короткое имя). */
    public String getModel() {
        return model;
    }

    /**
     * Отправить запрос: системный промпт + сообщение пользователя, модель из настроек.
     *
     * @throws LlmException AUTH / TIMEOUT / OTHER
     */
    public LlmResponse chat(String systemPrompt, String userPrompt) {
        return complete(LlmRequest.text(model, systemPrompt, userPrompt, null));
    }

    /**
     * Общий вызов: модель, лимит токенов, температура и картинка — из запроса;
     * не заданные берутся из настроек клиента.
     *
     * @throws LlmException AUTH / TIMEOUT / OTHER
     */
    public LlmResponse complete(LlmRequest request) {
        if (apiKey.isEmpty() || folderId.isEmpty()) {
            throw new LlmException(LlmException.Kind.AUTH,
                    "Не заданы YANDEX_API_KEY или YANDEX_FOLDER_ID");
        }
        String useModel = request.model() == null || request.model().isBlank()
                ? model : request.model();
        int limit = request.maxTokens() != null ? request.maxTokens() : maxTokens;
        double temp = request.temperature() != null ? request.temperature() : temperature;
        boolean withImage = request.imageUrl() != null && !request.imageUrl().isBlank();

        // ---------- 1. Тело запроса ----------
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt://" + folderId + "/" + useModel);
        body.put("messages", List.of(
                message("system", request.systemPrompt()),
                message("user", withImage
                        ? List.of(textPart(request.userPrompt()), imagePart(request.imageUrl()))
                        : request.userPrompt())));
        body.put("temperature", temp);
        body.put("max_tokens", limit);
        String lowerModel = useModel.toLowerCase(Locale.ROOT);
        if (lowerModel.contains("deepseek") || lowerModel.contains("qwen")) {
            body.put("reasoning_effort", "none");
        }
        String json = mapper.writeValueAsString(body);

        log.info("Yandex AI Studio: запрос, model={}, system={} симв., user={} симв.{}",
                useModel, request.systemPrompt().length(), request.userPrompt().length(),
                withImage ? ", с картинкой" : "");

        // ---------- 2. Отправка ----------
        ResponseEntity<String> response;
        try {
            response = rest.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Api-Key " + apiKey)
                    .header("x-folder-id", folderId)
                    .header("x-data-logging-enabled", "false")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .onStatus(status -> true, (req, resp) -> { })  // статус разбираем сами
                    .toEntity(String.class);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new LlmException(LlmException.Kind.TIMEOUT,
                        "Yandex AI Studio: таймаут " + timeoutSeconds + " с, model=" + useModel, e);
            }
            throw new LlmException(LlmException.Kind.OTHER,
                    "Yandex AI Studio: запрос не выполнен: " + e.getMessage(), e);
        }

        // ---------- 3. Статус ----------
        int status = response.getStatusCode().value();
        String raw = response.getBody() == null ? "" : response.getBody();
        if (status == 401 || status == 403) {
            throw new LlmException(LlmException.Kind.AUTH,
                    "Yandex AI Studio: ошибка авторизации или оплаты, HTTP " + status
                            + ": " + abbreviate(raw));
        }
        if (status != 200) {
            throw new LlmException(LlmException.Kind.OTHER,
                    "Yandex AI Studio: HTTP " + status + ": " + abbreviate(raw));
        }

        // ---------- 4. Разбор ответа ----------
        ChatResponse parsed;
        try {
            parsed = mapper.readValue(raw, ChatResponse.class);
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.OTHER,
                    "Yandex AI Studio: не разобрать ответ: " + e.getMessage(), e);
        }
        if (parsed.choices() == null || parsed.choices().isEmpty()
                || parsed.choices().get(0).message() == null) {
            throw new LlmException(LlmException.Kind.OTHER,
                    "Yandex AI Studio: в ответе нет choices[0].message: " + abbreviate(raw));
        }

        Choice choice = parsed.choices().get(0);
        String content = choice.message().content();
        if ((content == null || content.isBlank()) && "length".equals(choice.finishReason())) {
            throw new LlmException(LlmException.Kind.OTHER,
                    "Yandex AI Studio: ответ пуст, генерация остановлена по лимиту max_tokens="
                            + limit + " (рассуждение расходует токены?), model=" + useModel);
        }

        Usage usage = parsed.usage();
        Integer promptTokens = usage != null ? usage.promptTokens() : null;
        Integer completionTokens = usage != null ? usage.completionTokens() : null;
        int tokens = usage != null && usage.totalTokens() != null ? usage.totalTokens()
                : (promptTokens != null ? promptTokens : 0)
                + (completionTokens != null ? completionTokens : 0);
        String modelName = shortModelName(parsed.model());

        log.info("Yandex AI Studio: ответ, model={}, tokens={} (вход {}, выход {}), {} симв.",
                modelName, tokens, promptTokens, completionTokens,
                content == null ? 0 : content.length());
        return new LlmResponse(content == null ? "" : content,
                modelName != null ? modelName : useModel, tokens, promptTokens, completionTokens);
    }

    // ---------- Части запроса ----------

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        return part;
    }

    private static Map<String, Object> imagePart(String url) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "image_url");
        part.put("image_url", Map.of("url", url));
        return part;
    }

    /** gpt://<folder>/deepseek-v4-flash/latest → deepseek-v4-flash */
    private String shortModelName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String last = null;
        for (String part : raw.split("/")) {
            if (part.isBlank() || part.equals("gpt:") || part.equals("latest")
                    || part.equals(folderId)) {
                continue;
            }
            last = part;
        }
        return last != null ? last : raw;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String s) {
        return s.length() <= ERROR_BODY_LIMIT ? s : s.substring(0, ERROR_BODY_LIMIT) + "…";
    }

    // ---------- DTO ответа (лишние поля игнорируются) ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<Choice> choices, String model, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                 @JsonProperty("completion_tokens") Integer completionTokens,
                 @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
