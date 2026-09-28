package com.mercator.taskmanager.controller;

import java.util.UUID;

/**
 * Задание ниши от сервиса аналитики.
 * marketplace / category / geo — названия из справочников ядра
 * (ozon, clothing, Санкт-Петербург). Обязательно description или referenceUrl.
 */
public record NicheCreateRequest(
        UUID externalId,
        String marketplace,
        String category,
        String geo,
        String description,
        String referenceUrl) {
}