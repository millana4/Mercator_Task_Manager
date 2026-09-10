package com.mercator.taskmanager.redis;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Результат подбора из топика select.results.* (присылает парсер).
 *
 * Эхо трассировки и привязки: task_id и stratum_id — те же, что были
 * в задаче; парсер возвращает их, чтобы таск-менеджер знал, к какой
 * страте относятся карточки, не храня состояние. geo — для замера.
 *
 * cards — подобранные карточки. error — текст ошибки, если подбор
 * не удался (тогда cards пустой или null).
 */
@Data
public class SelectResultMessage {
    private UUID taskId;
    private UUID setId;
    private UUID stratumId;
    private String geo;
    private String parserId;
    private Boolean ok;
    private Integer requestedCount;
    private Integer foundCount;
    private List<OzonCard> cards;
    private String error;
}
