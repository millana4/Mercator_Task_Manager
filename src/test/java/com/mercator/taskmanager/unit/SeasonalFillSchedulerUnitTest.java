package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.MarketplaceRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.service.MonitoringScheduler;
import com.mercator.taskmanager.service.SeasonalFillScheduler;
import com.mercator.taskmanager.service.SetFillRedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Очередь сбора сезонного сета: пауза при отставании, интервал, порядок страт, повтор через 24 ч. */
class SeasonalFillSchedulerUnitTest {

    private static final short MP_ID = 1;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-27T12:00:00+03:00");

    private final MarketplaceRepository marketplaces = mock(MarketplaceRepository.class);
    private final StratumRepository strata = mock(StratumRepository.class);
    private final CardRepository cards = mock(CardRepository.class);
    private final SetFillRedisService fill = mock(SetFillRedisService.class);
    private final MonitoringScheduler monitoring = mock(MonitoringScheduler.class);

    private MarketplaceEntity ozon;
    private SeasonalFillScheduler scheduler;

    @BeforeEach
    void setUp() {
        ozon = mock(MarketplaceEntity.class);
        when(ozon.getId()).thenReturn(MP_ID);
        when(ozon.getTitle()).thenReturn("ozon");
        scheduler = new SeasonalFillScheduler(marketplaces, strata, cards, fill, monitoring, true, 30, 24);
    }

    private static StratumEntity stratum(String query, int count, Integer quota, OffsetDateTime requestedAt) {
        StratumEntity st = new StratumEntity();
        st.setId(UUID.randomUUID());
        st.setQuery(query);
        st.setCount(count);
        st.setAnalysisQuota(quota);
        st.setSelectRequestedAt(requestedAt);
        return st;
    }

    @Test
    void noCollectingStrataSendsNothing() {
        when(strata.findCollectingForSelect(MP_ID)).thenReturn(List.of());

        assertThat(scheduler.tickMarketplace(ozon, NOW)).isFalse();
        verify(monitoring, never()).isLagging(any());
        verify(fill, never()).sendCollectingTask(any(), anyInt());
    }

    @Test
    void laggingBlocksSelect() {
        when(strata.findCollectingForSelect(MP_ID)).thenReturn(List.of(stratum("A", 5, 3, null)));
        when(monitoring.isLagging(MP_ID)).thenReturn(true);

        assertThat(scheduler.tickMarketplace(ozon, NOW)).isFalse();
        verify(fill, never()).sendCollectingTask(any(), anyInt());
    }

    @Test
    void recentTaskBlocksSelect() {
        when(strata.findCollectingForSelect(MP_ID)).thenReturn(List.of(
                stratum("A", 5, 3, NOW.minusMinutes(10)), stratum("B", 5, 7, null)));

        assertThat(scheduler.tickMarketplace(ozon, NOW)).isFalse();
        verify(fill, never()).sendCollectingTask(any(), anyInt());
    }

    @Test
    void sendsNeverRequestedWithLargestQuotaForDeficit() {
        StratumEntity small = stratum("A", 4, 3, null);
        StratumEntity large = stratum("B", 12, 7, null);
        StratumEntity sent = stratum("C", 5, 9, NOW.minusMinutes(40));
        when(strata.findCollectingForSelect(MP_ID)).thenReturn(List.of(small, sent, large));
        when(cards.countActiveStratumMembers(large.getId())).thenReturn(1L);

        assertThat(scheduler.tickMarketplace(ozon, NOW)).isTrue();

        verify(fill).sendCollectingTask(large, 11);
        assertThat(large.getSelectRequestedAt()).isEqualTo(NOW);
        verify(strata).save(large);
        assertThat(small.getSelectRequestedAt()).isNull();
    }

    @Test
    void retriesStratumOnlyAfter24Hours() {
        StratumEntity recent = stratum("A", 5, 9, NOW.minusHours(10));
        StratumEntity old = stratum("B", 5, 1, NOW.minusHours(25));
        when(strata.findCollectingForSelect(MP_ID)).thenReturn(List.of(recent, old));

        assertThat(scheduler.tickMarketplace(ozon, NOW)).isTrue();

        verify(fill).sendCollectingTask(old, 5);
        verify(fill, never()).sendCollectingTask(eq(recent), anyInt());
    }

    @Test
    void fullStrataAreSkipped() {
        StratumEntity full = stratum("A", 5, 9, null);
        StratumEntity open = stratum("B", 3, 1, null);
        when(strata.findCollectingForSelect(MP_ID)).thenReturn(List.of(full, open));
        when(cards.countActiveStratumMembers(full.getId())).thenReturn(5L);

        assertThat(scheduler.tickMarketplace(ozon, NOW)).isTrue();
        verify(fill).sendCollectingTask(open, 3);

        // Укомплектованы все — задач нет.
        when(cards.countActiveStratumMembers(open.getId())).thenReturn(3L);
        open.setSelectRequestedAt(null);
        assertThat(scheduler.tickMarketplace(ozon, NOW.plusHours(1))).isFalse();
    }
}