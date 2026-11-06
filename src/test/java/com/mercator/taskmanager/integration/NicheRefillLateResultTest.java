package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.entity.NicheStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Таймаут поиска автодобора (12 ч) и опоздавший результат.
 * Ниша как в NicheRefillFlowTest: страта #75 #55, plan 3, пачка #80 #70 #60;
 * выбывают C80 и C70 → поиск автодобора. Ожидание ответа имитируется сдвигом
 * refill_requested_at назад.
 */
class NicheRefillLateResultTest extends NicheTestSupport {

    /** Довести нишу до отправленного поиска автодобора. Возвращает id ниши. */
    private UUID startRefillSearch() {
        createHintStratum(75, 55);
        UUID id = createNiche(true, 3).id();
        bringToActive(id);
        assertThat(nicheStatus(id)).isEqualTo("active");

        exec("UPDATE card SET status = 'dropped', dropped_at = now() WHERE sku IN (?, ?)",
                searchSku(80), searchSku(70));
        scheduler.tick();
        assertThat(selectTaskId(id)).isNotNull();
        return id;
    }

    /** Поиск ждёт ответа hours часов; тик планировщика проверяет таймаут. */
    private void waitHours(UUID id, int hours) {
        exec("UPDATE niche SET refill_requested_at = now() - make_interval(hours => ?) WHERE id = ?",
                hours, id);
        scheduler.tick();
    }

    private UUID lostTaskId(UUID id) {
        return uuid("SELECT refill_lost_task_id FROM niche WHERE id = ?", id);
    }

    private String emptyCount(UUID id) {
        return str("SELECT refill_empty_count::text FROM niche WHERE id = ?", id);
    }

    private String choicePending(UUID id) {
        return str("SELECT refill_choice_pending::text FROM niche WHERE id = ?", id);
    }

    @Test
    void searchIsKeptWithinTimeout() {
        UUID id = startRefillSearch();
        UUID task = selectTaskId(id);

        waitHours(id, 11);

        assertThat(selectTaskId(id)).isEqualTo(task);
        assertThat(lostTaskId(id)).isNull();
    }

    @Test
    void lateResultWithCandidatesIsAcceptedAndCurrentSearchKept() {
        UUID id = startRefillSearch();
        UUID lateTask = selectTaskId(id);

        waitHours(id, 13);
        assertThat(selectTaskId(id)).isNull();
        assertThat(lostTaskId(id)).isEqualTo(lateTask);

        scheduler.tick();  // интервал 6 ч прошёл — уходит новый поиск
        UUID newTask = selectTaskId(id);
        assertThat(newTask).isNotNull().isNotEqualTo(lateTask);

        selectConsumer.handle(selectResult(lateTask, 97, 40));

        assertThat(candidateStatus(id, searchSku(97))).isEqualTo("candidate");
        assertThat(candidateStatus(id, searchSku(40))).isEqualTo("candidate");
        assertThat(selectTaskId(id)).isEqualTo(newTask);  // текущий поиск не сброшен
        assertThat(lostTaskId(id)).isNull();
        assertThat(choicePending(id)).isEqualTo("true");
        assertThat(emptyCount(id)).isEqualTo("0");
    }

    @Test
    void lateEmptyResultCountsAsExhausted() {
        UUID id = startRefillSearch();
        UUID lateTask = selectTaskId(id);
        waitHours(id, 13);

        selectConsumer.handle(selectResult(lateTask, 80, 60));  // все уже известны нише

        assertThat(emptyCount(id)).isEqualTo("1");
        assertThat(choicePending(id)).isEqualTo("false");
        assertThat(lostTaskId(id)).isNull();
    }

    @Test
    void lateResultForInactiveNicheIsSkipped() {
        UUID id = startRefillSearch();
        UUID lateTask = selectTaskId(id);
        waitHours(id, 13);
        exec("UPDATE niche SET status = ? WHERE id = ?", NicheStatus.FAILED, id);

        selectConsumer.handle(selectResult(lateTask, 97));

        assertThat(count("SELECT count(*) FROM niche_candidate WHERE niche_id = ? AND sku = ?",
                id, searchSku(97))).isZero();
        assertThat(lostTaskId(id)).isNull();
    }
}