package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.redis.ParseTaskMessage;
import com.mercator.taskmanager.redis.ParseTaskProducer;
import com.mercator.taskmanager.redis.RedisKeys;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.MarketplaceRepository;
import com.mercator.taskmanager.service.MonitoringScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Плановый обход: интервалы, жетоны, отставание и сигнал резерва, порядок учащения. */
class MonitoringSchedulerUnitTest {

    private static final short MP_ID = 1;
    private static final String KIDS = "kids_clothing";
    private static final String CLOTHING = "clothing";
    private static final String SIGNAL_KEY = "monitoring:reserve:ozon:spb";

    private final CardRepository cards = mock(CardRepository.class);
    private final MarketplaceRepository marketplaces = mock(MarketplaceRepository.class);
    private final ParseTaskProducer producer = mock(ParseTaskProducer.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> lists = mock(ListOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisKeys keys = new RedisKeys("parse:tasks", "parse:results", "select:tasks",
            "select:results", "search:tasks", "search:results", "monitoring:reserve");

    private MonitoringScheduler scheduler() {
        MarketplaceEntity ozon = mock(MarketplaceEntity.class);
        when(ozon.getId()).thenReturn(MP_ID);
        when(ozon.getTitle()).thenReturn("ozon");
        when(marketplaces.findAll()).thenReturn(List.of(ozon));
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForValue()).thenReturn(values);
        when(lists.size(any())).thenReturn(0L);
        when(cards.countActiveInCategory(eq(MP_ID), eq(KIDS))).thenReturn(656L);
        when(cards.countActiveInCategory(eq(MP_ID), eq(CLOTHING))).thenReturn(315L);
        return new MonitoringScheduler(cards, marketplaces, producer, redis, keys,
                10, 45, "Санкт-Петербург", "kids_clothing=10,clothing=24",
                1.2, 1, 0.05, 0.02, 120, 60);
    }

    private static List<CardEntity> cardsMeasuredHoursAgo(int count, long hours) {
        List<CardEntity> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CardEntity c = new CardEntity();
            c.setId(UUID.randomUUID());
            c.setSku(String.valueOf(1000 + i));
            c.setLastMeasuredAt(OffsetDateTime.now().minusHours(hours).plusMinutes(i));
            out.add(c);
        }
        return out;
    }

    @Test
    void parsesIntervalsByCategory() {
        Map<String, Long> m = MonitoringScheduler.parseIntervals("kids_clothing=10, clothing=24");
        assertThat(m).containsEntry(KIDS, 10L);
        assertThat(m).containsEntry(CLOTHING, 24L);
        assertThatThrownBy(() -> MonitoringScheduler.parseIntervals("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitoringScheduler.parseIntervals("clothing:24"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitoringScheduler.parseIntervals("clothing=0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laggingHasHysteresis() {
        assertThat(MonitoringScheduler.nextLagging(false, 0.06, 0.05, 0.02)).isTrue();
        assertThat(MonitoringScheduler.nextLagging(false, 0.04, 0.05, 0.02)).isFalse();
        assertThat(MonitoringScheduler.nextLagging(true, 0.03, 0.05, 0.02)).isTrue();   // держится
        assertThat(MonitoringScheduler.nextLagging(true, 0.01, 0.05, 0.02)).isFalse();
    }

    @Test
    void tokensGrowByRateAndAreCapped() {
        // 95 в час: за 6 мин — 9.5 жетона, за 10 мин — потолок 10
        assertThat(MonitoringScheduler.nextTokens(0, 95, 360, 10)).isCloseTo(9.5, within(1e-9));
        assertThat(MonitoringScheduler.nextTokens(0, 95, 600, 10)).isEqualTo(10.0);
        assertThat(MonitoringScheduler.nextTokens(3, 95, -5, 10)).isEqualTo(3.0);
    }

    @Test
    void onScheduleFirstTickHasNoTokensAndNoSignal() {
        MonitoringScheduler s = scheduler();
        when(cards.countOverdueInCategory(eq(MP_ID), any(), any())).thenReturn(0L);
        when(cards.findBoostCandidates(eq(MP_ID), any(), any(), anyInt()))
                .thenReturn(cardsMeasuredHoursAgo(2, 2));

        s.refill();

        verify(redis).delete(SIGNAL_KEY);
        verify(values, never()).set(any(), any(), any(Duration.class));
        verify(producer, times(2)).send(any(ParseTaskMessage.class));       // только учащённые
        verify(cards, never()).findDueCandidates(any(), any(), any(), any(), anyInt());   // жетонов 0
    }

    @Test
    void laggingSetsSignalSkipsBoostAndFillsBuffer() {
        MonitoringScheduler s = scheduler();
        when(cards.countOverdueInCategory(eq(MP_ID), eq(KIDS), any())).thenReturn(66L);   // 10% > 5%
        when(cards.countOverdueInCategory(eq(MP_ID), eq(CLOTHING), any())).thenReturn(0L);
        when(cards.findDueCandidates(eq(MP_ID), eq(KIDS), any(), any(), anyInt()))
                .thenReturn(cardsMeasuredHoursAgo(10, 12));
        when(cards.findDueCandidates(eq(MP_ID), eq(CLOTHING), any(), any(), anyInt()))
                .thenReturn(List.of());

        s.refill();

        verify(values).set(eq(SIGNAL_KEY), eq("1"), eq(Duration.ofSeconds(120)));
        verify(cards, never()).findBoostCandidates(any(), any(), any(), anyInt());
        verify(producer, times(10)).send(any(ParseTaskMessage.class));      // до заполнения буфера
        verify(cards).markEnqueued(anyList(), any());
    }

    @Test
    void fullQueueEnqueuesNothing() {
        MonitoringScheduler s = scheduler();
        when(lists.size(any())).thenReturn(10L);
        when(cards.countOverdueInCategory(eq(MP_ID), any(), any())).thenReturn(0L);

        s.refill();

        verify(producer, never()).send(any(ParseTaskMessage.class));
        verify(redis).delete(SIGNAL_KEY);                                   // сигнал обновляется всегда
    }
}