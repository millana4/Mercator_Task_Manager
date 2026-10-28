package com.mercator.taskmanager;

import com.mercator.taskmanager.assignment.SeasonType;
import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.CategorySettingEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CategorySettingRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.ReferenceResolver;
import com.mercator.taskmanager.service.SeasonalSetService;
import com.mercator.taskmanager.service.SeasonalSetService.DraftChange;
import com.mercator.taskmanager.service.SeasonalSetService.StratumChange;
import com.mercator.taskmanager.service.SeasonalSetService.SubcategoryChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Сезонный сет: черновик копией активного, пересчёт, запуск сбора, удаление черновика.
 * Свой гео «Сезон-тест» — чтобы не пересекаться с сетами других тестов в общей базе.
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class SeasonalSetServiceTest extends PostgresTestBase {

    private static final String GEO = "Сезон-тест";

    @Autowired SeasonalSetService service;
    @Autowired ReferenceResolver references;
    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;
    @Autowired CategorySettingRepository categorySettingRepository;

    private SetEntity active;

    @BeforeEach
    void createActiveKidsSet() {
        Short kidsId = references.category("kids_clothing").getId();
        CategorySettingEntity setting = new CategorySettingEntity();
        setting.setCategoryId(kidsId);
        setting.setSeasonalSwitch(true);
        categorySettingRepository.save(setting);

        active = new SetEntity();
        active.setMarketplace(references.marketplace("ozon"));
        active.setCategory(references.category("kids_clothing"));
        active.setGeo(references.geo(GEO));
        active.setStatus(SetStatus.ACTIVE);
        active.setSeasonType(SeasonType.SPRING_SUMMER);
        active.setSeasonYear((short) 2026);
        active.setAnalysisCards(200);
        active.setMinSubcategoryCards(30);
        active.setRotationFactor(new BigDecimal("1.35"));
        active = setRepository.save(active);

        SubcategoryEntity outer = subcategory(active, "Верхняя одежда", "f", 3, "0.04805479316", true);
        stratum(outer, "Куртка", "Куртка для девочки", "0.7", 29, false, null);
        stratum(outer, "Пуховик", "Пуховик для девочки", "0.3", 12, false, null);
        SubcategoryEntity underwear = subcategory(active, "Нижнее белье", "f", 0, "0.007890035632", false);
        stratum(underwear, "Трусы", "Трусы для девочки", "0.8", 2, false, null);
        stratum(underwear, "Бюстгальтер", "Бюстгальтер для девочки", "0.2", 0, false, null);
    }

    @Test
    void draftCopiesActiveSetWithNextSeason() {
        SetEntity draft = service.createDraft(active.getId());

        assertEquals(SetStatus.DRAFT, draft.getStatus());
        assertEquals(SeasonType.AUTUMN_WINTER, draft.getSeasonType());
        assertEquals((short) 2027, draft.getSeasonYear());
        assertEquals(200, draft.getAnalysisCards());
        assertEquals(30, draft.getMinSubcategoryCards());
        assertEquals(0, draft.getRotationFactor().compareTo(new BigDecimal("1.35")));

        List<SubcategoryEntity> subs = subcategoryRepository.findBySetId(draft.getId());
        assertEquals(2, subs.size());
        SubcategoryEntity outer = subs.stream().filter(s -> s.getTitle().equals("Верхняя одежда"))
                .findFirst().orElseThrow();
        assertTrue(outer.getTracked());
        assertEquals("f", outer.getAttrs().get("segment"));
        assertEquals(3, ((Number) outer.getAttrs().get("layer")).intValue());

        Map<String, StratumEntity> strata = strataByItem(draft.getId());
        assertEquals(4, strata.size());
        assertEquals("Куртка для девочки", strata.get("Куртка").getQuery());
        assertCountAndQuota(strata.get("Куртка"), 29, 7);
        assertCountAndQuota(strata.get("Пуховик"), 12, 3);
        assertCountAndQuota(strata.get("Трусы"), 2, 1);
        assertCountAndQuota(strata.get("Бюстгальтер"), 0, 0);

        // Активный сет не тронут.
        assertEquals(SetStatus.ACTIVE, setRepository.findById(active.getId()).orElseThrow().getStatus());
        assertEquals(4, stratumRepository.findBySubcategorySetId(active.getId()).size());
    }

    @Test
    void secondDraftIsRefused() {
        service.createDraft(active.getId());
        assertThrows(IllegalStateException.class, () -> service.createDraft(active.getId()));
    }

    @Test
    void categoryWithoutSeasonalSwitchIsRefused() {
        SetEntity clothing = new SetEntity();
        clothing.setMarketplace(references.marketplace("ozon"));
        clothing.setCategory(references.category("clothing"));
        clothing.setGeo(references.geo(GEO));
        clothing.setStatus(SetStatus.ACTIVE);
        clothing.setAnalysisCards(200);
        clothing.setMinSubcategoryCards(30);
        clothing.setRotationFactor(new BigDecimal("1.35"));
        SetEntity saved = setRepository.save(clothing);

        assertThrows(IllegalStateException.class, () -> service.createDraft(saved.getId()));
    }

    @Test
    void recalculateAfterShareEdit() {
        SetEntity draft = service.createDraft(active.getId());
        SubcategoryEntity outer = subcategoryRepository.findBySetId(draft.getId()).stream()
                .filter(s -> s.getTitle().equals("Верхняя одежда")).findFirst().orElseThrow();
        outer.setShare(new BigDecimal("0.2"));   // 0,2 × 200 = 40 > 30 → 40 × 1,35 = 54
        subcategoryRepository.save(outer);

        service.recalculate(draft.getId());

        Map<String, StratumEntity> strata = strataByItem(draft.getId());
        assertCountAndQuota(strata.get("Куртка"), 38, 28);    // 54 × 0,7 = 37,8; 40 × 0,7 = 28
        assertCountAndQuota(strata.get("Пуховик"), 16, 12);   // 54 × 0,3 = 16,2; 40 × 0,3 = 12
    }

    @Test
    void startCollectingMovesDraftToCollecting() {
        SetEntity draft = service.createDraft(active.getId());

        SetEntity collecting = service.startCollecting(draft.getId());

        assertEquals(SetStatus.COLLECTING, collecting.getStatus());
        assertNotNull(collecting.getCollectingAt());
        // Пока идёт сбор, нового черновика нет.
        assertThrows(IllegalStateException.class, () -> service.createDraft(active.getId()));
    }

    @Test
    void startCollectingRefusesSubcategoryWithoutShare() {
        SetEntity draft = service.createDraft(active.getId());
        SubcategoryEntity underwear = subcategoryRepository.findBySetId(draft.getId()).stream()
                .filter(s -> s.getTitle().equals("Нижнее белье")).findFirst().orElseThrow();
        underwear.setShare(null);
        subcategoryRepository.save(underwear);

        assertThrows(IllegalStateException.class, () -> service.startCollecting(draft.getId()));
        assertEquals(SetStatus.DRAFT, setRepository.findById(draft.getId()).orElseThrow().getStatus());
    }

    @Test
    void deleteDraftRemovesItWithStrata() {
        SetEntity draft = service.createDraft(active.getId());
        UUID draftId = draft.getId();

        service.deleteDraft(draftId);
        setRepository.flush();

        assertTrue(setRepository.findById(draftId).isEmpty());
        assertTrue(stratumRepository.findBySubcategorySetId(draftId).isEmpty());
        // Активный сет удалить как черновик нельзя.
        assertThrows(IllegalStateException.class, () -> service.deleteDraft(active.getId()));
    }

    @Test
    void updateDraftParamsRecalculates() {
        SetEntity draft = service.createDraft(active.getId());

        // 1,5 вместо 1,35: верхняя одежда 30 × 1,5 = 45, куртка 45 × 0,7 = 31,5 → 32
        service.updateDraft(draft.getId(), new DraftChange(null, null, null, null, new BigDecimal("1.5")));

        assertCountAndQuota(strataByItem(draft.getId()).get("Куртка"), 32, 7);
        assertThrows(IllegalStateException.class, () -> service.updateDraft(draft.getId(),
                new DraftChange(null, null, null, null, new BigDecimal("0.9"))));
        assertThrows(IllegalStateException.class, () -> service.updateDraft(draft.getId(),
                new DraftChange("winter", (short) 2027, null, null, null)));
    }

    @Test
    void addAndEditStratumOfDraft() {
        SetEntity draft = service.createDraft(active.getId());
        SubcategoryEntity outer = subcategoryRepository.findBySetId(draft.getId()).stream()
                .filter(s -> s.getTitle().equals("Верхняя одежда")).findFirst().orElseThrow();

        // куртка 0,5, пуховик 0,3, парка 0,2 → 41 × 0,2 = 8,2 → 8
        service.updateStratum(draft.getId(), strataByItem(draft.getId()).get("Куртка").getId(),
                new StratumChange(null, null, new BigDecimal("0.5"), null, null));
        service.addStratum(draft.getId(), outer.getId(),
                new StratumChange("Парка", "Парка для девочки", new BigDecimal("0.2"), true, new BigDecimal("0.5")));

        Map<String, StratumEntity> strata = strataByItem(draft.getId());
        assertCountAndQuota(strata.get("Куртка"), 21, 5);
        assertCountAndQuota(strata.get("Парка"), 8, 2);
        assertTrue(strata.get("Парка").getIsSeasonal());
        assertEquals(0, strata.get("Парка").getBaseShare().compareTo(new BigDecimal("0.5")));

        // повтор предмета и сезонная страта без доли базы — отказ
        assertThrows(IllegalStateException.class, () -> service.addStratum(draft.getId(), outer.getId(),
                new StratumChange("Парка", "Парка ещё", new BigDecimal("0.1"), false, null)));
        assertThrows(IllegalStateException.class, () -> service.addStratum(draft.getId(), outer.getId(),
                new StratumChange("Жилет", "Жилет для девочки", new BigDecimal("0.1"), true, null)));
    }

    @Test
    void addEditAndDeleteSubcategoryOfDraft() {
        SetEntity draft = service.createDraft(active.getId());

        SubcategoryEntity hats = service.addSubcategory(draft.getId(),
                new SubcategoryChange("Головные уборы", "f", 3, new BigDecimal("0.02"), false));
        service.addStratum(draft.getId(), hats.getId(),
                new StratumChange("Шапка", "Шапка для девочки", null, false, null));
        // 0,02 × 200 = 4 → 4 × 1,35 = 5,4 → 5; единственный предмет — вся подкатегория
        assertCountAndQuota(strataByItem(draft.getId()).get("Шапка"), 5, 4);

        service.updateSubcategory(draft.getId(), hats.getId(),
                new SubcategoryChange(null, null, null, null, true));   // отслеживать: минимум 30
        assertCountAndQuota(strataByItem(draft.getId()).get("Шапка"), 41, 4);

        assertThrows(IllegalStateException.class, () -> service.addSubcategory(draft.getId(),
                new SubcategoryChange("Головные уборы", "f", 3, new BigDecimal("0.01"), false)));
        assertThrows(IllegalStateException.class, () -> service.updateSubcategory(draft.getId(), hats.getId(),
                new SubcategoryChange(null, null, null, new BigDecimal("1.5"), null)));

        service.deleteSubcategory(draft.getId(), hats.getId());
        assertFalse(strataByItem(draft.getId()).containsKey("Шапка"));
    }

    @Test
    void activeSetIsNotEditable() {
        SubcategoryEntity outer = subcategoryRepository.findBySetId(active.getId()).get(0);
        assertThrows(IllegalStateException.class, () -> service.updateSubcategory(active.getId(), outer.getId(),
                new SubcategoryChange(null, null, null, new BigDecimal("0.1"), null)));
    }

    // ---------- Вспомогательное ----------

    private SubcategoryEntity subcategory(SetEntity set, String title, String segment, int layer,
                                          String share, boolean tracked) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", segment);
        attrs.put("layer", layer);
        SubcategoryEntity sub = new SubcategoryEntity();
        sub.setSet(set);
        sub.setTitle(title);
        sub.setAttrs(attrs);
        sub.setShare(new BigDecimal(share));
        sub.setTracked(tracked);
        return subcategoryRepository.save(sub);
    }

    private void stratum(SubcategoryEntity sub, String item, String query, String queryShare,
                         int count, boolean seasonal, String baseShare) {
        StratumEntity st = new StratumEntity();
        st.setSubcategory(sub);
        st.setItem(item);
        st.setQuery(query);
        st.setQueryShare(new BigDecimal(queryShare));
        st.setCount(count);
        st.setIsSeasonal(seasonal);
        st.setBaseShare(baseShare == null ? null : new BigDecimal(baseShare));
        stratumRepository.save(st);
    }

    private Map<String, StratumEntity> strataByItem(UUID setId) {
        return stratumRepository.findBySubcategorySetId(setId).stream()
                .collect(Collectors.toMap(StratumEntity::getItem, s -> s));
    }

    private static void assertCountAndQuota(StratumEntity stratum, int count, int quota) {
        assertEquals(count, stratum.getCount(), "Количество страты " + stratum.getItem());
        assertEquals(quota, stratum.getAnalysisQuota(), "Квота страты " + stratum.getItem());
    }
}