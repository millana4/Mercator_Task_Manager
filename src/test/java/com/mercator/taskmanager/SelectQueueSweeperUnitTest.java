package com.mercator.taskmanager;

import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.redis.SelectTaskProducer;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.service.SelectQueueSweeper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Чистка очереди подбора: страта сета в сборе освобождается для повторной отправки. */
class SelectQueueSweeperUnitTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-05T12:00:00+03:00");
    private static final Duration TTL = Duration.ofHours(6);

    private final SetRepository sets = mock(SetRepository.class);
    private final StratumRepository strata = mock(StratumRepository.class);
    private final SelectTaskProducer producer = mock(SelectTaskProducer.class);
    private final SelectQueueSweeper sweeper = new SelectQueueSweeper(sets, strata, producer, true, TTL);

    private static SetEntity set(String status) {
        SetEntity set = mock(SetEntity.class, RETURNS_DEEP_STUBS);
        when(set.getStatus()).thenReturn(status);
        when(set.getMarketplace().getTitle()).thenReturn("ozon");
        when(set.getGeo().getTitle()).thenReturn("Санкт-Петербург");
        return set;
    }

    private static StratumEntity stratum(SetEntity set) {
        SubcategoryEntity sub = new SubcategoryEntity();
        sub.setSet(set);
        StratumEntity stratum = new StratumEntity();
        stratum.setId(UUID.randomUUID());
        stratum.setSubcategory(sub);
        stratum.setSelectRequestedAt(NOW.minusHours(8));
        return stratum;
    }

    @Test
    void staleTasksReleaseOnlyStrataOfCollectingSet() {
        SetEntity collecting = set(SetStatus.COLLECTING);
        SetEntity active = set(SetStatus.ACTIVE);
        StratumEntity newSetStratum = stratum(collecting);
        StratumEntity summerStratum = stratum(active);
        when(sets.findByStatus(SetStatus.ACTIVE)).thenReturn(List.of(active));
        when(sets.findByStatus(SetStatus.COLLECTING)).thenReturn(List.of(collecting));
        when(producer.queueKey("ozon", "Санкт-Петербург")).thenReturn("q");
        when(producer.removeStale(eq("q"), eq(TTL), any()))
                .thenReturn(List.of(newSetStratum.getId(), summerStratum.getId()));
        when(strata.findById(newSetStratum.getId())).thenReturn(Optional.of(newSetStratum));
        when(strata.findById(summerStratum.getId())).thenReturn(Optional.of(summerStratum));

        assertThat(sweeper.sweep(NOW)).isEqualTo(2);

        assertThat(newSetStratum.getSelectRequestedAt()).isNull();       // уйдёт заново на ближайшем тике
        verify(strata).save(newSetStratum);
        assertThat(summerStratum.getSelectRequestedAt()).isNotNull();    // активный сет - отметка не нужна
        verify(strata, never()).save(summerStratum);
    }

    @Test
    void emptyQueueChangesNothing() {
        SetEntity collecting = set(SetStatus.COLLECTING);
        when(sets.findByStatus(SetStatus.ACTIVE)).thenReturn(List.of());
        when(sets.findByStatus(SetStatus.COLLECTING)).thenReturn(List.of(collecting));
        when(producer.queueKey("ozon", "Санкт-Петербург")).thenReturn("q");
        when(producer.removeStale(eq("q"), eq(TTL), any())).thenReturn(List.of());

        assertThat(sweeper.sweep(NOW)).isZero();
        verify(strata, never()).save(any());
    }
}