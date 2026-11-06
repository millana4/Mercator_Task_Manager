package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.analytics.AnalyticsClient;
import com.mercator.taskmanager.analytics.AnalyticsClient.AlgorithmState;
import com.mercator.taskmanager.analytics.AnalyticsClient.Readiness;
import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.service.SeasonSwitchScheduler;
import com.mercator.taskmanager.service.SeasonSwitchService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Проверка готовности сетов в сборе: переключение только по MACD, ошибки не останавливают. */
class SeasonSwitchSchedulerUnitTest {

    private final SetRepository sets = mock(SetRepository.class);
    private final AnalyticsClient analytics = mock(AnalyticsClient.class);
    private final SeasonSwitchService switchService = mock(SeasonSwitchService.class);
    private final SeasonSwitchScheduler scheduler =
            new SeasonSwitchScheduler(sets, analytics, switchService, true);

    private static SetEntity set(UUID id) {
        SetEntity set = new SetEntity();
        set.setId(id);
        set.setStatus(SetStatus.COLLECTING);
        return set;
    }

    private static Readiness readiness(boolean ewma, Boolean macd) {
        Map<String, AlgorithmState> algorithms = new HashMap<>();
        algorithms.put("EWMA", new AlgorithmState(ewma, 150, "2026-11-01T00:37:00", null));
        algorithms.put("MACD", macd == null ? null
                : new AlgorithmState(macd, 120, "2026-11-01T00:37:00", null));
        return new Readiness(null, Boolean.TRUE.equals(macd), algorithms);
    }

    @Test
    void switchesWhenMacdReady() {
        UUID id = UUID.randomUUID();
        when(sets.findByStatus(SetStatus.COLLECTING)).thenReturn(List.of(set(id)));
        when(analytics.setReadiness(id)).thenReturn(readiness(true, true));

        assertThat(scheduler.checkAll()).isEqualTo(1);
        verify(switchService).switchTo(id);
    }

    @Test
    void waitsWhileOnlyEwmaReady() {
        UUID id = UUID.randomUUID();
        when(sets.findByStatus(SetStatus.COLLECTING)).thenReturn(List.of(set(id)));
        when(analytics.setReadiness(id)).thenReturn(readiness(true, false));

        assertThat(scheduler.checkAll()).isZero();
        verify(switchService, never()).switchTo(any());
    }

    @Test
    void analyticsFailureDoesNotStopOtherSets() {
        UUID broken = UUID.randomUUID();
        UUID ready = UUID.randomUUID();
        when(sets.findByStatus(SetStatus.COLLECTING)).thenReturn(List.of(set(broken), set(ready)));
        when(analytics.setReadiness(broken)).thenThrow(new IllegalStateException("Аналитика недоступна"));
        when(analytics.setReadiness(ready)).thenReturn(readiness(true, true));

        assertThat(scheduler.checkAll()).isEqualTo(1);
        verify(switchService, never()).switchTo(broken);
        verify(switchService).switchTo(ready);
    }
}