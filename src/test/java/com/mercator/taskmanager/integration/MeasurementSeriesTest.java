package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.clickhouse.Measurement;
import com.mercator.taskmanager.clickhouse.MeasurementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Чтение временного ряда: пишем 3 замера одной карточки в разное время,
 * читаем ряд — должны прийти все 3 по возрастанию времени.
 * Живой ClickHouse.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
class MeasurementSeriesTest {

    @Autowired MeasurementRepository repo;

    @Test
    void readsTimeSeries() {
        UUID cardId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        // 3 замера с разными ценами и временем.
        repo.insert(measurement(cardId, now.minusHours(2), 1000.0));
        repo.insert(measurement(cardId, now.minusHours(1), 1100.0));
        repo.insert(measurement(cardId, now, 1200.0));

        List<Measurement> series = repo.findByCardId(cardId);

        assertEquals(3, series.size(), "Должно быть 3 замера");
        // Ряд по возрастанию времени: цены в порядке 1000 -> 1100 -> 1200.
        assertEquals(1000.0, series.get(0).getPrice());
        assertEquals(1200.0, series.get(2).getPrice());
    }

    private Measurement measurement(UUID cardId, OffsetDateTime at, Double price) {
        return Measurement.builder()
                .cardId(cardId).sku("SKU1").parsedAt(at).geo("Санкт-Петербург")
                .price(price).cardPrice(price).originalPrice(price)
                .quantity(10).rating(4.5f).reviewsCount(100)
                .build();
    }
}
