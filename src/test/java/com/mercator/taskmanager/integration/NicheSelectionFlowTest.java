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
 * Первичный подбор ниши до active: референс → запрос → пачки → ранжирование.
 * Подбор останавливается, как только отобрано plan-count карточек; каждая
 * следующая пачка — на недостающее число, не больше select-batch-size (3).
 * Близость задаёт «#NN» в названии карточки (0.NN).
 *
 * Кандидат из выдачи сразу получает карточку со статусом candidate (первый
 * замер, content_txt); отбор в нишу переводит её в active.
 */
class NicheSelectionFlowTest extends NicheTestSupport {

    /** Страта-подсказка #89 #75, plan 3: первая пачка вместе со стратой — 5 кандидатов, сразу active. */
    @Test
    void stopsWhenPlanCountSelected() {
        createHintStratum(89, 75);
        NicheRef niche = createNiche(true, 3);
        UUID id = niche.id();

        // ---------- 1. Референс — полный разбор ----------
        scheduler.tick();
        assertThat(nicheStatus(id)).isEqualTo("reference_parsing");
        UUID refTask = uuid("SELECT reference_task_id FROM niche WHERE id = ?", id);
        assertThat(refTask).isNotNull();
        assertThat(parseTask(refTask).get("full_content")).isEqualTo(true);

        parseConsumer.handle(referenceResult(refTask));
        assertThat(nicheStatus(id)).isEqualTo("query_generation");
        assertThat(str("SELECT reference_snapshot->>'name' FROM niche WHERE id = ?", id))
                .isEqualTo("Худи для девочки розовое");

        // ---------- 2. Запрос нейросети и первая пачка ----------
        scheduler.tick();
        assertThat(nicheStatus(id)).isEqualTo("selecting");
        assertThat(str("SELECT search_query FROM niche WHERE id = ?", id))
                .isEqualTo("розовое худи для девочки");
        assertThat(count("SELECT count(*) FROM niche_stratum_hint WHERE niche_id = ?", id)).isEqualTo(1);

        UUID batch1 = selectTaskId(id);
        Map<?, ?> task1 = selectTask(batch1);
        assertThat(task1.get("count")).isEqualTo(3);  // отобрано 0 из 3
        assertThat(task1.get("is_seasonal")).isEqualTo(false);
        assertThat(excludeSkus(task1)).containsExactlyInAnyOrder(stratumSku(89), stratumSku(75));

        // ---------- 3. Пачка 1: #80 #70 #60 — карточки-кандидаты ----------
        selectConsumer.handle(selectResult(batch1, 80, 70, 60));
        assertThat(nicheStatus(id)).isEqualTo("ranking");
        assertThat(cardStatus(searchSku(80))).isEqualTo("candidate");
        assertThat(str("SELECT content_txt->>'name' FROM card WHERE sku = ?", searchSku(70)))
                .isEqualTo("Худи #70 для девочки");

        // ---------- 4. Ранжирование — отобрано 3 из 3, подбор завершён ----------
        scheduler.tick();
        assertThat(members(id)).containsExactlyInAnyOrder(stratumSku(89), searchSku(80), stratumSku(75));
        assertThat(nicheStatus(id)).isEqualTo("active");
        assertThat(selectTaskId(id)).isNull();  // автодобор не должен считать поиск идущим
        assertThat(redis.opsForList().size(SELECT_TASKS)).isEqualTo(1L);  // второй пачки нет

        // отобранная карточка — в обходе; не вошедшие — кандидаты с карточками candidate
        assertThat(cardStatus(searchSku(80))).isEqualTo("active");
        assertThat(candidateStatus(id, searchSku(70))).isEqualTo("candidate");
        assertThat(candidateStatus(id, searchSku(60))).isEqualTo("candidate");
        assertThat(cardStatus(searchSku(70))).isEqualTo("candidate");
        assertThat(cardStatus(searchSku(60))).isEqualTo("candidate");

        // первые замеры записаны при подборе для всех кандидатов из выдачи: C80, C70, C60
        verify(measurementRepository, times(3)).insert(any(Measurement.class));
    }

    /** Страта-подсказка #89, plan 5: после первой пачки отобрано 3 — вторая пачка на остаток 2. */
    @Test
    void nextBatchRequestsRemainder() {
        createHintStratum(89);
        NicheRef niche = createNiche(true, 5);
        UUID id = niche.id();

        scheduler.tick();
        parseConsumer.handle(referenceResult(uuid("SELECT reference_task_id FROM niche WHERE id = ?", id)));
        scheduler.tick();
        assertThat(nicheStatus(id)).isEqualTo("selecting");

        // ---------- 1. Пачка 1 на 3: пришли #80 #70 — вместе с картой страты отобрано 3 из 5 ----------
        UUID batch1 = selectTaskId(id);
        assertThat(selectTask(batch1).get("count")).isEqualTo(3);

        selectConsumer.handle(selectResult(batch1, 80, 70));
        scheduler.tick();  // ранжирование + отправка пачки 2

        assertThat(members(id)).containsExactlyInAnyOrder(stratumSku(89), searchSku(80), searchSku(70));
        assertThat(nicheStatus(id)).isEqualTo("selecting");

        // ---------- 2. Пачка 2 — на остаток: min(3, 5 − 3) = 2 ----------
        UUID batch2 = selectTaskId(id);
        assertThat(batch2).isNotNull().isNotEqualTo(batch1);
        Map<?, ?> task2 = selectTask(batch2);
        assertThat(task2.get("count")).isEqualTo(2);
        assertThat(excludeSkus(task2)).containsExactlyInAnyOrder(
                stratumSku(89), searchSku(80), searchSku(70));

        // ---------- 3. Пачка 2: #95 #60 — отобрано 5 из 5, подбор завершён ----------
        selectConsumer.handle(selectResult(batch2, 95, 60));
        scheduler.tick();

        assertThat(members(id)).containsExactlyInAnyOrder(
                searchSku(95), stratumSku(89), searchSku(80), searchSku(70), searchSku(60));
        assertThat(nicheStatus(id)).isEqualTo("active");
        assertThat(selectTaskId(id)).isNull();

        // первые замеры при подборе для кандидатов из выдачи: C80, C70, C95, C60
        verify(measurementRepository, times(4)).insert(any(Measurement.class));
    }
}