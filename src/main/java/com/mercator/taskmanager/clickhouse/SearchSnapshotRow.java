package com.mercator.taskmanager.clickhouse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Строка таблицы search_snapshot: одна плитка выдачи одного прогона по страте. */
public record SearchSnapshotRow(
        OffsetDateTime runAt, OffsetDateTime parsedAt,
        String marketplace, String geo, String category,
        UUID setId, UUID stratumId, String query,
        int position, String sku, String name, String url,
        String brand, boolean brandVerified,
        Double price, Double originalPrice, Integer discountPct, Integer quantity,
        Double rating, Integer reviews,
        List<String> badges, List<String> labels,
        String parserId, UUID taskId) {
}