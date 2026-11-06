package com.mercator.taskmanager.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.llm.LlmGateway;
import com.mercator.taskmanager.llm.LlmRequest;
import com.mercator.taskmanager.llm.LlmResponse;
import com.mercator.taskmanager.prod_specs.ProdSpecsLabelQueue;
import com.mercator.taskmanager.repository.CardRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Рекомендации по закупкам (prod_specs): межсервисные команды от сервиса аналитики.
 * Авторизация — межсервисный ключ (NicheApiKeyFilter).
 *
 * POST /api/v1/prod_specs/cards — разметить карточки по списку sku (202):
 *   признаки карточки по фото и тексту строятся в фоне, карточка без изменений пропускается.
 * POST /api/v1/prod_specs/llm — вызов модели для анализа страты (шаг 2). Ключи Yandex
 *   хранятся только в ядре; обращение пишется в журнал llm_request_log.
 */
@RestController
@RequestMapping("/api/v1/prod_specs")
public class ProdSpecsController {

    /** sku за один вызов разметки. */
    static final int MAX_SKUS = 200;
    /** Верхний предел токенов ответа в вызове модели. */
    static final int MAX_LLM_TOKENS = 8000;

    private final CardRepository cardRepository;
    private final ProdSpecsLabelQueue labelQueue;
    private final LlmGateway gateway;
    private final String analysisModel;
    private final int analysisMaxTokens;

    public ProdSpecsController(CardRepository cardRepository,
                               ProdSpecsLabelQueue labelQueue,
                               LlmGateway gateway,
                               @Value("${taskmanager.prod_specs.analysis-model:qwen3.6-35b-a3b}")
                               String analysisModel,
                               @Value("${taskmanager.prod_specs.analysis-max-tokens:4000}")
                               int analysisMaxTokens) {
        this.cardRepository = cardRepository;
        this.labelQueue = labelQueue;
        this.gateway = gateway;
        this.analysisModel = analysisModel;
        this.analysisMaxTokens = analysisMaxTokens;
    }

    // ---------- Разметка карточек ----------

    public record LabelCardsRequest(List<String> skus, String marketplace) {
    }

    /** requested — разных sku в запросе; found — найдено карточек; accepted — принято очередью. */
    public record LabelCardsResponse(int requested, int found, int accepted, List<String> missing) {
    }

    @PostMapping("/cards")
    public ResponseEntity<?> labelCards(@RequestBody LabelCardsRequest request) {
        if (request == null || request.marketplace() == null || request.marketplace().isBlank()) {
            return badRequest("Не указан маркетплейс");
        }
        Set<String> skus = normalize(request.skus());
        if (skus.isEmpty()) {
            return badRequest("Список sku пуст");
        }
        if (skus.size() > MAX_SKUS) {
            return badRequest("Слишком много sku: " + skus.size() + ", не больше " + MAX_SKUS);
        }

        List<CardRepository.IdSku> found =
                cardRepository.findIdsBySkus(request.marketplace().strip(), new ArrayList<>(skus));
        Set<String> foundSkus = found.stream().map(CardRepository.IdSku::getSku)
                .collect(Collectors.toSet());
        List<String> missing = skus.stream().filter(sku -> !foundSkus.contains(sku)).toList();
        int accepted = labelQueue.enqueue(found.stream().map(CardRepository.IdSku::getId).toList());

        return ResponseEntity.accepted().body(
                new LabelCardsResponse(skus.size(), found.size(), accepted, missing));
    }

    // ---------- Вызов модели ----------

    public record LlmCallRequest(String purpose,
                                 @JsonProperty("ref_type") String refType,
                                 @JsonProperty("ref_id") String refId,
                                 @JsonProperty("system_prompt") String systemPrompt,
                                 @JsonProperty("user_prompt") String userPrompt,
                                 @JsonProperty("max_tokens") Integer maxTokens) {
    }

    public record LlmCallResponse(String content, String model, int tokens,
                                  @JsonProperty("prompt_tokens") Integer promptTokens,
                                  @JsonProperty("completion_tokens") Integer completionTokens) {
    }

    @PostMapping("/llm")
    public ResponseEntity<?> llm(@RequestBody LlmCallRequest request) {
        if (request == null || !LlmGateway.PROD_SPECS_ANALYSIS.equals(request.purpose())) {
            return badRequest("Допустимое назначение: " + LlmGateway.PROD_SPECS_ANALYSIS);
        }
        if (isBlank(request.systemPrompt()) || isBlank(request.userPrompt())) {
            return badRequest("Пустой system_prompt или user_prompt");
        }
        int maxTokens = request.maxTokens() == null ? analysisMaxTokens : request.maxTokens();
        if (maxTokens < 1 || maxTokens > MAX_LLM_TOKENS) {
            return badRequest("max_tokens должен быть от 1 до " + MAX_LLM_TOKENS);
        }

        try {
            LlmResponse response = gateway.call(LlmGateway.PROD_SPECS_ANALYSIS,
                    request.refType(), request.refId(),
                    LlmRequest.text(analysisModel, request.systemPrompt(), request.userPrompt(), maxTokens));
            return ResponseEntity.ok(new LlmCallResponse(response.content(), response.model(),
                    response.tokens(), response.promptTokens(), response.completionTokens()));
        } catch (LlmException e) {
            HttpStatus status = e.getKind() == LlmException.Kind.TIMEOUT
                    ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            return ResponseEntity.status(status).body(Map.of(
                    "error", e.getKind() + ": " + e.getMessage(),
                    "kind", e.getKind().name()));
        }
    }

    // ---------- Вспомогательное ----------

    private static Set<String> normalize(List<String> skus) {
        Set<String> out = new LinkedHashSet<>();
        if (skus != null) {
            for (String sku : skus) {
                if (sku != null && !sku.isBlank()) {
                    out.add(sku.strip());
                }
            }
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}