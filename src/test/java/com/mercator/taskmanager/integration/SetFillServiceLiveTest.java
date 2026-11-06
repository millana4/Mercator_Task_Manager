package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.clickhouse.MeasurementRepository;
import com.mercator.taskmanager.clickhouse.CardSnapshotRepository;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.ReferenceResolver;
import com.mercator.taskmanager.service.SetFillService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ЖИВОЙ тест полного наполнения: создаёт крошечный сет (1 страта),
 * наполняет через SetFillService (реальный вызов парсера), проверяет,
 * что карточки записаны в Postgres (card) и ClickHouse (measurement).
 *
 * Требует запущенных Postgres, ClickHouse и ПАРСЕРА. Медленный (Selenium).
 * За собой убирает созданный сет (каскадно удалит подкатегории, страты
 * и карточки Postgres).
 */
@SpringBootTest
@Disabled("Живой тест наполнения — запускать вручную, требует парсера")
class SetFillServiceLiveTest {

    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;
    @Autowired CardRepository cardRepository;
    @Autowired MeasurementRepository measurementRepository;
    @Autowired CardSnapshotRepository snapshotRepository;
    @Autowired SetFillService fillService;
    @Autowired ReferenceResolver references;

    @Test
    void fillsTinySetFromLiveParser() {
        // 1. Создаём крошечный сет с одной подкатегорией и одной стратой.
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace("ozon"));
        set.setCategory(references.category("clothing"));
        set.setGeo(references.geo("Санкт-Петербург"));
        SetEntity savedSet = setRepository.save(set);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", "m");
        attrs.put("layer", 0);

        SubcategoryEntity subcategory = new SubcategoryEntity();
        subcategory.setSet(savedSet);
        subcategory.setTitle("Носки");
        subcategory.setAttrs(attrs);
        SubcategoryEntity savedSubcategory = subcategoryRepository.save(subcategory);

        StratumEntity stratum = new StratumEntity();
        stratum.setSubcategory(savedSubcategory);
        stratum.setItem("Носки");
        stratum.setQuery("Носки мужские");
        stratum.setCount(2);              // всего 2 карточки — быстрее
        stratum.setIsSeasonal(false);
        stratum.setBaseShare(null);
        StratumEntity savedStratum = stratumRepository.save(stratum);

        try {
            // 2. Наполняем сет.
            int totalCards = fillService.fillSet(savedSet.getId());
            System.out.println(">>> Всего записано карточек: " + totalCards);

            assertTrue(totalCards > 0, "Ожидали хотя бы одну карточку");

            // 3. Проверяем Postgres: карточки привязаны к нашей страте.
            List<CardEntity> cards = cardRepository.findStratumMembers(savedStratum.getId());
            assertFalse(cards.isEmpty(), "В Postgres нет карточек страты");
            System.out.println(">>> Карточек в Postgres: " + cards.size());

            // 4. Проверяем ClickHouse: у каждой карточки есть замер и снимок.
            for (CardEntity card : cards) {
                long measurements = measurementRepository.countByCardId(card.getId());
                long snapshots = snapshotRepository.countByCardId(card.getId());
                System.out.println("    карточка " + card.getSku()
                        + " | замеров=" + measurements + " | снимков=" + snapshots);
                assertTrue(measurements >= 1, "Нет замера для карточки " + card.getSku());
                assertTrue(snapshots >= 1, "Нет снимка для карточки " + card.getSku());
            }

        } finally {
            // 5. Убираем за собой карточки страты и сет (подкатегории, страты и членство
            // удалятся каскадом; карточка — товар, каскадом от сета не удаляется).
            // Замеры/снимки в ClickHouse останутся — они append-only, это норма для теста.
            cardRepository.deleteAll(cardRepository.findStratumMembers(savedStratum.getId()));
            setRepository.deleteById(savedSet.getId());
        }
    }
}