package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.contract.Ozon.OzonAspect;
import com.mercator.taskmanager.contract.Ozon.OzonAspectValue;
import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.redis.ParseResultMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор результата обхода с полями полного разбора — тем же JsonMapper,
 * что в ParseResultConsumer (SNAKE_CASE).
 */
class ParseResultMessageFullContentTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private static final String NEW_PARSER_RESULT = """
            {"task_id": "11111111-1111-1111-1111-111111111111",
             "card_id": "22222222-2222-2222-2222-222222222222",
             "sku": "4569427009", "geo": "Санкт-Петербург", "parser_id": "6", "ok": true,
             "card": {
               "sku": "4569427009", "name": "Лонгслив Happyfox для девочек 1 шт",
               "characteristics": {"Сезон": "Демисезон"},
               "characteristics_full": {"Артикул": "4569427009", "Тип": "Лонгслив",
                                        "Декоративные элементы": "В рубчик, Рюши"},
               "tnved": "6109100000",
               "aspects": [
                 {"name": "Цвет", "key": "Color", "type": "colorsMix",
                  "values": [{"sku": "4569427009", "value": "розовый", "availability": "inStock",
                              "price": 728.0, "original_price": 1079.0, "active": true}]},
                 {"name": "Размер", "key": "size", "type": "sizes",
                  "values": [{"sku": "4569425650", "value": "128 RU / 128", "availability": "inStock",
                              "price": 728.0, "original_price": 1079.0, "active": false}]}
               ],
               "full_content": true
             }}
            """;

    private static final String OLD_PARSER_RESULT = """
            {"task_id": "11111111-1111-1111-1111-111111111111",
             "card_id": "22222222-2222-2222-2222-222222222222",
             "sku": "4569427009", "geo": "Санкт-Петербург", "parser_id": "1", "ok": true,
             "card": {"sku": "4569427009", "characteristics": {"Сезон": "Демисезон"}}}
            """;

    @Test
    void newParserFieldsAreRead() {
        OzonCard card = mapper.readValue(NEW_PARSER_RESULT, ParseResultMessage.class).getCard();

        assertTrue(card.getFullContent());
        assertEquals("Лонгслив", card.getCharacteristicsFull().get("Тип"));
        assertEquals("В рубчик, Рюши", card.getCharacteristicsFull().get("Декоративные элементы"));
        assertEquals("6109100000", card.getTnved());
        assertEquals(2, card.getAspects().size());

        OzonAspect color = card.getAspects().get(0);
        assertEquals("Цвет", color.getName());
        assertEquals("colorsMix", color.getType());
        OzonAspectValue active = color.getValues().get(0);
        assertEquals("4569427009", active.getSku());
        assertEquals(1079.0, active.getOriginalPrice());
        assertTrue(active.getActive());

        assertEquals("size", card.getAspects().get(1).getKey());
    }

    @Test
    void oldParserResultHasEmptyNewFields() {
        OzonCard card = mapper.readValue(OLD_PARSER_RESULT, ParseResultMessage.class).getCard();

        assertNull(card.getFullContent());
        assertNull(card.getCharacteristicsFull());
        assertNull(card.getAspects());
        assertNull(card.getTnved());
        assertEquals("Демисезон", card.getCharacteristics().get("Сезон"));
    }
}