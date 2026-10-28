package com.mercator.taskmanager;

import com.mercator.taskmanager.analytics.AnalyticsClient;
import com.mercator.taskmanager.assignment.AssignmentViews.Overview;
import com.mercator.taskmanager.assignment.AssignmentViews.Progress;
import com.mercator.taskmanager.assignment.AssignmentViews.SetView;
import com.mercator.taskmanager.assignment.AssignmentViews.SubcategoryView;
import com.mercator.taskmanager.assignment.SeasonType;
import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.CategorySettingEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.CategorySettingRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.AssignmentViewService;
import com.mercator.taskmanager.service.ReferenceResolver;
import com.mercator.taskmanager.service.SeasonalSetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Просмотр задания в админке: расчётные колонки подкатегорий как в CSV, итоги,
 * порядок подкатегорий (девочки, мальчики, малыши; слой), ход сбора и готовность аналитики.
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class AssignmentViewServiceTest extends PostgresTestBase {

    private static final String GEO = "Админка-тест";

    @MockitoBean AnalyticsClient analytics;

    @Autowired AssignmentViewService views;
    @Autowired SeasonalSetService seasonal;
    @Autowired ReferenceResolver references;
    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;
    @Autowired CardRepository cardRepository;
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

        SubcategoryEntity baby = subcategory("Боди и песочники", "baby", 1, "0.01483007612", false);
        stratum(baby, "Боди", "Боди для малыша", "1", 4, 3);
        SubcategoryEntity outer = subcategory("Верхняя одежда", "f", 3, "0.04805479316", true);
        stratum(outer, "Куртка", "Куртка для девочки", "0.7", 29, 7);
        stratum(outer, "Пуховик", "Пуховик для девочки", "0.3", 12, 3);
        SubcategoryEntity boys = subcategory("Рубашки", "m", 1, "0.01121912411", true);
        stratum(boys, "Рубашка", "Рубашка для мальчика", "1", 41, 2);
    }

    @Test
    void viewHasCsvFiguresAndOrder() {
        SetView view = views.view(active.getId());

        assertEquals("Spring Summer 2026", view.set().seasonTitle());
        assertEquals(List.of("Верхняя одежда", "Рубашки", "Боди и песочники"),
                view.subcategories().stream().map(SubcategoryView::title).toList());
        SubcategoryView outer = view.subcategories().get(0);
        assertEquals(0, outer.base().compareTo(new BigDecimal("9.610958632")));
        assertEquals(0, outer.withAddition().compareTo(new BigDecimal("30")));
        assertEquals(0, outer.withRotation().compareTo(new BigDecimal("40.50")));
        assertEquals(41, outer.total());
        assertEquals(List.of("Куртка", "Пуховик"), outer.strata().stream().map(s -> s.item()).toList());
        assertEquals(29, outer.strata().get(0).count());
        assertEquals(3, view.set().subcategories());
        assertEquals(2, view.set().trackedSubcategories());    // верхняя одежда и рубашки
        assertEquals(86, view.set().plannedCards());           // 4 + 29 + 12 + 41
        assertEquals(15, view.set().analysisQuota());          // 3 + 7 + 3 + 2
    }

    @Test
    void overviewShowsActiveDraftAndCollectingWithProgress() {
        Overview empty = views.overview("kids_clothing", GEO, "ozon");
        assertTrue(empty.seasonalSwitch());
        assertNotNull(empty.active());
        assertNull(empty.draft());
        assertNull(empty.collecting());

        SetEntity draft = seasonal.createDraft(active.getId());
        assertEquals("Autumn Winter 2027", views.overview("kids_clothing", GEO, "ozon").draft().seasonTitle());

        seasonal.startCollecting(draft.getId());
        StratumEntity jacket = stratumRepository.findBySubcategorySetId(draft.getId()).stream()
                .filter(s -> s.getItem().equals("Куртка")).findFirst().orElseThrow();
        jacket.setSelectRequestedAt(OffsetDateTime.now());
        stratumRepository.save(jacket);
        CardEntity card = new CardEntity();
        card.setMarketplaceId(references.marketplace("ozon").getId());
        card.setSku("ADM" + System.nanoTime());
        card.setStatus("active");
        card.setFailedAttempts(0);
        card = cardRepository.saveAndFlush(card);
        cardRepository.addToStratum(jacket.getId(), card.getId(), OffsetDateTime.now());
        when(analytics.setReadiness(any())).thenReturn(new AnalyticsClient.Readiness(null, false, Map.of(
                "EWMA", new AnalyticsClient.AlgorithmState(false, 1, "2026-11-01T00:37:00", "мало карточек"))));

        Overview overview = views.overview("kids_clothing", GEO, "ozon");
        assertNull(overview.draft());
        assertEquals(SetStatus.COLLECTING, overview.collecting().status());
        Progress progress = overview.progress();
        assertEquals(4, progress.strataTotal());
        assertEquals(1, progress.strataSent());
        assertEquals(86, progress.plannedCards());
        assertEquals(1, progress.collectedCards());
        assertEquals(Boolean.FALSE, progress.readiness().ewma());
        assertNull(progress.readiness().macd());
        assertFalse(progress.readiness().ready());
    }

    @Test
    void progressReportsAnalyticsFailure() {
        when(analytics.setReadiness(any())).thenThrow(new IllegalStateException("Аналитика недоступна"));

        Progress progress = views.progress(active.getId());

        assertNull(progress.readiness());
        assertEquals("Аналитика недоступна", progress.readinessError());
    }

    // ---------- Вспомогательное ----------

    private SubcategoryEntity subcategory(String title, String segment, int layer, String share, boolean tracked) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", segment);
        attrs.put("layer", layer);
        SubcategoryEntity sub = new SubcategoryEntity();
        sub.setSet(active);
        sub.setTitle(title);
        sub.setAttrs(attrs);
        sub.setShare(new BigDecimal(share));
        sub.setTracked(tracked);
        return subcategoryRepository.save(sub);
    }

    private void stratum(SubcategoryEntity sub, String item, String query, String queryShare,
                         int count, int quota) {
        StratumEntity st = new StratumEntity();
        st.setSubcategory(sub);
        st.setItem(item);
        st.setQuery(query);
        st.setQueryShare(new BigDecimal(queryShare));
        st.setCount(count);
        st.setAnalysisQuota(quota);
        st.setIsSeasonal(false);
        stratumRepository.save(st);
    }
}