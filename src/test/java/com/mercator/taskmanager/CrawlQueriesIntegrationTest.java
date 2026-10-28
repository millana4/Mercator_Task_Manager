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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Запросы планового и учащённого обхода на настоящем Postgres:
 * просроченные по категории, счётчики активных и просроченных, кандидаты учащения.
 * Карточка в работе — активна и состоит в страте живого сета (членство открыто) или в нише.
 */
@SpringBootTest
@ActiveProfiles("test")
class CrawlQueriesIntegrationTest extends PostgresTestBase {

    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;
    @Autowired CardRepository cardRepository;
    @Autowired ReferenceResolver references;

    private final OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    private StratumEntity stratum(String category, String query) {
        return stratum(category, query, SetStatus.ACTIVE);
    }

    private StratumEntity stratum(String category, String query, String setStatus) {
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace("ozon"));
        set.setCategory(references.category(category));
        set.setGeo(references.geo("Санкт-Петербург"));
        set.setStatus(setStatus);
        SetEntity savedSet = setRepository.save(set);

        SubcategoryEntity subcategory = new SubcategoryEntity();
        subcategory.setSet(savedSet);
        subcategory.setTitle(query);
        subcategory.setAttrs(new HashMap<>());
        SubcategoryEntity savedSubcategory = subcategoryRepository.save(subcategory);

        StratumEntity stratum = new StratumEntity();
        stratum.setSubcategory(savedSubcategory);
        stratum.setItem(query);
        stratum.setQuery(query);
        stratum.setCount(10);
        stratum.setIsSeasonal(false);
        return stratumRepository.save(stratum);
    }

    private CardEntity card(StratumEntity stratum, String sku, String status,
                            OffsetDateTime measuredAt, OffsetDateTime enqueuedAt,
                            OffsetDateTime boostStartedAt) {
        CardEntity card = new CardEntity();
        card.setMarketplaceId(stratum.getSubcategory().getSet().getMarketplace().getId());
        card.setSku(sku);
        card.setStatus(status);
        card.setFailedAttempts(0);
        card.setLastMeasuredAt(measuredAt);
        card.setLastEnqueuedAt(enqueuedAt);
        card.setBoostStartedAt(boostStartedAt);
        card.setBoostBasePrice(boostStartedAt == null ? null : 1000.0);
        CardEntity saved = cardRepository.saveAndFlush(card);
        cardRepository.addToStratum(stratum.getId(), saved.getId(), now);
        return saved;
    }

    private static List<String> skus(List<CardEntity> cards) {
        return cards.stream().map(CardEntity::getSku).toList();
    }

    @Test
    @Transactional
    void dueBoostAndCountsByCategory() {
        StratumEntity kids = stratum("kids_clothing", "Брюки для мальчика");
        StratumEntity adult = stratum("clothing", "Брюки женские");
        Short mp = kids.getSubcategory().getSet().getMarketplace().getId();

        card(kids, "D", "active", null, null, null);                                     // без замера
        card(kids, "A", "active", now.minusMinutes(690), null, null);                    // 11,5 ч назад
        card(kids, "B", "active", now.minusHours(9), null, now.minusHours(9));           // на учащении
        card(kids, "H", "active", now.minusMinutes(10), null, now.minusHours(2));        // на учащении, свежий
        card(kids, "C", "active", now.minusHours(12), now.minusMinutes(10), null);       // в работе
        card(kids, "E", "dropped", now.minusHours(20), null, null);                      // выбыла
        card(adult, "F", "active", now.minusHours(11), null, null);                      // одежда, 11 ч

        OffsetDateTime inflight = now.minusMinutes(45);

        // Плановый обход: детская одежда, интервал 10 ч — сначала без замера, потом самые давние
        assertEquals(List.of("D", "A"),
                skus(cardRepository.findDueCandidates(mp, "kids_clothing", now.minusHours(10), inflight, 10)));
        // Одежда, интервал 24 ч — пока никто не просрочен
        assertEquals(List.of(),
                skus(cardRepository.findDueCandidates(mp, "clothing", now.minusHours(24), inflight, 10)));

        // Активные и просроченные (интервал + 1 ч = 11 ч): D (без замера), A (11,5 ч), C (12 ч)
        assertEquals(5, cardRepository.countActiveInCategory(mp, "kids_clothing"));
        assertEquals(1, cardRepository.countActiveInCategory(mp, "clothing"));
        assertEquals(3, cardRepository.countOverdueInCategory(mp, "kids_clothing", now.minusHours(11)));

        // Учащение раз в 60 мин: B (9 ч с замера) — кандидат, H (10 мин) — ещё нет
        assertEquals(List.of("B"),
                skus(cardRepository.findBoostCandidates(mp, now.minusMinutes(60), inflight, 10)));
        assertEquals(2, cardRepository.countBoosted());
    }

    @Test
    @Transactional
    void inWorkByMembershipOfLiveSets() {
        Short mp = references.marketplace("ozon").getId();
        long kidsBefore = cardRepository.countActiveInCategory(mp, "kids_clothing");
        long adultBefore = cardRepository.countActiveInCategory(mp, "clothing");

        StratumEntity live = stratum("kids_clothing", "Куртка для девочки");
        StratumEntity collecting = stratum("kids_clothing", "Пуховик для девочки", SetStatus.COLLECTING);
        StratumEntity retired = stratum("kids_clothing", "Шорты для девочки", SetStatus.RETIRED);
        StratumEntity adult = stratum("clothing", "Куртка женская");

        card(live, "M-live", "active", null, null, null);                 // в работе
        card(collecting, "M-coll", "active", null, null, null);           // сет в сборе — в работе
        card(retired, "M-ret", "active", null, null, null);               // выведенный сет — нет
        CardEntity closed = card(live, "M-closed", "active", null, null, null);
        cardRepository.closeStratumMemberships(closed.getId(), now);       // членство закрыто — нет
        CardEntity both = card(live, "M-both", "active", null, null, null);
        cardRepository.addToStratum(adult.getId(), both.getId(), now);     // товар в двух категориях

        assertEquals(kidsBefore + 3, cardRepository.countActiveInCategory(mp, "kids_clothing"));
        assertEquals(adultBefore + 1, cardRepository.countActiveInCategory(mp, "clothing"));
        assertEquals(1, cardRepository.countActiveStratumMembers(live.getId()) - 1);   // M-live и M-both
        assertEquals(0, cardRepository.countLiveStrata(closed.getId()));
        assertEquals(2, cardRepository.countLiveStrata(both.getId()));
    }
}