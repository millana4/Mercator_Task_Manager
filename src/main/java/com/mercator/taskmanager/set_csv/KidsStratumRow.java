package com.mercator.taskmanager.set_csv;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Одна страта иерархического CSV: предмет вместе с признаками
 * подкатегории, к которой он относится. Количество уже рассчитано
 * (итог подкатегории × доля запроса) — сама доля дальше не нужна.
 */
@Data
public class KidsStratumRow {

    // Признаки подкатегории
    private String subcategoryTitle;  // "Домашняя одежда"
    private String segment;           // f / m / baby
    private Short layer;              // 0..3

    // Признаки страты
    private String item;              // "Пижама"
    private String query;             // "Пижама для девочки"
    private Integer count;            // рассчитанное количество карточек
    private Boolean isSeasonal;
    private BigDecimal baseShare;
}