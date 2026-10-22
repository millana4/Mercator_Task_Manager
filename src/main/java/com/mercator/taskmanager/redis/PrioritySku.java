package com.mercator.taskmanager.redis;

/**
 * Элемент списка приоритетных SKU задачи подбора: SKU из поисковой выдачи
 * страты и в скольких прогонах выдачи (разных run_at) он встретился за окно.
 * Парсер ставит такие SKU первыми среди кандидатов страницы (по убыванию count).
 */
public record PrioritySku(String sku, long count) {
}