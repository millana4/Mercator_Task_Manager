package com.mercator.taskmanager.entity;

/**
 * Статусы ниши — шаги её обработки.
 * Совпадают с ограничением niche_status_check в базе.
 */
public final class NicheStatus {

    public static final String NEW = "new";                            // задание принято
    public static final String REFERENCE_PARSING = "reference_parsing"; // парсинг референса
    public static final String QUERY_GENERATION = "query_generation";   // запрос к нейросети
    public static final String SELECTING = "selecting";                 // подбор кандидатов
    public static final String RANKING = "ranking";                     // эмбеддинги и ранжирование
    public static final String ACTIVE = "active";                       // идёт сбор данных
    public static final String FAILED = "failed";                       // ошибка
    public static final String DELETED = "deleted";                     // ниша удалена

    private NicheStatus() {
    }
}