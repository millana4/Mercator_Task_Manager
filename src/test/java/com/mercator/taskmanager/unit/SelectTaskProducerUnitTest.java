package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.redis.RedisKeys;
import com.mercator.taskmanager.redis.SelectTaskMessage;
import com.mercator.taskmanager.redis.SelectTaskProducer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Очередь подбора: created_at, замена задач той же страты, чистка просроченных. */
class SelectTaskProducerUnitTest {

    private static final String KEY = "select:tasks:ozon:spb";
    private static final String GEO = "Санкт-Петербург";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> list = mock(ListOperations.class);
    private final RedisKeys keys = mock(RedisKeys.class);
    private final SelectTaskProducer producer;

    SelectTaskProducerUnitTest() {
        when(redis.opsForList()).thenReturn(list);
        when(keys.selectTasks("ozon", GEO)).thenReturn(KEY);
        producer = new SelectTaskProducer(redis, keys);
    }

    private static SelectTaskMessage task(UUID stratumId) {
        SelectTaskMessage task = new SelectTaskMessage();
        task.setTaskId(UUID.randomUUID());
        task.setStratumId(stratumId);
        task.setMarketplace("ozon");
        task.setGeo(GEO);
        task.setQuery("Юбка для девочки");
        task.setCount(3);
        return task;
    }

    // Задача в том виде, в каком она лежит в Redis.
    private static String queued(UUID stratumId, String createdAt) {
        String stratum = stratumId == null ? "null" : "\"" + stratumId + "\"";
        String created = createdAt == null ? "" : ",\"created_at\":\"" + createdAt + "\"";
        return "{\"task_id\":\"" + UUID.randomUUID() + "\",\"stratum_id\":" + stratum + created + "}";
    }

    @Test
    void sendAddsCreatedAt() {
        producer.send(task(UUID.randomUUID()));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(list).leftPush(eq(KEY), json.capture());
        assertThat(json.getValue()).contains("\"created_at\":\"");
    }

    @Test
    void sendReplacesQueuedTaskOfSameStratum() {
        UUID same = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String old = queued(same, "2026-11-05T10:00:00+03:00");
        String foreign = queued(other, "2026-11-05T10:00:00+03:00");
        when(list.range(KEY, 0, -1)).thenReturn(List.of(foreign, old));
        when(list.remove(KEY, 0, old)).thenReturn(1L);

        producer.send(task(same));

        verify(list).remove(KEY, 0, old);
        verify(list, never()).remove(KEY, 0, foreign);
        verify(list).leftPush(eq(KEY), anyString());
    }

    @Test
    void nicheTaskWithoutStratumReplacesNothing() {
        producer.send(task(null));

        verify(list, never()).range(any(), anyLong(), anyLong());
        verify(list).leftPush(eq(KEY), anyString());
    }

    @Test
    void removeStaleDropsOldAndLegacyStrataTasksOnly() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-05T12:00:00+03:00");
        UUID fresh = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        String freshTask = queued(fresh, "2026-11-05T11:00:00+03:00");
        String oldTask = queued(old, "2026-11-05T04:00:00+03:00");        // 8 ч
        String legacyTask = queued(legacy, null);                        // прежняя версия, без времени
        String nicheTask = queued(null, null);                           // ниша - не трогаем
        when(list.range(KEY, 0, -1)).thenReturn(List.of(freshTask, oldTask, legacyTask, nicheTask));
        when(list.remove(eq(KEY), eq(0L), any())).thenReturn(1L);

        List<UUID> stale = producer.removeStale(KEY, Duration.ofHours(6), now);

        assertThat(stale).containsExactlyInAnyOrder(old, legacy);
        verify(list).remove(KEY, 0, oldTask);
        verify(list).remove(KEY, 0, legacyTask);
        verify(list, never()).remove(KEY, 0, freshTask);
        verify(list, never()).remove(KEY, 0, nicheTask);
    }
}