package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.assignment.AssignmentCalculator;
import com.mercator.taskmanager.assignment.AssignmentCalculator.Params;
import com.mercator.taskmanager.assignment.AssignmentCalculator.SubcategoryFigures;
import com.mercator.taskmanager.assignment.SeasonType;
import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.ReferenceResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V23: минимум на подкатегорию 40 и коэффициент ротации 1,60 у сетов детской одежды, количества
 * страт пересчитываются SQL. Тест гоняет файл миграции на данных теста и сверяет результат с
 * AssignmentCalculator (формулы записаны в двух местах - расхождение должно упасть здесь).
 * Выведенный (retired) сет миграция не трогает.
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class RotationParamsMigrationTest extends PostgresTestBase {

    private static final String GEO = "Миграция-тест";
    private static final String MIGRATION = "db/migration/V23__rotation_and_niche_size.sql";
    private static final Params OLD = new Params(200, 30, new BigDecimal("1.35"));
    private static final Params NEW = new Params(200, 40, new BigDecimal("1.60"));

    @Autowired ReferenceResolver references;
    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;
    @PersistenceContext EntityManager em;

    private SetEntity active;
    private SetEntity retired;

    @BeforeEach
    void createSets() {
        active = kidsSet(SetStatus.ACTIVE, SeasonType.AUTUMN_WINTER, (short) 2027);
        retired = kidsSet("retired", SeasonType.SPRING_SUMMER, (short) 2026);
        for (SetEntity set : List.of(active, retired)) {
            // отслеживаемая: 0,07 x 200 = 14 < 40 -> 40 x 1,6 = 64
            SubcategoryEntity outer = subcategory(set, "Верхняя одежда", "f", 3, "0.07", true);
            stratum(outer, "Куртка", "0.7");
            stratum(outer, "Пуховик", "0.3");
            // отслеживаемая: 0,25 x 200 = 50 > 40 -> 50 x 1,6 = 80, доля запроса пуста
            SubcategoryEntity big = subcategory(set, "Футболки", "m", 1, "0.25", true);
            stratum(big, "Футболка", null);
            // не отслеживаемая: 0,0125 x 200 = 2,5 x 1,6 = 4
            SubcategoryEntity small = subcategory(set, "Майки", "f", 1, "0.0125", false);
            stratum(small, "Майка бельевая", "0.625");
            stratum(small, "Майка", "0.375");
        }
        em.flush();
    }

    @Test
    void activeSetGetsNewParametersAndCountsMatchCalculator() throws IOException {
        applyMigration();

        SetEntity set = setRepository.findById(active.getId()).orElseThrow();
        assertEquals(200, set.getAnalysisCards());
        assertEquals(40, set.getMinSubcategoryCards());
        assertEquals(0, set.getRotationFactor().compareTo(new BigDecimal("1.60")));

        for (SubcategoryEntity sub : subcategoryRepository.findBySetId(active.getId())) {
            SubcategoryFigures figures = AssignmentCalculator.subcategory(
                    sub.getShare(), Boolean.TRUE.equals(sub.getTracked()), NEW);
            for (StratumEntity stratum : stratumRepository.findBySubcategoryId(sub.getId())) {
                int expected = AssignmentCalculator.stratumCount(figures.total(), stratum.getQueryShare());
                assertEquals(expected, (int) stratum.getCount(),
                        sub.getTitle() + " / " + stratum.getItem());
            }
        }
    }

    @Test
    void countsOnKnownValues() throws IOException {
        applyMigration();

        Map<String, Integer> counts = stratumRepository.findBySubcategorySetId(active.getId()).stream()
                .collect(Collectors.toMap(StratumEntity::getItem, StratumEntity::getCount));
        assertEquals(45, (int) counts.get("Куртка"));          // 64 x 0,7 = 44,8
        assertEquals(19, (int) counts.get("Пуховик"));         // 64 x 0,3 = 19,2
        assertEquals(80, (int) counts.get("Футболка"));        // 50 x 1,6, доля запроса пуста
        assertEquals(3, (int) counts.get("Майка бельевая"));   // 4 x 0,625 = 2,5 -> 3, половина вверх
        assertEquals(2, (int) counts.get("Майка"));            // 4 x 0,375 = 1,5 -> 2
    }

    @Test
    void retiredSetIsNotTouched() throws IOException {
        Map<UUID, Integer> before = countsByStratum(retired.getId());

        applyMigration();

        SetEntity set = setRepository.findById(retired.getId()).orElseThrow();
        assertEquals(30, set.getMinSubcategoryCards());
        assertEquals(0, set.getRotationFactor().compareTo(new BigDecimal("1.35")));
        assertEquals(before, countsByStratum(retired.getId()));
    }

    // ---------- Вспомогательное ----------

    // Файл миграции выполняется как есть, на том же соединении (в транзакции теста).
    private void applyMigration() throws IOException {
        String sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        em.flush();
        em.unwrap(Session.class).doWork(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
        em.clear();
    }

    private Map<UUID, Integer> countsByStratum(UUID setId) {
        return stratumRepository.findBySubcategorySetId(setId).stream()
                .collect(Collectors.toMap(StratumEntity::getId, StratumEntity::getCount));
    }

    private SetEntity kidsSet(String status, String seasonType, short seasonYear) {
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace("ozon"));
        set.setCategory(references.category("kids_clothing"));
        set.setGeo(references.geo(GEO));
        set.setStatus(status);
        set.setSeasonType(seasonType);
        set.setSeasonYear(seasonYear);
        set.setAnalysisCards(200);
        set.setMinSubcategoryCards(30);
        set.setRotationFactor(new BigDecimal("1.35"));
        return setRepository.save(set);
    }

    private SubcategoryEntity subcategory(SetEntity set, String title, String segment, int layer,
                                          String share, boolean tracked) {
        SubcategoryEntity sub = new SubcategoryEntity();
        sub.setSet(set);
        sub.setTitle(title);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", segment);
        attrs.put("layer", layer);
        sub.setAttrs(attrs);
        sub.setShare(new BigDecimal(share));
        sub.setTracked(tracked);
        return subcategoryRepository.save(sub);
    }

    // Количество - как до миграции (по старым параметрам).
    private StratumEntity stratum(SubcategoryEntity sub, String item, String queryShare) {
        StratumEntity stratum = new StratumEntity();
        stratum.setSubcategory(sub);
        stratum.setItem(item);
        stratum.setQuery(item + " для теста");
        stratum.setIsSeasonal(false);
        BigDecimal share = queryShare == null ? null : new BigDecimal(queryShare);
        stratum.setQueryShare(share);
        int total = AssignmentCalculator.subcategory(sub.getShare(), Boolean.TRUE.equals(sub.getTracked()), OLD).total();
        stratum.setCount(AssignmentCalculator.stratumCount(total, share));
        return stratumRepository.save(stratum);
    }
}