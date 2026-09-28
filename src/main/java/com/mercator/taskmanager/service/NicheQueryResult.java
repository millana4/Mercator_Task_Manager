package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.StratumEntity;

import java.util.List;

/**
 * Результат генерации запроса ниши.
 *
 * @param searchQuery поисковый запрос для строки поиска маркетплейса
 * @param strata      страты, которые предложила нейросеть (может быть пусто)
 * @param model       модель, ответившая на запрос
 * @param tokens      total_tokens
 */
public record NicheQueryResult(String searchQuery,
                               List<StratumEntity> strata,
                               String model,
                               int tokens) {
}