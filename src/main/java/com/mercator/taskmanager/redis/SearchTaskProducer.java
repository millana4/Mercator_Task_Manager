package com.mercator.taskmanager.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Set;

/**
 * Задачи снимка выдачи: отправка в Redis-список search:tasks:<маркетплейс>:<гео>
 * (LPUSH) и план отправки — отсортированное множество (по умолчанию
 * search:planned), вес = время отправки в мс. Созревшие задачи из плана
 * перекладываются в список задач (sendDue).
 */
@Component
public class SearchTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(SearchTaskProducer.class);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final String plannedKey;

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public SearchTaskProducer(StringRedisTemplate redis, RedisKeys keys,
                              @Value("${taskmanager.redis.search-planned-key:search:planned}") String plannedKey) {
        this.redis = redis;
        this.keys = keys;
        this.plannedKey = plannedKey;
    }

    public void send(SearchTaskMessage task) {
        String json = mapper.writeValueAsString(task);
        String key = keys.searchTasks(task.getMarketplace(), task.getGeo());
        redis.opsForList().leftPush(key, json);
        log.debug("Задача снимка выдачи отправлена в {}: task_id={}, stratum_id={}, query='{}'",
                key, task.getTaskId(), task.getStratumId(), task.getQuery());
    }

    /** Запланировать задачу: отправить не раньше dueAt. */
    public void plan(SearchTaskMessage task, Instant dueAt) {
        String json = mapper.writeValueAsString(task);
        redis.opsForZSet().add(plannedKey, json, dueAt.toEpochMilli());
        log.debug("Задача снимка выдачи запланирована на {}: task_id={}, stratum_id={}",
                dueAt, task.getTaskId(), task.getStratumId());
    }

    /**
     * Отправить созревшие задачи плана (время отправки ≤ now) в списки задач.
     * Задача снимается из плана до отправки: дважды не уйдёт. Возвращает число отправленных.
     */
    public int sendDue(Instant now) {
        Set<String> due = redis.opsForZSet()
                .rangeByScore(plannedKey, Double.NEGATIVE_INFINITY, now.toEpochMilli());
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (String json : due) {
            Long removed = redis.opsForZSet().remove(plannedKey, json);
            if (removed == null || removed == 0) {
                continue;   // уже отправлена
            }
            try {
                send(mapper.readValue(json, SearchTaskMessage.class));
                sent++;
            } catch (RuntimeException e) {
                log.error("Задача снимка выдачи из плана не отправлена: {} | {}", e.getMessage(), json);
            }
        }
        return sent;
    }
}