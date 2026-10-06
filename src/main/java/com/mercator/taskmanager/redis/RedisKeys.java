package com.mercator.taskmanager.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Строит ключи Redis-списков из префиксов (application.yaml), кода
 * маркетплейса и кода гео: "<prefix>:<маркетплейс>:<гео>", например
 * "parse:tasks:ozon:spb".
 *
 * Разделение по маркетплейсам позволяет настроить каждый парсер на свой
 * набор площадок: воркер делает BRPOP только по тем ключам, которые ему
 * разрешены в .env.
 */
@Component
public class RedisKeys {

    private final String parseTasksPrefix;
    private final String parseResultsPrefix;
    private final String selectTasksPrefix;
    private final String selectResultsPrefix;
    private final String searchTasksPrefix;
    private final String searchResultsPrefix;

    public RedisKeys(
            @Value("${taskmanager.redis.parse-tasks-prefix}") String parseTasksPrefix,
            @Value("${taskmanager.redis.parse-results-prefix}") String parseResultsPrefix,
            @Value("${taskmanager.redis.select-tasks-prefix}") String selectTasksPrefix,
            @Value("${taskmanager.redis.select-results-prefix}") String selectResultsPrefix,
            @Value("${taskmanager.redis.search-tasks-prefix:search:tasks}") String searchTasksPrefix,
            @Value("${taskmanager.redis.search-results-prefix:search:results}") String searchResultsPrefix) {
        this.searchTasksPrefix = searchTasksPrefix;
        this.searchResultsPrefix = searchResultsPrefix;
        this.parseTasksPrefix = parseTasksPrefix;
        this.parseResultsPrefix = parseResultsPrefix;
        this.selectTasksPrefix = selectTasksPrefix;
        this.selectResultsPrefix = selectResultsPrefix;
    }

    private String key(String prefix, String marketplace, String geo) {
        return prefix + ":" + MarketplaceTopics.codeOf(marketplace) + ":" + GeoTopics.codeOf(geo);
    }

    public String parseTasks(String marketplace, String geo) {
        return key(parseTasksPrefix, marketplace, geo);
    }

    public String parseResults(String marketplace, String geo) {
        return key(parseResultsPrefix, marketplace, geo);
    }

    public String selectTasks(String marketplace, String geo) {
        return key(selectTasksPrefix, marketplace, geo);
    }

    public String selectResults(String marketplace, String geo) {
        return key(selectResultsPrefix, marketplace, geo);
    }

    public String searchTasks(String marketplace, String geo) {
        return key(searchTasksPrefix, marketplace, geo);
    }

    public String searchResults(String marketplace, String geo) {
        return key(searchResultsPrefix, marketplace, geo);
    }
}
