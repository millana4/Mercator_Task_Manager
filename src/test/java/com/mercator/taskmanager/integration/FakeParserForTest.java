package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.redis.RedisKeys;
import com.mercator.taskmanager.redis.SelectTaskMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Тестовый двойник парсера на Redis: в фоне читает select:tasks:spb (BRPOP)
 * и на каждую задачу кладёт результат в select:results:spb (одна карточка),
 * возвращая task_id/set_id/stratum_id/geo эхом. Так проверяется асинхронный
 * контур наполнения без настоящего парсера.
 *
 * Только для тестов (src/test). Активен как обычный бин при поднятом контексте.
 */
public class FakeParserForTest {

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private volatile boolean running = true;
    private ExecutorService pool;

    public FakeParserForTest(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    @PostConstruct
    void start() {
        pool = Executors.newSingleThreadExecutor();
        String taskKey = keys.selectTasks("ozon", "Санкт-Петербург");
        String resultKey = keys.selectResults("ozon", "Санкт-Петербург");
        pool.submit(() -> loop(taskKey, resultKey));
    }

    private void loop(String taskKey, String resultKey) {
        while (running) {
            try {
                String taskJson = redis.opsForList().rightPop(taskKey, Duration.ofSeconds(2));
                if (taskJson == null) continue;

                SelectTaskMessage task = mapper.readValue(taskJson, SelectTaskMessage.class);
                String sku = "FAKE" + System.nanoTime();
                String resultJson = """
                    {
                      "task_id": "%s",
                      "set_id": "%s",
                      "stratum_id": "%s",
                      "geo": "%s",
                      "marketplace": "%s",
                      "ok": true,
                      "requested_count": %d,
                      "found_count": 1,
                      "cards": [
                        { "sku": "%s", "name": "Фейковая карточка",
                          "price": { "price": 1000 }, "seller": { "id": "999" } }
                      ]
                    }
                    """.formatted(task.getTaskId(), task.getSetId(), task.getStratumId(),
                        task.getGeo(), task.getMarketplace(), task.getCount(), sku);

                redis.opsForList().leftPush(resultKey, resultJson);
                System.out.println(">>> FakeParser ответил на task_id=" + task.getTaskId());
            } catch (Exception e) {
                System.out.println(">>> FakeParser ошибка: " + e.getMessage());
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        if (pool != null) pool.shutdown();
    }
}