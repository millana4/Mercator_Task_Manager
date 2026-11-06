package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.analytics.AnalyticsClient;
import com.mercator.taskmanager.analytics.AnalyticsClient.Readiness;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Разбор ответа готовности аналитики и отказ без межсервисного ключа. */
class AnalyticsClientUnitTest {

    @Test
    void parsesReadinessWithMacdNotYetComputed() {
        Readiness r = AnalyticsClient.parse("""
                {"set_id":"11111111-1111-1111-1111-111111111111",
                 "algorithms":{"EWMA":{"sufficient":true,"n_cards":150,
                                       "grid_point_at":"2026-11-01T00:37:00","insufficient_reason":null},
                               "MACD":null},
                 "ready":false}
                """);
        assertThat(r.readyForSwitch()).isFalse();
        assertThat(r.algorithm("EWMA").sufficient()).isTrue();
        assertThat(r.algorithm("EWMA").nCards()).isEqualTo(150);
        assertThat(r.algorithm("MACD")).isNull();
    }

    @Test
    void parsesReadyAnswer() {
        Readiness r = AnalyticsClient.parse("""
                {"set_id":"11111111-1111-1111-1111-111111111111",
                 "algorithms":{"EWMA":{"sufficient":true,"n_cards":160,"grid_point_at":"2026-11-03T12:37:00",
                                       "insufficient_reason":null},
                               "MACD":{"sufficient":true,"n_cards":120,"grid_point_at":"2026-11-03T12:37:00",
                                       "insufficient_reason":null}},
                 "ready":true}
                """);
        assertThat(r.readyForSwitch()).isTrue();
        assertThat(r.algorithm("MACD").nCards()).isEqualTo(120);
    }

    @Test
    void refusesWithoutServiceKey() {
        AnalyticsClient client = new AnalyticsClient("http://an-app:8020", "", 5);
        assertThatThrownBy(() -> client.setReadiness(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
}