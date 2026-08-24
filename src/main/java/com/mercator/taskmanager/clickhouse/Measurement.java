package com.mercator.taskmanager.clickhouse;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Один замер временного ряда — строка таблицы measurement в ClickHouse.
 * Часто меняющийся «пульс» карточки: цены, количество, рейтинг, число отзывов.
 *
 * @Builder (Lombok) позволяет собирать объект по-полю через .builder() —
 * удобно, когда часть полей может отсутствовать (Nullable в ClickHouse).
 */
@Data
@Builder
public class Measurement {
    private UUID cardId;
    private String sku;
    private OffsetDateTime parsedAt;
    private String geo;

    private Double cardPrice;
    private Double price;
    private Double originalPrice;
    private Integer quantity;
    private Float rating;
    private Integer reviewsCount;
    private String parserId;
}