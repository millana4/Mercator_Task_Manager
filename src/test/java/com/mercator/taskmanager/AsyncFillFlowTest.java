package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.service.FillBatch;
import com.mercator.taskmanager.service.FillBatchRegistry;
import com.mercator.taskmanager.service.SetFillRedisService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Полный асинхронный контур наполнения на Redis.
 *
 * FakeParserForTest в фоне читает select:tasks:spb и кладёт результат в
 * select:results:spb; RedisResultListener подхватывает и пишет карточки.
 * Проверяем: запуск наполнения → задачи в очередь → двойник ответил →
 * консьюмер записал карточки и отметил партию.
 *
 * Живые Postgres и Redis (Testcontainers). Настоящий парсер не нужен.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
class AsyncFillFlowTest extends PostgresTestBase {

    @org.springframework.boot.test.context.TestConfiguration
    static class FakeParserConfig {
        @org.springframework.context.annotation.Bean
        FakeParserForTest fakeParser(
                org.springframework.data.redis.core.StringRedisTemplate redis,
                com.mercator.taskmanager.redis.RedisKeys keys) {
            return new FakeParserForTest(redis, keys);
        }
    }

    @Autowired SetRepository setRepository;
    @Autowired SetClothingRepository stratumRepository;
    @Autowired CardRepository cardRepository;
    @Autowired
    SetFillRedisService fillKafkaService;
    @Autowired FillBatchRegistry batchRegistry;

    @Test
    void fullAsyncFillFlow() {
        SetEntity set = new SetEntity();
        set.setMarketplace("ozon");
        set.setCategory("clothing");
        set.setGeo("Санкт-Петербург");
        SetEntity savedSet = setRepository.save(set);

        createStratum(savedSet, "Рубашка", "Рубашка женская");
        createStratum(savedSet, "Носки", "Носки мужские");

        int sent = fillKafkaService.startFill(savedSet.getId());
        assertEquals(2, sent, "Должны уйти 2 задачи (по числу страт)");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            FillBatch batch = batchRegistry.find(savedSet.getId());
            assertNotNull(batch, "Партия должна существовать");
            assertTrue(batch.allReceived(),
                    "Оба результата должны прийти: получено "
                            + batch.getReceivedResults() + "/" + batch.getTotalTasks());
        });

        List<SetClothingEntity> strata = stratumRepository.findBySetId(savedSet.getId());
        int totalCards = strata.stream()
                .mapToInt(s -> cardRepository.findByStratumId(s.getId()).size())
                .sum();
        assertEquals(2, totalCards, "Должно записаться 2 карточки");

        strata.forEach(s -> {
            List<CardEntity> cards = cardRepository.findByStratumId(s.getId());
            cardRepository.deleteAll(cards);
        });
        stratumRepository.deleteAll(strata);
        setRepository.deleteById(savedSet.getId());
    }

    private void createStratum(SetEntity set, String item, String query) {
        SetClothingEntity s = new SetClothingEntity();
        s.setSet(set);
        s.setItem(item);
        s.setQuery(query);
        s.setCount(1);
        s.setGender("f");
        s.setLayer((short) 1);
        s.setIsSeasonal(false);
        s.setBaseShare(null);
        stratumRepository.save(s);
    }
}