package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.clickhouse.Measurement;
import com.mercator.taskmanager.clickhouse.MeasurementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Живой тест записи в ClickHouse. Подключается к реальному контейнеру
 * tm-clickhouse (на 8123, как в application.yml), пишет замер и проверяет,
 * что он записался. Требует запущенного docker compose.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
class ClickHouseMeasurementTest {

    @Autowired
    MeasurementRepository measurementRepository;

    @Test
    void insertsAndCountsMeasurement() {
        UUID cardId = UUID.randomUUID();  // случайный id — чтобы не пересекаться с другими прогонами

        Measurement m = Measurement.builder()
                .cardId(cardId)
                .sku("1489388650")
                .parsedAt(OffsetDateTime.now())
                .geo("Санкт-Петербург")
                .cardPrice(1080.0)
                .price(1200.0)
                .originalPrice(3248.0)
                .quantity(157)
                .rating(4.9f)
                .reviewsCount(18)
                .build();

        measurementRepository.insert(m);

        long count = measurementRepository.countByCardId(cardId);
        assertTrue(count >= 1, "Ожидали хотя бы один замер для card_id");
    }
}