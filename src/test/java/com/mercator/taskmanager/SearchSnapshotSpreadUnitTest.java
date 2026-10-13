package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.redis.SearchTaskMessage;
import com.mercator.taskmanager.redis.SearchTaskProducer;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.service.SearchSnapshotScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Раскладка задач прогона снимков выдачи по окну. */
class SearchSnapshotSpreadUnitTest {

    @Test
    void offsetsOnePerSlotInsideWindow() {
        Duration window = Duration.ofMinutes(240);
        List<Long> offsets = SearchSnapshotScheduler.spreadOffsets(57, window, new Random(1));
        long slot = window.toMillis() / 57;
        assertThat(offsets).hasSize(57);
        for (int i = 0; i < offsets.size(); i++) {
            assertThat(offsets.get(i)).isBetween(i * slot, (i + 1) * slot - 1);
        }
        assertThat(SearchSnapshotScheduler.spreadOffsets(0, window, new Random(1))).isEmpty();
    }

    @Test
    void planSpreadsTasksAcrossWindow() {
        StratumRepository repo = mock(StratumRepository.class);
        SearchTaskProducer producer = mock(SearchTaskProducer.class);
        List<StratumEntity> strata = strata(10);
        when(repo.findByCategoryTitleWithSet("kids_clothing")).thenReturn(strata);

        SearchSnapshotScheduler scheduler = new SearchSnapshotScheduler(repo, producer, true, "", 240);
        OffsetDateTime runAt = OffsetDateTime.parse("2026-10-13T06:37:00+03:00");
        assertThat(scheduler.plan("kids_clothing", runAt)).isEqualTo(10);

        ArgumentCaptor<SearchTaskMessage> tasks = ArgumentCaptor.forClass(SearchTaskMessage.class);
        ArgumentCaptor<Instant> due = ArgumentCaptor.forClass(Instant.class);
        verify(producer, times(10)).plan(tasks.capture(), due.capture());
        verify(producer, never()).send(any());
        Instant start = runAt.toInstant();
        assertThat(due.getAllValues()).allSatisfy(d ->
                assertThat(d).isBetween(start, start.plus(Duration.ofMinutes(240))));
        assertThat(tasks.getAllValues()).allSatisfy(t ->
                assertThat(t.getRunAt()).isEqualTo("2026-10-13T06:37:00+03:00"));
    }

    @Test
    void zeroSpreadSendsAllAtOnce() {
        StratumRepository repo = mock(StratumRepository.class);
        SearchTaskProducer producer = mock(SearchTaskProducer.class);
        List<StratumEntity> strata = strata(3);
        when(repo.findByCategoryTitleWithSet("clothing")).thenReturn(strata);

        SearchSnapshotScheduler scheduler = new SearchSnapshotScheduler(repo, producer, true, "", 0);
        assertThat(scheduler.plan("clothing", OffsetDateTime.parse("2026-10-13T18:37:00+03:00")))
                .isEqualTo(3);
        verify(producer, times(3)).send(any());
        verify(producer, never()).plan(any(), any());
    }

    private static List<StratumEntity> strata(int n) {
        List<StratumEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            StratumEntity st = mock(StratumEntity.class, RETURNS_DEEP_STUBS);
            when(st.getId()).thenReturn(UUID.randomUUID());
            when(st.getQuery()).thenReturn("запрос " + i);
            when(st.getSubcategory().getSet().getId()).thenReturn(UUID.randomUUID());
            when(st.getSubcategory().getSet().getGeo().getTitle()).thenReturn("Санкт-Петербург");
            when(st.getSubcategory().getSet().getMarketplace().getTitle()).thenReturn("Ozon");
            out.add(st);
        }
        return out;
    }
}