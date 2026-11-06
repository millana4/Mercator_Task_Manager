package com.mercator.taskmanager.llm;

import com.mercator.taskmanager.entity.LlmRequestLogEntity;
import com.mercator.taskmanager.repository.LlmRequestLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Единственный шлюз к нейросети: вызывает клиент Yandex AI Studio и пишет каждое
 * обращение (успешное или нет) в llm_request_log: назначение, модель, токены,
 * длительность, ссылка на нишу, карточку или страту. Тексты промптов и ответов в журнал
 * не попадают. Сбой записи в журнал основной вызов не ломает.
 *
 * Ключи Yandex хранятся только в ядре: сервис аналитики обращается к модели через
 * эндпоинт ядра, который вызывает этот шлюз.
 */
@Service
public class LlmGateway {

    public static final String NICHE_QUERY = "niche_query";
    public static final String PROD_SPECS_CARD = "prod_specs_card";
    public static final String PROD_SPECS_ANALYSIS = "prod_specs_analysis";

    private static final Set<String> PURPOSES = Set.of(NICHE_QUERY, PROD_SPECS_CARD, PROD_SPECS_ANALYSIS);
    private static final int ERROR_LIMIT = 500;
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final YandexLlmClient client;
    private final LlmRequestLogRepository logRepository;

    public LlmGateway(YandexLlmClient client, LlmRequestLogRepository logRepository) {
        this.client = client;
        this.logRepository = logRepository;
    }

    /** Модель клиента по умолчанию (короткое имя). */
    public String defaultModel() {
        return client.getModel();
    }

    /** Допустимое назначение обращения (для проверки входящих запросов). */
    public static boolean isKnownPurpose(String purpose) {
        return purpose != null && PURPOSES.contains(purpose);
    }

    /**
     * Обращение к нейросети с записью в журнал.
     *
     * @param purpose назначение (константы класса)
     * @param refType на что ссылается обращение: niche, sku, stratum; может быть null
     * @param refId   идентификатор того, на что ссылается обращение; может быть null
     * @throws LlmException AUTH / TIMEOUT / OTHER
     */
    public LlmResponse call(String purpose, String refType, String refId, LlmRequest request) {
        long startedNanos = System.nanoTime();
        LlmResponse response = null;
        String error = null;
        try {
            response = client.complete(request);
            return response;
        } catch (LlmException e) {
            error = e.getKind() + ": " + e.getMessage();
            throw e;
        } catch (RuntimeException e) {
            error = "OTHER: " + e.getMessage();
            throw new LlmException(LlmException.Kind.OTHER, e.getMessage(), e);
        } finally {
            journal(purpose, refType, refId, request, response, error, startedNanos);
        }
    }

    private void journal(String purpose, String refType, String refId, LlmRequest request,
                         LlmResponse response, String error, long startedNanos) {
        try {
            LlmRequestLogEntity entry = new LlmRequestLogEntity();
            entry.setPurpose(purpose);
            entry.setModel(response != null ? response.model() : modelOf(request));
            entry.setPromptTokens(response != null ? response.promptTokens() : null);
            entry.setCompletionTokens(response != null ? response.completionTokens() : null);
            entry.setTotalTokens(response != null ? response.tokens() : null);
            entry.setSuccess(error == null);
            entry.setError(error == null ? null : abbreviate(error));
            entry.setDurationMs((int) ((System.nanoTime() - startedNanos) / 1_000_000));
            entry.setRefType(refType);
            entry.setRefId(refId);
            logRepository.save(entry);
        } catch (RuntimeException e) {
            log.warn("Журнал обращений к нейросети: запись не сохранена ({}, {}): {}",
                    purpose, refId, e.getMessage());
        }
    }

    private String modelOf(LlmRequest request) {
        return request.model() == null || request.model().isBlank() ? client.getModel() : request.model();
    }

    private static String abbreviate(String s) {
        return s.length() <= ERROR_LIMIT ? s : s.substring(0, ERROR_LIMIT) + "…";
    }
}