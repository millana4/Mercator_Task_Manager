package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.llm.LlmRequest;
import com.mercator.taskmanager.llm.LlmResponse;
import com.mercator.taskmanager.llm.YandexLlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Клиент Yandex AI Studio: тело запроса (модель, картинка, reasoning_effort, лимит токенов),
 * разбор ответа (текст и токены), ошибки. Вместо настоящего API — локальный HTTP-сервер.
 */
class YandexLlmClientUnitTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String IMAGE = "https://ir.ozone.ru/s3/multimedia-1-n/c600/14990236007.jpg";

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private YandexLlmClient client() {
        return new YandexLlmClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key", "b1gfolder", "deepseek-v4.1-flash", 0.2, 500, 10);
    }

    /** Ответ API; content — JSON-литерал: "\"текст\"" или "null". */
    private static String okResponse(String modelUri, String contentLiteral, String finishReason) {
        return """
                {"id":"x","model":"%s","choices":[{"index":0,"finish_reason":"%s",
                 "message":{"role":"assistant","content":%s}}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500}}
                """.formatted(modelUri, finishReason, contentLiteral);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentBody() {
        return MAPPER.readValue(lastBody.get(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("messages");
    }

    @Test
    void textRequestDisablesReasoningForDeepseekAndParsesTokens() {
        responseBody = okResponse("gpt://b1gfolder/deepseek-v4.1-flash/latest", "\"привет\"", "stop");

        LlmResponse response = client().chat("система", "вопрос");

        Map<String, Object> body = sentBody();
        assertThat(lastAuth.get()).isEqualTo("Api-Key test-key");
        assertThat(body.get("model")).isEqualTo("gpt://b1gfolder/deepseek-v4.1-flash");
        assertThat(body.get("reasoning_effort")).isEqualTo("none");
        assertThat(body.get("max_tokens")).isEqualTo(500);
        assertThat(messages(body).get(1).get("content")).isEqualTo("вопрос");

        assertThat(response.content()).isEqualTo("привет");
        assertThat(response.model()).isEqualTo("deepseek-v4.1-flash");
        assertThat(response.tokens()).isEqualTo(1500);
        assertThat(response.promptTokens()).isEqualTo(1200);
        assertThat(response.completionTokens()).isEqualTo(300);
    }

    @Test
    void imageRequestUsesContentPartsAndQwenWithoutReasoning() {
        responseBody = okResponse("gpt://qwen3.6-35b-a3b/latest", "\"{}\"", "stop");

        LlmResponse response = client().complete(
                LlmRequest.withImage("qwen3.6-35b-a3b", "система", "опиши", IMAGE, 1500));

        Map<String, Object> body = sentBody();
        assertThat(body.get("model")).isEqualTo("gpt://b1gfolder/qwen3.6-35b-a3b");
        assertThat(body.get("reasoning_effort")).isEqualTo("none");
        assertThat(body.get("max_tokens")).isEqualTo(1500);

        Object content = messages(body).get(1).get("content");
        assertThat(content).isInstanceOf(List.class);
        List<?> parts = (List<?>) content;
        assertThat(parts).hasSize(2);
        Map<?, ?> text = (Map<?, ?>) parts.get(0);
        Map<?, ?> image = (Map<?, ?>) parts.get(1);
        assertThat(text.get("type")).isEqualTo("text");
        assertThat(text.get("text")).isEqualTo("опиши");
        assertThat(image.get("type")).isEqualTo("image_url");
        assertThat(((Map<?, ?>) image.get("image_url")).get("url")).isEqualTo(IMAGE);

        assertThat(response.model()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    void modelWithoutReasoningSwitchDoesNotGetIt() {
        responseBody = okResponse("gpt://yandexgpt/latest", "\"ok\"", "stop");

        client().complete(LlmRequest.text("yandexgpt", "система", "вопрос", null));

        assertThat(sentBody()).doesNotContainKey("reasoning_effort");
    }

    @Test
    void emptyContentWithLengthFinishIsAnError() {
        responseBody = okResponse("gpt://qwen3.6-35b-a3b/latest", "null", "length");

        assertThatThrownBy(() -> client().complete(
                LlmRequest.withImage("qwen3.6-35b-a3b", "система", "опиши", IMAGE, 300)))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.getKind()).isEqualTo(LlmException.Kind.OTHER));
    }

    @Test
    void unauthorizedIsAuthError() {
        status = 401;
        responseBody = "{\"error\":\"unauthorized\"}";

        assertThatThrownBy(() -> client().chat("система", "вопрос"))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.getKind()).isEqualTo(LlmException.Kind.AUTH));
    }

    @Test
    void missingKeysIsAuthError() {
        YandexLlmClient noKeys = new YandexLlmClient("http://127.0.0.1:1/v1", "", "",
                "deepseek-v4.1-flash", 0.2, 500, 10);

        assertThatThrownBy(() -> noKeys.chat("система", "вопрос"))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.getKind()).isEqualTo(LlmException.Kind.AUTH));
    }
}