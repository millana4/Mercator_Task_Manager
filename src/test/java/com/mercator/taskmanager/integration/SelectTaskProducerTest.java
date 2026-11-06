package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.redis.RedisKeys;
import com.mercator.taskmanager.redis.SelectTaskMessage;
import com.mercator.taskmanager.redis.SelectTaskProducer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Отправляет задачу подбора через SelectTaskProducer и проверяет, что она
 * реально легла в Redis-список select:tasks:ozon:spb. Ключ строится из
 * маркетплейса и гео задачи. Живой Redis (Testcontainers).
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
class SelectTaskProducerTest extends PostgresTestBase {

    @Autowired SelectTaskProducer producer;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisKeys keys;

    @Test
    void sendsSelectTaskToRedis() {
        String key = keys.selectTasks("ozon", "Санкт-Петербург");
        redis.delete(key);  // чистим перед проверкой

        SelectTaskMessage task = new SelectTaskMessage();
        task.setTaskId(UUID.randomUUID());
        task.setStratumId(UUID.randomUUID());
        task.setGeo("Санкт-Петербург");
        task.setMarketplace("ozon");     // по нему продюсер выберет ключ
        task.setCategory("clothing");
        task.setQuery("Трусы женские");
        task.setCount(4);
        task.setIsSeasonal(false);
        task.setBaseShare(null);
        task.setExclude(List.of());

        producer.send(task);

        Long len = redis.opsForList().size(key);
        assertNotNull(len);
        assertEquals(1L, len, "Задача должна лечь в Redis-список");
    }
}