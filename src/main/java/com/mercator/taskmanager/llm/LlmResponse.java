package com.mercator.taskmanager.llm;

/**
 * Ответ нейросети.
 *
 * @param content          текст ответа
 * @param model            короткое имя модели, ответившей на запрос
 * @param tokens           total_tokens (вход + выход)
 * @param promptTokens     токены запроса (вход); null — API не сообщил
 * @param completionTokens токены ответа (выход); null — API не сообщил
 */
public record LlmResponse(String content, String model, int tokens,
                          Integer promptTokens, Integer completionTokens) {

    /** Ответ без разбивки токенов. */
    public LlmResponse(String content, String model, int tokens) {
        this(content, model, tokens, null, null);
    }
}