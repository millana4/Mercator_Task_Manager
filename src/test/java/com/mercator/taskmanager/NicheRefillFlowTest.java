package com.mercator.taskmanager;

import com.mercator.taskmanager.clickhouse.Measurement;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Автодобор. После bringToActive: в нише C95, C93, S89; кандидаты: C80 (карточка
 * выбыла при вытеснении), S75 (активная карточка страты), C70, C60, C50.
 * Выбывают C95 и C93 → уборка, поиск на 4, ответ #97 #40 →
 * C97 сразу, C80 — на проверку.
 */
class NicheRefillFlowTest extends NicheTestSupport {

    /** Довести до момента, когда C97 взят, а C80 на проверке. Возвращает id ниши. */
    private UUID refillUntilVerification() {
        createHintStratum(89, 75);
        UUID id = createNiche(true, 3).id();
        bringToActive(id);
        assertThat(nicheStatus(id)).isEqualTo("active");

        // ---------- выбытие по жизненному циклу ----------
        exec("UPDATE card SET status = 'dropped', dropped_at = now() WHERE sku IN (?, ?)",
                searchSku(95), searchSku(93));
        scheduler.tick();

        assertThat(members(id)).containsExactly(stratumSku(89));
        assertThat(candidateStatus(id, searchSku(95))).isEqualTo("unavailable");
        assertThat(candidateStatus(id, searchSku(93))).isEqualTo("unavailable");

        // ---------- поиск автодобора ----------
        UUID refillTask = selectTaskId(id);
        assertThat(refillTask).isNotNull();
        assertThat(str("SELECT refill_requested_at::text FROM niche WHERE id = ?", id)).isNotNull();
        Map<?, ?> task = selectTask(refillTask);
        assertThat(task.get("count")).isEqualTo(4);  // дефицит 2 × множитель 2
        assertThat(excludeSkus(task)).contains(
                searchSku(80), searchSku(70), searchSku(60), searchSku(50),
                searchSku(95), searchSku(93), stratumSku(89), stratumSku(75));

        selectConsumer.handle(selectResult(refillTask, 97, 40));
        assertThat(selectTaskId(id)).isNull();
        assertThat(candidateStatus(id, searchSku(97))).isEqualTo("candidate");

        // ---------- выбор: C97 — сразу, C80 — на проверку ----------
        scheduler.tick();
        assertThat(members(id)).containsExactlyInAnyOrder(stratumSku(89), searchSku(97));
        assertThat(uuid("SELECT verify_task_id FROM niche_candidate WHERE niche_id = ? AND sku = ?",
                id, searchSku(80))).isNotNull();
        assertThat(selectTaskId(id)).isNull();  // дефицит закрыт — нового поиска нет
        return id;
    }

    @Test
    void outOfStockCandidateIsReplacedByNextOne() {
        UUID id = refillUntilVerification();
        UUID verifyTask = uuid("SELECT verify_task_id FROM niche_candidate WHERE niche_id = ? AND sku = ?",
                id, searchSku(80));

        parseConsumer.handle(verifyResult(verifyTask, searchSku(80), false));
        assertThat(candidateStatus(id, searchSku(80))).isEqualTo("unavailable");
        assertThat(cardStatus(searchSku(80))).isEqualTo("dropped");

        // освободившееся место — следующему: активная карточка страты S75, без проверки
        scheduler.tick();
        assertThat(members(id)).containsExactlyInAnyOrder(
                stratumSku(89), searchSku(97), stratumSku(75));
    }

    @Test
    void inStockCandidateReturnsDroppedCard() {
        UUID id = refillUntilVerification();
        UUID verifyTask = uuid("SELECT verify_task_id FROM niche_candidate WHERE niche_id = ? AND sku = ?",
                id, searchSku(80));

        parseConsumer.handle(verifyResult(verifyTask, searchSku(80), true));

        assertThat(cardStatus(searchSku(80))).isEqualTo("active");
        assertThat(str("SELECT dropped_at::text FROM card WHERE sku = ?", searchSku(80))).isNull();
        assertThat(candidateStatus(id, searchSku(80))).isEqualTo("selected");
        assertThat(members(id)).containsExactlyInAnyOrder(
                stratumSku(89), searchSku(97), searchSku(80));
        // замеры: C80, C95, C93 (первичный подбор) + C97 (автодобор) + C80 (проверка)
        verify(measurementRepository, times(5)).insert(any(Measurement.class));
    }
}