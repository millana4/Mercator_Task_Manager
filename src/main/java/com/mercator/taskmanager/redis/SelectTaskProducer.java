package com.mercator.taskmanager.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Отправляет задачи подбора в Redis-список select:tasks:<гео> (LPUSH).
 * Парсер нужного региона читает свой ключ через BRPOP.
 *
 * Сообщение сериализуем в JSON snake_case вручную (как для RestClient),
 * чтобы формат совпадал с тем, что ждёт Python-парсер.
 */
@Component
public class SelectTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(SelectTaskProducer.class);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public SelectTaskProducer(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    public void send(SelectTaskMessage task) {
        String json = mapper.writeValueAsString(task);
        String key = keys.selectTasks(task.getMarketplace(), task.getGeo());
        redis.opsForList().leftPush(key, json);
        int priority = task.getPrioritySkus() != null ? task.getPrioritySkus().size() : 0;
        log.info("Задача подбора отправлена в {}: task_id={}, stratum_id={}, query='{}', geo={}, "
                        + "частых в выдаче={}",
                key, task.getTaskId(), task.getStratumId(), task.getQuery(), task.getGeo(), priority);
    }
}