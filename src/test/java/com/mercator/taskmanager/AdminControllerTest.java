package com.mercator.taskmanager;

import com.mercator.taskmanager.analytics.AnalyticsClient;
import com.mercator.taskmanager.assignment.AssignmentViews.SetView;
import com.mercator.taskmanager.assignment.AssignmentViews.StratumView;
import com.mercator.taskmanager.assignment.SeasonType;
import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.controller.AdminController;
import com.mercator.taskmanager.entity.CategorySettingEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CategorySettingRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.ReferenceResolver;
import com.mercator.taskmanager.service.SeasonalSetService.StratumChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Админское API ядра прямыми вызовами контроллера: черновик (201) → правка доли
 * с пересчётом → запуск сбора; удаление черновика (204); ошибки — 404 и 409.
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class AdminControllerTest extends PostgresTestBase {

    private static final String GEO = "Контроллер-тест";

    @MockitoBean AnalyticsClient analytics;

    @Autowired AdminController controller;
    @Autowired ReferenceResolver references;
    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;
    @Autowired CategorySettingRepository categorySettingRepository;

    private SetEntity active;

    @BeforeEach
    void createActiveKidsSet() {
        CategorySettingEntity setting = new CategorySettingEntity();
        setting.setCategoryId(references.category("kids_clothing").getId());
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

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", "f");
        attrs.put("layer", 3);
        SubcategoryEntity outer = new SubcategoryEntity();
        outer.setSet(active);
        outer.setTitle("Верхняя одежда");
        outer.setAttrs(attrs);
        outer.setShare(new BigDecimal("0.04805479316"));
        outer.setTracked(true);
        outer = subcategoryRepository.save(outer);
        stratum(outer, "Куртка", "0.7", 29, 7);
        stratum(outer, "Пуховик", "0.3", 12, 3);
    }

    @Test
    void draftEditAndStart() {
        ResponseEntity<SetView> created = controller.createDraft(active.getId());
        assertEquals(201, created.getStatusCode().value());
        SetView draft = created.getBody();
        assertNotNull(draft);
        assertEquals(SetStatus.DRAFT, draft.set().status());
        assertEquals("Autumn Winter 2027", draft.set().seasonTitle());
        UUID draftId = draft.set().id();

        // куртка 0,5: 41 × 0,5 = 20,5 → 21
        StratumView jacket = draft.subcategories().get(0).strata().get(0);
        SetView edited = controller.updateStratum(draftId, jacket.id(),
                new StratumChange(null, null, new BigDecimal("0.5"), null, null));
        assertEquals(21, edited.subcategories().get(0).strata().get(0).count());

        SetView started = controller.start(draftId);
        assertEquals(SetStatus.COLLECTING, started.set().status());
        assertNotNull(started.set().collectingAt());
    }

    @Test
    void deleteDraftReturnsNoContent() {
        UUID draftId = controller.createDraft(active.getId()).getBody().set().id();

        assertEquals(204, controller.deleteDraft(draftId).getStatusCode().value());
        assertTrue(setRepository.findById(draftId).isEmpty());
    }

    @Test
    void errorsMapToNotFoundAndConflict() {
        controller.createDraft(active.getId());
        // второй черновик — неверное состояние
        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> controller.createDraft(active.getId()));
        assertEquals(409, controller.conflict(conflict).getStatusCode().value());
        // нет такого сета
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> controller.set(UUID.randomUUID()));
        assertEquals(404, controller.notFound(missing).getStatusCode().value());
        assertNotNull(controller.notFound(missing).getBody().get("error"));
    }

    // ---------- Вспомогательное ----------

    private void stratum(SubcategoryEntity sub, String item, String queryShare, int count, int quota) {
        StratumEntity st = new StratumEntity();
        st.setSubcategory(sub);
        st.setItem(item);
        st.setQuery(item + " для девочки");
        st.setQueryShare(new BigDecimal(queryShare));
        st.setCount(count);
        st.setAnalysisQuota(quota);
        st.setIsSeasonal(false);
        stratumRepository.save(st);
    }
}