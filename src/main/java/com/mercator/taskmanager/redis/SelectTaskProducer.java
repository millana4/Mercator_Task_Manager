package com.mercator.taskmanager.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Отправляет задачи подбора в Redis-список select:tasks:<гео> (LPUSH).
 * Парсер нужного региона читает свой ключ через BRPOP.
 *
 * Сообщение сериализуем в JSON snake_case вручную (как для RestClient),
 * чтобы формат совпадал с тем, что ждёт Python-парсер.
 *
 * В каждую задачу кладётся created_at. Перед отправкой задачи по страте из очереди
 * убираются прежние задачи этой же страты: в очереди не больше одной задачи на страту.
 * Задачи ниш (stratum_id пуст) не трогаются. removeStale убирает задачи страт старше
 * срока жизни (в том числе без created_at - из прежней версии) и возвращает id их страт.
 * Очередь короткая, поэтому задачи разбираются по тексту JSON, без разбора дерева.
 */
@Component
public class SelectTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(SelectTaskProducer.class);

    private static final Pattern STRATUM_ID = Pattern.compile("\"stratum_id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern CREATED_AT = Pattern.compile("\"created_at\":\"([^\"]+)\"");

    private final StringRedisTemplate redis;
    private final RedisKeys keys;

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public SelectTaskProducer(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    /** Ключ очереди подбора маркетплейса и региона. */
    public String queueKey(String marketplace, String geo) {
        return keys.selectTasks(marketplace, geo);
    }

    public void send(SelectTaskMessage task) {
        if (task.getCreatedAt() == null) {
            task.setCreatedAt(DateTimeFormatter.ISO_OFFSET_DATE_TIME
                    .format(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)));
        }
        String json = mapper.writeValueAsString(task);
        String key = queueKey(task.getMarketplace(), task.getGeo());
        int replaced = removeQueued(key, task.getStratumId());
        redis.opsForList().leftPush(key, json);
        int priority = task.getPrioritySkus() != null ? task.getPrioritySkus().size() : 0;
        log.info("Задача подбора отправлена в {}: task_id={}, stratum_id={}, query='{}', geo={}, "
                        + "частых в выдаче={}",
                key, task.getTaskId(), task.getStratumId(), task.getQuery(), task.getGeo(), priority);
        if (replaced > 0) {
            log.warn("В очереди {} уже стояла задача страты {} — заменена новой (убрано {})",
                    key, task.getStratumId(), replaced);
        }
    }

    /**
     * Убрать из очереди задачи страт старше ttl (и задачи без created_at).
     * Возвращает id страт убранных задач - ядро освободит их для повторной отправки.
     */
    public List<UUID> removeStale(String key, Duration ttl, OffsetDateTime now) {
        List<UUID> stale = new ArrayList<>();
        List<String> queued = redis.opsForList().range(key, 0, -1);
        if (queued == null) {
            return stale;
        }
        for (String item : queued) {
            Matcher stratum = STRATUM_ID.matcher(item);
            if (!stratum.find() || !olderThan(item, ttl, now)) {
                continue;                       // задача ниши или свежая
            }
            Long removed = redis.opsForList().remove(key, 0, item);
            if (removed != null && removed > 0) {
                stale.add(UUID.fromString(stratum.group(1)));
            }
        }
        return stale;
    }

    // Убрать задачи страты stratumId, уже стоящие в очереди. Возвращает, сколько убрано.
    private int removeQueued(String key, UUID stratumId) {
        if (stratumId == null) {
            return 0;
        }
        String needle = "\"stratum_id\":\"" + stratumId + "\"";
        List<String> queued = redis.opsForList().range(key, 0, -1);
        if (queued == null) {
            return 0;
        }
        int removed = 0;
        for (String item : queued) {
            if (item.contains(needle)) {
                Long n = redis.opsForList().remove(key, 0, item);
                removed += n == null ? 0 : n.intValue();
            }
        }
        return removed;
    }

    private static boolean olderThan(String item, Duration ttl, OffsetDateTime now) {
        Matcher created = CREATED_AT.matcher(item);
        if (!created.find()) {
            return true;                        // задача из прежней версии - без времени создания
        }
        try {
            return OffsetDateTime.parse(created.group(1)).isBefore(now.minus(ttl));
        } catch (DateTimeParseException e) {
            return true;
        }
    }
}