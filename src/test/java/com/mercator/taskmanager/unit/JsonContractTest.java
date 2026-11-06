package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.contract.Ozon.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет фундамент контрактов до создания остальных классов:
 * 1) что snake_case из JSON парсера корректно ложится в camelCase-поля Java;
 * 2) что значение коллекции ("base") читается в enum Collection.
 *
 * Jackson 3: ObjectMapper неизменяем, стратегия задаётся через builder
 * при создании JsonMapper (аналог настройки в application.yml).
 */
class JsonContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void snakeCaseFieldsMapToCamelCase() {
        String json = """
            {
              "card_price": 1330,
              "price": 1400,
              "original_price": 7500
            }
            """;

        OzonPrice parsed = mapper.readValue(json, OzonPrice.class);

        assertEquals(1330.0, parsed.getCardPrice());
        assertEquals(1400.0, parsed.getPrice());
        assertEquals(7500.0, parsed.getOriginalPrice());
    }

    @Test
    void collectionEnumReadsFromLowercaseJson() {
        Collection parsed = mapper.readValue("\"base\"", Collection.class);
        assertEquals(Collection.BASE, parsed);
    }

    @Test
    void fullCardParsesFromParserJson() {
        // Реальный (сокращённый) пример ответа парсера по одной карточке.
        String json = """
            {
              "sku": "3974473087",
              "url": "https://www.ozon.ru/product/rubashka-3974473087/",
              "parsed_at": "2026-07-06T06:34:47+00:00",
              "name": "Рубашка",
              "brand": null,
              "price": { "card_price": 1330, "price": 1400, "original_price": 7500 },
              "quantity": 101,
              "rating": "5",
              "reviews_count": "282",
              "characteristics": {
                "Материал": "Хлопок, Эластан",
                "Коллекция": "Базовая коллекция"
              },
              "category": "Блузы и рубашки",
              "category_path": "Одежда/Женская одежда/Блузы и рубашки",
              "variants_aspect": "Цвет",
              "variants": [
                { "sku": "3974482331", "value": "Красная", "availability": "inStock", "price": 1387 }
              ],
              "seller": { "id": "3463754", "name": "S", "legal_name": "ИП Субботин", "ogrn": null },
              "location": { "city": "Санкт-Петербург", "country": "Россия", "country_code": "RUS", "area_id": 5911 }
            }
            """;

        OzonCard card = mapper.readValue(json, OzonCard.class);

        // Верхний уровень
        assertEquals("3974473087", card.getSku());
        assertEquals("282", card.getReviewsCount());          // reviews_count -> reviewsCount
        // Вложенные объекты
        assertEquals(1330.0, card.getPrice().getCardPrice()); // price.card_price -> cardPrice
        assertEquals("3463754", card.getSeller().getId());
        assertEquals("Санкт-Петербург", card.getLocation().getCity());
        assertEquals(5911, card.getLocation().getAreaId());   // area_id -> areaId
        // Словарь характеристик
        assertEquals("Базовая коллекция", card.getCharacteristics().get("Коллекция"));
        // Список вариантов
        assertEquals(1, card.getVariants().size());
        assertEquals("Красная", card.getVariants().get(0).getValue());
    }

    @Test
    void stratumRequestSerializesToParserJson() {
        // Формируем запрос, как таск-менеджер отправит парсеру, и проверяем,
        // что он превращается в правильный snake_case JSON.
        StratumRequest req = new StratumRequest();
        req.setQuery("Рубашка женская");
        req.setCount(20);
        req.setIsSeasonal(true);
        req.setBaseShare(0.5);

        String json = mapper.writeValueAsString(req);

        // Ключевая проверка: isSeasonal ушло как "is_seasonal", baseShare как "base_share".
        assertTrue(json.contains("\"is_seasonal\":true"), "Ожидали is_seasonal в JSON, получили: " + json);
        assertTrue(json.contains("\"base_share\":0.5"), "Ожидали base_share в JSON, получили: " + json);
        assertTrue(json.contains("\"query\":\"Рубашка женская\""));
    }

    @Test
    void selectionResponseParsesFromParserJson() {
        // Ответ подбора с одной карточкой и found_count < requested_count.
        String json = """
            {
              "ok": true,
              "requested_count": 20,
              "found_count": 1,
              "cards": [
                { "sku": "3974473087", "name": "Рубашка", "price": { "price": 1400 } }
              ]
            }
            """;

        SelectionResponse resp = mapper.readValue(json, SelectionResponse.class);

        assertEquals(20, resp.getRequestedCount());   // requested_count -> requestedCount
        assertEquals(1, resp.getFoundCount());        // found_count -> foundCount
        assertEquals(1, resp.getCards().size());
        assertEquals("3974473087", resp.getCards().get(0).getSku());
    }

    @Test
    void cardResponseParsesFromParserJson() {
        // Ответ card/by-id: обёртка ok + одна карточка. debug_files намеренно
        // присутствует в JSON, но отсутствует в DTO — должен игнорироваться.
        String json = """
            {
              "ok": true,
              "card": { "sku": "3641521371", "name": "Платье", "price": { "price": 2990 } },
              "debug_files": { "html": "dump.html" }
            }
            """;

        CardResponse resp = mapper.readValue(json, CardResponse.class);

        assertEquals(true, resp.getOk());
        assertEquals("3641521371", resp.getCard().getSku());
        assertEquals(2990.0, resp.getCard().getPrice().getPrice());
    }
}