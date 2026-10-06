package com.mercator.taskmanager.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Отправляет задачи снимка выдачи в Redis-список search:tasks:<маркетплейс>:<гео> (LPUSH). */
@Component
public class SearchTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(SearchTaskProducer.class);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public SearchTaskProducer(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    public void send(SearchTaskMessage task) {
        String json = mapper.writeValueAsString(task);
        String key = keys.searchTasks(task.getMarketplace(), task.getGeo());
        redis.opsForList().leftPush(key, json);
        log.debug("Задача снимка выдачи отправлена в {}: task_id={}, stratum_id={}, query='{}'",
                key, task.getTaskId(), task.getStratumId(), task.getQuery());
    }
}