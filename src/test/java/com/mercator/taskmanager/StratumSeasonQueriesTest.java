package com.mercator.taskmanager;

import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.ReferenceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запросы страт с учётом статуса сета: выдача и подсказки ниш — только активный сет
 * и страты с количеством больше нуля; очередь сбора — только сет в сборе; частые SKU —
 * по страте активного сета с тем же запросом. Свой гео «Очередь-тест».
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class StratumSeasonQueriesTest extends PostgresTestBase {

    private static final String GEO = "Очередь-тест";
    private static final String QUERY = "Блузка для девочки";

    @Autowired ReferenceResolver references;
    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;

    private SetEntity active, collecting;
    private StratumEntity activeBlouse, activeZero, collectingBlouse, collectingZero, draftBlouse;

    @BeforeEach
    void createSets() {
        active = set(SetStatus.ACTIVE);
        SubcategoryEntity a = subcategory(active);
        activeBlouse = stratum(a, "Блузка", QUERY, 3);
        activeZero = stratum(a, "Бюстгальтер", "Бюстгальтер для девочки", 0);

        collecting = set(SetStatus.COLLECTING);
        SubcategoryEntity c = subcategory(collecting);
        collectingBlouse = stratum(c, "Блузка", QUERY, 3);
        collectingZero = stratum(c, "Бюстгальтер", "Бюстгальтер для девочки", 0);

        SetEntity draft = set(SetStatus.DRAFT);
        draftBlouse = stratum(subcategory(draft), "Блузка", QUERY, 3);
    }

    @Test
    void searchSnapshotTakesOnlyActiveNonZeroStrata() {
        List<UUID> ids = ids(stratumRepository.findByCategoryTitleWithSet("kids_clothing"));
        assertThat(ids).contains(activeBlouse.getId())
                .doesNotContain(activeZero.getId(), collectingBlouse.getId(),
                        collectingZero.getId(), draftBlouse.getId());
    }

    @Test
    void nicheHintsTakeOnlyActiveNonZeroStrata() {
        List<UUID> ids = ids(stratumRepository.findForNicheHints(
                active.getMarketplace().getId(), active.getCategory().getId(), active.getGeo().getId()));
        assertThat(ids).containsExactly(activeBlouse.getId());
    }

    @Test
    void collectingQueueTakesOnlyCollectingNonZeroStrata() {
        List<UUID> ids = ids(stratumRepository.findCollectingForSelect(active.getMarketplace().getId()));
        assertThat(ids).contains(collectingBlouse.getId())
                .doesNotContain(collectingZero.getId(), activeBlouse.getId(), draftBlouse.getId());
    }

    @Test
    void activeCounterpartFoundByQuery() {
        List<UUID> ids = ids(stratumRepository.findActiveByQuery(active.getMarketplace().getId(),
                active.getCategory().getId(), active.getGeo().getId(), QUERY));
        assertThat(ids).containsExactly(activeBlouse.getId());
    }

    @Test
    void setsByStatus() {
        assertThat(setRepository.findByStatus(SetStatus.COLLECTING))
                .extracting(SetEntity::getId).contains(collecting.getId())
                .doesNotContain(active.getId());
    }

    // ---------- Вспомогательное ----------

    private SetEntity set(String status) {
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace("ozon"));
        set.setCategory(references.category("kids_clothing"));
        set.setGeo(references.geo(GEO));
        set.setStatus(status);
        return setRepository.save(set);
    }

    private SubcategoryEntity subcategory(SetEntity set) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", "f");
        attrs.put("layer", 1);
        SubcategoryEntity sub = new SubcategoryEntity();
        sub.setSet(set);
        sub.setTitle("Блузы и рубашки");
        sub.setAttrs(attrs);
        return subcategoryRepository.save(sub);
    }

    private StratumEntity stratum(SubcategoryEntity sub, String item, String query, int count) {
        StratumEntity st = new StratumEntity();
        st.setSubcategory(sub);
        st.setItem(item);
        st.setQuery(query);
        st.setCount(count);
        st.setIsSeasonal(false);
        return stratumRepository.save(st);
    }

    private static List<UUID> ids(List<StratumEntity> strata) {
        return strata.stream().map(StratumEntity::getId).toList();
    }
}