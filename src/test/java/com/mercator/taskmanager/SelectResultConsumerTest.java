package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.redis.SelectResultConsumer;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тест приёмной логики подбора. Транспорт больше не нужен: вызываем
 * SelectResultConsumer.handle(json) напрямую и проверяем, что карточка
 * записалась в Postgres. Живой Postgres (Testcontainers).
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
class SelectResultConsumerTest extends PostgresTestBase {

    @Autowired SetRepository setRepository;
    @Autowired SetClothingRepository stratumRepository;
    @Autowired CardRepository cardRepository;
    @Autowired SelectResultConsumer consumer;

    @Test
    void consumesResultAndWritesCard() {
        SetEntity set = new SetEntity();
        set.setMarketplace("ozon");
        set.setCategory("clothing");
        set.setGeo("Санкт-Петербург");
        SetEntity savedSet = setRepository.save(set);

        SetClothingEntity stratum = new SetClothingEntity();
        stratum.setSet(savedSet);
        stratum.setItem("Рубашка");
        stratum.setQuery("Рубашка женская");
        stratum.setCount(3);
        stratum.setGender("f");
        stratum.setLayer((short) 2);
        stratum.setIsSeasonal(true);
        stratum.setBaseShare(new java.math.BigDecimal("0.500"));
        SetClothingEntity savedStratum = stratumRepository.save(stratum);

        UUID taskId = UUID.randomUUID();
        String uniqueSku = "TEST" + System.currentTimeMillis();

        String resultJson = """
            {
              "task_id": "%s",
              "set_id": "%s",
              "stratum_id": "%s",
              "geo": "Санкт-Петербург",
              "ok": true,
              "requested_count": 3,
              "found_count": 1,
              "cards": [
                {
                  "sku": "%s",
                  "url": "https://www.ozon.ru/product/test-%s/",
                  "name": "Рубашка тестовая",
                  "brand": "TestBrand",
                  "price": { "card_price": 1282, "price": 1425, "original_price": 10000 },
                  "quantity": 85,
                  "rating": "4.9",
                  "reviews_count": "5",
                  "seller": { "id": "554625", "name": "Zella" }
                }
              ]
            }
            """.formatted(taskId, savedSet.getId(), savedStratum.getId(), uniqueSku, uniqueSku);

        // Прямой вызов логики — без брокера.
        consumer.handle(resultJson);

        List<CardEntity> cards = cardRepository.findByStratumId(savedStratum.getId());
        assertFalse(cards.isEmpty(), "Консьюмер должен был записать карточку");
        assertEquals(uniqueSku, cards.get(0).getSku());
        assertEquals("Рубашка тестовая", cards.get(0).getName());

        // Уборка.
        cardRepository.deleteAll(cards);
        stratumRepository.deleteById(savedStratum.getId());
        setRepository.deleteById(savedSet.getId());
    }
}