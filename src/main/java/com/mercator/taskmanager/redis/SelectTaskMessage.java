package com.mercator.taskmanager.redis;

import com.mercator.taskmanager.contract.Ozon.ExcludedCard;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Задача подбора, отправляемая в топик select.tasks.
 * Это контракт между таск-менеджером и парсером — парсер читает
 * ровно эти поля (JSON, snake_case).
 *
 * task_id — сквозной id для трассировки в логах обоих сервисов.
 * stratum_id — привязка: парсер вернёт его в результате, чтобы
 * таск-менеджер знал, к какой страте относятся карточки.
 * geo — регион (для замера).
 * Остальное — параметры подбора (как в HTTP-контракте select).
 */
@Data
public class SelectTaskMessage {
    private UUID taskId;
    private UUID setId;
    private UUID stratumId;
    private String geo;
    private String marketplace;
    private String category;

    private String query;
    private Integer count;
    private Boolean isSeasonal;
    private Double baseShare;
    private List<ExcludedCard> exclude;
}
