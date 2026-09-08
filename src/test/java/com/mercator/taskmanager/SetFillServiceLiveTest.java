package com.mercator.taskmanager;

import com.mercator.taskmanager.clickhouse.MeasurementRepository;
import com.mercator.taskmanager.clickhouse.CardSnapshotRepository;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.service.SetFillService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ЖИВОЙ тест полного наполнения: создаёт крошечный сет (1 страта),
 * наполняет через SetFillService (реальный вызов парсера), проверяет,
 * что карточки записаны в Postgres (card) и ClickHouse (measurement).
 *
 * Требует запущенных Postgres, ClickHouse и ПАРСЕРА. Медленный (Selenium).
 * За собой убирает созданный сет (каскадно удалит страты и карточки Postgres).
 */
@SpringBootTest
@Disabled("Живой тест наполнения — запускать вручную, требует парсера")
class SetFillServiceLiveTest {

    @Autowired SetRepository setRepository;
    @Autowired SetClothingRepository stratumRepository;
    @Autowired CardRepository cardRepository;
    @Autowired MeasurementRepository measurementRepository;
    @Autowired CardSnapshotRepository snapshotRepository;
    @Autowired SetFillService fillService;

    @Test
    void fillsTinySetFromLiveParser() {
        // 1. Создаём крошечный сет с одной стратой.
        SetEntity set = new SetEntity();
        set.setMarketplace("ozon");
        set.setCategory("clothing");
        set.setGeo("Санкт-Петербург");
        SetEntity savedSet = setRepository.save(set);

        SetClothingEntity stratum = new SetClothingEntity();
        stratum.setSet(savedSet);
        stratum.setItem("Носки");
        stratum.setQuery("Носки мужские");
        stratum.setCount(2);              // всего 2 карточки — быстрее
        stratum.setGender("m");
        stratum.setLayer((short) 0);
        stratum.setIsSeasonal(false);
        stratum.setBaseShare(null);
        SetClothingEntity savedStratum = stratumRepository.save(stratum);

        try {
            // 2. Наполняем сет.
            int totalCards = fillService.fillSet(savedSet.getId());
            System.out.println(">>> Всего записано карточек: " + totalCards);

            assertTrue(totalCards > 0, "Ожидали хотя бы одну карточку");

            // 3. Проверяем Postgres: карточки привязаны к нашей страте.
            List<CardEntity> cards = cardRepository.findByStratumId(savedStratum.getId());
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
            // 5. Убираем за собой сет (страты и карточки Postgres удалятся каскадом).
            // Замеры/снимки в ClickHouse останутся — они append-only, это норма для теста.
            setRepository.deleteById(savedSet.getId());
        }
    }
}
