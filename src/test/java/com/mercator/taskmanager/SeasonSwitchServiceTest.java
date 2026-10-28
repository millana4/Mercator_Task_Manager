package com.mercator.taskmanager;

import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.ReferenceResolver;
import com.mercator.taskmanager.service.SeasonSwitchService;
import com.mercator.taskmanager.service.SeasonSwitchService.SwitchResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Переключение на новый сезонный сет: меняются только статусы сетов, карточки не трогаются,
 * обход следует за членством. A — только в старом сете (выпадает из обхода), B — общий товар
 * (обходится дальше под тем же card_id), C — старый сет и ниша (обходится ради ниши).
 * Ниша вставляется нативным запросом через EntityManager (Postgres): JdbcTemplate в контексте
 * ядра — ClickHouse.
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class SeasonSwitchServiceTest extends PostgresTestBase {

    private static final String GEO = "Переключение-тест";

    @Autowired SeasonSwitchService service;
    @Autowired ReferenceResolver references;
    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;
    @Autowired CardRepository cardRepository;
    @PersistenceContext EntityManager em;

    private final String prefix = "SW" + System.nanoTime();

    @Test
    void switchChangesOnlySetStatusesAndCrawlFollowsMembership() {
        SetEntity oldSet = set(SetStatus.ACTIVE);
        SetEntity newSet = set(SetStatus.COLLECTING);
        StratumEntity oldStratum = stratum(oldSet);
        StratumEntity newStratum = stratum(newSet);
        CardEntity a = card(prefix + "A", oldStratum);
        CardEntity b = card(prefix + "B", oldStratum);
        cardRepository.addToStratum(newStratum.getId(), b.getId(), OffsetDateTime.now());
        CardEntity c = card(prefix + "C", oldStratum);
        UUID niche = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO niche (id, marketplace_id, category_id, geo_id, external_id, "
                        + "reference_url, target_count, plan_count, status) "
                        + "VALUES (:id, :mp, :cat, :geo, :ext, 'https://www.ozon.ru/product/x-1/', 30, 34, 'active')")
                .setParameter("id", niche)
                .setParameter("mp", oldSet.getMarketplace().getId())
                .setParameter("cat", oldSet.getCategory().getId())
                .setParameter("geo", oldSet.getGeo().getId())
                .setParameter("ext", UUID.randomUUID())
                .executeUpdate();
        cardRepository.addToNiche(niche, c.getId());

        Short mp = oldSet.getMarketplace().getId();
        long inWorkBefore = cardRepository.countActiveInCategory(mp, "kids_clothing");

        SwitchResult result = service.switchTo(newSet.getId());
        setRepository.flush();

        assertEquals(List.of(oldSet.getId()), result.retiredSetIds());
        assertEquals(SetStatus.RETIRED, setRepository.findById(oldSet.getId()).orElseThrow().getStatus());
        SetEntity active = setRepository.findById(newSet.getId()).orElseThrow();
        assertEquals(SetStatus.ACTIVE, active.getStatus());
        assertNotNull(active.getActivatedAt());

        // карточки не тронуты
        for (CardEntity card : List.of(a, b, c)) {
            assertEquals("active", cardRepository.findById(card.getId()).orElseThrow().getStatus());
        }
        // обход по членству: A выпала, B — через новый сет, C — через нишу
        assertEquals(0, cardRepository.countLiveStrata(a.getId()));
        assertEquals(1, cardRepository.countLiveStrata(b.getId()));
        assertEquals(0, cardRepository.countLiveStrata(c.getId()));
        assertEquals(inWorkBefore - 1, cardRepository.countActiveInCategory(mp, "kids_clothing"));
    }

    @Test
    void onlyCollectingSetCanBecomeActive() {
        SetEntity active = set(SetStatus.ACTIVE);
        assertThrows(IllegalStateException.class, () -> service.switchTo(active.getId()));
    }

    // ---------- Вспомогательное ----------

    private SetEntity set(String status) {
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace("ozon"));
        set.setCategory(references.category("kids_clothing"));
        set.setGeo(references.geo(GEO));
        set.setStatus(status);
        return setRepository.saveAndFlush(set);
    }

    private StratumEntity stratum(SetEntity set) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", "f");
        attrs.put("layer", 3);
        SubcategoryEntity sub = new SubcategoryEntity();
        sub.setSet(set);
        sub.setTitle("Верхняя одежда");
        sub.setAttrs(attrs);
        sub = subcategoryRepository.save(sub);

        StratumEntity st = new StratumEntity();
        st.setSubcategory(sub);
        st.setItem("Куртка");
        st.setQuery("Куртка для девочки");
        st.setCount(5);
        st.setIsSeasonal(false);
        return stratumRepository.saveAndFlush(st);
    }

    private CardEntity card(String sku, StratumEntity stratum) {
        CardEntity card = new CardEntity();
        card.setMarketplaceId(references.marketplace("ozon").getId());
        card.setSku(sku);
        card.setStatus("active");
        card.setFailedAttempts(0);
        CardEntity saved = cardRepository.saveAndFlush(card);
        cardRepository.addToStratum(stratum.getId(), saved.getId(), OffsetDateTime.now());
        return saved;
    }
}