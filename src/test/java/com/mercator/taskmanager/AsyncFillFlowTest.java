package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.FillBatch;
import com.mercator.taskmanager.service.FillBatchRegistry;
import com.mercator.taskmanager.service.ReferenceResolver;
import com.mercator.taskmanager.service.SetFillRedisService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Полный асинхронный контур наполнения на Redis.
 *
 * FakeParserForTest в фоне читает select:tasks:ozon:spb и кладёт результат в
 * select:results:ozon:spb; RedisResultListener подхватывает и пишет карточки.
 * Проверяем: запуск наполнения → задачи в очередь → двойник ответил →
 * консьюмер записал карточки и отметил партию.
 *
 * Живые Postgres и Redis (Testcontainers). Настоящий парсер не нужен.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@Sql(scripts = "/sql/marketplace_seed.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
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
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;
    @Autowired CardRepository cardRepository;
    @Autowired SetFillRedisService fillRedisService;
    @Autowired FillBatchRegistry batchRegistry;
    @Autowired ReferenceResolver references;

    @Test
    void fullAsyncFillFlow() {
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace("ozon"));
        set.setCategory(references.category("clothing"));
        set.setGeo(references.geo("Санкт-Петербург"));
        SetEntity savedSet = setRepository.save(set);

        createStratum(savedSet, "Рубашка", "Рубашка женская");
        createStratum(savedSet, "Носки", "Носки мужские");

        int sent = fillRedisService.startFill(savedSet.getId());
        assertEquals(2, sent, "Должны уйти 2 задачи (по числу страт)");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            FillBatch batch = batchRegistry.find(savedSet.getId());
            assertNotNull(batch, "Партия должна существовать");
            assertTrue(batch.allReceived(),
                    "Оба результата должны прийти: получено "
                            + batch.getReceivedResults() + "/" + batch.getTotalTasks());
        });

        List<StratumEntity> strata = stratumRepository.findBySubcategorySetId(savedSet.getId());
        int totalCards = strata.stream()
                .mapToInt(s -> cardRepository.findByStratumId(s.getId()).size())
                .sum();
        assertEquals(2, totalCards, "Должно записаться 2 карточки");

        strata.forEach(s -> {
            List<CardEntity> cards = cardRepository.findByStratumId(s.getId());
            cardRepository.deleteAll(cards);
        });
        stratumRepository.deleteAll(strata);
        subcategoryRepository.deleteAll(subcategoryRepository.findBySetId(savedSet.getId()));
        setRepository.deleteById(savedSet.getId());
    }

    // Каждая страта заворачивается в свою подкатегорию — как это делает
    // импорт плоского CSV.
    private void createStratum(SetEntity set, String item, String query) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", "f");
        attrs.put("layer", 1);

        SubcategoryEntity subcategory = new SubcategoryEntity();
        subcategory.setSet(set);
        subcategory.setTitle(item);
        subcategory.setAttrs(attrs);
        SubcategoryEntity savedSubcategory = subcategoryRepository.save(subcategory);

        StratumEntity s = new StratumEntity();
        s.setSubcategory(savedSubcategory);
        s.setItem(item);
        s.setQuery(query);
        s.setCount(1);
        s.setIsSeasonal(false);
        s.setBaseShare(null);
        stratumRepository.save(s);
    }
}