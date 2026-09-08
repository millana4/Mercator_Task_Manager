package com.mercator.taskmanager.redis;

import lombok.Data;

import java.util.UUID;

/**
 * Задача обхода: перепарсить карточку по sku.
 * Отправляется в parse:tasks:<маркетплейс>:<гео>.
 * card_id — наша карточка (для привязки замера), sku — что парсить.
 * marketplace — эхо-поле: парсер вернёт его в результате, чтобы ядро
 * знало, из какой очереди пришёл ответ.
 */
@Data
public class ParseTaskMessage {
    private UUID taskId;
    private UUID cardId;
    private String sku;
    private String geo;
    private String marketplace;
}