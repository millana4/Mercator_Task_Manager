package com.mercator.taskmanager.llm;

/**
 * Запрос к нейросети.
 *
 * @param model        короткое имя модели; null — модель из настроек клиента
 * @param systemPrompt системный промпт
 * @param userPrompt   текст пользователя
 * @param imageUrl     адрес картинки (модель получает ссылку); null — запрос без картинки
 * @param maxTokens    лимит токенов ответа; null — из настроек клиента
 * @param temperature  температура; null — из настроек клиента
 */
public record LlmRequest(String model, String systemPrompt, String userPrompt, String imageUrl,
                         Integer maxTokens, Double temperature) {

    /** Текстовый запрос. */
    public static LlmRequest text(String model, String systemPrompt, String userPrompt,
                                  Integer maxTokens) {
        return new LlmRequest(model, systemPrompt, userPrompt, null, maxTokens, null);
    }

    /** Запрос с картинкой: текст и ссылка на изображение. */
    public static LlmRequest withImage(String model, String systemPrompt, String userPrompt,
                                       String imageUrl, Integer maxTokens) {
        return new LlmRequest(model, systemPrompt, userPrompt, imageUrl, maxTokens, null);
    }
}