package com.mercator.taskmanager.redis;

import lombok.Data;

import java.util.UUID;

/**
 * Задача снимка поисковой выдачи по страте (очередь search:tasks:<маркетплейс>:<гео>).
 * Контракт с парсером (JSON, snake_case): парсер открывает выдачу по query,
 * в карточки не заходит и возвращает эхом task_id, set_id, stratum_id, geo,
 * marketplace, category, query, run_at.
 * run_at - плановое время прогона (ISO с поясом), общее для всех страт прогона.
 */
@Data
public class SearchTaskMessage {
    private UUID taskId;
    private UUID setId;
    private UUID stratumId;
    private String geo;
    private String marketplace;
    private String category;
    private String query;
    private String runAt;
}