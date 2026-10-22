package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.service.PriceBoostService;
import com.mercator.taskmanager.service.PriceBoostService.Decision;
import com.mercator.taskmanager.service.PriceBoostService.Event;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Учащённый обход при скачке цены: решение по замеру и запись в карточку. */
class PriceBoostServiceUnitTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-22T12:00:00+03:00");
    private static final double THR = 0.35;
    private static final double TOL = 0.03;
    private static final long HOLD = 48;

    private static Decision decide(Double last, Double base, OffsetDateTime started, Double price,
                                   OffsetDateTime at, boolean cap) {
        return PriceBoostService.decide(last, base, started, price, at, cap, THR, TOL, HOLD);
    }

    @Test
    void jumpAboveThresholdStartsBoost() {
        Decision d = decide(1000.0, null, null, 1400.0, T0, false);
        assertThat(d.event()).isEqualTo(Event.STARTED);
        assertThat(d.boostBasePrice()).isEqualTo(1000.0);
        assertThat(d.boostStartedAt()).isEqualTo(T0);
        assertThat(d.lastPrice()).isEqualTo(1400.0);
    }

    @Test
    void jumpIsSymmetricInLog() {
        // ln(0.70) = −0.357, |·| > ln(1.35) = 0.300 — падение на 30% тоже скачок
        assertThat(decide(1000.0, null, null, 700.0, T0, false).event()).isEqualTo(Event.STARTED);
        assertThat(decide(1000.0, null, null, 1340.0, T0, false).event()).isEqualTo(Event.NONE);
    }

    @Test
    void capSkipsBoost() {
        Decision d = decide(1000.0, null, null, 1400.0, T0, true);
        assertThat(d.event()).isEqualTo(Event.SKIPPED_CAP);
        assertThat(d.boostStartedAt()).isNull();
        assertThat(d.lastPrice()).isEqualTo(1400.0);
    }

    @Test
    void returnToBaseStopsBoost() {
        Decision d = decide(1400.0, 1000.0, T0, 1020.0, T0.plusHours(5), false);
        assertThat(d.event()).isEqualTo(Event.RETURNED);
        assertThat(d.boostStartedAt()).isNull();
        assertThat(d.boostBasePrice()).isNull();
    }

    @Test
    void holdTimeEstablishesNewPrice() {
        assertThat(decide(1400.0, 1000.0, T0, 1390.0, T0.plusHours(47), false).event())
                .isEqualTo(Event.NONE);
        Decision d = decide(1400.0, 1000.0, T0, 1390.0, T0.plusHours(48), false);
        assertThat(d.event()).isEqualTo(Event.ESTABLISHED);
        assertThat(d.boostStartedAt()).isNull();
    }

    @Test
    void measurementWithoutPriceChangesNothing() {
        Decision d = decide(1000.0, 1000.0, T0, null, T0.plusHours(1), false);
        assertThat(d.event()).isEqualTo(Event.NONE);
        assertThat(d.lastPrice()).isEqualTo(1000.0);
        assertThat(d.boostStartedAt()).isEqualTo(T0);
    }

    @Test
    void firstPriceOnlyRemembered() {
        Decision d = decide(null, null, null, 1500.0, T0, false);
        assertThat(d.event()).isEqualTo(Event.NONE);
        assertThat(d.lastPrice()).isEqualTo(1500.0);
    }

    @Test
    void onMeasuredSavesBoostState() {
        CardRepository repo = mock(CardRepository.class);
        CardEntity card = new CardEntity();
        card.setLastPrice(1000.0);
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(card));
        when(repo.countBoosted()).thenReturn(0L);

        new PriceBoostService(repo, THR, TOL, HOLD, 30).onMeasured(id, 1500.0, T0);

        verify(repo).save(card);
        assertThat(card.getLastPrice()).isEqualTo(1500.0);
        assertThat(card.getBoostBasePrice()).isEqualTo(1000.0);
        assertThat(card.getBoostStartedAt()).isEqualTo(T0);
    }

    @Test
    void onMeasuredRespectsCap() {
        CardRepository repo = mock(CardRepository.class);
        CardEntity card = new CardEntity();
        card.setLastPrice(1000.0);
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(card));
        when(repo.countBoosted()).thenReturn(30L);

        new PriceBoostService(repo, THR, TOL, HOLD, 30).onMeasured(id, 1500.0, T0);

        assertThat(card.getBoostStartedAt()).isNull();
        assertThat(card.getLastPrice()).isEqualTo(1500.0);
    }

    @Test
    void onMeasuredWithoutPriceDoesNotSave() {
        CardRepository repo = mock(CardRepository.class);
        CardEntity card = new CardEntity();
        card.setLastPrice(1000.0);
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(card));

        new PriceBoostService(repo, THR, TOL, HOLD, 30).onMeasured(id, null, T0);

        verify(repo, never()).save(any());
    }
}