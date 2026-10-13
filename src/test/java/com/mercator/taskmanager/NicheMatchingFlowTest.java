package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.matching.DuplicateFilter;
import com.mercator.taskmanager.matching.NicheScorer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Новый подбор аналогов ниши: жёсткий фильтр (вид изделия, пол), отсев задвоений
 * (та же модель, дубль по тексту), подстановка размера через проверку, пропуск
 * кандидатов без полного набора, ниша без референса.
 * Текстовая близость — «#NN» в «Цвете» (0,NN); цены одинаковые, если не сказано иное.
 * Задача подбора просит не больше 3 карточек: в выдаче — до трёх кандидатов,
 * остальное — карточки страт.
 */
class NicheMatchingFlowTest extends NicheTestSupport {

    /** Брюки — другой вид, худи для мальчика — другой пол; детское худи (для всех) проходит. */
    @Test
    void filtersOtherTypeAndGender() {
        StratumEntity st = createHintStratum();
        addStratumCard(st, hoodie(stratumSku(90), 90));
        UUID id = createNiche(true, 3).id();

        Map<String, Object> trousers = hoodie(searchSku(99), "Брюки #99 для девочки", 99, true);
        chars(trousers).put("Тип", "Брюки");
        Map<String, Object> boys = hoodie(searchSku(98), "Худи #98 для мальчика", 98, true);
        chars(boys).put("Пол", "Мальчики");
        Map<String, Object> kids = hoodie(searchSku(95), "Худи #95 детское", 95, true);
        chars(kids).remove("Пол");

        toRanking(id, referenceCard(), List.of(trousers, boys, kids));
        scheduler.tick();

        assertThat(members(id)).containsExactlyInAnyOrder(searchSku(95), stratumSku(90));
        assertThat(rejectReason(id, searchSku(99))).isEqualTo(NicheScorer.REJECT_TYPE);
        assertThat(rejectReason(id, searchSku(98))).isEqualTo(NicheScorer.REJECT_GENDER);
        assertThat(similarity(id, searchSku(99))).isNull();
        assertThat(similarity(id, searchSku(98))).isNull();
        assertThat(candidateStatus(id, searchSku(98))).isEqualTo("candidate");
        assertThat(nicheStatus(id)).isEqualTo("selecting");  // 2 из 3
    }

    /**
     * #98 — та же модель, что референс (sku референса в переключателе «Цвет»);
     * #94 — дубль #96 по тексту (признаки, кроме цвета, совпадают, цена −3%);
     * S92 — те же признаки, но на 50% дороже — не дубль.
     */
    @Test
    void rejectsSameModelAndTextDuplicate() {
        StratumEntity st = createHintStratum();
        Map<String, Object> pricier = hoodie(stratumSku(92), 92);
        chars(pricier).put("Коллекция", "школа");
        setPrice(pricier, 2400.0);
        addStratumCard(st, pricier);
        UUID id = createNiche(true, 3).id();

        Map<String, Object> sameModel = hoodie(searchSku(98), 98);
        addAspect(sameModel, "Color", "colorsMix", List.of(
                aspectValue(searchSku(98), "розовый", PRICE),
                aspectValue(REFERENCE_SKU, "голубой", PRICE)));
        Map<String, Object> first = hoodie(searchSku(96), 96);
        chars(first).put("Коллекция", "школа");
        Map<String, Object> twin = hoodie(searchSku(94), 94);
        chars(twin).put("Коллекция", "школа");
        setPrice(twin, 1550.0);

        toRanking(id, referenceCard(), List.of(sameModel, first, twin));
        scheduler.tick();

        assertThat(members(id)).containsExactlyInAnyOrder(searchSku(96), stratumSku(92));
        assertThat(rejectReason(id, searchSku(98))).isEqualTo(DuplicateFilter.REJECT_SAME_MODEL);
        assertThat(rejectReason(id, searchSku(94))).isEqualTo(DuplicateFilter.REJECT_TEXT_DUPLICATE);
        assertThat(rejectReason(id, stratumSku(92))).isNull();
        assertThat(nicheStatus(id)).isEqualTo("selecting");  // 2 из 3
    }

    /**
     * Референс — рост 122. #94 — размер 146, в переключателе «Размер» есть 122 со своим sku:
     * место резервируется, проверка — по sku размера 122. Ниша остаётся selecting
     * (в нише 1 + на проверке 1 из 3), результат проверки принимается в selecting.
     */
    @Test
    void substitutedSizeIsVerifiedAndAcceptedWhileSelecting() {
        createHintStratum();
        UUID id = createNiche(true, 3).id();

        Map<String, Object> reference = referenceCard();
        chars(reference).put("Российский размер", "122");
        Map<String, Object> fits = hoodie(searchSku(96), 96);
        chars(fits).put("Российский размер", "122");
        Map<String, Object> other = hoodie(searchSku(94), 94);
        chars(other).put("Российский размер", "146");
        String size122 = prefix + "Z122";
        addAspect(other, "size", "sizes", List.of(
                aspectValue(size122, "122 RU / 122", PRICE),
                aspectValue(searchSku(94), "146 RU / 146", PRICE)));

        toRanking(id, reference, List.of(fits, other));
        scheduler.tick();

        // ---------- 1. Подставлен размер 122: место зарезервировано, проверка по его sku ----------
        assertThat(members(id)).containsExactly(searchSku(96));
        assertThat(str("SELECT analog_sku FROM niche_candidate WHERE niche_id = ? AND sku = ?",
                id, searchSku(94))).isEqualTo(size122);
        UUID verifyTask = uuid("SELECT verify_task_id FROM niche_candidate WHERE niche_id = ? AND sku = ?",
                id, searchSku(94));
        assertThat(verifyTask).isNotNull();
        Map<?, ?> task = parseTask(verifyTask);
        assertThat(task.get("sku")).isEqualTo(size122);
        assertThat(task.get("full_content")).isEqualTo(true);
        assertThat(nicheStatus(id)).isEqualTo("selecting");

        // ---------- 2. Проверка прошла, ниша в selecting — карточка размера в нише ----------
        parseConsumer.handle(verifyResult(verifyTask, size122, true));

        assertThat(members(id)).containsExactlyInAnyOrder(searchSku(96), size122);
        assertThat(candidateStatus(id, searchSku(94))).isEqualTo("selected");
        assertThat(cardStatus(size122)).isEqualTo("active");
        assertThat(str("SELECT c.sku FROM niche_candidate nc JOIN card c ON c.id = nc.card_id "
                + "WHERE nc.niche_id = ? AND nc.sku = ?", id, searchSku(94))).isEqualTo(size122);
    }

    /** Карточка страты без content_txt в раунде пропускается: не отсеяна, не unavailable. */
    @Test
    void candidateWithoutFullContentIsSkipped() {
        StratumEntity st = createHintStratum(93);
        addStratumCardWithoutContent(st, 99);
        UUID id = createNiche(true, 3).id();

        toRanking(id, referenceCard(), List.of(hoodie(searchSku(80), 80)));
        scheduler.tick();

        assertThat(members(id)).containsExactlyInAnyOrder(stratumSku(93), searchSku(80));
        assertThat(candidateStatus(id, stratumSku(99))).isEqualTo("candidate");
        assertThat(similarity(id, stratumSku(99))).isNull();
        assertThat(rejectReason(id, stratumSku(99))).isNull();
        assertThat(nicheStatus(id)).isEqualTo("selecting");  // 2 из 3
    }

    /** Ранжирование ниши без референса — failed. */
    @Test
    void nicheWithoutReferenceFails() {
        createHintStratum();
        UUID id = createNiche(true, 3).id();

        toRanking(id, referenceCard(), List.of(hoodie(searchSku(80), 80)));
        exec("UPDATE niche SET reference_snapshot = NULL WHERE id = ?", id);
        scheduler.tick();

        assertThat(nicheStatus(id)).isEqualTo("failed");
        assertThat(str("SELECT error FROM niche WHERE id = ?", id)).startsWith("Нет референса");
        assertThat(members(id)).isEmpty();
    }

    // ---------- Вспомогательное ----------

    /** Референс, запрос, первая пачка с заданными карточками — ниша в ranking. */
    private void toRanking(UUID id, Map<String, Object> reference, List<Map<String, Object>> cards) {
        scheduler.tick();
        parseConsumer.handle(referenceResult(
                uuid("SELECT reference_task_id FROM niche WHERE id = ?", id), reference));
        scheduler.tick();
        assertThat(nicheStatus(id)).isEqualTo("selecting");
        selectConsumer.handle(selectResultCards(selectTaskId(id), cards));
        assertThat(nicheStatus(id)).isEqualTo("ranking");
    }

    private String rejectReason(UUID id, String sku) {
        return str("SELECT reject_reason FROM niche_candidate WHERE niche_id = ? AND sku = ?", id, sku);
    }

    private String similarity(UUID id, String sku) {
        return str("SELECT similarity::text FROM niche_candidate WHERE niche_id = ? AND sku = ?", id, sku);
    }
}