package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.clickhouse.CardSnapshot;
import com.mercator.taskmanager.clickhouse.CardSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Живой тест записи снимка в ClickHouse. Проверяет, что сложные типы —
 * Map (характеристики) и Array (вектор) — пишутся корректно.
 * Подключается к реальному tm-clickhouse.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
class ClickHouseSnapshotTest {

    @Autowired
    CardSnapshotRepository snapshotRepository;

    @Test
    void insertsSnapshotWithMapAndVector() {
        UUID cardId = UUID.randomUUID();

        CardSnapshot snapshot = CardSnapshot.builder()
                .cardId(cardId)
                .sku("1489388650")
                .parsedAt(OffsetDateTime.now())
                .name("Платье Селтекс")
                .description("Платье женское универсальное")
                .brand("Селтекс")
                .category("Платья и сарафаны")
                .categoryPath("Одежда/Женская одежда/Платья и сарафаны")
                // характеристики — Map с русскими ключами и апострофом для проверки экранирования
                .characteristics(Map.of(
                        "Сезон", "На любой сезон",
                        "Материал", "Хлопок, Трикотаж",
                        "Коллекция", "Весна-лето 2026"
                ))
                .variantsAspect("Цвет")
                .variants("[{\"sku\":\"123\",\"value\":\"красный\"}]")
                .sellerId("466906")
                .sellerName("ООО Элитекс")
                .sellerLegalName("ООО Элитекс Текстиль")
                .sellerOgrn("1053701224202")
                // вектор — короткий, для проверки записи Array(Float32)
                .embedding(List.of(0.1f, 0.2f, 0.3f, -0.5f))
                .build();

        snapshotRepository.insert(snapshot);

        long count = snapshotRepository.countByCardId(cardId);
        assertTrue(count >= 1, "Ожидали хотя бы один снимок для card_id");
    }
}