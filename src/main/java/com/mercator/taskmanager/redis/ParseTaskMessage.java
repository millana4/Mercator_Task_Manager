package com.mercator.taskmanager.redis;

import lombok.Data;

import java.util.UUID;

/**
 * Задача обхода: перепарсить карточку по sku.
 * Отправляется в parse:tasks:<маркетплейс>:<гео>.
 * card_id — наша карточка (для привязки замера), sku — что парсить.
 * marketplace — эхо-поле: парсер вернёт его в результате, чтобы ядро
 * знало, из какой очереди пришёл ответ.
 * full_content — пометка «полный разбор»: парсер дождётся полного блока
 * характеристик (добор content_txt, референс ниши). null/false — обычный обход.
 */
@Data
public class ParseTaskMessage {
    private UUID taskId;
    private UUID cardId;
    private String sku;
    private String geo;
    private String marketplace;
    private Boolean fullContent;
}