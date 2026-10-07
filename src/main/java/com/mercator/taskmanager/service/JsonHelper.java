package com.mercator.taskmanager.service;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Мелкий помощник для сериализации объектов в JSON.
 * toJson — JSON-строка (например, список вариантов в снимок ClickHouse).
 * toSnakeCaseMap / toSnakeCaseJson — объект с ключами в snake_case, как в
 * сообщениях парсера (characteristics_full, full_content): для card.content_txt.
 */
@Component
public class JsonHelper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final JsonMapper snakeMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public String toJson(Object value) {
        if (value == null) {
            return "[]";
        }
        return mapper.writeValueAsString(value);
    }

    /** Объект как словарь с ключами в snake_case; null — null. */
    public Map<String, Object> toSnakeCaseMap(Object value) {
        if (value == null) {
            return null;
        }
        return snakeMapper.convertValue(value, MAP_TYPE);
    }

    /** Объект как JSON-строка с ключами в snake_case; null — null. */
    public String toSnakeCaseJson(Object value) {
        if (value == null) {
            return null;
        }
        return snakeMapper.writeValueAsString(value);
    }
}