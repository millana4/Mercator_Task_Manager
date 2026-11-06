package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.service.CardLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Юнит-тест жизненного цикла карточки — БЕЗ Spring, БД и Kafka.
 * CardRepository замокан; проверяем чистую логику переходов статусов,
 * включая grace-период out_of_stock и мгновенное выбытие not_found.
 * Порог неудач = 5, grace = 7 суток (как дефолты сервиса).
 */
class CardLifecycleServiceUnitTest {

    private CardRepository repo;
    private CardLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        repo = mock(CardRepository.class);
        lifecycle = new CardLifecycleService(repo, 5, 7);
        // save() возвращает переданную карточку (как настоящий JPA-save).
        when(repo.save(any(CardEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // Готовит карточку с заданным начальным состоянием и вешает на findById.
    private CardEntity givenCard(UUID id) {
        CardEntity card = new CardEntity();
        card.setSku("SKU-TEST");
        card.setStatus("active");
        card.setFailedAttempts(0);
        when(repo.findById(id)).thenReturn(Optional.of(card));
        return card;
    }

    @Test
    void markInStock_setsActiveAndClearsCounters() {
        UUID id = UUID.randomUUID();
        CardEntity card = givenCard(id);
        card.setStatus("stale");
        card.setFailedAttempts(3);
        card.setUnavailableSince(OffsetDateTime.now());
        card.setOutOfStockSince(OffsetDateTime.now());

        lifecycle.markInStock(id);

        assertEquals("active", card.getStatus());
        assertEquals(0, card.getFailedAttempts());
        assertNull(card.getUnavailableSince());
        assertNull(card.getOutOfStockSince());
        verify(repo).save(card);
    }

    @Test
    void markOutOfStock_firstTime_setsSinceAndStaysActive() {
        UUID id = UUID.randomUUID();
        CardEntity card = givenCard(id);
        assertNull(card.getOutOfStockSince());

        lifecycle.markOutOfStock(id);

        assertEquals("active", card.getStatus(), "карточка жива, товар просто кончился");
        assertNotNull(card.getOutOfStockSince(), "начало отсутствия зафиксировано");
        assertEquals(0, card.getFailedAttempts());
        assertNull(card.getUnavailableSince());
    }

    @Test
    void markOutOfStock_withinGrace_staysActive() {
        UUID id = UUID.randomUUID();
        CardEntity card = givenCard(id);
        // Нет в наличии уже 3 суток — меньше порога 7, ещё ждём.
        card.setOutOfStockSince(OffsetDateTime.now().minusDays(3));

        lifecycle.markOutOfStock(id);

        assertEquals("active", card.getStatus());
        assertNull(card.getDroppedAt());
    }

    @Test
    void markOutOfStock_afterGrace_dropsCard() {
        UUID id = UUID.randomUUID();
        CardEntity card = givenCard(id);
        // Нет в наличии 8 суток — превышен порог 7, списываем.
        card.setOutOfStockSince(OffsetDateTime.now().minusDays(8));

        lifecycle.markOutOfStock(id);

        assertEquals("dropped", card.getStatus());
        assertNotNull(card.getDroppedAt());
    }

    @Test
    void markOutOfStock_exactlyAtThreshold_dropsCard() {
        UUID id = UUID.randomUUID();
        CardEntity card = givenCard(id);
        // Ровно 7 суток + запас минут — порог days >= 7 сработает.
        card.setOutOfStockSince(OffsetDateTime.now().minusDays(7).minusMinutes(1));

        lifecycle.markOutOfStock(id);

        assertEquals("dropped", card.getStatus(), "порог days >= notifyGraceDays");
    }

    @Test
    void markNotFound_dropsImmediately() {
        UUID id = UUID.randomUUID();
        CardEntity card = givenCard(id);

        lifecycle.markNotFound(id);

        assertEquals("dropped", card.getStatus());
        assertNotNull(card.getDroppedAt());
    }

    @Test
    void markFailure_belowThreshold_becomesStale() {
        UUID id = UUID.randomUUID();
        CardEntity card = givenCard(id);

        for (int i = 0; i < 4; i++) lifecycle.markFailure(id);

        assertEquals("stale", card.getStatus());
        assertEquals(4, card.getFailedAttempts());
        assertNotNull(card.getUnavailableSince());
        assertNull(card.getDroppedAt());
    }

    @Test
    void markFailure_atThreshold_dropsCard() {
        UUID id = UUID.randomUUID();
        CardEntity card = givenCard(id);

        for (int i = 0; i < 5; i++) lifecycle.markFailure(id);

        assertEquals("dropped", card.getStatus());
        assertEquals(5, card.getFailedAttempts());
        assertNotNull(card.getDroppedAt());
    }

    @Test
    void unknownCard_isNoop() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        lifecycle.markInStock(id);
        lifecycle.markOutOfStock(id);
        lifecycle.markNotFound(id);
        lifecycle.markFailure(id);

        verify(repo, never()).save(any());
    }
}