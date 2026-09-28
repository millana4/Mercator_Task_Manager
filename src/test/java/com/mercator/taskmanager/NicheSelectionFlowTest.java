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
 * Путь ниши до active: референс → запрос → пачки → ранжирование с вытеснением.
 * Страта-подсказка: #89, #75; plan_count = 3; подбор завершается при 6 кандидатах из выдачи.
 */
class NicheSelectionFlowTest extends NicheTestSupport {

    @Test
    void referenceQueryBatchesRankingToActive() {
        createHintStratum(89, 75);
        NicheRef niche = createNiche(true, 3);
        UUID id = niche.id();

        // ---------- 1. Референс ----------
        scheduler.tick();
        assertThat(nicheStatus(id)).isEqualTo("reference_parsing");
        UUID refTask = uuid("SELECT reference_task_id FROM niche WHERE id = ?", id);
        assertThat(refTask).isNotNull();

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
        assertThat(task1.get("count")).isEqualTo(3);
        assertThat(task1.get("is_seasonal")).isEqualTo(false);
        assertThat(excludeSkus(task1)).containsExactlyInAnyOrder(stratumSku(89), stratumSku(75));

        // ---------- 3. Пачка 1: #80 #70 #60 ----------
        selectConsumer.handle(selectResult(batch1, 80, 70, 60));
        assertThat(nicheStatus(id)).isEqualTo("ranking");

        scheduler.tick();  // ранжирование + отправка пачки 2
        assertThat(members(id)).containsExactlyInAnyOrder(stratumSku(89), searchSku(80), stratumSku(75));
        assertThat(nicheStatus(id)).isEqualTo("selecting");
        UUID batch2 = selectTaskId(id);
        assertThat(batch2).isNotNull().isNotEqualTo(batch1);
        assertThat(excludeSkus(selectTask(batch2))).hasSize(5);

        // ---------- 4. Пачка 2: #95 #93 #50 — вытеснение и завершение ----------
        selectConsumer.handle(selectResult(batch2, 95, 93, 50));
        scheduler.tick();

        assertThat(members(id)).containsExactlyInAnyOrder(searchSku(95), searchSku(93), stratumSku(89));
        assertThat(nicheStatus(id)).isEqualTo("active");
        assertThat(selectTaskId(id)).isNull();  // автодобор не должен считать поиск идущим

        // вытесненная карточка, созданная для ниши, выбыла; карточка страты осталась в страте
        assertThat(cardStatus(searchSku(80))).isEqualTo("dropped");
        assertThat(cardStatus(stratumSku(75))).isEqualTo("active");
        assertThat(candidateStatus(id, searchSku(80))).isEqualTo("candidate");
        assertThat(candidateStatus(id, stratumSku(75))).isEqualTo("candidate");

        // первые замеры записаны для созданных карточек: C80, C95, C93
        verify(measurementRepository, times(3)).insert(any(Measurement.class));
    }
}