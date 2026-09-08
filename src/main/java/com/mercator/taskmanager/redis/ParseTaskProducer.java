package com.mercator.taskmanager.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Отправляет задачи обхода в Redis-список parse:tasks:<гео> (LPUSH).
 * Воркеры-парсеры забирают их через BRPOP — общий пул, work-stealing.
 * Формат — JSON snake_case (единый контракт с парсером).
 */
@Component
public class ParseTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(ParseTaskProducer.class);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public ParseTaskProducer(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    public void send(ParseTaskMessage task) {
        String json = mapper.writeValueAsString(task);
        String key = keys.parseTasks(task.getMarketplace(), task.getGeo());
        redis.opsForList().leftPush(key, json);
        log.info("Задача обхода отправлена в {}: task_id={}, card_id={}, sku={}",
                key, task.getTaskId(), task.getCardId(), task.getSku());
    }
}