package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.prod_specs.LabelResult;
import com.mercator.taskmanager.prod_specs.ProdSpecsCardLabeler;
import com.mercator.taskmanager.prod_specs.ProdSpecsLabelQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Очередь разметки: итоги пачки, остановка по ошибке авторизации, ошибка одной карточки
 * не останавливает остальные, повторная постановка карточки в работе. Разметчик — мок.
 */
class ProdSpecsLabelQueueTest {

    private final ProdSpecsCardLabeler labeler = mock(ProdSpecsCardLabeler.class);
    private final ProdSpecsLabelQueue queue = new ProdSpecsLabelQueue(labeler, 0);

    @AfterEach
    void stop() {
        queue.shutdown();
    }

    @Test
    void batchCountsResults() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(labeler.label(a)).thenReturn(LabelResult.LABELED);
        when(labeler.label(b)).thenReturn(LabelResult.UP_TO_DATE);
        when(labeler.label(c)).thenReturn(LabelResult.FAILED);

        Map<LabelResult, Integer> stats = queue.processBatch(List.of(a, b, c));

        assertThat(stats).containsEntry(LabelResult.LABELED, 1)
                .containsEntry(LabelResult.UP_TO_DATE, 1)
                .containsEntry(LabelResult.FAILED, 1);
    }

    @Test
    void authErrorStopsTheBatch() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(labeler.label(first)).thenThrow(new LlmException(LlmException.Kind.AUTH, "нет ключей"));

        queue.processBatch(List.of(first, second));

        verify(labeler, never()).label(second);
    }

    @Test
    void unexpectedErrorDoesNotStopOthers() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(labeler.label(first)).thenThrow(new IllegalStateException("сбой"));
        when(labeler.label(second)).thenReturn(LabelResult.LABELED);

        Map<LabelResult, Integer> stats = queue.processBatch(List.of(first, second));

        assertThat(stats).containsEntry(LabelResult.FAILED, 1).containsEntry(LabelResult.LABELED, 1);
    }

    @Test
    void enqueueCountsDuplicatesOnce() {
        UUID id = UUID.randomUUID();
        when(labeler.label(id)).thenReturn(LabelResult.LABELED);

        assertThat(queue.enqueue(List.of(id, id))).isEqualTo(1);

        verify(labeler, timeout(2000).times(1)).label(id);
    }

    @Test
    void pendingCardIsNotEnqueuedAgain() throws Exception {
        UUID id = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(labeler.label(id)).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return LabelResult.LABELED;
        });

        assertThat(queue.enqueue(List.of(id))).isEqualTo(1);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.enqueue(List.of(id))).isZero();   // карточка в работе

        release.countDown();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(queue.enqueue(List.of(id))).isEqualTo(1));
        verify(labeler, timeout(2000).times(2)).label(id);
    }
}