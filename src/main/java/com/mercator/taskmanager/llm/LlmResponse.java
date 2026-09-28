package com.mercator.taskmanager.llm;

/**
 * Ответ нейросети.
 *
 * @param content текст ответа
 * @param model   короткое имя модели, ответившей на запрос
 * @param tokens  total_tokens (вход + выход)
 */
public record LlmResponse(String content, String model, int tokens) {
}