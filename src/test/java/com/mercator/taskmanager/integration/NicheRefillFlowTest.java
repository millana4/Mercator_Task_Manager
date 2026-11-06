package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.clickhouse.Measurement;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Автодобор. После bringToActive (страта #75 #55, plan 3, пачка #80 #70 #60):
 * в нише C80, S75, C70; кандидаты: C60 (карточка candidate), S55 (активная карточка страты).
 * Выбывают C80 и C70 → уборка, поиск на 4, ответ #97 #40 (карточки candidate) →
 * C97 сразу (новый кандидат поиска), C60 — на проверку (исторический кандидат),
 * задача проверки — с полным разбором.
 */
class NicheRefillFlowTest extends NicheTestSupport {

    /** Довести до момента, когда C97 взят, а C60 на проверке. Возвращает id ниши. */
    private UUID refillUntilVerification() {
        createHintStratum(75, 55);
        UUID id = createNiche(true, 3).id();
        bringToActive(id);
        assertThat(nicheStatus(id)).isEqualTo("active");
        assertThat(members(id)).containsExactlyInAnyOrder(searchSku(80), stratumSku(75), searchSku(70));
        assertThat(cardStatus(searchSku(60))).isEqualTo("candidate");

        // ---------- выбытие по жизненному циклу ----------
        exec("UPDATE card SET status = 'dropped', dropped_at = now() WHERE sku IN (?, ?)",
                searchSku(80), searchSku(70));
        scheduler.tick();

        assertThat(members(id)).containsExactly(stratumSku(75));
        assertThat(candidateStatus(id, searchSku(80))).isEqualTo("unavailable");
        assertThat(candidateStatus(id, searchSku(70))).isEqualTo("unavailable");

        // ---------- поиск автодобора ----------
        UUID refillTask = selectTaskId(id);
        assertThat(refillTask).isNotNull();
        assertThat(str("SELECT refill_requested_at::text FROM niche WHERE id = ?", id)).isNotNull();
        Map<?, ?> task = selectTask(refillTask);
        assertThat(task.get("count")).isEqualTo(4);  // дефицит 2 × множитель 2
        assertThat(excludeSkus(task)).contains(
                searchSku(80), searchSku(70), searchSku(60),
                stratumSku(75), stratumSku(55));

        selectConsumer.handle(selectResult(refillTask, 97, 40));
        assertThat(selectTaskId(id)).isNull();
        assertThat(candidateStatus(id, searchSku(97))).isEqualTo("candidate");
        assertThat(cardStatus(searchSku(97))).isEqualTo("candidate");

        // ---------- выбор: C97 — сразу, C60 — на проверку с полным разбором ----------
        scheduler.tick();
        assertThat(members(id)).containsExactlyInAnyOrder(stratumSku(75), searchSku(97));
        assertThat(cardStatus(searchSku(97))).isEqualTo("active");
        UUID verifyTask = uuid("SELECT verify_task_id FROM niche_candidate WHERE niche_id = ? AND sku = ?",
                id, searchSku(60));
        assertThat(verifyTask).isNotNull();
        assertThat(parseTask(verifyTask).get("full_content")).isEqualTo(true);
        assertThat(selectTaskId(id)).isNull();  // дефицит закрыт — нового поиска нет
        return id;
    }

    @Test
    void outOfStockCandidateIsReplacedByNextOne() {
        UUID id = refillUntilVerification();
        UUID verifyTask = uuid("SELECT verify_task_id FROM niche_candidate WHERE niche_id = ? AND sku = ?",
                id, searchSku(60));

        parseConsumer.handle(verifyResult(verifyTask, searchSku(60), false));
        assertThat(candidateStatus(id, searchSku(60))).isEqualTo("unavailable");
        assertThat(cardStatus(searchSku(60))).isEqualTo("candidate");  // в обход не попадает

        // освободившееся место — следующему: активная карточка страты S55, без проверки
        scheduler.tick();
        assertThat(members(id)).containsExactlyInAnyOrder(
                stratumSku(75), searchSku(97), stratumSku(55));
    }

    @Test
    void inStockCandidateCardBecomesActive() {
        UUID id = refillUntilVerification();
        UUID verifyTask = uuid("SELECT verify_task_id FROM niche_candidate WHERE niche_id = ? AND sku = ?",
                id, searchSku(60));

        parseConsumer.handle(verifyResult(verifyTask, searchSku(60), true));

        assertThat(cardStatus(searchSku(60))).isEqualTo("active");
        assertThat(candidateStatus(id, searchSku(60))).isEqualTo("selected");
        assertThat(members(id)).containsExactlyInAnyOrder(
                stratumSku(75), searchSku(97), searchSku(60));
        // замеры: C80, C70, C60 (первичный подбор) + C97, C40 (поиск автодобора) + C60 (проверка)
        verify(measurementRepository, times(6)).insert(any(Measurement.class));
    }
}